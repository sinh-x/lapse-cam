# Timestamp Timelapse

Native Android app prototype: capture interval photos and export a portrait MP4 with the local capture clock (`HH:MM:SS`, 24-hour) burned into every frame.

Product exploration: [output-first work sessions, discussion record, and session-setup proposal](docs/OUTPUT_FIRST_WORK_SESSIONS.md) (planned, not implemented).

Platform exploration: [Linux / NixOS webcam timelapse ideas](docs/LINUX_WEBCAM_EXPLORATION.md) (exploration only; no ticket or implementation).

**TTL-014 owner-accepted as-is for local `develop` integration (2026-10-31):** private native Go archive/browser, durable Android sync/shared history, enrollment feedback, browser-origin fix and reusable Nix package/module. Pre-merge local checks: 85/85 host tests, both APKs, lint 0 errors/72 warnings; server tests/vet and offline package/module checks pass. **This is acceptance with known gaps, not full UAT or production readiness.** Host package adoption/browser verification remain in **NX-067**; independent two-phone/server validation, CI and human UAT are deferred. [Decision, actual evidence and limits](docs/sessions/d-0d4044-ttl014-closeout.md) · [implementation history](docs/TTL014_IMPLEMENTATION.md) · [validation](docs/TTL014_VALIDATION.md) · [operator draft](docs/PRIVATE_SYNC_SERVER.md) · [protocol](server/PROTOCOL.md).

**TTL-011 implementation candidate (not accepted):** preview-first Camera UI with grouped settings, model-driven LIVE, separate Work setup/Start/End timeboxes, durable drafts and capture-time recording links. Local offline checks: **67/67 host**, both debug APKs and lint pass; **80 Android methods compile, with 12 isolated store/execution/link methods passing on Pixel** (not full80 execution). Installed UI follow-up fixes Work keyboard/Back navigation and reminder-permission feedback. **Archive/Restore and confirmed Delete** now manage planned/ended timeboxes without deleting photos/videos; [behavior, migration and checks](docs/TTL011_ARCHIVE_DELETE.md). Targeted Pixel Work checks include real expiry and a user-confirmed audible end notification with DND off; see [actual outcomes and limits](docs/TTL011_UI_CHECKS.md). Full device UAT, independent matrix/review and matching GitHub CI remain outstanding; no push/merge. See [implementation/limits](docs/TTL011_IMPLEMENTATION.md) and [current phase evidence](docs/FOLLOW_UP.md#ttl-011-local-implementation-not-acceptance). Results below describe earlier tickets, not TTL-011 acceptance.

**TTL-006 focused persistence validation (implementation checks only):** shared defaults adapter and five new Android defaults/Activity-recreation tests; core persistence/sampling/retention/export behavior is unchanged. Offline host **50/50**, both APK builds and lint (**0 errors / 26 warnings**) pass; accounting self-tests **25/25** pass. Android inventory is **58 methods**. The authorized Pixel defaults run executed **5: 3 passed, 2 failed safe admission guards** because existing defaults/sessions prevent Activity fixture isolation. Both current APKs are installed and their on-device hashes match; existing data was retained. Work is on `feature/TTL-006-persistence-validation`; full Pixel regression execution, clean-candidate L1, independent review and all ten human UAT sign-offs (including separately permitted actual termination/reboot) remain outstanding. No acceptance, clearing/uninstall or actual-loss operation is claimed. Hosted CI is N/A for this ticket. See [current evidence and warning dispositions](docs/FOLLOW_UP.md#ttl-006-focused-persistence-validation-implementation-checks) and [loss-evidence procedure](docs/PERSISTENCE_VALIDATION.md).

**Prior status: TTL-005 implemented and locally validated; owner-directed local `develop` closeout (2026-10-21).** The observed unknown-sidecar bug is fixed without weakening deletion checks. Host tests **50/50 pass**; full authorized OPPO CPH2699/API36 instrumentation **53/53 passes**, failures/errors/skips0. Both APKs build; lint **0 errors / 26 warnings**. Unknown sidecars remain untouched and block cleanup; corrupt reserved frame metadata still blocks recovery, and future capture cannot overwrite an unknown sidecar. Both updated APKs remain installed without clear/uninstall. New Adaptive sessions offer **Off / 2h / 3h (default)** splitting, Pause/Finish, concurrent export/verified cleanup, retries and Sessions review. Independent review and physical UAT remain pending. See [passing fix verification and retained original failure](docs/FOLLOW_UP.md#ttl-005-sidecar-fix-and-passing-oppo-rerun-2026-10-21).

**Historical TTL-004 baseline (not TTL-005 test evidence):** Android **16+ (API 36)**; minimum/compile/target SDK remain 36. Latest candidate `c98ddb3` passes **42 host tests**, both APK builds and lint (**0 errors / 24 warnings**). Supplementary **CPH2699/API36 device validation passes 32/32 methods**, including a 48-export stress matrix with exact decoded counts, PTS and duration checks. Fixture double-deletion errors were corrected. Earlier encoder frame-count failures did not recur; their root cause is **not established or claimed fixed**. See [candidate-bound results and retained failures](docs/FOLLOW_UP.md). The latest app remains installed on OPPO without clear/uninstall; Pixel was left on its earlier manual-UAT build. TTL-003 verified the instrumentation cache offline under an explicitly revised native-resolution procedure. Its earlier supplementary CPH2699/API36 run executed **16 tests: 14 passed, 2 failed**, owned by **TTL-010**; these are not TTL-004 results or Pixel coverage. Mandatory Pixel 8 Pro / GrapheneOS API37 validation, independent review/CI and formal UAT remain pending. See the [offline workflow and approval limitations](docs/OFFLINE_INSTRUMENTATION.md), [warning dispositions and remaining work](docs/FOLLOW_UP.md), [historical TTL-001 results](docs/sessions/d-4582d3.md) and [prototype history](docs/sessions/d-1044a6.md). Android 10–15 compatibility is out of scope.

## Implemented

- New sessions default to **Adaptive / 30-second maximum video / 30 fps**. Set an integer cap of **5–600 seconds**, at **15/24/30 fps**. The cap limits video, **not recording time or storage**.
- Adaptive starts at a nominal **500 ms** cadence. At a frame-budget overflow, keep every other entry across the whole selected list plus the latest endpoint; double cadence up to 60 seconds. All completed JPEGs, including unselected photos, remain until verified cleanup or confirmed manual deletion. Sampling is approximate, not perfectly uniform; the camera is not guaranteed to achieve 2 fps.
- **Adaptive auto-split:** Off/2h/3h, default 3h for new sessions only. Active time excludes pauses/downtime. At the boundary, drain the already-started photo into its original folder and publish one successor with inherited settings, a fresh selector and independent cap. The 1Hz boundary check does not depend on the adaptive shutter interval.
- **Pause versus Finish:** Pause/Resume preserves a split-enabled session. Finish closes it and queues export even below the limit; empty sessions create no MP4. Off/Fixed/legacy keep Stop-as-pause and explicit manual export.
- **Serial automatic export alongside capture:** a separate media worker reconciles a stable operation UUID before encoding. Reopen the exact app-owned published Gallery output, validate tracks/count/PTS/duration/cap and fully decode it before recording a durable receipt. Only then clean that closed session's exact inventoried JPEGs/sidecars; unknown files, aliases or output uncertainty stop cleanup. Published videos are never deleted by the app.
- **Bounded recovery:** three export attempts, with app-open retry delays of 10s/30s, then Needs attention with sources retained. Cleanup has its own bounded cycle and never re-encodes a proven output. Explicit Retry starts a fresh cycle. Sessions shows retained backlog, errors, progress, exact Open and confirmed per-session source Delete.
- **Fixed** mode preserves 1/2/5/10/30/60-second intervals with no video cap. Rear/front, mode/cap/fps, timestamp appearance and original time zone freeze per session.
- Preview-first Camera: separate Work card, settings gear/bottom sheet, app-only state-driven LIVE, circular Start/Pause/Resume and separate split Finish. Work/settings/Sessions navigation keeps the camera surface mounted; actual backgrounding still pauses. Work minutes are independent of the MP4 cap.
- **Blackout while recording (TTL-015):** after 30 seconds of eligible foreground inactivity, an opaque black app cover shows a compact red “LIVE — recording” banner. Status/navigation bars hide while covered and return with Camera; Android may still expose privacy indicators or transient bars on an edge swipe. The first complete touch gesture, Back, Enter/Space or accessible Return action only returns to Camera; recording continues. Camera settings → Capture has a saved, default-on “Blackout screen while recording” option, editable during recording. Dialogs, focus loss, errors and non-live states suspend the cover. This is not screen-off/background recording or brightness dimming; the preview remains mounted. Black pixels may help OLED display power, not necessarily LCD backlights; no measured battery or reduced camera-work claim. The static indicator carries OLED burn-in risk. [Implementation/checks and remaining device UAT](docs/TTL015_IMPLEMENTATION.md).
- Work: validated durable intention/draft, explicit independent Start/End, deadline expiry with permission-aware reminders, same-boot recovery/reboot interruption, copy-as-new and capture-time overlap details. No work Pause/Extend, journal, video overlay or automatic camera action. See [semantics and remaining validation](docs/TTL011_IMPLEMENTATION.md).
- Recording details: active recording duration, wall-clock span, selected/total counts, saved bytes, video estimate, interval/stage and missed-slot indication. Active time excludes pauses; after interrupted recording, recovery is paused and labels last-saved timing with an unknown-tail warning. Legacy historical active time is unavailable.
- Timestamp appearance panel: Monospace/Sans-serif/Serif, Regular/Bold, 32/44/60 output pixels, six positions and optional `yyyy-MM-dd` above `HH:mm:ss`. Defaults: 44 px bold monospace, bottom-left, date off. Preview and saved output share 720×1280 geometry, independent of UI font scale; saved photos cannot be restyled.
- Versioned lens metadata; legacy v1 sessions remain rear-only. Unsupported/malformed metadata blocks capture with a recovery error rather than hiding photos or selecting another session.
- Timestamp at the **shutter request** in the session's original time zone. This is not sensor-exposure timing or elapsed video time.
- EXIF orientation normalization, portrait center crop, front-scene reflection before burning a readable timestamp into JPEG pixels. CameraX handles front-preview mirroring; export does not mirror/stamp saved photos again.
- Private, persistent session photos; resume capture, re-export, or explicitly delete photos/start a new session.
- Surface-input H.264 encoder using MediaCodec + OpenGL ES and MediaMuxer; 720 × 1280, 15/24/30 fps, no audio.
- Export through MediaStore to `Movies/TimestampTimelapse` without broad storage permissions. Durable attempt records reconcile only exact app-owned unfinished output; published videos are never cleanup targets. Automatic source cleanup requires explicit split opt-in and a matching verified receipt.
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

## NixOS private sync server

Use this repository as a flake input for `packages.<system>.timelapse-server` and
`nixosModules.timelapse-server`. See [installation instructions](docs/NIXOS_INSTALL.md)
and the [disabled-by-default consumer example](examples/nixos/timelapse.nix).
This packaging does not activate a host. TTL-014's owner acceptance does not complete
NX-067 deployment or the deferred validation checks.

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

Open **Work** to prepare/save an intention, then explicitly Start it if wanted; saving starts neither countdown nor camera. Work and video are independent. Camera's **gear** opens Capture/Timestamp settings. Grant camera permission and select **rear/front, Adaptive cap or Fixed interval, fps and timestamp appearance before starting**. Valid choices are remembered for later new sessions. Keep the app open and the phone steady. With Adaptive 2h/3h, use Pause to resume later or Finish to automatically export and verify/clean that segment. With Off/Fixed/legacy, stop capture and export manually. Open Sessions for closed-session status, retries and individually confirmed source deletion. The session's camera/settings stay locked while paused or recovered; Finish a split-enabled session to start a new one, or use confirmed source Delete (Cancel retains it). Camera permission is not needed to export valid saved photos. Exported videos remain in Gallery when session photos are deleted. Uninstalling/clearing app data removes private session photos.

This is a foreground-only prototype, with one current capture owner plus a persistent closed-session queue, portrait output, no background service, no raw-photo export, and no sensor-accurate clock calibration. Below 50 MiB free space, capture pauses before another shutter. Export checks 50 MiB plus twice the estimated MP4 size at 5 Mbps with 25% overhead; this is a headroom estimate, not a storage reservation. Insufficient headroom blocks export with photos retained for retry. Off/Fixed/legacy retain photos until explicit deletion; play their exports before deleting. Split-enabled sessions automatically remove sources only after software verification of the published output; if that video is later removed or damaged, already-deleted photos cannot be restored. Mandatory Pixel fixture execution, actual front/rear camera/lifecycle/permission tests, all six Pixel capture combinations, single-frame Gallery playback, independent review/CI and human UAT remain outstanding. TTL-003's supplementary API36 media/recovery run had two failures; see the offline runbook and TTL-010 follow-up. New TTL-005 fixture results must be tied to an unchanged candidate; earlier TTL-004/OPPO results do not validate this implementation. UI polish/localization and release packaging are deferred. On recovery errors, retain the app data and seek metadata/ownership inspection before retrying; never delete unknown files or media to unblock the app.

## Stack and source map

- Kotlin 2.1.20; Android Gradle Plugin 8.11.1; Gradle 8.14 (checksum-pinned wrapper).
- CameraX 1.4.2; AndroidX Activity 1.10.1; ExifInterface 1.4.1.
- Native Android views, Canvas, MediaCodec, GLES 2.0, MediaMuxer, MediaStore.
- `MainActivity.kt`: UI, camera binding, permissions/lifecycle.
- `WorkSession.kt` / `WorkSessionStore.kt` / `WorkSessionCoordinator.kt`: independent work policy, atomic private draft/work/span context and single serialized process owner.
- `WorkSessionsView.kt` / `WorkDialog.kt` / `TimeboxAlarmScheduler.kt`: IME-capable native Work surfaces, archive/restore/confirmed metadata deletion, identity-bound alarms/receiver and generic reminders.
- `TimeboxReminderAccess.kt`: independent notification/background permission status and actionable labels (not a DND bypass).
- `CameraUiPolicy.kt` / `CameraSettingsSheet.kt`: pure LIVE/action projection and native grouped settings sheet.
- `BlackoutPolicy.kt`: Activity-owned monotonic idle/eligibility policy, callback generations and one-shot timebox notices; no capture or media ownership.
- `tools/verify_timebox_results.py` / `.github/workflows/android.yml`: TTL-011 artifact accounting and separate hosted host/build/lint checks (neither is human UAT).
- `CaptureViewModel.kt`: capture cadence, 1Hz boundary admission/drain, foreground eligibility and separate file/media workers.
- `SessionLifecycle.kt`: pure split, retry-cycle and fair due-work policies.
- `SessionRepository.kt`: atomic index, stable successor identities, frozen manifests, receipts and exact resumable inventory cleanup.
- `SessionExportQueue.kt`: app-open serial export/verification, durable attempts and cleanup-only retry.
- `SessionReview.kt` / `SessionsView.kt`: compact review values and native session rows/actions.
- `tools/verify_session_results.py`: standard-library self-tests and fail-closed independent report accounting; never invokes Gradle/ADB or operates a phone.
- `SessionStore.kt`: legacy/v3 metadata, durable frame intent/checkpoints, replay and retained atomic JPEGs; no full-history scans during steady saves.
- `AdaptiveSampling.kt`: pure bounded whole-list selection and frozen sampling configuration.
- `TimestampAppearance.kt`: frozen style values, monotonic active clock and checked export-space arithmetic.
- `TimestampLayout.kt` / `TimestampRenderer.kt`: shared preview/output geometry and image orientation/cropping/stamp.
- `VideoExporter.kt` / `CodecSurface.kt`: MP4 encoder and Gallery publication.
- `CaptureTiming.kt`: clock formatting/cadence/presentation timestamps.

## Repository

Local repository: `~/git-repos/sinh-x/timestamp-timelapse`. PA project key: `timestamp-timelapse`, ticket prefix `TTL`. TTL-005 was developed on `feature/TTL-005-session-rollover`, rebased onto local `develop` at `cf4e76deeca9dffc2d2f0df8cab63c7e36f9d4c3`. Sinh explicitly directed its local commit, merge into develop and administrative ticket closure (`c-20261021200436743`); final Git/closure receipts are recorded in the TTL-005 ticket and PA session log. The feature checkout/branch and safety stash are retained; no push or checkout return is included. Historically, the TTL-004 candidate is on `feature/TTL-004-adaptive-timestamp`, rebased onto `develop` at `170dda2` (including TTL-003), with the Phase 1 contract originally committed as `78b903d`. `main` is older. Sinh requested committing TTL-001 and integrating directly into `develop` as a per-ticket exception to the default main-targeted workflow below; `main` is not part of this integration. No remote action is part of TTL-001 implementation.

Branch structure: **main → develop → feature**. Create feature branches from up-to-date `develop`, but target **`main` for all feature merges**, then fast-forward `develop` from `main`. See [branching strategy and commands](docs/BRANCHING.md).
