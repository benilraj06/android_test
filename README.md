# AndroidTest

Minimal Android app (Kotlin + Jetpack Compose, Material 3).

- AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.11.1
- minSdk 24 · target/compileSdk 35 · JDK 17

## Build

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

CI (`.github/workflows/build.yml`) builds the debug APK on every push and uploads it as an artifact.
