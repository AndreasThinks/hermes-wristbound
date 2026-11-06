# Wristbound Android v0.6.9 / watch v0.6.7: reply from the notification

Android companion v0.6.9 (versionCode 15) and watchapp v0.6.7. Protocol v5 and the watch UUID are unchanged. The new REPLY flag is ignored by older watch builds.

## Changes

- **Reply action.** Reply notifications mirrored to the Pebble now lead with *Reply*: dictation, or a canned answer (Yes, No, Go ahead, Thanks). The text goes to that session as the next message, and Hermes' answer arrives as a new notification. The action is left out for messaging sessions while replies to them are off. If Hermes refuses the message (still working, not set up, messaging session), a phone notification says so.
- **Action order.** Reply, Open in Wristbound, Play audio, Play on phone speaker.
- **Open in Wristbound opens the conversation.** Before, the watch showed the reply as a fresh alert: it vibrated again, showed the 15 s banner, and closed if Select wasn't pressed. With watch v0.6.7 it opens the conversation at that reply, quietly, and stays open. With an older watch build the banner behaviour remains.
- **Open in Wristbound is faster and more reliable.** The phone sends the reply as soon as the watchapp says hello, before the session list is fetched from Hermes. The notification carries the reply text, so it can still be shown if the companion was restarted and Hermes can't be reached. A request whose launch failed expires after 60 s instead of appearing on the next manual open.

## Install

Install the companion APK over the existing one without uninstalling, and update the watchapp through the Pebble Store listing or `wristbound-v0.6.7.pbw`. Update both to get the direct open; the Reply action needs only the APK.

## Verification and limits

- Android JVM tests pass, including the action order, the Reply text-input flag and its omission where Hermes would refuse it. All seven watch targets build in CI.
- Not yet tried on hardware. Still to check on a watch: that the Core app shows the RemoteInput as Reply with dictation and canned answers, and that Open in Wristbound opens the conversation from both a cold launch and an already-open watchapp.
