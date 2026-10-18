# Timestamp Timelapse

Native Android app prototype: capture interval photos and export a portrait MP4 with the local capture clock (`HH:MM:SS`, 24-hour) burned into every frame.

**Status: initial checkpoint, not release-ready.** Debug and instrumentation APKs compile and 11 unit tests pass. Lint currently fails on three API-30 window-inset calls despite minSdk 29; launching on Android 10 is unsafe until fixed. No device/emulator runtime testing has been performed. See [session handoff](docs/sessions/d-1044a6.md) and [follow-up work](docs/FOLLOW_UP.md).

## Implemented

- Rear-camera preview and capture using CameraX; intervals: 1, 2, 5, 10, 30, or 60 seconds.
- Timestamp at the **shutter request** in the session's original time zone. This is not sensor-exposure timing or elapsed video time.
- EXIF orientation normalization, portrait center crop, readable lower-left timestamp burned into saved JPEG pixels.
- Private, persistent session photos; resume capture, re-export, or explicitly delete photos/start a new session.
- Surface-input H.264 encoder using MediaCodec + OpenGL ES and MediaMuxer; 720 × 1280, 15/24/30 fps, no audio.
- Export through MediaStore to `Movies/TimestampTimelapse` without broad storage permissions.
- Capture pauses when leaving the foreground; screen stays on while capturing/exporting.

Example: at a 5-second capture interval and 30 fps, 300 photos represent approximately 25 minutes of real time in a 10-second video.

## Build

Use Android Studio or a **full JDK 17** and Android SDK platform 35/build-tools 35.0.0. A minimal Java runtime is insufficient. Set `ANDROID_HOME` to your SDK (or configure `sdk.dir` in ignored `local.properties`).

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
# Currently fails until the API-29 compatibility issue is fixed:
./gradlew :app:lintDebug
# Requires an attached device/emulator with a surface-input H.264 encoder:
./gradlew :app:connectedDebugAndroidTest
```

Local build artifacts (not committed):

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Unit test report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint report: `app/build/reports/lint-results-debug.html`

Install the debug APK only on a test device after considering the known compatibility issue:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use

Grant camera permission, select the interval/fps before starting, keep the app open and the phone steady, then stop capture and export. Exported videos remain in Gallery when session photos are deleted. Uninstalling/clearing app data removes private session photos.

This is a foreground-only prototype, with one current session, portrait output, no background service, no raw-photo export, and no sensor-accurate clock calibration. Low storage stops capture, but export needs additional space for a temporary MP4 and its Gallery copy. Retain photos until export has been played back successfully. Device validation, Android-10 inset compatibility, UI polish/localization, lifecycle/storage failure tests, and release packaging remain outstanding.

## Stack and source map

- Kotlin 2.1.20; Android Gradle Plugin 8.11.1; Gradle 8.14 (checksum-pinned wrapper).
- CameraX 1.4.2; AndroidX Activity 1.10.1; ExifInterface 1.4.1.
- Native Android views, Canvas, MediaCodec, GLES 2.0, MediaMuxer, MediaStore.
- `MainActivity.kt`: UI, camera binding, permissions/lifecycle.
- `CaptureViewModel.kt`: capture cadence and background worker/state.
- `SessionStore.kt`: session metadata and atomic completed JPEGs.
- `TimestampRenderer.kt`: image orientation/cropping/stamp.
- `VideoExporter.kt` / `CodecSurface.kt`: MP4 encoder and Gallery publication.
- `CaptureTiming.kt`: clock formatting/cadence/presentation timestamps.

## Repository

Local repository: `~/git-repos/sinh/timestamp-timelapse`. PA project key: `timestamp-timelapse`, ticket prefix `TTL`. Initial implementation is on `feature/no-ticket-android-timelapse`; `develop` and `main` remain at the initialization commit pending review. No remote has been created or pushed.
