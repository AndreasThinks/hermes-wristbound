# Install Hermes Wristbound

Hermes Wristbound connects a Pebble watch and the Index ring to **your own Hermes Agent**. It is not a standalone watch chatbot and does not provide a hosted Hermes account. It has three parts; install the ones you want:

| You want | Install | Sections |
|----------|---------|----------|
| Hermes on the watch: sessions, replies, audio, approvals | Watch app + Android companion | 1 to 6 |
| To talk to Hermes from Index (Android or iOS) | Index plugin | 2, then [7](#7-talk-to-hermes-from-index-index-plugin) |
| Spoken answers to what you say to Index, played from the wrist | All three, plus the voice plugin on your Hermes host | 1 to 8 |

Every part signs in to the same Hermes dashboard, so start by making it reachable (section 2).

## What you need

- For the watch app: a Pebble paired with the current Pebble Android app supporting PebbleKit Android 2, and Android 10 or later with the Wristbound companion APK.
- For the Index plugin: the Index ring or another plugin-aware app, with a plugin-capable Pebble phone app on Android or iOS.
- For voice replies: shell access to your Hermes host to install the voice plugin, and a working Hermes TTS provider.
- Your own running Hermes dashboard. Version 0.22.2 was used for the real-device tests; other versions have not all been validated.
- A network path from the phone to that dashboard, preferably a private Tailscale connection with HTTPS.
- Your Hermes model/provider setup. Inference and TTS may use third-party services and incur costs under your configuration.

No iOS companion is currently provided; the Index plugin is built for the Pebble app on iOS too, though iOS is unverified. The watch bundle builds for all seven platforms, but features vary with hardware: no microphone means quick replies instead of dictation; watch-speaker playback is available only on supported speaker/API targets.

## 1. Install the Android companion

1. Open [GitHub Releases](https://github.com/AndreasThinks/hermes-wristbound/releases/latest) on your phone.
2. Download the matching `wristbound-companion-vX.Y.Z-debug.apk`. Check the release notes and optional `SHA256SUMS`.
3. Android may ask you to allow installation from the browser/file manager. Grant that only if you trust this project and APK; you can disable it again afterward.
4. Install and open Wristbound. Allow notifications if you want incoming-reply alerts.

The current APK is a **sideloaded debug build**, package `dev.wristbound.companion.debug`, not a Google Play release. Keep using the published release APKs for a consistent certificate. CI artifacts may have a different debug signing certificate. If Android refuses an update because signatures differ, do not casually uninstall: uninstalling clears local settings/cache and sign-in. Hermes server conversations are separate and are not deleted by uninstalling the companion.

## 2. Make your Hermes dashboard reachable securely

Configure authentication using the [official Hermes configuration and messaging documentation](https://hermes-agent.nousresearch.com/docs/). Use the **dashboard** endpoint, normally port 9119, not the separate API-server endpoint on 8642. Do not expose an unauthenticated dashboard or unrestricted agent tools to the public Internet.

If you already connect the Hermes desktop app or Hermes-Relay remotely, use the same authenticated dashboard URL. For a new private setup, install Tailscale on the Hermes host and Android phone, join the same tailnet, and configure your dashboard's authentication and allowed public URL/host using the Hermes documentation.

One possible transport arrangement is a dashboard bound to loopback behind Tailscale Serve:

```sh
hermes dashboard --host 127.0.0.1 --port 9119 --no-open
# In a separate terminal, after confirming the dashboard/auth configuration:
tailscale serve --bg --https=10443 http://127.0.0.1:9119
```

The corresponding phone URL is like `https://your-host.your-tailnet.ts.net:10443`. Replace the host with your own Tailscale name. These commands illustrate the transport, not a complete authentication configuration. Loopback binding alone does not make an unauthenticated dashboard safe to proxy remotely. Confirm authentication, Host/public-URL handling, and reachability from the phone before proceeding. Direct tailnet-only dashboard access is another option when configured with authentication and suitable firewall restrictions.

Wristbound supports password providers, browser sign-in, and an advanced dashboard session-token mode. Browser/password sign-in is preferable for a remote authenticated dashboard. Never paste secrets into the server URL.

## 3. Sign in on the phone

1. Enter the full dashboard URL, including `https://` and the correct port.
2. Choose username/password or **Sign in with browser**, as supported by your dashboard.
3. After success, the sign-in form is replaced by a signed-in card.
4. Press **Test connection** and require **Connected**, not merely saved credentials or a signed-in label. This also checks the gateway socket used to send replies.

Credentials are encrypted in Android Keystore-backed storage and bound to the issuing endpoint. Temporary network failure should show Offline rather than erase the session.

## 4. Install the watch app

Install Wristbound through its Pebble Store listing. Alternatively download `wristbound-vX.Y.Z.pbw` from the same GitHub release and open it with the Pebble Android app.

**Use protocol-compatible phone/watch versions.** The current watch app is v0.6.7, and every Android companion from v0.5.0 on speaks the same protocol (v5); use the newest APK from Releases, installed over the existing companion without uninstalling. Open in Wristbound opening the conversation directly needs watch v0.6.7. Version 0.4.0 uses protocol v4; versions 0.5.x and 0.6.x use protocol v5. Per-version changes are in the release notes. Updating only one side across a protocol change can result in an Update watch or phone app warning. Installing a watch update with the same UUID replaces the old version; there is no need to remove it first.

Open Wristbound on the watch. The home screen has New session, Continue (the last session), Recent audio and Sessions. A session opens as one scrolling conversation: Select replies by dictation where supported or a quick reply, and a reply shows there as Hermes writes it. Hold Select for playback, Newest and Refresh. Watch options are set in the companion app, under Watch.

## 5. Incoming replies and audio

The companion keeps one quiet connection open to the dashboard and checks only when Hermes says a session changed, so replies from Telegram, the desktop or Index reach the watch within seconds; a timed check every 15 minutes is the safety net. The status line says `live` while that connection is up. If it drops, **Reply speed** (*Fast* 30 s, *Normal* 1 min, *Battery saver* 5 min; 30 s while the watch app is open) sets how often the companion checks instead. This keeps working while the watch app is closed and the phone is locked; Android shows a silent foreground-service notification. *Only while Wristbound is open* turns background checking off. When battery optimisation could hold checks back, the status card at the top says so and offers **Allow background use**; it also shows when Hermes was last reached, and flags checks that are running late. The same service keeps a reply you sent from the watch, or audio playing on the phone, going after the watch app closes. Check notification permissions and any OS battery restrictions if alerts stop. The Pebble app skips apps it has muted, so check that Wristbound is enabled in its notification settings; if it is set not to send notifications while the phone is in use, alerts only reach the watch with the phone locked. **Send a test alert**, under the status card, posts one 10 s later, so you can lock the phone and see whether it reaches the watch.

Alerts are set per kind of reply under **Notifications**: audio replies from any session, replies to your watch messages, replies in desktop and terminal chats, text replies in Telegram and other messaging apps (off by default, since those apps notify the phone already), and background and scheduled tasks (off by default). Settings from earlier versions carry over. Which sessions the watch lists is set separately, under **Sessions on the watch**. First-run polling records a baseline rather than replaying all old replies as notifications; old retained audio can still be found in Recent audio.

**Alert style** (under *Customise* or *Advanced*) is *Pebble notification* by default. Choose **Wristbound banner** to open the watchapp over the watchface or another app for incoming reply banners. Startup waits up to 10 seconds for HELLO; startup or delivery failure falls back to the native notification. An untouched banner expires after 15 seconds and returns to the watchface; Select opens the reply and keeps Wristbound open. Silent late-audio updates do not relaunch it. This behavior is distinct from a native PebbleOS overlay, and still needs real-device Bluetooth/locked-phone acceptance.

Playback actions:

- **Play on headphones:** the phone's connected Bluetooth/wired/USB/hearing-aid output. Only shown when the phone reports a supported output. Disconnecting headphones pauses rather than switching out loud.
- **Play on phone speaker:** explicitly requests the phone speaker, including with headphones connected. This new routing behavior still needs broader hardware testing.
- **Play on watch speaker:** explicit streaming to a supported watch speaker. This experimental route uses low-bandwidth audio, is capped at two minutes and may buffer. Audible output/throughput has not yet been confirmed on the physical watch in this project's acceptance tests.

Only Hermes-generated audio is playable: a `text_to_speech` tool result, or a voice reply from the [wristbound-voice Hermes plugin](../hermes-plugin/README.md) for messages sent from Index with **Reply with voice** on. Text-only replies have no Play actions: Wristbound does not generate substitute read-aloud speech. A voice reply alerts once, with Play, as soon as its clip is ready (Wristbound waits up to 20 seconds for it, then alerts with text). Enable Wristbound notification forwarding in Pebble and review the new “Hermes replies · silent on phone” channel. Existing channel choices are not rewritten; watch forwarding/vibration with silent phone delivery requires device testing. If an audio attachment cannot be fetched, playback reports an error rather than substituting another voice.

## Approvals

For a turn started from Wristbound, Hermes may ask before running a command. On supported watches this appears as an approval card; **Deny is selected first**. Up/Down chooses *Allow once* or *Deny*, and Select answers; *Allow session* and *Always allow* are only offered on the desktop. Back dismisses the card without approving it; the conversation lets you reopen it. If the watch app is closed or the model cannot show the card, use the phone notification.

Only approve a command you can fully understand. A watch screen can truncate long details: use the phone or desktop when there is not enough context. The phone notification's answers need the phone unlocked (the Pebble's mirrored actions do not), and the lock screen hides the command. Request cancellation or turn completion closes pending prompts. Clarifications, password/sudo/secret prompts and other unsupported requests still need a capable desktop client. Approval handling for turns started elsewhere stays with that surface. Real backend approval round trips remain a hardware acceptance check, not a claim established by the unit tests.

## 6. Quick Launch

First assign Wristbound to a Quick Launch button using the supported Pebble watch/phone settings. Then, **in the Wristbound companion app**, choose under **Watch > Quick Launch opens on**:

- Home
- Dictate a new session
- Recent audio
- Play latest audio
- Last session

The watch is told at once if the watchapp is open, otherwise the next time it opens. The companion shows whether the watch has confirmed the choice (*Applied on the watch*) or will pick it up the next time Wristbound opens there. A choice made on the watch in 0.4.0 is kept until you pick one in the companion app.

It only affects actual hardware Quick Launch starts, not normal launches. **Play latest audio deliberately plays through headphones when connected, otherwise the phone speaker.** Pick Home or Recent audio for a silent shortcut. Ordinary incoming replies do not autoplay.

## 7. Talk to Hermes from Index (Index plugin)

The Index plugin runs inside the Pebble phone app, on Android or iOS, and makes Hermes an agent destination for the Index ring and any plugin-aware app. It does not need the watch app or the companion.

1. Install [Hermes for Index](https://apps.repebble.com/77caeb14dc3f4d3c8367ab82) in the Pebble phone app.
2. Open **Apps → Plugins → Hermes → Settings**, enter the same dashboard address as in section 2, and sign in.
3. Choose a **Destination**: the most recently active conversation, one Index conversation, a new session each time, or a named chat.
4. Say "Tell Hermes …" to Index. The receipt says *Sent*; the answer arrives in Hermes, and on the watch if the companion is set up.

Password sign-in needs a dashboard that supports the native JSON sign-in flow; see the [plugin README](../pebble-plugin/README.md) for destinations, starting a fresh session by voice, and compatibility notes.

## 8. Voice replies (voice plugin)

With the voice plugin on your Hermes host and **Reply with voice** ticked in the Index plugin, Hermes answers each message you dictate to Index with a spoken reply, using your own Hermes text-to-speech, and the watch buzzes once with **Play**.

1. On the machine that runs Hermes:

   ```sh
   hermes plugins install AndreasThinks/hermes-wristbound/hermes-plugin/wristbound-voice --enable
   ```

2. Restart the Hermes dashboard and gateway so both load it. Hermes needs a working TTS provider (`/voice tts` on Telegram is a quick check).
3. In the Index plugin's settings, turn on **Reply with voice**.
4. In the Wristbound companion, keep **Voice** alerts on.

Without the voice plugin, Index still sends your message, and the receipt says *No voice reply*. Details are in the [voice plugin README](../hermes-plugin/README.md).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Signed in, but not Connected | URL/port, Tailscale, dashboard authentication, allowed Host/public URL, gateway socket and protocol compatibility. |
| Watch stays Connecting | Matching APK/PBW versions, Pebble Bluetooth link, companion installed and signed in; use Test connection on the phone. |
| No incoming alert | Monitoring state, background switch when closed, permissions, text/audio policy, and a new reply after the initial baseline. |
| Audio marker but no sound | Open the reply's action menu and explicitly choose an output. Audio markers alone do not start playback. |
| Quick launch seems absent | It is in the companion app under Watch > Quick Launch opens on, not on the watch. |
| Index says *No voice reply* | The voice plugin is not installed or not enabled on the Hermes host, or the dashboard was not restarted after installing it. |
| APK will not update | Read the signing-certificate warning above; preserve data/sign-in before any uninstall. |

Report bugs at [GitHub Issues](https://github.com/AndreasThinks/hermes-wristbound/issues), including watch model/firmware, Android and app versions, exact action, and the visible error. Do not post passwords, tokens, cookies or private conversation content in public issues.

## Privacy and support limits

Wristbound connects the phone to the user's configured Hermes dashboard. No maintainer-operated message relay is provided. The phone stores encrypted credentials plus bounded conversation/audio caches; the watch receives the selected text and streamed audio over Bluetooth. Hermes, its configured inference/TTS providers, and the watch/phone dictation provider may process data according to their own policies. Do not treat this as an offline-only or zero-third-party-processing product.

Real-device checks so far used a Pixel 9a and Pebble Time 2. Session browsing and phone/headphone playback were user-confirmed. Other hardware, watch-speaker output and Quick Launch interaction need further testing. [Testing](TESTING.md) and [release notes](https://github.com/AndreasThinks/hermes-wristbound/releases) distinguish verified behavior from outstanding checks.
