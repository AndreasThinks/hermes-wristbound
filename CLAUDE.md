# Hermes Wristbound implementation guidance

Start with `docs/CLOUD-HANDOFF.md` for actual checkpoint state, build prerequisites, remaining tasks and operational boundaries. Continue the partial implementation on a feature branch; proposal documents are not completion evidence.

Read README.md and docs/ for the project proposal. The following requirement was added by the user while the initial Claude Code implementation was running. Incorporate it before declaring the initial version complete.

## Telegram session controls: independent visibility, notifications and replies

Users may keep their existing Telegram-to-watch notification path for Telegram sessions while using Wristbound for every other Hermes session. Avoid duplicate notifications without hiding useful session history.

Provide independent persistent preferences:

1. Include Telegram sessions in session lists/history.
2. Notify for Telegram text replies. When disabled, suppress Wristbound text-only alerts for those sessions, but retain history/sync when included. Add an independent option to notify for incoming assistant audio replies even when Telegram text alerts are disabled. This audio exception must not enable autoplay, sending replies, or Telegram's own alerts. Do not change Telegram's own settings or Hermes delivery to Telegram.
3. Allow sending replies to Telegram-origin sessions from Wristbound. When disabled, those visible sessions are read-only in Wristbound, with a clear explanation; non-Telegram sessions remain usable.

A concrete acceptance configuration: Telegram sessions visible, Telegram notifications disabled, Telegram replies disabled; non-Telegram sessions still notify and accept replies according to their own settings. Also support opting into Telegram notifications and replies. Choose and document defaults; do not couple these three preferences to one switch.

Use authoritative session source/origin metadata, not message text or title matching. If metadata cannot identify origin, define and document conservative unknown-source behaviour without silently labelling it Telegram. Audio exception requirement: allow assistant audio replies from Telegram-origin sessions to produce a Wristbound Play notification even when Telegram text notifications are off. This solves the user's Ringbearer workflow by media kind, not by assuming audio proves ring provenance. Only assistant replies qualify, not the user's incoming ring recording. Any Telegram-session audio reply may qualify unless trusted metadata permits narrower filtering. Investigate whether Hermes API exposes generated audio events/assets, including delayed TTS and separately delivered audio; do not assume transcript/history text contains them. Use stable message/media/turn identifiers to avoid duplicate alerts on reconnect and text-then-audio arrival; an updated existing notification may be preferable when text already alerted. Never infer linkage merely from adjacency. Respect global mute, explicit session mute and notification privacy; define precedence clearly. Manual Play remains manual. Add tests for text off/audio on, both off, late audio, replayed events and no outgoing-recording notifications. Review the best UX and API approach before treating a whole-session Telegram filter as sufficient.

These settings govern this client's behaviour only. They must not stop Hermes responding, disable server execution, mute Telegram remotely, or rewrite Ringbearer routing. Include tests for all relevant preference combinations and settings persistence. Preserve source security and ordinary global/session notification controls.
