# Watch ↔ phone protocol (v4)

Pebble AppMessages between the watchapp (`watch/src/c/protocol.h`) and the Android companion (`android/.../pebble/Protocol.kt`). The phone holds all Hermes state and credentials; the watch holds only what is on screen.

## Keys

Keys are numbered by the Pebble SDK from the order of `messageKeys` in `watch/package.json`, starting at 10000. Reordering that list breaks the protocol; a unit test checks the two sides agree.

| Key | Id | Type |
|-----|----|------|
| Cmd | 10000 | uint8, watch → phone |
| Type | 10001 | uint8, phone → watch |
| ProtoVersion | 10002 | uint8 |
| TextBudget | 10003 | uint16, bytes the watch keeps per message |
| SessionId | 10004 | cstring |
| Title | 10005 | cstring (≤ 47 bytes) |
| Flags | 10006 | uint8 |
| Index | 10007 | uint8 |
| Total | 10008 | uint8 |
| MessageId | 10009 | cstring |
| Role | 10010 | uint8: 0 user, 1 assistant, 2 tool, 3 system |
| Text | 10011 | cstring (UTF-8, ≤ TextBudget − 1 bytes) |
| State | 10012 | uint8 |
| Detail | 10013 | cstring (≤ 63 bytes), human-readable status |
| Filter | 10014 | uint8: 1 include Telegram sessions |
| Route | 10015 | uint8: 0 headphones only, 1 the phone's own speaker |
| Time | 10016 | uint32, seconds since the epoch |
| Data | 10017 | byte array, audio samples |
| Offset | 10018 | uint32, byte offset into a clip |
| Length | 10019 | uint32, byte count |

## Watch → phone (`Cmd`)

| Cmd | Name | Fields |
|-----|------|--------|
| 1 | HELLO | TextBudget, ProtoVersion, Filter, SessionId (last open, optional) |
| 2 | LIST_SESSIONS | Filter |
| 3 | OPEN_SESSION | SessionId |
| 4 | SEND | SessionId, Text |
| 5 | NEW_SESSION | Text (first message) |
| 6 | PLAY | SessionId, MessageId, Route |
| 7 | PAUSE | |
| 8 | RESUME | Route |
| 9 | REPLAY | SessionId, MessageId, Route |
| 10 | STOP | |
| 11 | LIST_AUDIO | Filter. Asks for the Recent audio list, refreshed from Hermes. |
| 12 | OPEN_REPLY | SessionId, MessageId. Asks for one reply's full text; answered with REPLY flagged *requested*. |
| 13 | PLAY_WATCH | SessionId, MessageId, TextBudget (largest Data chunk the watch takes). Play this reply on the watch's own speaker; answered with AUDIO_START. Sending it again replays from the start. |
| 14 | AUDIO_PULL | MessageId, Offset, Length. The phone may send the clip up to Offset + Length. |
| 15 | AUDIO_STOP | MessageId. Stop sending this clip. |

## Phone → watch (`Type`)

| Type | Name | Fields |
|------|------|--------|
| 1 | SESSION | Index, Total, SessionId, Title, Flags. Total 0 means an empty list. |
| 2 | MESSAGE | SessionId, Index, Total, MessageId, Role, Flags, Text. Oldest first; the watch shows newest first. |
| 3 | STATUS | State (0 ok, 1 offline, 2 stale, 3 not configured, 4 auth error, 5 error), Detail |
| 4 | REPLY | SessionId, MessageId, Title, Text, Flags. A new final reply; the watch vibrates and opens it as a card, even over the same session. With flag 8 (*requested*) it answers OPEN_REPLY instead: no vibration, and it only fills in the card already showing that message. |
| 5 | PLAYBACK | MessageId, State (0 idle, 1 playing, 2 paused, 3 needs speaker, 4 error, 5 done), Detail |
| 6 | SUBMIT | SessionId, State (1 queued, 2 working, 3 completed, 4 failed, 5 uncertain, 6 read-only, 7 cancelled), Detail |
| 7 | SESSION_OPENED | SessionId, Title, Flags. Sent after NEW_SESSION created a session. |
| 8 | AUDIO | Index, Total, SessionId, MessageId, Title (session), Text (preview, ≤ 63 bytes), Flags (1 new, 2 Telegram), Time. One Recent audio row, newest first. Total 0 means an empty list. |
| 9 | AUDIO_START | MessageId, Length (bytes; 0 means it cannot play, with the reason in Detail), State (format: 0 = 8 kHz 8-bit signed mono PCM), Detail (label, such as "Hermes audio"). |
| 10 | AUDIO_DATA | MessageId, Offset, Data. One chunk, in order. |
| 11 | AUDIO_ROUTE | State (1 = headphones connected to the phone). Sent after HELLO and whenever headphones connect or disconnect; the watch offers *Play on headphones* only while it is 1. |

Session flags: 1 unread, 2 Telegram origin, 4 Hermes refused to continue it (for example a Kanban worker transcript), 8 Hermes is working. Message flags: 1 truncated (rest on phone), 2 Hermes generated audio for this reply, 4 pending, 8 requested (REPLY only).

## Rules

- One message in flight each way. The watch queues commands and retries a failed send three times; a SEND the phone refused is reported as failed, and one that timed out as uncertain, never as sent. Because a retry can repeat a command whose acknowledgement was lost, the phone ignores an identical SEND or NEW_SESSION received within 15 seconds.
- The phone answers every command asynchronously: it acknowledges the AppMessage at once and sends results as separate messages, so a slow Hermes call never times out the watch.
- Lists are sent as numbered pages. Index 0 resets the list on the watch. Pages for a session the user has left are ignored.
- A transport acknowledgement means only that the phone has the command. Whether Hermes ran it arrives later as SUBMIT states. *Uncertain* means the phone lost the stream after Hermes may have accepted the turn; it is not resent.
- Recent audio lists assistant replies that carry audio Hermes generated (its text-to-speech output), from the 12 most recently active visible sessions, the last 60 transcript rows of each, newest 15 overall (5 on aplite). The phone keeps a bounded copy (30 items) so the list shows at once, offline and after a restart; HELLO sends that copy, LIST_AUDIO sends it and then a refreshed one. Items are new until opened on the watch. The user's own recordings never appear, nor do Telegram's automatic voice replies, which Hermes does not keep.
- A watch and phone on different protocol versions show "Update watch or phone app".
- PLAY names one exact message id. With Route 0 and no headphones (they went between the menu and the press) the phone answers PLAYBACK state 3 instead of switching to the loudspeaker. Route 1 plays from the phone's built-in speaker even while headphones are connected (the phone voice is rendered to a file for this, since Android speech always follows the media route). RESUME and REPLAY carry the route of the last PLAY; a missing Route counts as 0.
- Watch speaker (emery, flint and gabbro only). The phone decodes the reply's own Hermes audio, mixes it to mono, resamples it to 8 kHz 8-bit and caps it at 2 minutes (960 KB), so the watch never decodes anything. Flow control is pull based: the watch asks with AUDIO_PULL for as much as its ring buffer has room for (48 KB, about 6 s; 16 KB on flint), and the phone never sends past the last pull. The watch buffers before it starts, by an amount set from the link rate it measures, and goes back to buffering if the link falls behind. The phone keeps the last two converted clips, so Replay does not download again. A reply without Hermes audio is answered with Length 0 and a reason; nothing ever falls back to the phone's speaker.
