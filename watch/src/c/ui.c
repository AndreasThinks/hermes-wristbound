#include "ui.h"
#include "comm.h"
#include "model.h"

#define DICTATION_LEN 256
#define STATUS_H 22
// Round screens inset text by flowing it; a fixed inset would add to that.
#define TEXT_INSET PBL_IF_ROUND_ELSE(0, 4)

// ---------------------------------------------------------------------------
// Shared helpers

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
  if (strcmp(message_id, g_model.playback_message_id) != 0) {
    return NULL;
  }
  if (g_model.playback_detail[0]) {
    return g_model.playback_detail;
  }
  switch (g_model.playback) {
    case PLAYBACK_PLAYING: return "Playing on phone";
    case PLAYBACK_PAUSED: return "Paused";
    case PLAYBACK_NEEDS_SPEAKER: return "No headphones";
    case PLAYBACK_ERROR: return "Playback failed";
    case PLAYBACK_DONE: return "Finished";
    default: return NULL;
  }
}

static const char *prv_role_label(uint8_t role) {
  switch (role) {
    case ROLE_USER: return "You";
    case ROLE_ASSISTANT: return "Hermes";
    case ROLE_TOOL: return "Tool";
    default: return "System";
  }
}

// The stock header is left-aligned, which the round screen's edge cuts off; centre it there.
static void prv_header_draw(GContext *ctx, const Layer *cell, const char *text) {
#if defined(PBL_ROUND)
  GRect bounds = layer_get_bounds(cell);
  graphics_context_set_text_color(ctx, GColorBlack);
  graphics_draw_text(ctx, text, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD),
                     GRect(0, -1, bounds.size.w, bounds.size.h), GTextOverflowModeTrailingEllipsis,
                     GTextAlignmentCenter, NULL);
#else
  menu_cell_basic_header_draw(ctx, cell, text);
#endif
}

static void prv_menu_colors(MenuLayer *menu) {
#if defined(PBL_COLOR)
  menu_layer_set_highlight_colors(menu, GColorCobaltBlue, GColorWhite);
#endif
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

static void prv_submit_text(const char *text) {
  if (s_compose_mode == COMPOSE_NEW_SESSION) {
    comm_new_session(text);
  } else {
    comm_send(g_model.open_session_id, text);
  }
  ui_submit_changed();
  window_stack_remove(s_compose_window, true);
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
    menu_cell_basic_draw(ctx, cell, "Dictate",
                         prv_has_dictation() ? "Speak your message" : "No microphone", NULL);
  } else {
    menu_cell_basic_draw(ctx, cell, CANNED[idx->row - 1], NULL, NULL);
  }
}

static void prv_compose_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  if (idx->row == 0) {
    if (prv_has_dictation()) {
      dictation_session_start(s_dictation);
    } else {
      vibes_short_pulse();
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
  prv_menu_colors(s_compose_menu);
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

// ---------------------------------------------------------------------------
// Message / reply window

typedef enum { MSG_MODE_MESSAGE, MSG_MODE_REPLY } MessageMode;

typedef enum {
  ACT_PLAY = 1,
  ACT_PLAY_SPEAKER,
  ACT_PAUSE,
  ACT_RESUME,
  ACT_REPLAY,
  ACT_STOP,
  ACT_OPEN_SESSION,
  ACT_REPLY,
  ACT_DISMISS,
} MessageAction;

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

static void prv_open_session_window(void);

static void prv_msg_update_status(void) {
  if (!s_msg_status) {
    return;
  }
  const char *pb = prv_playback_label(s_msg_id);
  text_layer_set_text(s_msg_status, pb ? pb : s_msg_header);
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

static void prv_msg_action(ActionMenu *menu, const ActionMenuItem *item, void *ctx) {
  MessageAction act = (MessageAction)(uintptr_t)action_menu_item_get_action_data(item);
  switch (act) {
    case ACT_PLAY: comm_play(s_msg_session_id, s_msg_id, ROUTE_CURRENT); break;
    case ACT_PLAY_SPEAKER: comm_play(s_msg_session_id, s_msg_id, ROUTE_SPEAKER); break;
    case ACT_PAUSE: comm_simple(CMD_PAUSE); break;
    case ACT_RESUME: comm_simple(CMD_RESUME); break;
    case ACT_REPLAY: comm_replay(s_msg_session_id, s_msg_id); break;
    case ACT_STOP: comm_simple(CMD_STOP); break;
    case ACT_OPEN_SESSION:
    case ACT_REPLY:
      strncpy(g_model.open_session_id, s_msg_session_id, SESSION_ID_LEN - 1);
      g_model.open_session_title[0] = '\0';
      g_model.open_session_flags = 0;
      window_stack_remove(s_msg_window, false);
      prv_open_session_window();
      // Replying needs the session's own policy: a read-only session opens without the composer,
      // and its Reply row says why.
      if (act == ACT_REPLY && !(g_model.open_session_flags & SESSION_FLAG_READ_ONLY)) {
        prv_open_compose(COMPOSE_REPLY);
      }
      break;
    case ACT_DISMISS:
      window_stack_remove(s_msg_window, true);
      break;
  }
}

static void prv_action_menu_closed(ActionMenu *menu, const ActionMenuItem *item, void *ctx) {
  action_menu_hierarchy_destroy(action_menu_get_root_level(menu), NULL, NULL);
}

static void prv_msg_select(ClickRecognizerRef rec, void *ctx) {
  if (s_msg_role != ROLE_ASSISTANT && s_msg_mode != MSG_MODE_REPLY) {
    return; // only assistant replies are playable
  }
  bool playing_this = strcmp(g_model.playback_message_id, s_msg_id) == 0;
  ActionMenuLevel *root = action_menu_level_create(8);
  if (playing_this && g_model.playback == PLAYBACK_PLAYING) {
    action_menu_level_add_action(root, "Pause", prv_msg_action, (void *)ACT_PAUSE);
    action_menu_level_add_action(root, "Stop", prv_msg_action, (void *)ACT_STOP);
  } else if (playing_this && g_model.playback == PLAYBACK_PAUSED) {
    action_menu_level_add_action(root, "Resume", prv_msg_action, (void *)ACT_RESUME);
    action_menu_level_add_action(root, "Stop", prv_msg_action, (void *)ACT_STOP);
  } else {
    action_menu_level_add_action(root, "Play", prv_msg_action, (void *)ACT_PLAY);
  }
  action_menu_level_add_action(root, "Play on phone speaker", prv_msg_action, (void *)ACT_PLAY_SPEAKER);
  action_menu_level_add_action(root, "Replay from start", prv_msg_action, (void *)ACT_REPLAY);
  if (s_msg_mode == MSG_MODE_REPLY) {
    action_menu_level_add_action(root, "Reply", prv_msg_action, (void *)ACT_REPLY);
    action_menu_level_add_action(root, "Open session", prv_msg_action, (void *)ACT_OPEN_SESSION);
    action_menu_level_add_action(root, "Dismiss", prv_msg_action, (void *)ACT_DISMISS);
  }
  ActionMenuConfig config = {
    .root_level = root,
    .colors = {
      .background = PBL_IF_COLOR_ELSE(GColorCobaltBlue, GColorBlack),
      .foreground = GColorWhite,
    },
    .align = ActionMenuAlignCenter,
    .did_close = prv_action_menu_closed,
  };
  action_menu_open(&config);
}

static void prv_msg_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_msg_select);
}

static void prv_msg_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);

  s_msg_status = text_layer_create(GRect(0, 0, bounds.size.w, STATUS_H));
  text_layer_set_font(s_msg_status, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_alignment(s_msg_status, GTextAlignmentCenter);
  text_layer_set_background_color(s_msg_status, PBL_IF_COLOR_ELSE(GColorCobaltBlue, GColorBlack));
  text_layer_set_text_color(s_msg_status, GColorWhite);
  layer_add_child(root, text_layer_get_layer(s_msg_status));

  GRect scroll_frame = GRect(0, STATUS_H, bounds.size.w, bounds.size.h - STATUS_H);
  s_msg_scroll = scroll_layer_create(scroll_frame);
  scroll_layer_set_callbacks(s_msg_scroll, (ScrollLayerCallbacks) {
    .click_config_provider = prv_msg_click_config,
  });
  scroll_layer_set_click_config_onto_window(s_msg_scroll, window);
#if defined(PBL_ROUND)
  scroll_layer_set_paging(s_msg_scroll, true);
#endif

  s_msg_text = text_layer_create(GRect(TEXT_INSET, 0, bounds.size.w - 2 * TEXT_INSET, 4000));
  text_layer_set_font(s_msg_text, fonts_get_system_font(FONT_KEY_GOTHIC_24));
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
}

static void prv_msg_unload(Window *window) {
  text_layer_destroy(s_msg_text);
  scroll_layer_destroy(s_msg_scroll);
  text_layer_destroy(s_msg_status);
  s_msg_text = NULL;
  s_msg_scroll = NULL;
  s_msg_status = NULL;
  free(s_msg_body);
  s_msg_body = NULL;
}

static void prv_open_message(MessageMode mode, const char *session_id, const char *message_id,
                             const char *header, uint8_t role, uint8_t flags, const char *text) {
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

  const char *more = (flags & MESSAGE_FLAG_TRUNCATED) ? "\n\n[Continues on phone]" : "";
  size_t len = strlen(text) + strlen(more) + 1;
  free(s_msg_body);
  s_msg_body = malloc(len);
  if (s_msg_body) {
    snprintf(s_msg_body, len, "%s%s", text, more);
  }

  if (!s_msg_window) {
    s_msg_window = window_create();
    window_set_window_handlers(s_msg_window, (WindowHandlers) {
      .load = prv_msg_load,
      .unload = prv_msg_unload,
    });
  }
  window_stack_push(s_msg_window, true);
}

// ---------------------------------------------------------------------------
// Session window (history + reply)

static Window *s_session_window;
static MenuLayer *s_session_menu;

// Row 0 is "Reply"; message rows follow, newest first.
static int prv_message_for_row(int row) {
  return g_model.message_count - row;
}

static uint16_t prv_session_rows(MenuLayer *menu, uint16_t section, void *ctx) {
  return 1 + (g_model.message_count > 0 ? g_model.message_count : 1);
}

static int16_t prv_session_header_h(MenuLayer *menu, uint16_t section, void *ctx) {
  return MENU_CELL_BASIC_HEADER_HEIGHT;
}

static void prv_session_header(GContext *ctx, const Layer *cell, uint16_t section, void *data) {
  const char *title = g_model.open_session_title[0] ? g_model.open_session_title : "Session";
  prv_header_draw(ctx, cell, title);
}

static void prv_session_draw(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  if (idx->row == 0) {
    const char *sub = prv_submit_label();
    if (!sub && (g_model.open_session_flags & SESSION_FLAG_READ_ONLY)) {
      sub = "Read-only here";
    }
    menu_cell_basic_draw(ctx, cell, "Reply", sub ? sub : "Dictate or quick reply", NULL);
    return;
  }
  if (g_model.message_count == 0) {
    menu_cell_basic_draw(ctx, cell, g_model.messages_loading ? "Loading..." : "No messages",
                         NULL, NULL);
    return;
  }
  int i = prv_message_for_row(idx->row);
  const Message *m = &g_model.messages[i];
  const char *pb = prv_playback_label(m->id);
  menu_cell_basic_draw(ctx, cell, pb ? pb : prv_role_label(m->role), m->text ? m->text : "", NULL);
}

static void prv_session_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  if (idx->row == 0) {
    // Read-only sessions (refused by Hermes, or Telegram with replies turned off on the phone)
    // stay readable and playable; the phone also refuses the send.
    if (g_model.open_session_flags & SESSION_FLAG_READ_ONLY) {
      vibes_short_pulse();
      return;
    }
    prv_open_compose(COMPOSE_REPLY);
    return;
  }
  if (g_model.message_count == 0) {
    comm_open_session(g_model.open_session_id);
    return;
  }
  const Message *m = &g_model.messages[prv_message_for_row(idx->row)];
  prv_open_message(MSG_MODE_MESSAGE, g_model.open_session_id, m->id, prv_role_label(m->role),
                   m->role, m->flags, m->text ? m->text : "");
}

static void prv_session_long_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  // Long press anywhere refreshes the history.
  g_model.messages_loading = true;
  comm_open_session(g_model.open_session_id);
  menu_layer_reload_data(menu);
}

static void prv_session_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_session_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_session_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = prv_session_rows,
    .get_header_height = prv_session_header_h,
    .draw_header = prv_session_header,
    .draw_row = prv_session_draw,
    .select_click = prv_session_select,
    .select_long_click = prv_session_long_select,
  });
  prv_menu_colors(s_session_menu);
  menu_layer_set_click_config_onto_window(s_session_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_session_menu));
}

static void prv_session_unload(Window *window) {
  menu_layer_destroy(s_session_menu);
  s_session_menu = NULL;
}

static void prv_open_session_window(void) {
  const Session *s = model_find_session(g_model.open_session_id);
  if (s) {
    strncpy(g_model.open_session_title, s->title, TITLE_LEN - 1);
    g_model.open_session_flags = s->flags;
  }
  g_model.submit = SUBMIT_NONE;
  g_model.submit_detail[0] = '\0';
  model_clear_messages();
  g_model.messages_loading = true;
  model_save_prefs();
  comm_open_session(g_model.open_session_id);

  if (!s_session_window) {
    s_session_window = window_create();
    window_set_window_handlers(s_session_window, (WindowHandlers) {
      .load = prv_session_load,
      .unload = prv_session_unload,
    });
  }
  if (!window_stack_contains_window(s_session_window)) {
    window_stack_push(s_session_window, true);
  } else if (s_session_menu) {
    menu_layer_reload_data(s_session_menu);
  }
}

// ---------------------------------------------------------------------------
// Main window (sessions + actions)

enum { MAIN_ACT_NEW, MAIN_ACT_TELEGRAM, MAIN_ACT_REFRESH, MAIN_ACT_COUNT };

static Window *s_main_window;
static MenuLayer *s_main_menu;

static uint16_t prv_main_sections(MenuLayer *menu, void *ctx) {
  return 2;
}

static uint16_t prv_main_rows(MenuLayer *menu, uint16_t section, void *ctx) {
  if (section == 0) {
    return g_model.session_count > 0 ? g_model.session_count : 1;
  }
  return MAIN_ACT_COUNT;
}

static int16_t prv_main_header_h(MenuLayer *menu, uint16_t section, void *ctx) {
  return MENU_CELL_BASIC_HEADER_HEIGHT;
}

static void prv_main_header(GContext *ctx, const Layer *cell, uint16_t section, void *data) {
  prv_header_draw(ctx, cell, section == 0 ? prv_conn_label() : "Actions");
}

static void prv_session_subtitle(const Session *s, char *buf, size_t len) {
  snprintf(buf, len, "%s%s%s%s",
           (s->flags & SESSION_FLAG_UNREAD) ? "* New " : "",
           (s->flags & SESSION_FLAG_ACTIVE) ? "Working " : "",
           (s->flags & SESSION_FLAG_TELEGRAM) ? "Telegram " : "",
           (s->flags & SESSION_FLAG_READ_ONLY) ? "Read-only" : "");
}

static void prv_main_draw(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  if (idx->section == 0) {
    if (g_model.session_count == 0) {
      menu_cell_basic_draw(ctx, cell, g_model.sessions_loading ? "Loading..." : "No sessions",
                           "Long press to refresh", NULL);
      return;
    }
    const Session *s = &g_model.sessions[idx->row];
    char sub[40];
    prv_session_subtitle(s, sub, sizeof(sub));
    menu_cell_basic_draw(ctx, cell, s->title[0] ? s->title : "Untitled", sub[0] ? sub : NULL, NULL);
    return;
  }
  switch (idx->row) {
    case MAIN_ACT_NEW:
      menu_cell_basic_draw(ctx, cell, "New session", NULL, NULL);
      break;
    case MAIN_ACT_TELEGRAM:
      menu_cell_basic_draw(ctx, cell, "Telegram sessions",
                           g_model.include_telegram ? "Shown" : "Hidden", NULL);
      break;
    case MAIN_ACT_REFRESH:
      menu_cell_basic_draw(ctx, cell, "Refresh", NULL, NULL);
      break;
  }
}

static void prv_main_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  if (idx->section == 0) {
    if (g_model.session_count == 0) {
      comm_list_sessions();
      menu_layer_reload_data(menu);
      return;
    }
    strncpy(g_model.open_session_id, g_model.sessions[idx->row].id, SESSION_ID_LEN - 1);
    prv_open_session_window();
    return;
  }
  switch (idx->row) {
    case MAIN_ACT_NEW:
      prv_open_compose(COMPOSE_NEW_SESSION);
      break;
    case MAIN_ACT_TELEGRAM:
      g_model.include_telegram = !g_model.include_telegram;
      model_save_prefs();
      comm_list_sessions();
      menu_layer_reload_data(menu);
      break;
    case MAIN_ACT_REFRESH:
      comm_list_sessions();
      menu_layer_reload_data(menu);
      break;
  }
}

static void prv_main_long_select(MenuLayer *menu, MenuIndex *idx, void *ctx) {
  comm_list_sessions();
  menu_layer_reload_data(menu);
}

static void prv_main_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_main_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_main_menu, NULL, (MenuLayerCallbacks) {
    .get_num_sections = prv_main_sections,
    .get_num_rows = prv_main_rows,
    .get_header_height = prv_main_header_h,
    .draw_header = prv_main_header,
    .draw_row = prv_main_draw,
    .select_click = prv_main_select,
    .select_long_click = prv_main_long_select,
  });
  prv_menu_colors(s_main_menu);
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
  if (!s_main_menu) {
    return;
  }
  menu_layer_reload_data(s_main_menu);
  // Keep the previously selected session highlighted across reconnects.
  if (!g_model.sessions_loading && g_model.open_session_id[0] &&
      window_stack_get_top_window() == s_main_window) {
    for (int i = 0; i < g_model.session_count; i++) {
      if (strcmp(g_model.sessions[i].id, g_model.open_session_id) == 0) {
        menu_layer_set_selected_index(s_main_menu, MenuIndex(0, i), MenuRowAlignCenter, false);
        break;
      }
    }
  }
}

void ui_messages_changed(void) {
  if (s_session_menu) {
    menu_layer_reload_data(s_session_menu);
    if (!g_model.messages_loading && g_model.message_count > 0) {
      // Land on the newest message, just below "Reply".
      menu_layer_set_selected_index(s_session_menu, MenuIndex(0, 1), MenuRowAlignCenter, false);
    }
  }
}

void ui_status_changed(void) {
  if (s_main_menu) {
    menu_layer_reload_data(s_main_menu);
  }
}

void ui_submit_changed(void) {
  if (s_session_menu) {
    menu_layer_reload_data(s_session_menu);
  }
  if (g_model.submit == SUBMIT_FAILED || g_model.submit == SUBMIT_UNCERTAIN ||
      g_model.submit == SUBMIT_READ_ONLY) {
    vibes_double_pulse();
  }
}

void ui_playback_changed(void) {
  prv_msg_update_status();
  if (s_session_menu) {
    menu_layer_reload_data(s_session_menu);
  }
  if (g_model.playback == PLAYBACK_NEEDS_SPEAKER) {
    vibes_short_pulse();
  }
}

void ui_session_opened(void) {
  // A new session was created from the watch; show it.
  prv_open_session_window();
}

void ui_reply_received(const char *session_id, const char *message_id,
                       const char *title, const char *text, uint8_t flags) {
  // Always show the card, even over the same session's history: it is the one place with Play,
  // Reply and Open session for this reply. The history behind it refreshes from the phone.
  vibes_short_pulse();
  prv_open_message(MSG_MODE_REPLY, session_id, message_id, title[0] ? title : "New reply",
                   ROLE_ASSISTANT, flags, text);
}

// ---------------------------------------------------------------------------

void ui_init(void) {
  s_main_window = window_create();
  window_set_window_handlers(s_main_window, (WindowHandlers) {
    .load = prv_main_load,
    .unload = prv_main_unload,
  });
  window_stack_push(s_main_window, true);
}

void ui_deinit(void) {
  if (s_dictation) {
    dictation_session_destroy(s_dictation);
  }
  if (s_msg_window) {
    window_destroy(s_msg_window);
  }
  if (s_compose_window) {
    window_destroy(s_compose_window);
  }
  if (s_session_window) {
    window_destroy(s_session_window);
  }
  window_destroy(s_main_window);
}
