# Aerviqo architecture

```text
CameraX
  ↓
MediaPipe Gesture Recognizer (VIDEO mode)
  ↓
Hand quality gate + landmark fallback
  ↓
GestureStateMachine
  ├── CAPTURE → persistent MediaProjection session → ScreenCaptureService → app storage
  ├── RECEIVE → Nearby authorization
  └── background runtime → Nearby send/receive

Optional motion layer
  ├── hand motion → TouchlessActionDispatcher
  │     ├── AccessibilityService: scroll / swipe / Back / Home / tap / pinch zoom
  │     ├── AudioManager: volume / media keys
  │     ├── CameraManager: flashlight
  │     └── Settings.System: brightness (after user approval)
  └── accelerometer → ShakeFlashlightController

Foreground-only experimental Air Mic
  CameraX frame → ML Kit Face Detection → mouth-open temporal gate
  → Android voice input → focused editable field / clipboard
```

## Preservation rules

The foreground service remains the single owner of the background CameraX/MediaPipe engine, Nearby runtime and MediaProjection session. The existing controller registry/deferred MediaPipe close path is retained.

The existing Nearby authentication, transfer IDs, SHA-256 verification, PNG validation, temporary storage and atomic publication remain the transfer boundary. Visual feedback and optional controls are layered on top rather than replacing that protocol.

The existing three-tab Compose UI remains the primary UI. Settings is exposed from the existing top-bar settings button. The gesture manual is opened from Settings.

## Optional cross-app control boundary

Aerviqo never attempts hidden input injection. Cross-app gestures use the documented AccessibilityService API only after the user explicitly enables the service. The service can perform global navigation, scroll/swipe/tap and multi-stroke pinch gestures.

## Air Mic boundary

ML Kit face detection is only instantiated when the foreground Gesture screen supplies an Air Mic callback. Detection is throttled and drops overlapping work so it does not create an unbounded queue. The returned voice text is inserted through the user-enabled AccessibilityService when possible; otherwise Aerviqo copies the text to the clipboard.

Android's while-in-use microphone restrictions are respected. Aerviqo does not add microphone capture to the always-on camera/MediaProjection foreground service.
