# Architecture

```text
CameraX
  ↓
MediaPipe Gesture Recognizer
  ↓
Hand quality gate
  ↓
GestureStateMachine
  ├── CAPTURE → MediaProjection request → ScreenCaptureService → app storage
  ├── SEND    → Nearby offer message
  └── RECEIVE → Nearby accept message
                         ↓
                  Nearby Connections
                         ↓
                   File payload
                         ↓
                  app storage → gallery/share
```

## Why the gesture state machine matters

The recognizer's output is a noisy stream of labels. The app therefore requires:

- confidence ≥ 0.72,
- one detected hand,
- sufficient hand size,
- a hand center near the frame center,
- a stable gesture dwell of 350 ms,
- sequence order,
- and a neutral release after each trigger.

That combination is more deliberate than checking `if (label == "Closed_Fist")` on every frame.

## Boundaries

Gesture processing is local.

Screen capture stays behind Android's MediaProjection consent mechanism.

Nearby transfer is local peer-to-peer. A sender first sends a small offer, the receiver accepts, and only then is the file payload sent.

No cloud backend or account system exists in the MVP.
