# No Touch Gesture roadmap

## Product goal

Make nearby screenshot capture and sharing feel like a deliberate physical interaction rather than a collection of permissions and gestures.

## Release 0.1: proof of concept

Delivered in this source tree:

- Android Compose UI with Auto / Light / Dark appearance.
- CameraX front-camera preview.
- MediaPipe live-stream gesture recognition.
- Stable palm/fist gesture state machine.
- One-hand and framing quality gate.
- Open palm → closed fist capture trigger.
- Closed fist → open palm send trigger.
- Open palm receiver acceptance.
- MediaProjection capture service.
- Nearby Connections discovery, connection, offer, accept and file payload transfer.
- Local capture storage.
- Gallery export and Android share sheet.
- User-facing privacy and Android security notes.

## Release 0.2: reliability

1. Add CameraX/MediaPipe instrumentation tests on at least three physical device families.
2. Track gesture false positives and missed detections across lighting, distance, skin tone, background clutter, glasses/reflections, and camera quality.
3. Tune thresholds per device performance profile.
4. Add lifecycle recovery after app backgrounding, camera interruptions, incoming calls, and display rotation.
5. Add persistent transfer jobs, progress recovery and retry.
6. Add a real receive inbox/history instead of a single latest item.
7. Add explicit connection trust records so repeated transfers do not silently trust a new endpoint.

## Release 0.3: product differentiation

1. QR fallback for pairing when Nearby discovery is blocked.
2. Device nickname and verified-device list.
3. One-tap quick action for recently paired devices.
4. Animated gesture guidance that explains timing without covering the camera.
5. Accessibility-conscious alternatives: physical shortcut, notification action and Quick Settings tile.
6. Better transfer diagnostics and a privacy audit screen.

## Global screenshot strategy

A normal Android app cannot silently capture another app's screen just because it is looking through the camera. MediaProjection requires OS-mediated user consent, and Android 14+ adds stricter consent/session behavior.

Do not solve this by quietly abusing AccessibilityService. The production architecture should instead investigate a justified OS-level integration, launcher/device-owner scenario, OEM API, Quick Settings workflow, or another platform-approved mechanism. The correct solution depends on the intended distribution channel and Android version range.

## Release 1.0 gates

- Gesture action precision/recall measured on a real device test set.
- No screenshot or transfer starts from a single noisy frame.
- Every transfer has an explicit, understandable trust boundary.
- All stored screenshots can be deleted and exported.
- Failed transfers never surface incomplete images as successful results.
- Privacy text matches the actual implementation.
- Android 14+ behavior verified on physical hardware.
- Release build is signed and reproducible in CI.
