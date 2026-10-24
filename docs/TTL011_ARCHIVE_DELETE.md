# TTL-011 archive / delete extension

## Foreground scope change

After reviewing the installed Work UI, Sinh asked how to clean up old timeboxes,
then explicitly requested **“okay please add both archive/delete”**. This adds to
r003's original read-only scope; it does not rewrite the historical signed
requirements or make the old acceptance evidence cover the extension automatically.
Sinh also separately authorized committing/pushing the feature branch and running
the scoped GitHub Actions checks, not merge/release/deployment.

## Behavior

- Planned and ended timeboxes offer **Archive timebox**. Archived entries are hidden
  from the default Work list, accessible through **Show archived timeboxes (N)**.
- Archived details offer **Restore timebox**, preserving UUID, title, original plan,
  execution state and capture-time links. Restore is not Start; an archived planned
  timebox must be restored before it can start. Copy as new from an ended archived
  timebox still produces a new, unarchived planned identity.
- Running timeboxes offer End, not archive/delete. The store/policy rejects stale
  archive/delete requests against a running identity as well; hiding a running
  countdown is not permitted.
- **Delete timebox…** opens a standalone confirmation naming the exact title and
  UUID. Cancel changes nothing. Confirm permanently removes that intention's text,
  outcome, execution metadata and its derived recording associations.
- Neither operation deletes a JPEG, MP4, recording session or another timebox.
  Capture spans remain separate because multiple timeboxes can overlap the same
  recording span. Archived links stay inspectable from Sessions; explicitly deleted
  work no longer appears in recording-link projections.
- No bulk delete, automatic age cleanup or “clear app data” action is introduced.
  Acknowledged unsaved drafts remain independently owned; deleting a copied source
  does not delete the new draft's separate UUID/metadata.

## Persistence and replay

Work storage version2 adds `archived` on saved records and `deletedIds` on the
snapshot. Version1 remains supported. A valid v1 file loads without any write;
the first successful actual mutation atomically publishes the v2 shape. All old
values/identities/draft raw text/span anchors are preserved. Unsupported versions,
malformed booleans, duplicate/conflicting identities and aliased/corrupt bytes
remain retained and block affected work writes, not reset to an empty history.

Delete retains only an opaque UUID tombstone, not the deleted title/outcome/times.
This prevents a delayed autosave, double-submit or crash retry from resurrecting
that identity after the saved record is removed. Tombstones are monotonic across
commits. A restored Android view cannot bypass this guard by reusing a deleted
form UUID. It must create a new intention. This is deletion of user-visible work
metadata/links, not cryptographic secure erasure of filesystem storage.

All transitions use the existing single serialized work owner and atomic snapshot
publication; no media folder, source-cleanup allowlist, capture timer or export
ownership rule is modified. A failure before publication leaves previous bytes;
a failure after publication is reloaded before retry. Archived list/detail
navigation is retained across Activity recreation. The remaining small UI fixes
also correct singular “1 minute” and clear repaired field errors while editing.

## Coverage and later evidence

Added host management-policy cases cover archive/restore idempotency, running and
archived-start guards, link preservation versus selected-work deletion, copy-as-new
and original-plan immutability. Android store fixtures cover v1 migration without
read-time rewrite, retained invalid drafts/media, before/after publication faults,
non-resurrection after delete and malformed v2 rejection. Production-view fixtures
cover archive visibility/restore/confirmation cancellation/exact-ID deletion.
The recording-link fixture asserts archived context survives and confirmed deletion
removes only selected work links while retained JPEG bytes stay unchanged.

An additional injected production CaptureViewModel/coordinator test exercises one
recording spanning work A, an unassigned gap and B, confirming actual admission /
first-durable-frame / pause hooks without starting a physical camera or Gallery
export. Injection is not a real camera or elapsed rollover result.

## Executed implementation checks

Sinh authorized updating the app/test APK, the exact12 isolated store/execution/link
methods and one new synthetic management-UI record. On Pixel8Pro/API37/
build2026100601/user0, all **12/12** executed once with failures/errors/skips0,
including the injected production CaptureViewModel A/gap/B test. No empty-context
Activity fixture, physical camera or Gallery export was invoked. Existing data was
not cleared/uninstalled. This subset is not all80 Android methods or the independent
matrix. The instrumented process may restart as disclosed in the permission request.

The installed physical UI trial created `TTL011 CLEAN Q`, archived it, verified it
was hidden from the normal list, opened the archived list and restored it with
exact UUID/original plan unchanged and no Start. A second archive followed by
Delete → Cancel kept it. Explicit Delete then removed only that new owned record.
All five prior visible synthetic UUIDs remained, also exercising the real v1→v2
upgrade. The agent did not delete old/user timeboxes or invoke any media action.

Host **67/67**, both APK builds and lint **0 errors /55 warnings** pass locally;
all80 Android methods compile. Accounting self-tests6/6 and19/19 pass.
Current warning groups: SetTextI18n38, UseKtx13, LockedOrientationActivity1,
DiscouragedApi1, DataExtractionRules1, ViewConstructor1; prior proposed dispositions
apply, not independent sign-off. App installed/local SHA-256:
`d413acd7ae2e4c25336071dc141749b0d1c8c686e830ba6708fb2b372b3a1730`.
Test APK installed SHA-256:
`c81cc23d5196782dd77896f1256e7eb28c855ac36613c2b31b4f5f3ce95c3f19`.

Receipts, exact selected-method accounting, original command streams, UI JSON/
screenshot, source hashes and local reports are under ignored restricted
`app/build/ttl011-phase-evidence/d-eeedd5-management/`. One initial shell packet
was refused before execution because its redirect target was not an admitted temp
path; no install/test was claimed from it. The same authorized scope then executed
with its original stream retained in a system-temp file. No failed product test was
hidden or baseline assertion relaxed.

Sinh authorized commit/push/CI and selected a **draft PR to develop** to register/
trigger the new workflow (default GitHub branch develop, no existing workflow).
This does not authorize merging or rewriting the historical main-target policy.
Exact candidate/PR/run identities and subsequent hosted results belong in the
external ticket/PR receipts, not a self-referential source SHA. At this document's
write, hosted CI and independent review are still pending. Full80 Android, real
capture/rollover/process/reboot/permission/TalkBack UAT and independent matrix remain
outstanding; neither this subset nor the prior audible notification trial closes them.
