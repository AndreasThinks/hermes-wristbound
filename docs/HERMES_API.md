# Hermes API findings

Milestone 1 asked for the Hermes capabilities Wristbound can use, verified rather than inferred from the desktop UI. These notes come from reading the Hermes Agent source and docs (`gateway/platforms/api_server.py` and `website/docs/user-guide/features/api-server.md`, upstream `main` as of October 2026). They have **not** yet been checked against a running Hermes; the device test plan in [TESTING.md](TESTING.md) covers that.

## Surface used

Wristbound talks only to the Hermes **API server** (`hermes gateway` with `API_SERVER_ENABLED=true`, default `127.0.0.1:8642`). Every route needs `Authorization: Bearer $API_SERVER_KEY`. Hermes refuses to start the API server with a missing or weak key (under 16 characters).

| Need | Route | Status |
|------|-------|--------|
| List sessions with origin | `GET /api/sessions?limit=&offset=` | Verified in source. Rows carry `id`, `source` (e.g. `telegram`, `api_server`, `cli`), `title`, `preview`, `last_active`, `archived`, `hidden`, `is_internal_child`. Ordered by last activity. |
| Read recent history | `GET /api/sessions/{id}/messages?order=latest&limit=N&inline_images=false` | Verified. Messages carry integer `id`, `role`, `content` (string or multimodal parts), `tool_calls`, `tool_name`, `timestamp`. |
| Create a session | `POST /api/sessions` `{"source":"api_server","title":...}` | Verified. |
| Send a message and stream the turn | `POST /api/sessions/{id}/chat/stream` `{"message": "..."}` | Verified. SSE events: `run.started`, `assistant.delta`, `assistant.commentary`, `tool.started/completed/failed`, `assistant.completed`, then exactly one of `run.completed`, `run.failed`, `run.cancelled`, then `done`. Every payload has `run_id` and `session_id`. `: keepalive` comments every 10 s. |
| Recover a turn after the stream drops | `GET /v1/runs/{run_id}` | Verified. Session-stream runs record their status and `output`; terminal statuses are kept about an hour. A status of `unknown` means Hermes restarted mid-turn. |
| Health | `GET /health` (no auth) | Verified. |

## Gaps and how Wristbound handles them

**Continuing Telegram-origin (and other foreign) sessions.** The API docs say API routes "cannot bind a session that another surface owns — a CLI, TUI, Desktop, ACP or messaging conversation" and answer `409 permission_denied`. The documented route for that is the gateway WebSocket (`prompt.submit` on a shared session), which is a different protocol from the REST surface. Wristbound attempts the turn once; on `409 permission_denied` it marks the session read-only on the watch and tells the user to reply from the session's own app. Nothing is resent. Reading these sessions works.
*Next step:* decide whether to add a WebSocket attach client to the companion, or ask Hermes upstream for a REST submit route that queues into the owning surface.

**Generated audio.** The API server replaces `MEDIA:` tags with inline data only for images. Audio files (TTS output in `~/.hermes/audio_cache/`) stay server paths, and there is no authenticated route that serves them to a remote client. The dashboard's `/api/audio/speak` uses dashboard auth, not the API key. Wristbound therefore speaks the exact selected reply with the phone's TextToSpeech engine on the current audio route, and flags replies that had Hermes audio attached.
*Next step:* a small authenticated media route (or an upstream change) would let the phone fetch the real audio for a given message id.

**Push delivery.** There is no session-activity event stream on the API server, so replies that arrive through Telegram, Ringbearer or the desktop are found by polling `GET /api/sessions` and reading new messages for sessions whose `last_active` advanced. Polling runs inside an optional foreground service, at a user-chosen interval (30 s to 5 min). Replies to turns sent from the watch arrive over the live stream and need no polling.

**Idempotent submission.** `Idempotency-Key` is documented for `/v1/chat/completions`, `/v1/responses` and `/v1/runs`, not for the session chat route. Wristbound sends a fresh key anyway (harmless), but its safety rests on never resubmitting: after a broken stream it only observes the run by id, and reports *uncertain* when it has no run id.

**Unread state.** Sessions have a `PATCH ... {"unread": bool}` flag, but list responses do not return it. Wristbound keeps its own per-session "seen up to" timestamp on the phone instead.

## Security notes

- Hermes binds to loopback by default; `API_SERVER_HOST=0.0.0.0` exposes terminal-capable agent access to the network. Prefer `tailscale serve --https=443 http://127.0.0.1:8642` so the server stays on loopback and the phone reaches it over the tailnet with a certificate.
- The companion refuses cleartext HTTP except to `*.ts.net` MagicDNS names, where WireGuard already encrypts the link.
- The API key is stored encrypted with an Android Keystore key, never sent to the watch, and app data is excluded from backups.
