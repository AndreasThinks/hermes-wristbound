# What Hermes Wristbound does

Everything the watch app and its Android companion do today. For the Index plugin see [its README](../pebble-plugin/README.md), and for voice replies see [the voice plugin](../hermes-plugin/README.md). How it looks and feels is in the [visual guide](BRAND.md).


## On the watch

- **Home:** New session, Continue (the last session), Recent audio and Sessions, with the connection state at the top.
- **Recent audio:** Hermes audio replies from every session, newest first, marked new until opened. Each opens as a reply card with Play, Reply and Open session, so incoming audio never needs hunting for.
- **Sessions:** recent sessions with unread, Telegram, working and read-only labels; long-press to refresh. The last session is remembered across launches.
- **Conversation:** a session opens as one scrolling conversation, your messages and Hermes' replies together, and a reply streams in as Hermes writes it. Select replies; hold Select for playback of the reply in focus, Newest and Refresh.
- **Reply:** dictation where the watch has a microphone, or a quick reply (Yes, No, Continue, Thanks, Stop, Summarise that). Each send shows queued, working, completed, failed, uncertain or read-only.
- **Playback** of one exact reply: Play on headphones (shown only while headphones are connected to the phone), Play on phone speaker (the phone's own speaker, even with headphones in), Pause, Resume, Replay from start, Stop.
- **Play on watch speaker** (Pebble Time 2 and Pebble 2 Duo): the reply's own Hermes audio, streamed from the phone and played by the watch, with buffering progress, Pause/Resume, Stop and Replay on watch. Always an explicit choice; it never falls back to the phone's speaker. Clips are capped at 2 minutes. Other watches do not show the action.
- **Quick Launch:** assign Wristbound to a Quick Launch button in the Pebble settings, then pick what it does in the companion app under *Watch → Watchapp opens on*: *Home*, *Dictate a new session* (straight into dictation for a new session), *Recent audio*, *Play latest audio* (opens and plays the newest audio reply, on headphones if connected, otherwise the phone speaker) or *Last session*. Opening the app normally always shows Home.
- **Approvals:** when Hermes asks before a step (a shell command, say) in a turn you sent from the watch, the question takes the screen with the needs-you haptic. Up and Down pick *Allow once*, *Allow session*, *Always allow* or *Deny* (Deny is picked first, so a stray press never approves), Select answers. Back puts it away; the conversation offers it again. With the watchapp closed, and on the original Pebble, it arrives as a phone notification with the answers as actions, mirrored to the Pebble.
- **Alerts:** a reply that arrives while the app is open slides up as a banner over the lower part of the screen, in Phosphor green (violet for audio), with the session and the start of the reply. A short first sentence or line is shown in bold as a headline, an audio reply carries an AUDIO tag, and the card says what Select does. Select opens the conversation at that reply; Back puts it away, and it fades after 15 s. A reply in the conversation on screen just appears there.
- Clear connection states: connected, offline with cached data, stale, not set up, sign-in failed.

## On the phone

- Signs in to the Hermes dashboard over your tailnet with a password, a browser sign-in, or the loopback session token. Credentials are stored encrypted, and tokens refresh on their own.
- Runs turns over Hermes' gateway socket with at-most-once submission: if a connection drops after sending, it looks for the answer in the transcript or re-attaches to the running turn, and reports *uncertain* rather than sending twice.
- Optionally opens Wristbound for incoming alerts (off by default); startup waits up to 10 seconds for HELLO before falling back to the native notification. Silent late-audio updates do not open the watchapp.
- Delivers replies to the open watchapp, or as one phone notification mirrored to the Pebble with Reply (dictation or a canned answer, sent to that session), Open in Wristbound (opens the watchapp at that conversation), Play audio and Play on phone speaker actions, in that order. Reply notifications use a separate channel with silent phone defaults; Pebble forwarding and vibration still need device acceptance.
- Replies that arrive through Telegram, Ringbearer, Index or the desktop reach the watch within seconds: the companion keeps one quiet connection to the dashboard and checks only when Hermes says a session changed, with a timed check every 15 minutes as a safety net (or at your *Reply speed* while the connection is down). This works with the watchapp closed and the phone locked, and a turn sent from the watch or a reply playing on the phone keeps the companion running after the watchapp closes.
- Plays a reply on headphones or on the phone's speaker, whichever the watch picked: Hermes' own audio for that reply. Only replies that carry Hermes audio offer Play; text replies are never read aloud. Headphone playback pauses when they disconnect instead of switching to the speaker; speaker playback is always explicit and uses the phone's speaker even while headphones are connected. The watch is told whenever headphones connect or disconnect.
- Chooses which sessions the watch lists: chats on this machine always, messaging chats (Telegram, Discord, Slack…) by default, scheduled and automation sessions (cron, Kanban, webhooks, subagents) only when asked; pinned sessions first.
- One alert policy for every delivery path, decided by what a reply answers: a voice reply from any session, a reply to a message sent from the watch, a reply in a desktop or terminal chat, a text reply in a messaging chat (off by default), or background work (scheduled sessions, and Hermes following up on its own tasks; off by default). Each has its own switch, under one master switch, alongside replying to Telegram sessions, tool activity and notification privacy. Polling checkpoints persist, so a failed fetch or a restart never loses or repeats an alert.
- Voice replies from the wristbound-voice Hermes plugin: a reply whose clip is still being made waits up to 20 s so its one alert already offers Play; after that it alerts as text and the clip joins history without a second buzz.
- Bounded offline conversation/audio caches on the phone; the watch never stores dashboard credentials.

## Known limits

- **Replies from other surfaces arrive over one live connection** to the dashboard (since Android v0.6.10), with a check every 15 minutes as a safety net. If that connection drops, the companion falls back to timed checks at your *Reply speed* (30 s while the watchapp is open); in deep Doze Android spaces those out further.
- **Clarifications and password prompts** that Hermes asks for mid-turn must be answered from another client; the watch shows that the turn is waiting. Approvals are answered on the watch, but only in turns sent from the watch.
- A watch reply in a Telegram session is stored in that session, as a desktop reply is; it is not posted into the Telegram chat. Replies to Telegram sessions can be turned off.
- **Voice replies need a Hermes plugin.** With *Reply with voice* on in the Index plugin, the [wristbound-voice Hermes plugin](../hermes-plugin/README.md) speaks the reply to each dictated message with your Hermes TTS, and Wristbound alerts once with Play. Telegram's own `/voice` replies are deleted by Hermes after sending, so they cannot be played here. Wristbound never synthesizes replacement speech.
- **Watch speaker playback is untested on hardware.** It works in the emulator up to the point of sound (the emulator has none). Audio is 8 kHz 8-bit to fit Bluetooth, so expect phone-call quality, and a slow link drops back to buffering mid-clip.
- Incoming replies do not autoplay. The optional **Play latest audio** Quick Launch action deliberately starts playback and may use the phone speaker when headphones are absent.

Details are in [HERMES_API.md](HERMES_API.md).

