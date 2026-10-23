#include "ui.h"
#include "banner.h"
#include "comm.h"
#include "model.h"
#include "speaker.h"
#include "splash.h"
#include "theme.h"

#define DICTATION_LEN 256
#define STATUS_H 22
// The round screen's top edge would clip the title; drop it to where the circle is wide enough.
#define STATUS_Y PBL_IF_ROUND_ELSE(12, 0)
#define WAVE_H 18
#define WAVE_INSET PBL_IF_ROUND_ELSE(24, 6)
#define WAVE_FRAME_MS 150
#define INTRO_FRAME_MS 33
#define INTRO_RISE_MS 200
#define INTRO_TYPE_MS 400
// Aplite's 24 KB holds code and heap together: the waveform and the intro are not worth their
// share there.
#if defined(PBL_PLATFORM_APLITE)
#define MOTION_ENABLED false
#else
#define MOTION_ENABLED true
#endif
// The full-screen reply card, with its waveform and Play menu. Aplite opens replies in their
// conversation instead: its 24 KB cannot hold both views.
#if !defined(PBL_PLATFORM_APLITE)
#define CARD_ENABLED 1
#endif
// Round screens inset text by flowing it; a fixed inset would add to that.
#define TEXT_INSET PBL_IF_ROUND_ELSE(0, 4)

// ---------------------------------------------------------------------------
// Shared helpers

// Where the last Play went; Resume and Replay go there too.
static PlaybackRoute s_route = ROUTE_SPEAKER;

static const char *prv_conn_label(void) {
  if (g_model.conn_detail[0]) {
    return g_model.conn_detail;
  }
  switch (g_model.conn) {
    case CONN_OK: return "Connected";
    case CONN_OFFLINE: return "Offline - cached";
    case CONN_STALE: return "Stale data";
    case CONN_NOT_CONFIGURED: return "Set up phone app";
    case CONN_AUTH_ERROR: return "Hermes auth failed";
    default: return "Error";
  }
}

static const char *prv_submit_label(void) {
  if (g_model.submit_detail[0]) {
    return g_model.submit_detail;
  }
  switch (g_model.submit) {
    case SUBMIT_QUEUED: return "Queued";
    case SUBMIT_WORKING: return "Hermes is working";
    case SUBMIT_COMPLETED: return "Reply received";
    case SUBMIT_FAILED: return "Failed";
    case SUBMIT_UNCERTAIN: return "Outcome unknown";
    case SUBMIT_READ_ONLY: return "Read-only session";
    case SUBMIT_CANCELLED: return "Cancelled";
    default: return NULL;
  }
}

static const char *prv_playback_label(const char *message_id) {
  const char *watch = wspk_label_for(message_id);
  if (watch) {
    return watch;
  }
  if (strcmp(message_id, g_model.playback_message_id) != 0) {
    return NULL;
  }
  if (g_model.playback_detail[0]) {
    return g_model.playback_detail;
  }
  switch (g_model.playback) {
    case PLAYBACK_PLAYING: return s_route == ROUTE_HEADPHONES ? "Playing on headphones" : "Playing on phone";
    case PLAYBACK_PAUSED: return "Paused";
    case PLAYBACK_NEEDS_SPEAKER: return "No headphones";
    case PLAYBACK_ERROR: return "Playback failed";
    case PLAYBACK_DONE: return "Finished";
    default: return NULL;
  }
}

// Headphones on the phone when there are any; otherwise the phone itself, out loud.
static PlaybackRoute prv_route(void) {
  return g_model.headphones ? ROUTE_HEADPHONES : ROUTE_SPEAKER;
}

static void prv_play(const char *session_id, const char *message_id, PlaybackRoute route) {
  s_route = route;
  comm_play(session_id, message_id, route);
}

// ---------------------------------------------------------------------------
// Compose window (dictation + canned replies)

typedef enum { COMPOSE_REPLY, COMPOSE_NEW_SESSION } ComposeMode;

static const char *const CANNED[] = { "Yes", "No", "Continue", "Thanks", "Stop", "Summarise that" };
#define CANNED_COUNT ((int)ARRAY_LENGTH(CANNED))

static Window *s_compose_window;
static MenuLayer *s_compose_menu;
static ComposeMode s_compose_mode;
static DictationSession *s_dictation;

static void prv_open_conv(bool keep_pending);
static Window *s_audio_window;
static Window *s_sessions_window;

static void prv_submit_text(const char *text) {
  model_set_outgoing(text);
  if (s_compose_mode == COMPOSE_NEW_SESSION) {
    // The conversation opens at once, with the message in it; the phone names the session
    // (SESSION_OPENED) once Hermes has made it.
    comm_new_session(text);
    g_model.open_session_id[0] = '\0';
    strncpy(g_model.open_session_title, "New session", TITLE_LEN - 1);
    g_model.open_session_flags = 0;
    window_stack_remove(s_compose_window, false);
    prv_open_conv(true);
  } else {
    comm_send(g_model.open_session_id, text);
    window_stack_remove(s_compose_window, true);
  }
  ui_submit_changed();
}

static void prv_dictation_cb(DictationSession *session, DictationSessionStatus status,
                             char *transcription, void *ctx) {
  if (status == DictationSessionStatusSuccess && transcription && transcription[0]) {
    prv_submit_text(transcription);
  }
}

static bool prv_has_dictation(void) {
  if (!s_dictation) {
    s_dictation = dictation_session_create(DICTATION_LEN, prv_dictation_cb, NULL);
  }
  return s_dictation != NULL;
}

static uint16_t prv_compose_rows(MenuLayer *menu, uint16_t section, void *ctx) {
  return CANNED_COUNT + 1;
}

static void prv_compose_draw(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  if (idx->row == 0) {
    theme_draw_row(ctx, cell, "Dictate",
                   prv_has_dictation() ? "Speak your message" : "No microphone", 0);
  } else {
    theme_draw_row(ctx, cell, CANNED[idx->row - 1], NULL, 0);
  }
}

static void prv_compose_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  if (idx->row == 0) {
    if (prv_has_dictation()) {
      dictation_session_start(s_dictation);
    } else {
      theme_vibe_nudge();
    }
  } else {
    prv_submit_text(CANNED[idx->row - 1]);
  }
}

static void prv_compose_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_compose_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_compose_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = prv_compose_rows,
    .draw_row = prv_compose_draw,
    .select_click = prv_compose_select,
  });
  theme_menu(s_compose_menu);
  menu_layer_set_click_config_onto_window(s_compose_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_compose_menu));
}

static void prv_compose_unload(Window *window) {
  menu_layer_destroy(s_compose_menu);
  s_compose_menu = NULL;
}

static void prv_open_compose(ComposeMode mode) {
  s_compose_mode = mode;
  if (!s_compose_window) {
    s_compose_window = window_create();
    window_set_window_handlers(s_compose_window, (WindowHandlers) {
      .load = prv_compose_load,
      .unload = prv_compose_unload,
    });
  }
  window_stack_push(s_compose_window, true);
}

// The composer with dictation already listening. Cancelling leaves the composer showing, so a
// quick reply or another try is one press away; watches without a microphone just get the composer.
static void prv_open_dictation(ComposeMode mode) {
  prv_open_compose(mode);
  if (prv_has_dictation()) {
    dictation_session_start(s_dictation);
  }
}

// ---------------------------------------------------------------------------
// Message / reply window

typedef enum { MSG_MODE_MESSAGE, MSG_MODE_REPLY } MessageMode;

typedef enum {
  ACT_PLAY_HEADPHONES = 1,
  ACT_PLAY_SPEAKER,
  ACT_PAUSE,
  ACT_RESUME,
  ACT_REPLAY,
  ACT_STOP,
  ACT_WATCH_PLAY,
  ACT_WATCH_PAUSE,
  ACT_WATCH_RESUME,
  ACT_WATCH_STOP,
  ACT_OPEN_SESSION,
  ACT_REPLY,
  ACT_DISMISS,
  ACT_NEWEST,
  ACT_REFRESH,
} MessageAction;

// The reply an action menu acts on: the card's, or the one in focus in a conversation.
static char s_act_session_id[SESSION_ID_LEN];
static char s_act_id[MESSAGE_ID_LEN];

static void prv_conv_newest(void);
static void prv_conv_refresh(void);
static void prv_open_conv_with(const char *session_id, const char *message_id, const char *title,
                               uint8_t flags, const char *text);

#if defined(CARD_ENABLED)
static Window *s_msg_window;
static TextLayer *s_msg_status;
static ScrollLayer *s_msg_scroll;
static TextLayer *s_msg_text;
static MessageMode s_msg_mode;
static char s_msg_session_id[SESSION_ID_LEN];
static char s_msg_id[MESSAGE_ID_LEN];
static char s_msg_header[TITLE_LEN];
static char *s_msg_body;
static uint8_t s_msg_role;
static uint8_t s_msg_flags;

// A reply with audio shows its waveform under the title bar: violet while there is something to
// play, moving while it plays, grey while paused.
static Layer *s_msg_wave;
static AppTimer *s_wave_timer;
static int s_wave_phase;
static int s_wave_rise = 100;

// An incoming reply arrives like a radio tuning in: the waveform rises, then the text types
// itself out. Any button skips to the end.
static bool s_msg_intro;
static AppTimer *s_intro_timer;
static char *s_msg_shown;
static size_t s_msg_shown_len;


static bool prv_msg_playing(void) {
  return strcmp(g_model.playback_message_id, s_msg_id) == 0 && g_model.playback == PLAYBACK_PLAYING;
}

static void prv_wave_tick(void *ctx) {
  s_wave_timer = NULL;
  if (!s_msg_wave || !prv_msg_playing()) {
    return;
  }
  s_wave_phase++;
  layer_mark_dirty(s_msg_wave);
  s_wave_timer = app_timer_register(WAVE_FRAME_MS, prv_wave_tick, NULL);
}

static void prv_wave_draw(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  bool paused = strcmp(g_model.playback_message_id, s_msg_id) == 0 &&
                g_model.playback == PLAYBACK_PAUSED;
  theme_draw_wave(ctx, b, theme_seed(s_msg_id), paused ? THEME_DIM : THEME_VOICE, s_wave_rise,
                  s_wave_phase);
}

static void prv_msg_update_status(void) {
  if (!s_msg_status) {
    return;
  }
  const char *pb = prv_playback_label(s_msg_id);
  text_layer_set_text(s_msg_status, pb ? pb : s_msg_header);
  text_layer_set_text_color(s_msg_status, pb ? THEME_VOICE : THEME_SIGNAL);
  if (MOTION_ENABLED && s_msg_wave) {
    layer_mark_dirty(s_msg_wave);
    if (prv_msg_playing() && !s_wave_timer) {
      s_wave_timer = app_timer_register(WAVE_FRAME_MS, prv_wave_tick, NULL);
    }
  }
}

static void prv_intro_stop(void) {
  if (s_intro_timer) {
    app_timer_cancel(s_intro_timer);
    s_intro_timer = NULL;
  }
  s_msg_intro = false;
  s_wave_rise = 100;
  free(s_msg_shown);
  s_msg_shown = NULL;
}

// Skip the intro: everything at once.
static void prv_intro_finish(void) {
  if (!MOTION_ENABLED || !s_msg_intro) {
    return;
  }
  prv_intro_stop();
  if (s_msg_wave) {
    layer_mark_dirty(s_msg_wave);
  }
  if (s_msg_text) {
    text_layer_set_text(s_msg_text, s_msg_body ? s_msg_body : "");
  }
}

static void prv_intro_tick(void *ctx) {
  s_intro_timer = NULL;
  if (s_wave_rise < 100) {
    s_wave_rise += 100 * INTRO_FRAME_MS / INTRO_RISE_MS;
    if (s_wave_rise > 100) {
      s_wave_rise = 100;
    }
    layer_mark_dirty(s_msg_wave);
    s_intro_timer = app_timer_register(INTRO_FRAME_MS, prv_intro_tick, NULL);
    return;
  }
  size_t len = s_msg_body ? strlen(s_msg_body) : 0;
  if (!s_msg_shown || s_msg_shown_len >= len) {
    prv_intro_finish();
    return;
  }
  // However long the reply, it finishes typing in about INTRO_TYPE_MS.
  size_t step = len / (INTRO_TYPE_MS / INTRO_FRAME_MS) + 2;
  size_t n = s_msg_shown_len + step;
  if (n >= len) {
    prv_intro_finish();
    return;
  }
  while (n < len && ((uint8_t)s_msg_body[n] & 0xC0) == 0x80) {
    n++; // never split a UTF-8 character
  }
  memcpy(s_msg_shown, s_msg_body, n);
  s_msg_shown[n] = '\0';
  s_msg_shown_len = n;
  text_layer_set_text(s_msg_text, s_msg_shown);
  s_intro_timer = app_timer_register(INTRO_FRAME_MS, prv_intro_tick, NULL);
}

static void prv_intro_start(void) {
  size_t len = s_msg_body ? strlen(s_msg_body) : 0;
  s_msg_shown = malloc(len + 1);
  if (!s_msg_shown) {
    prv_intro_finish(); // no memory to spare for the effect: just show it
    return;
  }
  s_msg_shown[0] = '\0';
  s_msg_shown_len = 0;
  s_wave_rise = s_msg_wave ? 0 : 100;
  text_layer_set_text(s_msg_text, s_msg_shown);
  s_intro_timer = app_timer_register(INTRO_FRAME_MS, prv_intro_tick, NULL);
}

static void prv_msg_scrolled(ScrollLayer *scroll, void *ctx) {
  prv_intro_finish();
}

static void prv_msg_layout(void) {
  Layer *root = window_get_root_layer(s_msg_window);
  GRect bounds = layer_get_bounds(root);
  GRect text_bounds = GRect(TEXT_INSET, 0, bounds.size.w - 2 * TEXT_INSET, 4000);
  text_layer_set_size(s_msg_text, text_bounds.size);
  GSize content = text_layer_get_content_size(s_msg_text);
  content.h += 12;
  text_layer_set_size(s_msg_text, GSize(text_bounds.size.w, content.h));
  scroll_layer_set_content_size(s_msg_scroll, GSize(bounds.size.w, content.h));
}

#endif // CARD_ENABLED

static void prv_msg_action(ActionMenu *menu, const ActionMenuItem *item, void *ctx) {
  MessageAction act = (MessageAction)(uintptr_t)action_menu_item_get_action_data(item);
  switch (act) {
    case ACT_PLAY_HEADPHONES: prv_play(s_act_session_id, s_act_id, ROUTE_HEADPHONES); break;
    case ACT_PLAY_SPEAKER: prv_play(s_act_session_id, s_act_id, ROUTE_SPEAKER); break;
    case ACT_PAUSE: comm_simple(CMD_PAUSE); break;
    case ACT_RESUME: comm_resume(s_route); break;
    case ACT_REPLAY: comm_replay(s_act_session_id, s_act_id, s_route); break;
    case ACT_STOP: comm_simple(CMD_STOP); break;
    case ACT_WATCH_PLAY: wspk_play(s_act_session_id, s_act_id); break;
    case ACT_WATCH_PAUSE: wspk_pause(); break;
    case ACT_WATCH_RESUME: wspk_resume(); break;
    case ACT_WATCH_STOP: wspk_stop(); break;
#if defined(CARD_ENABLED)
    case ACT_OPEN_SESSION:
    case ACT_REPLY:
      strncpy(g_model.open_session_id, s_act_session_id, SESSION_ID_LEN - 1);
      g_model.open_session_title[0] = '\0';
      g_model.open_session_flags = 0;
      window_stack_remove(s_msg_window, false);
      prv_open_conv(false);
      // Replying needs the session's own policy: a read-only session opens without the composer,
      // and its Reply row says why.
      if (act == ACT_REPLY && !(g_model.open_session_flags & SESSION_FLAG_READ_ONLY)) {
        prv_open_compose(COMPOSE_REPLY);
      }
      break;
    case ACT_DISMISS:
      window_stack_remove(s_msg_window, true);
      break;
#else
    default:
      break;
#endif
    case ACT_NEWEST:
      prv_conv_newest();
      break;
    case ACT_REFRESH:
      prv_conv_refresh();
      break;
  }
}

static void prv_action_menu_closed(ActionMenu *menu, const ActionMenuItem *item, void *ctx) {
  action_menu_hierarchy_destroy(action_menu_get_root_level(menu), NULL, NULL);
}

// Playback rows for the target reply, which carries Hermes audio.
static void prv_add_play_rows(ActionMenuLevel *root) {
  bool playing_this = strcmp(g_model.playback_message_id, s_act_id) == 0;
  if (playing_this && g_model.playback == PLAYBACK_PLAYING) {
    action_menu_level_add_action(root, "Pause", prv_msg_action, (void *)ACT_PAUSE);
    action_menu_level_add_action(root, "Stop", prv_msg_action, (void *)ACT_STOP);
  } else if (playing_this && g_model.playback == PLAYBACK_PAUSED) {
    action_menu_level_add_action(root, "Resume", prv_msg_action, (void *)ACT_RESUME);
    action_menu_level_add_action(root, "Stop", prv_msg_action, (void *)ACT_STOP);
  } else {
    // The phone reports whether headphones are connected; the option only shows while they are.
    if (g_model.headphones) {
      action_menu_level_add_action(root, "Play on headphones", prv_msg_action, (void *)ACT_PLAY_HEADPHONES);
    }
    action_menu_level_add_action(root, "Play on phone speaker", prv_msg_action, (void *)ACT_PLAY_SPEAKER);
  }
  if (wspk_supported()) {
    // The watch's own speaker: always an explicit choice, never a fallback for the phone route.
    switch (wspk_state_for(s_act_id)) {
      case WSPK_PREPARING:
        action_menu_level_add_action(root, "Stop on watch", prv_msg_action, (void *)ACT_WATCH_STOP);
        break;
      case WSPK_BUFFERING:
      case WSPK_PLAYING:
        action_menu_level_add_action(root, "Pause on watch", prv_msg_action, (void *)ACT_WATCH_PAUSE);
        action_menu_level_add_action(root, "Stop on watch", prv_msg_action, (void *)ACT_WATCH_STOP);
        break;
      case WSPK_PAUSED:
        action_menu_level_add_action(root, "Resume on watch", prv_msg_action, (void *)ACT_WATCH_RESUME);
        action_menu_level_add_action(root, "Stop on watch", prv_msg_action, (void *)ACT_WATCH_STOP);
        break;
      case WSPK_DONE:
        action_menu_level_add_action(root, "Replay on watch", prv_msg_action, (void *)ACT_WATCH_PLAY);
        break;
      case WSPK_FAILED:
        action_menu_level_add_action(root, "Try watch speaker again", prv_msg_action, (void *)ACT_WATCH_PLAY);
        break;
      default:
        action_menu_level_add_action(root, "Play on watch speaker", prv_msg_action, (void *)ACT_WATCH_PLAY);
        break;
    }
  }
  action_menu_level_add_action(root, "Replay from start", prv_msg_action, (void *)ACT_REPLAY);
}

static void prv_open_action_menu(ActionMenuLevel *root) {
  ActionMenuConfig config = {
    .root_level = root,
    .colors = {
      .background = PBL_IF_COLOR_ELSE(THEME_VOICE, GColorBlack),
      .foreground = PBL_IF_COLOR_ELSE(GColorBlack, GColorWhite),
    },
    .align = ActionMenuAlignCenter,
    .did_close = prv_action_menu_closed,
  };
  action_menu_open(&config);
}

static void prv_set_target(const char *session_id, const char *message_id) {
  strncpy(s_act_session_id, session_id, SESSION_ID_LEN - 1);
  s_act_session_id[SESSION_ID_LEN - 1] = '\0';
  strncpy(s_act_id, message_id, MESSAGE_ID_LEN - 1);
  s_act_id[MESSAGE_ID_LEN - 1] = '\0';
}

#if defined(CARD_ENABLED)
static void prv_msg_select(ClickRecognizerRef rec, void *ctx) {
  prv_intro_finish();
  // Only replies that carry Hermes audio are playable: a text reply is never read aloud.
  bool audio = s_msg_role == ROLE_ASSISTANT && (s_msg_flags & MESSAGE_FLAG_HAS_AUDIO);
  if (!audio && s_msg_mode != MSG_MODE_REPLY) {
    return;
  }
  prv_set_target(s_msg_session_id, s_msg_id);
  ActionMenuLevel *root = action_menu_level_create(11);
  if (audio) {
    prv_add_play_rows(root);
  }
  if (s_msg_mode == MSG_MODE_REPLY) {
    action_menu_level_add_action(root, "Reply", prv_msg_action, (void *)ACT_REPLY);
    action_menu_level_add_action(root, "Open session", prv_msg_action, (void *)ACT_OPEN_SESSION);
    action_menu_level_add_action(root, "Dismiss", prv_msg_action, (void *)ACT_DISMISS);
  }
  prv_open_action_menu(root);
}

static void prv_msg_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_msg_select);
}

static void prv_msg_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  theme_window(window);

  s_msg_status = text_layer_create(GRect(0, STATUS_Y, bounds.size.w, STATUS_H));
  text_layer_set_font(s_msg_status, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_alignment(s_msg_status, GTextAlignmentCenter);
  text_layer_set_background_color(s_msg_status, THEME_BG);
  text_layer_set_text_color(s_msg_status, THEME_SIGNAL);
  layer_add_child(root, text_layer_get_layer(s_msg_status));

  int top = STATUS_Y + STATUS_H + 1;
  if (MOTION_ENABLED && (s_msg_flags & MESSAGE_FLAG_HAS_AUDIO)) {
    s_msg_wave = layer_create(GRect(WAVE_INSET, top + 2, bounds.size.w - 2 * WAVE_INSET, WAVE_H));
    layer_set_update_proc(s_msg_wave, prv_wave_draw);
    layer_add_child(root, s_msg_wave);
    top += WAVE_H + 4;
  }

  GRect scroll_frame = GRect(0, top, bounds.size.w, bounds.size.h - top);
  s_msg_scroll = scroll_layer_create(scroll_frame);
  scroll_layer_set_callbacks(s_msg_scroll, (ScrollLayerCallbacks) {
    .click_config_provider = prv_msg_click_config,
    .content_offset_changed_handler = prv_msg_scrolled,
  });
  scroll_layer_set_click_config_onto_window(s_msg_scroll, window);
#if defined(PBL_ROUND)
  scroll_layer_set_paging(s_msg_scroll, true);
#endif

  s_msg_text = text_layer_create(GRect(TEXT_INSET, 0, bounds.size.w - 2 * TEXT_INSET, 4000));
  text_layer_set_font(s_msg_text, fonts_get_system_font(FONT_KEY_GOTHIC_24));
  text_layer_set_background_color(s_msg_text, GColorClear);
  text_layer_set_text_color(s_msg_text, THEME_FG);
  text_layer_set_text(s_msg_text, s_msg_body ? s_msg_body : "");
  text_layer_set_overflow_mode(s_msg_text, GTextOverflowModeWordWrap);
  scroll_layer_add_child(s_msg_scroll, text_layer_get_layer(s_msg_text));
  layer_add_child(root, scroll_layer_get_layer(s_msg_scroll));
#if defined(PBL_ROUND)
  // Text flow only takes effect once the layer is in the window's hierarchy.
  text_layer_enable_screen_text_flow_and_paging(s_msg_text, 4);
#endif

  prv_msg_layout();
  prv_msg_update_status();
  if (MOTION_ENABLED && s_msg_intro) {
    prv_intro_start();
  }
}

static void prv_msg_unload(Window *window) {
  prv_intro_stop();
  if (s_wave_timer) {
    app_timer_cancel(s_wave_timer);
    s_wave_timer = NULL;
  }
  if (s_msg_wave) {
    layer_destroy(s_msg_wave);
    s_msg_wave = NULL;
  }
  text_layer_destroy(s_msg_text);
  scroll_layer_destroy(s_msg_scroll);
  text_layer_destroy(s_msg_status);
  s_msg_text = NULL;
  s_msg_scroll = NULL;
  s_msg_status = NULL;
  free(s_msg_body);
  s_msg_body = NULL;
}

// Replace the card's text; updates the window in place when it is showing.
static void prv_msg_set_body(const char *text, uint8_t flags) {
  prv_intro_finish();
  const char *more = (flags & MESSAGE_FLAG_TRUNCATED) ? "\n\n[Continues on phone]" : "";
  size_t len = strlen(text) + strlen(more) + 1;
  free(s_msg_body);
  s_msg_body = malloc(len);
  if (s_msg_body) {
    snprintf(s_msg_body, len, "%s%s", text, more);
  }
  if (s_msg_text) {
    text_layer_set_text(s_msg_text, s_msg_body ? s_msg_body : "");
    prv_msg_layout();
  }
}

static void prv_open_message(MessageMode mode, const char *session_id, const char *message_id,
                             const char *header, uint8_t role, uint8_t flags, const char *text,
                             bool intro) {
  if (s_msg_window && window_stack_contains_window(s_msg_window)) {
    window_stack_remove(s_msg_window, false);
  }
  s_msg_mode = mode;
  s_msg_role = role;
  s_msg_flags = flags;
  strncpy(s_msg_session_id, session_id, SESSION_ID_LEN - 1);
  s_msg_session_id[SESSION_ID_LEN - 1] = '\0';
  strncpy(s_msg_id, message_id, MESSAGE_ID_LEN - 1);
  s_msg_id[MESSAGE_ID_LEN - 1] = '\0';
  strncpy(s_msg_header, header, TITLE_LEN - 1);
  s_msg_header[TITLE_LEN - 1] = '\0';
  prv_msg_set_body(text, flags);
  s_msg_intro = intro && MOTION_ENABLED;

  if (!s_msg_window) {
    s_msg_window = window_create();
    window_set_window_handlers(s_msg_window, (WindowHandlers) {
      .load = prv_msg_load,
      .unload = prv_msg_unload,
    });
  }
  window_stack_push(s_msg_window, true);
}
#endif // CARD_ENABLED
// ---------------------------------------------------------------------------
// Conversation window: the whole session on one scrolling screen, oldest at the top, with what
// was just sent and Hermes' reply streaming in at the bottom. Select replies; holding Select
// offers Play for the reply in focus.

#define CONV_HEAD_H (STATUS_Y + 18)
#define CONV_TEXT_X PBL_IF_ROUND_ELSE(22, 8)
#define CONV_RIGHT PBL_IF_ROUND_ELSE(22, 4)
#define CONV_BAR_X 2
#define CONV_GAP 8
#define CONV_LABEL_H 13
#define CONV_BUBBLE_PAD 4
// Room left of the user's bubble on rectangular screens, so it reads as "mine".
#define CONV_GUTTER PBL_IF_ROUND_ELSE(0, 20)
// Gothic draws its glyphs a few pixels below the top of the text box.
#define CONV_SLACK 4
// Round screens leave room below the hint, so it can scroll up out of the bezel's curve.
#define CONV_TAIL_H PBL_IF_ROUND_ELSE(64, 34)
#define CONV_MAX_H 32000
#define CURSOR_MS 530
#define CONV_FONT_HERMES FONT_KEY_GOTHIC_24
#define CONV_FONT_USER FONT_KEY_GOTHIC_18
#define CONV_FONT_TOOL FONT_KEY_GOTHIC_14
#define CONV_ENTRIES (MAX_MESSAGES + 2)
#define USER_BUBBLE_FG PBL_IF_COLOR_ELSE(GColorWhite, GColorBlack)

typedef struct {
  const char *id;
  const char *text;
  uint8_t role;
  uint8_t flags;
  bool live; // what was sent or is streaming: not in the history yet
} ConvEntry;

static Window *s_conv_window;
static Layer *s_conv_head;
static ScrollLayer *s_conv_scroll;
static Layer *s_conv_body;
static int16_t s_conv_y[CONV_ENTRIES + 1]; // top of each entry; the last is where the tail starts
static int s_conv_count;
static int s_conv_focus = -1;
// Bring the newest exchange into view when the next layout lands (opening, sending, a reply).
static bool s_conv_follow;
static bool s_conv_scrolling; // the offset is being set from code, not by the user
static AppTimer *s_cursor_timer;
static bool s_cursor_on = true;
static bool s_stream_shown; // the current reply has started streaming in
// A reply to bring into view once the history lands (opened from an alert or Recent audio).
static char s_conv_target[MESSAGE_ID_LEN];

static bool prv_working(void) {
  return g_model.submit == SUBMIT_QUEUED || g_model.submit == SUBMIT_WORKING;
}

// What was sent shows until the history has it: hidden once the newest user row says the same.
static bool prv_show_outgoing(void) {
  if (!g_model.outgoing) {
    return false;
  }
  for (int i = g_model.message_count - 1; i >= 0; i--) {
    if (g_model.messages[i].role == ROLE_USER) {
      return !g_model.messages[i].text || strcmp(g_model.messages[i].text, g_model.outgoing) != 0;
    }
  }
  return true;
}

static bool prv_conv_entry(int i, ConvEntry *e) {
  if (i < g_model.message_count) {
    const Message *m = &g_model.messages[i];
    *e = (ConvEntry) { m->id, m->text ? m->text : "", m->role, m->flags, false };
    return true;
  }
  i -= g_model.message_count;
  if (prv_show_outgoing()) {
    if (i == 0) {
      *e = (ConvEntry) { "", g_model.outgoing, ROLE_USER, 0, true };
      return true;
    }
    i--;
  }
  if (g_model.stream && i == 0) {
    *e = (ConvEntry) { "", g_model.stream, ROLE_ASSISTANT, MESSAGE_FLAG_PENDING, true };
    return true;
  }
  return false;
}

static int16_t prv_conv_width(void) {
  return layer_get_bounds(window_get_root_layer(s_conv_window)).size.w;
}

static GFont prv_entry_font(const ConvEntry *e) {
  switch (e->role) {
    case ROLE_USER: return fonts_get_system_font(CONV_FONT_USER);
    case ROLE_ASSISTANT: return fonts_get_system_font(CONV_FONT_HERMES);
    default: return fonts_get_system_font(CONV_FONT_TOOL);
  }
}

static GTextAlignment prv_entry_align(const ConvEntry *e) {
  return PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft);
}

static int16_t prv_entry_text_w(const ConvEntry *e, int16_t w) {
  if (e->role == ROLE_USER) {
    return w - CONV_TEXT_X - CONV_RIGHT - 2 * CONV_BUBBLE_PAD - CONV_GUTTER;
  }
  return w - CONV_TEXT_X - CONV_RIGHT;
}

static GSize prv_entry_text_size(const ConvEntry *e, int16_t w) {
  return graphics_text_layout_get_content_size(e->text, prv_entry_font(e),
                                               GRect(0, 0, prv_entry_text_w(e, w), CONV_MAX_H),
                                               GTextOverflowModeWordWrap, prv_entry_align(e));
}

static int16_t prv_entry_height(const ConvEntry *e, int16_t w) {
  int16_t text_h = prv_entry_text_size(e, w).h;
  switch (e->role) {
    case ROLE_USER: return text_h + 2 * CONV_BUBBLE_PAD + CONV_GAP;
    case ROLE_ASSISTANT: return CONV_LABEL_H + text_h + CONV_GAP;
    default: return text_h + CONV_GAP;
  }
}

static int16_t prv_conv_viewport_h(void) {
  return layer_get_frame(scroll_layer_get_layer(s_conv_scroll)).size.h;
}

static int16_t prv_conv_top(void) {
  return -scroll_layer_get_content_offset(s_conv_scroll).y;
}

static void prv_conv_scroll_to(int16_t y) {
  int16_t max = s_conv_y[s_conv_count] + CONV_TAIL_H - prv_conv_viewport_h();
  if (y > max) {
    y = max;
  }
  if (y < 0) {
    y = 0;
  }
  s_conv_scrolling = true;
  scroll_layer_set_content_offset(s_conv_scroll, GPoint(0, -y), false);
  s_conv_scrolling = false;
}

// The newest exchange: from the last thing the user said when it is one of the last two
// entries (so a question and its answer show together), otherwise the newest entry.
static int16_t prv_conv_anchor(void) {
  if (s_conv_count == 0) {
    return 0;
  }
  if (s_conv_target[0]) {
    for (int i = 0; i < g_model.message_count; i++) {
      if (strcmp(g_model.messages[i].id, s_conv_target) == 0) {
        return s_conv_y[i];
      }
    }
  }
  int last = s_conv_count - 1;
  ConvEntry e;
  if (last > 0 && prv_conv_entry(last - 1, &e) && e.role == ROLE_USER) {
    return s_conv_y[last - 1];
  }
  return s_conv_y[last];
}

// The reply being read: the last assistant message that starts above a third of the way down
// the screen, or the first one below it.
static void prv_conv_update_focus(void) {
  int16_t line = prv_conv_top() + prv_conv_viewport_h() / 3;
  int focus = -1;
  for (int i = 0; i < g_model.message_count; i++) {
    if (g_model.messages[i].role != ROLE_ASSISTANT) {
      continue;
    }
    if (s_conv_y[i] <= line || focus < 0) {
      focus = i;
    }
    if (s_conv_y[i] > line) {
      break;
    }
  }
  if (focus != s_conv_focus) {
    s_conv_focus = focus;
    layer_mark_dirty(s_conv_body);
  }
}

static void prv_conv_layout(void) {
  if (!s_conv_body) {
    return;
  }
  int16_t w = prv_conv_width();
  int16_t y = CONV_GAP / 2;
  ConvEntry e;
  int n = 0;
  while (n < CONV_ENTRIES && prv_conv_entry(n, &e)) {
    s_conv_y[n] = y;
    int16_t h = prv_entry_height(&e, w);
    y = (y + h > CONV_MAX_H - CONV_TAIL_H) ? CONV_MAX_H - CONV_TAIL_H : y + h;
    n++;
  }
  s_conv_count = n;
  s_conv_y[n] = y;
  int16_t total = y + CONV_TAIL_H;
  layer_set_frame(s_conv_body, GRect(0, 0, w, total));
  s_conv_scrolling = true;
  scroll_layer_set_content_size(s_conv_scroll, GSize(w, total));
  s_conv_scrolling = false;
  if (s_conv_follow) {
    prv_conv_scroll_to(prv_conv_anchor());
  }
  prv_conv_update_focus();
  layer_mark_dirty(s_conv_body);
}

static void prv_draw_entry(GContext *ctx, const ConvEntry *e, int16_t y, int16_t h, int16_t w,
                           bool focus) {
  GFont font = prv_entry_font(e);
  GTextAlignment align = prv_entry_align(e);
  int16_t text_w = prv_entry_text_w(e, w);
  if (e->role == ROLE_USER) {
    GSize size = prv_entry_text_size(e, w);
    int16_t bubble_w = size.w + 2 * CONV_BUBBLE_PAD + 2;
    int16_t bubble_x = PBL_IF_ROUND_ELSE((w - bubble_w) / 2, w - CONV_RIGHT - bubble_w);
    GRect bubble = GRect(bubble_x, y, bubble_w, size.h + 2 * CONV_BUBBLE_PAD);
    // Sent but not in the history yet: an outline, filled in once Hermes has it.
    if (e->live) {
      graphics_context_set_stroke_color(ctx, THEME_DIM);
      graphics_draw_rect(ctx, bubble);
    } else {
      graphics_context_set_fill_color(ctx, THEME_DIM);
      graphics_fill_rect(ctx, bubble, 0, GCornerNone);
    }
    graphics_context_set_text_color(ctx, e->live ? THEME_META : USER_BUBBLE_FG);
    graphics_draw_text(ctx, e->text, font,
                       GRect(bubble_x + CONV_BUBBLE_PAD + 1, y + CONV_BUBBLE_PAD - CONV_SLACK,
                             size.w + 2, size.h + CONV_SLACK),
                       GTextOverflowModeWordWrap, align, NULL);
    return;
  }
  if (e->role != ROLE_ASSISTANT) {
    graphics_context_set_text_color(ctx, THEME_META);
    graphics_draw_text(ctx, e->text, font, GRect(CONV_TEXT_X, y - CONV_SLACK, text_w, CONV_MAX_H),
                       GTextOverflowModeWordWrap, align, NULL);
    return;
  }
  // Hermes speaking: a violet label and, on rectangular screens, a rule down the reply. The reply
  // in focus (the one Play acts on) is lit; the rest stay grey.
  GColor accent = (focus || e->live) ? THEME_VOICE : THEME_DIM;
  const char *label = e->live ? "Hermes..." : prv_playback_label(e->id);
  if (!label) {
    label = "Hermes";
  }
  int x = CONV_TEXT_X;
  int mark_w = 0;
  if (e->flags & MESSAGE_FLAG_HAS_AUDIO) {
    mark_w = theme_draw_mark(ctx, GPoint(x, y + 5), THEME_MARK_WAVE, accent);
  }
  theme_draw_label(ctx, GRect(x + mark_w, y, text_w - mark_w, CONV_LABEL_H), label, accent,
                   GTextAlignmentLeft);
  int16_t text_y = y + CONV_LABEL_H;
#if !defined(PBL_ROUND)
  graphics_context_set_fill_color(ctx, accent);
  graphics_fill_rect(ctx, GRect(CONV_BAR_X, text_y, 2, h - CONV_LABEL_H - CONV_GAP), 0, GCornerNone);
#endif
  graphics_context_set_text_color(ctx, THEME_FG);
  graphics_draw_text(ctx, e->text, font, GRect(CONV_TEXT_X, text_y - CONV_SLACK, text_w, CONV_MAX_H),
                     GTextOverflowModeWordWrap, align, NULL);
}

static void prv_draw_tail(GContext *ctx, int16_t y, int16_t w) {
  const char *label = prv_submit_label();
  bool working = prv_working();
  GTextAlignment align = PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft);
  if (working) {
    // Hermes at work: the block cursor from the phone app's title, blinking.
    if (s_cursor_on) {
      graphics_context_set_fill_color(ctx, THEME_SIGNAL);
      graphics_fill_rect(ctx, GRect(PBL_IF_ROUND_ELSE(w / 2 - 3, CONV_TEXT_X), y + 2, 6, 11), 0,
                         GCornerNone);
    }
    graphics_context_set_text_color(ctx, THEME_META);
    graphics_draw_text(ctx, label ? label : "Hermes is working", fonts_get_system_font(FONT_KEY_GOTHIC_14),
                       GRect(CONV_TEXT_X + PBL_IF_ROUND_ELSE(0, 10), y + PBL_IF_ROUND_ELSE(12, -2),
                             w - CONV_TEXT_X - CONV_RIGHT - PBL_IF_ROUND_ELSE(0, 10), 18),
                       GTextOverflowModeTrailingEllipsis, align, NULL);
    return;
  }
  const char *hint;
  const char *hint2 = NULL;
  GColor color = THEME_META;
  if (label && g_model.submit != SUBMIT_COMPLETED) {
    hint = label; // failed, uncertain, read-only, cancelled: say so where the reply would be
    color = THEME_FG;
  } else if (g_model.messages_loading && s_conv_count == 0) {
    hint = "Loading...";
  } else if (g_model.open_session_flags & SESSION_FLAG_READ_ONLY) {
    hint = "Read-only here";
  } else {
    hint = "Select: reply";
    hint2 = s_conv_count > 0 ? "Hold: more" : NULL;
  }
  GRect line = GRect(CONV_TEXT_X, y + 2, w - CONV_TEXT_X - CONV_RIGHT, 14);
  theme_draw_label(ctx, line, hint, color, align);
  if (hint2) {
    line.origin.y += 13;
    theme_draw_label(ctx, line, hint2, THEME_DIM, align);
  }
}

static void prv_conv_body_draw(Layer *layer, GContext *ctx) {
  int16_t w = layer_get_bounds(layer).size.w;
  int16_t top = prv_conv_top();
  int16_t bottom = top + prv_conv_viewport_h();
  ConvEntry e;
  for (int i = 0; i < s_conv_count; i++) {
    if (s_conv_y[i + 1] < top || s_conv_y[i] > bottom) {
      continue; // off screen: skip the text layout
    }
    if (prv_conv_entry(i, &e)) {
      prv_draw_entry(ctx, &e, s_conv_y[i], s_conv_y[i + 1] - s_conv_y[i], w, i == s_conv_focus);
    }
  }
  if (s_conv_y[s_conv_count] <= bottom) {
    prv_draw_tail(ctx, s_conv_y[s_conv_count], w);
  }
}

static void prv_conv_head_draw(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  graphics_context_set_fill_color(ctx, THEME_BG);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  const char *title = g_model.open_session_title[0] ? g_model.open_session_title : "Session";
  int inset = PBL_IF_ROUND_ELSE(30, 5);
  theme_draw_label(ctx, GRect(inset, STATUS_Y + 3, b.size.w - 2 * inset, 14), title, THEME_SIGNAL,
                   PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft));
  graphics_context_set_fill_color(ctx, THEME_DIM);
  graphics_fill_rect(ctx, GRect(0, b.size.h - 1, b.size.w, 1), 0, GCornerNone);
}

static void prv_cursor_tick(void *ctx) {
  s_cursor_timer = NULL;
  if (!s_conv_body || !prv_working()) {
    s_cursor_on = true;
    return;
  }
  s_cursor_on = !s_cursor_on;
  layer_mark_dirty(s_conv_body);
  s_cursor_timer = app_timer_register(CURSOR_MS, prv_cursor_tick, NULL);
}

static void prv_cursor_update(void) {
  if (MOTION_ENABLED && s_conv_body && prv_working() && !s_cursor_timer) {
    s_cursor_timer = app_timer_register(CURSOR_MS, prv_cursor_tick, NULL);
  }
}

static void prv_conv_scrolled(ScrollLayer *scroll, void *ctx) {
  if (!s_conv_scrolling) {
    s_conv_follow = false; // the user is reading: leave the view where they put it
    s_conv_target[0] = '\0';
  }
  prv_conv_update_focus();
}

static void prv_conv_select(ClickRecognizerRef rec, void *ctx) {
  // Read-only sessions (refused by Hermes, or Telegram with replies turned off on the phone)
  // stay readable and playable; the phone also refuses the send. One turn at a time.
  if ((g_model.open_session_flags & SESSION_FLAG_READ_ONLY) || prv_working() ||
      !g_model.open_session_id[0]) {
    theme_vibe_nudge();
    return;
  }
  s_conv_follow = true;
  prv_open_dictation(COMPOSE_REPLY);
}

// Aplite's heap cannot hold a long history and an action menu at once. The menu covers the
// conversation anyway: drop the history, and fetch it again when the conversation shows.
static bool s_history_shed;

static void prv_conv_long_select(ClickRecognizerRef rec, void *ctx) {
  bool audio = s_conv_focus >= 0 && s_conv_focus < g_model.message_count &&
               (g_model.messages[s_conv_focus].flags & MESSAGE_FLAG_HAS_AUDIO);
  if (audio) {
    prv_set_target(g_model.open_session_id, g_model.messages[s_conv_focus].id);
  }
#if defined(PBL_PLATFORM_APLITE)
  if (g_model.message_count) {
    // Come back to the reply that was in focus.
    if (s_conv_focus >= 0 && s_conv_focus < g_model.message_count) {
      strncpy(s_conv_target, g_model.messages[s_conv_focus].id, MESSAGE_ID_LEN - 1);
    }
    model_clear_messages();
    s_history_shed = true;
  }
#endif
  ActionMenuLevel *root = action_menu_level_create(9);
  if (audio) {
    prv_add_play_rows(root);
  }
  action_menu_level_add_action(root, "Newest", prv_msg_action, (void *)ACT_NEWEST);
  action_menu_level_add_action(root, "Refresh", prv_msg_action, (void *)ACT_REFRESH);
  prv_open_action_menu(root);
}

static void prv_conv_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_conv_select);
  window_long_click_subscribe(BUTTON_ID_SELECT, 0, prv_conv_long_select, NULL);
}

static void prv_conv_newest(void) {
  s_conv_follow = true;
  prv_conv_layout();
}

static void prv_conv_refresh(void) {
  if (!g_model.open_session_id[0]) {
    return;
  }
  g_model.messages_loading = true;
  s_conv_follow = true;
  comm_open_session(g_model.open_session_id);
  prv_conv_layout();
}

static void prv_conv_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  theme_window(window);
  s_conv_scroll = scroll_layer_create(GRect(0, CONV_HEAD_H, bounds.size.w, bounds.size.h - CONV_HEAD_H));
  scroll_layer_set_shadow_hidden(s_conv_scroll, true);
  scroll_layer_set_callbacks(s_conv_scroll, (ScrollLayerCallbacks) {
    .click_config_provider = prv_conv_click_config,
    .content_offset_changed_handler = prv_conv_scrolled,
  });
  scroll_layer_set_click_config_onto_window(s_conv_scroll, window);
#if defined(PBL_ROUND)
  scroll_layer_set_paging(s_conv_scroll, true);
#endif
  s_conv_body = layer_create(GRect(0, 0, bounds.size.w, CONV_TAIL_H));
  layer_set_update_proc(s_conv_body, prv_conv_body_draw);
  scroll_layer_add_child(s_conv_scroll, s_conv_body);
  layer_add_child(root, scroll_layer_get_layer(s_conv_scroll));
  s_conv_head = layer_create(GRect(0, 0, bounds.size.w, CONV_HEAD_H));
  layer_set_update_proc(s_conv_head, prv_conv_head_draw);
  layer_add_child(root, s_conv_head);
  s_conv_focus = -1;
  prv_conv_layout();
  prv_cursor_update();
}

static void prv_conv_appear(Window *window) {
  if (s_history_shed) {
    s_history_shed = false;
    char target[MESSAGE_ID_LEN];
    strncpy(target, s_conv_target, MESSAGE_ID_LEN);
    prv_conv_refresh();
    strncpy(s_conv_target, target, MESSAGE_ID_LEN);
  } else {
    prv_conv_layout();
  }
  prv_cursor_update();
}

static void prv_conv_unload(Window *window) {
  if (s_cursor_timer) {
    app_timer_cancel(s_cursor_timer);
    s_cursor_timer = NULL;
  }
  layer_destroy(s_conv_head);
  layer_destroy(s_conv_body);
  scroll_layer_destroy(s_conv_scroll);
  s_conv_head = NULL;
  s_conv_body = NULL;
  s_conv_scroll = NULL;
}

// Open g_model.open_session_id. With [keep_pending], what was just sent (and the turn's state)
// carries over: a new session from the watch, or the reply that opened it.
static void prv_open_conv(bool keep_pending) {
  const Session *s = model_find_session(g_model.open_session_id);
  if (s) {
    strncpy(g_model.open_session_title, s->title, TITLE_LEN - 1);
    g_model.open_session_flags = s->flags;
  }
  if (!keep_pending) {
    g_model.submit = SUBMIT_NONE;
    g_model.submit_detail[0] = '\0';
    model_clear_pending();
  }
  model_clear_messages();
  model_save_prefs();
  s_stream_shown = g_model.stream != NULL;
  if (g_model.open_session_id[0]) {
    g_model.messages_loading = true;
    comm_open_session(g_model.open_session_id);
  } else {
    g_model.messages_loading = false;
  }
  s_conv_follow = true;
  s_conv_target[0] = '\0';

  if (!s_conv_window) {
    s_conv_window = window_create();
    window_set_window_handlers(s_conv_window, (WindowHandlers) {
      .load = prv_conv_load,
      .appear = prv_conv_appear,
      .unload = prv_conv_unload,
    });
  }
  if (window_stack_get_top_window() == s_conv_window) {
    layer_mark_dirty(s_conv_head);
    prv_conv_layout();
    return;
  }
  if (window_stack_contains_window(s_conv_window)) {
    window_stack_remove(s_conv_window, false); // bring it to the top, over whatever covers it
  }
#if defined(PBL_PLATFORM_APLITE)
  // Aplite's heap cannot keep a list under the conversation and still open its menu: the
  // conversation takes the list's place, and Back goes home.
  Window *lists[] = { s_sessions_window, s_audio_window };
  for (size_t i = 0; i < ARRAY_LENGTH(lists); i++) {
    if (lists[i] && window_stack_contains_window(lists[i])) {
      window_stack_remove(lists[i], false);
    }
  }
#endif
  window_stack_push(s_conv_window, true);
}

// Open a session's conversation with one of its replies already showing and in view.
static void prv_open_conv_with(const char *session_id, const char *message_id, const char *title,
                               uint8_t flags, const char *text) {
  strncpy(g_model.open_session_id, session_id, SESSION_ID_LEN - 1);
  strncpy(g_model.open_session_title, title, TITLE_LEN - 1);
  g_model.open_session_flags = 0;
  prv_open_conv(false);
  strncpy(s_conv_target, message_id, MESSAGE_ID_LEN - 1);
  // Shown at once; the history replaces it when it arrives.
  model_set_message(0, message_id, ROLE_ASSISTANT, flags & ~MESSAGE_FLAG_REQUESTED, text);
  prv_conv_layout();
}

static bool prv_conv_on_top(void) {
  return s_conv_window && window_stack_get_top_window() == s_conv_window;
}

// ---------------------------------------------------------------------------
// Recent audio window: audio replies from any session, newest first

static MenuLayer *s_audio_menu;

// "now", "5m", "3h", "2d": how long ago, from the watch's clock (synced to the phone).
static void prv_age(uint32_t then, char *buf, size_t len) {
  time_t now = time(NULL);
  int32_t ago = then && (uint32_t)now > then ? (int32_t)((uint32_t)now - then) : 0;
  if (ago < 60) {
    snprintf(buf, len, "now");
  } else if (ago < 3600) {
    snprintf(buf, len, "%dm", (int)(ago / 60));
  } else if (ago < 86400) {
    snprintf(buf, len, "%dh", (int)(ago / 3600));
  } else {
    snprintf(buf, len, "%dd", (int)(ago / 86400));
  }
}

static uint16_t prv_audio_rows(MenuLayer *menu, uint16_t section, void *ctx) {
  return g_model.audio_count > 0 ? g_model.audio_count : 1;
}

static int16_t prv_audio_header_h(MenuLayer *menu, uint16_t section, void *ctx) {
  return MENU_CELL_BASIC_HEADER_HEIGHT;
}

static void prv_audio_header(GContext *ctx, const Layer *cell, uint16_t section, void *data) {
  if (g_model.conn == CONN_OK) {
    theme_draw_header(ctx, cell, "> Recent audio", THEME_VOICE);
  } else {
    theme_draw_header(ctx, cell, prv_conn_label(), THEME_FG);
  }
}

static void prv_audio_draw(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  if (g_model.audio_count == 0) {
    theme_draw_row(ctx, cell, g_model.audio_loading ? "Loading..." : "No recent audio",
                   "Long press to refresh", 0);
    return;
  }
  const AudioItem *a = &g_model.audio[idx->row];
  char age[8];
  prv_age(a->time, age, sizeof(age));
  char sub[PREVIEW_LEN + 16];
  snprintf(sub, sizeof(sub), "%s %s", age, a->preview);
  theme_draw_row(ctx, cell, a->title[0] ? a->title : "Untitled", sub,
                 THEME_MARK_WAVE | ((a->flags & AUDIO_FLAG_NEW) ? THEME_MARK_NEW : 0));
}

static void prv_open_audio_item(AudioItem *a) {
  a->flags &= ~AUDIO_FLAG_NEW;
  const char *title = a->title[0] ? a->title : "Hermes";
#if defined(CARD_ENABLED)
  // Show the preview at once; the phone answers with the full reply, which replaces it.
  prv_open_message(MSG_MODE_REPLY, a->session_id, a->message_id, title, ROLE_ASSISTANT,
                   MESSAGE_FLAG_HAS_AUDIO, a->preview, false);
  comm_open_reply(a->session_id, a->message_id);
#else
  prv_open_conv_with(a->session_id, a->message_id, title, MESSAGE_FLAG_HAS_AUDIO, a->preview);
#endif
}

static void prv_audio_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  if (g_model.audio_count == 0) {
    comm_list_audio();
    menu_layer_reload_data(menu);
    return;
  }
  prv_open_audio_item(&g_model.audio[idx->row]);
}

static void prv_audio_long_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  comm_list_audio();
  menu_layer_reload_data(menu);
}

static void prv_audio_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_audio_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_audio_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = prv_audio_rows,
    .get_header_height = prv_audio_header_h,
    .draw_header = prv_audio_header,
    .draw_row = prv_audio_draw,
    .select_click = prv_audio_select,
    .select_long_click = prv_audio_long_select,
  });
  theme_menu(s_audio_menu);
  menu_layer_set_click_config_onto_window(s_audio_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_audio_menu));
}

static void prv_audio_unload(Window *window) {
  menu_layer_destroy(s_audio_menu);
  s_audio_menu = NULL;
}

static void prv_open_audio_window(void) {
  if (!s_audio_window) {
    s_audio_window = window_create();
    window_set_window_handlers(s_audio_window, (WindowHandlers) {
      .load = prv_audio_load,
      .unload = prv_audio_unload,
    });
  }
  comm_list_audio();
  window_stack_push(s_audio_window, true);
}
// ---------------------------------------------------------------------------
// Sessions window: every visible session, newest activity first

static MenuLayer *s_sessions_menu;

// "new working telegram read-only": the marks carry the colour, the words stay plain.
static void prv_session_subtitle(const Session *s, char *buf, size_t len) {
  const char *parts[] = {
    (s->flags & SESSION_FLAG_UNREAD) ? "new" : NULL,
    (s->flags & SESSION_FLAG_TELEGRAM) ? "telegram" : NULL,
    (s->flags & SESSION_FLAG_READ_ONLY) ? "read-only" : NULL,
    (s->flags & SESSION_FLAG_ACTIVE) ? "working" : NULL,
  };
  buf[0] = '\0';
  for (size_t i = 0; i < ARRAY_LENGTH(parts); i++) {
    if (parts[i]) {
      size_t used = strlen(buf);
      snprintf(buf + used, len - used, "%s%s", used ? " " : "", parts[i]);
    }
  }
}

static uint16_t prv_sessions_rows(MenuLayer *menu, uint16_t section, void *ctx) {
  return g_model.session_count > 0 ? g_model.session_count : 1;
}

static int16_t prv_sessions_header_h(MenuLayer *menu, uint16_t section, void *ctx) {
  return MENU_CELL_BASIC_HEADER_HEIGHT;
}

static void prv_sessions_header(GContext *ctx, const Layer *cell, uint16_t section, void *data) {
  if (g_model.conn == CONN_OK) {
    theme_draw_header(ctx, cell, "// Sessions", THEME_META);
  } else {
    theme_draw_header(ctx, cell, prv_conn_label(), THEME_FG);
  }
}

static void prv_sessions_draw(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  if (g_model.session_count == 0) {
    theme_draw_row(ctx, cell, g_model.sessions_loading ? "Loading..." : "No sessions",
                   "Long press to refresh", 0);
    return;
  }
  const Session *s = &g_model.sessions[idx->row];
  char sub[40];
  prv_session_subtitle(s, sub, sizeof(sub));
  uint8_t marks = ((s->flags & SESSION_FLAG_UNREAD) ? THEME_MARK_NEW : 0) |
                  ((s->flags & SESSION_FLAG_ACTIVE) ? THEME_MARK_WORKING : 0);
  theme_draw_row(ctx, cell, s->title[0] ? s->title : "Untitled", sub[0] ? sub : NULL, marks);
}

static void prv_sessions_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  if (g_model.session_count == 0) {
    comm_list_sessions();
    menu_layer_reload_data(menu);
    return;
  }
  Session *s = &g_model.sessions[idx->row];
  s->flags &= ~SESSION_FLAG_UNREAD;
  strncpy(g_model.open_session_id, s->id, SESSION_ID_LEN - 1);
  prv_open_conv(false);
}

static void prv_sessions_long_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  comm_list_sessions();
  menu_layer_reload_data(menu);
}

static void prv_sessions_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_sessions_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_sessions_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = prv_sessions_rows,
    .get_header_height = prv_sessions_header_h,
    .draw_header = prv_sessions_header,
    .draw_row = prv_sessions_draw,
    .select_click = prv_sessions_select,
    .select_long_click = prv_sessions_long_select,
  });
  theme_menu(s_sessions_menu);
  menu_layer_set_click_config_onto_window(s_sessions_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_sessions_menu));
  // Start on the session opened last, if it is listed.
  for (int i = 0; i < g_model.session_count; i++) {
    if (strcmp(g_model.sessions[i].id, g_model.open_session_id) == 0) {
      menu_layer_set_selected_index(s_sessions_menu, MenuIndex(0, i), MenuRowAlignCenter, false);
      break;
    }
  }
}

static void prv_sessions_unload(Window *window) {
  menu_layer_destroy(s_sessions_menu);
  s_sessions_menu = NULL;
}

static void prv_open_sessions_window(void) {
  if (!s_sessions_window) {
    s_sessions_window = window_create();
    window_set_window_handlers(s_sessions_window, (WindowHandlers) {
      .load = prv_sessions_load,
      .unload = prv_sessions_unload,
    });
  }
  comm_list_sessions();
  window_stack_push(s_sessions_window, true);
}

// ---------------------------------------------------------------------------
// Home: New session, Continue (the last session), Recent audio, Sessions

typedef enum { HOME_NEW, HOME_CONTINUE, HOME_AUDIO, HOME_SESSIONS } HomeRow;

static Window *s_main_window;
static MenuLayer *s_main_menu;

static bool prv_has_last_session(void) {
  return g_model.open_session_id[0] != '\0';
}

static HomeRow prv_home_row(int row) {
  if (!prv_has_last_session() && row >= HOME_CONTINUE) {
    row++;
  }
  return (HomeRow)row;
}

static uint16_t prv_main_rows(MenuLayer *menu, uint16_t section, void *ctx) {
  return prv_has_last_session() ? 4 : 3;
}

static int16_t prv_main_header_h(MenuLayer *menu, uint16_t section, void *ctx) {
  return MENU_CELL_BASIC_HEADER_HEIGHT;
}

static void prv_main_header(GContext *ctx, const Layer *cell, uint16_t section, void *data) {
  // "> Connected" in green; anything else is something to look at.
  char label[DETAIL_LEN + 4];
  bool ok = g_model.conn == CONN_OK;
  snprintf(label, sizeof(label), "%s %s", ok ? ">" : "!", prv_conn_label());
  theme_draw_header(ctx, cell, label, ok ? THEME_SIGNAL : THEME_FG);
}

static void prv_audio_summary(char *buf, size_t len, bool *fresh_out) {
  int fresh = 0;
  for (int i = 0; i < g_model.audio_count; i++) {
    if (g_model.audio[i].flags & AUDIO_FLAG_NEW) {
      fresh++;
    }
  }
  *fresh_out = fresh > 0;
  if (fresh > 0) {
    snprintf(buf, len, "%d new", fresh);
  } else if (g_model.audio_count > 0) {
    snprintf(buf, len, "Latest: %s", g_model.audio[0].title);
  } else {
    snprintf(buf, len, "Audio from any session");
  }
}

static void prv_sessions_summary(char *buf, size_t len, uint8_t *marks) {
  int fresh = 0;
  bool working = false;
  for (int i = 0; i < g_model.session_count; i++) {
    fresh += (g_model.sessions[i].flags & SESSION_FLAG_UNREAD) ? 1 : 0;
    working |= (g_model.sessions[i].flags & SESSION_FLAG_ACTIVE) != 0;
  }
  *marks = (fresh ? THEME_MARK_NEW : 0) | (working ? THEME_MARK_WORKING : 0);
  if (fresh > 0) {
    snprintf(buf, len, "%d new of %d", fresh, g_model.session_count);
  } else if (g_model.session_count > 0) {
    snprintf(buf, len, "%d recent", g_model.session_count);
  } else {
    snprintf(buf, len, g_model.sessions_loading ? "Loading..." : "None yet");
  }
}

static void prv_main_draw(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  char sub[TITLE_LEN + 12];
  switch (prv_home_row(idx->row)) {
    case HOME_NEW:
      theme_draw_row(ctx, cell, "New session", "Dictate or quick reply", 0);
      break;
    case HOME_CONTINUE: {
      const Session *s = model_find_session(g_model.open_session_id);
      const char *title = s && s->title[0] ? s->title : g_model.open_session_title;
      uint8_t marks = s ? (((s->flags & SESSION_FLAG_UNREAD) ? THEME_MARK_NEW : 0) |
                           ((s->flags & SESSION_FLAG_ACTIVE) ? THEME_MARK_WORKING : 0)) : 0;
      theme_draw_row(ctx, cell, "Continue", title[0] ? title : "Last session", marks);
      break;
    }
    case HOME_AUDIO: {
      bool fresh;
      prv_audio_summary(sub, sizeof(sub), &fresh);
      theme_draw_row(ctx, cell, "Recent audio", sub, THEME_MARK_WAVE | (fresh ? THEME_MARK_NEW : 0));
      break;
    }
    case HOME_SESSIONS: {
      uint8_t marks;
      prv_sessions_summary(sub, sizeof(sub), &marks);
      theme_draw_row(ctx, cell, "Sessions", sub, marks);
      break;
    }
  }
}

static void prv_main_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  switch (prv_home_row(idx->row)) {
    case HOME_NEW: prv_open_compose(COMPOSE_NEW_SESSION); break;
    case HOME_CONTINUE: prv_open_conv(false); break;
    case HOME_AUDIO: prv_open_audio_window(); break;
    case HOME_SESSIONS: prv_open_sessions_window(); break;
  }
}

static void prv_main_long_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  comm_list_sessions();
  comm_list_audio();
  menu_layer_reload_data(menu);
}

static void prv_main_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_main_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_main_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = prv_main_rows,
    .get_header_height = prv_main_header_h,
    .draw_header = prv_main_header,
    .draw_row = prv_main_draw,
    .select_click = prv_main_select,
    .select_long_click = prv_main_long_select,
  });
  theme_menu(s_main_menu);
  menu_layer_set_click_config_onto_window(s_main_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_main_menu));
}

static void prv_main_unload(Window *window) {
  menu_layer_destroy(s_main_menu);
  s_main_menu = NULL;
}

// ---------------------------------------------------------------------------
// Comm callbacks

void ui_sessions_changed(void) {
  // A session opened before the list arrived (Quick launch) gets its title now.
  const Session *s = model_find_session(g_model.open_session_id);
  if (s && (!g_model.open_session_title[0] || g_model.open_session_flags != s->flags)) {
    strncpy(g_model.open_session_title, s->title, TITLE_LEN - 1);
    g_model.open_session_flags = s->flags;
    if (s_conv_head) {
      layer_mark_dirty(s_conv_head);
      layer_mark_dirty(s_conv_body);
    }
  }
  if (s_main_menu) {
    menu_layer_reload_data(s_main_menu);
  }
  if (!s_sessions_menu) {
    return;
  }
  menu_layer_reload_data(s_sessions_menu);
  // Keep the previously opened session highlighted across refreshes.
  if (!g_model.sessions_loading && window_stack_get_top_window() == s_sessions_window) {
    for (int i = 0; i < g_model.session_count; i++) {
      if (strcmp(g_model.sessions[i].id, g_model.open_session_id) == 0) {
        menu_layer_set_selected_index(s_sessions_menu, MenuIndex(0, i), MenuRowAlignCenter, false);
        break;
      }
    }
  }
}

static bool s_play_latest_pending;

static void prv_play_latest(void) {
  s_play_latest_pending = false;
  if (g_model.audio_count == 0 || window_stack_get_top_window() != s_audio_window) {
    return; // nothing to play, or the user has already moved on
  }
  char session_id[SESSION_ID_LEN];
  char message_id[MESSAGE_ID_LEN];
  strncpy(session_id, g_model.audio[0].session_id, SESSION_ID_LEN);
  strncpy(message_id, g_model.audio[0].message_id, MESSAGE_ID_LEN);
  prv_open_audio_item(&g_model.audio[0]);
  prv_play(session_id, message_id, prv_route());
}

void ui_audio_changed(void) {
  // The first complete list is the phone's saved copy, which it keeps current while it checks
  // Hermes: good enough to start playing at once.
  if (s_play_latest_pending && !g_model.audio_loading) {
    prv_play_latest();
  }
  if (s_audio_menu) {
    menu_layer_reload_data(s_audio_menu);
  }
  if (s_main_menu) {
    menu_layer_reload_data(s_main_menu);
  }
}

void ui_messages_changed(void) {
  if (!g_model.stream) {
    s_stream_shown = false;
  }
  if (s_conv_body) {
    prv_conv_layout();
    if (!g_model.messages_loading) {
      s_conv_follow = false;
      s_conv_target[0] = '\0';
    }
  }
}

void ui_stream_changed(void) {
  if (!s_conv_body) {
    return;
  }
  // The first words of a reply bring the exchange into view; after that the reply grows below
  // without moving the screen, so it can be read from the start.
  bool started = !s_stream_shown;
  s_stream_shown = true;
  if (started) {
    s_conv_follow = true;
  }
  prv_conv_layout();
  s_conv_follow = false;
}

void ui_status_changed(void) {
  if (s_main_menu) {
    menu_layer_reload_data(s_main_menu);
  }
  if (s_sessions_menu) {
    menu_layer_reload_data(s_sessions_menu);
  }
  if (s_audio_menu) {
    menu_layer_reload_data(s_audio_menu);
  }
}

void ui_submit_changed(void) {
  if (s_conv_body) {
    prv_conv_layout();
    prv_cursor_update();
  }
  static SubmitState s_last_submit;
  if (g_model.submit != s_last_submit) {
    if (g_model.submit == SUBMIT_COMPLETED) {
      s_conv_follow = true; // the history that follows brings the whole exchange into view
    }
    if (g_model.submit == SUBMIT_QUEUED) {
      theme_vibe_sent();
    } else if (g_model.submit == SUBMIT_FAILED || g_model.submit == SUBMIT_UNCERTAIN ||
               g_model.submit == SUBMIT_READ_ONLY) {
      theme_vibe_needs_you();
    }
  }
  s_last_submit = g_model.submit;
}

void ui_playback_changed(void) {
#if defined(CARD_ENABLED)
  prv_msg_update_status();
#endif
  if (s_conv_body) {
    layer_mark_dirty(s_conv_body);
  }
  if (g_model.playback == PLAYBACK_NEEDS_SPEAKER) {
    theme_vibe_needs_you();
  }
}

void ui_session_opened(void) {
  // A new session was created from the watch: its conversation is usually showing already,
  // waiting for the name.
  if (s_conv_window && window_stack_contains_window(s_conv_window)) {
    if (s_conv_head) {
      layer_mark_dirty(s_conv_head);
    }
    return;
  }
  prv_open_conv(true);
}

#if defined(BANNER_ENABLED)
// The reply on the banner, kept to open its conversation with the reply already in it.
static char s_alert_session_id[SESSION_ID_LEN];
static char s_alert_id[MESSAGE_ID_LEN];
static char s_alert_title[TITLE_LEN];
static uint8_t s_alert_flags;
static char *s_alert_text;

static void prv_alert_open(void) {
  prv_open_conv_with(s_alert_session_id, s_alert_id, s_alert_title, s_alert_flags,
                     s_alert_text ? s_alert_text : "");
  free(s_alert_text);
  s_alert_text = NULL;
}
#endif

void ui_reply_received(const char *session_id, const char *message_id,
                       const char *title, const char *text, uint8_t flags) {
  if (flags & MESSAGE_FLAG_REQUESTED) {
#if defined(CARD_ENABLED)
    // The full text of a card opened from Recent audio: fill it in quietly, if still showing.
    if (s_msg_window && window_stack_get_top_window() == s_msg_window &&
        strcmp(message_id, s_msg_id) == 0) {
      s_msg_flags = flags & ~MESSAGE_FLAG_REQUESTED;
      prv_msg_set_body(text, s_msg_flags);
    }
#endif
    return;
  }
  if (flags & MESSAGE_FLAG_HAS_AUDIO) {
    theme_vibe_voice();
  } else {
    theme_vibe_text();
  }
  // Already reading this conversation: the reply lands in it (the phone sends the history next).
  if (prv_conv_on_top() && strcmp(session_id, g_model.open_session_id) == 0) {
    return;
  }
#if defined(BANNER_ENABLED)
  strncpy(s_alert_session_id, session_id, SESSION_ID_LEN - 1);
  strncpy(s_alert_id, message_id, MESSAGE_ID_LEN - 1);
  strncpy(s_alert_title, title[0] ? title : "Hermes", TITLE_LEN - 1);
  s_alert_flags = flags;
  free(s_alert_text);
  size_t len = strlen(text);
  s_alert_text = malloc(len + 1);
  if (s_alert_text) {
    memcpy(s_alert_text, text, len + 1);
  }
  banner_show(s_alert_title, text, flags & MESSAGE_FLAG_HAS_AUDIO, prv_alert_open);
#else
  prv_open_conv_with(session_id, message_id, title[0] ? title : "Hermes", flags, text);
#endif
}

// ---------------------------------------------------------------------------

static void prv_quick_launch(void *ctx) {
  switch (g_model.quick_launch) {
    case QUICK_DICTATE:
      prv_open_dictation(COMPOSE_NEW_SESSION);
      break;
    case QUICK_RECENT_AUDIO:
      prv_open_audio_window();
      break;
    case QUICK_PLAY_LATEST:
      // Recent audio shows while the list arrives; the newest reply then opens and plays.
      s_play_latest_pending = true;
      prv_open_audio_window();
      break;
    case QUICK_LAST_SESSION:
      if (g_model.open_session_id[0]) {
        prv_open_conv(false);
      }
      break;
    default:
      break;
  }
}

void ui_init(void) {
  theme_init();
  s_main_window = window_create();
  window_set_window_handlers(s_main_window, (WindowHandlers) {
    .load = prv_main_load,
    .unload = prv_main_unload,
  });
  window_stack_push(s_main_window, true);
  if (launch_reason() == APP_LAUNCH_QUICK_LAUNCH && g_model.quick_launch != QUICK_HOME) {
    // Straight to the shortcut, no splash; Back still lands on Home. Started once the event
    // loop runs, so dictation opens over a window that has loaded.
    app_timer_register(50, prv_quick_launch, NULL);
    return;
  }
  splash_show();
}

void ui_deinit(void) {
  if (s_dictation) {
    dictation_session_destroy(s_dictation);
  }
#if defined(BANNER_ENABLED)
  banner_deinit();
  free(s_alert_text);
#endif
#if defined(CARD_ENABLED)
  if (s_msg_window) {
    window_destroy(s_msg_window);
  }
#endif
  if (s_compose_window) {
    window_destroy(s_compose_window);
  }
  if (s_conv_window) {
    window_destroy(s_conv_window);
  }
  if (s_sessions_window) {
    window_destroy(s_sessions_window);
  }
  if (s_audio_window) {
    window_destroy(s_audio_window);
  }
  window_destroy(s_main_window);
  splash_deinit();
  theme_deinit();
}
