#include "wristbound.h"

// Outgoing commands are queued and sent one at a time; AppMessage has a single outbox.
// The phone ACK only means "received by the companion"; outcomes arrive as state messages.

#define QUEUE_LEN 6
#define TEXT_LEN 400

typedef struct {
  uint8_t type;
  char session_id[SESSION_ID_LEN];
  uint32_t msg_id;
  char text[TEXT_LEN];
  uint32_t submit_id;
  uint8_t route;
  uint8_t action;
  uint8_t pref_id;
  uint8_t pref_value;
  bool has_msg;
} Cmd;

static Cmd s_queue[QUEUE_LEN];
static uint8_t s_head, s_count;
static bool s_sending;
static uint8_t s_retries;
static AppTimer *s_retry_timer;
static AppTimer *s_submit_timer;
static Cmd s_last_submit;
static uint8_t s_submit_resends;

static void send_next(void);

static uint32_t tuple_uint(const Tuple *t) {
  if (!t) return 0;
  switch (t->type) {
    case TUPLE_UINT:
      if (t->length == 1) return t->value->uint8;
      if (t->length == 2) return t->value->uint16;
      return t->value->uint32;
    case TUPLE_INT:
      if (t->length == 1) return (uint32_t)(uint8_t)t->value->int8;
      if (t->length == 2) return (uint32_t)(uint16_t)t->value->int16;
      return (uint32_t)t->value->int32;
    default:
      return 0;
  }
}

static const char *tuple_str(DictionaryIterator *it, uint32_t key) {
  Tuple *t = dict_find(it, key);
  return (t && t->type == TUPLE_CSTRING) ? t->value->cstring : "";
}

static void enqueue(const Cmd *c) {
  if (s_count == QUEUE_LEN) {
    // Full: drop a new non-submit command (state is re-requested later anyway). A submit makes
    // room by dropping the oldest queued command that is not in flight and not a submit.
    APP_LOG(APP_LOG_LEVEL_WARNING, "outbox queue full");
    if (c->type != T_SUBMIT) return;
    bool dropped = false;
    for (uint8_t i = s_sending ? 1 : 0; i < s_count && !dropped; i++) {
      uint8_t idx = (s_head + i) % QUEUE_LEN;
      if (s_queue[idx].type == T_SUBMIT) continue;
      for (uint8_t j = i; j + 1 < s_count; j++) {
        s_queue[(s_head + j) % QUEUE_LEN] = s_queue[(s_head + j + 1) % QUEUE_LEN];
      }
      s_count--;
      dropped = true;
    }
    if (!dropped) return;
  }
  s_queue[(s_head + s_count) % QUEUE_LEN] = *c;
  s_count++;
  send_next();
}

static void retry_cb(void *ctx) {
  s_retry_timer = NULL;
  send_next();
}

static void send_next(void) {
  if (s_sending || s_count == 0) return;
  Cmd *c = &s_queue[s_head];
  DictionaryIterator *it;
  if (app_message_outbox_begin(&it) != APP_MSG_OK) {
    if (!s_retry_timer) s_retry_timer = app_timer_register(400, retry_cb, NULL);
    return;
  }
  dict_write_uint8(it, KEY_TYPE, c->type);
  switch (c->type) {
    case T_HELLO:
      dict_write_uint8(it, KEY_PROTO, WB_PROTO_VERSION);
      break;
    case T_SUBMIT:
      dict_write_cstring(it, KEY_SESSION_ID, c->session_id);
      dict_write_cstring(it, KEY_TEXT, c->text);
      dict_write_uint32(it, KEY_SUBMIT_ID, c->submit_id);
      break;
    case T_PLAY:
      dict_write_cstring(it, KEY_SESSION_ID, c->session_id);
      dict_write_uint32(it, KEY_MSG_ID, c->msg_id);
      dict_write_uint8(it, KEY_ROUTE, c->route);
      break;
    case T_PLAYBACK:
      dict_write_uint8(it, KEY_ACTION, c->action);
      break;
    case T_SET_PREF:
      dict_write_uint8(it, KEY_PREF_ID, c->pref_id);
      dict_write_uint8(it, KEY_PREF_VALUE, c->pref_value);
      break;
    default:
      if (c->session_id[0]) dict_write_cstring(it, KEY_SESSION_ID, c->session_id);
      if (c->has_msg) dict_write_uint32(it, KEY_MSG_ID, c->msg_id);
      break;
  }
  if (app_message_outbox_send() == APP_MSG_OK) {
    s_sending = true;
  } else if (!s_retry_timer) {
    s_retry_timer = app_timer_register(400, retry_cb, NULL);
  }
}

static void outbox_sent(DictionaryIterator *it, void *ctx) {
  s_sending = false;
  s_retries = 0;
  if (s_count) {
    s_head = (s_head + 1) % QUEUE_LEN;
    s_count--;
  }
  send_next();
}

static void outbox_failed(DictionaryIterator *it, AppMessageResult reason, void *ctx) {
  s_sending = false;
  APP_LOG(APP_LOG_LEVEL_WARNING, "outbox failed %d", (int)reason);
  if (++s_retries > 3) {
    // Give up on this command; a submit is surfaced as failed rather than retried forever.
    if (s_count && s_queue[s_head].type == T_SUBMIT) {
      g.submit_state = SS_FAILED;
      snprintf(g.submit_text, sizeof(g.submit_text), "Phone not reachable");
      compose_submit_updated();
      detail_window_submit_updated();
    }
    s_retries = 0;
    if (s_count) {
      s_head = (s_head + 1) % QUEUE_LEN;
      s_count--;
    }
  }
  if (!s_retry_timer) s_retry_timer = app_timer_register(500, retry_cb, NULL);
}

// If no submission state arrives, resend the SAME submit id: the phone deduplicates it, so this
// can never create a second Hermes turn.
static void submit_watchdog(void *ctx) {
  s_submit_timer = NULL;
  if (g.submit_state != 0) return;
  if (s_submit_resends++ < 2) {
    enqueue(&s_last_submit);
    s_submit_timer = app_timer_register(10000, submit_watchdog, NULL);
  } else {
    g.submit_state = SS_UNCERTAIN;
    snprintf(g.submit_text, sizeof(g.submit_text), "No answer from phone");
    compose_submit_updated();
    detail_window_submit_updated();
  }
}

// ---------------------------------------------------------------------------------------------

static void handle_session_item(DictionaryIterator *it) {
  uint8_t gen = tuple_uint(dict_find(it, KEY_GEN));
  uint8_t idx = tuple_uint(dict_find(it, KEY_INDEX));
  uint8_t count = tuple_uint(dict_find(it, KEY_COUNT));
  if (gen != g.session_gen || idx == 0) {
    g.session_gen = gen;
    g.session_count = 0;
  }
  g.sessions_loaded = true;
  if (count == 0) {
    g.session_count = 0;
    sessions_window_refresh();
    return;
  }
  if (idx >= MAX_SESSIONS) return;
  SessionItem *s = &g.sessions[idx];
  strncpy(s->id, tuple_str(it, KEY_SESSION_ID), sizeof(s->id) - 1);
  s->id[sizeof(s->id) - 1] = 0;
  strncpy(s->title, tuple_str(it, KEY_TITLE), sizeof(s->title) - 1);
  s->title[sizeof(s->title) - 1] = 0;
  s->flags = tuple_uint(dict_find(it, KEY_FLAGS));
  if (idx + 1 > g.session_count) g.session_count = idx + 1;
  if (idx + 1 == count || idx + 1 == MAX_SESSIONS) sessions_window_refresh();
}

static void handle_message_item(DictionaryIterator *it) {
  const char *sid = tuple_str(it, KEY_SESSION_ID);
  if (strcmp(sid, g.open_session) != 0) return;  // stale list for another session
  uint8_t gen = tuple_uint(dict_find(it, KEY_GEN));
  uint8_t idx = tuple_uint(dict_find(it, KEY_INDEX));
  uint8_t count = tuple_uint(dict_find(it, KEY_COUNT));
  if (gen != g.message_gen || idx == 0) {
    g.message_gen = gen;
    g.message_count = 0;
  }
  g.messages_loaded = true;
  if (count == 0) {
    g.message_count = 0;
    messages_window_refresh();
    return;
  }
  if (idx >= MAX_MESSAGES) return;
  MessageItem *m = &g.messages[idx];
  m->id = tuple_uint(dict_find(it, KEY_MSG_ID));
  m->role = tuple_uint(dict_find(it, KEY_ROLE));
  m->flags = tuple_uint(dict_find(it, KEY_FLAGS));
  strncpy(m->preview, tuple_str(it, KEY_TEXT), sizeof(m->preview) - 1);
  m->preview[sizeof(m->preview) - 1] = 0;
  if (idx + 1 > g.message_count) g.message_count = idx + 1;
  if (idx + 1 == count || idx + 1 == MAX_MESSAGES) messages_window_refresh();
}

static void handle_body_chunk(DictionaryIterator *it) {
  uint32_t mid = tuple_uint(dict_find(it, KEY_MSG_ID));
  if (mid != g.body_msg_id) return;
  uint32_t offset = tuple_uint(dict_find(it, KEY_OFFSET));
  const char *text = tuple_str(it, KEY_TEXT);
  uint8_t flags = tuple_uint(dict_find(it, KEY_FLAGS));
  if (offset == 0) g.body_len = 0;
  if (offset != g.body_len) return;  // out of order; phone resends on next request
  size_t n = strlen(text);
  if (g.body_len + n >= BODY_LEN) n = BODY_LEN - 1 - g.body_len;
  memcpy(g.body + g.body_len, text, n);
  g.body_len += n;
  g.body[g.body_len] = 0;
  g.body_complete = (flags & MF_FINAL_CHUNK) != 0;
  detail_window_body_updated();
}

static void handle_alert(DictionaryIterator *it) {
  const char *sid = tuple_str(it, KEY_SESSION_ID);
  uint32_t mid = tuple_uint(dict_find(it, KEY_MSG_ID));
  uint8_t flags = tuple_uint(dict_find(it, KEY_FLAGS));
  // The phone only sends T_ALERT when its alert policy says so; the Android notification for
  // the same reply is posted silently while this app is open, so this is the one vibration.
  vibes_short_pulse();
  detail_window_push(sid, mid, ROLE_ASSISTANT, flags, tuple_str(it, KEY_TITLE), tuple_str(it, KEY_TEXT));
}

static void inbox_received(DictionaryIterator *it, void *ctx) {
  g.phone_seen = true;
  uint8_t type = tuple_uint(dict_find(it, KEY_TYPE));
  switch (type) {
    case T_STATUS:
      g.status_code = tuple_uint(dict_find(it, KEY_STATE));
      strncpy(g.status_text, tuple_str(it, KEY_TEXT), sizeof(g.status_text) - 1);
      sessions_window_refresh();
      break;
    case T_SESSION_ITEM:
      handle_session_item(it);
      break;
    case T_MESSAGE_ITEM:
      handle_message_item(it);
      break;
    case T_BODY_CHUNK:
      handle_body_chunk(it);
      break;
    case T_SUBMIT_STATE: {
      uint32_t sid = tuple_uint(dict_find(it, KEY_SUBMIT_ID));
      if (sid != g.submit_id) break;
      g.submit_state = tuple_uint(dict_find(it, KEY_STATE));
      strncpy(g.submit_text, tuple_str(it, KEY_TEXT), sizeof(g.submit_text) - 1);
      const char *session = tuple_str(it, KEY_SESSION_ID);
      if (session[0] && !g.open_session[0]) {
        strncpy(g.open_session, session, sizeof(g.open_session) - 1);  // new session adopted
      }
      compose_submit_updated();
      detail_window_submit_updated();
      if (g.submit_state == SS_COMPLETED && messages_window_is_open()) comm_open_session(g.open_session);
      break;
    }
    case T_PLAYBACK_STATE:
      g.play_msg_id = tuple_uint(dict_find(it, KEY_MSG_ID));
      g.play_state = tuple_uint(dict_find(it, KEY_STATE));
      g.play_source = tuple_uint(dict_find(it, KEY_SOURCE));
      strncpy(g.play_text, tuple_str(it, KEY_TEXT), sizeof(g.play_text) - 1);
      if (g.play_state == PS_NEED_HEADPHONES || g.play_state == PS_ERROR) vibes_double_pulse();
      detail_window_playback_updated();
      break;
    case T_PREFS: {
      uint8_t id = tuple_uint(dict_find(it, KEY_PREF_ID));
      uint8_t v = tuple_uint(dict_find(it, KEY_PREF_VALUE));
      if (id == PREF_INCLUDE_TELEGRAM) g.pref_include_telegram = v;
      else if (id == PREF_TOOL_MODE) g.pref_tool_mode = v;
      else if (id == PREF_REPLY_MODE) g.pref_reply_mode = v;
      settings_window_refresh();
      break;
    }
    case T_ALERT:
      handle_alert(it);
      break;
    case T_TOOL_STATUS:
      strncpy(g.tool_status, tuple_str(it, KEY_TEXT), sizeof(g.tool_status) - 1);
      sessions_window_refresh();
      messages_window_refresh();
      break;
    default:
      break;
  }
}

static void inbox_dropped(AppMessageResult reason, void *ctx) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "inbox dropped %d", (int)reason);
}

void comm_init(void) {
  app_message_register_inbox_received(inbox_received);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_register_outbox_sent(outbox_sent);
  app_message_register_outbox_failed(outbox_failed);
  app_message_open(1024, 512);
}

void comm_deinit(void) {
  app_message_deregister_callbacks();
}

static Cmd make(uint8_t type) {
  Cmd c;
  memset(&c, 0, sizeof(c));
  c.type = type;
  return c;
}

static void set_sid(Cmd *c, const char *sid) {
  strncpy(c->session_id, sid ? sid : "", sizeof(c->session_id) - 1);
}

void comm_hello(void) { Cmd c = make(T_HELLO); enqueue(&c); }
void comm_list_sessions(void) { Cmd c = make(T_LIST_SESSIONS); enqueue(&c); }

void comm_open_session(const char *sid) {
  Cmd c = make(T_OPEN_SESSION);
  set_sid(&c, sid);
  enqueue(&c);
}

void comm_get_body(const char *sid, uint32_t msg_id) {
  Cmd c = make(T_GET_BODY);
  set_sid(&c, sid);
  c.msg_id = msg_id;
  c.has_msg = true;
  g.body_msg_id = msg_id;
  g.body_len = 0;
  g.body[0] = 0;
  g.body_complete = false;
  enqueue(&c);
}

void comm_submit(const char *sid, const char *text) {
  Cmd c = make(T_SUBMIT);
  set_sid(&c, sid);
  strncpy(c.text, text, sizeof(c.text) - 1);
  // Unique per user action; reused for watch-side resends so the phone can deduplicate.
  c.submit_id = ((uint32_t)time(NULL) << 8) ^ (uint32_t)(rand() & 0xFFFFFF);
  if (c.submit_id == 0) c.submit_id = 1;
  g.submit_id = c.submit_id;
  g.submit_state = 0;
  snprintf(g.submit_text, sizeof(g.submit_text), "Sending to phone…");
  s_last_submit = c;
  s_submit_resends = 0;
  if (s_submit_timer) app_timer_cancel(s_submit_timer);
  s_submit_timer = app_timer_register(10000, submit_watchdog, NULL);
  enqueue(&c);
}

void comm_play(const char *sid, uint32_t msg_id, bool speaker) {
  Cmd c = make(T_PLAY);
  set_sid(&c, sid);
  c.msg_id = msg_id;
  c.route = speaker ? ROUTE_SPEAKER : ROUTE_HEADPHONES;
  g.play_msg_id = msg_id;
  g.play_state = PS_PREPARING;
  snprintf(g.play_text, sizeof(g.play_text), "Requesting…");
  enqueue(&c);
}

void comm_playback(uint8_t action) { Cmd c = make(T_PLAYBACK); c.action = action; enqueue(&c); }

void comm_set_pref(uint8_t id, uint8_t value) {
  Cmd c = make(T_SET_PREF);
  c.pref_id = id;
  c.pref_value = value;
  enqueue(&c);
}

void comm_mark_read(const char *sid, uint32_t msg_id) {
  Cmd c = make(T_MARK_READ);
  set_sid(&c, sid);
  c.msg_id = msg_id;
  c.has_msg = true;
  enqueue(&c);
}
