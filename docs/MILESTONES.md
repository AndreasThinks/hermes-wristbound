# Milestones and acceptance

## Status (October 2026)

- **Milestone 1** is done from source: [HERMES_API.md](HERMES_API.md) records the routes, authentication and gaps, and [TESTING.md](TESTING.md) is the device test plan. The findings still need confirming against a running Hermes.
- **Milestone 2** client is built: watchapp and Android companion cover items 1 to 7 in code, with two known limits (Telegram-origin sessions are read-only through the REST API; playback uses the phone's voice, not Hermes audio). None of the acceptance steps has been run on real hardware yet.
- **Milestone 3** items partly in code: reconnect catch-up by polling, at-most-once submission with uncertain outcomes, notification deduplication, separate visibility and alert preferences, bounded cache and media-free storage. Untested on devices.

## 0. Documentation-only starting point

The repository began as the proposal only.

## 1. Capability discovery

Inspect Hermes's existing session, submission, event and audio APIs and the relevant desktop flows. Record verified endpoints and authentication without credentials. Verify Pebble/Android device compatibility. Identify missing capabilities and propose the narrowest integration boundary before implementation.

Exit: a concrete contract and device test plan, with unknowns labelled.

## 2. End-to-end vertical slice

Build the smallest client that can:

1. List real sessions and include/exclude Telegram-origin sessions.
2. Open an existing session and display real recent messages.
3. Submit one harmless message into that exact session, without forking context.
4. Receive its actual answer while the phone is locked and the watch initially shows its watchface.
5. Notify once and open the reply on the watch.
6. Play the selected reply's real audio on the phone/headphones from a watch action, without opening Telegram or unlocking the phone.
7. Pause playback from the watch and safely pause on headphone disconnect.

Use actual Hermes output, not fabricated replies, for acceptance. Test the existing Ringbearer ingress and confirm its reply appears in the expected session, without changing its route merely for the prototype.

## 3. Reliability and preferences

- Bluetooth loss/reconnection and offline catch-up.
- Tailscale loss/reconnection and expired application authentication.
- Android background restrictions, process death and Doze.
- Audio arriving later than text, unavailable audio and interrupted playback.
- Multiple sessions and overlapping requests with exact-message playback.
- Submission retries and uncertain outcomes without duplicate task execution.
- Notification deduplication and visibility-versus-alert preferences.
- Bounded cache, media expiry and redacted diagnostics.

## Deferred

Optional watch-speaker playback, opt-in autoplay, advanced session management, interactive approvals and direct Ringbearer API ingress. These require explicit decisions after the core slice works.
