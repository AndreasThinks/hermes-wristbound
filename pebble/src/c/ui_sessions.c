#include "wristbound.h"

// Root window: status header, "New session", "Settings", then sessions (newest activity first).

static Window *s_window;
static MenuLayer *s_menu;
static char s_status[STATUS_LEN];
#define FIXED_ROWS 2

static uint16_t num_rows(MenuLayer *m, uint16_t section, void *ctx) {
  uint16_t n = FIXED_ROWS + g.session_count;
  if (g.session_count == 0) n++;  // placeholder row
  return n;
}

static int16_t header_height(MenuLayer *m, uint16_t section, void *ctx) {
  return MENU_CELL_BASIC_HEADER_HEIGHT;
}

static void draw_header(GContext *ctx, const Layer *cell, uint16_t section, void *data) {
  status_line(s_status, sizeof(s_status));
  menu_cell_basic_header_draw(ctx, cell, s_status);
}

static void draw_row(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  if (idx->row == 0) {
    menu_cell_basic_draw(ctx, cell, "New session", "Explicitly start fresh", NULL);
    return;
  }
  if (idx->row == 1) {
    menu_cell_basic_draw(ctx, cell, "Settings", g.pref_include_telegram ? "Telegram: shown" : "Telegram: hidden", NULL);
    return;
  }
  if (g.session_count == 0) {
    const char *msg = !g.phone_seen ? "Open Wristbound on phone"
                      : (g.sessions_loaded ? "No sessions" : "Loading…");
    menu_cell_basic_draw(ctx, cell, msg, NULL, NULL);
    return;
  }
  SessionItem *s = &g.sessions[idx->row - FIXED_ROWS];
  static char sub[40];
  snprintf(sub, sizeof(sub), "%s%s%s",
           (s->flags & SF_UNREAD) ? "● New  " : "",
           (s->flags & SF_TELEGRAM) ? "Telegram " : "",
           (s->flags & SF_ACTIVE) ? "· active" : "");
  menu_cell_basic_draw(ctx, cell, s->title[0] ? s->title : "(untitled)", sub[0] ? sub : NULL, NULL);
}

static void select_click(MenuLayer *m, MenuIndex *idx, void *ctx) {
  if (idx->row == 0) {
    compose_window_push("");
  } else if (idx->row == 1) {
    settings_window_push();
  } else if (g.session_count > 0) {
    SessionItem *s = &g.sessions[idx->row - FIXED_ROWS];
    messages_window_push(s->id, s->title);
  } else {
    comm_hello();
  }
}

static void window_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  s_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks){
    .get_num_rows = num_rows,
    .get_header_height = header_height,
    .draw_header = draw_header,
    .draw_row = draw_row,
    .select_click = select_click,
  });
#if defined(PBL_COLOR)
  menu_layer_set_highlight_colors(s_menu, GColorCobaltBlue, GColorWhite);
#endif
  menu_layer_set_click_config_onto_window(s_menu, w);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void window_appear(Window *w) {
  comm_list_sessions();
}

static void window_unload(Window *w) {
  menu_layer_destroy(s_menu);
  s_menu = NULL;
}

void sessions_window_push(void) {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers){
    .load = window_load, .appear = window_appear, .unload = window_unload,
  });
  window_stack_push(s_window, true);
}

void sessions_window_refresh(void) {
  if (s_menu) menu_layer_reload_data(s_menu);
}
