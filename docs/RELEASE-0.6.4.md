# Wristbound v0.6.4: background reliability

Android companion and watchapp now both carry version 0.6.4; protocol v5 is unchanged.

## Changes
- PR #24: background checks use wakeup alarms rather than a coroutine timer while the watchapp is closed.
- One foreground service covers background checking, active watch-submitted turns, and playing or paused phone audio.
- Bounded wake locks protect active turns and background checks; MediaPlayer keeps the CPU awake during audio.
- Monitoring restores after boot/app replacement, and checks again when the default network returns.
- New or unset background-checking preferences default on. Existing saved choices are preserved.
- Phone setup adds Allow background use and shows when Hermes was last reached.
- Watch source/UI/protocol are unchanged from watch v0.6.1; this rebuild aligns the coordinated release versions.

## Install
Install `wristbound-companion-v0.6.4-debug.apk` over the existing companion, without uninstalling. The APK remains a sideloaded debug build, not Google Play; its signing certificate matches the previously published companion. Update the watchapp through the existing Pebble Store listing, or install the included PBW.

On the phone, check Keep checking in the background and press Allow background use. Notification permission and Pebble forwarding must remain enabled. Doze can still delay alarms; exact timing is not guaranteed. No changes to your self-hosted Hermes server are deployed by this release.

## Verification and limits
135 JUnit tests pass, zero failures/errors/skips; canonical testDebugUnitTest assembleDebug succeeds. Clean Pebble SDK 4.33.1 build succeeds for all seven existing platforms, preserving the UUID. Source review found no release blocker. No physical phone/watch is connected: forced Doze, reboot, turns completing after watchapp closure, and long screen-off playback remain hardware acceptance items. A watch metadata rebuild is not new visual or hardware proof.
