# TTL-004: implementation candidate → independent review / UAT

## Rebase and connected-phone phase (2026-10-20)

At Sinh's request, rebased the ticket worktree onto updated local `develop` at **170dda221be2018a32b83f1020d2723992a1c168** (TTL-003 tooling). No remote is configured/fetched. Rebase mapped Phase 1 `78b903d` → `ae3f4fc` and integration `9c5d36f` → **8787776621ec6a074ba768b41619c41d80a8ac20**. Only README content conflicted; resolution retained both TTL-003's actual cache/supplementary outcomes and TTL-004's behavior/status. App/test/build/flake/lock bytes are identical to the pre-rebase integration; TTL-003 helper/runbook are retained from develop.

**Tested runtime/source candidate: 8787776621ec6a074ba768b41619c41d80a8ac20.** Any following results-documentation commit changes only documentation, not the tested APK/source bytes. Fresh offline phase host command above/below with `--rerun-tasks`: **exit0, 42 host tests, 0 failures/errors/skips, both APKs assembled, lint0 errors/24 warnings**.

Fresh read-only discovery found exactly one authorized **CPH2699 / Android16 / API36 / CPH2699_16.0.5.1200(EX01)**, not the mandatory Pixel. SDK and locked-Nix ADB clients/server all use protocol41/version37.0.0; no shared-server restart. Sinh selected **Run all 31 tests, then leave app installed**, confirming a disposable app context and stopped cache writers. This authorizes supplementary phase testing, not replacement of Pixel/matrix/UAT acceptance.

Actual device invocation retained pinned offline Gradle/UTP and all three named classes:

```text
:app:connectedDebugAndroidTest --rerun-tasks
-Pandroid.builder.sdkDownload=false
-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false
-Pandroid.testInstrumentationRunnerArguments.class=com.sinh.timelapse.MediaPipelineTest,com.sinh.timelapse.AdaptiveSessionTest,com.sinh.timelapse.TimestampAppearanceTest
```

The wrapper reasserted exact candidate, clean source inventory, single device/model/API/build and explicit selected serial (not printed) before Gradle. Connected-results/report directories were absent before the run; marker `app/build/reports/ttl004-device-start-8787776.txt` precedes fresh XML. A first command was rejected before execution because its redirected log target was dynamic; after creating a verified owner-only system-temp log, the actual Gradle/device invocation ran **once**. Original output retained at `/tmp/ttl004-device-8787776.GleOLs.log` (owner-only); serial-redacted output is in the deployment native transcript. Do not publish raw serial-bearing XML/log paths.

| Device suite | Executed | Passed | Failed | Errors/skips |
|---|---:|---:|---:|---:|
| MediaPipelineTest (unchanged original methods) | 16 | 14 | 2 | 0/0 |
| AdaptiveSessionTest | 11 | 10 | 1 | 0/0 |
| TimestampAppearanceTest | 4 | 4 | 0 | 0/0 |
| **Total** | **31** | **28** | **3** | **0/0** |

Gradle exited **1** due to failed tests, not dependency resolution. Exact fresh XML method-set equality against all31 source methods, no duplicates/missing cases, was verified. A failed method stops its internal loop: do not claim every fps/count vector executed merely because all methods were discovered.

Failures retained without suppression or automatic repair/retry:

- `MediaPipelineTest.ambiguousOwnershipAndCleanupFailureRetainRecordsForExplicitRetry`: MediaStore `SecurityException` in fixture `finally` cleanup at line600. Reproduces the existing TTL-010 finding; a cleanup exception may mask an earlier fixture result.
- `MediaPipelineTest.interruptedExportsCleanOnlyTheirPendingOutputsAndPreservePublishedVideos`: `Encoder produced an unexpected frame count` at `VideoExporter.kt:286`, called by test line550. Reproduces the existing TTL-010 symptom.
- `AdaptiveSessionTest.adaptiveMediaUsesExactlyFrozenSelectionAtEveryFpsWithoutModifyingPhotos`: same encoder frame-count check at `VideoExporter.kt:286`, test line320. Newly exercised TTL-004 coverage; likely related path, **not a proven common root cause or a proven rebase regression**. Bounded encoder diagnosis/repair and candidate-bound rerun remain required.

All four timestamp methods passed (216 safe layouts, legacy pixel equality, date/DST/frozen zone, shared preview geometry). Adaptive session recovery/retention/atomicity/counting/fake-time/storage tests passed; adaptive MP4 coverage remains failing. Application package presence was confirmed after the run; the updated app remains installed for hands-on testing. No app-data clear/uninstall, real capture, unrelated phone-media deletion, security change or shared ADB replacement was performed. Fixtures target isolated roots and exact self-created video URIs; no phone-wide preservation inventory or human playback/camera pass is inferred.

Tested APK SHA-256:
- Debug: `f18cf851634de9328518bab6294a67906b6b24ad91abd3a922b530225e58c26d`.
- AndroidTest: `c1b9c2b402ece588e1c605062d8cbc0859810b82d67f58df7fc46a24e53e7ef8`.

Local device report: `app/build/reports/androidTests/connected/debug/index.html`; original XML tree: `app/build/outputs/androidTest-results/connected/debug/`. Preserve failed outputs before any newly authorized rerun. Mandatory Pixel, actual both-lens capture/Gallery playback, full independent review/matrix/CI and human TS1–16 sign-off remain pending. TTL-003's original receipt pins pre-TTL-004 source/method sets and cannot be relabelled as this candidate's unchanged receipt or31-method result authority.

## Initial handoff (d-e2d1fe, 2026-10-20; pre-rebase results)

Implemented on the selected ticket worktree, **not merged, installed, released or accepted**:

- Branch `feature/TTL-004-adaptive-timestamp`; approved base `d753424a7643993a3514626726e58030c48ea093`; existing Phase 1 commit `78b903ddcee1c341005d3d7b8a49b3943c3830ed` preserved.
- Candidate is the commit containing this handoff; exact final SHA is recorded in the TTL-004 deployment/ticket handoff. Identify it with `git rev-parse HEAD`; review/UAT must bind that unchanged candidate, not the original Phase 1 SHA.
- V3 sessions freeze sampling, cap/fps, lens, zone and appearance. V1/v2 readers and exact legacy timestamp geometry remain. Legacy capture uses cached counts/high water; its historical active time is explicitly unavailable.
- Durable pre-shutter ID reservation and per-frame provenance precede atomic JPEG rename. One atomic progress checkpoint commits total count/bytes, selection/stage and known active time. Startup replays identifiable completed JPEGs once; contradictory/missing provenance blocks recovery without older-session fallback or completed-photo deletion. Incomplete camera/JPEG files are not selected. Per-frame intents are retained; storage is deliberately unbounded by the video cap.
- Steady saves use cached metadata and bounded selection, not historical directory scans. Startup reconciles history; Fixed export scans once, Adaptive export resolves selected provenance only.
- Monotonic active time advances before photos complete, excludes pause/background/export and is checkpointed periodically (5s), at frame commit and normal pause. Recovery is paused; a previously running checkpoint adds a durable unknown-interruption-tail warning, never downtime. Wall span is independently wall-clock based and clamped nonnegative.
- Export snapshots the committed selection, checks cap/uniqueness/order/files and headroom, then verifies actual encoded track/sample count, PTS and reported duration/cap **before publication**. Existing exact-owned pending-output recovery remains intact; published media is never a cleanup target.
- Scrollable controls/HUD, remembered new-session settings, frozen timestamp panel and shared output-space preview support the approved Adaptive/Fixed/style/date options. Camera/lifecycle safeguards remain; pause permits at most the already in-flight completion.

### Phase checks actually executed

Existing full JDK `/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2`, SDK `/home/sinh/android-sdk-writable`, unchanged pinned dependencies, offline only:

```sh
JAVA_HOME=/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2 \
ANDROID_HOME=/home/sinh/android-sdk-writable \
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --rerun-tasks
```

These are implementation-phase checks, **not execution of the independent six-command matrix**. Original command output/exits are in the deployment's native tool/activity transcript. The initial run and fresh reruns passed; no compiler/test/lint failure was hidden or repaired by changing tolerances, versions, permissions or runners.

| Evidence | Actual result |
|---|---|
| `AdaptiveSamplingTest` | 14 host cases passed |
| `CaptureTimingTest` | 22 host cases passed; original 16 host cases preserved |
| `SessionContractTest` | 6 host cases passed: active/pause/recovery arithmetic, headroom limits/overflow, 216 style value combinations |
| Fresh host XML total | **42 tests; 0 failures, 0 errors, 0 skipped** |
| Debug + instrumentation APK | Both assemble successfully; this is compilation, not device execution |
| Lint XML | **0 errors / 24 warnings**, unsuppressed; dispositions below are implementer proposals |
| Device fixture source | Original `MediaPipelineTest` 16 methods unchanged; `AdaptiveSessionTest` 11 and `TimestampAppearanceTest` 4 added; **31 methods compiled, all unrun** |
| Whitespace / configuration | `git diff --check` passes; no manifest, dependency, SDK, Nix/Gradle lock, runner or permission changes |
| Independent review / matrix / CI | **Not run** |
| Pixel installation / capture / app UAT | **Not performed** in d-e2d1fe; historical TTL-001 installation is not candidate evidence |

Local fresh outputs: `app/build/test-results/testDebugUnitTest/TEST-*.xml`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.xml` and `.html`, `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. They are ignored build artifacts, not committed test evidence or browser observations.

### Coverage added, runtime proof outstanding

| Contract | Fixtures / review surface | Remaining proof |
|---|---|---|
| Caps/fps/boundaries, seven-day selection/gap/replay | Existing Phase 1 host suites | Independent rerun and physical achieved cadence |
| Retention, exact thinning, no steady scans | AdaptiveSessionTest hashing/counting fixture | Execute fixture; observe actual device I/O/cadence |
| Crash before/after reserve, intent, atomic checkpoint, JPEG rename and commit | AdaptiveSessionTest interruption/replay fixtures | Execute on Android AtomicFile/filesystem; deliberate abrupt-loss injection is not real process-kill proof |
| Missing/unknown/contradictory metadata, frozen config and legacy behavior | AdaptiveSessionTest + unchanged MediaPipelineTest | Execute all device cases and lifecycle/reboot UAT |
| 10min active / 5min pause / 10min active, slow in-flight camera | Pure clock plus fake-time live-model fixture | Host clock passed; Android model/HUD fixture unrun; actual display refresh/missed slots need measurement |
| Space below/at threshold, overflow, export failure | Host arithmetic + isolated fake-space device fixtures | Host arithmetic passed; Android I/O/retry fixtures unrun; never fill real storage |
| One/two/full/thinned output at 15/24/30fps, PTS/count/duration | AdaptiveSessionTest + original media tests; pre-publication validator | Real codec/container results and playback unrun |
| All 216 safe layouts, legacy pixel equality, date/DST/zone, shared preview | TimestampAppearanceTest + original EXIF/lens fixtures | Execute raster/layout fixtures and actual preview-vs-photo/video comparison |
| 360dp / 200% font scale / accessibility | Scrollable vertical native controls, multiline spinner values, non-live timer HUD | **Manual operability and TalkBack assessment pending**; not proven by assembly/lint |

### Lint dispositions proposed for independent review

| Warning ID | Count | Rationale / remaining debt |
|---|---:|---|
| GradleDependency | 2 | Existing approved dependency pins retained; no upgrade/download authorized |
| LockedOrientationActivity + DiscouragedApi | 1 + 1 | Existing portrait 720×1280 capture contract; large-screen/rotation behavior still needs UAT |
| DataExtractionRules | 1 | Existing `allowBackup=false` retained; device-transfer/backup policy remains baseline review debt |
| UseKtx | 7 | Style suggestions only; explicit Android API calls retained without suppression or new dependencies |
| SetTextI18n | 12 | English prototype/localization debt; numeric cap entry intentionally uses strict ASCII whole seconds. Accessibility/large-font UAT still required |

Gradle also reports pre-existing deprecated features for Gradle 9 compatibility. No wrapper/plugin migration is part of TTL-004. These dispositions are **not independent warning acceptance**.

### Required next actions — no automatic device or merge permission

1. Reconcile supported PA reporting/review authority. One `pa_phase_report capture` attempt for this integration returned the protected-evidence stop (missing/insecure/unsupported/conflicting/uncertain evidence); no source/report reference was returned. No publish/settle, HTML delivery, browser/Obsidian observation or authority repair was attempted. This repository narrative is not canonical settlement. An attempted build-log redirection to the deployment artifact directory was also policy-blocked; subsequent builds ran directly with native tool-captured output, without bypassing that constraint.
2. Independent exact-candidate source review and the approved six-command matrix remain required. Requirements: `agent-teams/requirements/artifacts/2026-10-20-ttl-004-adaptive-timestamp.md`; matrix authority `7839091177015bf99df5dc0e5c3e3d20bc05dbbe1f51fc7c4194f684409bb358`; approval `c-20261019192823150`. This document does not replace the matrix, its gates or tolerances.
3. TTL-003 owns offline instrumentation provisioning. The newer develop integration now records cache readiness and supplementary failures owned by TTL-010 in the runbook/below; the original UTP cache failure remains historical. Initial TTL-004 results above predate that integration. Obtain fresh toolchain/isolated Pixel authorization before the prescribed wrapper/device command. Do not use an alternate runner, fetch dependencies, clear/uninstall valuable data or change security settings as a workaround.
4. Run all 31 device methods with zero failures/errors/skips, then execute the approved companion UAT (`2026-10-20-ttl-004-adaptive-timestamp-uat.md`). Record candidate, actual model/API/build, achieved rate/save latency, counts, pause/recovery behavior, sample/PTS/duration and geometry measurements. Do not put serials in shared evidence.
5. Obtain independent review/required CI and Sinh's human sign-off. TTL-001 acceptance debt remains separate; no merge/push/release, branch deletion or checkout return follows from this handoff.

| Human UAT scenario | Current result |
|---|---|
| TS-1 configuration/defaults/Fixed | Pending |
| TS-2 hierarchical selection/coverage | Pending |
| TS-3 originals/counts | Pending |
| TS-4 cadence/I/O | Pending |
| TS-5 dual duration/video estimate | Pending |
| TS-6 durable recovery/timing | Pending |
| TS-7 capped MP4/re-export | Pending |
| TS-8 storage/headroom/failures | Pending |
| TS-9 timestamp controls/216 layouts | Pending |
| TS-10 preview/output/lenses | Pending |
| TS-11 date/zone/clock | Pending |
| TS-12 frozen/legacy settings | Pending |
| TS-13 lifecycle/ownership regressions | Pending |
| TS-14 narrow-screen/accessibility | Pending |
| TS-15 physical integrated capture | Pending |
| TS-16 independent matrix/evidence | Pending |

---

# TTL-001: Android 16+ camera and media validation follow-up (historical debt retained)

- Ticket: **TTL-001**; implementation branch: `feature/TTL-001-android16-camera-validation`.
- Approved baseline: `b9aabdc2b125450576e39b673a3e0fdd87e0327f`.
- State: implementation present; device execution and acceptance **blocked/pending**, not complete.
- Current environment/supplementary execution: [TTL-003 offline runbook](OFFLINE_INSTRUMENTATION.md). Prior implementation evidence: [d-4582d3](sessions/d-4582d3.md). Preserve [original prototype history](sessions/d-1044a6.md).

## Scope correction

The approved requirements supersede the original Android-10 ticket wording: **minSdk / compileSdk / targetSdk 36**, Android 16+. No Android 10–15 compatibility claim is made. Mandatory physical app UAT is **Pixel 8 Pro / GrapheneOS Android 17 / API 37 / build 2026100601**. OPPO/API 36 app runtime coverage is deferred, not passed.

Requirements and paired UAT remain the approved external artifacts:

- `agent-teams/requirements/artifacts/2026-10-18-ttl-001-android16-camera-validation.md`
- `agent-teams/requirements/artifacts/2026-10-18-ttl-001-android16-camera-validation-uat.md`
- Approval: TTL-001 `c-20261018121729255`; matrix authority SHA-256 `d2003956cba9e1748b421cbd4ee9aa3c7f4e17612c7f6cc1eaf9415c5480c372`.

This document does not replace their commands, gates, acceptance criteria or tolerances.

## Implemented (runtime proof still pending)

- SDK 36 configuration; immutable pre-session lens selection, versioned metadata, legacy rear recovery, strict invalid-session blocking and explicit recovery retry.
- Lens-aware camera attachment and stale-provider-callback rejection; no unavailable-lens fallback. Valid-photo export does not depend on camera readiness.
- Front scene reflected after EXIF normalization, before the readable clock; no second preview/export mirror.
- Explicit zero-sized terminal muxer EOS timestamp for final-frame duration, without a duplicated sample.
- Serialized storage checks and capture cancellation; bounded fake-space/camera seams for fixtures.
- Atomic export attempt journal before encoding/insertion; exact UUID/name, package ownership, path, URI and pending-state checks before cleanup. Published output is retained. Ambiguous ownership, unknown attempt cache and cleanup failures retain/report state for explicit retry. Legacy unjournaled cache/pending media is not broadly swept.
- Original 11 host and 3 device tests retained. Host suite expanded to 16 tests; device suite expanded to 16 compiled test methods with loops over EXIF/fps/interruption boundaries.

## TTL-001 implementation phase-check results (historical)

| Check | Result |
|---|---|
| Offline `:app:testDebugUnitTest :app:assembleDebug` | Pass; XML: 16 tests, 0 failures/errors/skips |
| Offline `:app:assembleDebugAndroidTest` | Pass; instrumentation APK compiled, not executed |
| Offline `:app:lintDebug` | Pass; 0 errors, 16 warnings; dispositions in current evidence |
| Targeted MediaPipelineTest through approved Pixel identity wrapper | Blocked before test execution by missing cached UTP artifact |
| Changed-file whitespace | `git diff --check` passes |
| Independent full matrix / CI | Not run |
| Debug APK installation on authorized Pixel | Success via data-preserving `adb install -r`; package 0.1.0, min/target SDK 36 |
| Human/physical app UAT TS-1–TS-12 | Formal execution/sign-off pending; reported in-session lens lock explained as intended behavior |

## Original offline execution blocker (historical)

Fresh wrapper checks found exactly one authorized Pixel 8 Pro, API 37, build 2026100601. Gradle then failed offline resolving:

`com.android.tools.utp:android-test-plugin-host-additional-test-output:31.11.1`

No automated device test result is claimed. In a later user-requested operation, the debug APK was installed successfully after repeating exact Pixel identity and APK-hash checks; this does not resolve the missing test dependency or prove UAT. Sinh explicitly chose **Continue implementation offline**, rather than fetching missing dependencies. Do not retry through an alternate runner, change the dependency/toolchain/lock, or enable downloads under that decision. Arrange separately approved cache provisioning through the build owner, then rerun the prescribed named class with the exact identity wrapper after fresh toolchain/device authorization checks. Additional missing offline artifacts remain blockers, not permission to download.

## TTL-003 update — 2026-10-20, d-f14722

The [runbook](OFFLINE_INSTRUMENTATION.md) records the separately approved native-resolution revision, immutable manifests and retry history. The original strict URL/redirect/provenance requirements are not claimed satisfied; requirements/UAT/matrix reconciliation remains necessary. App/tests/toolchain/flake/lock inputs are unchanged.

- **Cache ready:** 16 configurations / 164 unique artifact files verify offline, zero missing/digest mismatches. Separate metadata and artifact retrieval approvals retained; no blanket future download permission.
- Fresh offline host tests: **16 passed, 0 failures/errors/skips**; both APKs build. Lint: **0 errors / 16 warnings**; existing deferrals remain proposals for independent review.
- Fresh mandatory Pixel wrapper stopped before installation: connected model was **CPH2699/API36**, not the approved Pixel. Sinh then separately approved supplementary execution on that exact model/API/build.
- Supplementary named MediaPipelineTest reached real UTP execution offline: **16 executed, 14 passed, 2 failures, 0 errors/skips**, exact source method set matched XML. `ambiguousOwnershipAndCleanupFailureRetainRecordsForExplicitRetry` failed with MediaStore SecurityException at fixture cleanup line600; `interruptedExportsCleanOnlyTheirPendingOutputsAndPreservePublishedVideos` failed with unexpected encoder frame count at VideoExporter.kt:237. New follow-up **TTL-010** owns both failures at Sinh's request, rather than reopening TTL-001. The earlier TTL-001 referral `c-20261020134353240` is superseded for execution ownership, not erased from history. No test suppression or app fix in TTL-003.
- Both keep-installed/no-incompatible-uninstall flags were used; post-run app package remained installed. No app-data clear/uninstall, broad media deletion or shared ADB-server replacement. Existing unrelated phone data was outside authorized effects, not inspected phone-wide.
- **Important command correction:** pinned AGP normally uninstalls tested APKs after the run. The original V5 lacks the preserving property. Do not replay it unchanged; see the revised phase flags in the runbook. Independent matrix approval must be renewed separately.
- Mandatory Pixel execution, independent matrix/review/CI, physical camera/Gallery UAT and final acceptance remain **pending**, not passed. Cache readiness does not accept TTL-001 or TTL-009. PAPC-061 captures reusable Android setup/development knowledge.

## Remaining acceptance work

- [ ] Execute all 16 device test methods, zero failures/errors/skips. Investigate actual codec/MediaStore/EXIF results; compilation is not runtime proof.
- [ ] Pixel front/rear preview and saved output comparison, ordinary permission denial/grant/retry and unavailable-lens capture blocking with valid-photo export.
- [ ] Pixel sessions per lens: **1s/15fps, 5s/24fps, 60s/30fps**, at least two completed photos each, with shutter-request clock comparison.
- [ ] One-photo export and Gallery/player playback at **15/24/30fps**, exactly one decodable sample and duration within **1 ms**; multi-photo samples/PTS within **1 µs**.
- [ ] Physical Stop/in-flight, two-interval background pause, Activity recreation/process restart, explicit Resume, deletion-dialog Cancel/Confirm and published-video preservation.
- [ ] Inspect isolated low-space/save/export failure, interrupted-publication and ambiguous-ownership fixture evidence. Never fill real phone storage or damage user sessions.
- [ ] Obtain independent source/full-matrix review, CI and human UAT acceptance against the exact candidate. Warning dispositions below are implementer proposals, not independent acceptance.

Sinh subsequently requested a commit and direct integration into `develop`; this is explicit integration permission, not passing test/UAT evidence or a change to the repository's default main-targeted policy. `main`, release, remote operations, branch deletion and checkout return are outside this wrap-up. Retain completed photos until playback is verified. Preserve historical failures and current offline blocker evidence.
