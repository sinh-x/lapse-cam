## TTL-005 owner-directed local integration and closeout (2026-10-21)

Sinh selected **"Commit, merge into develop, and close TTL-005"**, then explicitly instructed **"merge to develop and wrap ticke done, session log + registry"**. Authorization is durably recorded in TTL-005 `c-20261021200436743`. This supersedes the earlier no-commit/no-merge/no-closure handoff restrictions for this local wrap-up, and is an explicit exception to the repository's default main-targeted merge policy. It does not authorize a push, main update, branch/checkout deletion or checkout return.

The implementation and passing sidecar fix below are the integration scope: host50/50, OPPO53/53, validator19/19, TTL-010 helper21/21, both APKs and lint0 errors/26 unsuppressed warnings. Final actual feature/merge SHA, ticket closure and registry receipts are recorded outside this commit to avoid self-referential hashes:
- Session log: `sessions/2026/10/agent-team/2026-10-21-d-d6243e-rogue-one--TTL-005--session-rollover.md`.
- Closeout report: `agent-teams/builder/artifacts/TTL-005/2026-10-21-d-d6243e-closeout.md`.

**Owner-directed administrative closure is not full technical acceptance.** Independent matrix/review, CI, mandatory Pixel and formal human UAT remain pending. Original failed OPPO48/49 evidence and subsequent passing53/53 evidence are retained; no failed assertion was removed or tolerance weakened. The chronological uncommitted/not-merged statements below describe those earlier stages rather than the later owner direction. Feature branch/worktree and safety stash are retained; no further device operation follows from wrap-up.

---

## TTL-005 sidecar fix and passing OPPO rerun (2026-10-21)

*Historical verification stage before the later owner-directed commit/integration/closure above.*

**The observed failure is fixed; host50/50 and OPPO53/53 pass.** Source remains uncommitted on `feature/TTL-005-session-rollover`, HEAD/base `cf4e76deeca9dffc2d2f0df8cab63c7e36f9d4c3`. Sinh requested the fix, then separately approved one full OPPO rerun with the same disposable-context/no-uninstall/fixture-only boundary and stopped other IDE/Gradle/cache writers. This is supplementary implementation verification, not independent matrix/CI/Pixel/human UAT or ticket/release acceptance.

### Fix and regression contract

- Root cause: split-session close replayed every intent-looking file before distinguishing a genuinely reserved frame from an unknown sidecar. The deliberately malformed unreserved `intent_999999.json` therefore aborted closure instead of remaining outside the source inventory.
- Added pure `SessionIntentPolicy.isReservedSidecar`: only canonical decimal `intent_<id>.json` (or its AtomicFile `.bak`) with `0 <= id < progress.nextId` qualifies for replay. Overflowing IDs, leading-zero aliases, unreserved IDs and malformed names have no provenance authority.
- In explicitly opted-in split sessions, `recoverProgress` leaves those unreserved/lookalike sidecars untouched and does not parse or adopt them. Existing strict parsing, replay/checkpoint equality, selected IDs/count/bytes/stage checks and unknown-completed-JPEG rejection remain. Corrupt or missing **reserved** intent metadata still fails recovery. Off/Fixed/manual recovery retains its original strict behavior.
- `ownsSource` applies the same reservation/canonical-name gate before reading a sidecar, preventing lookalike aliases from entering a frozen deletion inventory. Unknown files and aliases still block the entire affected cleanup step before its first unlink; there is no exception to receipt/output verification or source ownership checks.
- Added a pre-reservation collision guard for the next intent basename, `.bak` and `.new`, including dangling symlinks. It runs **before** advancing/persisting `nextId`; a future capture cannot overwrite an earlier quarantined unknown file or accidentally promote it into owned provenance.
- Production changes are confined to `SessionStore.kt` and the pure policy in `SessionLifecycle.kt`. No close/recovery bypass, catch-and-ignore of reserved metadata, new deletion target, encoder change, assertion/tolerance weakening, dependency/pin/manifest/permission change or download.
- The original failing method is retained and strengthened with closed-state/operation recovery and source/sidecar byte-invariance assertions. Added one host method for canonical/high-water/overflow boundaries and four Android methods covering interrupted CLOSING/repeated recovery/successor saving, unknown-file cleanup rejection, reserved-corruption/missing provenance/unattributed JPEG refusal, manual-mode compatibility, and future-reservation collisions/dangling links. All prior49 Android identities remain and now execute alongside four additions.

### Fresh outcomes

| Check | Actual result |
|---|---|
| Offline host/build/lint with `--rerun-tasks` | **Exit0**; **50/50 host**, no failures/errors/skips; both APKs built; lint **0 errors / 26 warnings** |
| TTL-005 result-accounting self-tests | **19 passed**; no failures/errors/skips |
| Updated-develop TTL-010 offline self-tests | **21 passed**; fixture-only, production CLI still intentionally partial |
| Authorized full OPPO suite | **Exit0**, no timeout; **53/53 methods exactly once**, failures/errors/skips0; all five source classes included |
| Original failure | `lookalikeSidecarNeverBecomesDeletionAuthority` passed with its cleanup rejection and preservation assertions reached |
| Baseline preservation | Original42 host and32 Android methods remain and pass; strict repeated-export/encoder assertions unchanged |
| Source and installed APK binding | All42 compiled/test/tool/pin inputs unchanged during execution; both installed APK hashes match the local built files |
| Independent matrix / CI / mandatory Pixel / human UAT | Not run; remain separate pending gates |

The OPPO was freshly asserted as exactly one authorized **CPH2699 / Android16 / API36 / CPH2699_16.0.5.1200(EX01)** immediately before the run, with the serial retained only privately. Locked offline Nix ADB and SDK ADB37.0.0/protocol41 matched the existing server without restarting it. The native offline `:app:connectedDebugAndroidTest --rerun-tasks` invocation selected all source classes and retained **both** `android.injected.androidTest.leaveApksInstalledAfterRun=true` and `android.experimental.testOptions.uninstallIncompatibleApks=false`. No app-data clear/uninstall, real-camera capture, unrelated-media deletion, network/tool provisioning or automatic extra device attempt. Only authorized self-owned test fixtures were created/deleted.

Restricted new evidence root: **`/tmp/ttl005-sidecar-fix-zn7z1yxk/`** (mode0700). `before-source/`, `before.json` and `fix.patch` retain the exact fix delta; `tested-inputs.json` binds all compiled/tool inputs. `host.log`, `host-start`, `host-accounting.json`, `device.log`, `device-start`, `device-execution.json`, `outcomes.json` and `installed-apks.json` retain raw checks and exact method/byte accounting. `host-artifacts/` and `after-device/` retain APK/XML/lint/HTML outputs. Old connected failure outputs were moved intact to `prior-failed-connected/` before this run; their original independent copy remains in **`/tmp/ttl005-oppo-6h7vd78h/`**. Preserve both temporary evidence roots; generated HTML was not observed in a browser/Obsidian and these are not canonical PA settlement reports.

Latest APK SHA-256 (installed/local equality verified):
- Debug: `cd95d859b1ee56582c5f156061ea298d01f9ece7dbc99000aac5d57e042950af`.
- AndroidTest: `4a6069c5d75713a8f0ed87bb144b42bebb4a85cc618aa40cc9fab374919b71c0`.

Lint warning counts/rationales remain unchanged: orientation1 + discouraged API1 + backup policy1 + explicit-API/KTX style9 + English/localization14. No suppressions were added; independent disposition and actual accessible-control/hardware UAT remain pending. The older48/49 failure and earlier unrun states below are retained history, not the current result. No new frozen Feature SHA, commit, merge, push or checkout return was performed.

---

## TTL-005 rebase and full OPPO run (2026-10-21)

*Historical first OPPO run; its one failure is retained below and fixed/verified in the later section above.*

**Rebased; full test run completed with one retained failure.** Sinh requested rebasing onto updated develop and full tests on the connected OPPO, then explicitly confirmed the disposable app/test context, fixture/install effects, no clear/uninstall/unrelated-media changes and stopped other IDE/Gradle/cache writers.

### Rebase and preservation

- Branch remains `feature/TTL-005-session-rollover`; HEAD/base is now local develop **`cf4e76deeca9dffc2d2f0df8cab63c7e36f9d4c3`**, incorporating TTL-010 documentation and `tools/ttl010_validation.py`. Develop introduced no Kotlin/manifest/dependency/pin changes.
- The feature had no implementation commits, so its uncommitted overlay was preserved, the branch rebased/advanced, then that overlay restored. All application/test/tool/README bytes matched the pre-rebase inventory; `docs/FOLLOW_UP.md` merged automatically with both histories retained. No source assertion or tolerance was changed to obtain these results. Only current-result documentation was edited afterward.
- Retained stash: **`18702c5535e56a2203283103b2f9c9275df4926f`**. A transient index lock interrupted the stash's cleanup after saving; it disappeared without removal. Exact stash/backup/worktree hashes were verified before restoring only the six saved tracked paths for rebase. All nine untracked implementation files were restored as well. No force reset, lock-file deletion, source loss, merge/push or checkout return.
- Restricted evidence/backup root: **`/tmp/ttl005-oppo-6h7vd78h/`** (mode0700). `before.json`, `before.patch`, `source-before.tar.gz`, `after-rebase.json` and `tested-inputs.json` retain source state. Historical host/APK/lint/connected outputs were copied to `before-artifacts`; original connected directories were moved to `pre-run-connected` before this fresh run. Current raw outputs are copied under `after-artifacts`. Preserve this temporary evidence before cleanup; it is not canonical PA publication.
- This is HEAD plus a raw-hash-bound **uncommitted overlay**, not a frozen reviewed Feature SHA. All42 compiled/test/tool/pin inputs remained unchanged throughout host and device execution. The separate TTL-010 helper remains intentionally partial; only its offline `selftest` was invoked, not its blocked production CLI stages.

### Actual full validation outcomes

Existing full JDK17/SDK36/build-tools35.0.0 and locked offline Nix ADB were retained. SDK and locked ADB clients were37.0.0/protocol41, with the existing server verified protocol41 without restarting it. Immediately before installation, exactly one authorized device matched **CPH2699 / Android16 / API36 / CPH2699_16.0.5.1200(EX01)**; the explicitly selected serial was not printed. This is supplementary OPPO validation, not Pixel acceptance.

| Check | Result |
|---|---|
| `tools/verify_session_results.py --self-test` | **19 passed**, failures/errors/skips0 |
| Updated-develop `tools/ttl010_validation.py selftest` | **21 passed**, failures/errors/skips0; fixture-only, production runner still incomplete |
| Offline host + both APK builds + lint, `--rerun-tasks` | **Exit0**; host49/49 exactly once, failures/errors/skips0; both APKs built; lint0 errors/26 unsuppressed warnings |
| Full connected suite (all five source test classes) | **Exit1**, no timeout; **49 executed, 48 passed, 1 failed, 0 errors/skips**, exact complete source method set and no duplicates |
| Original baseline Android methods | **32/32 passed**; existing repeated-export media assertions retained, no encoder mismatch surfaced in this run |
| App/test APK preservation | Both remain installed; installed SHA-256 independently equals the local built APK bytes |
| App source/pins across rebase and run | Unchanged, except addition of develop's separate validator; no application/test/tool fix applied after the failed run |
| Independent matrix / CI / mandatory Pixel / human UAT | Not run; no overall acceptance or ticket closure |

Connected invocation used the pinned native `:app:connectedDebugAndroidTest` with `--offline --no-daemon --console=plain --rerun-tasks`, SDK downloads disabled, every source class selected (AdaptiveSession, MediaPipeline, SessionExportQueue, SessionRollover, TimestampAppearance), and **both** preservation flags:

```text
-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false
```

Fresh markers/logs: `host-start`, `host.log`, `device-start`, `device.log`, `device-execution.json` under the restricted evidence root; `outcomes.json` records complete method-set accounting, original failure stack and APK/lint outcomes. `installed-apks.json` records hash equality. Serial-bearing raw files remain restricted; displayed logs were redacted. No package uninstall/data clear, real-camera capture, unrelated-media sweep, network/download, shared-server restart or automatic second device invocation occurred. Tests did create/delete their own authorized cache/MediaStore fixtures.

APK SHA-256 (both installed and built):
- Debug: `83fc1564d268ddafa33553f35129c83f982dc1b88adf216c6ec93e41b660822c`.
- AndroidTest: `8cfe578ee10d6e5429eec5715f5aa27e13a124620ab21a8352d1d264ab0dc456`.

### Retained failure — no assertion weakening or automatic retry

`SessionExportQueueTest.lookalikeSidecarNeverBecomesDeletionAuthority` failed at its `repo.close(session, false)` call (test line189), **before the cleanup assertions**. `SessionRepository.finishClose` calls `session(record, true)` (line139), which invokes full `SessionStore.recoverProgress`. That recovery enumerates every numeric `intent_*.json` sidecar and attempts to parse the deliberately unattributed fixture file `intent_999999.json`. Its literal `unattributed` contents trigger `org.json.JSONException` at `readIntent`/`readJson`; `SessionStore.load` wraps it as a retained-session recovery error (line151).

This is an early closure/recovery rejection, not an observed unverified-photo deletion or recurrence of the encoder issue. However, it does **not** prove the intended flow where the unknown sidecar is excluded from inventory and later blocks only cleanup. Resolve that close/recovery versus unknown-sidecar contract without weakening provenance/deletion checks, then obtain a new bounded device rerun. The fixture teardown removes only its allocated cache root; no unrelated phone data was a deletion target. The original failing XML/logs and installed candidate are preserved. The all-green independent result validator must reject this failed suite; no V6 pass/report was generated.

Next work: fix/reconcile this one new safety-edge failure; rerun the complete suite with fresh output directories under new authorization; then independent review/CI, mandatory Pixel and all16 physical UAT scenarios. TTL-010's owner-directed administrative closeout now appears in develop below, but it does not prove the encoder's unknown cause was fixed.

---

## TTL-005 implementation-phase handoff (2026-10-21)

*Historical initial handoff, before the rebase and OPPO authorization/run above; its unrun/initial-base statements describe that earlier stage.*

**Implementation present, uncommitted; device validation and acceptance pending.** Work is confined to the selected `feature/TTL-005-session-rollover` checkout, based on `4b4a6ee17492035c9f41a91b02fd93d5ff3f89f0`. There is no new frozen Feature SHA yet. The earlier PA command-transport blocker was not repaired or bypassed by disabling controls; this session used the supplied rogue-one runtime's normal repository tools and retained audit/safety boundaries. No implementation acceptance, merge, push, checkout return or ticket closure is asserted.

Approved scope source: `agent-teams/requirements/artifacts/2026-10-21-ttl-005-session-rollover.md`, approval `c-20261021103657484`, paired 16-scenario UAT of the same date. Those external documents remain authoritative; this is a local implementation handoff, not a replacement matrix or signed UAT result.

### Delivered source surfaces

- `SessionLifecycle.kt`: explicit Off/2h/3h split values, strict boundary admission, drain/Finish gate, reserved three-attempt cycles with eligible-time 10s/30s delays, and allocation-free fair scanning over compact queue metadata.
- `SessionStore.kt`: additive opt-in fields on new v3 headers, frozen successor zone/settings, exact loading/exclusions, historical summaries without retaining historical selectors, source provenance checks and selector-cache release. Missing lifecycle fields mean manual, never the current UI default. Unknown schema/split values block recovery. Existing sampling math, renderer and encoder format are unchanged.
- `SessionRepository.kt`: atomic compact index, durable successor intent before allocation, atomic closure/successor/queue publication, immutable selected-source manifest and hashed source inventory. Repeated closure resumes the same identity. Missing/contradictory index or an unindexed nonempty/closed session is retained and blocked rather than assigned a new publication identity.
- `CaptureViewModel.kt`: independent 1Hz boundary check plus checks before shutter admission, at-most-one pre-boundary in-flight completion in its original folder, Pause/Finish distinction and paused recovery. Separate serial file/transition and media executors let successor capture proceed during export. Duplicate camera callbacks are ignored. Low-space cutoff remains 50MiB; no automatic capture resume.
- `VideoExporter.kt` / `SessionExportQueue.kt`: exact stable-operation lookup before re-encoding; persisted reservation before effects; fair app-open queue; published Gallery ownership/name/path/nonpending checks, reopened-byte track/sample/PTS/duration/cap validation and complete decode count. A durable receipt binds session/operation, selection/inventory hashes, fps/cap, exact URI and output SHA-256. A cleanup failure never re-encodes that proven output.
- Cleanup only touches exact inventoried JPEGs and known frame intents. Aliases, unknown/lookalike files, changed sources, contradictory receipts or missing/changed output stop the affected cleanup. A pending-delete checkpoint precedes each unlink; a later checkpoint records completion. Each owner step removes at most 16 inventory entries; resumed steps reverify the same output on the media worker. Completed records compact source metadata, retaining the header, index summary and verified receipt. Partial cleanup cannot restore already removed photos if the video is later removed/damaged.
- `MainActivity.kt`, `SessionReview.kt`, `SessionsView.kt`: remembered future-session split choice (default 3h), separate Pause/Finish controls, backlog and stable native review rows, exact Open, Retry and session-named source Delete/Cancel. Current/busy work cannot be deleted. A confirmed manual deletion cancels eligibility durably and leaves every video/other session alone. Off/Fixed/legacy are not automatically exported, converted or opted into cleanup. Historical exports without a per-session URI are not guessed from timestamps.
- HUD reports last shutter gap, save-completion latency and boundary-to-next-shutter latency. Queue log tag `SessionExportQueue` records operation-bound media-step time (encoding/publication/verification together, or cleanup verification). These are measurement hooks, **not measured Pixel performance claims**.
- `SessionLifecycleTest`, `SessionRolloverTest`, `SessionExportQueueTest`: additive tests; all 42 baseline host and 32 baseline Android methods preserved. New Android fixtures cover closure checkpoints, live fake-time model boundary/drain, stale callbacks, queue concurrency/fairness/retries/restart, receipt/inventory/alias safety, exact publication corruption, manual deletion and a real native view at 360dp/200% font scale. They are compiled, not executed here; exception injection is not real process-kill/reboot proof.
- `tools/verify_session_results.py`: standard-library-only report validator and self-tests. It checks baseline/source method inventories (including Kotlin backticks), exact fresh XML sets/counters, duplicate/missing/stale/failed/skipped reports, nested Android outputs, DTD/entity rejection, APK/lint freshness, unchanged clean candidate and matrix bindings, then exclusively creates absent `results.json`. It does not invoke Gradle/ADB, provision a runtime, operate a phone or certify human UAT.

### Actual local phase checks

Common environment was the existing full JDK17 at `/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2`, SDK `/home/sinh/android-sdk-writable`; every Gradle invocation used `--offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false` and `--rerun-tasks`. No dependency, SDK, manifest, permission, runner or lock changes/downloads were made.

| Check | Observed result / limit |
|---|---|
| P1 focused AdaptiveSampling/SessionContract/SessionLifecycle host tests | Exit0; original literal shell environment used, no launcher sanitation workaround |
| P2/P3 host + both APK builds | Exit0; Android source compilation only |
| P4 APK builds + lint | Exit0; actual UI walkthrough not performed |
| Final P5 host + both APK builds + lint | Exit0; **49 host methods exactly once**, failures/errors/skips0; both APKs assembled |
| Host source/XML accounting | Exact fresh source-method equality; original 42 baseline methods preserved |
| Android source inventory | **49 compiled methods** = original32 + SessionRollover6 + SessionExportQueue11; **0 executed in this deployment** |
| Python validator self-tests | **19 passed**, failures/errors/skips0; main independent V6 report not generated |
| Lint | **0 errors / 26 warnings**, unsuppressed; proposed dispositions below |
| Whitespace | `git diff --check` passes |
| Independent V1–V7 / CI / Pixel install / physical UAT | **Not run**; all16 UAT scenarios remain pending |

Local outputs are ignored build products under `app/build/test-results/testDebugUnitTest/`, `app/build/reports/`, and `app/build/outputs/apk/`. Latest native phase log/marker: `/tmp/ttl005-handoff.log` and `/tmp/ttl005-last-start`; preceding successful final rerun: `/tmp/ttl005-last.log`; earlier successful phase logs: `/tmp/ttl005-p1.log`, `ttl005-p2.log`, `ttl005-p3a.log`, `ttl005-p3.log`, `ttl005-p4.log`, `ttl005-p5.log`, `ttl005-final.log`. These temporary files are not durable PA publication; preserve them through the owning workflow before cleanup. No project build/test failure was suppressed; all executed phase invocations exited0. Generated HTML was not opened/observed in a browser or Obsidian.

Latest built APK SHA-256 (local uncommitted source, not an installed/frozen reviewed candidate):
- Debug: `83fc1564d268ddafa33553f35129c83f982dc1b88adf216c6ec93e41b660822c`.
- AndroidTest: `8cfe578ee10d6e5429eec5715f5aa27e13a124620ab21a8352d1d264ab0dc456`.

Lint dispositions proposed for independent review:

| ID | Count | Rationale / remaining debt |
|---|---:|---|
| LockedOrientationActivity / DiscouragedApi | 1 / 1 | Unchanged portrait output/activity contract; large-screen/rotation UAT still required |
| DataExtractionRules | 1 | Existing `allowBackup=false`; explicit device-transfer policy is pre-existing follow-up, not changed here |
| UseKtx | 9 | Style suggestions; explicit Android APIs retained without new dependencies or suppressions |
| SetTextI18n | 14 | Existing English prototype/localization debt plus labeled new controls; strict ASCII cap entry is intentional. Real large-font/TalkBack operability is not proven by compilation |

Gradle's deprecated-feature/Gradle9 compatibility notice remains; no toolchain migration was attempted. These are implementer rationales, not independent warning acceptance or a Critical/Major review result.

### Remaining gates and limits

1. Freeze/commit a candidate only through the operator's chosen workflow, then obtain the requirements-owned independent review/runtime/toolchain/device gates and run the seven-command matrix in its distinct review checkout. Do not relabel these local phase checks as V1–V7 or run the validator against stale historical Android results.
2. Freshly authorize an isolated Pixel8Pro/GrapheneOS API37/build2026100601 context and the exact no-uninstall wrapper before installation/instrumentation. **No ADB/device discovery, install, phone fixture, source deletion or Gallery mutation occurred here.** No OPPO substitution, background service or background-completion promise.
3. Execute all49 Android methods and all16 physical UAT scenarios, including both lenses, actual Gallery playback, publication/cleanup/process interruptions, concurrent latency/cadence, large retained disk backlog, low-space injection and 360dp/200% font-scale/TalkBack controls. The 100,000-entry pure fairness test does not prove physical backlog I/O latency. Real main-loop stalls can delay the 1Hz runnable; no hard hardware switching-gap guarantee is claimed.
4. Inspect original TTL-010 encoder failures below. The strict encoder assertions/tolerances and all original Android test methods are unchanged; this implementation does not diagnose or close TTL-010. Any publication/verification failure retains remaining sources and exhausts only its bounded cycle.
5. Keep independent review/CI, formal human acceptance, merge and checkout lifecycle separate. Unrun UAT is not passed, and the current dirty worktree is not an immutable reviewed release candidate.

---

# TTL-004: implementation candidate → independent review / UAT

## TTL-010 owner-directed closeout (2026-10-21; d-43fa80)

Sinh explicitly directed: **“consider ticket done proceed to merge to develop
and wrap up ticket”**, recorded in `c-20261021173725580`. This supersedes the
prior handoff's no-commit/no-merge instruction. Scope being integrated: the
retained offline validation libraries/selftests and diagnosis documentation;
**no new encoder or Kotlin test behavior change**. Final commit/merge and ticket
status receipts are recorded in the ticket rather than guessed here.

This is administrative closure by owner direction, **not a proven encoder fix or
completion of the original acceptance criteria**. The encoder cause remains
unknown after non-reproduction. The helper is deliberately partial: only
`selftest` is end-to-end; its production CLI stages fail closed. Native focused
checks used the separately authorized phase exception, not that unfinished CLI.
No missing check is marked passed by this closeout: the independent final full
suite/matrix/review, CI, mandatory Pixel and camera/Gallery human UAT remain
pending, and warning dispositions remain proposals. No release/push, extra device
run, focused-budget reset, branch deletion or checkout return is authorized.
Earlier “uncommitted/not closed/no merge” statements below describe their recorded
historical stage; they do not negate this later owner decision or turn it into
technical acceptance.

## TTL-010 authorized focused OPPO check (2026-10-21; d-43fa80)

**Checked, not fixed/closed: the encoder mismatch did not reproduce.** Sinh's
fresh foreground decision `c-20261021171617264` confirmed a disposable OPPO
app/test context and stopped other cache writers. It explicitly permitted direct
native offline phase checks while the unfinished helper remains blocked. This
exception is **not** the independent final matrix or completion of Phase1.

Frozen execution source: HEAD `4b4a6ee17492035c9f41a91b02fd93d5ff3f89f0`,
branch `feature/TTL-010-encoder-diagnosis`, with the pre-run docs/helper overlay
bound by an all-path raw-hash inventory. Helper execution SHA-256:
`6e6899fb80de2bad6e89620f0f4b5db0cfbee9151b8ee5f3addc28da771f1ddc`.
All product, Kotlin tests, toolchain pins and cache-helper bytes matched HEAD;
source hashes were rechecked after commands. No new test/encoder fix was made.

| Fresh phase check | Actual result |
|---|---|
| Source-current offline cache inventory | Exit0;16 configurations,164 unique artifacts; full graph/artifact equality with retained TTL-003 receipt and independently checked raw cached bytes; source delta explicit, old receipt not relabelled |
| Native host/build/lint, `--rerun-tasks` | Exit0;42/42 current host methods,0failures/errors/skips/duplicates; both APKs built; lint0errors/22warnings |
| Sole focused native OPPO invocation | Exit0;exact4/4 approved methods,0failures/errors/skips/duplicates; fresh XML after marker |
| Installed app + test APKs | Both retained; post-run hashes match the fresh candidate outputs |
| Final full32-method suite / independent matrix | **Not run**; no final device slot claimed |

Focused results: `ambiguousOwnershipAndCleanupFailureRetainRecordsForExplicitRetry`,
`interruptedExportsCleanOnlyTheirPendingOutputsAndPreservePublishedVideos`,
`adaptiveMediaUsesExactlyFrozenSelectionAtEveryFpsWithoutModifyingPhotos`, and
`repeatedRapidExportsKeepExactFramesAcrossFpsCountsAndScenes` all passed on one
freshly checked **CPH2699/API36/buildCPH2699_16.0.5.1200(EX01)**. The unchanged
stress method's exports==48, encoded/decoded counts, PTS/duration/cap and per-scene
JPEG-hash assertions completed; no raw per-vector telemetry is invented from XML.
This confirms the existing cleanup regressions pass, not an intermittent encoder
root cause or repair. The single focused ticket-wide budget is now consumed;
crash/nonzero/unknown attempts must not reset it. No automatic device replay.

Lint has individual proposed dispositions bound to every current occurrence:
LockedOrientationActivity1, DiscouragedApi1, DataExtractionRules1, UseKtx7,
SetTextI18n12. No GradleDependency warning was emitted in this run; its absence is
not an upgrade, suppression or explanation of historical warning-count changes.
Independent warning acceptance remains pending. APK SHA-256:
- App: `e1478848586da03a31a81c9f5695ebeeda1e09606a646bed4f819c398b8034d6`.
- Test: `9645514ade1d927b40bd2e2de4e92aaef31cee54e4d62ed679db59e050cab89a`.

Old host/device outputs were preserved absent-only before fresh native runs;
restricted original streams, exits, XML/HTML, APKs, hash manifests, source delta,
warning ledger and driver are under
`deployments/d-43fa80/artifacts/ttl010-focused-phase/` in the PA workspace.
Two prelaunch driver stops are retained separately: tuple/list JSON binding
comparison (corrected before any Gradle launch), and a transient Gradle writer
observation before device admission (no device intent/process yet). Only one
native cache, host and focused invocation actually launched; original stops are
not reclassified as passing checks. Device serials remain private.

Native Gradle retained both keep-installed/no-incompatible-uninstall properties,
explicit private device selection, original pinned environment and offline-only
resolution. No app-data clear/uninstall, broad cleanup, real storage exhaustion,
shared ADB restart/replacement, pin changes, downloads, commit, merge or return.
**Unresolved/no-cause handback:** no speculative test/codec/sleep/count workaround.
Helper completion, clean final candidate and separately authorized independent
review/matrix remain pending; mandatory Pixel, CI, camera/Gallery human UAT and
release gates are also not passed. TTL-001 history remains unchanged.

## TTL-010 offline validators and OPPO connection (2026-10-21; d-43fa80)

**Partial, uncommitted; encoder cause remains unknown.** Sinh approved further
device-free source analysis/validators, then requested an OPPO device check.
Read-only queries through the **already running** local ADB server confirmed one
connected, authorized **CPH2699 / Android16 / API36 /
CPH2699_16.0.5.1200(EX01)**. Queries were limited to device listing/state and four
identity properties. No server start/replacement, APK installation, test execution,
app-data change or device-stage budget claim occurred. Serial stayed private.
This identity check is not focused/final test permission, cache-writer exclusion,
source-current offline readiness or disposable-app-context approval.

The retained helper's starting SHA-256 matched d-34584a. Added **library-only**
checks for bounded UTF-8 XML, owner-only no-follow retained artifacts with strict
marker freshness/raw hashes, lint format6 per-occurrence dispositions bound to
raw XML, and extraction of the existing codec/count/EOS/last8PTS mismatch message.
Supplied rationales are not authenticated warning acceptance; parsed diagnostics
are not a proven cause; absent diagnostics are not evidence of success. No new
telemetry format, alternate test runner or production stage was enabled.

Source review: `VideoExporter.encode` increments submitted count after
`CodecSurface.draw` successfully sets presentation time and swaps EGL buffers;
it signals input EOS after all submissions and checks sample count after draining.
Codec-config buffers are excluded from samples, including any config-flagged
payload; this is an inspection boundary, **not evidence that an OEM emitted such
a combined buffer or that this caused the failure**. The repeated-export test
asserts exports==48 after both scenes and checks JPEG hashes per scene; no raw
per-vector observations survive its temporary fixture teardown. Fresh XML plus
candidate-bound source review is still needed, and must not be invented from
these offline comparators. No encoder, cleanup or instrumentation changes made.

Device-free checks: **21/21 selftest groups passed**, zero failures/errors/skips;
tracked diff and separate untracked-helper whitespace checks passed. Production
`preflight/host/device/verify` remain blocked before probes, claims or launches.
No Gradle/build/lint/device tests, commit, independent matrix, Pixel or human UAT.
Prior reports below remain historical; Phase1 and all ACs remain unaccepted.

## TTL-010 source-only continuation (2026-10-21; d-34584a)

**Partial, uncommitted; encoder cause still unknown.** The older TTL-004 results
below are historical, not acceptance of this TTL-010 overlay. Starting HEAD/base
is `4b4a6ee17492035c9f41a91b02fd93d5ff3f89f0` on
`feature/TTL-010-encoder-diagnosis`. Sinh approved staged source work and direct
check output in this session (ticket comment `c-20261021114917124` records the
interactive answer); Gradle, cache effects and all ADB/phone access remain gated.
The prior 441-line draft and its denied check packet remain preserved in the
immutable d-0fe3f7 handoff; no historical artifact was overwritten.

`tools/ttl010_validation.py` now provides these **library-only**, offline-tested
building blocks:

- Owner-only, no-follow, bounded regular-file reads; restricted evidence storage,
  shared nonblocking directory locking, atomic absent-only result publication,
  and directly exclusive device intents. Empty/crashed/unsafe intents consume the
  slot across candidate changes; an orphan result is contradictory, not absence.
- Read-only candidate/branch and complete NUL-safe clean-inventory checks, all
  tracked source hashes compared to committed objects (including paths hidden by
  Git assume-unchanged flags), approved-base pin equality and repeated drift
  observations. Ignored inputs under `app/src` are also rejected. The exact
  approved `.gitattributes` is itself pinned: the existing `gradlew.bat` LF blob
  must match its expected CRLF checkout, without normalizing observed raw hashes.
  A final audit exposed and corrected the initial blob-vs-checkout comparison;
  regressions reject wrong wrapper endings or changed attributes.
- Conservative Kotlin source discovery, including backtick host method names,
  comments/strings and local helper classes. Unsupported/disabled/aliased/nested
  test declarations fail closed. Every approved-base method must remain present;
  new methods expand the set. Current source discovery finds **42 host / 32 device**
  methods across the six classes; this is inspection, not execution.
- **18 selftest groups pass**, zero failures/errors/skips. Cases include shared
  lock exclusion, immutable nonzero results, partial/orphan/unsafe records,
  source/pin/object drift, baseline-method replacement, added methods, unsafe
  paths, XML equality/freshness and inert production CLI paths.

Only `selftest` works end-to-end. `preflight`, `host`, `device` and `verify`
continue to return blocked JSON / exit2 **before observations, stage claims or
process launches**. Storage fixtures and supplied process receipts are not
permission or verified settlement. No production validation ledger was opened or
claimed. No build, device query, app installation or device invocation occurred;
TTL-010's focused/final device budgets were not consumed by this deployment.

**Execution integration blocker:** installed PPA0.1.141's documented
`protected-review-v1` contract authenticates protected launch; its CLI
`review preflight` is explicitly observational. No documented helper-consumable
stage-authorization/settlement handoff was established. Neither a comment ID,
caller JSON, environment string nor static-compatible preflight can replace it.
Before implementing effectful stages, establish a supported authenticated handoff
binding candidate/ticket/checkout/stage, selected cache/device permissions and
writer exclusion, process custody and original exit/stream receipts. Do not add
invented tokens or modify the approved matrix to work around this boundary.
Native archival/capture/timeouts, installed-APK preservation, lint dispositions,
raw media evidence and integrated final verification remain **unimplemented**.
The read-only Git library's captured-output size is checked after completion;
bounded streaming capture remains part of the unfinished process integration.

`git diff --check` passes for tracked changes; the untracked helper also has a
separate whitespace audit (ordinary Git diff does not cover it). Host/build/lint
checks remain blocked, not waived, so Phase1 is **not accepted** and no commit is
made. Kotlin cleanup/media/stress and all toolchain/cache-helper pins are
unchanged. No speculative encoder repair, relaxed assertions, download, merge or
checkout return. Mandatory Pixel, independent matrix/review/CI, camera/Gallery
human UAT and release gates remain pending.

## OPPO follow-up: cleanup fixes and repeated-export validation (2026-10-20)

Sinh authorized bounded encoder/fixture work on this TTL-004 branch, then explicitly corrected testing from Pixel to **OPPO/CPH2699**. Fresh identity checks before every device run: exactly one authorized CPH2699 / Android16 / API36 / buildCPH2699_16.0.5.1200(EX01). The Pixel was not tested or updated during this follow-up; it retains the earlier manual-UAT build installed on request. The reported foreground “all good” is informal manual feedback, not a completed16-scenario UAT record.

Changes and observations, preserving the original failures:

1. **2cc4868dc1da4727743ad5d09142fa6942bffb51**: fixed `ambiguousOwnershipAndCleanupFailureRetainRecordsForExplicitRetry` teardown. It intentionally deleted its row during the test, then deleted the now-absent URI again in `finally`. Android may reject that stale URI with `SecurityException`. Track successful removal, assert one affected row and absence, and do not delete it twice. Added bounded encoder diagnostic detail (codec, fps, submitted/encoded counts, config/EOS flags and last8 output timestamps); no encoder algorithm or tolerances changed.
2. Focused run of the three previously failing methods: **2 passed / 1 failed**, exit1. Cleanup fixture and adaptive media passed. Interrupted-export test now surfaced the same double-delete pattern in its teardown (`MediaPipelineTest.kt:557` at that revision); the earlier cleanup exception could mask preceding loop results. This is retained evidence, not a hidden retry.
3. **1d9153d03705ad9d0a3ea6fac391340f1bc08d90**: interrupted-export fixture now records exact allocated URI + UUID name. Teardown first checks the exact name; absent rows already removed by recovery are not deleted again. Present rows require matching URI and a delete constrained by exact name, app owner and expected path. No wildcard cleanup, broad permission or ownership-check weakening. All existing media assertions retained. Fresh full suite: **31/31 passed**, exit0.
4. **c98ddb3c018b396c53fc534251241d9cce851153**, latest tested candidate: added `repeatedRapidExportsKeepExactFramesAcrossFpsCountsAndScenes`. It executes **48 exports**: black/asymmetric scenes ×15/24/30fps ×1/2/4/full5-second-budget frames ×2 repetitions. Every output passes strict sample/PTS/duration/cap validation and actual decoder frame count; source JPEG hashes remain unchanged. Full suite expanded to **32/32 passed**, exit0, exact source-method set matching fresh XML with no missing/duplicate/failed/error/skipped cases.

**Encoder root cause remains unproven.** No frame-count or timing assertion was removed, no frame duplication/drop workaround, real-time sleep, codec substitution or format/pin change was introduced. The original encoder mismatch did not recur in the focused run, the31-method run or the48-export stress run. Passing these runs is current validation evidence, not proof that an intermittent encoder defect is resolved. Keep that part of TTL-010 open for an actual diagnostic reproduction if it recurs; new failures now include bounded codec/count/PTS evidence.

Final candidate-bound checks:

| Check | Actual outcome |
|---|---|
| Offline host/build/lint with `--rerun-tasks` | Exit0;42 host cases,0 failures/errors/skips; both APKs assembled; lint0 errors/24 warnings |
| Full connected suite on exact CPH2699 | Exit0;32 methods: MediaPipeline17/17, AdaptiveSession11/11, TimestampAppearance4/4 |
| Repeated rapid-export vectors | All48 executed; strict container timing/count and decoded counts pass; all JPEG hashes unchanged |
| Installed OPPO app + test APK | Both retained; installed SHA-256 independently matches local tested outputs |
| Pixel / independent matrix / CI / formal human UAT | Not run in this follow-up; separate gates remain pending |

Latest APK SHA-256:
- Debug: `e1478848586da03a31a81c9f5695ebeeda1e09606a646bed4f819c398b8034d6`.
- AndroidTest: `9645514ade1d927b40bd2e2de4e92aaef31cee54e4d62ed679db59e050cab89a`.

All device invocations used the same pinned offline connected runner, all named classes or the explicit3-method focus filter, SDK downloads disabled and **both** keep-installed/no-incompatible-uninstall properties. No app-data clear/uninstall, real capture, unrelated-media deletion, permission/security change, downloads or shared ADB restart. Cache-writer exclusion was confirmed in the foreground approval. Present APKs reflect c98ddb3; any following results-doc commit changes only documentation.

Retained evidence (owner-only temp roots; preserve before any later authorized rerun):
- Original failed31-method outputs + host XML/log: `/tmp/ttl004-before-oppo-fix.PHmXbE/`.
- Focused2-pass/1-fail outputs: `/tmp/ttl004-oppo-diagnosis-results.XdNTcg/`; original log `/tmp/ttl004-oppo-diagnosis.ywiGHc.log`.
- Full31/31 outputs: `/tmp/ttl004-oppo-31pass.dnUFnp/`; original log `/tmp/ttl004-oppo-full.sR8Djv.log`.
- Latest32/32: worktree `app/build/outputs/androidTest-results/connected/debug/`, `app/build/reports/androidTests/connected/debug/index.html`; marker `app/build/reports/ttl004-oppo-stress-c98ddb3.txt`; original log `/tmp/ttl004-oppo-stress.LoA5f6.log`.

Original serial-bearing files stay restricted; native displayed logs were serial-redacted. Temporary evidence is not durable publication: retain externally through the supported owner workflow before cleanup. PA canonical phase-report/publication reconciliation remains the pre-existing protected-evidence blocker, not waived by these results. No independent-review, full-matrix, release, merge or checkout-return acceptance is asserted.

## Rebase and connected-phone phase (2026-10-20; earlier failing candidate)

At Sinh's request, rebased the ticket worktree onto updated local `develop` at **170dda221be2018a32b83f1020d2723992a1c168** (TTL-003 tooling). No remote is configured/fetched. Rebase mapped Phase 1 `78b903d` → `ae3f4fc` and integration `9c5d36f` → **8787776621ec6a074ba768b41619c41d80a8ac20**. Only README content conflicted; resolution retained both TTL-003's actual cache/supplementary outcomes and TTL-004's behavior/status. App/test/build/flake/lock bytes are identical to the pre-rebase integration; TTL-003 helper/runbook are retained from develop.

**Tested runtime/source candidate: 8787776621ec6a074ba768b41619c41d80a8ac20.** Any following results-documentation commit changes only documentation, not the tested APK/source bytes. Fresh offline phase host command above/below with `--rerun-tasks`: **exit0, 42 host tests, 0 failures/errors/skips, both APKs assembled, lint0 errors/24 warnings**.

Fresh read-only discovery found exactly one authorized **CPH2699 / Android16 / API36 / CPH2699_16.0.5.1200(EX01)**, not the mandatory Pixel. SDK and locked-Nix ADB clients/server all use protocol41/version37.0.0; no shared-server restart. Sinh selected **Run all 31 tests, then leave app installed**, confirming a disposable app context and stopped cache writers. This authorizes supplementary phase testing, not replacement of Pixel/matrix/UAT acceptance.

Actual device invocation retained pinned offline Gradle/UTP and all three named classes:

```text
:app:connectedDebugAndroidTest --rerun-tasks
-Pandroid.builder.sdkDownload=false
-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false
-Pandroid.testInstrumentationRunnerArguments.class=com.sinh.timelapse.MediaPipelineTest,com.sinh.timelapse.AdaptiveSessionTest,com.sinh.timelapse.TimestampAppearanceTest
```

The wrapper reasserted exact candidate, clean source inventory, single device/model/API/build and explicit selected serial (not printed) before Gradle. Connected-results/report directories were absent before the run; marker `app/build/reports/ttl004-device-start-8787776.txt` precedes fresh XML. A first command was rejected before execution because its redirected log target was dynamic; after creating a verified owner-only system-temp log, the actual Gradle/device invocation ran **once**. Original output retained at `/tmp/ttl004-device-8787776.GleOLs.log` (owner-only); serial-redacted output is in the deployment native transcript. Do not publish raw serial-bearing XML/log paths.

| Device suite | Executed | Passed | Failed | Errors/skips |
|---|---:|---:|---:|---:|
| MediaPipelineTest (unchanged original methods) | 16 | 14 | 2 | 0/0 |
| AdaptiveSessionTest | 11 | 10 | 1 | 0/0 |
| TimestampAppearanceTest | 4 | 4 | 0 | 0/0 |
| **Total** | **31** | **28** | **3** | **0/0** |

Gradle exited **1** due to failed tests, not dependency resolution. Exact fresh XML method-set equality against all31 source methods, no duplicates/missing cases, was verified. A failed method stops its internal loop: do not claim every fps/count vector executed merely because all methods were discovered.

Failures retained without suppression or automatic repair/retry:

- `MediaPipelineTest.ambiguousOwnershipAndCleanupFailureRetainRecordsForExplicitRetry`: MediaStore `SecurityException` in fixture `finally` cleanup at line600. Reproduces the existing TTL-010 finding; a cleanup exception may mask an earlier fixture result.
- `MediaPipelineTest.interruptedExportsCleanOnlyTheirPendingOutputsAndPreservePublishedVideos`: `Encoder produced an unexpected frame count` at `VideoExporter.kt:286`, called by test line550. Reproduces the existing TTL-010 symptom.
- `AdaptiveSessionTest.adaptiveMediaUsesExactlyFrozenSelectionAtEveryFpsWithoutModifyingPhotos`: same encoder frame-count check at `VideoExporter.kt:286`, test line320. Newly exercised TTL-004 coverage; likely related path, **not a proven common root cause or a proven rebase regression**. Bounded encoder diagnosis/repair and candidate-bound rerun remain required.

All four timestamp methods passed (216 safe layouts, legacy pixel equality, date/DST/frozen zone, shared preview geometry). Adaptive session recovery/retention/atomicity/counting/fake-time/storage tests passed; adaptive MP4 coverage remains failing. Application package presence was confirmed after the run; the updated app remains installed for hands-on testing. No app-data clear/uninstall, real capture, unrelated phone-media deletion, security change or shared ADB replacement was performed. Fixtures target isolated roots and exact self-created video URIs; no phone-wide preservation inventory or human playback/camera pass is inferred.

Tested APK SHA-256:
- Debug: `f18cf851634de9328518bab6294a67906b6b24ad91abd3a922b530225e58c26d`.
- AndroidTest: `c1b9c2b402ece588e1c605062d8cbc0859810b82d67f58df7fc46a24e53e7ef8`.

Local device report: `app/build/reports/androidTests/connected/debug/index.html`; original XML tree: `app/build/outputs/androidTest-results/connected/debug/`. Preserve failed outputs before any newly authorized rerun. Mandatory Pixel, actual both-lens capture/Gallery playback, full independent review/matrix/CI and human TS1–16 sign-off remain pending. TTL-003's original receipt pins pre-TTL-004 source/method sets and cannot be relabelled as this candidate's unchanged receipt or31-method result authority.

## Initial handoff (d-e2d1fe, 2026-10-20; pre-rebase results)

Implemented on the selected ticket worktree, **not merged, installed, released or accepted**:

- Branch `feature/TTL-004-adaptive-timestamp`; approved base `d753424a7643993a3514626726e58030c48ea093`; existing Phase 1 commit `78b903ddcee1c341005d3d7b8a49b3943c3830ed` preserved.
- Candidate is the commit containing this handoff; exact final SHA is recorded in the TTL-004 deployment/ticket handoff. Identify it with `git rev-parse HEAD`; review/UAT must bind that unchanged candidate, not the original Phase 1 SHA.
- V3 sessions freeze sampling, cap/fps, lens, zone and appearance. V1/v2 readers and exact legacy timestamp geometry remain. Legacy capture uses cached counts/high water; its historical active time is explicitly unavailable.
- Durable pre-shutter ID reservation and per-frame provenance precede atomic JPEG rename. One atomic progress checkpoint commits total count/bytes, selection/stage and known active time. Startup replays identifiable completed JPEGs once; contradictory/missing provenance blocks recovery without older-session fallback or completed-photo deletion. Incomplete camera/JPEG files are not selected. Per-frame intents are retained; storage is deliberately unbounded by the video cap.
- Steady saves use cached metadata and bounded selection, not historical directory scans. Startup reconciles history; Fixed export scans once, Adaptive export resolves selected provenance only.
- Monotonic active time advances before photos complete, excludes pause/background/export and is checkpointed periodically (5s), at frame commit and normal pause. Recovery is paused; a previously running checkpoint adds a durable unknown-interruption-tail warning, never downtime. Wall span is independently wall-clock based and clamped nonnegative.
- Export snapshots the committed selection, checks cap/uniqueness/order/files and headroom, then verifies actual encoded track/sample count, PTS and reported duration/cap **before publication**. Existing exact-owned pending-output recovery remains intact; published media is never a cleanup target.
- Scrollable controls/HUD, remembered new-session settings, frozen timestamp panel and shared output-space preview support the approved Adaptive/Fixed/style/date options. Camera/lifecycle safeguards remain; pause permits at most the already in-flight completion.

### Phase checks actually executed

Existing full JDK `/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2`, SDK `/home/sinh/android-sdk-writable`, unchanged pinned dependencies, offline only:

```sh
JAVA_HOME=/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2 \
ANDROID_HOME=/home/sinh/android-sdk-writable \
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --rerun-tasks
```

These are implementation-phase checks, **not execution of the independent six-command matrix**. Original command output/exits are in the deployment's native tool/activity transcript. The initial run and fresh reruns passed; no compiler/test/lint failure was hidden or repaired by changing tolerances, versions, permissions or runners.

| Evidence | Actual result |
|---|---|
| `AdaptiveSamplingTest` | 14 host cases passed |
| `CaptureTimingTest` | 22 host cases passed; original 16 host cases preserved |
| `SessionContractTest` | 6 host cases passed: active/pause/recovery arithmetic, headroom limits/overflow, 216 style value combinations |
| Fresh host XML total | **42 tests; 0 failures, 0 errors, 0 skipped** |
| Debug + instrumentation APK | Both assemble successfully; this is compilation, not device execution |
| Lint XML | **0 errors / 24 warnings**, unsuppressed; dispositions below are implementer proposals |
| Device fixture source | Original `MediaPipelineTest` 16 methods unchanged; `AdaptiveSessionTest` 11 and `TimestampAppearanceTest` 4 added; **31 methods compiled, all unrun** |
| Whitespace / configuration | `git diff --check` passes; no manifest, dependency, SDK, Nix/Gradle lock, runner or permission changes |
| Independent review / matrix / CI | **Not run** |
| Pixel installation / capture / app UAT | **Not performed** in d-e2d1fe; historical TTL-001 installation is not candidate evidence |

Local fresh outputs: `app/build/test-results/testDebugUnitTest/TEST-*.xml`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.xml` and `.html`, `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. They are ignored build artifacts, not committed test evidence or browser observations.

### Coverage added, runtime proof outstanding

| Contract | Fixtures / review surface | Remaining proof |
|---|---|---|
| Caps/fps/boundaries, seven-day selection/gap/replay | Existing Phase 1 host suites | Independent rerun and physical achieved cadence |
| Retention, exact thinning, no steady scans | AdaptiveSessionTest hashing/counting fixture | Execute fixture; observe actual device I/O/cadence |
| Crash before/after reserve, intent, atomic checkpoint, JPEG rename and commit | AdaptiveSessionTest interruption/replay fixtures | Execute on Android AtomicFile/filesystem; deliberate abrupt-loss injection is not real process-kill proof |
| Missing/unknown/contradictory metadata, frozen config and legacy behavior | AdaptiveSessionTest + unchanged MediaPipelineTest | Execute all device cases and lifecycle/reboot UAT |
| 10min active / 5min pause / 10min active, slow in-flight camera | Pure clock plus fake-time live-model fixture | Host clock passed; Android model/HUD fixture unrun; actual display refresh/missed slots need measurement |
| Space below/at threshold, overflow, export failure | Host arithmetic + isolated fake-space device fixtures | Host arithmetic passed; Android I/O/retry fixtures unrun; never fill real storage |
| One/two/full/thinned output at 15/24/30fps, PTS/count/duration | AdaptiveSessionTest + original media tests; pre-publication validator | Real codec/container results and playback unrun |
| All 216 safe layouts, legacy pixel equality, date/DST/zone, shared preview | TimestampAppearanceTest + original EXIF/lens fixtures | Execute raster/layout fixtures and actual preview-vs-photo/video comparison |
| 360dp / 200% font scale / accessibility | Scrollable vertical native controls, multiline spinner values, non-live timer HUD | **Manual operability and TalkBack assessment pending**; not proven by assembly/lint |

### Lint dispositions proposed for independent review

| Warning ID | Count | Rationale / remaining debt |
|---|---:|---|
| GradleDependency | 2 | Existing approved dependency pins retained; no upgrade/download authorized |
| LockedOrientationActivity + DiscouragedApi | 1 + 1 | Existing portrait 720×1280 capture contract; large-screen/rotation behavior still needs UAT |
| DataExtractionRules | 1 | Existing `allowBackup=false` retained; device-transfer/backup policy remains baseline review debt |
| UseKtx | 7 | Style suggestions only; explicit Android API calls retained without suppression or new dependencies |
| SetTextI18n | 12 | English prototype/localization debt; numeric cap entry intentionally uses strict ASCII whole seconds. Accessibility/large-font UAT still required |

Gradle also reports pre-existing deprecated features for Gradle 9 compatibility. No wrapper/plugin migration is part of TTL-004. These dispositions are **not independent warning acceptance**.

### Required next actions — no automatic device or merge permission

1. Reconcile supported PA reporting/review authority. One `pa_phase_report capture` attempt for this integration returned the protected-evidence stop (missing/insecure/unsupported/conflicting/uncertain evidence); no source/report reference was returned. No publish/settle, HTML delivery, browser/Obsidian observation or authority repair was attempted. This repository narrative is not canonical settlement. An attempted build-log redirection to the deployment artifact directory was also policy-blocked; subsequent builds ran directly with native tool-captured output, without bypassing that constraint.
2. Independent exact-candidate source review and the approved six-command matrix remain required. Requirements: `agent-teams/requirements/artifacts/2026-10-20-ttl-004-adaptive-timestamp.md`; matrix authority `7839091177015bf99df5dc0e5c3e3d20bc05dbbe1f51fc7c4194f684409bb358`; approval `c-20261019192823150`. This document does not replace the matrix, its gates or tolerances.
3. TTL-003 owns offline instrumentation provisioning. The newer develop integration now records cache readiness and supplementary failures owned by TTL-010 in the runbook/below; the original UTP cache failure remains historical. Initial TTL-004 results above predate that integration. Obtain fresh toolchain/isolated Pixel authorization before the prescribed wrapper/device command. Do not use an alternate runner, fetch dependencies, clear/uninstall valuable data or change security settings as a workaround.
4. Run all 31 device methods with zero failures/errors/skips, then execute the approved companion UAT (`2026-10-20-ttl-004-adaptive-timestamp-uat.md`). Record candidate, actual model/API/build, achieved rate/save latency, counts, pause/recovery behavior, sample/PTS/duration and geometry measurements. Do not put serials in shared evidence.
5. Obtain independent review/required CI and Sinh's human sign-off. TTL-001 acceptance debt remains separate; no merge/push/release, branch deletion or checkout return follows from this handoff.

| Human UAT scenario | Current result |
|---|---|
| TS-1 configuration/defaults/Fixed | Pending |
| TS-2 hierarchical selection/coverage | Pending |
| TS-3 originals/counts | Pending |
| TS-4 cadence/I/O | Pending |
| TS-5 dual duration/video estimate | Pending |
| TS-6 durable recovery/timing | Pending |
| TS-7 capped MP4/re-export | Pending |
| TS-8 storage/headroom/failures | Pending |
| TS-9 timestamp controls/216 layouts | Pending |
| TS-10 preview/output/lenses | Pending |
| TS-11 date/zone/clock | Pending |
| TS-12 frozen/legacy settings | Pending |
| TS-13 lifecycle/ownership regressions | Pending |
| TS-14 narrow-screen/accessibility | Pending |
| TS-15 physical integrated capture | Pending |
| TS-16 independent matrix/evidence | Pending |

---

# TTL-001: Android 16+ camera and media validation follow-up (historical debt retained)

- Ticket: **TTL-001**; implementation branch: `feature/TTL-001-android16-camera-validation`.
- Approved baseline: `b9aabdc2b125450576e39b673a3e0fdd87e0327f`.
- State: implementation present; device execution and acceptance **blocked/pending**, not complete.
- Current environment/supplementary execution: [TTL-003 offline runbook](OFFLINE_INSTRUMENTATION.md). Prior implementation evidence: [d-4582d3](sessions/d-4582d3.md). Preserve [original prototype history](sessions/d-1044a6.md).

## Scope correction

The approved requirements supersede the original Android-10 ticket wording: **minSdk / compileSdk / targetSdk 36**, Android 16+. No Android 10–15 compatibility claim is made. Mandatory physical app UAT is **Pixel 8 Pro / GrapheneOS Android 17 / API 37 / build 2026100601**. OPPO/API 36 app runtime coverage is deferred, not passed.

Requirements and paired UAT remain the approved external artifacts:

- `agent-teams/requirements/artifacts/2026-10-18-ttl-001-android16-camera-validation.md`
- `agent-teams/requirements/artifacts/2026-10-18-ttl-001-android16-camera-validation-uat.md`
- Approval: TTL-001 `c-20261018121729255`; matrix authority SHA-256 `d2003956cba9e1748b421cbd4ee9aa3c7f4e17612c7f6cc1eaf9415c5480c372`.

This document does not replace their commands, gates, acceptance criteria or tolerances.

## Implemented (runtime proof still pending)

- SDK 36 configuration; immutable pre-session lens selection, versioned metadata, legacy rear recovery, strict invalid-session blocking and explicit recovery retry.
- Lens-aware camera attachment and stale-provider-callback rejection; no unavailable-lens fallback. Valid-photo export does not depend on camera readiness.
- Front scene reflected after EXIF normalization, before the readable clock; no second preview/export mirror.
- Explicit zero-sized terminal muxer EOS timestamp for final-frame duration, without a duplicated sample.
- Serialized storage checks and capture cancellation; bounded fake-space/camera seams for fixtures.
- Atomic export attempt journal before encoding/insertion; exact UUID/name, package ownership, path, URI and pending-state checks before cleanup. Published output is retained. Ambiguous ownership, unknown attempt cache and cleanup failures retain/report state for explicit retry. Legacy unjournaled cache/pending media is not broadly swept.
- Original 11 host and 3 device tests retained. Host suite expanded to 16 tests; device suite expanded to 16 compiled test methods with loops over EXIF/fps/interruption boundaries.

## TTL-001 implementation phase-check results (historical)

| Check | Result |
|---|---|
| Offline `:app:testDebugUnitTest :app:assembleDebug` | Pass; XML: 16 tests, 0 failures/errors/skips |
| Offline `:app:assembleDebugAndroidTest` | Pass; instrumentation APK compiled, not executed |
| Offline `:app:lintDebug` | Pass; 0 errors, 16 warnings; dispositions in current evidence |
| Targeted MediaPipelineTest through approved Pixel identity wrapper | Blocked before test execution by missing cached UTP artifact |
| Changed-file whitespace | `git diff --check` passes |
| Independent full matrix / CI | Not run |
| Debug APK installation on authorized Pixel | Success via data-preserving `adb install -r`; package 0.1.0, min/target SDK 36 |
| Human/physical app UAT TS-1–TS-12 | Formal execution/sign-off pending; reported in-session lens lock explained as intended behavior |

## Original offline execution blocker (historical)

Fresh wrapper checks found exactly one authorized Pixel 8 Pro, API 37, build 2026100601. Gradle then failed offline resolving:

`com.android.tools.utp:android-test-plugin-host-additional-test-output:31.11.1`

No automated device test result is claimed. In a later user-requested operation, the debug APK was installed successfully after repeating exact Pixel identity and APK-hash checks; this does not resolve the missing test dependency or prove UAT. Sinh explicitly chose **Continue implementation offline**, rather than fetching missing dependencies. Do not retry through an alternate runner, change the dependency/toolchain/lock, or enable downloads under that decision. Arrange separately approved cache provisioning through the build owner, then rerun the prescribed named class with the exact identity wrapper after fresh toolchain/device authorization checks. Additional missing offline artifacts remain blockers, not permission to download.

## TTL-003 update — 2026-10-20, d-f14722

The [runbook](OFFLINE_INSTRUMENTATION.md) records the separately approved native-resolution revision, immutable manifests and retry history. The original strict URL/redirect/provenance requirements are not claimed satisfied; requirements/UAT/matrix reconciliation remains necessary. App/tests/toolchain/flake/lock inputs are unchanged.

- **Cache ready:** 16 configurations / 164 unique artifact files verify offline, zero missing/digest mismatches. Separate metadata and artifact retrieval approvals retained; no blanket future download permission.
- Fresh offline host tests: **16 passed, 0 failures/errors/skips**; both APKs build. Lint: **0 errors / 16 warnings**; existing deferrals remain proposals for independent review.
- Fresh mandatory Pixel wrapper stopped before installation: connected model was **CPH2699/API36**, not the approved Pixel. Sinh then separately approved supplementary execution on that exact model/API/build.
- Supplementary named MediaPipelineTest reached real UTP execution offline: **16 executed, 14 passed, 2 failures, 0 errors/skips**, exact source method set matched XML. `ambiguousOwnershipAndCleanupFailureRetainRecordsForExplicitRetry` failed with MediaStore SecurityException at fixture cleanup line600; `interruptedExportsCleanOnlyTheirPendingOutputsAndPreservePublishedVideos` failed with unexpected encoder frame count at VideoExporter.kt:237. New follow-up **TTL-010** owns both failures at Sinh's request, rather than reopening TTL-001. The earlier TTL-001 referral `c-20261020134353240` is superseded for execution ownership, not erased from history. No test suppression or app fix in TTL-003.
- Both keep-installed/no-incompatible-uninstall flags were used; post-run app package remained installed. No app-data clear/uninstall, broad media deletion or shared ADB-server replacement. Existing unrelated phone data was outside authorized effects, not inspected phone-wide.
- **Important command correction:** pinned AGP normally uninstalls tested APKs after the run. The original V5 lacks the preserving property. Do not replay it unchanged; see the revised phase flags in the runbook. Independent matrix approval must be renewed separately.
- Mandatory Pixel execution, independent matrix/review/CI, physical camera/Gallery UAT and final acceptance remain **pending**, not passed. Cache readiness does not accept TTL-001 or TTL-009. PAPC-061 captures reusable Android setup/development knowledge.

## Remaining acceptance work

- [ ] Execute all 16 device test methods, zero failures/errors/skips. Investigate actual codec/MediaStore/EXIF results; compilation is not runtime proof.
- [ ] Pixel front/rear preview and saved output comparison, ordinary permission denial/grant/retry and unavailable-lens capture blocking with valid-photo export.
- [ ] Pixel sessions per lens: **1s/15fps, 5s/24fps, 60s/30fps**, at least two completed photos each, with shutter-request clock comparison.
- [ ] One-photo export and Gallery/player playback at **15/24/30fps**, exactly one decodable sample and duration within **1 ms**; multi-photo samples/PTS within **1 µs**.
- [ ] Physical Stop/in-flight, two-interval background pause, Activity recreation/process restart, explicit Resume, deletion-dialog Cancel/Confirm and published-video preservation.
- [ ] Inspect isolated low-space/save/export failure, interrupted-publication and ambiguous-ownership fixture evidence. Never fill real phone storage or damage user sessions.
- [ ] Obtain independent source/full-matrix review, CI and human UAT acceptance against the exact candidate. Warning dispositions below are implementer proposals, not independent acceptance.

Sinh subsequently requested a commit and direct integration into `develop`; this is explicit integration permission, not passing test/UAT evidence or a change to the repository's default main-targeted policy. `main`, release, remote operations, branch deletion and checkout return are outside this wrap-up. Retain completed photos until playback is verified. Preserve historical failures and current offline blocker evidence.
