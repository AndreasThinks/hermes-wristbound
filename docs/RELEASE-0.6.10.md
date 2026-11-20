# Wristbound Android v0.6.10: live updates, much less battery

Android companion v0.6.10 (versionCode 16). The watchapp is unchanged (v0.6.7), and so are protocol v5 and the watch UUID.

## Changes

- **Replies arrive live.** The companion keeps one quiet connection open to the Hermes dashboard and listens for its `sessions.changed` broadcast. That's the same signal the desktop app uses, and it covers Telegram, cron and desktop sessions. When something changes, the phone checks once, so a reply from another surface reaches the wrist within seconds instead of within a minute.
- **Much less battery.** Before, background checking woke the phone and radio every 60 s all day, which came to about 1,440 wake-ups a day. While the live connection is up, the timed checks now run only every 15 minutes as a safety net. Each of those checks also confirms the connection still answers and reopens it if it doesn't.
- **A fallback you can see.** If the connection drops, checks go straight back to your chosen Reply speed, at 30 s while the watchapp is open. The setup screen's status line says `live` or `checking every ~1 min`, so you can tell which mode is in use. The Reply speed page explains that the setting now applies only while the connection is down.

No Hermes change is needed. Behind `tailscale serve` the dashboard sends no WebSocket pings. On a non-loopback bind, raising `dashboard.ws_ping_interval` from its 20 s default saves a little more battery.

## Install

Install the APK over the existing one without uninstalling. The watchapp needs no update.

## Verification and limits

- 175 Android JVM tests pass, including new `ChangeListener` tests: bursts collapse into one check, a lost socket reconnects, an alarm reopens a socket that stopped answering, and a rejected sign-in stops retries until a new sign-in. CI is green.
- Not yet tried on hardware or against a live Hermes. Still to check: that the status line reads `live`, that a Telegram reply alerts within seconds with the screen off, and battery use over a day.
