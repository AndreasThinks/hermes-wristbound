#include <pebble.h>
#include "splash.h"
#include "theme.h"

// Aplite has no room for the picture or the code to show it.
#if !defined(PBL_PLATFORM_APLITE)

#define SPLASH_MS 1400
#define FRAME_MS 33
// The picture comes in top to bottom, like a screen warming up.
#define REVEAL_MS 400
#define LABEL_H PBL_IF_COLOR_ELSE(12, 16)

static Window *s_window;
static Layer *s_layer;
static GBitmap *s_image;
static AppTimer *s_frame_timer;
static AppTimer *s_close_timer;
static int s_reveal;

static void prv_close(void) {
  if (s_window && window_stack_contains_window(s_window)) {
    window_stack_remove(s_window, true);
  }
}

static void prv_close_timer(void *ctx) {
  s_close_timer = NULL;
  prv_close();
}

static void prv_frame(void *ctx) {
  s_frame_timer = NULL;
  s_reveal += 100 * FRAME_MS / REVEAL_MS;
  if (s_reveal > 100) {
    s_reveal = 100;
  }
  layer_mark_dirty(s_layer);
  if (s_reveal < 100) {
    s_frame_timer = app_timer_register(FRAME_MS, prv_frame, NULL);
  }
}

static void prv_draw(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  GSize img = s_image ? gbitmap_get_bounds(s_image).size : GSize(0, 0);
#if defined(PBL_ROUND)
  // Round: the picture fills the dial (its black ground meets the bezel); the name sits over
  // its lower edge.
  GRect frame = GRect((b.size.w - img.w) / 2, (b.size.h - img.h) / 2, img.w, img.h);
  GRect label = GRect(0, b.size.h - LABEL_H - 22, b.size.w, LABEL_H);
#else
  int top = (b.size.h - img.h - LABEL_H - 4) / 2;
  GRect frame = GRect((b.size.w - img.w) / 2, top, img.w, img.h);
  GRect label = GRect(0, top + img.h + 2, b.size.w, LABEL_H);
#endif
  if (s_image) {
    graphics_context_set_compositing_mode(ctx, GCompOpSet);
    graphics_draw_bitmap_in_rect(ctx, s_image, frame);
    int shown = img.h * s_reveal / 100;
    graphics_context_set_fill_color(ctx, THEME_BG);
    graphics_fill_rect(ctx, GRect(frame.origin.x, frame.origin.y + shown, img.w, img.h - shown), 0,
                       GCornerNone);
    if (s_reveal < 100) {
      // The scanline itself.
      graphics_context_set_fill_color(ctx, THEME_SIGNAL);
      graphics_fill_rect(ctx, GRect(frame.origin.x, frame.origin.y + shown, img.w, 1), 0,
                         GCornerNone);
    }
  }
#if defined(PBL_ROUND)
  GSize text = graphics_text_layout_get_content_size("WRISTBOUND", theme_font_label(), label,
                                                     GTextOverflowModeTrailingEllipsis,
                                                     GTextAlignmentCenter);
  graphics_context_set_fill_color(ctx, THEME_BG);
  graphics_fill_rect(ctx, GRect((b.size.w - text.w) / 2 - 4, label.origin.y - 1, text.w + 8,
                                LABEL_H + 2), 0, GCornerNone);
#endif
  graphics_context_set_text_color(ctx, THEME_SIGNAL);
  graphics_draw_text(ctx, "WRISTBOUND", theme_font_label(), label,
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
}

static void prv_skip(ClickRecognizerRef rec, void *ctx) {
  prv_close();
}

static void prv_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_UP, prv_skip);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_skip);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_skip);
}

static void prv_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  theme_window(window);
  s_image = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_SPLASH);
  s_layer = layer_create(layer_get_bounds(root));
  layer_set_update_proc(s_layer, prv_draw);
  layer_add_child(root, s_layer);
  s_reveal = 0;
  s_frame_timer = app_timer_register(FRAME_MS, prv_frame, NULL);
  s_close_timer = app_timer_register(SPLASH_MS, prv_close_timer, NULL);
}

static void prv_unload(Window *window) {
  if (s_frame_timer) {
    app_timer_cancel(s_frame_timer);
    s_frame_timer = NULL;
  }
  if (s_close_timer) {
    app_timer_cancel(s_close_timer);
    s_close_timer = NULL;
  }
  layer_destroy(s_layer);
  s_layer = NULL;
  // Freed as soon as it is off screen: the menus need the heap more.
  gbitmap_destroy(s_image);
  s_image = NULL;
}

void splash_show(void) {
  if (!s_window) {
    s_window = window_create();
    window_set_click_config_provider(s_window, prv_click_config);
    window_set_window_handlers(s_window, (WindowHandlers) {
      .load = prv_load,
      .unload = prv_unload,
    });
  }
  window_stack_push(s_window, false);
}

void splash_deinit(void) {
  if (s_window) {
    window_destroy(s_window);
    s_window = NULL;
  }
}

#else

void splash_show(void) {}
void splash_deinit(void) {}

#endif
