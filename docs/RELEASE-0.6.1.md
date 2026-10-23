# Wristbound Android v0.6.1: clearer native notifications

Android-only update. Continue using the v0.6.0 watchapp from the [Pebble Store](https://apps.repebble.com/22984baa50dd4b68ba9a0945); protocol v5 is unchanged. There is no v0.6.1 watch PBW because watch source and protocol did not change.

## Changes
- Native notifications identify Wristbound, with conversation context in the heading.
- Audio previews begin with **Audio reply available**, before reply text can be truncated.
- **Play audio** remains first, with phone speaker an explicit separate action.
- Private previews hide conversation titles and reply text while retaining audio availability.
- Text-only notifications do not advertise audio or offer playback.
- Existing violet/green colors, silent-phone defaults and delivery/deduplication identities are preserved.

Custom watch notification icons and folded/half-screen presentation are not included. The Pebble companion's built-in per-app icon selector remains available; the default forwarded icon can remain a bell.

## Install
Install `wristbound-companion-v0.6.1-debug.apk` over the existing workstation-signed companion. Do not uninstall. This is a sideloaded debug APK, not a Play Store release; CI-produced debug APK certificates can differ. The published APK is byte-identical to the candidate sent for testing and retains the v0.6.0 signing certificate. Keep background checking and notification forwarding enabled for alerts while the watchapp is closed.

## Verification
Canonical `testDebugUnitTest assembleDebug` succeeds: 126 tests, zero failures/errors/skips. Regression tests cover branded ordinary/private previews, explicit audio availability, empty/media-only audio, text-only behavior, stable late-audio identity and action ordering. Watch rendering and real-device audio routing remain hardware acceptance items; host tests do not establish them.
