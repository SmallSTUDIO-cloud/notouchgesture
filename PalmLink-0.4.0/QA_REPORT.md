# PalmLink 0.4.0 QA report

## Scope
This release adds visual feedback, shutter sound controls, automatic Gallery saving and transfer-preview animation while preserving the 0.3.1 gesture/Nearby/MediaProjection architecture.

## Source-level audit
- Foreground service remains the single owner of Nearby, MediaProjection, screen capture and background gesture recognition.
- Existing gesture semantics are unchanged: OPEN_PALM -> CLOSED_FIST captures/sends; CLOSED_FIST -> OPEN_PALM receives.
- Existing pairing/authentication and transfer verification remain in place.
- Captures are still written to private PalmLink storage first, then automatically exported to Pictures/PalmLink.
- Modern Gallery export uses MediaStore with IS_PENDING and an idempotent source-to-URI mapping.
- Legacy Android support requests WRITE_EXTERNAL_STORAGE only through API 28.
- Visual feedback is non-touchable and non-focusable, so it does not consume taps intended for the underlying app.
- Visual feedback is skipped safely when overlay permission is unavailable; the app exposes a Settings route to enable it.
- Screenshot capture feedback clears before a subsequent capture and adds a brief delay if an existing overlay was active, reducing the risk of capturing the prior overlay into the next screenshot.
- Shutter sound is controlled by a persistent app setting and uses MediaActionSound.SHUTTER_CLICK.
- Sending preview data is a small JPEG thumbnail and is transmitted only after receiver acceptance. The original file still transfers through the existing verified FILE payload path.
- Duplicate preview/transfer state is cleared on completion, cancellation and disconnect.
- Received screenshots are auto-saved to Gallery only after payload verification/publication.
- The existing app icon remains unchanged; the transfer animation uses a transparent-logo derivative for a clean blue-circle presentation.

## Pure Kotlin verification
Passed targeted checks for:
- background capture gesture sequence
- transfer protocol Offer encode/decode
- Preview encode/decode and malformed-preview rejection
- invalid offer size rejection
- pairing shared-secret symmetry
- HMAC proof equality

## Packaging checks
- No APK/build output is included in the source ZIP.
- ZIP has one project root with no accidental nested project directory.
- Source ZIP contents were compared against the original 0.3.1 source; only intended 0.4.0 files/assets differ.

## Full Android build / physical-device test
Required in the configured Codespace:
`./gradlew clean test assembleDebug --no-configuration-cache`

This inspection container does not contain the Android SDK or Gradle distribution, and external downloads are unavailable. Therefore the final 0.4.0 APK is **not claimed as Android-build-verified in this environment**. Physical-device validation is still required, especially for Samsung/OEM overlay, foreground-service and camera behavior.

## Gesture timing

The production gesture state machine uses a short stable window (180 ms), a 120 ms neutral rearm window, a 450 ms action cooldown, and a 2.5 second two-step timeout. Brief confidence dips up to 140 ms do not restart the gesture. MediaPipe runs in synchronous VIDEO mode with monotonically increasing timestamps, while the existing 640×480 CameraX pipeline remains unchanged. No capture, Nearby, transfer, gallery, sound, visual feedback, or service lifecycle code was changed.
