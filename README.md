# Astra

Filename: README.md

A privacy-first Android assistant foundation based on the supplied Astra specification.

## Current foundation

- Kotlin + Jetpack Compose
- Local-first provider abstraction
- Local AI placeholder engine
- Voice/TTS/wake-word interfaces
- Accessibility service declaration
- Foreground assistant service
- Permission-aware initial UI
- Local-only and background settings
- Unit-test foundation

## Important Android limitations

A normal Android app cannot arbitrarily intercept third-party call audio, silently capture screens/camera/microphone, or bypass another app's security. Astra must use user-granted Android capabilities.

## Build

Open this directory in Android Studio and let Gradle sync.

For a debug APK:

    ./gradlew assembleDebug

The supplied specification requires later stages for real on-device STT, wake-word detection, OCR/vision, MediaProjection, notification access, messaging assistance, and OpenAI Realtime integration. Those integrations require concrete provider/model choices and Android-version-specific implementation/testing; they are intentionally not faked as complete functionality in this foundation.
