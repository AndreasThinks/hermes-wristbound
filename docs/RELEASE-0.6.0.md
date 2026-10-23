# Wristbound v0.6.0

- Conversation-aware native Pebble notification content and explicit non-UI watch actions: Play audio, Play on phone speaker, Open in Wristbound.
- Silent phone defaults on a dedicated reply channel, preserving blocked legacy channels. Silent notification-shade and Android background-service records can remain; Pebble forwarding and user overrides still apply.
- Discover retained automatic Hermes voice replies, including answers to Ringbearer captures. Requires the matching durable automatic-reply-audio Hermes backend extension, not just upstream Hermes 0.22.2.
- Exact-reply ordered playback for split audio, late attachment detection, and independent Telegram text/audio eligibility. No autoplay or replacement speech synthesis.

## Update both components
Install the v0.6.0 debug Android APK from this release over your existing workstation-signed debug companion, and update Wristbound from the existing Pebble Store listing. Do not uninstall to update. CI-built debug APKs may have a different signing certificate. Preserve sign-in and enable Keep checking in the background for watchapp-closed monitoring.

## Verification and limits
Canonical Android unit tests and debug build pass; all seven watch targets clean-build with SDK 4.33.1. The protocol remains v5. Code review found no concrete Android blockers. Prior hardware checks applied to v0.5.0, not this feature set. Locked-phone watchface-visible native rendering, silent-phone forwarding, playback actions, split audio/headphone routing and real Ringbearer voice acceptance remain pending hardware testing. No guarantee of zero Android notification records or exact-once behavior after lost watch acknowledgements.
