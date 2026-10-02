# PalmLink architecture

```text
CameraX
  ↓
MediaPipe Gesture Recognizer
  ↓
Hand quality gate + landmark fallback
  ↓
GestureStateMachine
  ├── CAPTURE → persistent MediaProjection session → ScreenCaptureService → app storage
  ├── SEND    → Nearby offer message
  └── RECEIVE → Nearby accept message
                         ↓
                  Nearby Connections
                         ↓
                   File payload
                         ↓
                  app storage → gallery/share
```

## Gesture pipeline

CameraX uses a single latest-frame analysis queue. Frames are rotated using the CameraX frame metadata before MediaPipe inference. MediaPipe runs in synchronous `IMAGE` mode on a single analysis executor so an image/bitmap cannot be recycled while native inference is still using it. Action state is evaluated on every frame, while UI updates are throttled to avoid main-thread churn.

The canned classifier is limited to `Open_Palm` and `Closed_Fist`. When those categories are temporarily unavailable but MediaPipe still returns a valid 21-point hand, PalmLink uses a conservative landmark-only fallback. Final gesture actions still pass through the state machine confidence and stability gates.

## Background handoff

The Activity and foreground service never intentionally run two independent CameraX sessions. A process-local gesture-controller registry transfers camera ownership before the service starts its analyzer. The previous controller stops accepting frames and defers native recognizer close until the in-flight analysis call finishes; the Activity starts the service only after that handoff callback completes.

## Screen capture

The user grants MediaProjection consent once when enabling background mode. `ScreenCaptureService` keeps that projection session alive for the duration of the background session and requests a new screen frame only after a gesture asks for a capture. It does not attempt to bypass Android's consent mechanism.

## Nearby lifecycle

`NearbyTransferManager` is process-local and shared by the Activity and service. A device uses the explicit advertiser or discoverer role selected on the Nearby screen, and switching roles stops the previous scan/advertisement before starting the new one. Stale start callbacks are ignored, duplicate start requests are idempotent, and a short delayed retry covers the platform stop/start race that can surface 8001 (`STATUS_ALREADY_ADVERTISING`) or 8002 (`STATUS_ALREADY_DISCOVERING`). Background mode does not silently start both roles; existing authenticated connections remain available during the camera-service handoff.

## Transfer flow

A sender first sends a small offer. The receiver explicitly accepts the connection and then the offer. Only after the sender receives an `Accept` does PalmLink send the file payload. Incoming files are copied into app-private storage only after a successful payload transfer.

## Boundaries

Gesture inference is local. Screen capture remains behind Android's MediaProjection permission. Nearby transfer is device-to-device through Google Nearby Connections. No cloud backend or account system is part of the app.
