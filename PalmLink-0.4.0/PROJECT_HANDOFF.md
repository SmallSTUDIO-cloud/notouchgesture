# PalmLink 0.4.0 handoff

## Baseline preserved
The PalmLink 0.3.1 implementation is the behavioral baseline for this release. Gesture recognition, Nearby lifecycle, reconnect authentication, MediaProjection capture and verified file transfer are not intentionally redesigned.

## 0.4.0 additions
- Automatic Gallery save to Pictures/PalmLink.
- Duplicate-safe MediaStore export on Android 10+.
- Screenshot shutter flash and 3-second PalmLink-blue edge animation.
- Shutter sound ON/OFF setting.
- Non-interactive system overlay for background visual feedback.
- Actual screenshot thumbnail orbit animation for send/receive.
- Receiving completion panel with screenshot preview and Gallery status.
- Clean transparent PalmLink logo asset for the animation; the app launcher icon is unchanged.

## Build
`./gradlew clean test assembleDebug --no-configuration-cache`

## Recommended release validation
Install the debug APK on both test phones and verify capture, automatic Gallery save, gesture-driven sending, receiving, preview animation, shutter ON/OFF, and the existing background behavior.
