# Sessions and Timeboxes browsers

Both destinations are full-window, in-Activity surfaces. Browsing does not itself
Pause/Finish a recording or admit background camera work. Modal/focus accounting
keeps the blackout cover off browsers, details and confirmations; returning to
Camera uses the existing fresh-idle rearming policy. External players/background
still follow the existing lifecycle owner. TTL-016 owns services, notifications,
background admission and camera policy; this feature does not implement them.

## Shared presentation

Camera-home dark/amber native controls are shared by Sessions, Timeboxes and
Private sync. Capture indicators derive from the actual capture owner, not queue
activity or a remote record's RUNNING state. Return to Camera is navigation only;
unsaved local timebox edits retain their existing confirmation guard. Sessions
capture and output/recovery details are separate cards, and its processing overview
and current-session actions scroll with the recycled list. Timeboxes has a child
End reminders page for notification and background-timing access.

Private-sync nested pages preserve their parent; recreation restores cached list
filters/page, selected source/record/video IDs and scroll positions without transfer
or lifecycle actions. Tokens are excluded from browser saved state and cleared on
closing enrollment. Queued proposals remain owned by sync; unqueued editor text is
not a durable proposal. See [Camera-home design and focus safety](APP_DESIGN_FOCUS.md)
for the output boundary, strict focus gate and visual/device verification checklist.

## Dates and navigation

Sessions use the recording's actual start instant in the current phone timezone.
Ordinary entry shows Today (including an explicit no-Today state); other dates
start collapsed with unique-session counts. Expanding dates mounts summaries
through a recycling ListView, not every historical action/detail panel.

Timeboxes preserve archive/type filters. Their directly visible top list contains
all matching saved unstarted items first, then today's started items. Other
actual start dates have collapsed headers. Creation time never supplies a start
date. Newest/Oldest/Activity type act within each portion/date, not across date
groups. Unstarted items use saved time for ordering only. The running summary,
drafts, actions and stored Work* identities remain unchanged. The **Work**
activity-type category is still Work; the feature destination is **Timeboxes**.

Back from Sessions details returns to its originating ordinary/attention list,
then Camera. Saved-instance state retains selected identity, expanded dates,
list positions, Timeboxes filters/sort and destination stack order. Refreshing
phone date/timezone recomputes date projections without changing record IDs.
Date-contiguous history reuses local-day bounds rather than converting every
instant separately. Any day containing an offset transition falls back to direct
conversion, including historical date-line shifts; extreme epoch bounds also use
the direct path. This is metadata projection only, not a stored-date migration.
Incoming `sessionId` or `sessionsAttention=true` Intent extras select a useful
Sessions destination; stale IDs explain unavailability. These routes are not
Android notification/PendingIntent delivery evidence.

## Attention versus causal notices

Needs attention is an all-date, deduplicated **session** review. It uses typed
processing state/evidence: NEEDS_ATTENTION (with honest fallback diagnostics),
blocked explicit deletion, or an observed unavailable output. Ordinary queue,
active work and scheduled automatic retries are not themselves attention errors.
Details retain stage/reason, source/output state and eligible existing actions.
Unknown historical failure provenance says **Stage unknown**, never a guessed
stage parsed from error copy or the sampling/thinning stage.

TTL-017's causal incident notices are separate. An automatically retrying incident
can retain a notice without entering the actionable-session count. Unassigned
queue/recovery/output-review diagnostics remain discoverable without invented
session IDs. Reading a list never resolves incidents or starts retry/deletion.
Actions still revalidate in their existing authoritative owner. Source deletion
still requires an explicit confirmation, inventories and alias/unknown-file
checks; cleanup still requires full publication verification, not a read check.

## Bounded output observation

`output-observations.json` is an independent AtomicFile sidecar (writes version 2,
reads versions 1 and 2) owned
by the serialized session executor. It does not rewrite the session index,
receipts, operation identities, attempts or original media. Missing sidecar is
compatible with old stores; corrupt/unknown evidence is retained and reported
as unavailable, not silently reset to an empty successful review.

Detail entry and **Recheck video** request a lightweight read on the existing
media executor. Admission keeps one read in flight and one latest pending
request, deduplicates detail-entry tokens, and never scans/decode-checks the
whole library. Open uses the same read/evidence path; no installed player is not
a missing-video error. Results carry session/URI/known-operation identity and
are rejected if that output changed. Persisted evidence distinguishes readable,
missing, permission-blocked, unreadable and unknown, with observation/attempt
times. An inconclusive attempt cannot erase a previous conclusive problem.
Successful current reads resolve only the corresponding availability issue.
The owner-approved diagnostic extension also retains one last observed manual
export failure per session (operation identity, proven stage or Unknown, reason,
and observation time). It survives process restart without a second queue,
retry budget or deletion authority. A read/Recheck or acknowledgement cannot
clear that failure. Only a proven publication success from the actual current
manual producer resolves that session's manual failure; stale operation results
cannot overwrite or resolve another attempt. Version-1 evidence loads without a
write; the next evidence mutation upgrades while retaining availability data.
Corrupt, duplicate or unknown fields remain retained and block overwrites.

## Processing and bounded updates

`ActiveProcessing` carries actual session/operation identity, stage and optional
encoding percentage. The existing queue/manual producer owns it; CLEANUP phase
or a nonempty queue alone is not activity. Encoding 100% transitions through
indeterminate verification/publication/cleanup instead of claiming total
completion. Camera and Sessions distinguish actual work from queue/retry wait
and idle. Manual exports and explicit source deletion are attributed too.

`LatestUpdate` bounds redundant progress/review delivery to one pending routine
update and yields to already queued work. Durable writes/reservations, terminal
transitions and incident observations are not routed through that mailbox.
Maintenance admission is bounded separately. Recovery uses one immutable index
ID set rather than rebuilding/scanning the entire index for every directory.
Unchanged Timeboxes row projections and sync-association hints are reused.
Historical SessionReview objects are reused until the repository's review
revision or foreground admission context changes; actual operation identity,
progress and output observations remain live overlays. Bounded source cursor
steps invalidate that generation even without an index rewrite. Failed
projections never commit a successful cache key. Sessions date grouping is
reused while IDs/start dates/attention membership remain unchanged, but current
status/action labels still come from the latest snapshot. Causal snapshots are
cached only between real transitions and expose an unmodifiable details map.
No publication verification, inventory validation or durable attempt accounting
was removed to improve timings. Sync source observation now reports whether it
actually added a source mutation; identical observations avoid rewriting the
whole sync file or cloning/publishing an unchanged pending-request backlog to
the UI. Strict store reads and a final byte/path/identity validation still run
on no-ops; changed live links, tombstones and source transitions still enqueue,
commit and publish in order. A single immutable source-input fingerprint (saved
records, tombstones and exact derived capture links) avoids re-projecting an
identical observation; unlinked capture checkpoints and drafts add no source
mutation. Live-link advances and metadata/deletion changes invalidate it. This
fingerprint is recorded only after successful validation/commit/publication;
external byte/path changes are still checked on every no-op.

## Validation boundaries

See [VALIDATION_WORKFLOW.md](VALIDATION_WORKFLOW.md) and the
[isolated baseline harness](TTL018_BASELINE.md). Deployment evidence, approved
numeric budgets, failures, exact source/APK identities and scenario outcomes
belong outside this repository. Unit or component tests do not establish real
capture cadence, text scaling/TalkBack, physical blackout behavior, process-death
navigation, lifecycle notifications or full UAT. Preserve failed/censored runs
and do not relax frozen budgets to make them pass.

Legacy/manual failures that predate diagnostic evidence cannot be reconstructed
or assigned an invented stage. Newly observed manual failures persist as the
owner-approved diagnostic evidence above, not a processing authority. Persistent
historical queue failures still use their existing repository. Remaining
performance/device failures require owner review before integration acceptance.
