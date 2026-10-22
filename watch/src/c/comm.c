#include "comm.h"
#include "model.h"

#if defined(PBL_PLATFORM_APLITE)
#define QUEUE_LEN 3
#define OUTGOING_TEXT_LEN 256
#else
#define QUEUE_LEN 6
#define OUTGOING_TEXT_LEN 512
#endif
#define MAX_RETRIES 3
#define RETRY_MS 600

typedef struct {
  Command cmd;
  char session_id[SESSION_ID_LEN];
  char message_id[MESSAGE_ID_LEN];
  char text[OUTGOING_TEXT_LEN];
  uint8_t route;
} Pending;

static CommHandlers s_handlers;
static Pending s_queue[QUEUE_LEN];
static int s_head;
static int s_count;
static bool s_in_flight;
static int s_retries;
static AppTimer *s_retry_timer;

static void prv_pump(void);

static void prv_set_conn(ConnState state, const char *detail) {
  g_model.conn = state;
  strncpy(g_model.conn_detail, detail ? detail : "", DETAIL_LEN - 1);
  g_model.conn_detail[DETAIL_LEN - 1] = '\0';
  if (s_handlers.status_changed) {
    s_handlers.status_changed();
  }
}

static void prv_enqueue(const Pending *p) {
  if (s_count == QUEUE_LEN) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "outbox full, refusing command %d", (int)p->cmd);
    if (p->cmd == CMD_SEND || p->cmd == CMD_NEW_SESSION) {
      g_model.submit = SUBMIT_FAILED;
      strncpy(g_model.submit_detail, "Watch busy, try again", DETAIL_LEN - 1);
      if (s_handlers.submit_changed) {
        s_handlers.submit_changed();
      }
    }
    return;
  }
  s_queue[(s_head + s_count) % QUEUE_LEN] = *p;
  s_count++;
  prv_pump();
}

static void prv_retry_cb(void *ctx) {
  s_retry_timer = NULL;
  prv_pump();
}

static void prv_pump(void) {
  if (s_in_flight || s_count == 0 || s_retry_timer) {
    return;
  }
  const Pending *p = &s_queue[s_head];
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    s_retry_timer = app_timer_register(RETRY_MS, prv_retry_cb, NULL);
    return;
  }
  dict_write_uint8(iter, MESSAGE_KEY_Cmd, p->cmd);
  switch (p->cmd) {
    case CMD_HELLO:
      dict_write_uint16(iter, MESSAGE_KEY_TextBudget, TEXT_BUDGET);
      dict_write_uint8(iter, MESSAGE_KEY_ProtoVersion, PROTO_VERSION);
      dict_write_uint8(iter, MESSAGE_KEY_Filter, g_model.include_telegram);
      if (p->session_id[0]) {
        dict_write_cstring(iter, MESSAGE_KEY_SessionId, p->session_id);
      }
      break;
    case CMD_LIST_SESSIONS:
    case CMD_LIST_AUDIO:
      dict_write_uint8(iter, MESSAGE_KEY_Filter, g_model.include_telegram);
      break;
    case CMD_OPEN_SESSION:
      dict_write_cstring(iter, MESSAGE_KEY_SessionId, p->session_id);
      break;
    case CMD_SEND:
      dict_write_cstring(iter, MESSAGE_KEY_SessionId, p->session_id);
      dict_write_cstring(iter, MESSAGE_KEY_Text, p->text);
      break;
    case CMD_NEW_SESSION:
      dict_write_cstring(iter, MESSAGE_KEY_Text, p->text);
      break;
    case CMD_PLAY:
      dict_write_uint8(iter, MESSAGE_KEY_Route, p->route);
      // fallthrough
    case CMD_REPLAY:
    case CMD_OPEN_REPLY:
      dict_write_cstring(iter, MESSAGE_KEY_SessionId, p->session_id);
      dict_write_cstring(iter, MESSAGE_KEY_MessageId, p->message_id);
      break;
    default:
      break;
  }
  if (app_message_outbox_send() == APP_MSG_OK) {
    s_in_flight = true;
  } else {
    s_retry_timer = app_timer_register(RETRY_MS, prv_retry_cb, NULL);
  }
}

static void prv_pop(void) {
  s_head = (s_head + 1) % QUEUE_LEN;
  s_count--;
  s_retries = 0;
}

static void prv_outbox_sent(DictionaryIterator *iter, void *ctx) {
  s_in_flight = false;
  prv_pop();
  prv_pump();
}

static void prv_outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *ctx) {
  s_in_flight = false;
  APP_LOG(APP_LOG_LEVEL_WARNING, "outbox failed: %d", (int)reason);
  const Pending *p = &s_queue[s_head];
  if (++s_retries > MAX_RETRIES) {
    if (p->cmd == CMD_SEND || p->cmd == CMD_NEW_SESSION) {
      // A NACK or a closed connection means the phone never took it. A timeout may only have
      // lost the acknowledgement, so the message may be on its way to Hermes.
      bool maybe_delivered = reason == APP_MSG_SEND_TIMEOUT;
      g_model.submit = maybe_delivered ? SUBMIT_UNCERTAIN : SUBMIT_FAILED;
      strncpy(g_model.submit_detail, maybe_delivered ? "Check phone before resending"
                                                     : "Phone unreachable", DETAIL_LEN - 1);
      if (s_handlers.submit_changed) {
        s_handlers.submit_changed();
      }
    }
    prv_set_conn(CONN_OFFLINE, "Phone unreachable");
    prv_pop();
  }
  s_retry_timer = app_timer_register(RETRY_MS * s_retries + RETRY_MS, prv_retry_cb, NULL);
}

static const char *prv_str(DictionaryIterator *iter, uint32_t key) {
  Tuple *t = dict_find(iter, key);
  return (t && t->type == TUPLE_CSTRING) ? t->value->cstring : "";
}

static uint32_t prv_int(DictionaryIterator *iter, uint32_t key, uint32_t fallback) {
  Tuple *t = dict_find(iter, key);
  if (!t) {
    return fallback;
  }
  switch (t->length) {
    case 1: return t->type == TUPLE_INT ? (uint32_t)t->value->int8 : t->value->uint8;
    case 2: return t->type == TUPLE_INT ? (uint32_t)t->value->int16 : t->value->uint16;
    default: return t->type == TUPLE_INT ? (uint32_t)t->value->int32 : t->value->uint32;
  }
}

static void prv_copy(char *dst, const char *src, size_t len) {
  strncpy(dst, src, len - 1);
  dst[len - 1] = '\0';
}

static void prv_handle_session(DictionaryIterator *iter) {
  int index = prv_int(iter, MESSAGE_KEY_Index, 0);
  int total = prv_int(iter, MESSAGE_KEY_Total, 0);
  if (total > MAX_SESSIONS) {
    total = MAX_SESSIONS;
  }
  if (index == 0) {
    g_model.session_count = 0;
  }
  if (total == 0) {
    g_model.session_count = 0;
    g_model.sessions_loading = false;
  } else if (index < total) {
    Session *s = &g_model.sessions[index];
    prv_copy(s->id, prv_str(iter, MESSAGE_KEY_SessionId), SESSION_ID_LEN);
    prv_copy(s->title, prv_str(iter, MESSAGE_KEY_Title), TITLE_LEN);
    s->flags = prv_int(iter, MESSAGE_KEY_Flags, 0);
    g_model.session_count = index + 1;
    g_model.sessions_loading = index + 1 < total;
  }
  if (s_handlers.sessions_changed) {
    s_handlers.sessions_changed();
  }
}

static void prv_handle_audio(DictionaryIterator *iter) {
  int index = prv_int(iter, MESSAGE_KEY_Index, 0);
  int total = prv_int(iter, MESSAGE_KEY_Total, 0);
  if (total > MAX_AUDIO) {
    total = MAX_AUDIO;
  }
  if (index == 0 || total == 0) {
    g_model.audio_count = 0;
  }
  if (total > 0 && index < total) {
    AudioItem *a = &g_model.audio[index];
    prv_copy(a->session_id, prv_str(iter, MESSAGE_KEY_SessionId), SESSION_ID_LEN);
    prv_copy(a->message_id, prv_str(iter, MESSAGE_KEY_MessageId), MESSAGE_ID_LEN);
    prv_copy(a->title, prv_str(iter, MESSAGE_KEY_Title), TITLE_LEN);
    prv_copy(a->preview, prv_str(iter, MESSAGE_KEY_Text), PREVIEW_LEN);
    a->time = prv_int(iter, MESSAGE_KEY_Time, 0);
    a->flags = prv_int(iter, MESSAGE_KEY_Flags, 0);
    g_model.audio_count = index + 1;
  }
  g_model.audio_loading = total > 0 && index + 1 < total;
  if (s_handlers.audio_changed) {
    s_handlers.audio_changed();
  }
}

static void prv_handle_message(DictionaryIterator *iter) {
  if (strcmp(prv_str(iter, MESSAGE_KEY_SessionId), g_model.open_session_id) != 0) {
    return; // late page for a session the user already left
  }
  int index = prv_int(iter, MESSAGE_KEY_Index, 0);
  int total = prv_int(iter, MESSAGE_KEY_Total, 0);
  if (index == 0) {
    model_clear_messages();
  }
  if (total > 0 && index < total) {
    model_set_message(index, prv_str(iter, MESSAGE_KEY_MessageId),
                      prv_int(iter, MESSAGE_KEY_Role, ROLE_ASSISTANT),
                      prv_int(iter, MESSAGE_KEY_Flags, 0), prv_str(iter, MESSAGE_KEY_Text));
  }
  g_model.messages_loading = total > 0 && index + 1 < total;
  if (s_handlers.messages_changed) {
    s_handlers.messages_changed();
  }
}

static void prv_inbox_received(DictionaryIterator *iter, void *ctx) {
  MessageType type = prv_int(iter, MESSAGE_KEY_Type, 0);
  switch (type) {
    case TYPE_SESSION:
      prv_handle_session(iter);
      break;
    case TYPE_MESSAGE:
      prv_handle_message(iter);
      break;
    case TYPE_AUDIO:
      prv_handle_audio(iter);
      break;
    case TYPE_STATUS:
      prv_set_conn(prv_int(iter, MESSAGE_KEY_State, CONN_ERROR), prv_str(iter, MESSAGE_KEY_Detail));
      break;
    case TYPE_REPLY:
      if (s_handlers.reply_received) {
        s_handlers.reply_received(prv_str(iter, MESSAGE_KEY_SessionId),
                                  prv_str(iter, MESSAGE_KEY_MessageId),
                                  prv_str(iter, MESSAGE_KEY_Title),
                                  prv_str(iter, MESSAGE_KEY_Text),
                                  prv_int(iter, MESSAGE_KEY_Flags, 0));
      }
      break;
    case TYPE_PLAYBACK:
      prv_copy(g_model.playback_message_id, prv_str(iter, MESSAGE_KEY_MessageId), MESSAGE_ID_LEN);
      g_model.playback = prv_int(iter, MESSAGE_KEY_State, PLAYBACK_IDLE);
      prv_copy(g_model.playback_detail, prv_str(iter, MESSAGE_KEY_Detail), DETAIL_LEN);
      if (s_handlers.playback_changed) {
        s_handlers.playback_changed();
      }
      break;
    case TYPE_SUBMIT: {
      const char *sid = prv_str(iter, MESSAGE_KEY_SessionId);
      if (sid[0] && strcmp(sid, g_model.open_session_id) != 0) {
        break;
      }
      g_model.submit = prv_int(iter, MESSAGE_KEY_State, SUBMIT_NONE);
      prv_copy(g_model.submit_detail, prv_str(iter, MESSAGE_KEY_Detail), DETAIL_LEN);
      if (s_handlers.submit_changed) {
        s_handlers.submit_changed();
      }
      break;
    }
    case TYPE_SESSION_OPENED:
      prv_copy(g_model.open_session_id, prv_str(iter, MESSAGE_KEY_SessionId), SESSION_ID_LEN);
      prv_copy(g_model.open_session_title, prv_str(iter, MESSAGE_KEY_Title), TITLE_LEN);
      g_model.open_session_flags = prv_int(iter, MESSAGE_KEY_Flags, 0);
      model_clear_messages();
      g_model.messages_loading = true;
      model_save_prefs();
      if (s_handlers.session_opened) {
        s_handlers.session_opened();
      }
      break;
    default:
      APP_LOG(APP_LOG_LEVEL_WARNING, "unknown message type %d", (int)type);
      break;
  }
}

static void prv_inbox_dropped(AppMessageResult reason, void *ctx) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "inbox dropped: %d", (int)reason);
}

void comm_init(CommHandlers handlers) {
  s_handlers = handlers;
  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_inbox_dropped(prv_inbox_dropped);
  app_message_register_outbox_sent(prv_outbox_sent);
  app_message_register_outbox_failed(prv_outbox_failed);
  uint32_t inbox = TEXT_BUDGET + 512;
  if (inbox > app_message_inbox_size_maximum()) {
    inbox = app_message_inbox_size_maximum();
  }
  uint32_t outbox = OUTGOING_TEXT_LEN + SESSION_ID_LEN + 128;
  if (outbox > app_message_outbox_size_maximum()) {
    outbox = app_message_outbox_size_maximum();
  }
  app_message_open(inbox, outbox);
}

void comm_deinit(void) {
  if (s_retry_timer) {
    app_timer_cancel(s_retry_timer);
    s_retry_timer = NULL;
  }
  app_message_deregister_callbacks();
}

static Pending prv_pending(Command cmd) {
  Pending p;
  memset(&p, 0, sizeof(p));
  p.cmd = cmd;
  return p;
}

void comm_hello(void) {
  Pending p = prv_pending(CMD_HELLO);
  prv_copy(p.session_id, g_model.open_session_id, SESSION_ID_LEN);
  prv_enqueue(&p);
}

void comm_list_sessions(void) {
  g_model.sessions_loading = true;
  Pending p = prv_pending(CMD_LIST_SESSIONS);
  prv_enqueue(&p);
}

void comm_open_session(const char *session_id) {
  Pending p = prv_pending(CMD_OPEN_SESSION);
  prv_copy(p.session_id, session_id, SESSION_ID_LEN);
  prv_enqueue(&p);
}

void comm_send(const char *session_id, const char *text) {
  Pending p = prv_pending(CMD_SEND);
  prv_copy(p.session_id, session_id, SESSION_ID_LEN);
  prv_copy(p.text, text, OUTGOING_TEXT_LEN);
  g_model.submit = SUBMIT_QUEUED;
  prv_copy(g_model.submit_detail, "Sending to phone", DETAIL_LEN);
  prv_enqueue(&p);
}

void comm_new_session(const char *text) {
  Pending p = prv_pending(CMD_NEW_SESSION);
  prv_copy(p.text, text, OUTGOING_TEXT_LEN);
  g_model.submit = SUBMIT_QUEUED;
  prv_copy(g_model.submit_detail, "Creating session", DETAIL_LEN);
  prv_enqueue(&p);
}

void comm_play(const char *session_id, const char *message_id, PlaybackRoute route) {
  Pending p = prv_pending(CMD_PLAY);
  prv_copy(p.session_id, session_id, SESSION_ID_LEN);
  prv_copy(p.message_id, message_id, MESSAGE_ID_LEN);
  p.route = route;
  prv_enqueue(&p);
}

void comm_replay(const char *session_id, const char *message_id) {
  Pending p = prv_pending(CMD_REPLAY);
  prv_copy(p.session_id, session_id, SESSION_ID_LEN);
  prv_copy(p.message_id, message_id, MESSAGE_ID_LEN);
  prv_enqueue(&p);
}

void comm_simple(Command cmd) {
  Pending p = prv_pending(cmd);
  prv_enqueue(&p);
}

void comm_list_audio(void) {
  g_model.audio_loading = true;
  Pending p = prv_pending(CMD_LIST_AUDIO);
  prv_enqueue(&p);
}

void comm_open_reply(const char *session_id, const char *message_id) {
  Pending p = prv_pending(CMD_OPEN_REPLY);
  prv_copy(p.session_id, session_id, SESSION_ID_LEN);
  prv_copy(p.message_id, message_id, MESSAGE_ID_LEN);
  prv_enqueue(&p);
}
