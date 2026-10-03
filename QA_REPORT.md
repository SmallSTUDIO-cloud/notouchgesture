# PalmLink 0.3.1 QA report

## Final source audit
- Foreground service owns Nearby, MediaProjection, screen capture and background gesture recognition.
- Nearby advertising/discovery commands are routed through the foreground service; Activity destruction does not stop Nearby.
- `connectedDevice` foreground-service type is added before Nearby use, including the case where initial foreground promotion fell back to `mediaProjection` only.
- Camera foreground-service type remains guarded at API 30+.
- MediaProjection startup keeps the Android-required consent -> foreground service -> MediaProjection acquisition order.
- Background gesture semantics are deterministic: `OPEN_PALM -> CLOSED_FIST` captures/sends; `CLOSED_FIST -> OPEN_PALM` receives.
- Screenshot transfer uses transfer ID, size, SHA-256, PNG signature validation, temporary storage and atomic publication.
- First pairing uses Nearby's user-visible authentication confirmation, then a local shared-secret/HMAC handshake for reconnect authentication. Endpoint names are not treated as credentials.
- Queued screenshots use wall-clock timestamps so the reconnect TTL remains valid across process recreation/reboot.
- A screenshot queued while another transfer is active is persisted and can survive service/process recreation.

## Pure Kotlin verification
Passed targeted smoke checks for:
- background capture gesture
- background receive gesture
- shared-secret derivation on both peers
- HMAC proof equality and nonce mismatch rejection
- transfer protocol encode/decode
- pairing message encode/decode
- malformed message rejection

## Full Android build
Required command:
`./gradlew clean test assembleDebug --no-configuration-cache`

The available inspection environment does not have the Android SDK/Gradle distribution required to execute the full Android build, so an APK is **not claimed as build-verified here**. The project is packaged without build outputs; run the command above in the configured Codespace before installing.
