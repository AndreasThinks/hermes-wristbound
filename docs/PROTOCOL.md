# Watch ↔ phone protocol (v1)

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
| Route | 10015 | uint8: 0 current route, 1 phone speaker allowed |

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
| 8 | RESUME | |
| 9 | REPLAY | SessionId, MessageId |
| 10 | STOP | |

## Phone → watch (`Type`)

| Type | Name | Fields |
|------|------|--------|
| 1 | SESSION | Index, Total, SessionId, Title, Flags. Total 0 means an empty list. |
| 2 | MESSAGE | SessionId, Index, Total, MessageId, Role, Flags, Text. Oldest first; the watch shows newest first. |
| 3 | STATUS | State (0 ok, 1 offline, 2 stale, 3 not configured, 4 auth error, 5 error), Detail |
| 4 | REPLY | SessionId, MessageId, Title, Text, Flags. A new final reply; the watch vibrates and opens it unless that session is already on screen. |
| 5 | PLAYBACK | MessageId, State (0 idle, 1 playing, 2 paused, 3 needs speaker, 4 error, 5 done), Detail |
| 6 | SUBMIT | SessionId, State (1 queued, 2 working, 3 completed, 4 failed, 5 uncertain, 6 read-only, 7 cancelled), Detail |
| 7 | SESSION_OPENED | SessionId, Title, Flags. Sent after NEW_SESSION created a session. |

Session flags: 1 unread, 2 Telegram origin, 4 read-only through the API, 8 Hermes is working. Message flags: 1 truncated (rest on phone), 2 Hermes attached audio, 4 pending.

## Rules

- One message in flight each way. The watch queues commands and retries a failed send three times; a SEND the phone refused is reported as failed, and one that timed out as uncertain, never as sent. Because a retry can repeat a command whose acknowledgement was lost, the phone ignores an identical SEND or NEW_SESSION received within 15 seconds.
- The phone answers every command asynchronously: it acknowledges the AppMessage at once and sends results as separate messages, so a slow Hermes call never times out the watch.
- Lists are sent as numbered pages. Index 0 resets the list on the watch. Pages for a session the user has left are ignored.
- A transport acknowledgement means only that the phone has the command. Whether Hermes ran it arrives later as SUBMIT states. *Uncertain* means the phone lost the stream after Hermes may have accepted the turn; it is not resent.
- PLAY names one exact message id. Without headphones and with Route 0 the phone answers PLAYBACK state 3 instead of switching to the loudspeaker.
