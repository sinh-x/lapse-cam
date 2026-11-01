# TTL-015 review, Pixel validation and PR — d-77bede

## Owner-approved validation workflow revision

After matching full hosted CI run
[41262489862](https://github.com/sinh-x/lapse-cam/actions/runs/41262489862) passed on
`d7f20565eecc644b0f58276b91dc51c545addf7a`, Sinh requested a lighter workflow,
focused primarily on Pixel and moving some CI checks local. Presented the exact
split and Sinh selected **Apply this workflow split**: relevant Pixel feature tests
and hands-on checks required; OPPO/exhaustive cross-device UAT optional; CI runs
host unit/accounting tests; both APK builds and lint remain local pre-merge checks.

See [current workflow](../VALIDATION_WORKFLOW.md). This continuation changes only
workflow/accounting/docs, not compiled app/test sources or installed test app.
The latest local/Pixel app evidence below remains source-equivalent; a new CI run
must validate the workflow head. The historical signed plan/matrix is unchanged,
missing checks are not relabelled passed, and unrelated ticket debt is not closed.
No independent review or full human UAT sign-off is claimed. Earlier paragraphs
below naming both-phone gates describe the superseded validation scope.

The workflow verifier now has separate host-only and historical ttl011-full
profiles. Historical candidate accounting still requires its original full task
set; the smaller hosted command cannot satisfy it. Cross-profile/extra-task and
missing-host negatives guard the current split without changing pinned actions,
token scope, failure handling or artifact retention.

## Final motion continuation (supersedes earlier candidate counts/hashes)

After the immersive candidate, Sinh asked about differentiating phone movement
from stillness and selected **Use motion to control blackout**, followed by
**Movement wakes; stillness rearms blackout**. Recorded in
TTL-015/c-20261031140119721. Commit `73fb3d7` preserved the earlier immersive
candidate; motion is a separately verified continuation on the same feature
branch, not an implicit change to the signed historical plan/matrix.

- `PhoneMotionPolicy`: gravity-vector low-pass (800ms time constant), significant
  residual acceleration at 1.5m/s² or angular speed at 0.35rad/s, then 2,000ms
  quiet hysteresis. Injected monotonic clock; rejects nonfinite samples and resets
  history between owners. Thresholds are conservative implementation defaults,
  not calibrated physical guarantees; smooth constant-speed travel can appear
  still. No camera-scene motion detection or measured power benefit.
- Foreground Activity registers ordinary-rate accelerometer/gyroscope listeners
  only during otherwise eligible recording. Modal/focus/error/non-live/disabled
  preference and teardown unregister and reset. Events predating the current
  registration are ignored. Missing/denied sensors fall back to timer/tap return.
- Motion is an eligibility blocker: immediate reveal, no capture command, no
  rearm during detected motion; quiet starts the full new 30-second delay.
  Unchanged quiet sensor emissions never reset idle. Existing blackout toggle
  governs the behavior; no new preference, permission or dependency.
- Added seven pure motion tests, one display-policy integration case and one
  Android sensor-lifetime/movement seam. Final fresh offline `--rerun-tasks`
  command: **112 host tests passed, zero failures/errors/skips; both debug APKs;
  lint 0 errors / 78 warnings; 76 tasks executed**, 3m03s. All source method
  names match fresh JUnit XML. **116 Android methods compiled**.
- Same isolated Pixel package updated; user explicitly authorized another
  test-only reset. USB disconnected before any reset/test command and was
  reconnected/revalidated at the owner's request. Final selected run:
  **17/17 passed**, all 11 BlackoutActivityTest + all 6 PersistenceDefaultsTest
  methods, 25.942s. No missing/skipped methods in this selected inventory.
- User reported behavior not working; current foreground package was verified
  as the **original** app, not the updated test app. User confirmed original/not
  sure. Opened the labelled test app without replacing original data.
- Physical pickup check: screenshot first showed immersive blackout. User lifted/
  tilted without touching the screen and explicitly answered **Camera appeared
  when I moved it**. Screenshot then showed LIVE/207 photos. After owner confirmed
  placement flat, waited 35s and observed immersive blackout again. Return still
  LIVE; separate next tap paused at 492 photos/4m06s. Test session left paused,
  not automatically resumed/exported/deleted. These are selected observations,
  not full sustained-motion/threshold/device UAT.

| Final motion APK | SHA-256 |
|---|---|
| Normal app | `97d07d68fa9026f25b70d225f07be21aa32c2fd9e7e429bf9c2dc4ef4fa62bb4` |
| Normal Android tests | `72f1c3b03a59e06558f8747b479a431bc6d68d042e8c652404e725e4370d95bb` |
| Isolated Pixel app | `9ea6d6b851c4f8fe04dd2d0aacb08c7a7c26fe9485ab980628497c249fc1fe92` |
| Isolated Pixel tests | `207f47030c8cac4928b11f4599ef2cfe75b2149175f29392c9af3fc436ea3e52` |

Complete main/Android-test Kotlin sources were compared byte-for-byte with the
isolated build. Only app ID/label differ; device evidence is not normal-package
APK execution. Final evidence uses `motion-*`/`pixel-motion-tests.txt`; previous
failures/evidence remain retained. OPPO, full 116-method Android execution,
independent review and complete both-phone human UAT remain missing. No merge
or full acceptance is inferred from selected device success.

## Historical immersive candidate evidence (before motion continuation)

## Candidate and decisions

- Branch: `feature/TTL-015-blackout-recording`.
- Worktree: `/home/sinh/.treehouse/timestamp-timelapse-230d79/3/timestamp-timelapse`.
- Base and observed remote develop: `693feed0ff0961616b63326f57455fd3dcfdf95a`.
- User requested implementation, PR and merge to develop **if good**. This
  explicitly selects develop instead of the older main-target branching guide.
- Existing implementation from d-1901a2 retained and reviewed. Initial source
  review needed no production repair; device execution then exposed fixture issues.
- User explicitly selected **hide both bars during blackout**, restoring them
  on return/dialog/focus loss. Recorded in TTL-015/c-20261031134043603. This
  supersedes r001 FR-2's visible-bars design only; signed historical plan/matrix
  bytes remain unchanged, not retroactively approved at the new base.
- No release, original-app reset/uninstall, sync enrollment or checkout return.

## Source review and final changes

Reviewed production/test changes against FR-1–11, AC/TS-1–12 and the TTL-014
modal/picker reconciliation. Policy uses monotonic time, stable eligible deadlines,
generations and disposal. Cover leaves compatible PreviewView mounted and issues
no capture/bind/media commands. Defaults remain independently editable. Touch/key
completion and focus/accessibility isolation protect hidden controls; parent modal
and picker lifetime suspend rearming. Capture/media/permissions/dependencies and
brightness settings are unchanged.

The immersive amendment uses the public WindowInsetsController to hide system
bars with transient-by-swipe behavior, restoring visibility, prior behavior and
light-bar appearance when uncovered. Insets preserve cutout-safe banner placement.
Android privacy indicators/transient bars are not suppressed. Added a regression
case asserting actual status/navigation-bar visibility after animation and
restoration on both return and focus loss.

## Final local verification

Full command from `docs/TTL015_IMPLEMENTATION.md`, including `--offline
--no-daemon --rerun-tasks`, host tests, both debug APKs and lint: **exit 0,
76 tasks executed**, 1m50s after the immersive amendment.

- **104 host tests, zero failures/errors/skips**; every source method name matches
  fresh JUnit XML, including backtick names and expected-exception tests.
- **115 Android methods compiled**; not all executed.
- **0 lint errors, 78 warnings**, with category rationales retained in the
  implementation document. No suppression or baseline added.
- Session-accounting self-tests: 19 passed; timebox-accounting self-tests: 7
  passed. These validate accounting tools, not physical Android behavior.
- `git diff --check` passed.
- An initial ad-hoc inventory regex omitted four expected-exception methods;
  corrected it and compared every name. This was an accounting assertion,
  not an application failure.

| Final APK | SHA-256 |
|---|---|
| Normal app | `0bd7245817f66a87ef7c7d43ac4041cd4b38f3cfb42b31c04387cca82bf8a139` |
| Normal Android tests | `046eeb356feb68bc4d892358e4bb9f8dc1c24e65648b6b49b69c0067ad65c6f9` |
| Isolated Pixel app | `ec73cf356cbf2d0a46b65079a2aabfbd7aaa1e7f51f2474db83718dc3411a2c8` |
| Isolated Pixel tests | `cf95a49f95a6b9ffba07034e36dc7528b5f0955588be6f5cce5ca497e725f2c7` |

## Authorized Pixel execution

Pixel 8 Pro, API37, build2026102101; selected serial retained privately. Existing
shared ADB server reused, not restarted. Existing `com.sinh.timelapse` contains
valuable defaults/sessions/export/sync state. User explicitly approved a separate
package: **`com.sinh.timelapse.ttl015.d77bede`**, label **TTL-015 Pixel Test**.

Built in `/tmp/ttl015-pixel-d-77bede` from the same Kotlin sources; only application
ID and app label differ. This is a clearly identified test build, **not execution
of the normal-package APK**. Original installed app APK hash verified unchanged.
Original app/data were not cleared, uninstalled, replaced or edited. Camera
permission granted only to the test app. No global brightness/font-scale/TalkBack
settings changed. Test app and test APK remain installed. User separately approved
two test-only resets after guards found nonempty state; never applied to the
original package or unrelated media.

### Failures and repairs retained

1. First 15-case run: 12 passed, 3 failed. Fixtures asserted queued button clicks
   and Dialog dismissal listeners before the main loop dispatched them, and
   propagated an accessibility event when accessibility was disabled.
2. Repaired fixtures by yielding before completion assertions and stopping event
   propagation at the fixture parent. All original assertions retained; switched
   settings opener to the actual always-enabled Camera gear. This is a synthetic
   delegate seam, **not a TalkBack pass**.
3. Rerun while phone relocked failed recreation/focus checks. Another run hit
   nonempty-default guards. Failures retained, not counted as passes or permission
   to clear. Owner authorized resetting **only** the disposable test app.
4. Clean unlocked pre-immersive rerun: **15/15 passed**.
5. Final immersive build, clean unlocked run: **16/16 passed** in 16.086s:
   all 10 BlackoutActivityTest and all 6 PersistenceDefaultsTest methods. Exact
   completed names match selected source inventory; no skipped cases.

### Real camera smoke test (partial UAT only)

- Pre-amendment rear Adaptive recording: black app area/red banner observed,
  saved JPEG count advanced 29 to 90 during a covered interval.
- Final immersive rear Adaptive recording: screenshot shows black screen/red
  banner with status/navigation bars absent. Keep-screen-on window observed.
- Tap over underlying Pause only returned to Camera: screenshot remained LIVE,
  70 saved photos at 34 seconds. Next deliberate tap paused: 73 photos at 36
  seconds. Both bars restored. No auto-pause from return.
- Finish exported the fixture, inventory reports 73 frames / 30 fps. Exact output
  `content://media/external/video/media/307` decoded with ffprobe: 720x1280,
  30/1 fps, **73 decoded frames**. Inspected saved JPEG and decoded frame 65:
  scene/timestamp present, no blackout cover or LIVE banner.
- Early live-check attempts were blocked by lock screen or Ready/not-recording
  state; those screenshots are not labelled successful blackout observations.
- Reading the output through run-as content failed with provider permission
  denial. Retained the error; shell content read of that exact fixture URI
  succeeded. No broader MediaStore read/delete or permission escalation.
- This does not cover front lens, Fixed mode, physical TalkBack/font scaling,
  every nested dialog, full lifecycle/error/rollover matrix, OPPO or full human UAT.

## Evidence and acceptance boundary

Local raw evidence: `/tmp/ttl015-evidence-d-77bede/`; retained copy under
`/home/sinh/Documents/ai-usage/implementation-evidence/TTL-015/d-77bede/`.
Contains original failed logs, final Gradle/JUnit/lint/inventories/APK digests,
selected instrumentation protocol and local screenshots/JPEG/MP4 samples. Camera
scene samples remain local, not committed or uploaded to the PR.

**Full acceptance remains unproven**: independent reconciled exact-SHA review,
complete 115-method Android execution, OPPO and full both-phone TS-1–12 human UAT
are missing. Selected Pixel evidence does not pass those gates. Matching hosted
CI must be checked after publication. Merge requires completed remaining gates
or explicit owner acceptance of integration with known validation gaps. No release
or inherited TTL-014 acceptance is claimed.
