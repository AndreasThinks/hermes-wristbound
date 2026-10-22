#pragma once

#include <pebble.h>
#include "protocol.h"

#define MAX_SESSIONS 20
#define MAX_MESSAGES 12
#define SESSION_ID_LEN 72
#define MESSAGE_ID_LEN 40
#define TITLE_LEN 48
#define DETAIL_LEN 64

// Per-message text budget the phone truncates to. Aplite has a much smaller heap.
#if defined(PBL_PLATFORM_APLITE)
#define TEXT_BUDGET 320
#elif defined(PBL_PLATFORM_EMERY) || defined(PBL_PLATFORM_GABBRO)
#define TEXT_BUDGET 2000
#else
#define TEXT_BUDGET 1200
#endif

typedef struct {
  char id[SESSION_ID_LEN];
  char title[TITLE_LEN];
  uint8_t flags;
} Session;

typedef struct {
  char id[MESSAGE_ID_LEN];
  uint8_t role;
  uint8_t flags;
  char *text; // heap allocated, owned by the model
} Message;

typedef struct {
  ConnState conn;
  char conn_detail[DETAIL_LEN];

  bool include_telegram;
  Session sessions[MAX_SESSIONS];
  int session_count;
  bool sessions_loading;

  char open_session_id[SESSION_ID_LEN];
  char open_session_title[TITLE_LEN];
  uint8_t open_session_flags;
  Message messages[MAX_MESSAGES];
  int message_count;
  bool messages_loading;

  SubmitState submit;
  char submit_detail[DETAIL_LEN];

  char playback_message_id[MESSAGE_ID_LEN];
  PlaybackState playback;
  char playback_detail[DETAIL_LEN];
} Model;

extern Model g_model;

void model_init(void);
void model_deinit(void);
void model_clear_messages(void);
void model_set_message(int index, const char *id, uint8_t role, uint8_t flags, const char *text);
const Session *model_find_session(const char *id);

void model_save_prefs(void);
