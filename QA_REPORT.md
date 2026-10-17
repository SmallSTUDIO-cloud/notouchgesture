# Aerviqo QA report

## Source-level scope audit

The current project was based on the uploaded PalmLink visual baseline. The working architecture was preserved rather than replaced.

Restored from the archived 0.4.0 implementation:
- automatic Gallery save;
- shutter sound and persistent shutter setting;
- screenshot shutter/edge visual feedback;
- send/receive preview animation;
- faster temporal gesture tracking and confidence-dropout tolerance;
- Nearby preview protocol support.

Added as isolated layers:
- Aerviqo settings/manual UI;
- supplied Aerviqo logo asset;
- optional touchless action dispatcher;
- optional AccessibilityService bridge;
- shake flashlight controller;
- foreground-only experimental mouth-open Air Mic detector.

## Build & Automated Test Verification Completed

- **Unit Test Suite**: 30/30 unit tests passed via `./gradlew testDebugUnitTest` with 0 failures, 0 errors, 0 skipped.
  - Core sender workflow (Open Palm → Closed Fist) verified.
  - Pinch-vs-fist priority defect resolved: pinch suppresses fist, disarms pending capture, and activates lockout preventing false captures.
  - Extended gesture mechanics verified (brightness bounds/direction, volume direction, hand motion data structures).
  - Protocol wire serialization, pairing crypto, and control message handling verified.
- **APK Compilation & Assembly**: Successfully compiled and packaged `./gradlew assembleDebug` in 2m 28s generating `app/build/outputs/apk/debug/app-debug.apk` (114 MB).
- **Background Execution & Battery Optimization**:
  - Implemented adaptive inference frame throttling: ~135ms interval during idle searching, ~45ms during active hand presence.
  - Foreground-only gating for experimental Air Mic and face detector.
  - Overlay permission verification before creating system window overlays (`TYPE_APPLICATION_OVERLAY`).
  - AccessibilityService check before invoking global navigation actions (`GLOBAL_ACTION_BACK`, `GLOBAL_ACTION_HOME`, `GLOBAL_ACTION_RECENTS`, `GLOBAL_ACTION_NOTIFICATIONS`).

## Physical-device verification required

The generated APK (`app-debug.apk`) is ready for physical device deployment and real-device testing:
- OEM-specific camera behavior and front-camera mirroring under low-light conditions.
- MediaProjection token persistence across device sleep/wake cycles.
- Nearby Connections phone-to-phone pairing and Wi-Fi Direct / BLE negotiation in noisy RF environments.
- AccessibilityService gesture dispatch on Android 14/15 OEM skins.
