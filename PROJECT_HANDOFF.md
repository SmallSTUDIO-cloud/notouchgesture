# Aerviqo 0.5.0 handoff

## Baseline preserved

The uploaded PalmLink visual project remains the behavioral baseline. The existing gesture/Nearby/MediaProjection/background-service architecture was retained.

## Included

- Aerviqo branding using the supplied logo artwork exactly at the pixel level.
- 0.4.0 automatic Gallery save to Pictures/Aerviqo.
- 0.4.0 shutter sound ON/OFF behavior.
- 0.4.0 screenshot shutter/edge visual feedback and transfer preview animation.
- 0.4.0 temporal gesture tracking improvements.
- Separate Settings place and illustrated gesture manual.
- Optional extended hand-motion controls.
- Optional shake flashlight.
- Optional AccessibilityService controls.
- Foreground-only experimental mouth-open Air Mic.
- Nearby handling for the documented already-connected and out-of-order race states.

## Core mappings

Sender: Open Palm → Closed Fist = capture/send.
Receiver: Closed Fist → Open Palm = receive/authorize.

## Final verification status

Dependency-free core smoke checks and static resource/XML checks were run successfully in the available container. The Android SDK/Gradle distribution was unavailable, so the full Android build and APK generation must be performed in the configured Codespace/Freebuff environment.

Physical-device validation remains mandatory before release.
