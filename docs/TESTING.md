# Testing

## Automated

```bash
# Watchapp: builds for aplite, basalt, chalk, diorite, emery, flint and gabbro
cd watch && pebble build

# Companion: unit tests and debug APK
cd android && ./gradlew testDebugUnitTest assembleDebug
```

The unit tests cover the dashboard client against a mock server (bearer and session-token auth, token refresh and rotation, a dead refresh token signing out, password sign-in through the native PKCE flow, the gateway socket's ticket, ready frame, JSON-RPC answers, errors and events, Hermes audio download and Hermes voice), turn outcomes over the gateway (completed, tool activity, other sessions' events ignored, new sessions, queued behind a running turn, failed, interrupted, refused resume, refused submit, unreachable, signed out, a drop before sending, and after sending: answer found in the transcript, re-attaching to the running turn, no trace, and Hermes staying offline, each without resending), recovery bound to the submitted prompt (an older identical prompt and its answer, a later turn's answer, the acked row id, and no boundary), reply audio classification (text-to-speech output only, the user's own voice note excluded, audio arriving after the text, media-only answers), reply alerts through one policy (the Ringbearer-in-Telegram acceptance case, Telegram text off with audio on, both off, late audio alerting once or refreshing silently, a failed fetch retried at the same activity, busy sessions, restart catch-up from saved checkpoints, the first-poll baseline, direct completions under the same policy, and a direct completion plus a later poll alerting once), Telegram read-only mode with other sessions writable, session filtering and flags, text fitting for the watch, protocol encoding, and that the message keys match `watch/package.json`.

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

1. **Connection.** Expose the dashboard on the tailnet (`tailscale serve --bg --https=10443 http://127.0.0.1:9119`), install the APK, enter the URL and sign in (password, browser, or session token). Expect "Connected" with session and Telegram counts. Note which sign-in provider the dashboard uses.
2. **Watch link.** Install `watch/build/watch.pbw` through the Pebble/Core app, with the debug APK installed (package `dev.wristbound.companion.debug`, listed in `companionApp`). Open Wristbound on the watch; the session list should load.
3. **Sessions and filter.** Toggle Telegram sessions; confirm the list changes and that alerts do not.
4. **History.** Open an API session and a Telegram session; compare with the desktop app.
5. **Send.** Send a harmless quick reply into an API-created session. Confirm on the desktop that it landed in that session (not a fork) and that the reply appears on the watch.
6. **Telegram-origin send.** Send a quick reply in a Telegram session. Expect working, then completed, and the turn visible in that session on the desktop (not posted into Telegram). Also send while a desktop turn is running in that session: expect "Queued behind a running turn", not an interrupted desktop turn.
7. **Locked phone.** Enable *Watch for replies in background*, lock the phone, close the watchapp, send a message from the desktop or Telegram, and confirm one notification on the watch with Play / Play on speaker / Read on watch actions.
8. **Playback.** Ask Hermes to reply with voice (its text-to-speech tool), then Play from the watch: expect "Playing Hermes audio". Play a plain text reply: expect "Hermes voice, read again" (or "Playing phone voice" with *Use Hermes' voice* off). With headphones: Pause, Resume, Replay. Unplug or disconnect the headphones mid-reply: playback must pause, not move to the speaker. Without headphones: Play reports "No headphones"; *Play on phone speaker* plays.
9. **Ringbearer (acceptance).** Settings: Telegram sessions shown, *Also notify for Telegram sessions* off, *Notify Telegram replies with Hermes audio* on, optionally *Allow replies to Telegram sessions* off. Lock the phone, close the watchapp, and record on the Pebble Index so Hermes answers with its text-to-speech tool. Expect exactly one notification, labelled as audio, whose Play plays that audio; the recording itself must not be offered as the reply. Nothing may play on its own. Repeat with a reply whose audio arrives after the text (one alert, when the audio arrives), with a text-only reply (no alert), with airplane mode toggled across the reply (still one alert), and after killing and reopening the companion (one alert, no repeats). With Telegram replies off, the session's Reply row must say "Read-only here" and refuse to open, while other sessions still send. Note: Telegram's own automatic voice replies cannot be played here; see the audio contract in [HERMES_API.md](HERMES_API.md).
10. **Reliability.** Kill the companion during a turn and reopen; toggle airplane mode mid-turn (expect the reply once the phone reconnects, or *uncertain*, never a duplicate turn); restart Hermes mid-turn (expect *uncertain*). Leave the phone signed in for more than a day to confirm token refresh.

Record the watch model and firmware, phone model, Android version, Pebble app version and Hermes version with each run.
