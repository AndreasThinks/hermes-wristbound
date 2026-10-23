#pragma once

#include <pebble.h>

void ui_init(void);
void ui_deinit(void);

// Comm callbacks
void ui_sessions_changed(void);
void ui_messages_changed(void);
void ui_status_changed(void);
void ui_submit_changed(void);
void ui_playback_changed(void);
void ui_session_opened(void);
void ui_audio_changed(void);
void ui_stream_changed(void);
void ui_reply_received(const char *session_id, const char *message_id,
                       const char *title, const char *text, uint8_t flags);
// Only where approval.h enables it.
void ui_approval(const char *session_id, const char *request_id, const char *title, const char *text,
                 uint8_t choices, bool closed);
