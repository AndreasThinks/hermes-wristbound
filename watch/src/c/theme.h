#pragma once

#include <pebble.h>

// Phosphor: black glass, green for anything you can act on, violet whenever Hermes speaks.
// Black-and-white watches draw every accent in white.
#define THEME_BG GColorBlack
#define THEME_FG GColorWhite
#define THEME_SIGNAL PBL_IF_COLOR_ELSE(GColorMediumSpringGreen, GColorWhite)
#define THEME_VOICE PBL_IF_COLOR_ELSE(GColorLavenderIndigo, GColorWhite)
#define THEME_META PBL_IF_COLOR_ELSE(GColorLightGray, GColorWhite)
#define THEME_DIM PBL_IF_COLOR_ELSE(GColorDarkGray, GColorWhite)

// Small marks drawn around a row's subtitle.
#define THEME_MARK_NEW     (1 << 0) // a block before the subtitle: unread or not yet opened
#define THEME_MARK_WAVE    (1 << 1) // a tiny waveform before the subtitle: carries audio
#define THEME_MARK_WORKING (1 << 2) // a block cursor after the subtitle: Hermes is working

void theme_init(void);
void theme_deinit(void);

// Pixel caps for fixed labels (headers, status); only ASCII, so never for Hermes' own text.
GFont theme_font_label(void);

void theme_window(Window *window);
void theme_menu(MenuLayer *menu);
void theme_draw_header(GContext *ctx, const Layer *cell, const char *text, GColor color);
// A fixed label in pixel caps (Gothic when it is not ASCII), drawn at `rect`.
void theme_draw_label(GContext *ctx, GRect rect, const char *text, GColor color,
                      GTextAlignment align);
// One mark (wave, else the new block) with its left edge and vertical centre at `left_mid`.
// Returns the width it took, gap included; 0 for none. Aplite draws a plain block.
int theme_draw_mark(GContext *ctx, GPoint left_mid, uint8_t marks, GColor color);
void theme_draw_row(GContext *ctx, const Layer *cell, const char *title, const char *subtitle,
                    uint8_t marks);

// A decorative waveform (not on Aplite) seeded by `seed` (the same reply always draws the same shape).
// `rise` is 0-100: how far the bars have grown in; `phase` shifts it while audio plays.
void theme_draw_wave(GContext *ctx, GRect bounds, uint32_t seed, GColor color, int rise,
                     int phase);
uint32_t theme_seed(const char *text);

// Haptics: each kind of event has its own rhythm, so the wrist alone tells them apart.
void theme_vibe_text(void);     // dot: a text reply
void theme_vibe_voice(void);    // dot dot dash: a reply with audio
void theme_vibe_needs_you(void); // dash dash dash: failed, uncertain, read-only, no headphones
void theme_vibe_sent(void);     // double blip: a message left the watch
void theme_vibe_nudge(void);    // dot: that press did nothing
