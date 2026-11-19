# Hermes plugin for Pebble (prototype)

A [Pebble plugin](https://developer.repebble.com/guides/plugins/preview/) that makes Hermes an
**agent destination**. Index and any plugin-aware watchface or app can then send to Hermes without the
Wristbound watchapp, the companion APK or Telegram. It sits beside Wristbound; it does not replace it.

It runs as JavaScript inside the Pebble phone app (Android and iOS) and talks to the same Hermes
dashboard as the companion ([docs/HERMES_API.md](../docs/HERMES_API.md)).

## What it offers

| Kind | Name | What it does |
|------|------|--------------|
| Action | `send_to_agent({ message, chat_id? })` | Sends a prompt to Hermes and returns a receipt once Hermes accepts it. `chat_id` is a session id from `agent/chats`, a title, a unique part of a title, or `new`. Without it the message goes to a new session or to the default chat chosen in settings. This is the action Index calls for "tell Hermes…". |
| Action | `read_latest_reply({ chat_id? })` | Returns Hermes' latest reply in a chat as text, with MEDIA tags removed. |
| Source | `agent/chats` | Recent sessions: name, title, preview, active in the last five minutes, last activity. |
| Source | `agent/configured` | Whether the plugin is signed in; Index shows a Connect button while it is false. |
| Source | `hermes/status` | A complication reading: how many chats were active in the last five minutes, and the latest chat. |

A send opens one gateway socket and either resumes the session (`session.resume`) or creates one
(`session.create`). It then calls `prompt.submit` with `queued: true`, waits for the acknowledgement
and closes the socket. Hermes keeps a detached turn running while it is producing output
(`tui_gateway/session_lifecycle.py`), so the turn finishes on the server. Wristbound's companion, the
desktop app or Telegram show the answer. The Hermes behaviour comes from reading its source; it has
not been checked against a live Hermes.

At most once, as in the companion: if the socket drops before `prompt.submit` leaves the phone, the
result is "not sent". After that, it is "check Hermes before sending again", and the plugin never resends.

## Sign-in

The settings page takes the dashboard address (`https://…ts.net:10443`, as for the companion) and
either a username and password or the dashboard session token.

- **Password**: runs the companion's headless native flow. That is `/auth/native/authorize` with
  PKCE, then `/auth/password-login`, then `/auth/native/token`. The Pebble app's `fetch` follows
  redirects itself and may hide the cookie set by authorize. In that case Hermes treats the request
  as a browser login and sets session cookies on the login answer. The plugin then reads the access
  and refresh tokens from those cookies; Hermes accepts them on their own anywhere except behind the
  gateway's Webapp. Tokens are kept in `Pebble.secureStorage` and refreshed before they expire. A
  lease in `localStorage` stops two overlapping runs from replaying a refresh token, which Hermes
  would treat as reuse and revoke.
- **Session token**: for a loopback-mode dashboard; it is sent as `X-Hermes-Session-Token`.

The `Internet` permission is declared for `ts.net`, which covers every tailnet name. A dashboard on
another domain is asked about the first time the plugin uses it.

## Build and test

```bash
node --test test/*.test.js                       # 18 unit tests (fake fetch and gateway)
pebble build                                     # -> build/pebble-plugin.pbw (SDK 4.44+, pebble-tool 5.1+)
NO_PROXY='*' "$(dirname "$(readlink -f "$(which pebble)")")/python" tools/emulator_e2e.py build/pebble-plugin.pbw
```

`tools/emulator_e2e.py` loads the pbw into pypkjs' plugin host and runs it against a scripted Hermes
dashboard (HTTP plus the gateway socket). That host is QuickJS with the phone app's own `fetch`,
`WebSocket` and `secureStorage` shims. The script signs in, reads every source and sends to a new
chat and a named one, then checks what the dashboard received. CI runs all three steps.

To try it on a phone, sideload `build/pebble-plugin.pbw` in the Pebble app (2.0+), open
**Apps → Plugins → Hermes**, sign in, then ask Index to "tell Hermes …".

## Known limits

- **No answer in the receipt.** A plugin runs only per request (about 20 s, with no timers), so it
  cannot wait for a turn. `read_latest_reply` reads the answer later.
- **No push.** A plugin cannot wake itself when Hermes replies, so it is not a replacement for the
  companion's alerts. Push for plugins is listed under Pebble's "Future" plans.
- **Approvals.** A turn sent from the plugin has no client attached that can answer approvals. The
  plugin declines them (code 4404) so the desktop or terminal can answer. With no other client
  attached, Hermes gives up and the step is not approved.
- **No audio.** Plugins cannot play audio on the phone.
- **Weak randomness.** The plugin host offers no `crypto.getRandomValues`, so the PKCE verifier falls
  back to `Math.random`. The verifier lives for about two minutes, and the code comes back in a JSON
  answer, not through a redirect another app could catch.
- **Not tested on a phone.** The phone app's cookie handling, the `ts.net` permission prompt and
  Index's use of `send_to_agent` all need a real device.
