# TTL-015 review, Pixel validation and PR — d-77bede

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
