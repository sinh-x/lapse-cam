# TTL-006 persistence validation handoff

Approved scope: r001 requirements/UAT pair, approval `c-20261022145410122`,
matrix authority `77be789f712168fe585aa463ff958f2282febe1504928a2f7f52f3f7c37f2da4`.
Base: `c6715b580890378775516d3ee6c2021626197ba8`;
branch: `feature/TTL-006-persistence-validation`.

## Later authorized Pixel attempt (2026-10-23)

Both APKs are now installed on the exact approved Pixel/user, and their on-device
hashes match the P3 build in FOLLOW_UP (app v0.1.0/versionCode1). Scoped defaults
instrumentation executed **5 methods: 3 pass, 2 failure, errors/skips0**. Both
Activity methods refused existing capture defaults before launching an Activity.
No defaults were reset to bypass that guard. Explicitly authorized subsequent
read-only inspection found 10 default entries, 2,634 existing JPEG paths and a
session index, so this is not an empty fixture context. Preserve all data.
No full regression rerun, force-stop, reboot, clearing/uninstall, permission
change or human UAT sign-off occurred. Raw failure XML/HTML are retained in the
owner-restricted `app/build/ttl006-phase-p1` snapshot; installed APK hashes and
permission/inspection chronology are in FOLLOW_UP. Safe isolation must be
reconciled with Sinh before another device attempt; no guard weakening or
cleanup is implicitly approved. The following host-stage evidence and original
blocked ledger remain historical, not a claim that no later install occurred.

## Implementation phase evidence (d-48b0c0)

These are uncommitted implementation checks, **not** a frozen-candidate L1,
independent V1–V7 review, Android execution, or human UAT. Full command/output
receipts are retained in this deployment's native tool/activity history. The
native phase-report capture refused protected-workflow evidence; no published
phase report or settled delivery is claimed. Log redirection attempts were
refused before build execution; the direct offline command then succeeded.

- Phase 1: shared `CaptureDefaults` adapter, MainActivity delegation and five new
  `PersistenceDefaultsTest` methods. Offline `testDebugUnitTest assembleDebug
  assembleDebugAndroidTest --rerun-tasks`: exit 0, 50 host methods, no failures,
  errors or skips; both APKs compile. Existing transitive ActivityScenario works
  without dependencies. Device behavior of the new tests is **unverified**.
- Phase 2: prescribed CaptureTimingTest, AdaptiveSamplingTest,
  SessionContractTest and SessionLifecycleTest offline run: exit 0, 50 methods,
  no failures/errors/skips. No existing fixture, assertion, session persistence,
  selector, scheduler, lifecycle, renderer, media or build/pin file was changed.
  No demonstrated missing simulated boundary justified additional core fixtures.
- Phase 3 results are recorded in FOLLOW_UP after accounting/build/lint checks.

The Activity tests refuse admission if defaults, sessions, index, export journal
or saved-video preferences already exist. They create only their own defaults
and one named fixture session/index; teardown never clears app data or unrelated
media. Tests never click Start/Resume. Permission-gated preview binding remains
normal app behavior. Run **only** in the freshly approved disposable context,
with the exact V5 identity/no-uninstall wrapper and class filter from the approved
requirements. A refusal is a failed/blocked check, not permission to clear data.

## Actual-loss UAT procedure — permission required, not an executable script

No actual-loss phone trial was executed. Exact Pixel 8 Pro,
GrapheneOS Android 17/API37/build2026100601, isolated primary-user app context,
install/test/media operations and read-only private-file extraction need fresh
operator approval. Force-stop and reboot each require their own additional,
immediate approval. Do not substitute an OPPO/profile, restart shared ADB,
change permissions/security, uninstall/clear data or fill storage.

Follow approved companion UAT TS-6 and TS-7 verbatim; this checklist does not
replace or expand their authority:

1. Pin the final full Feature SHA and APK digests. Retain candidate/device
   identity (serial restricted), exact permission refs, reviewer and UTC times
   in a new owner-only external evidence directory. Verify safe run-as/tar
   support and private paths without aliases/foreign data before any trial.
2. Use a NEW Adaptive/Off 5-second/15fps session for each trial; no automatic
   cleanup. Reach at least 76 completed photos to exercise thinning and a
   running checkpoint. Record actual achieved shutter/save gaps/count, not a
   promised 2fps. Capture pauses below 50MiB; never fill storage for testing.
3. After the separately authorized foreground force-stop or actual reboot,
   verify the process is stopped. **Before first relaunch**, collect a stable
   read-only archive of `files/sessions` into a new no-clobber owner-only host
   file. Keep raw session/progress/intents and hashes of every completed JPEG.
   Retain incomplete temporary files too, but never select them. Do not repair
   or delete evidence. An archive/read failure blocks the trial.
4. Record at least 60 seconds downtime. Reassert device identity after reboot
   and deliberate unlock; do not launch the app before the stopped-state
   archive. Record whether shutdown gracefully paused: that is not evidence
   of a lost running checkpoint and cannot be silently relabelled or repeated.
5. Relaunch without Resume; collect the second archive while paused. Replay
   ordered identifiable completed intents/JPEGs once under the unchanged
   whole-list selector. For B=75, 76 committed positions yield 0,2,...,74,75
   (39 selected, stage 1); gaps in consumed IDs are not missing completed
   photos. Reconcile any completed suffix after the saved progress boundary.
   Compare exact IDs, stage, count, bytes and every original completed JPEG
   hash. Unknown/contradictory provenance must block with data retained.
6. Compare recovered active time to the durable checkpoint/replayed completed
   suffix, not wall time or the pre-kill screenshot. Observe another 30 seconds
   paused: active time must not advance. A running checkpoint discloses its
   persistent unknown tail. Commit/pause and nominal 5-second checkpoints do
   **not** guarantee a maximum hardware crash-tail loss. Reboot monotonic origin
   and process downtime add zero active time. Keep failures/partial attempts.
7. Gallery playback/export needs its own already-granted media permission;
   verify exact ordered selected frame count/cap at 15/24/30fps and unchanged
   photos. The headroom estimate is not a reservation. Preserving Off originals
   permits unbounded storage growth. No data-loss guarantee beyond the actual
   observed operation or any permission for pruning follows from this plan.

## Acceptance ledger

All ten human UAT scenarios still lack reviewer/sign-off. TS-1 now has three
passing automated supporting methods; TS-2's two automated methods failed safe
fixture admission as recorded above. The remaining scenario execution and all
human sign-offs are **blocked/not run**:
TS-1 defaults; TS-2 actual recreation; TS-3 simulated Android recovery;
TS-4 scheduling/hardware observation; TS-5 legacy/corruption;
TS-6 actual termination; TS-7 actual reboot; TS-8 device media/Gallery;
TS-9 independent candidate-bound accounting; TS-10 human acceptance.
Host fixture results are supporting evidence only, not scenario sign-off.

Still required on one frozen clean candidate: L1 offline aggregate receipt,
independent authorized V1–V7 (7/7) and source review, lint warning dispositions,
fresh exact-device Android XML for all base 53 methods plus all additions,
and all ten human UAT results with dates/reviewer. Hosted CI is N/A for TTL-006;
no CI service/workflow is added. TTL-009 retains umbrella verification, TTL-005
retains split-only verified cleanup, and TTL-010's original encoder cause remains
unknown. Preserve their original failure/pass history. No merge, release,
ticket-done, checkout return or sibling closure is asserted here.
