# Wristbound v0.6.6: opt-in watchapp alert banners

Paired Android companion and watchapp release, both v0.6.6. Android versionCode is 12; protocol v5 and the existing watch UUID are unchanged.

## Changes

- Adds **Open Wristbound for alerts** under Notifications, off by default. When enabled, incoming reply alerts can open Wristbound over the watchface or another watchapp, skip its splash and display its own banner.
- Waits up to 10 seconds for the watchapp's HELLO. Failed startup or missing delivery acknowledgement falls back to the existing native notification path.
- An untouched banner expires after 15 seconds and returns to the watchface when Home is underneath. Select opens the reply and keeps Wristbound open.
- Silent late-audio updates do not launch the watchapp. Existing alert policies, background checking, silent-phone notification defaults and playback routes are preserved.
- Integrates the reply-check fixes from Android v0.6.5: wakeup checks while the watchapp is open, rescheduling after errors, and the delayed native test-alert button.

This is an opt-in foreground watchapp launch, not a native PebbleOS overlay. Aplite retains its existing direct-reply UI without the banner.

## Install

Install `wristbound-companion-v0.6.6-debug.apk` over the existing companion without uninstalling, and update the watchapp through the existing Pebble Store listing or `wristbound-v0.6.6.pbw`. The APK is a sideloaded debug build, not a Play Store release; CI artifacts may have different certificates. Keep existing settings and sign-in with an in-place upgrade.

## Verification and limits

- 140 Android JVM tests passed, including launch-before-send, startup fallback, failed-ACK fallback, already-open delivery and silent-update behavior.
- Android debug APK built; clean watch build succeeded for all seven targets with SDK 4.33.1.
- Final PBW on an isolated Emery emulator, using canned fake-phone messages: the audio banner rendered, then an untouched phone-launched alert returned to the system watchface. These fixtures do not establish a Hermes backend round trip.
- No Android phone was attached over ADB for this release. Real PebbleKit startup/HELLO timing, locked-phone behavior, Doze/reboot, headphone/phone/watch-speaker output and Bluetooth throughput remain hardware acceptance checks. Neither a unit test nor an emulator screenshot establishes audible output.

Nothing plays automatically when an incoming alert opens the watchapp. Enable the launch setting only if you want Wristbound to interrupt the current watch display.
