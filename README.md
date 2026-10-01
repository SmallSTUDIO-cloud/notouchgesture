# No Touch Gesture

Touchless Android prototype for gesture-driven screenshot triggering and nearby screenshot transfer.

## What this build actually does

- Uses the front camera + MediaPipe Gesture Recognizer to detect `Open_Palm` and `Closed_Fist`.
- Uses a debounced state machine instead of raw gesture labels, reducing false triggers.
- Capture sequence: **Open palm → closed fist**.
- Send sequence: **Closed fist → open palm**.
- Receive sequence: **Open palm** after a pending transfer offer.
- Uses Android MediaProjection for screen capture and Android Nearby Connections for local peer-to-peer transfer.
- Includes Auto / Light / Dark appearance selection in the top app bar.
- Saves the latest capture privately and can export it to the gallery or share it with another Android app.

## Important Android constraint

Modern Android requires explicit user consent for MediaProjection screen capture. A normal camera activity also cannot silently capture another app's screen. This project therefore does **not** pretend to implement a hidden global gesture overlay. The Gesture Lab is fully testable inside the app, while screen capture uses the official Android consent flow.

A global, no-touch capture experience needs an OS-level integration that is appropriate for the product's distribution model. Do not ship an AccessibilityService merely as a workaround for screenshot access.

## Run

### Android Studio

Open this folder as a Gradle project. The build downloads the MediaPipe gesture model into `app/src/main/assets/` if it is not already present.

### GitHub Actions

This repository includes `.github/workflows/android-build.yml`, which installs Gradle 9.5 on the runner and produces `app-debug.apk` as a build artifact. This is useful when working from a mobile-only workflow.

## Manual test flow

1. Install the debug APK on two Android devices.
2. On device A, open **Gesture Lab** and grant camera permission.
3. Verify the status moves from `Searching` to `Ready` while one hand is centered in frame.
4. Test **Open palm → fist**. The app should raise the Android screen-capture consent dialog.
5. Grant consent. A capture should be saved and shown in the app.
6. On device A/B, open **Nearby**. One device advertises while the other discovers.
7. Establish the connection while both devices are in the transfer screen. The auth digits are shown for comparison.
8. On the sender, ensure a capture exists and perform **Fist → Open palm** to create a transfer offer.
9. On the receiver, perform **Open palm** to accept the pending offer.
10. Confirm the received image appears in the receiver's capture history and can be exported.

## Known scope limits

- This is an Android-first prototype, not a polished production release.
- No cloud backend is used.
- The app intentionally avoids background camera capture and AccessibilityService-based screen scraping.
- The first production release should add stronger device pairing/trust, transfer resume, lifecycle recovery, instrumentation tests on physical devices, and a dedicated global-capture architecture backed by platform permissions that are explicitly justified to users.
