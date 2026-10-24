# Poker Helper Android

Kotlin + Jetpack Compose poker analysis and tournament timer. Minimum supported Android version: Android 10 (API 29).

## Features
- Manual Hero and community-card selection with duplicate prevention.
- Five-to-seven-card evaluator, from high card through straight flush.
- Monte Carlo equity estimate against a random opponent hand.
- Pot odds, call EV, and outs that improve the current hand.
- Tournament blind timer with editable starting blinds and automatic doubling.
- Unit tests for hand ranking, kickers, wheel straight, equity, and pot odds.

## Build
Use JDK 17 and Android SDK Platform 35. Open in Android Studio or run `gradle test assembleDebug`.
The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## CI
Pushes to main build and test the app. GitHub Actions uploads the APK as `PokerHelper-APK`.
This app uses manual card entry and does not capture or overlay on third-party poker clients.

## Limitations
Equity is a Monte Carlo estimate against a uniformly random opponent hand, not a range-aware solver. Outs are cards that improve the current best hand value and do not account for opponent redraws.
