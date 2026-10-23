#include "banner.h"
#include "model.h"
#include "theme.h"

#if defined(BANNER_ENABLED)

#define SHOW_MS 15000
#define TICK_MS 250
#define SLIDE_MS 180
#define PREVIEW_BYTES 160
#define RULE_H 2
#define PAD_X PBL_IF_ROUND_ELSE(28, 5)
// The dial narrows towards the bottom: round screens stop the text short of it.
#define BODY_BOTTOM PBL_IF_ROUND_ELSE(20, 2)
#define LABEL_Y 7
#define BODY_Y 18

// The window has a clear background, so the window below it stays on screen above the card.
static Window *s_window;
static Layer *s_card;
static AppTimer *s_tick;
static int s_left_ms;
static bool s_audio;
static char s_title[TITLE_LEN + 8];
static char s_text[PREVIEW_BYTES];
static BannerOpenHandler s_open;
static PropertyAnimation *s_slide;

static GRect prv_card_frame(GRect screen) {
  // Rectangular screens: the lower ~45%. Round: the lower bowl, where the dial is still wide.
  int h = PBL_IF_ROUND_ELSE(screen.size.h * 48 / 100, screen.size.h * 45 / 100);
  return GRect(0, screen.size.h - h, screen.size.w, h);
}

static void prv_draw(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  GColor accent = s_audio ? THEME_VOICE : THEME_SIGNAL;
  graphics_context_set_fill_color(ctx, THEME_BG);
  graphics_fill_rect(ctx, b, 0, GCornerNone);

  // The accent rule runs down as the card times out, like phosphor fading.
  int rule = b.size.w * s_left_ms / SHOW_MS;
  graphics_context_set_fill_color(ctx, THEME_DIM);
  graphics_fill_rect(ctx, GRect(0, 0, b.size.w, RULE_H), 0, GCornerNone);
  graphics_context_set_fill_color(ctx, accent);
  graphics_fill_rect(ctx, GRect((b.size.w - rule) / 2, 0, rule, RULE_H), 0, GCornerNone);

  int x = PAD_X;
  int w = b.size.w - 2 * PAD_X;
  int mark_w = theme_draw_mark(ctx, GPoint(x, LABEL_Y + 5), s_audio ? THEME_MARK_WAVE : THEME_MARK_NEW,
                               accent);
  theme_draw_label(ctx, GRect(x + mark_w, LABEL_Y, w - mark_w, 14), s_title, accent,
                   GTextAlignmentLeft);

  graphics_context_set_text_color(ctx, THEME_FG);
  graphics_draw_text(ctx, s_text, fonts_get_system_font(FONT_KEY_GOTHIC_18),
                     GRect(x, BODY_Y, w, b.size.h - BODY_Y - BODY_BOTTOM), GTextOverflowModeTrailingEllipsis,
                     PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft), NULL);
}

static void prv_tick(void *ctx) {
  s_tick = NULL;
  s_left_ms -= TICK_MS;
  if (s_left_ms <= 0) {
    banner_hide();
    return;
  }
  layer_mark_dirty(s_card);
  s_tick = app_timer_register(TICK_MS, prv_tick, NULL);
}

static void prv_select(ClickRecognizerRef rec, void *ctx) {
  BannerOpenHandler handler = s_open;
  banner_hide();
  if (handler) {
    handler();
  }
}

static void prv_dismiss(ClickRecognizerRef rec, void *ctx) {
  banner_hide();
}

static void prv_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select);
  window_single_click_subscribe(BUTTON_ID_BACK, prv_dismiss);
  window_single_click_subscribe(BUTTON_ID_UP, prv_dismiss);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_dismiss);
}

static void prv_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect screen = layer_get_bounds(root);
  GRect frame = prv_card_frame(screen);
  s_card = layer_create(frame);
  layer_set_update_proc(s_card, prv_draw);
  layer_add_child(root, s_card);
  GRect from = frame;
  from.origin.y = screen.size.h;
  layer_set_frame(s_card, from);
  s_slide = property_animation_create_layer_frame(s_card, &from, &frame);
  Animation *a = property_animation_get_animation(s_slide);
  animation_set_duration(a, SLIDE_MS);
  animation_set_curve(a, AnimationCurveEaseOut);
  animation_schedule(a);
}

static void prv_unload(Window *window) {
  if (s_slide) {
    animation_unschedule(property_animation_get_animation(s_slide));
    property_animation_destroy(s_slide);
    s_slide = NULL;
  }
  if (s_tick) {
    app_timer_cancel(s_tick);
    s_tick = NULL;
  }
  layer_destroy(s_card);
  s_card = NULL;
}

void banner_show(const char *title, const char *text, bool audio, BannerOpenHandler on_open) {
  s_audio = audio;
  s_open = on_open;
  snprintf(s_title, sizeof(s_title), "%s", title && title[0] ? title : "Hermes");
  // The start of the message only; the card ellipsises whatever does not fit.
  size_t len = strlen(text ? text : "");
  if (len >= sizeof(s_text)) {
    len = sizeof(s_text) - 1;
    while (len > 0 && ((uint8_t)text[len] & 0xC0) == 0x80) {
      len--;
    }
  }
  memcpy(s_text, text ? text : "", len);
  s_text[len] = '\0';
  for (size_t i = 0; i < len; i++) {
    if (s_text[i] == '\n') {
      s_text[i] = ' '; // one flowing preview, as a notification shows it
    }
  }
  s_left_ms = SHOW_MS;
  if (!s_window) {
    s_window = window_create();
    window_set_background_color(s_window, GColorClear);
    window_set_click_config_provider(s_window, prv_click_config);
    window_set_window_handlers(s_window, (WindowHandlers) {
      .load = prv_load,
      .unload = prv_unload,
    });
  }
  if (window_stack_contains_window(s_window)) {
    // Another reply over the first: bring the card to the top with the new one.
    window_stack_remove(s_window, false);
  }
  window_stack_push(s_window, false);
  if (!s_tick) {
    s_tick = app_timer_register(TICK_MS, prv_tick, NULL);
  }
}

void banner_hide(void) {
  if (s_window && window_stack_contains_window(s_window)) {
    window_stack_remove(s_window, false);
  }
}

bool banner_showing(void) {
  return s_window && window_stack_contains_window(s_window);
}

void banner_deinit(void) {
  if (s_window) {
    window_destroy(s_window);
    s_window = NULL;
  }
}

#endif
