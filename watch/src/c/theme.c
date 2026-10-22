#include "theme.h"

#define ROW_INSET PBL_IF_ROUND_ELSE(12, 5)
#define MARK_GAP 4
// Round menus shrink unfocused rows; below this they show their title only.
#define ROW_SUBTITLE_MIN_H 36

#define LABEL_Y 3

static GFont s_label_font;

void theme_init(void) {
#if defined(PBL_PLATFORM_APLITE)
  // Aplite's heap is too small to spare for a custom font.
  s_label_font = fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD);
#else
  s_label_font = fonts_load_custom_font(resource_get_handle(RESOURCE_ID_FONT_PIXEL_8));
#endif
}

void theme_deinit(void) {
#if !defined(PBL_PLATFORM_APLITE)
  fonts_unload_custom_font(s_label_font);
#endif
}

GFont theme_font_label(void) {
  return s_label_font;
}

void theme_window(Window *window) {
  window_set_background_color(window, THEME_BG);
}

void theme_menu(MenuLayer *menu) {
  menu_layer_set_normal_colors(menu, THEME_BG, THEME_FG);
  menu_layer_set_highlight_colors(menu, THEME_SIGNAL, GColorBlack);
}

#if defined(PBL_PLATFORM_APLITE)

// Aplite's 24 KB holds code and heap together, so it keeps the stock cells in Phosphor's colours.
void theme_draw_header(GContext *ctx, const Layer *cell, const char *text, GColor color) {
  menu_cell_basic_header_draw(ctx, cell, text);
}

void theme_draw_row(GContext *ctx, const Layer *cell, const char *title, const char *subtitle,
                    uint8_t marks) {
  char sub[80];
  if (subtitle && (marks & THEME_MARK_NEW)) {
    snprintf(sub, sizeof(sub), "* %s", subtitle);
    subtitle = sub;
  }
  menu_cell_basic_draw(ctx, cell, title, subtitle, NULL);
}

#else

void theme_draw_header(GContext *ctx, const Layer *cell, const char *text, GColor color) {
  GRect b = layer_get_bounds(cell);
  // The pixel font is ASCII only: a session title or phone message with anything else in it
  // keeps its own case in Gothic.
  char upper[64];
  bool ascii = true;
  size_t i = 0;
  for (; text[i] && i < sizeof(upper) - 1; i++) {
    char c = text[i];
    ascii &= (uint8_t)c < 0x80;
    upper[i] = (c >= 'a' && c <= 'z') ? (char)(c - 'a' + 'A') : c;
  }
  upper[i] = '\0';
  GFont font = s_label_font;
  int y = LABEL_Y;
  if (!ascii) {
    font = fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD);
    y = -1;
  }
  graphics_context_set_fill_color(ctx, THEME_BG);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  graphics_context_set_text_color(ctx, color);
  graphics_draw_text(ctx, ascii ? upper : text, font,
                     GRect(ROW_INSET, y, b.size.w - 2 * ROW_INSET, b.size.h),
                     GTextOverflowModeTrailingEllipsis,
                     PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft), NULL);
}

static const uint8_t MINI_WAVE[] = { 4, 8, 6, 9, 3 };
#define MINI_WAVE_W ((int)ARRAY_LENGTH(MINI_WAVE) * 3 - 1)

void theme_draw_row(GContext *ctx, const Layer *cell, const char *title, const char *subtitle,
                    uint8_t marks) {
  GRect b = layer_get_bounds(cell);
  bool hl = menu_cell_layer_is_highlighted(cell);
  GColor fg = hl ? GColorBlack : THEME_FG;
  GFont title_font = fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
  GFont sub_font = fonts_get_system_font(FONT_KEY_GOTHIC_18);
  GTextAlignment align = PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft);
  int w = b.size.w - 2 * ROW_INSET;
  // Round menus show a subtitle on the focused row only, as the stock cells do.
  bool show_sub = subtitle && subtitle[0] && b.size.h >= ROW_SUBTITLE_MIN_H &&
                  PBL_IF_ROUND_ELSE(hl, true);

  int title_y = show_sub ? (b.size.h - 44) / 2 - 5 : (b.size.h - 28) / 2 - 4;
  graphics_context_set_text_color(ctx, fg);
  graphics_draw_text(ctx, title, title_font, GRect(ROW_INSET, title_y, w, 28),
                     GTextOverflowModeTrailingEllipsis, align, NULL);
  if (!show_sub) {
    return;
  }

  // Lay the marks and the subtitle out as one line, so a round screen can centre all of it.
  int sub_y = title_y + 25;
  int lead = 0;
  if (marks & THEME_MARK_NEW) {
    lead += 4 + MARK_GAP;
  }
  if (marks & THEME_MARK_WAVE) {
    lead += MINI_WAVE_W + MARK_GAP;
  }
  int trail = (marks & THEME_MARK_WORKING) ? MARK_GAP + 6 : 0;
  GSize text = graphics_text_layout_get_content_size(subtitle, sub_font,
                                                     GRect(0, 0, w - lead - trail, 22),
                                                     GTextOverflowModeTrailingEllipsis,
                                                     GTextAlignmentLeft);
  int x = ROW_INSET;
#if defined(PBL_ROUND)
  x += (w - (lead + text.w + trail)) / 2;
#endif
  int mid = sub_y + 12;

  if (marks & THEME_MARK_NEW) {
    graphics_context_set_fill_color(ctx, hl ? GColorBlack : THEME_SIGNAL);
    graphics_fill_rect(ctx, GRect(x, mid - 4, 4, 8), 0, GCornerNone);
    x += 4 + MARK_GAP;
  }
  if (marks & THEME_MARK_WAVE) {
    graphics_context_set_fill_color(ctx, hl ? GColorBlack : THEME_VOICE);
    for (int i = 0; i < (int)ARRAY_LENGTH(MINI_WAVE); i++) {
      int h = MINI_WAVE[i];
      graphics_fill_rect(ctx, GRect(x + i * 3, mid - h / 2, 2, h), 0, GCornerNone);
    }
    x += MINI_WAVE_W + MARK_GAP;
  }
  graphics_context_set_text_color(ctx, hl ? GColorBlack : THEME_META);
  graphics_draw_text(ctx, subtitle, sub_font, GRect(x, sub_y, text.w + 2, 22),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  if (marks & THEME_MARK_WORKING) {
    graphics_context_set_fill_color(ctx, hl ? GColorBlack : THEME_SIGNAL);
    graphics_fill_rect(ctx, GRect(x + text.w + MARK_GAP, mid - 5, 6, 11), 0, GCornerNone);
  }
}

uint32_t theme_seed(const char *text) {
  // FNV-1a: cheap, and stable across launches.
  uint32_t h = 2166136261u;
  for (; text && *text; text++) {
    h = (h ^ (uint8_t)*text) * 16777619u;
  }
  return h;
}

void theme_draw_wave(GContext *ctx, GRect bounds, uint32_t seed, GColor color, int rise,
                     int phase) {
  const int bar = 2;
  const int step = 3;
  int count = (bounds.size.w + 1) / step;
  int max_h = bounds.size.h;
  int mid = bounds.origin.y + max_h / 2;
  graphics_context_set_fill_color(ctx, color);
  for (int i = 0; i < count; i++) {
    // A smooth-ish envelope with per-bar jitter: one byte of the seed per bar, rotated.
    uint32_t s = seed ^ ((uint32_t)(i + phase) * 2654435761u);
    s ^= s >> 15;
    int jitter = (int)(s & 0xFF);
    int env = 40 + (int)((i * 37 + (seed & 0x3F)) % 60);
    int h = (max_h * env / 100) * (64 + jitter / 2) / 192;
    // Bars grow in left to right as `rise` goes 0 -> 100.
    int start = i * 60 / (count > 0 ? count : 1);
    int grown = rise >= 100 ? 100 : (rise - start) * 100 / 40;
    if (grown < 0) {
      grown = 0;
    } else if (grown > 100) {
      grown = 100;
    }
    h = h * grown / 100;
    if (h < 2) {
      h = 2;
    }
    graphics_fill_rect(ctx, GRect(bounds.origin.x + i * step, mid - h / 2, bar, h), 0,
                       GCornerNone);
  }
}

#endif

static void prv_vibe(const uint32_t *durations, uint32_t count) {
  vibes_cancel();
  vibes_enqueue_custom_pattern((VibePattern) { .durations = durations, .num_segments = count });
}

static const uint32_t VIBE_DOT[] = { 50 };
static const uint32_t VIBE_VOICE[] = { 50, 100, 50, 100, 250 };
static const uint32_t VIBE_NEEDS_YOU[] = { 250, 150, 250, 150, 250 };
static const uint32_t VIBE_SENT[] = { 30, 60, 30 };

void theme_vibe_text(void) {
  prv_vibe(VIBE_DOT, ARRAY_LENGTH(VIBE_DOT));
}

void theme_vibe_voice(void) {
  prv_vibe(VIBE_VOICE, ARRAY_LENGTH(VIBE_VOICE));
}

void theme_vibe_needs_you(void) {
  prv_vibe(VIBE_NEEDS_YOU, ARRAY_LENGTH(VIBE_NEEDS_YOU));
}

void theme_vibe_sent(void) {
  prv_vibe(VIBE_SENT, ARRAY_LENGTH(VIBE_SENT));
}

void theme_vibe_nudge(void) {
  prv_vibe(VIBE_DOT, ARRAY_LENGTH(VIBE_DOT));
}
