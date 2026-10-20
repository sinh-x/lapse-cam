# TTL-001: Android 16+ camera and media validation follow-up

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
