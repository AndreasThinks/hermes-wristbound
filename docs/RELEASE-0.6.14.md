# Hermes Wristbound v0.6.14: choose the phrase that starts a new session

[Wristbound website and setup guide](https://andreasthinks.github.io/hermes-wristbound/)

Index plugin 0.1.9. The Android companion is rebuilt as 0.6.14 (versionCode 20) with no changes, because the release tag follows its version. The watch app (0.6.8) and the voice plugin (0.2.0) are unchanged, and so are protocol v5 and the watch UUID.

## Index plugin 0.1.9

- **The phrase that starts a new session is now a setting.** In the plugin's Settings, under Destination, **Phrase that starts a new session** sets what you say to Index to start a fresh Hermes session. It defaults to “New Hermes session”, so nothing changes unless you edit it, and clearing the box restores the default. Case does not matter, any punctuation Index puts between the words is accepted, and a following “then” is still optional: with “Fresh start”, saying “Fresh start. Then plan the week” sends “plan the week” to a new session. A custom phrase replaces the default rather than adding to it, and it resets when you sign in to a different dashboard address.

## Install

- Update Hermes for Index from the Pebble Store, then pick a phrase in its Settings if you want one.
- The companion APK, watch app and voice plugin need no update.

## Verification and limits

- Index plugin: 36 tests pass, including the custom phrase, literal matching of punctuation in it, and clearing back to the default. The settings page was checked in a browser against the plugin's own settings handler.
- Not yet tried on a physical Index.
