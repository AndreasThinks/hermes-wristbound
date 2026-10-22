# Testing

## Automated

```bash
# Watchapp: builds for aplite, basalt, chalk, diorite, emery, flint and gabbro
cd watch && pebble build

# Companion: unit tests and debug APK
cd android && ./gradlew testDebugUnitTest assembleDebug
```

The unit tests cover the SSE parser, the Hermes client against a mock server (auth header, message parsing, streamed turn events, `409 permission_denied`), turn outcomes (completed, failed, read-only, refused connection, a dropped stream observed through `/v1/runs` without resending, uncertain), session filtering and flags, notification eligibility, text fitting for the watch, protocol encoding, and that the message keys match `watch/package.json`.

## Watchapp in the emulator

`watch/tools/fake_phone.py` plays the phone side of the protocol with canned data, so the watch UI can be clicked through without Android or Hermes:

```bash
cd watch
pebble install --emulator basalt
~/.local/share/uv/tools/pebble-tool/bin/python tools/fake_phone.py basalt &
pebble emu-button click select --emulator basalt
pebble screenshot --emulator basalt
```

Checked on basalt and aplite this way: session list with unread, Telegram and read-only labels; the Telegram toggle; opening a session (newest first); reading a reply; the action menu; Play without headphones reporting "No headphones"; Play on phone speaker; a quick reply moving through working to completed.

## Not yet tested: real devices

Nothing here has run against a real Hermes, phone or watch. Milestone 2's acceptance needs, in order:

1. **Connection.** Expose Hermes on the tailnet (`tailscale serve --https=443 http://127.0.0.1:8642`), install the APK, enter the URL and `API_SERVER_KEY`, press *Save and test*. Expect "Connected" and a session count.
2. **Watch link.** Install `watch/build/watch.pbw` through the Pebble/Core app, with the debug APK installed (package `dev.wristbound.companion.debug`, listed in `companionApp`). Open Wristbound on the watch; the session list should load.
3. **Sessions and filter.** Toggle Telegram sessions; confirm the list changes and that alerts do not.
4. **History.** Open an API session and a Telegram session; compare with the desktop app.
5. **Send.** Send a harmless quick reply into an API-created session. Confirm on the desktop that it landed in that session (not a fork) and that the reply appears on the watch.
6. **Telegram-origin send.** Try the same in a Telegram session. Expected today: Hermes refuses with `permission_denied`, the watch shows "Reply from its own app", and nothing is sent. Record the actual behaviour.
7. **Locked phone.** Enable *Watch for replies in background*, lock the phone, close the watchapp, send a message from the desktop or Telegram, and confirm one notification on the watch with Play / Play on speaker / Read on watch actions.
8. **Playback.** With headphones: Play from the watch, Pause, Resume, Replay. Unplug or disconnect the headphones mid-reply: playback must pause, not move to the speaker. Without headphones: Play reports "No headphones"; *Play on phone speaker* plays.
9. **Ringbearer.** Record on the Pebble Index; confirm the resulting reply is visible in the expected session and notifies once.
10. **Reliability.** Kill the companion during a turn and reopen; toggle airplane mode mid-turn (expect *uncertain* or a recovered result, never a duplicate turn); restart Hermes mid-turn (expect *uncertain*).

Record the watch model and firmware, phone model, Android version, Pebble app version and Hermes version with each run.
