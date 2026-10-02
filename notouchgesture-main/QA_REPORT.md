# PalmLink QA report

Date: 2026-10-02

## Verification performed in the working source tree

### Passed

- Pure Kotlin gesture state-machine smoke test.
- Low-confidence input does not trigger actions.
- Palm → fist capture sequence requires stable dwell.
- Fist → palm send sequence requires stable dwell.
- Receive mode requires a stable open palm.
- Background capture/send sequences are covered by smoke tests.
- Pending-receive mode takes priority in background mode.
- Gesture trigger requires neutral release before another trigger.
- Transfer protocol offer encodes/decodes correctly.
- Invalid transfer protocol messages are rejected.
- Offer byte counts must be positive before a file offer is accepted.
- Source-level review covers Activity, CameraX/MediaPipe controller, gesture state machine, MediaProjection service, Nearby manager/runtime, transfer protocol, persistence, UI, manifest, Gradle configuration, and CI workflow.

Smoke-test command:

```text
kotlinc app/src/main/java/com/samin/notouchgesture/gesture/GestureStateMachine.kt app/src/main/java/com/samin/notouchgesture/nearby/TransferProtocol.kt app/src/test/java/com/samin/notouchgesture/LogicSmokeTest.kt -include-runtime -d logic-smoke.jar
java -jar logic-smoke.jar
```

Result:

```text
LOGIC_SMOKE_TEST: PASS
```

## Fixes in this revision

### Camera and MediaPipe

- Replaced unsafe asynchronous LIVE_STREAM usage with synchronous IMAGE inference on the CameraX analyzer executor.
- Fixed bitmap lifetime so images are not recycled before inference completes.
- Applied `rotationDegrees` before inference.
- Kept only the latest CameraX analysis frame to reduce lag and stale-frame buildup.
- Added a process-wide CameraX handoff so the foreground Activity and background service cannot unbind each other's sessions.
- Deferred native recognizer close until the analysis executor has finished the current inference call.
- Lowered overly strict hand-quality and gesture confidence gates while retaining stable dwell and neutral-release protection.
- Added a landmark-based fallback for Open Palm / Closed Fist when the canned classifier returns no useful target category.
- Surface camera/engine startup errors instead of silently converting them to a permanent zero-confidence state.

### MediaProjection/background mode

- Keeps one user-approved MediaProjection session alive for the whole background session.
- Uses an explicit `mediaProjection|camera` foreground-service declaration and matching permissions.
- Starts the foreground service from the visible Activity after the user's screen-capture consent.
- Does not mark background mode running until CameraX has actually bound.
- Catches service-start and service-initialization failures and reports them to the Activity instead of allowing an immediate uncaught app crash.
- Moves ImageReader and PNG work off the main thread.
- Releases ImageReader, VirtualDisplay, MediaProjection callback, camera controller, and worker thread during shutdown.

### Nearby Connections

- Advertising and discovery are idempotent through the shared process-local manager.
- Role switches stop the previous operation, stale callbacks are ignored, and transient cross-role start races are retried.
- Handles 8001 and 8002 as already-running states instead of treating them as fatal failures.
- Prevents duplicate discovery connection requests.
- Prevents duplicate connection confirmations and duplicate offer/file sends.
- Validates non-empty files and positive offer sizes.
- Cleans pending state when connections disappear or transfers fail.
- Stops stale Nearby advertising/discovery when the Activity is destroyed and no background service is running.

### Android permissions/build

- Restored the complete Nearby permission matrix for older Android versions.
- Added the legacy coarse/fine location declarations needed for the app's minSdk range.
- Removed the unnecessary connected-device foreground-service type from the background camera/screen-capture service.
- Updated the GitHub Actions SDK installation to the exact API 37.0 platform package used by the project.
- Added a final size check to the MediaPipe model download step.

## Hardware validation boundary

A physical Samsung device is not available in the working container, so no claim is made here that a new APK was exercised on hardware. The provided source is designed to build in the existing Codespace with Java 17 and the Android SDK already installed there. Physical-device validation remains the final check for OEM camera and foreground-service behavior.
