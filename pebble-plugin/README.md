# Hermes plugin for Pebble (prototype)

A [Pebble plugin](https://developer.repebble.com/guides/plugins/preview/) that makes Hermes an
**agent destination**. Index and any plugin-aware watchface or app can then send to Hermes without the
Wristbound watchapp, the companion APK or Telegram. It sits beside Wristbound; it does not replace it.

It runs as JavaScript inside the Pebble phone app (Android and iOS) and talks to the same Hermes
dashboard as the companion ([docs/HERMES_API.md](../docs/HERMES_API.md)).

## Set up

1. Install [Hermes for Index (Experimental)](https://apps.repebble.com/77caeb14dc3f4d3c8367ab82) in the Pebble phone app. You do not need the Wristbound watchapp or companion APK.
2. Open **Apps → Plugins → Hermes → Settings**. Enter your Hermes **dashboard** address, not the general API port. For password sign-in use an authenticated HTTPS endpoint reachable from your phone, such as a private Tailscale HTTPS endpoint.
3. Sign in and choose a **Destination**. Index can then send a request with “Tell Hermes …”. Open Hermes to see the reply. The send receipt confirms acceptance, not completion.

**Password sign-in requires a compatible Hermes dashboard.** It must support `response_mode=json` on `/auth/native/authorize` and `native_only: true` on `/auth/password-login`. If your dashboard lacks that support, stock password sign-in will not work in the plugin. For self-hosters, see the [dashboard compatibility notes and patch](compatibility/README.md). The dashboard session-token option is only for installations configured for that mode; it is not a substitute for dashboard credentials. Do not expose a loopback-only dashboard to the internet.

## Choose where Index sends

| Destination | What an ordinary capture does |
| --- | --- |
| **Most recently active Hermes conversation** | Checks the dashboard on every send and follows the latest eligible chat, including chats you use on desktop. If nothing qualifies, ask explicitly for a new session. |
| **Continue my Index conversation** | Starts a conversation on your first capture and sticks with it. An explicit fresh send changes the saved chat only after Hermes accepts the prompt. |
| **A new Hermes session** | Starts a separate conversation for every capture. Existing installations keep their saved choice. |
| **A named chat in Settings** | Sends to that chat until you change the setting. |

To start fresh from Index, lead with **“New Hermes session, then …”** and say your message. The plugin removes the command before sending if the transcript preserves it. Mentioning a new session later in the message does not switch chats. You can also name a chat for a one-off send; using Latest can then follow that chat if it becomes the most recently active. Latest skips archived, hidden and automation chats, and respects **Don't send into Telegram and other messaging chats**.

If Ringbearer or another webhook handles the same gesture, use only one delivery route for an actionable request. A connection loss after submission can leave delivery uncertain. Check Hermes before repeating; the plugin does not automatically resend.

## Integration details

`send_to_agent` accepts a message and optional `chat_id`: a visible session ID, title, unique title fragment, or `new`. `read_latest_reply` reads a reply as text. The plugin exposes `agent/chats`, `agent/configured` and `hermes/status` sources.

Password sign-in uses HTTPS, secure randomness from the settings view, and a one-time PKCE code. The plugin stores tokens in Pebble secure storage. It does not recover browser cookies as bearer credentials. Approvals still need a compatible Hermes client.

## Build and test

```bash
node --test test/*.test.js                       # unit tests (fake fetch and gateway)
pebble build                                     # -> build/pebble-plugin.pbw (SDK 4.44+, pebble-tool 5.1+)
NO_PROXY='*' "$(dirname "$(readlink -f "$(which pebble)")")/python" tools/emulator_e2e.py build/pebble-plugin.pbw
```

`tools/emulator_e2e.py` loads the pbw into pypkjs' plugin host and runs it against a scripted Hermes
dashboard over HTTPS and WebSocket. It verifies sign-in, session routing, sources, and reply readback against a fixture. This is not a test of speech recognition on a physical Index.

To try it on a phone, sideload `build/pebble-plugin.pbw` in a plugin-capable Pebble app, open
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
- **Device coverage.** Password sign-in, direct delivery, fresh sessions and continuation were tested on an Android Pixel and Index with a compatible Hermes server. Speech transcription was imperfect. Latest mode was verified in the fixture, not on physical hardware. iOS is unverified.
