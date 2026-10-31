# TTL-014 — implementation and remaining acceptance gates

**Later closeout (2026-10-31):** Sinh explicitly accepted the current work as-is and
requested local integration into `develop`, with deployment and validation gaps
retained. See the [owner decision and current limits](sessions/d-0d4044-ttl014-closeout.md).
The phase-specific implementation evidence below is historical, not a current claim
that phone installation/service observation never happened or that deferred UAT passed.

Selected worktree: `/home/sinh/.treehouse/timestamp-timelapse-230d79/4/timestamp-timelapse`.
Branch: `feature/TTL-014-private-sync-server`; approved base
`bbe3be26d2e9985c4ceca2e0caed57411d8a0fa5`.
Current continuation: `d-1e8bbe`; first server/policy milestone: `d-8eb464`.
Requirements/UAT authority: approved r002 pair, Sinh comment `c-20261027124822121`.

**Implemented source is not an accepted release, executed phone UAT or deployed
service.** The unchanged nine-command matrix belongs to a separately admitted
independent executor. There have been no phone installs/tests, live listeners,
certificate/Tailscale/firewall/service changes, merge, push or checkout return.
Sinh separately authorized committing the ticket source and **read-only**
review/device-readiness discovery in the foreground of `d-1e8bbe`; this does not
permit later instrumentation or deployment.

## Approved plan implementation

| Phase | Source delivered | Remaining evidence/gates |
|---|---|---|
| 1 — contract/policies | Version-one protocol; per-field merge/provenance/conflicts; source namespaces/clock/link validation; Unicode/copy/network policies; additive description v3 with v1/v2 readers | Physical migration and complete independent regressions |
| 2 — isolated native server | Trusted tailnet-only TLS listener; hash-only per-device credentials/revocation; browser login/logout/CSRF/throttling; durable metadata/receipts/manifests; resumable verified upload; complete-only range media; filters/read-only browser/storage warning; restart/fault/restore fixtures | Independent security/scale review, browser observation, actual permitted tailnet/service/coexistence UAT |
| 3 — Android sync | Separate serialized SyncStore/Coordinator; durable installation identity and exact requests; Keystore AES-GCM credential storage; explicit enrollment/opt-in with identity check; source-only snapshots and metadata patch seam; JobScheduler Wi-Fi/default/manual-mobile policy; verified resumed chunks and stable completion/missing receipts; separate history replica | All Android fixtures currently **compiled only**; real server/two-phone integration remains unproven |
| 4 — phone UI | Settings/status/queue; offline combined paginated history and device/date/category/status filters; descriptive editors and preserved conflicts/revision-checked resolution; source-only execution controls remain in local Work; authenticated explicit range playback; verified SAF downloads; fresh planned Copy as new; description setup/detail | Compiled UI fixtures are not rendered/observed human acceptance; two-phone playback/seek/accessibility/mobile-consent UAT outstanding |
| 5 — packaging/accounting | Offline native package, disabled-by-default optional module/pure isolation fixture, operator/recovery instructions; `tools/verify_sync_results.py` self-tests, exact-source/Go/host/Android inventories, native/Go TLS/fault/module wrappers, explicit private device authorization and full-suite accounting | Strict wrapper stages require a clean exact candidate and admitted independent context; no full matrix has run; lint warnings need independent disposition and candidate-matching hosted CI remains separate |
| 6 — review/UAT/deploy | No acceptance claimed | One exact frozen SHA, supported independent runtime/one-use authorization, all readiness/tool/device gates, nine-command executor ledger, Critical0/Major0/Minor rationale, sixteen-scenario human UAT, separate live adoption |

## Android safety and data ownership

- Sync I/O uses its own executor; Work/capture owners post immutable observations
  without waiting for network or sync storage. Main-thread sync effects explicitly
  reject. Timer execution, clocks, duration, archive/deletion and recording links
  never come from shared-history editing. A history fetch does not replace private
  WorkSessionStore; foreign/deleted records are never resurrected there.
- Source snapshots include only saved work. Per-record sequences and exact pending
  requests/UUIDs are committed before transport. Retries reconcile old receipts,
  then fetch a fresh replica rather than applying historical response bodies.
- Description changes use the same four-field revision protocol for either phone.
  A stale rejected resolution retains its original proposal/base in the queue UI;
  it is not marked acknowledged and does not block unrelated new resolutions.
- Sync state is private mode0700/0600, unaliased, canonical and fsync/rename-published.
  Interrupted/unknown versions/files, external changes, missing initialized state
  or a missing credential key fail closed with retained bytes. Decryption does not
  silently generate a replacement key. Fixture key aliases are unique and cleaned
  without touching the production alias.
- Export provenance is an additive immutable catalog recorded before publication
  for **manual, Off, Fixed and automatic split** exports. Encoding, actual-output
  verification and source-cleanup algorithms remain unchanged; uploads never gate
  JPEG cleanup. The inventory queries only exact app-owned published UUID MP4s in
  this app's Movies folder, never unrelated Gallery/JPEG/draft media.
- Existing split receipts and exact manual-output preferences supply asynchronous
  recording hints from the media owner. Unproven older associations stay explicitly
  unattributed; dates are never used to guess links. Legacy discovery waits for
  app-open media-owner recovery; known provenance-backed new exports can be handled
  by background jobs. Once an upload identity/description is queued it is frozen.
- Actual published H.264/container bytes are reopened and streamed through SHA-256;
  chunks are <=1MiB (server limit8MiB). A reconnect checks the exact local/server
  verified prefix before resuming. Lost begin/chunk/complete replies retain one
  stable export identity. Source disappearance cannot fabricate archive success.
- Jobs require Wi-Fi and recheck active transports before each network request;
  cellular takes precedence over mixed capability reports. Unknown/VPN-only transport
  attribution fails closed, so some VPN/OS combinations may need manual network
  troubleshooting rather than a delivery-time promise. Manual mobile transfer and
  playback/download each require explicit confirmation; cancellation sends no bytes.
- Browsing fetches metadata only. Playback uses bounded authenticated range reads
  through the certificate-verifying/no-redirect transport. Downloads stage privately,
  verify size/hash, then copy to the explicit Create Document destination. Interrupted
  destination writes are reported incomplete; no unrelated download is overwritten.
- Copy creates a fresh local planned identity without old clocks/links or camera
  start. Local deletion sends retained source/missing markers; acknowledged archive
  copies have no automatic expiry/cap. Low-space warning is strictly below10% free.

## Validation helper boundaries

See [wrapper operation and private selector contract](TTL014_VALIDATION.md).
The wrapper is not PA launch/review authority. It refuses dirty/stale/wrong-candidate,
missing/skipped/duplicate test evidence; it retains raw failures and owned-process
exit/timeout receipts. It authenticates the **exact durable Sinh** authorization
against the private selector payload hash before any device command beyond local
ADB version/readiness. It never starts/restarts ADB, resets data, uninstalls,
downgrades, installs tools, changes locks or activates a service. An existing enrolled
or non-disposable app context is not repaired to make fixtures pass.

The server wrapper runs native CLI provisioning/rejection against temporary private
fixtures, source-bound Go TLS/auth/upload/merge/storage/fault/range/recovery tests,
and pure module evaluation. It does not introduce a production loopback-listener
escape hatch. Disk-fault injection is **not** actual power loss/disk exhaustion.
The accounting summary explicitly cannot certify its own V9 completion, the
independent executor ledger, findings dispositions, CI, human UAT or deployment.

## Local implementation checks (not independent acceptance)

Current evidence root: private `/tmp/ttl014-d-1e8bbe.l1MNae/`; original first-milestone
Go/native evidence remains `/tmp/ttl014-phase.Dnh3kV/`. Android artifacts/reports are
ignored `app/build/`. Temporary evidence is not a canonical PA report.

- Go race suite: **50 top-level methods, five packages**, all pass; no skip/race.
  `go vet` and native build pass using the existing GCC wrapper explicitly.
- Native compiled-binary CLI fixtures: **5 commands**, expected successful and
  negative exits all verified; hash-only credentials and durable revocation checked.
- Accounting self-tests: **15 tests pass**; positive/negative fixtures cover authority/selector drift,
  private/aliased files, raw tampering, missing/stale/duplicate/skipped/failed XML/Go,
  process failure/timeout retention, absent ADB server and existing read-only CI.
- Android: full offline host tests, debug APK, test APK and lint compile/build.
  Host inventory **75** methods; zero host failures/errors/skips. **98 Android methods
  compile**; **zero Android methods executed here**. Lint: **0 errors, 72 unsuppressed
  warnings** (SetTextI18n51, UseKtx14, GradleDependency2, ViewConstructor2 and one each
  LockedOrientationActivity/DiscouragedApi/DataExtractionRules).
- Offline Nix native package succeeds with unchanged lock/cached Go1.26.8. Pure
  optional-module isolation fixture passes without service activation.
- Browser/phone rendering, two-phone instrumentation, independent matrix, hosted
  CI, physical UAT, live service and reboot are **not performed**.

### Original failures/constraints retained

- First compile: Android OsConstants does not expose O_DIRECTORY. Use checked
  directory paths with O_RDONLY and directory fsync; no durability assertion removed.
- A dynamic Go output redirection was refused by the safety hook before execution.
  Used a separately created, known private temp directory with literal output paths;
  the canonical independent matrix's paths remain unchanged and require readiness.
- Go race tests passed; a following unqualified vet failed because gcc was absent
  from PATH. Subsequent phase vet/build selected the already installed GCC wrapper
  explicitly. This is not proof that the independent trusted-PATH gate is ready.
- First module expression incorrectly requested `nixosModules.default`; corrected
  the wrapper/expression to the actual `nixosModules.timelapse-server` export and
  reran pure evaluation successfully. The original error is retained in activity.
- An added absent-ADB self-test initially used a literal backslash-n in its fake
  version stdout and correctly failed the version gate. Corrected the fixture bytes,
  not the production gate; all15 self-tests then passed. Original failure retained.
- First-milestone native phase-report capture was denied for versioned-contract/
  protected-persistence readiness. No report reference existed. This continuation
  does not retry that unchanged denial, fabricate a report, or clear the historical
  preparation/baseline context; owning PA service/operator reconciliation is separate.
- Lint warnings are unsuppressed. Existing UI/SDK/backup/dependency warnings and new
  literal-text/custom-view warnings remain visible. No dependency pin was upgraded
  or warning suppressed for a green build. Proposed Minor rationale is documentation,
  not an independent reviewer disposition or waiver of Critical/Major findings.

Keep this feature worktree/lease. Acceptance, merge, service/device effects and
checkout return retain their explicit separate gates.
