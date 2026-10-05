# PalmLink gesture-tracking upgrade tracker

Date: 2026-10-04
Version: 0.4.0 gesture refinement

## Scope lock

Only gesture recognition/tracking behavior is being changed. Capture, MediaProjection, Nearby transfer, pairing, gallery saving, shutter sound, visual feedback, animations, app icon, UI navigation, and service lifecycle behavior are intentionally preserved.

## Implementation

- [x] Restore fast gesture-step timing: 180 ms stable window.
- [x] Keep capture semantics: OPEN_PALM -> CLOSED_FIST.
- [x] Keep receive semantics: CLOSED_FIST -> OPEN_PALM.
- [x] Add 120 ms neutral rearm window.
- [x] Add 450 ms action cooldown.
- [x] Keep a 2.5 s maximum interval between the two steps.
- [x] Tolerate up to 140 ms of confidence jitter without restarting the current gesture.
- [x] Reset the stable window after a longer confidence loss so stale gesture time cannot be reused.
- [x] Run MediaPipe GestureRecognizer in synchronous VIDEO mode with monotonic timestamps for temporal tracking.
- [x] Keep CameraX at 640x480 and KEEP_ONLY_LATEST.
- [x] Use MediaPipe ImageProcessingOptions for frame rotation instead of creating an additional rotated bitmap.
- [x] Keep one analysis executor and one detected hand.
- [x] Preserve the conservative landmark fallback for brief classifier gaps.

## Regression audit

- [x] Non-gesture runtime source hashes recorded before edits.
- [x] Capture/visual-feedback files unchanged.
- [x] Nearby/transfer files unchanged.
- [x] MainActivity unchanged.
- [x] Pure Kotlin gesture-state harness passes.
- [x] Fast default capture sequence passes.
- [x] Receive sequence passes.
- [x] Wrong-order sequence stays inactive.
- [x] Brief confidence dip does not restart a stable gesture.
- [x] Long confidence loss forces a fresh stable window.
- [x] Trigger remains single-fire until neutral release.

## Build boundary

The container cannot execute the project's Gradle wrapper because the Gradle 9.7.1 distribution is not cached and external downloads are unavailable. The Codespace remains the required Android build environment. Physical-device validation is required for final confirmation of camera/tracking feel on the target Samsung hardware.
