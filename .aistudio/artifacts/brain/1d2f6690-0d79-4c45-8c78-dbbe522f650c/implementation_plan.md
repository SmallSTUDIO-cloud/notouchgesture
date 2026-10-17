# Aerviqo 0.5.0: Complete Bug Fix, Gesture Repair & Performance Optimization

Stabilize the Aerviqo (formerly PalmLink) Android application, resolve the critical pinch-vs-fist classification defect, restore all 0.5.0 extended gesture controls and settings, optimize camera inference and background execution, and verify the build.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural decisions incorporate the user's clarified preferences and repository requirements:

- **Confirmed Decision 1: Pinch Interaction Model**: Active pinch will support hold-and-drag for continuous adjustments (vertical motion for volume, horizontal motion for brightness) and single tap-on-release for selection. Pinch state has strict priority over Closed Fist to eliminate accidental screenshot captures.
- **Confirmed Decision 2: Air Mic Lifecycle & Background Strategy**: Air Mic (mouth-open detection with ML Kit face detection) remains completely dormant until explicitly enabled in settings. When disabled, face detection does not run and allocates zero frame processing resources.
- **Confirmed Decision 3: Background Camera Inference Rate**: An adaptive throttling strategy is adopted: when no hand is present, inference drops to a power-saving baseline (5–8 Hz); once a hand enters the frame, inference ramps to 20–24 Hz for responsive gesture tracking.
- **Confirmed Decision 4: Build Toolchain Compatibility**: The container uses Gradle 9.3.1. Android Gradle Plugin (AGP) will be aligned to `9.1.1` and compileSdk to 36/37 with JVM toolchain 17, resolving the AGP 9.3.0 Gradle version mismatch without altering project dependencies.

---

## 1. Overview & Core Concept

- **What It Does**: Aerviqo enables touchless screenshot capture and phone-to-phone transfer using front-camera hand gestures, alongside system-wide touchless controls including scrolling, media playback, volume, brightness, cursor navigation, shake flashlight, and Air Mic.
- **Target Audience / Persona**: Users needing hands-free device control (cooking, laboratory work, presentations, dirty/wet hands, accessibility needs) and peer-to-peer screenshot sharing without manual button presses.
- **Key Value**: Seamless, instant air-gesture workflows without cloud accounts, preserving the core Open Palm → Closed Fist sender and Closed Fist → Open Palm receiver protocols while ensuring low CPU, battery, and thermal footprint.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Sender Screenshot & Transfer Flow**:
   - User poses Open Palm to front camera -> visual pill confirms "PALM DETECTED / ARMED".
   - User transitions to Closed Fist -> MediaProjection captures screen, shutter sound plays, glowing blue border flashes (3-second feedback), screenshot auto-saves to gallery, and Nearby Connections transmits offer to paired peer.
2. **Receiver Acceptance Flow**:
   - Paired peer holds Closed Fist -> then Open Palm -> incoming offer accepted touchlessly, screenshot received, blue-outline modal displays received image, auto-saved to gallery.
3. **Pinch-to-Interact & Drag Flow**:
   - User brings thumb and index fingertips together (ratio < 0.35) -> pinch state engages with hysteresis locking.
   - Closed Fist classification is suppressed immediately; holding pinch does not trigger screenshot.
   - Moving hand vertically adjusts media volume; moving horizontally adjusts display brightness (if enabled).
   - Releasing pinch triggers a single selection / tap action without re-triggering fist.
4. **Settings & Diagnostics**:
   - Material 3 settings dialog for toggling extended gestures, sensitivity (Low / Medium / High), flashlight shake, shutter sound, and feedback styling.

### Visual Identity & Theme
- **Aesthetic Direction**: Minimal, utilitarian, high-contrast dark/light mode with glowing accent rings.
- **Color Palette**: Deep background (`#0B0F19` dark, `#F8FAFC` light), Electric Cyan / Blue accent (`#0284C7` / `#38BDF8`), Surface containers (`#1E293B`), Success emerald (`#10B981`).
- **Typography & Hierarchy**: Clean system sans-serif with bold stat chips, mono-spaced auth digits, and distinct gesture recipe badges.

---

## 3. Key Product Decisions & Trade-Offs

- **Pinch Priority via Hysteresis & Ratio Shielding**:
  - *Chosen Approach*: Calculate continuous thumb-index ratio normalized by wrist-to-middle-MCP reference distance. Enter pinch below `0.38f`, exit above `0.52f`. When pinch is active or entering, override any MediaPipe `CLOSED_FIST` label to `PINCH`, preventing the state machine from entering capture or send-arm states.
  - *Why*: Eliminates false screenshot triggers caused by MediaPipe labeling pinched fingers as a closed fist.
- **Decoupled Background Camera Pipeline**:
  - *Chosen Approach*: Single active ImageAnalysis analyzer with `STRATEGY_KEEP_ONLY_LATEST`, queue depth 1, frame skipping when inference is running (`isAnalyzing` atomic lock), and frame interval throttling (150ms when idle, 45ms when tracking active hand).
  - *Why*: Eliminates frame backlog, CPU heating, and UI thread lag in background services.
- **Touchless Accessibility Action Dispatcher**:
  - *Chosen Approach*: Clean Android `AccessibilityService` (`TouchlessAccessibilityService`) integration with standard `performAction` (GLOBAL_ACTION_BACK, GLOBAL_ACTION_HOME, scroll forwards/backwards) and fallback intent broadcasts for media/volume.
  - *Why*: Respects official Android accessibility architecture without unstable private APIs.

---

## 4. Technical Architecture & Data Strategy

### Architecture & Component Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        CameraX & Sensor Pipeline                       │
│  Front Camera ──> ImageAnalysis (Keep-Latest, 1-depth)                │
│  Accelerometer ──> ShakeFlashlightController (Torch Callback)          │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       Inference & State Machine                        │
│  MediaPipe Tasks Vision (GestureRecognizer) ──> HandLandmarks (21 pts) │
│  ML Kit Face Detection (MouthOpenDetector)   ──> Dormant if disabled  │
│                                  │                                     │
│  Pinch Ratio Analyzer (Hysteresis 0.38/0.52) ──> Pinch Priority Gate   │
│                                  │                                     │
│  GestureStateMachine (StableMs, Cooldown, NeutralRearm)                │
│   ├── OPEN_PALM ──> CLOSED_FIST  ──> CAPTURE / SEND_OFFER              │
│   ├── CLOSED_FIST ──> OPEN_PALM  ──> RECEIVE_ACCEPT                    │
│   └── PINCH_ACTIVE               ──> VOLUME / BRIGHTNESS / TAP         │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                      Action Dispatch & Persistence                     │
│  ScreenCaptureService (MediaProjection + Shutter Sound + Blue Edge)    │
│  TouchlessActionDispatcher ──> TouchlessAccessibilityService           │
│  NearbyTransferManager     ──> Google Nearby Connections (P2P WiFi/BT) │
│  MediaStoreSaver           ──> Pictures/Aerviqo + DCIM gallery         │
│  FeedbackSettings          ──> SharedPreferences (PalmLink compat)     │
└────────────────────────────────────────────────────────────────────────┘
```

### Component & State Mapping

1. **`GestureRecognizerController.kt`**:
   - Computes thumb-index distance vs palm reference distance (`pinchRatio`).
   - Applies enter threshold `0.38` and release threshold `0.52`.
   - When pinched, emits `Label.PINCH`, completely suppressing `CLOSED_FIST`.
   - Dispatches `HandMotion(centerX, centerY, deltaX, deltaY, pinchRatio)`.
2. **`GestureStateMachine.kt`**:
   - Supports `Label.PINCH` as a distinct state.
   - Ignores `CLOSED_FIST` when transitioning from or holding `PINCH`.
   - Preserves `OPEN_PALM -> CLOSED_FIST` (Capture/Send) and `CLOSED_FIST -> OPEN_PALM` (Receive).
3. **`TouchlessActionDispatcher.kt`**:
   - Dispatches scroll, navigation (Back, Home, Recents), volume, brightness, and media commands.
   - Throttles continuous actions (volume/brightness) to max 5 updates/sec to prevent rapid jumping.
4. **`ScreenCaptureService.kt`**:
   - Handles MediaProjection lifecycle, screenshot capture, blue edge feedback broadcast, shutter audio, and auto-gallery save via `MediaStoreSaver`.
5. **`ShakeFlashlightController.kt`**:
   - Accelerometer shake detection with cooldown and torch camera hardware checks.
6. **`CursorOverlayController.kt`**:
   - Draggable pointer overlay responding to hand center position, filtered with dead-zone smoothing.
7. **`AerviqoSettingsDialog.kt` & `GestureManualDialog.kt`**:
   - Full user settings and visual gesture guides matching the v0.5.0 specification.
