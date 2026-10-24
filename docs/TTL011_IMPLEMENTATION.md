# TTL-011 implementation handoff

## Status and authority

Implementation-phase work in deployment `d-eeedd5`, on
`feature/TTL-011-timebox-video-links`, selected base
`62a021c0d72f2029340b040ac125a8b393836974`. This includes the merged TTL-006
CaptureDefaults adapter and its five Android tests. Sinh answered **yes** to
implementing approved r003 on this selected base; the clarification is retained
in ticket comment `c-20261023230134268`. Historical signed requirements, old base
`c6715b5`, matrix approval and preparation records were not rewritten/replayed.

**Not accepted; targeted device checks only.** Source remains an implementation
candidate, not an independent matrix/CI/full-UAT result. The later explicitly requested
[Archive/Delete extension](TTL011_ARCHIVE_DELETE.md) adds metadata management and v2
storage with v1 compatibility:67 host cases pass,80 Android methods compile and12
isolated Android cases pass on the Pixel. Later operator-requested
updates and Pixel UI/sound checks are recorded in [TTL011_UI_CHECKS.md](TTL011_UI_CHECKS.md):
63 host cases pass,75 Android cases compile, keyboard/Back and synthetic Work paths
were exercised, and Sinh confirmed hearing a real end notification after personally
disabling DND. Initial no-device statements describe the earlier implementation
phase only. No force-stop/reboot, push, hosted CI run, merge or checkout return.
Historical local checks remain in [FOLLOW_UP.md](FOLLOW_UP.md#ttl-011-local-implementation-not-acceptance).

Approved source: `agent-teams/requirements/artifacts/2026-10-23-ttl-011-camera-ui-timeboxes-r003.md`
and its companion fourteen-scenario UAT, under Sinh sign-off
`c-20261023143950024`. These live in the PA requirements home, not this checkout.

## User flow

- Camera has a compact Work card, a separate settings gear/HUD, an aspect-fitted
  9:16 preview, capture summary, circular Start/Pause/Resume, eligible separate
  Finish, Sessions and Recording details. LIVE is white text/red badge only from
  `capturing=true`; it is outside the output frame and never passed to rendering.
- Work uses a native IME-capable window with a compact 48dp Back arrow; reminder
  settings separately show/explain notification and background-timing access,
  disabling already-allowed actions. Permission access does not override DND or
  guarantee audible playback. See the targeted UI follow-up above.
- Gear opens a native bottom-anchored Capture/Timestamp sheet. Existing choices,
  adaptive cap validation, settings locks and accepted-default writes remain.
  Invalid raw cap and current selections survive Activity recreation. This is
  framework recreation state, not a new camera draft/preferences schema.
- Work → New timebox opens setup: trimmed title (1–120 code points), optional type
  (omission → To-be-sorted), whole minutes 1–1440 (default25; 25/45/60/90 shortcuts),
  optional outcome (0–2000 code points, preserving nonblank text/newlines).
- Autosaves report Saving/Saved/error. Saved acknowledges matching form bytes,
  not an older queued edit. Back retains a draft; explicit Discard consumes only
  that draft. Save atomically creates one planned intention and consumes its UUID;
  repeated submissions cannot create another identity. Save never starts work or
  camera. Detached-form callbacks cannot resurrect consumed/discarded drafts.
- Read-only detail exposes original plan, identity, creation/start/end clocks and
  links. Start is explicit; replacing a running timebox requires End current /
  Start new confirmation. End is terminal/idempotent. Copy as new copies metadata
  into a new draft, never execution/links or the old identity.
- Work, Sessions and settings are same-Activity panels. They do not detach the
  preview or invoke capture controls. Actual external/background navigation still
  pauses capture through the original lifecycle path; return never auto-resumes.
- Sessions retains eligible manual export/last-video, retry, exact output opening
  and individually confirmed source deletion. Recording details retains every
  original diagnostic. Work/recording links explicitly mean capture time, not
  MP4 seek positions; unavailable video never removes context.

## Ownership, timing and recovery

`WorkSession.kt` contains pure validation, Start/End/deadline policy and half-open
interval intersection. Stable type keys and canonical UUIDs are persisted.
Original planned minutes never change after Save.

`WorkSessionStore.kt` owns only `filesDir/work-context/state.json` (now v2, supporting
v1 reads; archive and delete scope/migration are documented in the extension above). It is outside
`sessions/` and `export-attempts/`; no media sidecar, cleanup allowlist, sampling,
codec or published-video ownership change is introduced. One process-wide
`WorkSessionCoordinator` serializes all work/draft/span I/O on its worker. The
Activity and alarm receiver use that same owner. Atomic publication/readback
precedes success. Recovery rejects unsupported, ambiguous, corrupt, aliased,
hard-linked, unexpectedly changed or interrupted work bytes without replacing
those bytes with empty/older state. Work errors leave camera/media recovery
independent and keep the form/error available for explicit retry.

Timeboxes use boot count + elapsedRealtime and a start wall anchor. Same-boot
reopening reconciles against the original monotonic deadline. Logical expiry is
at that deadline, not the callback time. A bounded 30-second running checkpoint
provides a reboot lower bound. Boot mismatch ends unfinished work as interrupted,
with uncertain actual end; it never resumes or invents post-boot elapsed time.

Exact elapsed-realtime wakeup alarms use an explicit immutable PendingIntent with
one never-reopened work UUID as generation/identity and a non-exported receiver.
Only explicit Work actions request notification or exact-alarm access. Denial,
blocked channel, scheduling failure and force-stop limitations are disclosed;
logical expiry is reconciled on reopening. Generic notifications contain no
activity title/outcome. Stable notification tags and only-alert-once bound
repeated delivery, but a commit/notification crash window is **best effort**, not
an exactly-once or latency guarantee. No boot receiver or foreground service.

Capture integration adds admission, first-durable-photo, bounded checkpoint and
pause/close events only; it does not drive work Start/End or alter capture cadence.
The first completed photo retains its shutter anchor, not its save callback time.
No frame means no link. Pause/background/rollover close spans under exact segment
IDs. Only the first completed frame writes work context; subsequent per-frame
saves perform no work-store/history scan. Existing five-second capture checkpoints
also confirm a span lower bound. On process recovery, open spans close at that
last confirmed evidence with an unknown tail, never at restart time. Open link
ends are explicitly provisional. Associations derive deterministically from the
persisted work/span context, so replay does not create duplicate association IDs.

## Checks and coverage limits

Added four host classes and six Android classes (plus shared UI fixture helpers).
Original test files and media assertions remain byte-exact, including TTL-006.
Host checks exercise validation, timing/replacement/reboot policy, interval algebra,
LIVE/control truth tables and explicit color contrast. Android sources cover store
faults/drift/aliases, coordinator/alarm seams, durable replay, cleanup retention,
production forms/rendering, detached callbacks, recreation and layout fixtures.

**Compiled Android cases are not executed cases.** The layout test uses labelled
linear text scaling at two viewports; it is not real nonlinear system font-scale,
IME, TalkBack or physical-camera evidence. The recording-link fixture uses exact
synthetic segment IDs; it is not an elapsed two/three-hour physical rollover trial.
Scheduler/process/reboot injections are not OS notification/force-stop/reboot UAT.
Actual active-capture navigation, rollover/link integration, retained-session
sheet locking, keyboard/insets and all mapped r003 scenarios still need execution
and repair if they fail. Existing nonempty Pixel data must not be cleared to make
Activity guards pass; obtain a genuinely suitable authorized disposable context.

`tools/verify_timebox_results.py` imports only generic TTL-005 safe primitives.
It never invokes the older validate/authority, Gradle, ADB, network or phone tools.
Begin/final are absent-only, bind clean exact HEAD/authority/raw tracked inputs,
attribute-normalized blobs, unchanged original tests and exact method identities,
fresh ordered markers/XML/APKs/lint/report hashes, and reject partial/stale/aliased,
drifted/failed/skipped/duplicate evidence. External Git filters and hidden index
flags are rejected. Self-tests allocate isolated synthetic repositories/reports;
their successful result is explicitly **not** an app matrix pass. Real begin/final
have not run on a frozen candidate in this deployment.

`.github/workflows/android.yml` adds PR, develop-push and manual hosted checks:
Ubuntu24.04, Temurin17.0.16+8, platform36/build-tools35.0.0, host tests, both debug
APKs and lint, read-only token, no custom secrets/device/release/deployment jobs.
Evidence uploads run on failure too. Action pins were checked read-only with
`git ls-remote` against the upstream tag refs:

| Action/tag | Verified commit |
| --- | --- |
| actions/checkout v4.2.2 | `11bd71901bbe5b1630ceea73d27597364c9af683` |
| actions/setup-java v4.7.1 | `c5195efecf7bdfc987ee8bae7a71cb8b11521c00` |
| actions/upload-artifact v4.6.2 | `ea165f8d65b6e75b540449e92b4886f43607fa02` |

No hosted run was initiated. Later obtain a non-skipped successful run whose
head_sha and workflow bytes match the frozen Feature SHA, retaining environment,
run/attempt/event and artifact digests. A synthetic PR merge SHA is not the feature
head. Hosted dependency downloads are not permission for local provisioning.

## Remaining acceptance gates

1. Review/freeze the implementation candidate and authenticate the current base,
   source/matrix tuple and independent-review readiness; historical preparation
   or this implementation's local checks are not that admission.
2. Execute the complete exact-candidate Android inventory in a freshly authorized
   disposable Pixel8Pro/GrapheneOS API37/build2026100601 context, preserving originals.
3. Perform independent V1–V7 accounting/review and resolve Critical/Major findings;
   collect a matching successful hosted CI run and artifact/version receipts.
4. Execute TS-1–14 with separate force-stop/reboot/permissions/settings/TalkBack
   consent and candidate-bound human sign-off. No scenario is currently signed.

No mandatory gate is waived. Prototype atomic snapshots remain transition-volume
storage; if history size makes writes blocking, return for the approved storage
redesign decision rather than silently introducing a database or dropping history.
