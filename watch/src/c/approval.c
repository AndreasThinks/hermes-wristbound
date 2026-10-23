#include "approval.h"
#include "model.h"
#include "theme.h"

#if defined(APPROVAL_ENABLED)

#if defined(PBL_PLATFORM_APLITE)
#define TEXT_CAP 200
#else
#define TEXT_CAP 480
#endif
#define PAD_X PBL_IF_ROUND_ELSE(24, 5)
#define HEAD_Y PBL_IF_ROUND_ELSE(14, 4)
#define BODY_Y (HEAD_Y + 30)
#define PILL_H 26
#define PILL_BOTTOM PBL_IF_ROUND_ELSE(18, 4)

static const char *const s_labels[APPROVAL_CHOICES] = {
  "Allow once", "Allow session", "Always allow", "Deny",
};

static Window *s_window;
static Layer *s_layer;
static char s_session_id[SESSION_ID_LEN];
static char s_request_id[MESSAGE_ID_LEN];
static char s_title[TITLE_LEN];
static char *s_text;
static uint8_t s_choices;
static int s_choice;
static ApprovalAnswerHandler s_on_answer;

static bool prv_open(void) {
  return s_request_id[0] != '\0';
}

static void prv_clear(void) {
  s_request_id[0] = '\0';
  s_session_id[0] = '\0';
  free(s_text);
  s_text = NULL;
}

static GRect prv_pill(GRect b) {
  int inset = PBL_IF_ROUND_ELSE(30, PAD_X);
  return GRect(inset, b.size.h - PILL_BOTTOM - PILL_H, b.size.w - 2 * inset, PILL_H);
}

static void prv_draw(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  graphics_context_set_fill_color(ctx, THEME_BG);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  int w = b.size.w - 2 * PAD_X;
  GTextAlignment align = PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft);

  theme_draw_label(ctx, GRect(PAD_X, HEAD_Y, w, 14), "Hermes asks", THEME_SIGNAL, align);
  theme_draw_label(ctx, GRect(PAD_X, HEAD_Y + 14, w, 14), s_title, THEME_META, align);

  GRect pill = prv_pill(b);
  graphics_context_set_text_color(ctx, THEME_FG);
  graphics_draw_text(ctx, s_text ? s_text : "", fonts_get_system_font(FONT_KEY_GOTHIC_18),
                     GRect(PAD_X, BODY_Y, w, pill.origin.y - BODY_Y - 4), GTextOverflowModeTrailingEllipsis,
                     align, NULL);

  // The answer Select sends, with a mark either side for Up and Down.
  graphics_context_set_fill_color(ctx, THEME_SIGNAL);
  graphics_fill_rect(ctx, pill, 0, GCornerNone);
  graphics_context_set_fill_color(ctx, THEME_BG);
  int mid = pill.origin.y + PILL_H / 2;
  for (int i = 0; i < 4; i++) {
    graphics_fill_rect(ctx, GRect(pill.origin.x + 4 + i, mid - i, 1, 2 * i + 1), 0, GCornerNone);
    graphics_fill_rect(ctx, GRect(pill.origin.x + pill.size.w - 5 - i, mid - i, 1, 2 * i + 1), 0, GCornerNone);
  }
  graphics_context_set_text_color(ctx, GColorBlack);
  graphics_draw_text(ctx, s_labels[s_choice], fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                     GRect(pill.origin.x + 10, pill.origin.y - 1, pill.size.w - 20, PILL_H),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
}

static void prv_step(int dir) {
  for (int i = 1; i <= APPROVAL_CHOICES; i++) {
    int next = (s_choice + dir * i + APPROVAL_CHOICES * 2) % APPROVAL_CHOICES;
    if (s_choices & (1 << next)) {
      s_choice = next;
      break;
    }
  }
  layer_mark_dirty(s_layer);
}

static void prv_up(ClickRecognizerRef rec, void *ctx) {
  prv_step(-1);
}

static void prv_down(ClickRecognizerRef rec, void *ctx) {
  prv_step(1);
}

static void prv_select(ClickRecognizerRef rec, void *ctx) {
  if (!prv_open()) {
    return;
  }
  if (s_on_answer) {
    s_on_answer(s_session_id, s_request_id, (ApprovalChoice)s_choice);
  }
  theme_vibe_sent();
  prv_clear();
  window_stack_remove(s_window, true);
}

static void prv_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select);
  window_single_repeating_click_subscribe(BUTTON_ID_UP, 200, prv_up);
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, 200, prv_down);
}

static void prv_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_layer = layer_create(layer_get_bounds(root));
  layer_set_update_proc(s_layer, prv_draw);
  layer_add_child(root, s_layer);
}

static void prv_unload(Window *window) {
  layer_destroy(s_layer);
  s_layer = NULL;
}

void approval_show(const char *session_id, const char *request_id, const char *title, const char *text,
                   uint8_t choices, ApprovalAnswerHandler on_answer) {
  bool same = prv_open() && strcmp(s_request_id, request_id) == 0;
  if (!same) {
    prv_clear();
    s_text = malloc(TEXT_CAP);
    if (!s_text) {
      return;
    }
    strncpy(s_session_id, session_id, SESSION_ID_LEN - 1);
    s_session_id[SESSION_ID_LEN - 1] = '\0';
    strncpy(s_request_id, request_id, MESSAGE_ID_LEN - 1);
    s_request_id[MESSAGE_ID_LEN - 1] = '\0';
    // Cut on a character boundary.
    size_t len = strlen(text);
    if (len >= TEXT_CAP) {
      len = TEXT_CAP - 1;
      while (len > 0 && ((uint8_t)text[len] & 0xC0) == 0x80) {
        len--;
      }
    }
    memcpy(s_text, text, len);
    s_text[len] = '\0';
  }
  snprintf(s_title, sizeof(s_title), "%s", title && title[0] ? title : "Hermes");
  s_choices = (choices & ((1 << APPROVAL_CHOICES) - 1)) | (1 << APPROVAL_DENY);
  s_choice = APPROVAL_DENY;
  s_on_answer = on_answer;
  if (!same) {
    theme_vibe_needs_you();
  }
  approval_reopen();
}

void approval_reopen(void) {
  if (!prv_open()) {
    return;
  }
  if (!s_window) {
    s_window = window_create();
    window_set_background_color(s_window, THEME_BG);
    window_set_click_config_provider(s_window, prv_click_config);
    window_set_window_handlers(s_window, (WindowHandlers) {
      .load = prv_load,
      .unload = prv_unload,
    });
  }
  if (window_stack_get_top_window() == s_window) {
    layer_mark_dirty(s_layer);
    return;
  }
  if (window_stack_contains_window(s_window)) {
    window_stack_remove(s_window, false);
  }
  window_stack_push(s_window, true);
}

void approval_close(const char *request_id) {
  if (!prv_open() || (request_id && request_id[0] && strcmp(request_id, s_request_id) != 0)) {
    return;
  }
  prv_clear();
  if (s_window && window_stack_contains_window(s_window)) {
    window_stack_remove(s_window, true);
  }
}

const char *approval_pending_session(void) {
  return prv_open() ? s_session_id : NULL;
}

void approval_deinit(void) {
  prv_clear();
  if (s_window) {
    window_destroy(s_window);
    s_window = NULL;
  }
}

#endif
