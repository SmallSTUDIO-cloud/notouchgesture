# PalmLink roadmap

## Completed in this revision

- Persistent foreground-service camera gesture recognition.
- Persistent MediaProjection session for gesture-triggered screenshots.
- No repeated screen-capture consent dialog for each gesture.
- Improved palm/fist detection tolerance and lower-latency state machine.
- Permission UI refresh after returning from Android settings/dialogs.
- Shared Nearby manager between Activity and foreground service.
- Background screenshot send/receive for already authenticated Nearby connections.
- PalmLink branding and app icon.
- CI SDK installation workflow cleanup.

## Next physical-device validation

- Android 11/12/13/14/15 on several device families.
- Samsung foreground-service behavior.
- Camera thermal/battery measurements.
- MediaProjection revocation and recovery.
- Nearby interruption/reconnection and large-file transfer.
- OEM battery-optimization behavior.
- Instrumented UI tests and service lifecycle tests.
