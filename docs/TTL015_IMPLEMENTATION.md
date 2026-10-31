# TTL-015 — blackout recording implementation

## Current continuation

Deployment `d-77bede` reviewed the existing implementation, repaired three Android
fixture issues found on Pixel and implemented the owner's subsequent **immersive
blackout** request. Status/navigation bars hide only while covered; return,
modal/focus loss and teardown restore them and their original behavior/appearance.
OS privacy indicators and transient edge-swipe bars remain Android-controlled.
This supersedes r001's retain-visible-bars design, explicitly selected by Sinh
and recorded in TTL-015/c-20261031134043603; signed historical plan bytes are unchanged.

Final fresh offline checks: **104 host tests passed, both debug APKs built,
lint 0 errors / 78 warnings**, all 76 tasks executed. **115 Android methods
compiled; 16 selected tests executed and passed** on Pixel 8 Pro/API37/build
2026102101 in the owner-approved separate disposable test package. A real rear
Adaptive capture verified immersive appearance, return without pausing, next-tap
Pause and a 73-frame export; sampled JPEG/MP4 images contain no cover/banner.
This is selected device/builder evidence, not full UAT or independent review.
Current hashes, failures/repairs and remaining coverage are recorded in the
[continuation evidence](sessions/d-77bede-ttl015-review.md).

The current user request authorizes a PR targeting `develop` (an explicit exception
to the older `docs/BRANCHING.md` main-target guide) and merge only if good. Remote
`develop` was verified at `693feed0ff0961616b63326f57455fd3dcfdf95a`, also the
repository's default branch. Full plan acceptance is **not established**:
independent review, full Android inventory and complete both-phone TS-1–12 UAT
remain unexecuted (selected Pixel coverage is described above). Integration
must retain these gates unless the owner explicitly accepts the validation gaps;
no release, phone operations, cleanup or worktree return is implied.

The sections below retain the original deployment's scope, 114-method inventory,
old APK hashes and evidence. Its visible-system-bars design and no-device/no-remote/
no-commit statements describe that historical session only; use the continuation
above for current behavior and results.

## Base reconciliation and scope

Deployment `d-1901a2`, branch `feature/TTL-015-blackout-recording`, selected
worktree `/home/sinh/.treehouse/timestamp-timelapse-230d79/3/timestamp-timelapse`.
Base: `693feed0ff0961616b63326f57455fd3dcfdf95a` (TTL-014 merged).

The historical approved r001 requirements/UAT and matrix remain immutable at
base `856ee741c662944caf924ff12a9fea39b06ebcf9`; their approval is not represented
as a new-base independent matrix pass. After inspecting the merged surfaces,
Sinh selected **Approve implementation and local checks** in the foreground
base-reconciliation question in `d-1901a2`, including exclusive Gradle-cache use.
This authorizes this reconciled local implementation, tests/docs, offline host
checks, both debug APK builds and lint. No phone/ADB, remote publication, merge,
release or checkout return is authorized.

### Reconciled FR/AC/TS-6/7/8 coverage

- Preserve r001 visual/default/input/capture policy and all TS-1–12 expectations.
- Settings, Work (including unsaved edits, choices, confirmations), Sessions,
  recording links/details and delete/notification alerts suspend blackout.
- TTL-014 shared history remains a modal blocker, including enrollment/result,
  durable queue, descriptive edits/conflicts, archived video and mobile-data
  confirmation. Nested windows also remove Activity window focus; closing an
  inner window cannot rearm while its parent remains open.
- The external CreateDocument destination picker is explicitly pending until
  its result callback, including recreation/cancellation; external playback,
  permission/system settings and focus loss also prevent eligibility.
- Only final modal dismissal, picker completion and regained foreground focus
  permit a fresh full 30-second interval. Ordinary sync state emissions never
  count as input or determine LIVE. No sync enrollment/transfer is initiated by
  blackout, and capture/export associations and saved Work edits are unchanged.
- Historical matrix M1–M4 is not rewritten or claimed executed independently.
  Local checks use the same installed JDK/SDK/offline Gradle tasks; they run on
  the working candidate, not a fabricated clean feature SHA. Independent review,
  matching CI and both-phone Android/UAT execution remain separate.

## Delivered implementation

- `BlackoutPolicy.kt`: injected monotonic clock, exact 30,000ms threshold,
  unchanged-eligibility emission stability, generation-bound timers, one-shot
  terminal Work notices and explicit owner disposal.
- `CaptureDefaults.kt`: independent default-on persisted `blackoutEnabled` key.
  The Capture settings checkbox remains enabled under existing session locks.
- `MainActivity.kt`: opaque sibling cover, exact static #AA0000/white 16sp banner,
  safe insets, retained system controls, mounted preview and unchanged keep-awake
  policy. No capture/media code, permissions, dependency pins or brightness
  assignments change. CameraUiPolicy and CameraSettingsSheet need no rewrite:
  reuse their live projection and existing individually locked session controls.
- Whole touch/multipointer/cancel and key sequences are consumed before return;
  ordinary state emissions cannot prematurely reveal during a held return.
  Genuine errors still clear immediately and the remainder remains consumed.
  Focus loss releases held-input eligibility without forwarding orphan key-ups.
  Modern Back and the named accessibility return action reveal only; underlying
  Camera focus/accessibility is blocked, then neutral focus is restored.
- Genuine accessibility focus/click and generic pointer input reset inactivity;
  ordinary content/timer/sync emissions do not. Parent modal lifetime and Activity
  focus jointly suspend nested windows; the external save picker has a separately
  saved pending flag. Cover/elapsed idle are never saved or restored.

## Local check results (not independent acceptance)

Final command, exit 0, **76 Gradle tasks executed**, no up-to-date substitution:

```sh
JAVA_HOME=/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2 \
ANDROID_HOME=/home/sinh/android-sdk-writable \
PATH="/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2/bin:$PATH" \
./gradlew --offline --no-daemon --console=plain --rerun-tasks \
  -Pandroid.builder.sdkDownload=false :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

- **104 host tests passed**, 0 failures/errors/skips. Source-method inventory
  exactly matches fresh JUnit XML (including backtick-named Kotlin methods).
- **114 Android test methods compiled; 0 executed here.** Includes 9 new
  `BlackoutActivityTest` methods and one added persistence method. Existing test
  methods/assertions/isolation guards are retained. These synthetic fixtures do
  not certify rendered appearance, real camera/media, OS input or TalkBack.
- Both debug APKs built. Lint: **0 errors, 78 unsuppressed warnings**.
- `git diff --check` passes. Historical approved requirement/UAT SHA-256 values
  remain `80804d678f61bfe5ee86366015c07f73b96e8d86e9a93bdc8de842a17432d7f9`
  and `11a691c3947e0d04e7acef4ea0f88f9197da8eef62ba66f8e06fcbe4842b667f`.

### Candidate/artifact identity

This is an **uncommitted working candidate**, not a clean Feature SHA or an M1 pass.
Evidence directory: `/tmp/ttl015-evidence-d-1901a2/` (local temporary retention).
It contains the full host/Android method inventory, source SHA manifest, fresh
JUnit XML, lint XML, Gradle log and result summary. Generated reports/APKs also
remain under ignored `app/build/`; these are builder evidence, not PA approval.

| Item | SHA-256 |
|---|---|
| App debug APK | `8c09b05cb802758488afad448e6b36a177374d68223d96bdc878dfa9ca8bf3b1` |
| Android test APK | `d5f8a423cbcd07d0b02c2d99a48d72917248af6d6058f9f8560e1c27a616a0f5` |
| Source SHA manifest (all app sources, build files and README) | `bc2c67fe356c716e5a1338e73ab94f7c0d0be026fdf3645c8d952a57997c6c42` |
| Complete method inventory | `a0a446c096daf2d8aa5eef2259b63ca24d0f8a91fd2e4108460331c67b7752be` |

### Test inventory by class

| Host class | Executed methods |
|---|---:|
| AdaptiveSamplingTest | 14 |
| BlackoutPolicyTest | 8 |
| CameraUiPolicyTest | 2 |
| CaptureTimingTest | 22 |
| RecordingLinkTest | 3 |
| SessionContractTest | 6 |
| SessionLifecycleTest | 8 |
| SyncEnrollmentTest | 10 |
| SyncPolicyTest | 5 |
| SyncTransportTest | 3 |
| TimeboxPolicyTest | 3 |
| TimeboxReminderAccessTest | 2 |
| WorkEditPolicyTest | 7 |
| WorkListPolicyTest | 4 |
| WorkManagementPolicyTest | 4 |
| WorkSessionTest | 3 |

| Android class | Compiled methods (not run) |
|---|---:|
| AdaptiveSessionTest | 11 |
| BlackoutActivityTest | 9 |
| CameraSettingsSheetTest | 2 |
| CameraUiActivityTest | 2 |
| MediaPipelineTest | 17 |
| PersistenceDefaultsTest | 6 |
| SessionExportQueueTest | 15 |
| SessionRolloverTest | 6 |
| SharedHistoryViewTest | 2 |
| SyncMigrationTest | 3 |
| SyncStoreTest | 8 |
| SyncUploadTest | 5 |
| TimeboxExecutionTest | 3 |
| TimeboxRecordingLinkTest | 3 |
| TimestampAppearanceTest | 4 |
| WorkSessionActivityTest | 9 |
| WorkSessionStoreTest | 9 |

### Coverage and remaining physical checks

| Criteria | Added local coverage | Still unexecuted |
|---|---|---|
| AC/TS-1/3/6/8/9 | Eight host policy cases: boundary/emissions, input/reset, every eligibility blocker, nesting/picker, rollover, terminal deduplication and stale owners; persistence reader/writer fixture | Actual preference/relaunch, camera transitions, Work/error presentation |
| AC/TS-2/4/5 | Android source fixtures: exact colors/layout at both viewports and 200% linear text, full touch/cancel/multipointer, repeated key events, Back/accessibility/neutral focus | Physical appearance/nonlinear text scale, long-press timing, TalkBack and all actual hidden targets |
| AC/TS-7/8 | Android source fixtures: settings/shared-history/focus, picker blocker, held input, interrupted keys, stale callback and recreation | Complete actual nested Work/Sessions/sync/dialog/picker inventory and OS lifecycle |
| AC/TS-10/11 | Source separation, mounted-preview/owner/brightness fixture assertions; unchanged capture/export/manifest files | Both lenses/modes on both phones, JPEG/MP4 inspection, actual brightness/system bars/keep-awake/background behavior |
| AC/TS-12 | Fresh host/build/lint and full source inventory | Independent reconciled exact-SHA matrix/review, matching hosted CI and both-phone full Android/TS-1–12 human UAT |

### Warnings and retained repair history

Builder rationale for **every warning by issue category**, not independent Minor
acceptance or suppression:

- SetTextI18n **55**: existing prototype/localization debt plus display-only literals;
  retain visible warning and agreed exact English banner/toggle. Localization is
  not expanded here.
- UseKtx **16**: existing native APIs plus preference/visibility calls; stylistic,
  no correctness workaround or extra dependency needed.
- GradleDependency **2**: preserve approved offline cached pins; no network upgrade.
- ViewConstructor **2**: existing programmatically created views, not XML inflation.
- LockedOrientationActivity **1**, DiscouragedApi **1**: existing portrait/activity
  choices, unchanged by this display feature.
- DataExtractionRules **1**: existing backup-policy debt, no new data export policy.

Existing Kotlin deprecations in sheet/Work soft-input and accessibility APIs and
Gradle 9 compatibility notices remain. Android test AccessibilityEvent factory
warnings are fixture-only API deprecations, not execution failures.

The first P2 lint run failed with three RestrictedApi errors from overriding/
calling AndroidX ComponentActivity's key dispatch method. Repaired using the
public Android Window.Callback delegation boundary, not suppression or a baseline.
The P2 recheck and later full reruns passed. Subsequent source review added explicit
routine-emission/held-return and interrupted-focus regression assertions; the
final full command above includes those sources.

## Handoff / remaining gates

No phone queries, installs, instrumentation, physical UAT, remote operations,
commit, merge, release or checkout return were performed. Keep the worktree and
lease. TTL-014/prior validation debt remains separate, not inherited as a pass.
Next: review this working diff, freeze an authorized candidate when requested,
then obtain independent review/CI and explicit isolated Pixel/GrapheneOS + OPPO
execution scope. No clear-data/uninstall or valuable-media cleanup is implied.
