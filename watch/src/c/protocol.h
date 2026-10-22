#pragma once

// Watch <-> Android AppMessage protocol. Keep in sync with
// android/app/src/main/java/dev/wristbound/companion/pebble/Protocol.kt and docs/PROTOCOL.md.

#define PROTO_VERSION 2

// Watch -> phone commands (MESSAGE_KEY_Cmd)
typedef enum {
  CMD_HELLO = 1,         // TextBudget, ProtoVersion, Filter, SessionId (last selected, optional)
  CMD_LIST_SESSIONS = 2, // Filter
  CMD_OPEN_SESSION = 3,  // SessionId
  CMD_SEND = 4,          // SessionId, Text
  CMD_NEW_SESSION = 5,   // Text (first message)
  CMD_PLAY = 6,          // SessionId, MessageId, Route
  CMD_PAUSE = 7,
  CMD_RESUME = 8,
  CMD_REPLAY = 9,        // SessionId, MessageId
  CMD_STOP = 10,
  CMD_LIST_AUDIO = 11,   // Filter
  CMD_OPEN_REPLY = 12,   // SessionId, MessageId; answered with a REPLY flagged REQUESTED
} Command;

// Phone -> watch message types (MESSAGE_KEY_Type)
typedef enum {
  TYPE_SESSION = 1,        // Index, Total, SessionId, Title, Flags
  TYPE_MESSAGE = 2,        // SessionId, Index, Total, MessageId, Role, Flags, Text
  TYPE_STATUS = 3,         // State (ConnState), Detail
  TYPE_REPLY = 4,          // SessionId, MessageId, Title, Text, Flags
  TYPE_PLAYBACK = 5,       // MessageId, State (PlaybackState), Detail
  TYPE_SUBMIT = 6,         // SessionId, State (SubmitState), Detail
  TYPE_SESSION_OPENED = 7, // SessionId, Title, Flags
  TYPE_AUDIO = 8,          // Index, Total, SessionId, MessageId, Title, Text (preview), Flags, Time
} MessageType;

typedef enum {
  CONN_OK = 0,
  CONN_OFFLINE = 1,        // Hermes unreachable; cached data shown
  CONN_STALE = 2,          // data older than refresh window
  CONN_NOT_CONFIGURED = 3, // companion has no Hermes URL / key
  CONN_AUTH_ERROR = 4,
  CONN_ERROR = 5,
} ConnState;

typedef enum {
  PLAYBACK_IDLE = 0,
  PLAYBACK_PLAYING = 1,
  PLAYBACK_PAUSED = 2,
  PLAYBACK_NEEDS_SPEAKER = 3, // no headphones; watch must explicitly ask for speaker
  PLAYBACK_ERROR = 4,
  PLAYBACK_DONE = 5,
} PlaybackState;

typedef enum {
  SUBMIT_NONE = 0,
  SUBMIT_QUEUED = 1,
  SUBMIT_WORKING = 2,   // Hermes accepted the turn and is running it
  SUBMIT_COMPLETED = 3,
  SUBMIT_FAILED = 4,    // Hermes reported failure; nothing more will happen
  SUBMIT_UNCERTAIN = 5, // connection lost mid-turn; outcome unknown, not resent
  SUBMIT_READ_ONLY = 6, // not sendable here: Hermes refused, or Telegram replies are off on the phone
  SUBMIT_CANCELLED = 7,
} SubmitState;

typedef enum {
  ROUTE_CURRENT = 0, // phone's active route; refuse silently switching to speaker
  ROUTE_SPEAKER = 1, // explicit phone-speaker playback
} PlaybackRoute;

typedef enum {
  ROLE_USER = 0,
  ROLE_ASSISTANT = 1,
  ROLE_TOOL = 2,
  ROLE_SYSTEM = 3,
} Role;

// Session flags
#define SESSION_FLAG_UNREAD    (1 << 0)
#define SESSION_FLAG_TELEGRAM  (1 << 1)
#define SESSION_FLAG_READ_ONLY (1 << 2)
#define SESSION_FLAG_ACTIVE    (1 << 3)

// Message flags
#define MESSAGE_FLAG_TRUNCATED (1 << 0)
#define MESSAGE_FLAG_HAS_AUDIO (1 << 1)
#define MESSAGE_FLAG_PENDING   (1 << 2)
#define MESSAGE_FLAG_REQUESTED (1 << 3) // a REPLY the watch asked for, not a new alert

// Recent audio flags
#define AUDIO_FLAG_NEW      (1 << 0)
#define AUDIO_FLAG_TELEGRAM (1 << 1)

// Filter: whether Telegram-origin sessions are listed
#define FILTER_EXCLUDE_TELEGRAM 0
#define FILTER_INCLUDE_TELEGRAM 1
