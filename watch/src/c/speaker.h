#pragma once

#include <pebble.h>

// Playing a Hermes reply on the watch's own speaker (Pebble Time 2, Pebble 2 Duo, and the
// gabbro platform, which has the same Speaker API). The phone decodes the reply's audio to
// 8 kHz signed 8-bit PCM and streams it; this module pulls it as it has room, buffers enough
// to play smoothly, and feeds the SDK's speaker stream. Other platforms compile it out.
#if defined(PBL_PLATFORM_EMERY) || defined(PBL_PLATFORM_FLINT) || defined(PBL_PLATFORM_GABBRO)
#define WATCH_SPEAKER 1
#endif

typedef enum {
  WSPK_IDLE = 0,
  WSPK_PREPARING, // phone is fetching and converting the audio
  WSPK_BUFFERING, // receiving; not enough buffered to play smoothly yet
  WSPK_PLAYING,
  WSPK_PAUSED,
  WSPK_DONE,
  WSPK_FAILED,
} WatchSpeakerState;

void wspk_init(void (*changed)(void));
void wspk_deinit(void);
bool wspk_supported(void);

void wspk_play(const char *session_id, const char *message_id);
void wspk_pause(void);
void wspk_resume(void);
void wspk_stop(void);

// State and status text for [message_id]; idle and NULL when another reply (or none) is on the speaker.
WatchSpeakerState wspk_state_for(const char *message_id);
const char *wspk_label_for(const char *message_id);

// Largest PCM chunk the phone may send in one AppMessage.
uint16_t wspk_chunk_size(void);

// From comm.c
void wspk_on_start(const char *message_id, uint32_t length, const char *detail);
void wspk_on_data(const char *message_id, uint32_t offset, const uint8_t *data, uint16_t length);
