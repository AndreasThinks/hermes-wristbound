# Wristbound v0.6.5: reply checks that keep running

Android-only release. The watchapp stays at v0.6.4 and protocol v5 is unchanged.

## Changes
- PR #26: the 30 s checks while the watchapp is open now use the same wakeup alarms as background checks. The old timer stopped while the phone slept: the Pebble app's binding keeps the companion alive but not the CPU awake.
- A check that fails or times out still schedules the next one. Before, one unexpected error could stop checking until the app was opened again.
- *Send a test alert* under Notifications posts a test alert 10 s later, so you can lock the phone and see whether the Pebble app forwards Wristbound notifications.

## Install
Install `wristbound-companion-v0.6.5-debug.apk` over the existing companion, without uninstalling. It must be signed with the same certificate as earlier releases. No watch update is needed.

If alerts still go missing, press *Send a test alert* and lock the phone. If the test does not reach the watch, check that Wristbound is enabled in the Pebble app's notification settings, and whether that app is set to skip notifications while the phone is in use.

## Verification and limits
`testDebugUnitTest assembleDebug` passes. Not yet tested on hardware: watch-open checks with the phone locked, forced Doze and the test alert all remain acceptance items.
