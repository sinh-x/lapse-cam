# Blackout: capture safety versus unrelated attention

Blackout remains an opaque black cover with the existing red LIVE banner, not a
brightness override. `BlackoutPolicy` still owns the monotonic 30-second deadline
and callback generation. `PhoneMotionPolicy`, capture admission/cadence, media,
retry accounting, cleanup and Work persistence are unchanged.

## Safety is typed, not a keyword match

`CaptureViewModel.State.blackoutSafetyError` is the production boundary used by
`MainActivity`. Diagnostic strings are not permission to cover or to remain awake.

| Producer / operation | Display impact and resolution | Incident identity |
|---|---|---|
| Loading, create, ready/resume, ordinary pause, rollover/close, manual export progress/success | Routine; actual live/readiness flags still gate blackout | None |
| Camera admission/loss/binding error | Capture safety; absent camera or non-live state blocks, healthy Resume starts fresh | Existing camera UI, not an unrelated notice |
| `fail`: recovery/create, photo/storage check exceptions, frame intent/commit, JPEG/checkpoint/index errors, close or manual-export failure | Capture safety; existing stop/recovery behavior retained | Existing error UI; never relabelled independent |
| Capture-space exhaustion; active-time exhaustion | Capture safety; capture stops, recovery block retained where applicable | Existing error UI |
| Saved queue records: export/verification, cleanup, deletion; nonempty error or NEEDS_ATTENTION (including empty error with Retry) | Independent attention. Export receipt proves export resolution; terminal FINISHED/DELETED/EMPTY proves completion; cleanup failure is separate | Session ID + operation UUID + EXPORT/CLEANUP/DELETE |
| Unexpected errors attached to ACTIVE/PAUSED/CLOSING repository records | Conservative capture-safety block, regardless of selected session ID | Not treated as a harmless backlog error |
| Fresh `VideoExporter.reconcile` failure after repository hydration | Independent; successful explicit recovery resolves display episode | Export-recovery subsystem |
| Tick/queue publication exception, `retrySession` exception, direct delete exception | **Unknown shared-index/store impact**: conservative display block until a complete successful queue tick/publication. Per-record queue/delete failures successfully journalled and published are independent | Queue subsystem, retry item, delete item/operation |
| Work initial load/reconcile, draft/save/edit/discard, archive/delete/start/end/expiry, copy/metadata, capture-context failures | Independent of Camera. Existing Work error, snapshot/draft retention, locks and acknowledgement rules unchanged | Store subsystem or operation + affected Work item/span ID |
| Work expiry/end terminal changes | Existing separate `BlackoutPolicy.observeTerminals` behavior | Existing terminal ID/end marker |
| Work reminder/scheduler access diagnostics | Existing Work reminder UI and notification behavior unchanged | Not converted into a capture error |

The unknown shared-index boundary is deliberate: an exception during an atomic
index write/readback is not proof that capture's shared repository is healthy.
A persistent such exception may still prevent blackout. This is distinct from
successfully loaded historical NEEDS_ATTENTION records and normal repeated export
or cleanup failures, which cannot indefinitely veto cover or motion monitoring.
A successful queue pass clears only the transient display uncertainty; it does
not clear a saved error, repair data, retry an operation or establish media safety.
Other concurrent capture/recovery/camera blockers still win.

## Hydration and incident lifetime

`DisplayAttention` is an in-memory reducer owned by CaptureViewModel and the Work
process singleton, not by the disposable Activity. Session baseline observation
occurs after `repository.recover()` returns and **before** fresh exporter recovery
or queue execution. An initial empty LiveData placeholder never seeds the
baseline. Subsequent recovery does not rebaseline and silently swallow new errors.
Work has no persisted error/seen field; errors from fresh load/write attempts are
new runtime incidents, while an already delivered singleton episode does not replay.

Keys are causal item/operation identities, never error wording, frame counts,
countdowns, timestamps or automatic-attempt numbers. An unresolved failure's
latest diagnostic may change without another delivery. Partial/empty snapshots
and observer reattachment do not resolve it. Verified export completion resolves
export, not cleanup. Terminal records resolve their item operations. Only an
admitted explicit Retry cycle rearms the corresponding record incident; rejected
Retry requests and automatic attempts do not. Direct user deletion similarly
starts its display cycle without adding any deletion admission.

Work episodes resolve on the corresponding successful acknowledged operation;
existing explicit successful writes also establish store recovery. Successful
save/discard resolves the associated draft failure. This metadata is separate
from Work's existing error-clearing logic: noticing or delivering attention never
claims a draft was saved. Work errors retained by reconciliation remain retained.

Pending delivery uses one saturating counter per destination (constant storage,
not a queue of emissions); unresolved detail storage is proportional to affected
item/operation keys. Resolution does not erase a pending notice. Multiple pending
incidents may coalesce. No persistent schema or seen-marker writes are introduced.
Configuration recreation retains pending/delivered incident state in its owners,
but never restores the old cover, idle time or callbacks. Cold process launch
establishes the saved backlog baseline again.

## Delivery and discovery

`MainActivity.updateBlackout` first checks foreground, actual Activity focus,
modal depth, external picker state and a pending Work-navigation intent. Background
or modal incidents remain pending. It never opens a destination automatically.
Delivery reveals Camera/restores bars, exposes a polite non-modal notice, and
resets the eligible deadline once. Acknowledgement is not required. If capture
isn't live or another safety signal blocks, delivery cannot arm or show LIVE.
Final modal dismissal **and** regained focus are required to start a new interval.

Sessions and Work buttons retain attention labels/counts. Sessions retains all
original rows, errors, attempts, Retry/delete/video controls; a scrollable detail
area also exposes subsystem notices not represented by a saved row. Work retains
its existing error/draft/lock UI and displays unresolved diagnostic details.
Routine diagnostic updates are not repeated polite Camera alerts. The cover
itself is unchanged and contains no attention panel.

Delivery during a held return gesture/key reveals immediately but blocks rearming
until release; existing consumption markers swallow its remainder. Content-change
accessibility events do not count as interaction. Motion monitoring no longer
uses historical SessionReview errors or Work errors as a global veto. All existing
focus/lifecycle/modal, true capture-error, motion/quiet, safe-return and system-bar
rules remain in force.

## Regression inventory and evidence boundaries

- `DisplayAttentionTest`: completed-hydration baseline versus new startup failure;
  error-only/Retry-only variants; automatic failure/text/progress deduplication;
  partial omission; explicit resolution, category transitions and recurrence;
  Work subsystem/item identities; pending delivery; production typed safety
  projection; exact 20s notice / 50s deadline and stale-generation rejection.
- `BlackoutActivityTest`: adds historical error/Retry integration, preserved row
  controls, motion ownership, new Sessions/Work one-shot delivery, Work snapshot
  preservation, duplicate emissions, concurrent safety, foreground/nested-modal
  ordering, held-input return and pending/delivered recreation fixtures. Existing
  input, bars, lifecycle, motion and accessibility tests remain.
- `DisplayAttentionProducerTest`: isolated corrupt Work-store fixture checks real
  load/reconcile and draft failure publication, stable episodes, retained bytes,
  unsaved draft and loading/action locks. It does not touch the original store.

Host tests and APK compilation do not execute these Android fixtures or establish
physical behavior. Follow [the validation allocation](VALIDATION_WORKFLOW.md):
relevant Android checks need a separately authorized disposable Pixel context.
Flat/upright/tilted placement, real pickup/quiet/rearm, readable/accessibility
notices, continuing real capture/cadence and sampled media remain device/human
checks, not deductions from gravity-vector or synthetic-state tests. No device
execution, full UAT, independent review or integration approval follows from this
implementation document. Preserve original app data; do not use auto-uninstall,
clear-data, session repair or valuable-media fixtures.
