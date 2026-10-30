# Architecture

The first vertical slice is built (see [MILESTONES.md](MILESTONES.md)). The section below describes what the code does today; the rest of this document is the design it follows. None of it has been verified on real devices yet.

## As built

```text
Pebble watchapp (watch/src/c)
  ui.c       session list, session view, reply view, compose menu, action menu, alert
  comm.c     AppMessage send/receive, outbox queue
  model.c    on-screen state only: sessions, messages, submit and playback status
       |
       |  AppMessage, protocol v1 (PROTOCOL.md)
       v
Android companion (android/app/src/main/java/dev/wristbound/companion)
  pebble/    WatchLink + WristboundListenerService (PebbleKit Android 2), WatchCodec, text fitting
  core/      TurnRunner (submit, recover, at-most-once), SessionMapper, Alerts (one policy),
             Settings, LocalState (bounded cache and polling checkpoints)
  hermes/    DashboardAuth (PKCE, password, browser, session token), DashboardClient, Gateway (WebSocket JSON-RPC)
  playback/  PlaybackController (Hermes audio only; pause on disconnect)
  sync/      SyncService (the one foreground service), CheckAlarm (background checks), BootReceiver
  notify/    Notifier and ActionReceiver (phone notification mirrored to the Pebble)
  ui/        MainActivity (connection and settings)
       |
       |  HTTPS + WebSocket over Tailscale (HERMES_API.md)
       v
Hermes dashboard (port 9119, published with tailscale serve)
```

A watch reply runs like this: the watch sends a submit command with the session id and text; the companion resumes that session on the gateway socket, submits the prompt queued behind any running turn, and waits for the completion event; status updates (queued, working, completed, failed, uncertain) go back to the watch as they happen, and the answer arrives as a new message. If the socket drops after the prompt was sent, the companion never resends: it looks for the answer in the transcript, re-attaches to the turn, or reports *uncertain*.

Replies that start elsewhere (Telegram, Ringbearer, the desktop) are found by polling the session list. The controller owns the schedule: every check is an exact alarm under a short wakelock, every 30 s while the watchapp is open and at the chosen interval while it is closed with background checking on (the default). A coroutine timer stops while the CPU sleeps, and the Pebble app's binding keeps the process alive but not the CPU awake; a check that fails still schedules the next one. One foreground service keeps the process running whenever there is a reason (`KeepAlive`): background checking, a turn sent from the watch that has not finished, or a reply playing on the phone; it also holds the CPU while a turn streams and checks again when the network returns. Every delivery path goes through the same alert policy, which decides whether a reply alerts, alerts as audio, or updates silently, and records a checkpoint so it alerts once.

The decisions behind these choices, and why the Hermes API server is not used, are in [HERMES_API.md](HERMES_API.md).

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

Answered in code, still to confirm on devices:

- Hermes API coverage and authentication: the dashboard and gateway socket cover sessions, history, submission, events and audio ([HERMES_API.md](HERMES_API.md)).
- Continuing Telegram-origin sessions: `session.resume` on the gateway, as the desktop app does; replies can be turned off per user setting.
- Filters versus alerts: the session filters (messaging chats, scheduled and automation sessions) change what the watch lists only. Alerts are decided per reply by `AlertPolicy` from its kind: audio (its own switch, any session), a watch turn, a local chat, messaging text, or background (an automation source, or a turn opened by a user row Hermes wrote itself, `display_kind` `internal_notification`, `process_complete` or `async_delegation_complete`, that reports no finished work, such as a heartbeat or gateway check-in).
- Finished work reporting back in a chat is not background, as in the Hermes desktop app, whose "Response ready" fires for any turn that ends in the open chat, including ones Hermes opened. A completion row names its work (`display_metadata.delegation_id`, or the notice header: `[ASYNC DELEGATION COMPLETE — deleg_…]`, `[IMPORTANT: Background process proc_… …`, `Started by subagent … of delegation deleg_…`), and its reply alerts as that chat's reply (chat or messaging by the session). Where the tool result that started the work (`delegate_task`'s `delegation_id`, a background `terminal` call's `session_id`) is in the fetched rows, the companion follows it back to the person's message; if the watch sent that message (the companion remembers the user rows it sent), the reply alerts as a watch reply. Tracing only ever upgrades: a completion it cannot trace still alerts as a chat reply. Hermes does not record the originating request on the completion row; a `requested_by` row id there would make the watch attribution exact.
- Opening the watchapp, the Sessions list or a conversation shows the phone's saved copy at once, then asks Hermes and resends only what changed. The watch itself keeps nothing between launches (Pebble storage is a few KB), so the phone's cache is what makes reopening fast.
- Audio labelling: replies with Hermes audio are labelled as audio in the alert and are the only ones that offer Play; text replies are never read aloud.

Still open:

- Target watch model, firmware, Android release and Pebble companion release.
- Background reception and media-service startup while the phone is locked, under Doze.
- Push delivery for replies from other surfaces instead of polling.
- Optional on-watch playback.
- License choice and packaging/distribution.

## References

- Hermes docs: https://hermes-agent.nousresearch.com/docs/
- PebbleKit Android 2: https://github.com/pebble-dev/PebbleKitAndroid2
- Pebble communication: https://developer.repebble.com/guides/communication/
- Android background playback: https://developer.android.com/media/media3/session/background-playback
- Android service restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
