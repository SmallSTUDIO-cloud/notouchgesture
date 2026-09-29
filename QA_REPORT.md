# QA report

Date: 2026-09-28

## Verification actually performed in this environment

### Passed

- Pure Kotlin gesture state machine smoke test.
- Low-confidence input does not trigger capture.
- Palm → fist sequence triggers only after stable dwell.
- Triggered gesture cannot immediately retrigger without a neutral release.
- Transfer protocol offer message encodes/decodes correctly.
- Invalid transfer protocol message is rejected.

Smoke-test command:

```text
kotlinc GestureStateMachine.kt TransferProtocol.kt LogicSmokeTest.kt -include-runtime -d logic-smoke.jar
java -jar logic-smoke.jar
```

Result:

```text
LOGIC_SMOKE_TEST: PASS
```

### Not verified here

The container does not have an Android SDK, Android device/emulator, Gradle executable, or adb. External downloads are unavailable from this environment.

Therefore these items were **not** honestly marked as passed:

- Android Gradle compilation.
- MediaPipe model loading on a real Android runtime.
- CameraX live inference on hardware.
- Android MediaProjection permission flow.
- Actual Nearby Connections transfer between two physical devices.
- Performance, thermal behavior, battery use, or OEM-specific camera behavior.

## Bugs found and fixed during review

### 1. Initial cooldown overflow

`lastActionAt` was initialized with `Long.MIN_VALUE`, then subtracted directly. That overflowed and blocked the first gesture. Fixed with an explicit sentinel check.

### 2. Neutral-release rearm latency

The first implementation required an extra neutral frame before rearming. Fixed by anchoring the neutral window to the candidate's stable start instead of the next callback.

### 3. False-positive controls

Added one-hand gating, confidence thresholding, minimum hand-size quality, center-of-frame quality, gesture dwell time, ordered sequences, and neutral release.

### 4. Android capture claims

Removed any implication that the app can secretly screenshot an arbitrary foreground app. The UI and README now state the actual MediaProjection boundary.

## Remaining engineering risks

- Camera frame conversion through `ImageProxy.toBitmap()` is straightforward but should be profiled on low-memory phones.
- Nearby Connections API behavior and permission requirements should be rechecked against the exact Android versions targeted for release.
- The MVP accepts both sides of a Nearby connection automatically after user-arming the Nearby screen. Production pairing should require a stronger explicit trust confirmation and remember verified devices.
- The receive path should be expanded into a persistent inbox and tested under transfer interruption.
