# Hermes Wristbound

A Pebble watch app and Android companion for using [Hermes Agent](https://github.com/NousResearch/hermes-agent) without taking your phone out.

**Status: incomplete initial implementation on `feat/initial-client`.** Android debug packaging has been verified locally. The Pebble app is partial; functional, audio-delivery and real-device acceptance remain unverified. This is not a release.

**Continuing in Claude Code Cloud? Start with [the Cloud handoff](docs/CLOUD-HANDOFF.md) and `CLAUDE.md`.** They record verified progress, missing pieces, Telegram text/audio notification requirements and build prerequisites.

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
- [First milestone](docs/MILESTONES.md): discovery and real-device acceptance criteria.

## Not in scope yet

Replacing Hermes, rebuilding Telegram, changing Ringbearer's working ingress, or implementing a separate conversation store. No hosted service, public Internet exposure or generic notification scraping is required by the proposed design.

## Development

The partial implementation contains Kotlin Android and C Pebble sources. See [the Cloud handoff](docs/CLOUD-HANDOFF.md) for build setup, the verified Android command and remaining acceptance work. The original design documents below describe the target, not completed features.

## License

No license has been selected yet. Public visibility does not grant an open-source license.
