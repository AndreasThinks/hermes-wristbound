# Index spoken replies: Wristbound checkpoint

This branch captures the current implementation, not a release. It combines the latest Wristbound main at the time of the checkpoint (`b798eabbc756677cffcb8b5fb7dd108464cd08fe`) with the two local patches tested on the phone. Pair it with the [matching Hermes backend checkpoint](https://github.com/AndreasThinks/hermes-agent/blob/checkpoint/index-spoken-replies/docs/index-spoken-replies.md).

| Component | State in this branch |
| --- | --- |
| Wristbound Pebble watch app (`watch/`) | v0.6.7, unchanged from main. Protocol v5 and the watch UUID are unchanged. |
| Wristbound Android companion (`android/`) | v0.6.10 / versionCode 16, plus the late-audio duplicate-alert fix. Version labels were not bumped for this checkpoint. |
| Hermes for Index (`pebble-plugin/`) | v0.1.5 test source with the protocol-2 capability check and per-capture speech opt-in. The Store version label alone does not prove this exact build is installed. |

## Setup and expected behaviour

1. In Pebble's Index settings, route **Hold & Talk** to **Other agents → Hermes for Index** only. Leave **Double click & hold** on **Webhook only** for Ringbearer. Do not send a capture through both routes.
2. Sign in to the matching Hermes dashboard from the plugin and choose the intended conversation. Enable **Spoken replies** for Index captures if wanted. The plugin checks backend capability before sending; a server without protocol 2 gets one ordinary text submission. It never automatically resends after an uncertain acknowledgement. The receipt means accepted, not that speech has been generated.
3. In Wristbound, enable background checking and the relevant **Voice** / **Chat replies** switches. **Messaging text** can stay off. In the Pebble phone app, check **More → Notifications → Apps → Wristbound**: the app itself must not be muted, even if **Hermes replies · silent on phone** is already enabled. The phone channel can stay quiet.
4. With the Pixel locked and the ordinary watchface showing, make one distinct capture. Check the intended Hermes conversation for a single prompt and exact reply, then check the native watch alert. If the clip was already available on first delivery, the alert can offer **Play audio**. Otherwise check that the original reply or Recent audio gains the clip without another buzz. Play is explicit; phone-speaker playback is a separate action. Nothing autoplays.

## What is still broken

Wristbound can alert on text before synthesis completes. The Android patch records a successful alert for the exact reply and does not repost it when audio arrives. That prevents the second buzz but **does not add Play to an already-delivered text alert**. An eligible first audio alert can still fire if no text alert was delivered. The proposed next change is a bounded first-alert wait driven by an explicit server speech-readiness state, only for opted-in turns. It is not in this checkpoint. Typed turns should not wait for speech.

## Verification

- `npm test --prefix pebble-plugin`: 27 tests passed, including the capability gate and no-resend behaviour. Plugin-only PBW rebuilt with Pebble Tool 5.2.0 / SDK 4.44 and passed the scripted plugin-host routing and sign-in smoke (`pebble-plugin/tools/emulator_e2e.py`). The smoke uses fixtures; it is not a physical audio test.
- From `android/`, `./gradlew --offline testDebugUnitTest assembleDebug --console=plain`: 176 JVM tests passed and the debug APK built. Build setup is in [TESTING.md](TESTING.md) and `.github/workflows/build.yml`.
- Earlier on-device testing verified one alert despite an exact clip arriving about eight seconds after text. The Play-on-first-alert hardware case is not signed off. No Store release, production upgrade, re-pair, or Desktop change is part of this checkpoint.

For rollback, keep a copy of the currently installed APK and verify signing identity before replacing it. To revert the plugin, restore the previous PBW with the same UUID; do not duplicate the capture route. See [INSTALL.md](INSTALL.md) for normal installation. This branch contains source only, not signing material, account data, or built APK/PBW files.
