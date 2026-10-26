# Wristbound v0.6.3: complete formatting update

This release completes the update with Android companion v0.6.2 (versionCode 9). The watchapp remains v0.6.1 on the Pebble Store. Both use protocol v5.

## Install

- Update the watchapp to v0.6.1 through the existing [Pebble Store listing](https://apps.repebble.com/22984baa50dd4b68ba9a0945).
- Install `wristbound-companion-v0.6.2-debug.apk` over the existing companion. Do not uninstall. This is a sideloaded debug APK, not a Google Play release. Its signing certificate matches the published v0.6.1 APK.

The old companion remains protocol-compatible, but updating only the watch misses the new markdown formatter. Earlier watch-only advice and the original v0.6.2 release description incorrectly said Android source was unchanged.

## Changes

The companion preserves lists, task items, headings and table rows in watch-readable plain text; removes unsupported markup; retains code-block lines; and shortens bare URLs. The watchapp supplies bold banner headlines, an AUDIO tag and a Select hint.

## Verification and limits

- Canonical `testDebugUnitTest assembleDebug` succeeds; 132 JUnit tests, zero failures, errors or skips.
- Candidate APK signature verified and certificate SHA-256 matched against the downloaded v0.6.1 release APK.
- Watch v0.6.1 clean-builds for all seven supported platforms. Its Store and GitHub downloads were verified byte-identical.
- No phone was connected over ADB during this correction. Installed versions, upgrade behavior and real-watch interaction are not verified. The final watch emulator screenshot attempt timed out; no fresh visual acceptance is claimed.
