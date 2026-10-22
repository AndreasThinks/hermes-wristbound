#include "speaker.h"
#include "comm.h"
#include "model.h"

#if defined(WATCH_SPEAKER)

#if defined(PBL_PLATFORM_FLINT)
#define RING_SIZE (16 * 1024) // about 2 s: flint has a 64 KB app heap
#else
#define RING_SIZE (48 * 1024) // about 6 s
#endif
#define BYTES_PER_SEC 8000    // SpeakerPcmFormat_8kHz_8bit
#define VOLUME 100
#define PUMP_MS 50
#define PREPARE_TIMEOUT_MS 60000 // download and conversion on the phone
#define STALL_TIMEOUT_MS 10000
#define DRAIN_TIMEOUT_MS 3000
#define SPEAKER_STALL_MS 5000 // the speaker took nothing for this long while it had room before
#define MAX_WRITE 4096 // speaker_stream_write faults the app above 8192 bytes per call

static void (*s_changed)(void);
static WatchSpeakerState s_state;
static char s_message_id[MESSAGE_ID_LEN];
static char s_session_id[SESSION_ID_LEN];
static char s_label[DETAIL_LEN];
static char s_status[DETAIL_LEN];

static uint8_t *s_ring;
static uint32_t s_ring_size;
static uint32_t s_head; // next write
static uint32_t s_tail; // next read
static uint32_t s_fill;

static uint32_t s_total;     // bytes in the clip
static uint32_t s_received;  // bytes received, in order
static uint32_t s_requested; // bytes the phone may send so far
static bool s_stream_open;
static bool s_closing;

static AppTimer *s_timer;
static uint64_t s_last_activity_ms;
static uint64_t s_first_data_ms;
static uint64_t s_closing_ms;
static uint64_t s_last_pump_ms;
static uint64_t s_last_write_ms;

static uint64_t prv_now_ms(void) {
  time_t s;
  uint16_t ms;
  time_ms(&s, &ms);
  return (uint64_t)s * 1000 + ms;
}

// Milliseconds since [ref]. time_ms() is not strictly monotonic (its milliseconds can lag its
// seconds), so a reading slightly before [ref] counts as no time at all.
static uint64_t prv_since(uint64_t now, uint64_t ref) {
  return now > ref ? now - ref : 0;
}

static void prv_notify(void) {
  if (s_changed) {
    s_changed();
  }
}

static void prv_release(void) {
  if (s_timer) {
    app_timer_cancel(s_timer);
    s_timer = NULL;
  }
  free(s_ring);
  s_ring = NULL;
  s_fill = s_head = s_tail = 0;
  s_stream_open = false;
  s_closing = false;
}

static void prv_end(WatchSpeakerState state, const char *detail) {
  // Still preparing (no length yet) or not everything received: the phone may still be sending.
  bool unfinished = s_total == 0 || s_received < s_total;
  bool stop_speaker = s_stream_open || state == WSPK_FAILED;
  s_state = state; // first, so the speaker's finish callback for our own stop is ignored
  if (stop_speaker) {
    speaker_stop();
  }
  if (unfinished && s_message_id[0]) {
    comm_audio_stop(s_message_id); // the phone stops sending
  }
  prv_release();
  s_state = state;
  strncpy(s_status, detail, DETAIL_LEN - 1);
  s_status[DETAIL_LEN - 1] = '\0';
  APP_LOG(APP_LOG_LEVEL_INFO, "watch speaker %s: %s", s_message_id, detail);
  if (state == WSPK_FAILED) {
    vibes_short_pulse();
  }
  prv_notify();
}

// Ask the phone for as much as the ring has room for, in steps of at least a quarter ring so
// pulls do not cost a round trip per chunk.
static void prv_request_more(void) {
  if (!s_ring || s_requested >= s_total) {
    return;
  }
  uint32_t until = s_received + (s_ring_size - s_fill);
  if (until > s_total) {
    until = s_total;
  }
  bool idle = s_requested == s_received;
  if (until > s_requested && (idle || until - s_requested >= s_ring_size / 4 || until == s_total)) {
    s_requested = until;
    comm_audio_pull(s_message_id, s_received, until - s_received);
  }
}

// How much to buffer before playing. Unknown link speed: most of the ring. A link faster than
// real time: 1.5 s. A slower one: enough that the rest arrives before the buffer runs dry.
static uint32_t prv_prebuffer(void) {
  uint32_t remaining = s_total - (s_received - s_fill);
  uint32_t cap = s_ring_size - wspk_chunk_size();
  uint32_t need = cap;
  uint64_t elapsed = s_first_data_ms ? prv_since(prv_now_ms(), s_first_data_ms) : 0;
  if (elapsed >= 1000) {
    uint32_t rate = (uint32_t)((uint64_t)s_received * 1000 / elapsed);
    if (rate >= BYTES_PER_SEC + BYTES_PER_SEC / 10) {
      need = BYTES_PER_SEC * 3 / 2;
    } else {
      need = (uint32_t)((uint64_t)remaining * (BYTES_PER_SEC - (rate < BYTES_PER_SEC ? rate : BYTES_PER_SEC)) /
                        BYTES_PER_SEC) + BYTES_PER_SEC / 2;
    }
  }
  if (need > cap) {
    need = cap;
  }
  if (need > remaining) {
    need = remaining;
  }
  return need;
}

static void prv_finished(SpeakerFinishReason reason, void *ctx) {
  s_stream_open = false;
  if (s_state != WSPK_PLAYING && s_state != WSPK_BUFFERING) {
    return; // paused or stopped by us
  }
  switch (reason) {
    case SpeakerFinishReasonPreempted:
      prv_end(WSPK_FAILED, "Interrupted by the watch");
      break;
    case SpeakerFinishReasonError:
      prv_end(WSPK_FAILED, "Watch speaker error");
      break;
    default:
      if (s_closing || (s_received >= s_total && s_fill == 0)) {
        prv_end(WSPK_DONE, "Finished on watch");
      }
      // Otherwise the speaker ran dry while data is still coming: keep buffering.
      break;
  }
}

static void prv_pump(void);

static void prv_timer_cb(void *ctx) {
  s_timer = NULL;
  prv_pump();
}

static void prv_schedule(void) {
  if (!s_timer && s_ring) {
    s_timer = app_timer_register(PUMP_MS, prv_timer_cb, NULL);
  }
}

// The phone sets the watch's clock when it connects, so wall time can jump while a clip streams.
// A gap between pumps far longer than the timer's period is a jump: move the reference times by
// it, so it neither trips a timeout nor skews the measured link rate.
static void prv_follow_clock(uint64_t now) {
  if (s_last_pump_ms) {
    int64_t skew = (int64_t)(now - s_last_pump_ms) - PUMP_MS;
    if (skew > 2000 || skew < -2000) {
      if (s_last_activity_ms) {
        s_last_activity_ms += skew;
      }
      if (s_first_data_ms) {
        s_first_data_ms += skew;
      }
      if (s_closing_ms) {
        s_closing_ms += skew;
      }
      if (s_last_write_ms) {
        s_last_write_ms += skew;
      }
    }
  }
  s_last_pump_ms = now;
}

static void prv_pump(void) {
  if (!s_ring) {
    return;
  }
  uint64_t now = prv_now_ms();
  prv_follow_clock(now);
  if (s_state == WSPK_PREPARING) {
    if (prv_since(now, s_last_activity_ms) > PREPARE_TIMEOUT_MS) {
      prv_end(WSPK_FAILED, "Phone did not answer");
      return;
    }
    prv_schedule();
    return;
  }
  if (s_state == WSPK_BUFFERING) {
    if (s_fill >= prv_prebuffer() && s_fill > 0) {
      if (!s_stream_open) {
        if (speaker_is_muted()) {
          prv_end(WSPK_FAILED, "Watch speaker is muted");
          return;
        }
        if (!speaker_stream_open(SpeakerPcmFormat_8kHz_8bit, VOLUME)) {
          prv_end(WSPK_FAILED, "Watch speaker is busy");
          return;
        }
        s_stream_open = true;
      }
      s_state = WSPK_PLAYING;
      s_last_write_ms = now;
      prv_notify();
    } else if (s_received < s_total && prv_since(now, s_last_activity_ms) > STALL_TIMEOUT_MS) {
      prv_end(WSPK_FAILED, connection_service_peek_pebble_app_connection() ? "Phone stopped sending"
                                                                            : "Phone disconnected");
      return;
    } else {
      prv_notify(); // buffering progress
    }
  }
  if (s_state == WSPK_PLAYING) {
    while (s_fill > 0) {
      uint32_t run = s_ring_size - s_tail;
      if (run > s_fill) {
        run = s_fill;
      }
      if (run > MAX_WRITE) {
        run = MAX_WRITE;
      }
      uint32_t written = speaker_stream_write(s_ring + s_tail, run);
      if (written == 0) {
        break;
      }
      s_tail = (s_tail + written) % s_ring_size;
      s_fill -= written;
      s_last_write_ms = now;
    }
    // A speaker that never takes data would leave the clip "playing" forever. Its queue holds
    // well under a second, so seconds without room means it is not playing.
    if (s_fill > 0 && prv_since(now, s_last_write_ms) > SPEAKER_STALL_MS) {
      prv_end(WSPK_FAILED, "Watch speaker is not playing");
      return;
    }
    prv_request_more();
    if (s_fill == 0) {
      if (s_received >= s_total) {
        if (!s_closing) {
          s_closing = true;
          s_closing_ms = now;
          speaker_stream_close(); // plays out what the speaker already has
        } else if (prv_since(now, s_closing_ms) > DRAIN_TIMEOUT_MS &&
                   (speaker_get_status() == SpeakerStatusIdle ||
                    prv_since(now, s_closing_ms) > DRAIN_TIMEOUT_MS + SPEAKER_STALL_MS)) {
          prv_end(WSPK_DONE, "Finished on watch"); // in case no finish callback came
          return;
        }
      } else {
        s_state = WSPK_BUFFERING; // ran dry: the link is slower than playback
        prv_notify();
      }
    }
  }
  prv_schedule();
}

void wspk_init(void (*changed)(void)) {
  s_changed = changed;
  speaker_set_finish_callback(prv_finished, NULL);
}

void wspk_deinit(void) {
  if (s_ring) {
    s_state = WSPK_IDLE;
    speaker_stop();
  }
  speaker_set_finish_callback(NULL, NULL);
  prv_release();
}

bool wspk_supported(void) {
  return true;
}

uint16_t wspk_chunk_size(void) {
  return comm_inbox_size() > 160 ? comm_inbox_size() - 160 : 256;
}

void wspk_play(const char *session_id, const char *message_id) {
  if (s_ring) {
    s_state = WSPK_IDLE;
    speaker_stop();
    if (s_total == 0 || s_received < s_total) {
      comm_audio_stop(s_message_id);
    }
    prv_release();
  }
  strncpy(s_message_id, message_id, MESSAGE_ID_LEN - 1);
  s_message_id[MESSAGE_ID_LEN - 1] = '\0';
  strncpy(s_session_id, session_id, SESSION_ID_LEN - 1);
  s_session_id[SESSION_ID_LEN - 1] = '\0';
  s_label[0] = '\0';
  s_total = s_received = s_requested = 0;
  s_first_data_ms = s_closing_ms = s_last_pump_ms = s_last_write_ms = 0;
  s_state = WSPK_PREPARING;
  if (speaker_is_muted()) {
    prv_end(WSPK_FAILED, "Watch speaker is muted");
    return;
  }
  s_ring_size = RING_SIZE;
  s_ring = malloc(s_ring_size);
  if (!s_ring) {
    s_ring_size = RING_SIZE / 2;
    s_ring = malloc(s_ring_size);
  }
  if (!s_ring) {
    prv_end(WSPK_FAILED, "Not enough memory");
    return;
  }
  strncpy(s_status, "Preparing audio", DETAIL_LEN - 1);
  s_last_activity_ms = prv_now_ms();
  comm_play_watch(session_id, message_id, wspk_chunk_size());
  prv_notify();
  prv_schedule();
}

void wspk_on_start(const char *message_id, uint32_t length, const char *detail) {
  if (s_state != WSPK_PREPARING || strcmp(message_id, s_message_id) != 0) {
    return;
  }
  if (length == 0) {
    prv_end(WSPK_FAILED, detail[0] ? detail : "No audio");
    return;
  }
  s_total = length;
  strncpy(s_label, detail, DETAIL_LEN - 1);
  s_label[DETAIL_LEN - 1] = '\0';
  s_state = WSPK_BUFFERING;
  s_last_activity_ms = prv_now_ms();
  prv_request_more();
  prv_notify();
}

void wspk_on_data(const char *message_id, uint32_t offset, const uint8_t *data, uint16_t length) {
  if (!s_ring || s_total == 0 || strcmp(message_id, s_message_id) != 0) {
    return;
  }
  if (offset != s_received) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "speaker data at %lu, expected %lu", (unsigned long)offset,
            (unsigned long)s_received);
    return;
  }
  uint32_t room = s_ring_size - s_fill;
  uint32_t n = length < room ? length : room;
  for (uint32_t i = 0; i < n;) {
    uint32_t run = s_ring_size - s_head;
    if (run > n - i) {
      run = n - i;
    }
    memcpy(s_ring + s_head, data + i, run);
    s_head = (s_head + run) % s_ring_size;
    i += run;
  }
  s_fill += n;
  s_received += n;
  s_last_activity_ms = prv_now_ms();
  if (!s_first_data_ms) {
    s_first_data_ms = s_last_activity_ms;
  }
  if (s_state == WSPK_PAUSED) {
    prv_request_more();
    return;
  }
  prv_pump();
}

void wspk_pause(void) {
  if (s_state != WSPK_PLAYING && s_state != WSPK_BUFFERING) {
    return;
  }
  // The SDK has no pause; stopping drops only the little the speaker had queued.
  if (s_stream_open) {
    s_state = WSPK_PAUSED; // before speaker_stop, so its callback is ignored
    speaker_stop();
    s_stream_open = false;
  }
  s_state = WSPK_PAUSED;
  s_closing = false;
  prv_notify();
}

void wspk_resume(void) {
  if (s_state != WSPK_PAUSED) {
    return;
  }
  s_state = WSPK_BUFFERING;
  s_last_activity_ms = prv_now_ms();
  prv_pump();
  prv_notify();
}

void wspk_stop(void) {
  if (s_state == WSPK_IDLE) {
    return;
  }
  if (s_ring) {
    prv_end(WSPK_DONE, "Stopped on watch");
  } else {
    s_state = WSPK_IDLE;
    prv_notify();
  }
}

WatchSpeakerState wspk_state_for(const char *message_id) {
  return strcmp(message_id, s_message_id) == 0 ? s_state : WSPK_IDLE;
}

const char *wspk_label_for(const char *message_id) {
  if (s_state == WSPK_IDLE || strcmp(message_id, s_message_id) != 0) {
    return NULL;
  }
  static char buf[DETAIL_LEN + 8];
  switch (s_state) {
    case WSPK_BUFFERING: {
      uint32_t need = prv_prebuffer();
      uint32_t pct = need ? s_fill * 100 / need : 100;
      snprintf(buf, sizeof(buf), "Buffering %lu%%", (unsigned long)(pct > 99 ? 99 : pct));
      return buf;
    }
    case WSPK_PLAYING:
      snprintf(buf, sizeof(buf), "Watch: %s", s_label[0] ? s_label : "playing");
      return buf;
    case WSPK_PAUSED:
      return "Paused on watch";
    default:
      return s_status;
  }
}

#else // no speaker on this platform

void wspk_init(void (*changed)(void)) {}
void wspk_deinit(void) {}
bool wspk_supported(void) { return false; }
void wspk_play(const char *session_id, const char *message_id) {}
void wspk_pause(void) {}
void wspk_resume(void) {}
void wspk_stop(void) {}
WatchSpeakerState wspk_state_for(const char *message_id) { return WSPK_IDLE; }
const char *wspk_label_for(const char *message_id) { return NULL; }
uint16_t wspk_chunk_size(void) { return 0; }
void wspk_on_start(const char *message_id, uint32_t length, const char *detail) {}
void wspk_on_data(const char *message_id, uint32_t offset, const uint8_t *data, uint16_t length) {}

#endif
