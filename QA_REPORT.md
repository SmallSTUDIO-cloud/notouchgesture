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

## Dependency-free verification completed in this container

- Core Kotlin smoke test passed for the capture state machine, preview wire-message round trip and symmetric pairing-secret derivation.
- XML parsing passed for all Android resource XML files.
- Resource-reference check found no missing string resources.
- Kotlin parser/static checks showed no Kotlin syntax/parser errors in the changed Android/UI source files. Full Android type-checking could not be performed because the Android SDK/Gradle distribution is unavailable in this container.
- Supplied logo pixels were compared after lossless PNG conversion: decoded pixels are identical to the uploaded artwork.

## External build verification required

Run:

`./gradlew clean test assembleDebug --stacktrace --no-configuration-cache`

The configured Codespace/Freebuff environment must perform the final Android compile, unit-test run and APK packaging because this container cannot obtain the Gradle 9.7.1 distribution or Android SDK.

## Physical-device verification required

The final APK still needs real-device testing for Samsung/OEM camera behavior, background service survival, MediaProjection recovery, Nearby reconnects, Gallery publication, visual overlays, optional AccessibilityService actions, flashlight debounce and Air Mic.
