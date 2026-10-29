#pragma once

#include <pebble.h>

// A new reply arriving while the watchapp is open: a card over the lower part of the screen
// with the start of the message, like a phone's message notification. Whatever was showing
// stays visible above it. Select opens it, Back (or Up/Down) puts it away, and it fades out on
// its own after a while.
// Aplite has no room for it: a reply there opens as a full card, as before.
#if !defined(PBL_PLATFORM_APLITE)
#define BANNER_ENABLED 1
#endif

typedef void (*BannerHandler)(void);

// on_open runs on Select; on_expire (optional) when the card fades out untouched.
void banner_show(const char *title, const char *text, bool audio, BannerHandler on_open, BannerHandler on_expire);
void banner_hide(void);
bool banner_showing(void);
void banner_deinit(void);
