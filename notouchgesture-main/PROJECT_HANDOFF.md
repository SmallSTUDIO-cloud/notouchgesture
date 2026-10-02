# PalmLink — engineering handoff

## Architecture

PalmLink keeps gesture inference in a small Kotlin state machine and MediaPipe controller. A foreground `LifecycleService` owns the background camera pipeline and the single user-approved MediaProjection session. The Activity is only the configuration/UI surface.

## Background workflow

1. User grants camera permission.
2. User taps **Enable PalmLink** while the Activity is visible.
3. Activity launches the official MediaProjection consent flow.
4. After approval, `ScreenCaptureService` is promoted to a foreground service with camera + mediaProjection types. The visible Activity releases the foreground camera pipeline before starting the service.
5. The service starts CameraX analysis without a PreviewView and starts MediaProjection with an ImageReader.
6. The ImageReader continuously drains frames but only copies one frame after a capture gesture requests it.
7. The gesture recognizer runs in `BACKGROUND` mode.
8. Open palm → fist requests a screenshot. Closed fist → open palm sends the latest screenshot when a Nearby endpoint is connected. Open palm accepts a pending Nearby offer.

## Why the Samsung screen-recording popup is no longer repeated

The old implementation created a new MediaProjection service only after every capture gesture. The revised implementation keeps the projection session alive for the entire user-enabled background session. Android still shows its mandatory consent UI when the session is first enabled, but subsequent gesture captures do not launch a new consent dialog.

## Performance and recognition

- Camera analysis target: 640×480.
- Backpressure: `STRATEGY_KEEP_ONLY_LATEST`.
- One MediaPipe hand.
- Only `Open_Palm` and `Closed_Fist` classifier categories are requested.
- MediaPipe detection/tracking/presence thresholds: 0.35.
- State-machine confidence threshold: 0.50.
- Stable dwell: 220 ms.
- Rearm neutral window: 180 ms.
- Action cooldown: 600 ms.
- A landmark fallback is used only when MediaPipe has a valid 21-point hand but the canned target classifier is unavailable or very low confidence.

## Security

The app does not attempt to bypass Android's MediaProjection or permission model. Nearby authentication digits remain part of the connection flow, and both sides must accept a Nearby connection before payload transfer.

## Validation status

The pure Kotlin gesture/transfer smoke tests pass. Source-level review and static consistency checks have been completed for the Android project. Physical-device validation remains necessary for Samsung/OEM camera and foreground-service behavior because the working container cannot run the Android app on hardware.
