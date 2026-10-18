# Timestamp Timelapse

Native Android app prototype: capture interval photos and export a portrait MP4 with the local capture clock (`HH:MM:SS`, 24-hour) burned into every frame.

**Status: TTL-001 implementation, not release-ready.** Android **16+ (API 36)** is the intended support floor; minimum/compile/target SDK are 36. Fresh offline checks: debug and instrumentation APKs compile, **16 host tests pass**, and lint has **0 errors / 16 warnings**. The debug APK has been installed on the authorized Pixel 8 Pro without clearing data. Automated device tests are blocked by a missing offline Gradle UTP dependency; formal app UAT remains pending. Mandatory runtime validation is Pixel 8 Pro / GrapheneOS Android 17 / API 37; OPPO and API 36 runtime coverage remain deferred. See [current results and warning dispositions](docs/sessions/d-4582d3.md), [remaining work](docs/FOLLOW_UP.md), and the [historical prototype handoff](docs/sessions/d-1044a6.md). Android 10–15 compatibility is no longer in scope.

## Implemented

- Pre-session rear/front CameraX selection, fixed through pause/recovery until confirmed session deletion; intervals: 1, 2, 5, 10, 30, or 60 seconds.
- Versioned lens metadata; legacy v1 sessions remain rear-only. Unsupported/malformed metadata blocks capture with a recovery error rather than hiding photos or selecting another session.
- Timestamp at the **shutter request** in the session's original time zone. This is not sensor-exposure timing or elapsed video time.
- EXIF orientation normalization, portrait center crop, front-scene reflection before burning a readable lower-left timestamp into JPEG pixels. CameraX handles front-preview mirroring; export does not mirror/stamp saved photos again.
- Private, persistent session photos; resume capture, re-export, or explicitly delete photos/start a new session.
- Surface-input H.264 encoder using MediaCodec + OpenGL ES and MediaMuxer; 720 × 1280, 15/24/30 fps, no audio.
- Export through MediaStore to `Movies/TimestampTimelapse` without broad storage permissions. Durable attempt records reconcile only exact app-owned unfinished output; published videos and completed JPEGs are never recovery-cleanup targets.
- Permission/unavailable-lens errors block capture without fallback, independently of saved-photo export. Ambiguous export ownership is retained/reported, not guessed.
- Capture pauses when leaving the foreground; screen stays on while capturing/exporting.

Example: at a 5-second capture interval and 30 fps, 300 photos represent approximately 25 minutes of real time in a 10-second video.

## Build

Use a **full JDK 17** and existing Android SDK platform **36** / build-tools **35.0.0**, with the pinned Gradle/AGP/Kotlin and cached dependencies. A minimal Java runtime is insufficient. Set `JAVA_HOME` and `ANDROID_HOME` (or configure `sdk.dir` in ignored `local.properties`). TTL-001 checks use the exact approved host paths recorded in [current results](docs/sessions/d-4582d3.md); do not install/upgrade tools or fetch missing dependencies under the current offline-only decision.

```sh
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false :app:testDebugUnitTest :app:assembleDebug
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false :app:assembleDebugAndroidTest
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false :app:lintDebug
```

These are implementation phase checks, not the independently approved full review matrix. Device tests require the approved explicit Pixel identity-selection wrapper and isolated install/test authorization; do not use an implicit device or substitute a direct instrumentation invocation to bypass the missing offline dependency.

Local build artifacts (not committed):

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Unit test report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint report: `app/build/reports/lint-results-debug.html`

APK installation and app UAT remain separately authorized, explicitly selected-device operations. Do not clear/uninstall app data, fill phone storage, or delete source photos/published videos as a test workaround.

## ADB development shell (TTL-002)

This repo provides a pinned **ADB-only** Nix shell for **x86_64-linux** using
nixpkgs `android-tools`. It does not provision the full Android SDK, JDK, Android
Studio or emulator; the [build prerequisites](#build) above still apply.
Application compatibility, front/rear capture and export validation belong to
**TTL-001**. Discovering a phone does not validate the app or authorize installation.

### Enter, check and exit

Prerequisites: an existing Nix installation with store/daemon access and the
`nix-command` and `flakes` experimental features. First entry may need network
access to fetch the locked nixpkgs source and package closure from a binary cache
(or build it). No host-specific SDK path is needed for `adb`.

From the repository root, if those features are already enabled:

```sh
nix develop --no-update-lock-file --no-write-lock-file
command -v adb  # Expected: /nix/store/.../bin/adb
adb version
adb devices -l
exit
```

Otherwise enable the features for this invocation only:

```sh
nix --extra-experimental-features 'nix-command flakes' develop --no-update-lock-file --no-write-lock-file
```

For a one-shot check without an interactive shell:

```sh
nix --extra-experimental-features 'nix-command flakes' develop --no-update-lock-file --no-write-lock-file . --command adb version
nix --extra-experimental-features 'nix-command flakes' develop --no-update-lock-file --no-write-lock-file . --command adb devices -l
```

`flake.lock` pins the input revision and NAR hash; do not update it to work around
a failed check. Shell entry does not start ADB or alter host/device configuration.
Running device discovery may start the ordinary user-level ADB server and create
ADB keys/cache in your home directory. An empty list proves only command execution,
not phone authorization or compatibility.

### Authorize and select a physical phone

Use a data-capable USB cable and port. On the phone, deliberately enable Developer
options and ordinary USB debugging, unlock it, and accept the RSA trust prompt
only for this trusted computer. Pixel 8 running GrapheneOS and OPPO Reno13 F 5G
must each be checked separately. Do not unlock the bootloader, flash, disable
GrapheneOS protections or install an APK for this tooling check.

Inside the shell, list devices and identify the intended phone yourself. Never
choose an arbitrary row when multiple phones or emulators are present. Replace
the placeholder with that phone's actual listed serial:

```sh
adb devices -l
export ANDROID_SERIAL='REPLACE_WITH_SELECTED_PHONE_SERIAL'
adb -s "$ANDROID_SERIAL" get-state  # Must print exactly: device
adb -s "$ANDROID_SERIAL" shell getprop ro.product.model
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.release
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_SERIAL" shell getprop ro.build.display.id
adb -s "$ANDROID_SERIAL" shell getprop ro.build.fingerprint
unset ANDROID_SERIAL
```

Stop if the state is not `device`. Record actual nonempty property values and a
numeric API level; never infer installed Android/API/build versions from a model
name. Repeat with the other phone's explicit serial. Keep evidence locally and
redact serials in shared reports; fingerprints can also identify installed builds.

Operator record template (not executed results):

| Phone | Date | Discovery/state | Actual model | Android release | API | Build display ID | Fingerprint | Result |
|---|---|---|---|---|---|---|---|---|
| Pixel 8 / GrapheneOS | — | Not checked | — | — | — | — | — | Pending |
| OPPO Reno13 F 5G | — | Not checked | — | — | — | — | — | Pending |

Both authorized physical-phone records are required before TTL-002 closure.
Unavailable, unauthorized or offline hardware remains blocked UAT, even when the
shell and discovery command work.

### Troubleshooting boundaries

- **Empty list:** check a known data-capable cable, another data port, phone unlock
  and USB debugging. Re-run `adb devices -l`; no row is not a phone-test pass.
- **`unauthorized`:** unlock the intended phone and deliberately accept its trust
  prompt, then list again. Do not bypass authentication.
- **`offline`:** check the cable/connection and phone state, then retry discovery.
  If it persists, record the state and coordinate with the operator.
- **`no permissions` / USB access errors:** the shell supplies tooling, not host
  USB access. Stop and ask the operator; udev rules, groups, NixOS configuration,
  `sudo` and other host-system changes require separate explicit approval. Do not
  run ADB as root or change USB permissions as a workaround.
- **Multiple devices:** identify the intended serial and use `adb -s` explicitly
  for every query; do not rely on a default device.
- **Competing ADB server/client versions:** record `adb version` and the error;
  coordinate with Android Studio/other ADB workflow owners before any retry that
  could replace their server. Do not automatically kill/restart a shared server.
- **Missing inputs/cache/network:** retain the actual Nix failure, restore approved
  network/cache access, then retry with the same lock. Do not substitute an
  absolute SDK path or silently refresh the lock.

## Use

Grant camera permission and select **rear/front, interval and fps before starting**. Keep the app open and the phone steady, then stop capture and export. The session's camera/settings stay locked while paused or recovered; changing them requires confirmed Delete photos / New session (Cancel retains them). Camera permission is not needed to export valid saved photos. Exported videos remain in Gallery when session photos are deleted. Uninstalling/clearing app data removes private session photos.

This is a foreground-only prototype, with one current session, portrait output, no background service, no raw-photo export, and no sensor-accurate clock calibration. Low storage stops capture, but export needs additional space for a temporary MP4 and its Gallery copy. Retain photos until export has been played back successfully. Device fixture execution, actual front/rear camera/lifecycle/permission tests, all six Pixel capture combinations, single-frame Gallery playback, independent review/CI and human UAT remain outstanding. New media/recovery fixtures compile but have not run. UI polish/localization and release packaging are deferred. On recovery errors, retain the app data and seek metadata/ownership inspection before retrying; never delete unknown files or media to unblock the app.

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

Local repository: `~/git-repos/sinh-x/timestamp-timelapse`. PA project key: `timestamp-timelapse`, ticket prefix `TTL`. The selected TTL-001 feature checkout is based on `develop` at `b9aabdc` (including TTL-002). `main` is older. Sinh requested committing TTL-001 and integrating directly into `develop` as a per-ticket exception to the default main-targeted workflow below; `main` is not part of this integration. No remote action is part of TTL-001 implementation.

Branch structure: **main → develop → feature**. Create feature branches from up-to-date `develop`, but target **`main` for all feature merges**, then fast-forward `develop` from `main`. See [branching strategy and commands](docs/BRANCHING.md).
