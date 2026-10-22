# Hermes Wristbound

A Pebble watch app and Android companion for using [Hermes Agent](https://github.com/NousResearch/hermes-agent) without taking your phone out.

**Status: first vertical slice built, not yet tested on real devices.** The watchapp builds for every Pebble platform and runs in the emulator against a scripted phone; the Android companion builds and passes its unit tests. Nothing has been run against a real Hermes, phone or watch yet; see [docs/TESTING.md](docs/TESTING.md).

## The experience

Browse Hermes sessions, read and send messages, and control audio replies from your wrist. Android connects privately to Hermes over Tailscale, handles synchronisation and plays audio through the phone or connected headphones. Telegram is not required as the client or playback interface.

Pebble Index recordings can continue arriving through Ringbearer. Wristbound should make the resulting Hermes session and replies accessible on the watch, including sessions whose source is Telegram.

## Proposed features

- Recent sessions, unread indicators and recent message history.
- A toggle to include or exclude Telegram-origin sessions.
- Send messages and continue the selected authoritative Hermes session.
- Watch dictation where supported; explicit new-session action.
- Incoming reply notifications with readable text and an audio playback action.
- Play/pause and replay of the selected reply on Android's current audio route.
- Explicit phone-speaker playback; watch playback only if supported by the target hardware and APIs.
- Independent controls for displaying tool activity and notifying about it.
- Cached messages, reconnect catch-up and clear offline/error states.

## Architecture

```text
Pebble watch app
       | Pebble communication APIs
Android companion
       | authenticated HTTPS / event delivery over Tailscale
Hermes session and messaging APIs

Pebble Index -> Ringbearer -> Hermes conversation
                            (existing Telegram ingress may remain)
```

Use existing supported Hermes APIs first. Any missing capabilities should be identified before adding a narrow adapter or proposing upstream changes. No endpoint names or implementation compatibility are promised by this repository.

## Design documents

- [Project brief](docs/PROJECT.md): scope, UX and agreed direction.
- [Architecture and open questions](docs/ARCHITECTURE.md): boundaries, data and reliability.
- [Milestones](docs/MILESTONES.md): discovery and real-device acceptance criteria, with current status.
- [Hermes API findings](docs/HERMES_API.md): the dashboard routes and gateway calls Wristbound uses, sign-in, and remaining limits.
- [Watch ↔ phone protocol](docs/PROTOCOL.md): AppMessage keys, commands and rules.
- [Testing](docs/TESTING.md): automated checks, the emulator harness and the real-device plan.

## What works in this slice

- **Watch** (`watch/`, C, Pebble SDK 4.x): session list with unread, Telegram and working labels; Telegram show/hide toggle; recent history, newest first; full reply view; reply by dictation or quick replies; new session; Play, Play on phone speaker, Pause, Resume, Replay and Stop for one exact reply; incoming-reply alert; clear queued, working, failed, uncertain and read-only states; last session remembered across launches.
- **Phone** (`android/`, Kotlin, PebbleKit Android 2): Hermes dashboard client over the tailnet, signed in the way the desktop app and Hermes-Relay sign in (password or browser sign-in, or the loopback session token); turns run over Hermes' gateway socket, so Telegram, desktop and CLI sessions can be continued from the watch, with at-most-once submission; reply delivery to the open watchapp, or as one phone notification (mirrored to the Pebble with Play / Play on speaker / Read on watch actions); background polling for replies that arrive through Telegram, Ringbearer or the desktop; reply playback on the current audio route: the audio Hermes generated for that reply, else the reply spoken in Hermes' own configured voice, else the phone's voice, pausing on headphone disconnect instead of switching to the speaker; separate settings for visibility, alerts, tool activity and notification privacy; bounded offline cache; encrypted credentials.

## Known gaps

- **Replies from other surfaces are polled**, not pushed (30 s to 5 min, while background sync is on).
- **Tool approvals and clarifications** that Hermes asks for mid-turn must be answered from another client; the watch shows that the turn is waiting.
- A watch reply in a Telegram session is stored in that session, as a desktop reply is; it is not posted into the Telegram chat.

Details are in [docs/HERMES_API.md](docs/HERMES_API.md).

## Not in scope yet

Replacing Hermes, rebuilding Telegram, changing Ringbearer's working ingress, or implementing a separate conversation store. No hosted service, public Internet exposure or generic notification scraping is required by the proposed design.

## Development

```bash
# Watchapp (needs pebble-tool: `uv tool install pebble-tool && pebble sdk install latest`)
cd watch && pebble build            # -> watch/build/watch.pbw

# Android companion (JDK 21, Android SDK platform 37; PebbleKit Android 2 requires compileSdk 37)
cd android && ./gradlew testDebugUnitTest assembleDebug   # -> android/app/build/outputs/apk/debug/
```

CI runs both on every pull request (`.github/workflows/build.yml`).

### Setup

1. On the Hermes host, run the dashboard (`hermes dashboard`, port 9119) on loopback and publish it to your tailnet: `tailscale serve --bg --https=10443 http://127.0.0.1:9119`. If Hermes-Relay is already set up with `hermes-relay-tailscale enable`, this is already done; use the same address.
2. Install the APK on the phone, open Wristbound and enter the dashboard address (e.g. `https://hermes.tailnet-name.ts.net:10443`). Then either sign in with the dashboard username and password, use *Sign in with browser* for other providers, or, for a dashboard without a sign-in page, paste its session token. Wristbound tests the session list and the gateway socket and reports how many Telegram sessions it sees.
3. Install `watch.pbw` through the Pebble app. The debug APK's package (`dev.wristbound.companion.debug`) and the release package are both listed as companion apps.
4. Optional: turn on *Watch for replies in background* to get replies while the watchapp is closed and the phone is locked.

## License

No license has been selected yet. Public visibility does not grant an open-source license.
