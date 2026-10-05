# PalmLink roadmap

## Completed in this revision

- Persistent foreground-service camera gesture recognition.
- Persistent MediaProjection session for gesture-triggered screenshots.
- No repeated screen-capture consent dialog for each gesture.
- Safe foreground-to-background CameraX/MediaPipe ownership handoff.
- Synchronous MediaPipe inference with correct frame rotation and safe bitmap lifetime.
- More tolerant palm/fist recognition with landmark fallback and lower-latency state machine.
- Idempotent Nearby advertising/discovery with duplicate-start handling.
- Shared Nearby manager between Activity and foreground service.
- Background screenshot send/receive for already authenticated Nearby connections.
- PalmLink branding and app icon.
- Android permission matrix and foreground-service declarations cleaned up.
- CI SDK installation workflow updated for API 37.0.

## Final physical-device validation

- Android 11/12/13/14/15 on several device families.
- Samsung foreground-service and background-camera behavior.
- Camera thermal/battery measurements.
- MediaProjection revocation and recovery.
- Nearby interruption/reconnection and large-file transfer.
- OEM battery-optimization behavior.
- Instrumented UI tests and service lifecycle tests.
