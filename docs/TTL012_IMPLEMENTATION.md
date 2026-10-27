# TTL-012 — saved editing and list organization

Implementation branch: `feature/TTL-012-edit-sort-timeboxes`.
Base: `bbe3be26d2e9985c4ceca2e0caed57411d8a0fa5`.
Deployment: `d-766574`.

## Behavior and safety

- Planned and ended records, including archived ones, reuse the create form in
  **Edit timebox** mode. Save updates only title, Activity type, expected outcome
  and (planned records only) minutes. Running records cannot be edited.
- Ended minutes and duration shortcuts are disabled. Policy/store enforcement
  preserves ID, creation time, timing, reason, confirmed elapsed, archive status,
  spans, links and deleted identities. The create/save-idempotency path is not
  used for editing. No schema, dependency or capture implementation changes.
- Saved edits live in a separate coordinator buffer: no edit autosave and no
  writes to the persisted or live create draft. Cancel and Back (including the
  dialog's legacy cancel path) offer **Keep editing / Discard changes** when
  dirty. Save returns to the same detail; discard returns without changing it.
- The original record snapshot and an in-process per-record revision must match
  on the serial store executor. Revisions detect even archive/restore or metadata
  round trips with identical final fields. They are not stored: an editor lives
  through Activity recreation, not process death. Other records/drafts/spans
  changing do not invalidate an unchanged target.
- Publication errors retain the buffer and expose a failure. The coordinator
  attempts a store reload before enabling retry. A published edit with a failed
  acknowledgement is not reported as success or blindly replayed; its old edit
  baseline conflicts. Recovery-blocked bytes are never reset or cleaned up.
- List controls select All or an existing Activity type and Newest created,
  Oldest created or Activity type ordering. Ties are creation time descending
  for type sort, then ID ascending; chronological modes use ID ascending ties.
  The projected rows are explicitly reordered without reordering stored records.
- Type labels and separate no-match/empty-partition messages are visible. The
  active running summary and End remain accessible even when filtered out;
  archived view does not show the running summary.
- Filter/sort persist through detail/edit/archive navigation and Activity
  recreation via saved instance state. A newly opened Work window resets to
  All/Newest created. Countdown renders do not rebuild the form or steal input
  focus. Input and action targets retain the existing minimum 48dp sizing.

## Tests and execution boundary

Added host `WorkEditPolicyTest` and `WorkListPolicyTest` (9 methods), covering
allowed-field updates, all ended reasons and timing/links, stale targets,
validation/code-point boundaries, draft isolation and deterministic projection.
Extended store, execution/coordinator, Activity and recording-link fixtures with
edit publication faults, archive/restore round-trip conflicts, retained input,
discard confirmation, editor recreation, locked controls and visible row order.
These instrumentation additions compile; compilation is **not device execution**.

During implementation, offline host tests passed (76 methods, no failures,
errors or skips), both debug APKs built, and lint reported no errors/fatals.
Fresh final builder receipts are to be bound to the local candidate commit under
`app/build/ttl012-builder-validation/` with stdout/stderr, exact-source host
accounting, APK/report hashes and warning dispositions. This is supplementary
builder validation, **not** an independently authorized V1–V4 review run.
The generic accounting functions are reused; historical ticket-bound validator
entry points and old ticket results are not used as TTL-012 authority.

### Builder-proposed minor warning dispositions

The implementation lint run reports 60 warnings; the final report retains every
occurrence/location and attaches the following rationale to each. None is
suppressed. Independent review must confirm these dispositions.

| Warning | Count | Proposed disposition / rationale |
|---|---:|---|
| SetTextI18n | 41 | Defer localization of the existing English-only native UI, including the new filter/sort labels; no locale expansion is part of this ticket. |
| UseKtx | 13 | Defer optional style substitutions; current platform calls are supported and changing unrelated capture/storage code adds risk. |
| GradleDependency | 2 | Retain approved pinned dependencies and offline toolchain; dependency upgrades/downloads are outside scope. |
| LockedOrientationActivity | 1 | Retain existing camera orientation behavior; large-screen/orientation redesign is outside scope. |
| DiscouragedApi | 1 | Same existing orientation compatibility concern; do not change camera behavior for metadata editing. |
| DataExtractionRules | 1 | Defer explicit backup/transfer policy work; this ticket adds no storage schema or backup behavior. |
| ViewConstructor | 1 | WorkSessionsView is constructed programmatically with a coordinator and callbacks, never inflated from XML/tools. |

Compiler deprecations for existing soft-input resizing/accessibility announcement
APIs and the Gradle future-version notice are retained. The API36/pinned-Gradle
build succeeds; replacing those compatibility paths or upgrading the toolchain
is not part of this change.

## Outstanding acceptance gates

No device, ADB, install, app-data cleanup, camera capture, network, push or CI
operation is authorized by these results. Separately authorized isolated-device
store/execution/UI/link tests (zero skips), same-candidate hosted CI, independent
review with its approved V1–V4 matrix, and actual human UAT including font scales
1.0/2.0, IME/Back/accessibility remain required. Product acceptance, merge and
checkout return are not claimed. Keep the leased worktree for operator review.
