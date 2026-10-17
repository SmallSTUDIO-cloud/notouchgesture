# Aerviqo

Touchless Android app for gesture-driven screen capture and nearby screenshot transfer, with optional air controls.

## Preserved core behavior

- Front-camera MediaPipe hand recognition.
- Core capture gesture: **Open Palm → Closed Fist**.
- Core receive gesture: **Closed Fist → Open Palm**.
- Background gesture recognition remains owned by the existing foreground service.
- MediaProjection remains user-authorized and persistent for the background session.
- Nearby Connections remains device-to-device with the existing pairing/authentication and verified PNG transfer protocol.
- Activity/service CameraX ownership handoff remains process-local and single-owner.
- Existing three-tab UI and theme system remain intact.

## Aerviqo additions

- Aerviqo name and the supplied launcher/logo artwork, used without redesigning the supplied image.
- Automatic Gallery save to `Pictures/Aerviqo` for captures and received screenshots, with duplicate-safe MediaStore publication.
- Camera-style shutter sound with persistent ON/OFF control.
- Existing 0.4.0 shutter/edge visual feedback and transfer/receive preview animation restored as a separate visual layer.
- Separate Settings place containing feedback and optional air-control switches.
- In-app gesture manual with vector hand illustrations, not photographs of real people.
- Faster temporal gesture tracking using MediaPipe VIDEO mode and the 0.4.0 stable-window/grace timing.
- Optional shake → flashlight.
- Optional open-palm vertical scroll and horizontal media swipe.
- Optional pinch → screen-coordinate select/click, pinch zoom, pinch + vertical → volume, and pinch + horizontal → brightness.
- Optional two same-direction swipes → Back/Home through a user-enabled AccessibilityService.
- Experimental foreground Air Mic: mouth-open detection can launch Android voice input while Aerviqo is visible. Returned text is inserted into the focused editable field when the user-enabled AccessibilityService permits it; otherwise it is copied to the clipboard.
- Nearby connection handling now treats the documented `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` (8003) and `STATUS_OUT_OF_ORDER_API_CALL` (8009) cases as recoverable state races instead of blindly issuing another connection attempt.

## Important Android constraints

Android does not allow an ordinary app to silently grant itself MediaProjection permission or click the system screen-capture consent dialog. The user must approve screen capture when background mode is enabled.

Camera access outside the Activity requires the user-visible camera foreground service and the required camera permission. Aerviqo does not use hidden APIs to bypass Android restrictions.

Air Mic is deliberately foreground-only. Android applies while-in-use restrictions to microphone foreground services, so Aerviqo does not secretly start a microphone service from another app. The experimental feature launches standard Android voice input from the visible Aerviqo Activity instead.

Optional cross-app controls require the user to enable Aerviqo's documented AccessibilityService. Brightness control additionally requires the Android write-settings permission. Cursor/visual overlays require Android's overlay permission.

## Gesture manual

The in-app manual documents the core and optional gestures with illustrated hand examples. The core mappings are deliberately unchanged:

- **Open Palm → Closed Fist:** capture and send.
- **Closed Fist → Open Palm:** authorize and receive.
- **Open-palm vertical movement:** scroll when enabled.
- **Open-palm horizontal swipe:** previous/next media when enabled.
- **Pinch:** select/click at the hand cursor.
- **Pinch + vertical movement:** volume.
- **Pinch + horizontal movement:** brightness.
- **Two left swipes:** Back.
- **Two right swipes:** Home.
- **Phone shake:** flashlight toggle.
- **Mouth open:** experimental Air Mic while Aerviqo is visible.

Optional controls are disabled by default so they cannot accidentally interfere with the proven screenshot workflow. The core screenshot/receive gestures do not depend on the optional controls.

## Build

Run in the configured Android/Codespace environment:

```text
./gradlew clean test assembleDebug --stacktrace --no-configuration-cache
```

The current inspection container cannot download the Gradle 9.7.1 distribution or Android SDK, so an APK build is not claimed as locally build-verified here. The source has been checked with dependency-free Kotlin smoke tests plus XML/resource/static checks; the final Android build must still run in the configured Codespace/Freebuff environment.

## Physical-device validation still required

Test on the actual phones before release, especially:

- background camera/gesture operation after leaving Aerviqo;
- MediaProjection recovery after screen lock/revocation;
- Nearby reconnects and the 8003/8009 race cases;
- automatic Gallery save;
- shutter sound and visual feedback;
- flashlight shake debounce;
- AccessibilityService controls;
- Air Mic on the target Android version/OEM.
