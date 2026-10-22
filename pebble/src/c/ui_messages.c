#include "wristbound.h"

// Recent messages of one session, newest first, with "Reply…" on top.

static Window *s_window;
static MenuLayer *s_menu;
static char s_title[TITLE_LEN];
static char s_header[STATUS_LEN];

static uint16_t num_rows(MenuLayer *m, uint16_t section, void *ctx) {
  return 1 + (g.message_count ? g.message_count : 1);
}

static int16_t header_height(MenuLayer *m, uint16_t section, void *ctx) {
  return MENU_CELL_BASIC_HEADER_HEIGHT;
}

static void draw_header(GContext *ctx, const Layer *cell, uint16_t section, void *data) {
  if (g.tool_status[0]) {
    snprintf(s_header, sizeof(s_header), "%s", g.tool_status);
  } else {
    snprintf(s_header, sizeof(s_header), "%s", s_title);
  }
  menu_cell_basic_header_draw(ctx, cell, s_header);
}

static int16_t cell_height(MenuLayer *m, MenuIndex *idx, void *ctx) {
  return PBL_IF_ROUND_ELSE(60, 56);
}

static void draw_row(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  if (idx->row == 0) {
    menu_cell_basic_draw(ctx, cell, "Reply…", "Dictate or quick reply", NULL);
    return;
  }
  if (g.message_count == 0) {
    menu_cell_basic_draw(ctx, cell, g.messages_loaded ? "No messages" : "Loading…", NULL, NULL);
    return;
  }
  MessageItem *mi = &g.messages[idx->row - 1];
  const char *who = mi->role == ROLE_USER ? "You" : (mi->role == ROLE_ASSISTANT ? "Hermes" : "Tool");
  static char title[24];
  snprintf(title, sizeof(title), "%s%s", who, (mi->flags & MF_HERMES_AUDIO) ? " ♪" : "");
  menu_cell_basic_draw(ctx, cell, title, mi->preview, NULL);
}

static void select_click(MenuLayer *m, MenuIndex *idx, void *ctx) {
  if (idx->row == 0) {
    compose_window_push(g.open_session);
    return;
  }
  if (g.message_count == 0) return;
  MessageItem *mi = &g.messages[idx->row - 1];
  detail_window_push(g.open_session, mi->id, mi->role, mi->flags, s_title, mi->preview);
}

static void window_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  s_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks){
    .get_num_rows = num_rows,
    .get_header_height = header_height,
    .draw_header = draw_header,
    .get_cell_height = cell_height,
    .draw_row = draw_row,
    .select_click = select_click,
  });
#if defined(PBL_COLOR)
  menu_layer_set_highlight_colors(s_menu, GColorCobaltBlue, GColorWhite);
#endif
  menu_layer_set_click_config_onto_window(s_menu, w);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void window_unload(Window *w) {
  menu_layer_destroy(s_menu);
  s_menu = NULL;
  window_destroy(s_window);
  s_window = NULL;
}

void messages_window_push(const char *session_id, const char *title) {
  strncpy(g.open_session, session_id, sizeof(g.open_session) - 1);
  g.open_session[sizeof(g.open_session) - 1] = 0;
  strncpy(s_title, title, sizeof(s_title) - 1);
  s_title[sizeof(s_title) - 1] = 0;
  g.message_count = 0;
  g.messages_loaded = false;
  g.tool_status[0] = 0;
  if (!s_window) {
    s_window = window_create();
    window_set_window_handlers(s_window, (WindowHandlers){ .load = window_load, .unload = window_unload });
    window_stack_push(s_window, true);
  } else {
    messages_window_refresh();
  }
  comm_open_session(session_id);
}

void messages_window_refresh(void) {
  if (s_menu) menu_layer_reload_data(s_menu);
}

bool messages_window_is_open(void) {
  return s_window != NULL;
}
