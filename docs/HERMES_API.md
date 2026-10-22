# Hermes API findings

These notes come from reading the Hermes Agent source (upstream `main`, October 2026) and the Hermes-Relay Android client, which talks to Hermes the way the desktop app does. They have **not** yet been checked against a running Hermes; the device test plan in [TESTING.md](TESTING.md) covers that.

## Which Hermes surface, and why

Hermes has two network surfaces:

- The **API server** (`hermes gateway` with `API_SERVER_ENABLED=true`, port 8642, `Authorization: Bearer $API_SERVER_KEY`). It is OpenAI-compatible and has REST session routes, but it refuses turns in sessions another surface owns (`409 permission_denied` for Telegram, CLI, desktop and other sessions), and it does not serve audio files.
- The **dashboard** (`hermes dashboard`, port 9119). This is what the desktop app and Hermes-Relay use. Its gateway WebSocket (`/api/ws`) drives the same `tui_gateway` JSON-RPC the terminal UI uses, which can resume and continue any stored session, and it serves Hermes' audio. Hermes' own API docs point here: "to work on a chat from another surface, attach through the gateway WebSocket (a `prompt.submit` on the shared session)".

The first Wristbound slice used the API server, which is why Telegram sessions were read-only and Hermes audio was out of reach. The companion now uses the dashboard only, so both work. The API server is no longer needed.

## Authentication

`GET /api/status` (public) reports `auth_required` and `auth_flows`. The dashboard is in one of two modes:

- **Gated** (any non-loopback bind, including a tailnet `dashboard.public_url`). Sign-in goes through providers listed at `GET /api/auth/providers`. Native apps use the RFC 8252 flow, which Wristbound implements as the desktop and Hermes-Relay do:
  1. `GET /auth/native/authorize?code_challenge=…&code_challenge_method=S256&redirect_uri=http://127.0.0.1:<port>/callback&state=…&provider=…`
  2. The user signs in. For password providers (`dashboard.basic_auth`) the companion posts `POST /auth/password-login` itself, carrying the cookie step 1 set, and reads the code out of the returned `next` URL; no browser is involved. For OAuth providers the system browser runs the sign-in and redirects to a one-shot listener on the phone's loopback interface.
  3. `POST /auth/native/token {code, code_verifier}` returns `access_token`, `refresh_token`, `expires_at`.
  4. REST calls send `Authorization: Bearer <access_token>`. Before expiry, or after a 401, the companion rotates with `POST /auth/native/refresh`. Refreshes are serialised, because Hermes revokes a session whose refresh token is replayed. A 401 from refresh means sign-in is needed again.
- **Loopback** (no sign-in page). Every `/api/*` route takes the dashboard session token in `X-Hermes-Session-Token`. The desktop app fixes it with `HERMES_DASHBOARD_SESSION_TOKEN`; otherwise it changes on every restart.

The gateway socket authenticates on the upgrade: in gated mode with a single-use ticket from `POST /api/auth/ws-ticket` (30 s TTL, `?ticket=`), in loopback mode with `?token=<session token>`.

Credentials are stored encrypted with an Android Keystore key and never sent to the watch.

## Surface used

| Need | Route | Notes |
|------|-------|-------|
| List sessions with origin | `GET /api/sessions?order=recent&limit=N` | `{"sessions":[...]}`. Rows carry `id`, `source` (`telegram`, `cli`, `webui`, ...), `title`, `preview`, `last_active`, `archived`, `is_active`. Telegram sessions are included. |
| Read recent history | `GET /api/sessions/{id}/messages?order=latest&limit=N&inline_images=false` | `{"messages":[...]}`; rows carry integer `id`, `role`, `content`, `tool_calls`, `tool_name`, `timestamp`. Includes compression ancestors, matching what the desktop shows. |
| Open the gateway | `GET /api/ws` (WebSocket) | Newline-delimited JSON-RPC 2.0. The server sends `gateway.ready` first; clients send `gateway.ping` every 15 s. Events arrive as `{"method":"event","params":{"type","session_id","payload"}}`. |
| Attach to a stored session | `session.resume {session_id, omit_messages: true}` | Returns a runtime `session_id` for this socket, and `running` if a turn is live. Works for Telegram-origin sessions. Joins an already-live session rather than forking it. Refusals: `4007` not found, `4130` too long, `4132` Kanban worker transcript. |
| Start a session | `session.create {source: "webui"}` | Returns the runtime `session_id` and the `stored_session_id`. The stored row is written on the first prompt. |
| Send a turn | `prompt.submit {session_id, text, queued: true}` | Acks `{"status":"streaming"}`, or `"queued"` if a turn is already running there. `queued: true` waits behind that turn; without it Hermes' default is to redirect the running turn. |
| Turn events | `message.start`, `message.delta`, `tool.start`, `tool.complete`, `message.complete` | `message.complete` carries `text` and `status` (`complete`, `error`, `interrupted`). Approval, clarification and secret prompts arrive as server requests (`approval.request`, ...). The watch says "Needs an answer on desktop"; it cannot answer them. |
| Hermes-generated audio | `GET /api/files/stream?path=<server path>` | Streams the file a `MEDIA:<path>` tag names (the text-to-speech tool writes them under `~/.hermes/cache/audio/`). Audio extensions only. The desktop and Hermes-Relay use the same managed-files routes. See the audio contract below. |
| Read a reply aloud in Hermes' voice | `POST /api/audio/speak {text}` | Runs the `tts:` provider chain from Hermes' config on the text and returns a `data:` URL. This is **new** speech, not any audio Hermes sent earlier; the desktop's read-aloud uses it the same way. |

## Audio contract

What a client can and cannot get, from the Hermes source:

- **Text-to-speech tool output** (the agent calls `text_to_speech`). The tool writes the file under `~/.hermes/cache/audio/` and returns `MEDIA:<path>` in its tool result, which is stored in the transcript; the agent usually repeats the tag in its reply. This is the original audio, and the companion fetches it with `/api/files/stream`.
- **Automatic voice replies** (Telegram voice mode, or auto-TTS after a voice note). `gateway/run_voice.py` `_send_voice_reply` synthesises to a temp file, hands it to the platform adapter, then deletes it in a `finally` block. No transcript row or `MEDIA:` tag records it. **No client can retrieve this audio**, the desktop included; it exists only in the Telegram chat. Getting it would need a Hermes change that keeps the file and records a `MEDIA:` reference for it.
- **`/api/audio/speak`** synthesises the reply text again. It is labelled on the watch as "Hermes voice, read again", and never counts as a reply "having audio" for alerts.

How the companion decides which audio belongs to which reply (`SessionMapper.replies`):

- A turn runs from a user row to the next. Audio counts only from assistant rows and `text_to_speech` results in that turn, not from user rows or other tools.
- Incoming user recordings (a Telegram voice note, a Ringbearer capture) are cached in the same `cache/audio` folder as generated speech, and a user row names them as `[The user sent a voice message: <path>]` or a transcription note. Any file a user row names is excluded, even if the agent later refers to it, and so is any file named as Hermes names inbound media (`audio_<12 hex>`).
- A media-only assistant row joins the answer before it in the same turn, which is how audio that arrives after the text is matched.

## Alerts

One policy decides every reply alert, whether the reply came from a turn sent on the watch, from background polling, or from recovery after a dropped socket. Settings: replies on or off, scope (all eligible sessions or only the open one), Telegram text alerts (off by default), and Telegram audio alerts (on by default: a Telegram reply that carries Hermes audio alerts even with Telegram text alerts off). Each reply alerts at most once. Its text and its audio are recorded separately, so audio that turns up later either alerts once (when the text did not alert) or silently refreshes the alert already shown. Nothing plays until Play is pressed.

Deciding and delivering are separate. An alert counts as delivered when the open watchapp acknowledges the reply card, or a phone notification is posted. A watch that does not acknowledge is treated as closed and the phone notification is used instead; if neither works (notifications blocked), the decision is rolled back and retried on the next poll, up to five times.

Polling keeps a checkpoint per session, persisted on the phone. The first poll ever only records a baseline, so history from before sign-in never alerts. A session's checkpoint moves only after its history was fetched and processed; a failed fetch, or a turn still running from the watch, leaves it for the next poll. After a restart, polling picks up from the saved checkpoints.

## Telegram replies

"Allow replies to Telegram sessions" (on by default) can be turned off to keep Telegram sessions read-only from the watch. They stay listed, readable and playable. The phone refuses a send into a Telegram session (or into a session it cannot identify) with "Reply in Telegram", and the watch does not open the reply screen there. Nothing changes on the Hermes side.

## Turn safety

A turn is submitted at most once. The companion marks the prompt as sent as soon as `prompt.submit` is handed to the socket. If the socket fails after that, it never resends; it recovers by looking:

1. The transcript, bounded to this prompt. Before submitting, the companion records the session's newest row id. Only a user row written after that boundary counts (the row id Hermes acks as `user_row_id`, when the ack arrived, or else the first later user row with the same text), and the answer must sit between that row and the next user row. An older identical prompt, or a later turn's answer, never counts. If the boundary could not be read, the transcript is not used as evidence.
2. Re-attaching: a new socket and `session.resume` join the live session if the turn is still running (Hermes keeps a detached turn running while it is producing output), and the rest of the turn streams in.
3. Otherwise the watch shows *uncertain* with a short reason.

A drop before the prompt left the phone is reported as a plain failure, since nothing was sent.

When a turn in a Telegram session finishes, the reply is stored in that session like any other turn. It is not posted back into the Telegram chat; the desktop app behaves the same way.

## Remaining limits

**Push delivery.** The gateway socket broadcasts `sessions.changed`, but holding a socket open on a locked phone is fragile, so replies that arrive through Telegram, Ringbearer or the desktop are still found by polling `GET /api/sessions`: every 30 s while the watchapp is open, and from the optional foreground service (30 s to 5 min) while it is closed. Replies to turns sent from the watch arrive live over the socket.

**Server prompts.** Tool approvals, clarifications and sudo or secret prompts need another client (desktop, terminal UI or Telegram) to answer. The turn waits; the watch shows that it is waiting.

**Unread state.** List rows do not carry a per-client unread flag, so the phone keeps its own "seen up to" timestamp per session.

## Security notes

- Keep the dashboard on loopback and publish it to the tailnet with Tailscale Serve, as Hermes-Relay recommends: `tailscale serve --bg --https=10443 http://127.0.0.1:9119`. Setting `dashboard.public_url` to that address turns on the sign-in gate.
- The companion refuses cleartext HTTP except to `*.ts.net` MagicDNS names, where WireGuard already encrypts the link.
- The browser sign-in listener binds only `127.0.0.1`, accepts one redirect, and checks the `state` it sent. The PKCE verifier never leaves the phone.
