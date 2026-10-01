Made the apk! With help of chatgpt and tweaking some github codes!
Act like a professional app developer to fix and improve the program.

#Indentified problems 
1.those given screen shots (phone connection )
2.the open palm tracking is very poor. The camera barely can notice open palms.
3.the app is bit laggy.
4.when you  update a permission like u allow camera but the tab 'gesture' shows that option still allow permission, like you need to change tabs to back gesture to make the camera functional.
5.Big problem : When you try to capture any screen shot then a pop up appears as this app tries to record screen etc (samsung screen record pop up) then when you click 'start now' the app crashes then a pop up says that this app has a bug try clear cache.

#Missing feature
The camera and the tracking system with screen shot capture and send dosent work in background. Users cant close the app and keep the app running in background then doing his job then in any app any time users can do the hand movements and use the features.
Key concept: Users can use the features while using others app (main)to capture screen shot of those app's things

#Fix all the indentified problems above
#Add that missing feature
#make open palm tracking or detect much easier and faster(actually working)
#Fix the screen record thing that makes the app crash and make screen shot actually functional and no bug. Also the app mustn't ask for that start now or cancel pop up when shown hand gestures it must capture the screen no hand touch needed to confirm screen shot. It should automatically start screen shot or allow screen shot rather than manuallly pressing button 'start now' on android pop up.
#Improve 

Change : 
-Rename the apk to "PalmLink"
-Rechange the app icon to "appicon.jpg"

Analyze the full codes all codes, run them
Find more weaknesses, bugs and typos then fix them, check if theres more problems then fix those, check if its actually fixed-[run these until final completetion.]
Fix all the github workflow problems and possible problems or errors.
Check if the actual features are functional if not then fix them again.
Check everything all above again then find or analyze all problems, errors fix them
Run these until final best result.

Source: https://github.com/SmallSTUDIO-cloud/notouchgesture
Source (code and files) :
The full project files with codes (all) are given in a zip file.
# No Touch Gesture

Touchless Android prototype for gesture-driven screenshot triggering and nearby screenshot transfer.

## What this build actually does

- Uses the front camera + MediaPipe Gesture Recognizer to detect `Open_Palm` and `Closed_Fist`.
- Uses a debounced state machine instead of raw gesture labels, reducing false triggers.
- Capture sequence: **Open palm → closed fist**.
- Send sequence: **Closed fist → open palm**.
- Receive sequence: **Open palm** after a pending transfer offer.
- Uses Android MediaProjection for screen capture and Android Nearby Connections for local peer-to-peer transfer.
- Includes Auto / Light / Dark appearance selection in the top app bar.
- Saves the latest capture privately and can export it to the gallery or share it with another Android app.

## Important Android constraint

Modern Android requires explicit user consent for MediaProjection screen capture. A normal camera activity also cannot silently capture another app's screen. This project therefore does **not** pretend to implement a hidden global gesture overlay. The Gesture Lab is fully testable inside the app, while screen capture uses the official Android consent flow.

A global, no-touch capture experience needs an OS-level integration that is appropriate for the product's distribution model. Do not ship an AccessibilityService merely as a workaround for screenshot access.

## Run

### Android Studio

Open this folder as a Gradle project. The build downloads the MediaPipe gesture model into `app/src/main/assets/` if it is not already present.

### GitHub Actions

This repository includes `.github/workflows/android-build.yml`, which installs Gradle 9.5 on the runner and produces `app-debug.apk` as a build artifact. This is useful when working from a mobile-only workflow.

## Manual test flow

1. Install the debug APK on two Android devices.
2. On device A, open **Gesture Lab** and grant camera permission.
3. Verify the status moves from `Searching` to `Ready` while one hand is centered in frame.
4. Test **Open palm → fist**. The app should raise the Android screen-capture consent dialog.
5. Grant consent. A capture should be saved and shown in the app.
6. On device A/B, open **Nearby**. One device advertises while the other discovers.
7. Establish the connection while both devices are in the transfer screen. The auth digits are shown for comparison.
8. On the sender, ensure a capture exists and perform **Fist → Open palm** to create a transfer offer.
9. On the receiver, perform **Open palm** to accept the pending offer.
10. Confirm the received image appears in the receiver's capture history and can be exported.

## Known scope limits

- This is an Android-first prototype, not a polished production release.
- No cloud backend is used.
- The app intentionally avoids background camera capture and AccessibilityService-based screen scraping.
- The first production release should add stronger device pairing/trust, transfer resume, lifecycle recovery, instrumentation tests on physical devices, and a dedicated global-capture architecture backed by platform permissions that are explicitly justified to users.
