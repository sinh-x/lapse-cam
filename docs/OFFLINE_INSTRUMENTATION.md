# Offline Android instrumentation cache (TTL-003)

## Current state and acceptance boundary

2026-10-20, deployment `d-f14722`, feature worktree based on
`d753424a7643993a3514626726e58030c48ea093`:

- **Cache ready under an explicitly revised native-resolution procedure:** all
  16 selected configurations resolve offline, with 164 unique artifact files.
- Fresh host tests: **16 passed, 0 failures/errors/skips**. Both APKs build.
  Lint: **0 errors, 16 warnings**; existing proposed warning dispositions remain
  in [TTL-001 evidence](sessions/d-4582d3.md#lint-warning-ledger-16-occurrences).
  Those deferrals still need independent review; none is a suppression.
- Supplementary **CPH2699 / API36 / CPH2699_16.0.5.1200(EX01)** device run:
  **16 executed, 14 passed, 2 failures, 0 errors/skips**. All source methods
  matched fresh XML. New follow-up **TTL-010** owns both failures, not this
  cache task. Sinh requested separate follow-up rather than reopening TTL-001.
- The required Pixel 8 Pro/API37/build2026100601 was **not connected**. Its
  identity wrapper stopped before installation. Supplementary coverage does
  not replace that gate. Independent matrix, camera/Gallery UAT and acceptance
  remain pending. No merge/release/checkout return follows from cache readiness.

**Material requirements deviation:** Sinh selected bounded native Gradle
resolution after the strict URL/provenance contract proved unavailable through
public Gradle APIs (TTL-003 `c-20261020120826780`). This implementation checks
source hashes, exact configuration roots and the resolved graph; it **cannot
preauthorize individual URLs/classifiers/redirects or audit every network
request**. Declared repositories are not observed origins. Reports explicitly
set `networkProvenanceComplete=false` and `strictOriginalContractSatisfied=false`.
Raw received-byte hashes establish local integrity, not independent authenticity.
Do not use this revision to claim original FR-2/3 or AC-2/3 passed. Requirements,
UAT and the independent matrix need reconciliation and renewed approval.

## Pinned prerequisites

Use this project's existing inputs, not another project's toolchain:

```sh
export JAVA_HOME=/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2
export ANDROID_HOME=/home/sinh/android-sdk-writable
export PATH="$JAVA_HOME/bin:$PATH"
```

Retain Gradle **8.14**, AGP **8.11.1**, Kotlin **2.1.20**, JDK **17.0.20+2**,
SDK platform **36**, build-tools **35.0.0**, wrapper checksum, flake and lock.
The helper requires the existing `/home/sinh/.gradle`; it rejects other user
homes, refresh-dependencies, extra CLI/user init scripts, SDK-download enablement,
unknown `ttlCache*` properties, mixed tasks, excluded tasks and dry-run evidence.
Do not install SDKs, upgrade dependencies, edit native cache internals, introduce
repositories, purge caches or start/replace a shared ADB server.

Obtain current owner exclusion of other IDE/Gradle/provisioning cache writers
before each phase, in addition to native Gradle locking. Approval references in
JSON/properties are **data, not permission tokens**. A real durable owner decision
must bind the exact input bytes before any online invocation. No automatic retry
follows from permission for one attempt; reconcile partial effects first.

`andafit-movement` was inspected read-only for comparison. Its Nix full-JDK/SDK
pattern is useful, but its Gradle9/Expo/JitPack setup and auto-install shell hooks
are not this project's configuration. Its build-output caching is not dependency
provisioning. No reference-project builds or hooks were executed. Reusable
knowledge capture is tracked by **PAPC-061**.

## Helper interface and offline inventory

Only `:app:ttlInstrumentationCache` may be requested when using the init script.
It has no task dependencies; realizing the connected task definition is not
scheduling its actions. It never performs installation or device access.

```sh
# Convenience wrapper; all remaining arguments are Gradle flags/properties.
ttl_cache() {
  ./gradlew --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
    --init-script tools/instrumentation-cache.init.gradle \
    :app:ttlInstrumentationCache "$@"
}
ttl_cache --offline -PttlCacheMode=selftest \
  -PttlCacheReport=app/build/reports/ttl-cache/NEW-selftest.json
ttl_cache --offline -PttlCacheMode=inventory \
  -PttlCacheReport=app/build/reports/ttl-cache/NEW-inventory.json
```

Replace `NEW` with an unused run label. Every report is absent-only, has a UUID,
timestamps and a printed raw SHA-256. Preserve failed reports and their process
exit codes; never overwrite them to make a retry appear successful. Init-time
refusals occur before a task report exists; retain command stderr separately.

Inventory covers resolvable `debugAndroidTest*Classpath` and every AGP internal
UTP configuration. The pinned AGP UTP enum supplies a completeness check for 13
roots plus compile/runtime classpaths; annotation processing adds the 16th
configuration. The known root is
`com.android.tools.utp:android-test-plugin-host-additional-test-output:31.11.1`.
Empty/partial selection fails. Each configuration records roots, all selected
and unresolved edges, cached artifact paths/bytes/SHA-256 and failures. Project
outputs retain graph edges but are excluded from downloadable module artifacts.
Unknown metadata means the transitive closure is **unknown**, not just one missing
JAR. An incomplete inventory exits nonzero while retaining usable evidence.

Declared roots are frozen once per invocation: Kotlin/AGP resolution hooks may
inject effective dependencies. Complete effective edges are separately compared
before binary access. Resolution guards are registered once before resolution;
dynamic/changing dependencies are rejected. Selftests contain eight revised
fixture groups, including real secure XML parsing. The repository-drift fixture
is not a claim that native redirect interception exists.

## Approval inputs, retrieval and immutable receipts

Keep all inputs outside Git. A scope is reviewed data, not an automatically
approved file. To prepare a new `metadata-scope.json`, use the fresh inventory's
`sourceFiles`, configuration names and `roots` verbatim, plus:

- `schemaVersion`: `ttl-cache-native-scope/v1`;
- `configurationNames`: all selected names, in inventory order;
- `roots`: map of each name to its exact declared-root list;
- `declaredRepositories`: exactly `https://dl.google.com/dl/android/maven2/` and
  `https://repo.maven.apache.org/maven2/`;
- `nativeLimitationsAccepted`: true **only after the owner accepts that revision**;
- `approvalRefs`: earlier real decision references; `writerExclusionRef`: current
  real coordination reference. Include repository/base/branch context for review.

`sourceFiles` pins build/wrapper/flake/app/test/helper bytes. Online input checks
run before project evaluation and again before task resolution. Documentation
status changes are not compiled-input changes. Source changes require a new
input/hash and renewed permission, never edits to already approved inputs.

Review and hash the entire scope, obtain its specific owner approval, then run:

```sh
# Set these to real absolute files, approved raw hashes and actual decision refs.
# These examples are NOT download authorization.
ttl_cache -PttlCacheMode=metadata -PttlCacheManifest="$SCOPE" \
  -PttlCacheManifestSha256="$SCOPE_SHA256" -PttlCacheApprovalRef="$METADATA_APPROVAL" \
  -PttlCacheReport=app/build/reports/ttl-cache/NEW-metadata.json
```

Metadata uses native `resolutionResult`, without binary artifact views in its
action. Unchanged plugin/project setup still occurs: plugin-management includes
Google/Central/Plugin Portal, and native setup may contact declared plugin
sources. No refresh or unrelated task/configuration sweep is requested. Scope
covers transitive metadata under the listed roots, not an exact request ledger.
Before/after snapshots concern content files in `modules-2/files-2.1`; they are
not a complete trace of Gradle's binary metadata stores or network traffic.

Extract `candidateRetrievalManifest` **without editing it** to a new external
file, using exclusive creation, e.g. Python `open(path, 'x')` and `json.dump`.
Record that file's actual raw SHA-256. Its schema is
`ttl-cache-native-retrieval/v1`; it contains the exact full graph, source pins,
root scope and metadata input hash. Obtain a separate graph-scoped artifact
approval naming those bytes and the native limitations before this command:

```sh
ttl_cache -PttlCacheMode=provision -PttlCacheManifest="$RETRIEVAL" \
  -PttlCacheManifestSha256="$RETRIEVAL_SHA256" -PttlCacheApprovalRef="$ARTIFACT_APPROVAL" \
  -PttlCacheReport=app/build/reports/ttl-cache/NEW-provision.json
```

Provision rechecks the whole graph, then each configuration graph before binary
artifact views. Scope/graph drift stops before that configuration's binaries;
metadata access itself still has native limitations. Save `candidateReceipt`
absent-only as `provisioning-manifest.json`; never add results to an approved
input. Receipt schema `ttl-cache-native-receipt/v1` includes source/graph/artifact
snapshots, raw metadata/retrieval hashes, approval/exclusion references and cache
file digests. Null request/final URL/redirect fields deliberately mean unknown.
The receipt's cache-file inventory is conservative and includes existing shared
cache files, not only newly fetched files; changes/removal there can block verify.

```sh
ttl_cache --offline -PttlCacheMode=verify -PttlCacheManifest="$RECEIPT" \
  -PttlCacheReport=app/build/reports/ttl-cache/NEW-verification.json
```

Compare the receipt's independently retained raw hash before use. Verify checks
selected scope/complete graph, source pins and artifact/cache bytes offline. It
cannot authenticate upstream sources, validate an owner identity from a string,
or establish a passing app test. Preserve original failed logs and cache delta
before interruption recovery; do not delete cache entries/locks or blindly replay.
New graph/root/source/repository requires renewed approval. Hash mismatches stop.

## Validation and phone-data boundary

After cache verification and separate execution permission, use the exact host
commands in [Build](../README.md#build), adding `--rerun-tasks` and an absent-only
`app/build/reports/ttl-cache/host-start` marker before the host run. Preserve fresh
host XML, APKs/digests and lint XML. Current warning dispositions above remain
proposals, not independent acceptance.

**Do not run the originally approved V5 unchanged.** Pinned AGP8.11.1 defaults
`android.injected.androidTest.leaveApksInstalledAfterRun` to false; its connected
runner maps that to uninstall-after-test for app and test APKs. This conflicts
with the no-uninstall/data-preservation requirement. Sinh approved a revised
*phase* invocation (`c-20261020133400521`) retaining the exact wrapper/runner/class
filter, with both extra properties:

```text
-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false
```

The independent matrix must be reconciled/reapproved separately; this runbook
cannot silently replace its bytes. Before any device run require fresh owner
permission for a disposable app context, exactly one authorized device row,
exact model/API/build assertions, explicit selected serial (never printed), an
absent connected-results directory and a new `device-start` marker. Use the
locked offline Nix ADB shell and verify existing shared-server compatibility
without restarting it. Stop on zero/extra/unauthorized/mismatched devices.

The mandatory identity remains Pixel8Pro/API37/build2026100601. An explicitly
approved CPH2699/API36/buildCPH2699_16.0.5.1200(EX01) supplementary run occurred
instead; never label it Pixel coverage. Test fixtures create/delete UUID app-cache
roots and exact newly allocated MediaStore rows, including published test videos.
Fixture models read `exports/lastVideo`; some encoder constructors create normal
export-attempt directories. No real camera capture, broad media deletion,
app-data clearing or uninstall is authorized. Preserve existing unrelated phone
photos/videos/documents/other-app data. Code-bounded effects are not an absolute
phone-wide no-loss proof. Keep serial-bearing originals restricted and redact
logs/XML paths before sharing.

```sh
# After actual tests only; fails for stale/missing/failed/skipped/duplicate XML.
ttl_cache --offline -PttlCacheMode=results -PttlCacheManifest="$RECEIPT" \
  -PttlCacheReport=app/build/reports/ttl-cache/NEW-results.json
```

Results compares exact 16-method source sets, XML outcome counters and freshness,
APKs and lint. Secure JDK parsing disables DTD/external entities. It deliberately
fails on the two current supplementary failures. Do not suppress/fix them here:

| Failure | Observed location | Owner |
|---|---|---|
| MediaStore SecurityException in `ambiguousOwnershipAndCleanupFailureRetainRecordsForExplicitRetry` | fixture final cleanup, MediaPipelineTest.kt:600 | TTL-010 |
| Unexpected encoder frame count in `interruptedExportsCleanOnlyTheirPendingOutputsAndPreservePublishedVideos` | VideoExporter.kt:237, test:550 | TTL-010 |

## Retained execution evidence and handoff

External evidence home:
`agent-teams/builder/artifacts/TTL-003-cache/d-f14722/` under the PA workspace.

| Immutable file | Raw SHA-256 |
|---|---|
| metadata-scope.json | `636ad921ead886f35f4ce05b8f01d6b7f92f56956ea08c8dae6ea94426d8add3` |
| retrieval-manifest.json (first guarded attempt) | `1eec86dd7c3cf3336ca697968272132f3cd19c28b3ec31b3b8ddefd3261a5b6a` |
| retrieval-manifest-02.json (approved correction) | `c4c00c025b641dd9577702d49b0bb188748baa6249904a0342ad858df1bd5995` |
| provisioning-manifest.json | `505dc20133b1da5c4a9e316ef9dfbf70a915f5919627e164968c41be04db8280` |
| phase-summary.json (redacted supplementary counts/APK digests) | `d063ee709ea31576933c174113c50ae4ba6b63eef23a851c1efa22975e014fde` |

Approvals: metadata `c-20261020121735073`, original artifact graph
`c-20261020122243386`, corrected artifact retry `c-20261020122759338`, revised
preserving phase command `c-20261020133400521`, supplementary identity
`c-20261020134027652`. Metadata added 61 POM/module files; provisioning added 61
binary files (27,369,934 bytes). All 169 external selected coordinates and 164
artifact identities/digests are enumerated in the manifests/receipts, not guessed
from one JAR. Original 13 missing UTP roots and all failed attempts remain in
retained reports; first artifact guard stop added zero cache files. Later offline
inventory caught root/strategy lifecycle bugs before the approved corrected retry.

Current defect owner: **TTL-010**, a separate follow-up requested by Sinh to
preserve the TTL-001 implementation wrap-up. The earlier TTL-001 referral
`c-20261020134353240` remains historical evidence, not current execution ownership.
TTL-003 supplementary outcome:
`c-20261020134353301`. Cache-ready is the completed milestone; original strict
acceptance, independent review/CI, mandatory Pixel validation and human UAT are
**not passed**. Preserve checkout/results; do not replay the device run or remove
prior results without fresh reconciliation and authority.
