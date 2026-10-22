# Claude Code Cloud handoff

## Assignment

Finish Hermes Wristbound from branch `feat/initial-client`. Read this document, `CLAUDE.md`, the README and the original design documents before editing. The user is moving implementation to Claude Code Cloud; do not start or assume a local worker. Use Opus as requested by the user, with no silent model substitution or paid fallback.

## Product

A Pebble watch app and Android companion for browsing and continuing Hermes sessions, including optional Telegram-origin sessions, over private Tailscale connectivity. Receive replies and control playback of the selected assistant audio reply from the watch without opening Telegram. Keep the existing Ringbearer ingress working. Hardware-dependent watch-speaker playback is optional; phone/headphone playback is the initial requirement.

## Actual checkpoint

- Kotlin Android companion source includes session/history client, submission and sync logic, settings/status activity, notifications, Pebble bridge and playback implementation.
- Pebble C source is partial: communication, main entrypoint, session and message lists. Inspect unresolved declarations and finish detail/actions, input and settings flows as needed.
- Android debug APK packaging was independently exercised successfully by the supervising agent using `source scripts/env.sh` followed by `(cd android && ./gradlew :app:assembleDebug --console=plain)` on the original Linux workstation. The first offline build lacked cached runtime dependencies; allowing dependency downloads completed successfully.
- Successful packaging is NOT functional acceptance. No unit-test pass, Pebble build, emulator preview, physical-device test, locked-phone test or real Hermes end-to-end acceptance has been verified.
- No application changes have been deployed and no device installation has occurred.
- There is no completed audio-notification review. The review run stopped at quota with only a draft heading. Do not treat its intended analysis as evidence.
- An `adapter/` directory was created locally but contains no tracked implementation. References in source comments to an optional media adapter or `docs/API-CONTRACT.md` are not evidence those files or capabilities exist.

## Priority requirement: Telegram text versus audio

These are independent controls, fully detailed in `CLAUDE.md`:

1. Show Telegram-origin sessions in history/lists.
2. Notify for Telegram text replies.
3. Notify for assistant audio replies even when Telegram text alerts are off.
4. Allow sending replies from Wristbound to Telegram-origin sessions; otherwise show them read-only.

An important supported configuration is visible Telegram history, no Telegram text alerts, assistant audio alerts enabled, and optional read-only Telegram sessions. Existing Telegram watch notifications can handle text; Wristbound supplies the audio Play action. Never enable autoplay as a side effect. Respect explicit global/session mutes and privacy rules. Do not confuse incoming user recordings with assistant audio. Audio is not proof of Ringbearer provenance.

Inspect the best policy and media delivery approach before implementing. Handle delayed audio, stable IDs, reconnect deduplication and text-then-audio notifications. Test the preference matrix and persistence. Do not modify Telegram notification settings or suppress Hermes execution remotely.

## API discovery leads, not independently accepted contracts

The initial worker reported inspecting Hermes API-server source and using:

- `GET /api/sessions`, with origin in `source`.
- `GET /api/sessions/{id}/messages?order=latest&limit=N`.
- `POST /api/sessions` for client-selected session IDs.
- `POST /v1/runs` with `input`, `session_id` and `Idempotency-Key`.
- `GET /v1/runs/{id}` and resumable SSE at `GET /v1/runs/{id}/events`.
- Bearer authentication using the supported API-server credential.

Verify all contracts against the actual target Hermes revision. The worker reported no API-server audio retrieval endpoint, no live API event feed for gateway-origin turns, and automatic Telegram TTS files being deleted after send. It also reported that submitting into a Telegram-origin session through the API persists the reply but does not deliver it to Telegram. These are material integration risks to investigate, not guarantees. Check concurrent gateway/API session ownership and queuing safety rather than assuming a shared ID makes concurrent writes safe.

If required support is missing, propose/implement the smallest isolated adapter or optional integration without changing live Hermes or Ringbearer. Do not fabricate API responses and call them real acceptance. Phone-side text-to-speech is a fallback already present in the code, not a substitute for verified delivery of Hermes-generated audio.

## Portable build setup

- Android build: `bash scripts/bootstrap-toolchain.sh`, then `source scripts/env.sh`, then `cd android && ./gradlew :app:assembleDebug --console=plain`.
- Inspect the scripts before use. They download a Linux x64 JDK and Android command-line tools into ignored `.toolchain/`, and accept Android SDK licences. The current bootstrap and wrapper should be audited for reproducibility, version availability and download integrity.
- SDK/JDK/Gradle caches and APKs are deliberately not committed. Cloud must recreate build dependencies.
- Pebble SDK/tooling must be installed in Cloud using current official instructions. The original machine had pebble-tool 5.0.37 and SDK 4.9.169; those installations are not part of this repository. `env -u PYTHONPATH pebble build` avoided unrelated Python-path pollution locally. Verify tool/version availability rather than assuming it.
- Use `uv` and an isolated local virtual environment for any Python dependencies; do not share deployed environments.
- Public references: https://hermes-agent.nousresearch.com/docs/ ; https://github.com/NousResearch/hermes-agent ; https://developer.repebble.com/ ; https://github.com/coredevices/pebble-watchface-agent-skill ; https://github.com/pebble-dev/PebbleKitAndroid2 ; https://developer.android.com/media/media3/session/background-playback .

## Next bounded steps

1. Inventory the partial implementation and validate API/source contracts. Document actual media and concurrency gaps.
2. Complete and build the Pebble app and exercise the Android/watch protocol. Add meaningful unit tests for policy, sync, submission uncertainty/idempotency and exact-message media selection.
3. Implement independent Telegram text/audio notification policy and test delayed media and reconnection.
4. Finish actual Hermes-audio delivery or report the exact remaining integration dependency. Clearly label any phone TTS fallback.
5. Run clean Android/Pebble builds and tests, update status/setup/API documentation, and provide artifact paths and truthful acceptance boundaries.

Do not get lost expanding libraries while the watch-facing flow is unfinished. The first successful product slice is one existing session, one harmless request, one actual answer, one selected audio item and watch-controlled Play/Pause.

## Publication and operational boundary

Continue on a feature branch and prepare a reviewable PR for the owner. No merge, deployment, physical-device installation, production writes, live user-message probes, service restarts, credential access, firewall/Tailscale changes or Ringbearer route changes without further explicit permission. Do not select a software license on the owner's behalf. No licence is currently granted just because the repository is public.

Cloud cannot claim device or private-server acceptance without actual access. Document those as manual follow-ups with exact steps. Never commit private chat histories, tokens, recordings, API credentials, personal host addresses, build caches, SDK files or Waf environment snapshots.
