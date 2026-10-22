# Project brief

## Goal

Make Hermes usable from a Pebble watch while the phone stays locked and in a pocket: see sessions, send and receive messages, and start audio playback without opening Telegram.

## Chosen direction

- Product name: **Hermes Wristbound**.
- A Pebble app paired with an Android companion.
- Android reaches Hermes privately over Tailscale.
- Hermes remains authoritative for conversation history and execution.
- Telegram-origin sessions are selectable through an include/exclude filter, not a separate Telegram client.
- Ringbearer remains an additional input path for Index recordings.
- Audio delivery is not dependent on the assistant remembering a bespoke wearable tool call.

The user reports that the Hermes app already displays Telegram sessions. Reuse the corresponding supported session capabilities where possible; desktop visibility alone does not establish which APIs support reading, sending, events or audio retrieval.

## Watch experience

### Sessions

Show short titles and unread state. Open a session to read recent history and continue it. Make creating a new session an explicit action. Preserve the selected session across reconnects.

The Telegram-origin filter controls session visibility. Its relationship to notification subscriptions must be explicit, so hiding a session does not unexpectedly change alerts or leak hidden content.

### Messages

Use scrollable text with readable typography and restrained status indicators. Support sending through dictation where the watch and phone support it. Show queued, submitted, failed and uncertain states accurately. A transport acknowledgement is not proof that Hermes completed the task.

### Audio

An incoming audio-capable reply offers Play, Read and Dismiss. The action targets that exact reply, not whichever media app was most recently active. Provide pause/resume and replay. Keep text usable while audio is pending or unavailable.

Default playback is user-initiated through the phone's active headphones/audio route. Phone-speaker playback is explicit. Pause on headphone disconnect rather than silently switching to speaker. Any headphone-only autoplay is a later opt-in preference.

Play on watch is conditional on verified speaker hardware, codecs and API support. A watch microphone does not establish playback capability. The initial milestone must not depend on watch audio output.

## Notification preferences

Treat visibility and interruption as separate settings:

- Final replies: show and vibrate by default.
- Tool activity: hidden by default; alternatively show silently or notify.
- Progress: update one status indicator silently by default.
- Errors and approval requests: distinct states and alert policies.
- Session scope: active session, chosen sessions or all eligible sessions.
- Audio: manual playback by default.

Avoid turning every tool event into an inbox item. Avoid duplicate alerts when both the companion and Telegram are enabled. Telegram muting is an optional user setting, not an automatic deployment change.

## Scope limits

The initial version is a thin Hermes client, not an autonomous assistant or Telegram replacement. Do not execute approvals automatically. Approval controls, if added, require a separate safe interaction design. Do not retain original microphone recordings merely to support reply playback.
