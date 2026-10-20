# Timestamp Timelapse

Native Android app prototype: capture interval photos and export a portrait MP4 with the local capture clock (`HH:MM:SS`, 24-hour) burned into every frame.

**Status: TTL-004 implementation candidate; review/UAT pending, not release-ready.** Android **16+ (API 36)**; minimum/compile/target SDK remain 36. Latest candidate `c98ddb3` passes **42 host tests**, both APK builds and lint (**0 errors / 24 warnings**). Supplementary **CPH2699/API36 device validation passes 32/32 methods**, including a 48-export stress matrix with exact decoded counts, PTS and duration checks. Fixture double-deletion errors were corrected. Earlier encoder frame-count failures did not recur; their root cause is **not established or claimed fixed**. See [candidate-bound results and retained failures](docs/FOLLOW_UP.md). The latest app remains installed on OPPO without clear/uninstall; Pixel was left on its earlier manual-UAT build. TTL-003 verified the instrumentation cache offline under an explicitly revised native-resolution procedure. Its earlier supplementary CPH2699/API36 run executed **16 tests: 14 passed, 2 failed**, owned by **TTL-010**; these are not TTL-004 results or Pixel coverage. Mandatory Pixel 8 Pro / GrapheneOS API37 validation, independent review/CI and formal UAT remain pending. See the [offline workflow and approval limitations](docs/OFFLINE_INSTRUMENTATION.md), [warning dispositions and remaining work](docs/FOLLOW_UP.md), [historical TTL-001 results](docs/sessions/d-4582d3.md) and [prototype history](docs/sessions/d-1044a6.md). Android 10–15 compatibility is out of scope.

## Implemented

- New sessions default to **Adaptive / 30-second maximum video / 30 fps**. Set an integer cap of **5–600 seconds**, at **15/24/30 fps**. The cap limits video, **not recording time or storage**.
- Adaptive starts at a nominal **500 ms** cadence. At a frame-budget overflow, keep every other entry across the whole selected list plus the latest endpoint; double cadence up to 60 seconds. All completed JPEGs remain, including unselected photos. Sampling is approximate, not perfectly uniform; the camera is not guaranteed to achieve 2 fps.
- **Fixed** mode preserves 1/2/5/10/30/60-second intervals with no video cap. Rear/front, mode/cap/fps, timestamp appearance and original time zone freeze per session.
- Live HUD: active recording duration, wall-clock span, selected/total counts, saved bytes, video estimate, interval/stage and missed-slot indication. Active time excludes pauses; after interrupted recording, recovery is paused and labels last-saved timing with an unknown-tail warning. Legacy historical active time is unavailable.
- Timestamp appearance panel: Monospace/Sans-serif/Serif, Regular/Bold, 32/44/60 output pixels, six positions and optional `yyyy-MM-dd` above `HH:mm:ss`. Defaults: 44 px bold monospace, bottom-left, date off. Preview and saved output share 720×1280 geometry, independent of UI font scale; saved photos cannot be restyled.
- Versioned lens metadata; legacy v1 sessions remain rear-only. Unsupported/malformed metadata blocks capture with a recovery error rather than hiding photos or selecting another session.
- Timestamp at the **shutter request** in the session's original time zone. This is not sensor-exposure timing or elapsed video time.
- EXIF orientation normalization, portrait center crop, front-scene reflection before burning a readable timestamp into JPEG pixels. CameraX handles front-preview mirroring; export does not mirror/stamp saved photos again.
- Private, persistent session photos; resume capture, re-export, or explicitly delete photos/start a new session.
- Surface-input H.264 encoder using MediaCodec + OpenGL ES and MediaMuxer; 720 × 1280, 15/24/30 fps, no audio.
- Export through MediaStore to `Movies/TimestampTimelapse` without broad storage permissions. Durable attempt records reconcile only exact app-owned unfinished output; published videos and completed JPEGs are never recovery-cleanup targets.
- Permission/unavailable-lens errors block capture without fallback, independently of saved-photo export. Ambiguous export ownership is retained/reported, not guessed.
- Capture pauses when leaving the foreground; screen stays on while capturing/exporting.

Adaptive example: 30s × 30fps gives a 900-frame budget. Photo 901 thins selection to 451 frames (about 15.033s), changes nominal cadence to 1 second, and retains all 901 JPEGs. The first boundary is roughly 7.5 minutes at achieved 2 fps, not a recording cutoff. Fixed example: 300 photos at a 5-second interval give approximately 25 minutes of capture in a 10-second/30fps clip.

## Build

Use a **full JDK 17** and existing Android SDK platform **36** / build-tools **35.0.0**, with the pinned Gradle/AGP/Kotlin and cached dependencies. A minimal Java runtime is insufficient. Set `JAVA_HOME` and `ANDROID_HOME` (or configure `sdk.dir` in ignored `local.properties`). TTL-003 checks retain the exact existing host paths in the [offline runbook](docs/OFFLINE_INSTRUMENTATION.md). Do not install/upgrade tools or fetch missing dependencies without a separate bounded owner approval; prior retrieval approval is not blanket network permission.

```sh
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false :app:testDebugUnitTest :app:assembleDebug
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false :app:assembleDebugAndroidTest
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false :app:lintDebug
```

These are implementation phase checks, not the independently approved full review matrix. Device tests require fresh disposable-context/install/test permission and an explicit exact-identity wrapper. See the [runbook's no-uninstall command correction](docs/OFFLINE_INSTRUMENTATION.md#validation-and-phone-data-boundary) before any device invocation: the original matrix must be reconciled/reapproved. Do not use an implicit device, alternate runner or unapproved device substitution.

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

Grant camera permission and select **rear/front, Adaptive cap or Fixed interval, fps and timestamp appearance before starting**. Valid choices are remembered for later new sessions. Keep the app open and the phone steady, then stop capture and export. The session's camera/settings stay locked while paused or recovered; changing them requires confirmed Delete photos / New session (Cancel retains them). Camera permission is not needed to export valid saved photos. Exported videos remain in Gallery when session photos are deleted. Uninstalling/clearing app data removes private session photos.

This is a foreground-only prototype, with one current session, portrait output, no background service, no raw-photo export, and no sensor-accurate clock calibration. Below 50 MiB free space, capture pauses before another shutter. Export checks 50 MiB plus twice the estimated MP4 size at 5 Mbps with 25% overhead; this is a headroom estimate, not a storage reservation. Insufficient headroom blocks export with photos retained for retry. Retain photos until export has been played back successfully. Mandatory Pixel fixture execution, actual front/rear camera/lifecycle/permission tests, all six Pixel capture combinations, single-frame Gallery playback, independent review/CI and human UAT remain outstanding. TTL-003's supplementary API36 media/recovery run had two failures; see the offline runbook and TTL-010 follow-up. New TTL-004 fixture results must be tied to the rebased candidate. UI polish/localization and release packaging are deferred. On recovery errors, retain the app data and seek metadata/ownership inspection before retrying; never delete unknown files or media to unblock the app.

## Stack and source map

- Kotlin 2.1.20; Android Gradle Plugin 8.11.1; Gradle 8.14 (checksum-pinned wrapper).
- CameraX 1.4.2; AndroidX Activity 1.10.1; ExifInterface 1.4.1.
- Native Android views, Canvas, MediaCodec, GLES 2.0, MediaMuxer, MediaStore.
- `MainActivity.kt`: UI, camera binding, permissions/lifecycle.
- `CaptureViewModel.kt`: capture cadence and background worker/state.
- `SessionStore.kt`: legacy/v3 metadata, durable frame intent/checkpoints, replay and retained atomic JPEGs; no full-history scans during steady saves.
- `AdaptiveSampling.kt`: pure bounded whole-list selection and frozen sampling configuration.
- `TimestampAppearance.kt`: frozen style values, monotonic active clock and checked export-space arithmetic.
- `TimestampLayout.kt` / `TimestampRenderer.kt`: shared preview/output geometry and image orientation/cropping/stamp.
- `VideoExporter.kt` / `CodecSurface.kt`: MP4 encoder and Gallery publication.
- `CaptureTiming.kt`: clock formatting/cadence/presentation timestamps.

## Repository

Local repository: `~/git-repos/sinh-x/timestamp-timelapse`. PA project key: `timestamp-timelapse`, ticket prefix `TTL`. The TTL-004 candidate is on `feature/TTL-004-adaptive-timestamp`, rebased onto `develop` at `170dda2` (including TTL-003), with the Phase 1 contract originally committed as `78b903d`. `main` is older. Sinh requested committing TTL-001 and integrating directly into `develop` as a per-ticket exception to the default main-targeted workflow below; `main` is not part of this integration. No remote action is part of TTL-001 implementation.

Branch structure: **main → develop → feature**. Create feature branches from up-to-date `develop`, but target **`main` for all feature merges**, then fast-forward `develop` from `main`. See [branching strategy and commands](docs/BRANCHING.md).
