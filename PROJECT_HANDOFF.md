# PalmLink 0.3.1 handoff

This release keeps the foreground service as the single owner of Nearby, MediaProjection and background gesture recognition.

Key corrections in 0.3.1:
- Connected-device FGS type is added before Nearby use even if the initial combined FGS promotion fell back to mediaProjection-only.
- Activity destruction/recreation no longer controls Nearby.
- Nearby role is persisted and restored after service recreation.
- Sender gesture is OPEN_PALM -> CLOSED_FIST. Receiver gesture is CLOSED_FIST -> OPEN_PALM. Pending offers do not create a hidden third gesture.
- Screenshot transfers use transfer IDs, byte count, SHA-256, PNG signature validation and atomic publication.
- Pairing no longer treats endpoint display names as credentials. First pairing establishes a local shared secret after the Nearby authentication digits are manually confirmed; reconnects use an HMAC challenge/response before transfer data is accepted.
- Queued screenshots are persisted for a short reconnect window.

Build command:
`./gradlew clean test assembleDebug --no-configuration-cache`
