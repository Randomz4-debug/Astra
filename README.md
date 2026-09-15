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
- Integrated REST API Hub in the main Astra navigation

## Important Android limitations

A normal Android app cannot arbitrarily intercept third-party call audio, silently capture screens/camera/microphone, or bypass another app's security. Astra must use user-granted Android capabilities.

## Build

Open this directory in Android Studio and let Gradle sync.

For a debug APK:

    ./gradlew assembleDebug

The current app includes the integrated API Hub, local/cloud model settings, voice controls, screen/accessibility controls, file and camera entry points, and chat history. Provider-specific features remain permission- and Android-version-dependent and are not falsely represented as unrestricted functionality.
