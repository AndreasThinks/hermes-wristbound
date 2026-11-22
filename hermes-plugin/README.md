# Hermes Wristbound: voice plugin

*One of the three parts of [Hermes Wristbound](../README.md): the part that runs on your Hermes host. Plugin name `wristbound-voice`.*

With **Reply with voice** on in the Index plugin, Hermes answers each message you dictate with a
voice reply, the same way `/voice` does on Telegram: it runs its own text-to-speech tool on the
final reply, with your configured TTS provider. Instead of sending the clip to a chat and deleting
it, the plugin keeps it so Wristbound can play it on the watch, headphones or phone.

It is an ordinary Hermes user plugin. It does not patch Hermes and uses only documented extension
points, so it keeps working across Hermes updates.

## Install

On the machine that runs Hermes:

```sh
hermes plugins install AndreasThinks/hermes-wristbound/hermes-plugin/wristbound-voice --enable
```

Or copy `wristbound-voice/` into `~/.hermes/plugins/` and add it to `plugins.enabled` in
`~/.hermes/config.yaml`. Restart the dashboard and the gateway so both load it: the routes live
in the dashboard, and the hooks run in whichever process runs a turn.

You also need a working TTS provider in Hermes (`/voice tts` on Telegram is a quick check).

Then turn on **Reply with voice** in the Index plugin's settings, and keep **Voice** alerts on in
Wristbound.

## How it works

1. **Arm.** Before sending a dictated message, the Index plugin calls `POST /arm` with the exact
   text, and the chat's session id when it is sending to an existing chat. Then it sends the
   message with the normal `prompt.submit`, so it still queues behind a busy turn. If the send
   fails before Hermes accepts it, the plugin cancels the arm.
2. **Pending.** The `pre_llm_call` hook sees a turn whose prompt matches a live arm (same text,
   whitespace ignored, within 10 minutes, used once, and in the armed chat when one was given). A
   tag Hermes puts in front (`[Andreas] …`, `name: …`) still matches; other words in front do
   not. It records the turn as `pending` before any reply exists.
3. **Speak.** The `post_llm_call` hook runs after the reply is saved. It records the reply's row
   id (only a row written during this turn, never an older reply), then synthesizes on a background thread, so the text reply is never delayed. The turn ends
   `ready` with clips, or `failed`; the text reply stands either way.
4. **Play.** Wristbound sees a new reply, asks `/sessions/{id}/replies`, and if the reply is
   `pending` waits up to 20 seconds for it before alerting once, with Play. If the clip is late,
   it alerts with text and the clip appears in history without a second buzz.

Only the armed message is spoken. Anything else in the same chat, typed on the desktop or sent
from Telegram, stays text.

## Routes

All under `/api/plugins/wristbound-voice/`, behind the dashboard's normal sign-in. No route takes a
file path.

| Route | Purpose |
| --- | --- |
| `GET /capabilities` | `{"plugin":"wristbound-voice","version":2}`. A 404 means the plugin is not installed or not enabled. |
| `POST /arm` `{"text": "...", "session_id": "..."}` | Speak the reply to the next turn with exactly this prompt, in that session if `session_id` is given (optional). Returns `{"armed": true, "arm_id": 12, "expires_at": ...}`. |
| `DELETE /arms/{arm_id}` | Cancel an arm that was not used yet (the message was not sent). Returns `{"cancelled": true}`. |
| `GET /sessions/{id}/replies?limit=20` | Voice replies in a session, newest first: `turn_id`, `message_id`, `status` (`pending`, `ready`, `failed`), `created_at`, and `clips` (`id`, `part`, `mime_type`, `created_at`, `expires_at`). |
| `GET /clips/{id}` | The audio. |

## Storage

`~/.hermes/wristbound-voice/`: `voice.db` (SQLite) and `clips/`. Clips and records are removed
after 7 days. A turn that never finished stops reporting `pending` after 10 minutes. Set
`WRISTBOUND_VOICE_HOME` to move it.

## Tests

```sh
cd hermes-plugin
pip install pytest fastapi httpx
python -m pytest tests
```

The tests run without Hermes, with a fake TTS. The plugin was also loaded into a real Hermes
checkout: hooks registered, routes mounted behind dashboard auth, and a real agent turn against a
scripted model produced a `ready` clip bound to the right assistant row.
