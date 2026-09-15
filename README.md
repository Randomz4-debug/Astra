# Astra

Filename: README.md

A privacy-first Android assistant foundation based on the supplied Astra specification.

## Current foundation

- Kotlin + Jetpack Compose
- Local-first provider abstraction
- Local AI provider routing
- Voice/TTS/wake-word interfaces
- Accessibility service with device-control primitives
- Foreground assistant service
- Permission-aware UI
- Local-only and background settings
- REST API Hub
- Chat history, memory and connected-app infrastructure
- Camera and screen-access entry points

## Realtime execution upgrade

The Android build pipeline now applies Astra's realtime execution layer before packaging. It adds a pre-Astra input-translation layer, closed-loop screen-agent execution, semantic screen mapping, low-latency tap/scroll/gesture primitives, explicit long-press/double-tap support, deterministic Android timer execution, realtime event/state buses, performance profiles, overlay primitives, and a safe game-analysis state bus. Latency-sensitive operations remain local instead of requiring an LLM round trip.

Astra's perception and overlay architecture is intended to support high-speed object detection/tracking, live annotations and image/video analysis while respecting Android permissions and application security boundaries.

## Important Android limitations

A normal Android app cannot arbitrarily intercept third-party call audio, silently capture screens/camera/microphone, bypass another app's security, or guarantee unrestricted control of protected multiplayer games. Astra uses user-granted Android capabilities and keeps game features focused on permitted analysis/testing/accessibility use.

## Build

Open this directory in Android Studio and let Gradle sync.

For a debug APK:

    ./gradlew assembleDebug

The GitHub Actions build also applies the latest Astra upgrade scripts and uploads the resulting debug APK as a workflow artifact.
