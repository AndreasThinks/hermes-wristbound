#pragma once

#include <pebble.h>
#include "protocol.h"

typedef struct {
  void (*sessions_changed)(void);
  void (*messages_changed)(void);
  void (*status_changed)(void);
  void (*submit_changed)(void);
  void (*playback_changed)(void);
  void (*session_opened)(void);
  void (*audio_changed)(void);
  void (*reply_received)(const char *session_id, const char *message_id,
                         const char *title, const char *text, uint8_t flags);
} CommHandlers;

void comm_init(CommHandlers handlers);
void comm_deinit(void);

void comm_hello(void);
void comm_list_sessions(void);
void comm_open_session(const char *session_id);
void comm_send(const char *session_id, const char *text);
void comm_new_session(const char *text);
void comm_play(const char *session_id, const char *message_id, PlaybackRoute route);
void comm_replay(const char *session_id, const char *message_id);
void comm_simple(Command cmd); // PAUSE, RESUME, STOP
void comm_list_audio(void);
void comm_open_reply(const char *session_id, const char *message_id);
void comm_play_watch(const char *session_id, const char *message_id, uint16_t chunk);
void comm_audio_pull(const char *message_id, uint32_t offset, uint32_t length);
void comm_audio_stop(const char *message_id);
uint32_t comm_inbox_size(void);
