# TTL-014 — owner-accepted as-is local closeout

## Decision and scope

In foreground deployment `d-0d4044`, after the remaining gaps were listed, Sinh said:

> okay accept it as is for now and merge to develop

Durable decision: TTL-014 comment `c-20261031082458342`.

This is **owner acceptance as-is with known limitations**, authorizing the scoped
source commit and a local merge into `develop`. It is not evidence that deferred
checks passed, a production-readiness certification, or a repair of historical PA
workflow/preparation denials. The explicitly requested `develop` destination is a
one-time exception to the repository's default `main` integration guidance; that
guidance itself is unchanged. No push, `main` update, host activation, phone action,
worktree/lease retirement or unrelated source changes are authorized by this closeout.

Before integration: feature `feature/TTL-014-private-sync-server` at `967380b` plus
this deployment's scoped overlay; local `develop` at `856ee74` already contains
TTL-012 saved editing/filtering and the unrelated Linux webcam exploration. Preserve
both while combining the feature. Exact final commit/merge identities are recorded
in Git history and the ticket closeout comment, avoiding a self-referential hash here.

## Included implementation

- Native private archive/server and Android durable sync/shared history from the
  original feature commit, with source-owned clocks/media links and field conflicts.
- Enrollment progress and secret-safe explicit success/error feedback, plus ten
  host regressions. Both phones received the pre-merge feedback APK in-place.
- Browser-origin fix: `Referrer-Policy: same-origin`; exact-origin/CSRF/TLS/CSP and
  cookie security remain enforced. The running host has not adopted this fix here.
- Reusable named/default native packages/apps, optional NixOS module exports and
  pure consumer checks, with the input-owned locked compiler and offline Go tests.
- Fingerprinted Drgnfly input-migration patch, baseline and operator guide, tracked
  separately in **NX-067**. Its original baseline/source descriptions are historical
  draft facts; do not rewrite their fingerprints or infer permission to apply them
  after host drift. No real personal-nixos source/lock was changed by this deployment.

## Observed evidence and limits

- Pre-merge feedback build: 85 host tests pass, both APKs build, lint has zero errors
  and 72 unsuppressed warnings. Those are local checks, not independent acceptance.
- Each phone previously passed the explicitly scoped 16 isolated sync methods on
  the original candidate; no full valuable-data instrumentation suite was run.
- OPPO reported successful authenticated sync with three records/five videos and
  no pending/rejected work. This is not a current independent whole-server inventory.
- Pixel's 23 saved records and work/sync control hashes survived the update. Its
  existing export cleanup removed 16 raw frames from one frozen inventory; receipt,
  operation/selection hashes and actual retained published MP4 SHA-256 were checked.
  Do not misdescribe that update as an entirely unchanged media tree.
- Pixel network troubleshooting found GrapheneOS Network permission denied for the
  app and, earlier, always-on VPN lockdown with no active VPN. No settings were
  changed by the agent. The owner later reported syncing; fresh server-side Pixel
  inventory was not obtained, and no current upload-completeness claim is made.
- The browser-origin failure was reproduced with a sandboxed Chromium synthetic
  form; Go origin/security regressions, vet and all-five-package offline Nix build
  passed. Synthetic browser behavior is not authenticated live browser observation.
- ARM exports/consumer fixtures were evaluated, not built/run on ARM.

## Accepted outstanding work

1. **NX-067:** apply/reconcile the host input patch, capture only its named lock
   update, build/review the complete host candidate, and obtain separate operator
   activation and service/coexistence checks. Retain existing archive, passwords,
   device tokens, preparation/renewal and persistence. No re-enrollment/reset.
2. After the running server adopts the fixed package, verify real `/login`, combined
   history, playback and verified downloads with the existing browser account.
3. Independently confirm Pixel/server records and video completion and the complete
   two-phone workflow, including descriptive conflicts, offline/resume/restart and
   local-delete/server-retention behavior.
4. The original independent nine-command matrix, matching hosted CI, independent
   findings disposition and sixteen-scenario human UAT remain unexecuted/unproven
   as previously documented. Owner acceptance defers them; it does not mark them pass.

Historical implementation/runbook/requirements artifacts retain their original phase
claims. This note and the explicit owner decision supersede only their acceptance/
merge queue state. Retain the feature worktree for NX-067's local-path handoff; a
published immutable source pin remains a later authorized step.
