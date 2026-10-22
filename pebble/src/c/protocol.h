#pragma once
// Hermes Wristbound watch <-> Android AppMessage protocol, version 1.
// Numeric values are the wire contract; android/.../core/WatchProtocol.kt must match
// (enforced by WatchProtocolHeaderTest). Integers from the phone are sent as uint32.

#define WB_PROTO_VERSION 1

// ---- Dictionary keys ----
#define KEY_TYPE        1
#define KEY_SESSION_ID  2
#define KEY_MSG_ID      3
#define KEY_TEXT        4
#define KEY_TITLE       5
#define KEY_INDEX       6
#define KEY_COUNT       7
#define KEY_FLAGS       8
#define KEY_ROLE        9
#define KEY_OFFSET      10
#define KEY_STATE       11
#define KEY_SUBMIT_ID   12
#define KEY_ROUTE       13
#define KEY_ACTION      14
#define KEY_PREF_ID     15
#define KEY_PREF_VALUE  16
#define KEY_GEN         17
#define KEY_PROTO       18
#define KEY_SOURCE      19

// ---- Watch -> phone message types ----
#define T_HELLO         1   // PROTO
#define T_LIST_SESSIONS 2
#define T_OPEN_SESSION  3   // SESSION_ID
#define T_GET_BODY      4   // SESSION_ID, MSG_ID
#define T_SUBMIT        5   // SESSION_ID ("" = new session), TEXT, SUBMIT_ID
#define T_PLAY          6   // SESSION_ID, MSG_ID, ROUTE
#define T_PLAYBACK      7   // ACTION
#define T_SET_PREF      8   // PREF_ID, PREF_VALUE
#define T_MARK_READ     9   // SESSION_ID, MSG_ID

// ---- Phone -> watch message types ----
#define T_STATUS        101 // STATE (status code), TEXT
#define T_SESSION_ITEM  102 // GEN, INDEX, COUNT, SESSION_ID, TITLE, FLAGS
#define T_MESSAGE_ITEM  103 // GEN, INDEX, COUNT, SESSION_ID, MSG_ID, ROLE, FLAGS, TEXT
#define T_BODY_CHUNK    104 // MSG_ID, OFFSET, TEXT, FLAGS
#define T_SUBMIT_STATE  105 // SUBMIT_ID, STATE, TEXT, SESSION_ID
#define T_PLAYBACK_STATE 106 // MSG_ID, STATE, SOURCE, TEXT
#define T_PREFS         107 // PREF_ID/PREF_VALUE pairs are sent one per message
#define T_ALERT         108 // SESSION_ID, MSG_ID, TITLE, TEXT, FLAGS
#define T_TOOL_STATUS   109 // SESSION_ID, TEXT

// ---- Status codes (T_STATUS / STATE) ----
#define ST_OK           0
#define ST_OFFLINE      1
#define ST_AUTH         2
#define ST_UNCONFIGURED 3
#define ST_SYNCING      4

// ---- Session flags ----
#define SF_UNREAD       0x01
#define SF_TELEGRAM     0x02
#define SF_ACTIVE       0x04
#define SF_SELECTED     0x08

// ---- Message roles ----
#define ROLE_USER       1
#define ROLE_ASSISTANT  2
#define ROLE_TOOL       3

// ---- Message flags ----
#define MF_HERMES_AUDIO 0x01   // Hermes generated audio for this turn
#define MF_PLAYABLE     0x02   // can be spoken (phone TTS fallback)
#define MF_FINAL_CHUNK  0x04   // T_BODY_CHUNK: last chunk
#define MF_TRUNCATED    0x08   // T_BODY_CHUNK: body truncated for the watch

// ---- Submission states ----
#define SS_PENDING      1
#define SS_ACCEPTED     2
#define SS_RUNNING      3
#define SS_COMPLETED    4
#define SS_FAILED       5
#define SS_UNCERTAIN    6

// ---- Playback ----
#define ROUTE_HEADPHONES 0     // default: only on a private route, never silently on speaker
#define ROUTE_SPEAKER    1     // explicit phone-speaker request

#define PA_PAUSE  1
#define PA_RESUME 2
#define PA_REPLAY 3
#define PA_STOP   4

#define PS_IDLE           0
#define PS_PREPARING      1
#define PS_PLAYING        2
#define PS_PAUSED         3
#define PS_ENDED          4
#define PS_ERROR          5
#define PS_NEED_HEADPHONES 6

#define SRC_HERMES_AUDIO  1
#define SRC_PHONE_TTS     2

// ---- Preferences ----
#define PREF_INCLUDE_TELEGRAM 1  // 0/1
#define PREF_TOOL_MODE        2  // 0 hidden, 1 show silently, 2 notify
#define PREF_REPLY_MODE       3  // 0 notify, 1 show silently
