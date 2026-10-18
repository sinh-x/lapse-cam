# Follow-up: complete Android compatibility and validate the capture/export pipeline

- PA ticket: **TTL-001** — `opa ticket show TTL-001`
- Project: `timestamp-timelapse`
- Origin: deployment `d-1044a6`
- Priority: high; estimate: M
- State: pending implementation

## First blocker

`./gradlew :app:lintDebug` reports **3 errors and 24 warnings**. All errors are in `MainActivity.kt` around line 113: native `WindowInsets.getInsets`, `WindowInsets.Type.systemBars`, and `WindowInsets.Type.displayCutout` require API 30 while `minSdk` is 29. Replace this path with AndroidX `ViewCompat`/`WindowInsetsCompat` (preferred, retaining Android-10 support) or a tested API guard. Do not merely suppress lint.

## Acceptance criteria

- [ ] Debug build, all 11 existing host unit tests, instrumentation APK compilation, and lint complete successfully without suppressing the API errors.
- [ ] Run `MediaPipelineTest` on a device/emulator supporting surface-input AVC. Verify session recovery/incomplete-file filtering, EXIF normalization/stamp pixels, encoded frame count/PTS and upright color regions. Add a single-frame MP4 duration/playback test.
- [ ] Launch and capture on API 29 and a current Android version; verify edge-to-edge insets, permissions/denial/retry, rear-camera availability and portrait preview framing.
- [ ] Record a short real session at multiple intervals and fps values; confirm actual clock text in exported video, playback speed, orientation, and Gallery location.
- [ ] Verify stop during an in-flight capture, background pause/resume, Activity recreation, process restart, export failure/retry, nearly full storage and deletion confirmation. Preserve recoverable photos on failures.
- [ ] Review export cleanup after process death, stale temporary files/pending MediaStore items, encoder/EGL resource cleanup and single-frame output duration.
- [ ] Review warnings (hardcoded strings, locked orientation/large screens, backup/data-extraction rules, dependency versions); document any intentionally deferred items.
- [ ] Save commands/device details/results; update README status truthfully. No human UAT or production readiness is implied by compilation.

## Starting point

Read `README.md` and `docs/sessions/d-1044a6.md`. Initial code is on `feature/no-ticket-android-timelapse`, not merged into `develop` or `main`. Base follow-up work on that checkpoint (or integrate it into `develop` after review); do not branch from the still-empty `develop` and lose the prototype. No remote exists yet.

On the current NixOS host, builds succeeded with:

```sh
export JAVA_HOME=/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2
export ANDROID_HOME=/home/sinh/android-sdk-writable
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --console=plain --no-daemon
```

The full JDK path is host-specific; prefer Android Studio's JDK or another full JDK 17 elsewhere. The default `java` on this host is a minimal runtime lacking `java.instrument` and cannot run Gradle correctly. ADB reported no attached devices during the originating session.
