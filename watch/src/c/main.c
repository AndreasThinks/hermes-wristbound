#include <pebble.h>
#include "comm.h"
#include "model.h"
#include "speaker.h"
#include "ui.h"

static void prv_init(void) {
  model_init();
  ui_init();
  comm_init((CommHandlers) {
    .sessions_changed = ui_sessions_changed,
    .messages_changed = ui_messages_changed,
    .status_changed = ui_status_changed,
    .submit_changed = ui_submit_changed,
    .playback_changed = ui_playback_changed,
    .session_opened = ui_session_opened,
    .audio_changed = ui_audio_changed,
    .stream_changed = ui_stream_changed,
    .reply_received = ui_reply_received,
  });
  wspk_init(ui_playback_changed);
  g_model.sessions_loading = true;
  comm_hello();
}

static void prv_deinit(void) {
  wspk_deinit();
  comm_deinit();
  ui_deinit();
  model_deinit();
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
