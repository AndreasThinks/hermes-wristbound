#pragma once

#include <pebble.h>
#include "protocol.h"

#define SESSION_ID_LEN 72
#define MESSAGE_ID_LEN 40
#define TITLE_LEN 48
#define DETAIL_LEN 64
#define PREVIEW_LEN 64

// Aplite's heap shares 24 KB with the code and these lists: fewer rows leave room for the
// reply card and its action menu.
#if defined(PBL_PLATFORM_APLITE)
#define MAX_SESSIONS 8
#define MAX_AUDIO 3
#define MAX_MESSAGES 8
#else
#define MAX_SESSIONS 20
#define MAX_AUDIO 15
#define MAX_MESSAGES 12
#endif

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
  char session_id[SESSION_ID_LEN];
  char message_id[MESSAGE_ID_LEN];
  char title[TITLE_LEN];
  char preview[PREVIEW_LEN];
  uint32_t time; // seconds since the epoch
  uint8_t flags;
} AudioItem;

// What a Quick Launch shortcut to the app does. Chosen in the phone app (TYPE_CONFIG).
typedef enum {
  QUICK_HOME = 0,
  QUICK_DICTATE,      // a new session, straight into dictation
  QUICK_RECENT_AUDIO,
  QUICK_PLAY_LATEST,  // open the newest Recent audio reply and play it
  QUICK_LAST_SESSION,
  QUICK_COUNT,
} QuickLaunch;

typedef struct {
  ConnState conn;
  char conn_detail[DETAIL_LEN];

  QuickLaunch quick_launch;
  bool headphones; // headphones are connected to the phone
  Session sessions[MAX_SESSIONS];
  int session_count;
  bool sessions_loading;

  AudioItem audio[MAX_AUDIO];
  int audio_count;
  bool audio_loading;

  char open_session_id[SESSION_ID_LEN];
  char open_session_title[TITLE_LEN];
  uint8_t open_session_flags;
  Message messages[MAX_MESSAGES];
  int message_count;
  bool messages_loading;
  // Shown after the history until it catches up: what the user just sent, and Hermes' reply as
  // it streams in. Both heap allocated, owned by the model.
  char *outgoing;
  char *stream;

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
void model_truncate_messages(int count);
void model_set_outgoing(const char *text);
void model_set_stream(const char *text);
void model_clear_pending(void);
void model_set_message(int index, const char *id, uint8_t role, uint8_t flags, const char *text);
const Session *model_find_session(const char *id);

void model_save_prefs(void);
