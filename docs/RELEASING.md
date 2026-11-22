# Releasing Hermes Wristbound

Publishing a GitHub release runs [`.github/workflows/release.yml`](../.github/workflows/release.yml). It builds every part from the release tag and publishes what it can.

| Part | What the workflow does |
|------|------------------------|
| Watch app | Builds `wristbound-watch-vX.Y.Z.pbw`, attaches it, and publishes it to the Pebble Store if its version changed since the previous release |
| Index plugin | Runs its tests, builds `hermes-index-plugin-vX.Y.Z.pbw`, attaches it, and publishes it to the Pebble Store if its version changed |
| Android companion | Runs its tests, builds `wristbound-companion-vX.Y.Z-debug.apk` signed with the release key, checks the certificate, and attaches it |
| Voice plugin | Runs its tests and attaches `wristbound-voice-vX.Y.Z.zip`. Hosts install it from the repository path, so the release tag is the publication |
| Checksums | Attaches `SHA256SUMS` for every file above |
| Website | Redeploys GitHub Pages from `main` |

Files attached to the release by hand are replaced by the CI builds of the same name.

## Cutting a release

1. Bump the versions that changed: `versionName` and `versionCode` in `android/app/build.gradle.kts`, `version` in `watch/package.json`, `pebble-plugin/package.json` and `hermes-plugin/wristbound-voice/plugin.yaml`. Update the README's version line and add `docs/RELEASE-X.Y.Z.md` if you keep notes there.
2. Merge to `main`.
3. Create a GitHub release with tag `vX.Y.Z`, where `X.Y.Z` is the Android `versionName`. The workflow stops if the two differ.
4. Watch the **Release** run. If the `pebble-store` environment has required reviewers, approve the Store jobs there.

A prerelease gets the attached builds but is not sent to the Pebble Store. To rebuild an existing release, run **Release** from the Actions tab with its tag; untick *publish_store* to skip the Store.

## One-time setup

### Secrets and variables (Settings, then Secrets and variables, then Actions)

| Name | Kind | Required | What it is |
|------|------|----------|------------|
| `ANDROID_DEBUG_KEYSTORE_B64` | Secret | Yes | The debug keystore that signed the published APKs, base64-encoded. Without it the Android job fails rather than attach an APK that cannot update the installed one. Create it with `base64 -w0 ~/.android/debug.keystore` (on macOS, `base64 -i ~/.android/debug.keystore`) on the machine that built v0.6.11 and v0.6.12. The workflow uses the standard debug password and alias. |
| `ANDROID_CERT_SHA256` | Variable | Recommended | The expected signing certificate SHA-256, for example from `apksigner verify --print-certs` on the v0.6.12 APK. The run fails if the new APK's certificate differs. The run log prints the certificate either way. |
| `PEBBLE_FIREBASE_REFRESH_TOKEN` | Secret | Only if OIDC is refused | `pebble publish` first signs in with GitHub's OIDC token. If the Store does not accept that for your account, run `pebble login` locally and copy `refresh_token` from `firebase_oauth_storage.json` in the Pebble SDK folder (`~/Library/Application Support/Pebble SDK` on macOS, `~/.local/share/pebble-sdk` or `~/.pebble-sdk` on Linux). If it stops working, sign in again and replace it. |

### Environment

The Store jobs run in a `pebble-store` environment, which GitHub creates on first use. Add yourself as a required reviewer there (Settings, then Environments) if each Store release should wait for your approval.

### GitHub Pages

The `github-pages` environment keeps its default rule of deploying only from `main`; the website job redeploys from `main`, so no change is needed.

## What stays manual

- **Release notes and the tag.** You write the release and choose when to publish it.
- **Store listing assets.** Screenshots, description and icons on the Pebble Store are not changed by a release. Update them in the developer dashboard, or run `pebble publish --screenshots … --replace-screenshots` yourself.
- **Google Play.** The companion is a sideloaded debug APK with no Play listing. Play would need a release signing key, a Play Console app and a service-account key, and a switch from the debug package name.
- **Hardware checks.** The workflow runs unit tests only; the device checks in [Testing](TESTING.md) are still done by hand.
