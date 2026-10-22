# Proposed architecture

Everything here is a design proposal until verified against supported APIs and real devices.

## Component responsibilities

### Pebble

Session selection, compact message rendering, input actions, notification preferences and exact-message playback controls. Exchange small, acknowledged payloads with Android; chunk history and long messages to respect actual platform limits.

### Android

Hermes authentication, Tailscale reachability, event subscription/catch-up, a bounded local cache, notifications, Pebble communication and audio playback. Keep credentials on the phone, never on the watch. Android Media3 MediaSessionService is a candidate for background playback; PebbleKit Android 2 is a candidate communication library. Compatibility and background behaviour require verification.

### Hermes

Authoritative sessions, message submission, execution/queuing, final replies and media. Prefer its existing APIs. Do not write to Hermes's session database or launch a separate agent process for each watch message.

### Ringbearer

Existing Index ingress can remain. If it enters through Telegram, Wristbound must access and safely continue that same Hermes session. Direct Ringbearer-to-Hermes ingress is optional future work, not a prerequisite or an authorised routing change.

## Required capabilities to discover

- Authenticated session listing with origin metadata and pagination.
- Session history retrieval and stable identifiers.
- Supported message submission into an existing session, including queuing and reset semantics.
- Live or resumable reply/progress/tool events.
- Generated audio discovery and authenticated retrieval, including audio arriving after text.
- Clear terminal errors, approvals and cancellation states.

Inspect existing Hermes desktop/API implementation and documentation. Do not infer endpoint compatibility from the UI. If a capability is missing, document the smallest adapter/upstream boundary rather than inventing endpoints.

## Logical data contract

Not a wire protocol yet:

- Session: stable ID, title, origin, activity state and last-event cursor.
- Message: stable ID, session ID, role, content, completion state and media references.
- Media: stable ID, owning message/turn where available, MIME type and availability.
- Event: stable ID/cursor, session ID, kind and payload.
- Submission: client-generated idempotency key, session ID and user content.
- Playback command: exact message/media ID and requested action.

Do not pair text and audio by assuming adjacent arrival or latest timestamp. Preserve separate media items when Hermes exposes them that way. TTS failure must not hide a text answer.

## Background delivery

An open socket alone is not a reliable Android notification strategy. Investigate platform-supported lifecycle/wake options for both watch-app-open and watchface-active states. Account for Doze, phone process death, Bluetooth loss and Tailscale reconnection. A bound Pebble service while the watch app is open does not prove delivery while it is closed.

Persist event cursors and fetch missed items on reconnect. Deduplicate events and notifications. Bound the local cache and media lifetime. Provide clear offline and stale-data indicators.

Retrying delivery or fetching media must never re-execute a user task. If submission outcome is uncertain and the server cannot deduplicate it, report uncertainty rather than blindly resending.

## Security and privacy

Tailscale provides private connectivity, not application authentication. Use supported Hermes authentication and transport security. Limit token scope where possible, store it using Android secure storage, and support revocation. Never bundle credentials, personal host addresses, recordings, session exports or chat contents in the repository.

Protect cached conversations and media, redact diagnostics, and make notification-preview privacy configurable. Audio URLs must not become unauthenticated public links. Do not expose additional public listeners or change firewall rules as part of a client prototype.

## Open questions

- Target watch model, firmware, Android release and Pebble companion release.
- Actual Hermes session/event/media API coverage and supported authentication.
- Safe cross-client continuation of Telegram-origin sessions.
- Background reception and media-service startup while the phone is locked.
- Dictation and optional on-watch playback support.
- Exact relationship between filters, subscriptions and unread state.
- Whether incoming audio already has usable text or needs a separate display label.
- License choice, SDK versions and packaging/distribution.

## References

- Hermes docs: https://hermes-agent.nousresearch.com/docs/
- PebbleKit Android 2: https://github.com/pebble-dev/PebbleKitAndroid2
- Pebble communication: https://developer.repebble.com/guides/communication/
- Android background playback: https://developer.android.com/media/media3/session/background-playback
- Android service restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
