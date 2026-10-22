#pragma once
#include <pebble.h>
#include "protocol.h"

#define MAX_SESSIONS   15
#define MAX_MESSAGES   15
#define SESSION_ID_LEN 72
#define TITLE_LEN      36
#define PREVIEW_LEN    88
#define STATUS_LEN     68
#define BODY_LEN       3100

typedef struct {
  char id[SESSION_ID_LEN];
  char title[TITLE_LEN];
  uint8_t flags;
} SessionItem;

typedef struct {
  uint32_t id;
  uint8_t role;
  uint8_t flags;
  char preview[PREVIEW_LEN];
} MessageItem;

typedef struct {
  // Sessions (phone-filtered by the Telegram preference).
  SessionItem sessions[MAX_SESSIONS];
  uint8_t session_count;
  uint8_t session_gen;
  bool sessions_loaded;

  // Messages of the open session, newest first.
  char open_session[SESSION_ID_LEN];
  MessageItem messages[MAX_MESSAGES];
  uint8_t message_count;
  uint8_t message_gen;
  bool messages_loaded;

  // Body of the message shown in the detail window.
  uint32_t body_msg_id;
  char body[BODY_LEN];
  uint16_t body_len;
  bool body_complete;

  // Connection / activity.
  uint8_t status_code;
  char status_text[STATUS_LEN];
  char tool_status[STATUS_LEN];
  bool phone_seen;

  // Preferences mirrored from the phone (phone is authoritative).
  uint8_t pref_include_telegram;
  uint8_t pref_tool_mode;
  uint8_t pref_reply_mode;

  // Last submission.
  uint32_t submit_id;
  uint8_t submit_state;
  char submit_text[STATUS_LEN];

  // Playback of a specific message.
  uint32_t play_msg_id;
  uint8_t play_state;
  uint8_t play_source;
  char play_text[STATUS_LEN];
} AppState;

extern AppState g;

// ---- comm.c ----
void comm_init(void);
void comm_deinit(void);
void comm_hello(void);
void comm_list_sessions(void);
void comm_open_session(const char *session_id);
void comm_get_body(const char *session_id, uint32_t msg_id);
void comm_submit(const char *session_id, const char *text);
void comm_play(const char *session_id, uint32_t msg_id, bool speaker);
void comm_playback(uint8_t action);
void comm_set_pref(uint8_t id, uint8_t value);
void comm_mark_read(const char *session_id, uint32_t msg_id);

// ---- ui_sessions.c ----
void sessions_window_push(void);
void sessions_window_refresh(void);

// ---- ui_messages.c ----
void messages_window_push(const char *session_id, const char *title);
void messages_window_refresh(void);
bool messages_window_is_open(void);

// ---- ui_detail.c ----
void detail_window_push(const char *session_id, uint32_t msg_id, uint8_t role, uint8_t flags,
                        const char *title, const char *preview);
void detail_window_body_updated(void);
void detail_window_playback_updated(void);
void detail_window_submit_updated(void);

// ---- ui_compose.c ----
void compose_window_push(const char *session_id);  // "" = new session
void compose_submit_updated(void);

// ---- ui_settings.c ----
void settings_window_push(void);
void settings_window_refresh(void);

// ---- shared helpers ----
void status_line(char *out, size_t n);
const char *submit_state_label(uint8_t s);
