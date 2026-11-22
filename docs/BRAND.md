# Hermes Wristbound visual guide

One look for every part of Hermes Wristbound: the watch app, the Android companion, the Index plugin, the voice plugin, the docs and the website. The watch app is the reference; everything else borrows from it. The look is called **Phosphor**.

## Name

- **Hermes Wristbound** is the product: everything in this repository, together.
- **Wristbound** on its own is fine where space is short (the watch launcher, the Android launcher, the splash), and always means the same product.
- Name the parts by what they are, after the product name, not as separate brands:

| Part | Say | Shipped as |
|------|-----|------------|
| Watch app | the Wristbound watch app | Pebble Store: *Wristbound* (`watch/`) |
| Phone companion | the Wristbound companion | Android APK: *Wristbound* (`android/`) |
| Index plugin | the Hermes Wristbound Index plugin | Pebble Store: *Hermes for Index*; appears as *Hermes* in Index (`pebble-plugin/`) |
| Voice plugin | the Hermes Wristbound voice plugin | Hermes plugin `wristbound-voice` (`hermes-plugin/`) |

*Hermes* alone means Hermes Agent, the assistant this connects to. Don't call any part of Wristbound "Hermes".

## Colour

Black glass, one colour for what you can do, one colour for when Hermes speaks.

| Token | Hex | Use |
|-------|-----|-----|
| Glass | `#000000` | Every background. |
| Signal | `#00FFAA` | Anything you can act on: the focused row, buttons, links, the reply banner. Pebble's *Medium Spring Green*. |
| Voice | `#AA55FF` | Whenever Hermes is speaking: audio replies, the waveform, the AUDIO tag. Pebble's *Lavender Indigo*. |
| Text | `#EDEDF2` | Body text on glass. |
| Note | `#C4C4CC` | Secondary text. |
| Meta | `#9A9AA6` | Labels, timestamps, captions. Pebble's *Light Gray*. |
| Rule | `#2E2E36` | Hairlines and borders. |
| Panel | `#0B0B10` | Raised surfaces on the phone and web. |
| Warn | `#FFB547` | A broken link that needs you (phone and web only). |

Green and violet never mean the same thing. Never use green for Hermes' voice or violet for a control. Black-and-white watches draw both accents in white.

The source of truth is `watch/src/c/theme.h` for the watch and `android/app/src/main/res/values/colors.xml` for the phone; the website uses the same names in `site/style.css`.

## Type

- **Silkscreen** (`watch/resources/fonts/Silkscreen-Regular.ttf`, SIL OFL) for fixed labels only: section headers, status, tags, the wordmark. Always upper case, small, a little letter-spaced. ASCII only.
- **Hermes' own words stay in a plain readable face**: Pebble Gothic on the watch, the system sans on the phone and web. Hermes can send any character, and a pixel font can't show them all.
- Code, routes and settings names in a monospace face.

## Marks and iconography

| Mark | Meaning |
|------|---------|
| The **waveform**: pixel bars rising green, then violet | Wristbound itself (watch menu icon, Android launcher), and on a row, a reply that carries audio. The same reply always draws the same wave. |
| A **block** before a line | New, not yet opened. |
| A **block cursor** after a line | Hermes is working. |
| The **AUDIO** tag, violet | A reply you can play. |
| A **speech bubble** with three dots | The Index plugin (sending to Hermes). |
| The **Hermes girl** pixel portrait, green and violet on black | The launch screen and hero image. Use it whole, on black, never recoloured. |

Icons are drawn on a pixel grid with square ends, no gradients and no shadows.

## Motion and feel

A reply that arrives comes in like a radio tuning in: the waveform rises, then the text types itself out in under half a second, and any button skips to the end. On the web, keep motion to that one idea and honour reduced-motion settings.

Each kind of event has its own vibration, so the wrist alone tells them apart:

| Event | Pattern | ms on / off |
|-------|---------|-------------|
| Text reply | dot | 50 |
| Reply with audio | dot dot dash | 50 · 100 · 50 · 100 · 250 |
| Needs you | dash dash dash | 250 · 150 · 250 · 150 · 250 |
| Sent | double blip | 30 · 60 · 30 |
| A press that does nothing | dot | 50 |

## Voice

Plain, short and honest. Say what something does and what it doesn't. Mark untested paths as untested. No hype words, no emoji.
