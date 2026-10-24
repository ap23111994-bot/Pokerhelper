# Poker Helper Android

Kotlin + Jetpack Compose, minimum Android 10 (API 29).

## Implemented
- Manual Hero and community card selection.
- Hold'em hand evaluator and Monte Carlo equity estimate against a random opponent hand.
- Pot odds, call EV, and basic outs count.
- Tournament blind timer with editable blinds and automatic doubling.
- User-consented live screen capture using Android MediaProjection.
- On-device ML Kit OCR sampled at a throttled rate, with a floating overlay showing text detected on the screen.
- Seat-position mapping for 2–10 occupied seats once occupied seats and the dealer/button seat have been detected or calibrated.
- Unit tests for hand rankings and seat-position mapping.

## Build
Use JDK 17 and Android SDK Platform 35. Open in Android Studio or run `gradle test assembleDebug`.
APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Live capture setup
1. Open Poker Helper.
2. Tap **Start live OCR**.
3. Grant Android's **Display over other apps** permission if prompted.
4. Tap **Start live OCR** again and approve Android's system screen-capture dialog.
5. The floating panel shows text recognized by ML Kit. Tap **Stop capture** to end capture.

Android displays a system screen-capture indicator and ongoing foreground-service notification while capture is active. Capture starts only after explicit user consent.

## Current limitations
Live capture and OCR are implemented, but this is not yet full table vision. The OCR layer does not reliably identify illustrated card faces, player avatars, chip stacks, or the dealer button. Automatic seat-position labels require a visual seat/button detector or a one-time calibration step; the included position calculator maps known seats correctly. Equity currently estimates against a uniformly random opponent hand, not a range-aware solver. This build should be treated as a training prototype and verified against the intended table layout.
