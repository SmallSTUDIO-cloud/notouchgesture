# No Touch Gesture — engineering handoff

## Team output

### Software engineering

The project is organized around a small, testable core and thin Android integrations. Gesture decisions are made by a pure Kotlin state machine; camera, MediaProjection and Nearby APIs sit outside that core.

### Android development

The project includes real Android implementations for camera inference, screenshot consent/capture, nearby discovery/connection, transfer offer/accept, local file storage, gallery export and Android sharing.

### UI/UX

The interface uses a single Material 3 visual language, restrained cards, clear action hierarchy, Auto/Light/Dark appearance, visible status, permission explanations, and explicit security copy. The gesture UI emphasizes timing rather than decorative animation.

## Acceptance criteria

- [x] Android-first project source exists.
- [x] Theme selector at top: Auto / Light / Dark.
- [x] Palm → fist capture logic exists and is unit-smoke-tested.
- [x] Fist → palm send logic exists and is unit-smoke-tested.
- [x] Palm receive acceptance exists and is unit-smoke-tested.
- [x] Low-confidence gestures are ignored.
- [x] Wrong gesture order does not trigger an action.
- [x] Repeated holding cannot immediately retrigger after an action.
- [x] One-hand and framing quality checks exist.
- [x] MediaProjection consent flow is used.
- [x] Nearby connection uses a user-armed flow with authentication digits and explicit confirm/reject.
- [x] Screenshot offer precedes file payload transfer.
- [x] Received screenshots are saved into app storage and become the latest capture.
- [x] Gallery export and Android share sheet exist.
- [x] Privacy/security limitations are documented.
- [x] CI workflow exists for remote APK generation.

## Not claimed as complete

A physical-device build and two-device transfer test were not performed in this container because the Android SDK, Gradle runtime and adb are unavailable here. The logic layer was executed successfully, but that is not a substitute for Android hardware validation.

## First real-device test matrix

Test Android 11, 12/13, 14, and 15+ on at least three device families. For each device, record:

- gesture detection latency,
- false-trigger count in a 10-minute session,
- missed-trigger count,
- camera frame stability,
- screen-capture consent behavior,
- transfer discovery time,
- transfer completion time for 1 MB / 10 MB / 50 MB screenshots,
- interruption recovery,
- battery and thermal impact,
- behavior when app is backgrounded or the camera is temporarily unavailable.
