# Hermes Wristbound v0.6.13: wrist-safe approvals, reliable sends, exact voice replies

Android companion 0.6.13 (versionCode 19), Index plugin 0.1.8 and voice plugin 0.2.0. The watch app is unchanged (0.6.8), and so are protocol v5 and the watch UUID. This is the first release built and published by the release workflow ([Releasing](RELEASING.md)).

## Android companion 0.6.13

- **Approvals from the wrist are limited to Allow once and Deny.** Allow session and Always allow stay on the desktop, and the companion refuses either if an older watch app sends it. Approval actions on the phone notification need an unlocked screen, and the lock screen shows a stand-in instead of the command. Actions mirrored to the Pebble still work.
- **Less battery while Hermes is busy.** With the watch app closed and no watch turn running, checks triggered by `sessions.changed` are at least 20 s apart. Before, they could run every 3 to 5 s while Hermes was busy anywhere. The last change of a burst is never dropped.
- **Quick Launch shows whether the watch has it.** The setting says *Applied on the watch* or *Saved here; the watch picks it up next time*, explains each choice (including the speaker warning on *Play latest audio*), and says how to assign the button.

## Index plugin 0.1.8

- **Sends give up instead of hanging.** A send stops after 10 s without `gateway.ready`, or 15 s on any step before submit, and reports *not sent*. If `prompt.submit` gets no confirmation within 20 s, it reports *check Hermes before sending again*.
- **Voice replies go to the right chat.** A voice capture sent to a known chat arms only that chat, and the arm is cancelled when the message certainly did not go.

## Voice plugin 0.2.0

- An arm can carry a `session_id` and then matches only that chat, or the session continuing it.
- A tag Hermes puts in front of the prompt (`[Name] …`, `name: …`) still matches; ordinary words in front no longer do.
- New routes: `POST /arm` returns an `arm_id`, and `DELETE /arms/{id}` cancels an unused arm.
- A clip binds only to an assistant reply written since its turn started.
- Stores from 0.1.0 and 0.1.1 are upgraded on first use.

Update it on your Hermes host with `hermes plugins install AndreasThinks/hermes-wristbound/hermes-plugin/wristbound-voice --enable`, then restart the dashboard and gateway.

## Install

- Install `wristbound-companion-v0.6.13-debug.apk` over the existing companion without uninstalling. It is signed with the same key as 0.6.12.
- Update Hermes for Index from the Pebble Store.
- The watch app needs no update.

## Verification and limits

- Android: 193 unit tests pass and `assembleDebug` builds. Index plugin: 34 tests pass, and the release build runs it in the plugin host against a scripted Hermes. Voice plugin: 31 tests pass.
- Not yet tried on hardware. Still to check, listed in [Testing](TESTING.md): approvals from the wrist and on a locked phone, busy-check spacing over a day, and voice replies to a chosen chat.
