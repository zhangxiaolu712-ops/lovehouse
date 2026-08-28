# LoveHouse Native Android

Phase 0 is a single-module Kotlin + Jetpack Compose shell. It owns only native UI/navigation and reusable device-facing contracts; it does not copy the Web UI or connect production services.

## Open and build

Open this `android/` directory in Android Studio, use JDK 21, and install Android SDK Platform 36 plus Build Tools 36.0.0.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Pushes that touch `android/**` also run `.github/workflows/android-debug.yml` and upload the APK as `lovehouse-native-debug`.

## Stable Phase 0 boundaries

- Primary destinations: Home, Chat, Memory, Engineering, Settings.
- Secondary destination: Settings → Native Lab.
- Deep links: `lovehouse://home`, `lovehouse://chat`, `lovehouse://memory`, `lovehouse://engineering`, `lovehouse://settings`, `lovehouse://settings/native-lab`.
- Native Lab provides on-demand camera, microphone, one-shot location, test notification, biometric and deep-link smoke tests alongside the existing photo, file and share actions.
- Native capability contracts live under `core/permissions`, `core/storage`, and `core/status`.
- No backend URL, token, service role, provider session or production configuration exists in this module.
