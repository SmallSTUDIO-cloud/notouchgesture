# PalmLink

Touchless Android app for gesture-driven screen capture and nearby screenshot transfer.

## What this build does

- Uses the front camera + MediaPipe Gesture Recognizer to detect `Open_Palm` and `Closed_Fist`.
- Uses a debounced state machine for deliberate gesture sequences.
- Capture sequence: **Open palm → closed fist**.
- Send sequence: **Closed fist → open palm** when an authenticated Nearby connection exists.
- Receive sequence: **Open palm** when a transfer offer is pending.
- Runs gesture recognition from a visible Android foreground service so the user can leave PalmLink and use another app.
- Keeps one user-approved MediaProjection session alive while background mode is enabled, so a gesture does not launch a new screen-recording consent dialog.
- Uses Android MediaProjection for screen capture and Nearby Connections for local peer-to-peer transfer.
- Automatically saves captured and received screenshots to `Pictures/PalmLink` on supported Android versions.
- Shows PalmLink-blue shutter, edge-glow and transfer animations with the actual screenshot preview.
- Provides an in-app shutter-sound ON/OFF setting.
- Keeps the latest capture available for sharing and a manual Gallery re-save.

## Visual feedback and Gallery saving

PalmLink 0.4.0 shows a brief full-screen shutter flash followed by a thin blue edge glow after capture. During transfer, the PalmLink logo and a small screenshot preview orbit on screen. Receipt finishes with a blue outlined preview window and Gallery confirmation. These visuals are non-interactive so they do not consume touches.

The visual overlay uses Android's system overlay facility. On devices where Android does not already grant overlay access for the active screen-capture session, enable **Settings → Allow visual effects** inside PalmLink. Android requires explicit user authorization for `SYSTEM_ALERT_WINDOW`; PalmLink never grants this permission itself.

Captured and received PNGs are automatically exported to `Pictures/PalmLink` using MediaStore on Android 10+ and are kept duplicate-safe. Older Android versions use the appropriate legacy storage permission.

## Important Android constraint

Android does not allow an ordinary app to silently grant itself MediaProjection permission or click the system's screen-capture consent dialog. The user must approve screen capture when **PalmLink background mode is enabled**. After that approval, PalmLink keeps the authorized projection session alive in its user-visible foreground service and captures the next screen frame when the gesture fires. If the service is stopped or Android revokes the projection, the user must approve a new session.

Android also restricts starting camera foreground services from the background. PalmLink therefore starts its camera foreground service only from a visible user action while the app has camera permission, then the service may continue using the camera while the user switches to another app.

## Gesture performance changes

- Lowered MediaPipe hand detection/presence/tracking thresholds to 0.35.
- Limited recognition to the two gestures PalmLink actually uses.
- The state machine accepts 0.50+ confidence; the MediaPipe canned classifier is not blocked by an aggressive score threshold, and a landmark fallback covers temporary classifier gaps.
- Reduced camera analysis resolution to 640×480 and kept only the newest frame.
- Relaxed the old hand-size/framing gate so a reasonably sized hand farther from the camera is still usable.
- Gesture sequence timing is tuned for fast natural motions with short stable dwell and rearm windows.

## Nearby background behavior

The foreground service and Activity share one process-local Nearby manager. The Nearby screen explicitly chooses the advertiser or discoverer role, role switches stop the previous operation first, and duplicate start callbacks are ignored. Background mode does not start both roles automatically, so it cannot recreate the old 8001/8002 duplicate-operation loop. Existing authenticated connections remain available after the Activity is closed. Connection authentication still requires both devices to accept the connection.

## Build

### Android Studio

Open the project as a Gradle project. The build downloads the official MediaPipe gesture model into `app/src/main/assets/gesture_recognizer.task` when it is missing.

### GitHub Actions

The workflow installs the Android SDK packages explicitly, installs Gradle 9.5.0, builds the debug APK, and uploads `PalmLink-debug-apk`.

## Manual device test

1. Install the debug APK.
2. Grant camera permission.
3. Open Gesture and tap **Enable PalmLink**.
4. Approve Android's screen-capture dialog once.
5. Wait for the persistent PalmLink notification.
6. Leave PalmLink and open another app.
7. Show an open palm, hold briefly, then make a fist. The current screen should be captured without another screen-capture consent dialog.
8. If Nearby permissions are granted and a previously authenticated connection exists, closed fist → open palm sends the latest capture.
9. On a device with a pending offer, open palm accepts it.
10. Use the notification's **Stop** action to disable background mode.

## Known platform limits

- Android's MediaProjection consent cannot be bypassed programmatically.
- Camera access in the background requires a user-visible foreground service and the appropriate camera permission.
- Some OEMs may aggressively stop foreground services or camera use. Battery-optimization settings may affect long sessions.
- Physical-device validation is still required for Samsung/Oppo/Xiaomi and multiple Android versions.
