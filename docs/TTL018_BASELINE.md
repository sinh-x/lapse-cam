# Sessions/Timeboxes baseline harness

This is a debug-only **component** benchmark, not full-app UAT, camera-cadence
measurement, a reproduction of an export/cleanup hang, or performance acceptance.
It exists to preserve a before/after measurement path while TTL-018 replaces the
history surfaces. Keep run results and deployment closeout records outside Git.

## Isolation and admission

Use the toolchain and device/data-preservation rules in
[VALIDATION_WORKFLOW.md](VALIDATION_WORKFLOW.md) and
[OFFLINE_INSTRUMENTATION.md](OFFLINE_INSTRUMENTATION.md).
Before execution obtain explicit permission for the exact disposable device,
package, fixture/media effects and local build/cache-writer exclusion.

`-Pttl018Baseline=true` changes **debug only**:

- application ID: `com.sinh.timelapse.ttl018baseline`;
- test ID: `com.sinh.timelapse.ttl018baseline.test`;
- label: `TTL-018 Baseline (disposable)`.

The normal application ID, stored IDs, defaults, media and release configuration
are not migrated. Omit the property for ordinary builds. The property accepts
only `true`, not arbitrary application IDs or suffixes. Do not assume a release
build becomes isolated when the debug-only property is present.

`HistoryBenchmarkActivity` is a non-exported debug-only component host. It does
not instantiate the normal Activity, hydrate real history, start capture or
admit background processing. The tests require both the isolated package and
instrumentation argument `ttl018Baseline=true`; without the argument they are
explicitly skipped opt-in benchmarks, not routine regression passes.

## Build and selected execution

With the pinned offline JDK/SDK environment:

```sh
./gradlew --offline --no-daemon --console=plain \
  -Pandroid.builder.sdkDownload=false -Pttl018Baseline=true \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Inspect the resolved app/test identities and record APK/source hashes before
installing. Install only the selected isolated app/test APKs; never use automatic
connected-test uninstall. Use an existing compatible ADB server without replacing
it. Select one method and size per instrumentation process:

```sh
adb -s "$AUTHORIZED_SERIAL" shell am instrument -w -r \
  -e ttl018Baseline true -e historySize 100 \
  -e class 'com.sinh.timelapse.HistoryBaselineTest#sessionsHistory' \
  com.sinh.timelapse.ttl018baseline.test/androidx.test.runner.AndroidJUnitRunner
```

History methods: `sessionsHistory`, `timeboxesHistory`, `unstartedHistory`.
Sizes: 100, 1000, 10000. Metadata is generated in memory; temporary coordinator
storage is an exact newly allocated UUID cache root. Timebox fixtures have no
running records and disable reminders. They never load the application's saved
Timeboxes store. Ten percent of Sessions rows contain synthetic legacy error
copy; this does **not** test the future typed attention membership reducer.

The original eager Sessions renderer may exceed the app heap or take minutes at
10,000 records. Observe one process at a time with a bounded host timeout. A host
timeout does not stop device instrumentation. Reconcile the selected disposable
package afterward; never start another run over a still-running one. Preserve a
censored/failed run rather than calling it passed or silently rerunning it.
Stopping the disposable package must not target the normal app. No clear-data or
uninstall is needed. Failed fixture roots are retained for diagnosis.

## What the measurements mean

Each result is streamed as `TTL018_BASELINE` JSON and appended to
`files/ttl018-baseline.jsonl` in the isolated app. Preserve the raw stream/file,
source identity, both APK hashes, device model/API/build, method/size, failures
and timeout details outside the repository.

- `mountLayoutMillis`: one cold component construction/render/explicit-layout
  sample. It is not app launch or fully hydrated screen-open latency.
- `repeatRenderLayoutMillis`: one repeated render/layout of unchanged metadata,
  not a statistically established warm-open percentile.
- `mountedViews`: whole component hierarchy, not only visible session rows.
- heap/PSS: point-in-time whole-process measurements, not allocation attribution.
- frame gaps: Choreographer callback gaps in the component host, not Perfetto
  traces or Android FrameMetrics jank classifications.
- probe latency: main-handler feedback, **not human tap latency**. Probe queue is
  bounded to one outstanding callback. The longest blocked interval matters;
  low sampled p50/p95 can otherwise conceal a single long stall. Probe depth is
  **not production update backlog**.

Fixture revision v1 used an unbounded probe and lacked a reliable pre-work frame
anchor. Its probe backlog/percentiles and frame gaps must not be treated as
production-backlog/frame evidence. v2 bounds the probe and establishes an anchor.
Do not pool measurements from the two revisions into one percentile or assert
unexecuted candidate equivalence.

## Generated-media fixture

Select `HistoryBaselineTest#slowMediaAndMultiStepCleanup` explicitly. This
creates 20 generated full-resolution JPEGs in its UUID cache root, closes one
Adaptive session, and runs the real encoder, published-output verification,
receipt and serialized bounded cleanup. It injects 200ms delay **per verification**
on the media executor. That delay is a labelled simulation, not measured slow
storage. No camera photos, valuable pending sessions or existing outputs are
used. On success it deletes only its exact newly published output URI and UUID
fixture root. On failure it retains diagnostic state; do not repair/retry it to
make the result green.

The fixture reports actual verification calls, review/cleanup notifications,
owner pending depth and longest owner task. Notification count is not cleanup
step count. Longest owner task includes JPEG fixture setup; `fixtureSetupMillis`
identifies that confounder. The main-handler probe is observed without a running
capture or full-app history. This cannot establish real capture cadence or queue
backpressure under a production progress burst.

## Mandatory performance checkpoint

Before performance tuning, complete the approved baseline matrix: repeated
full-app usable-screen/input measurements, idle/processing/recording comparisons,
real capture cadence, owner occupancy/update backlog, memory and relevant traces;
include sparse/dense attention, no-Today and slow-storage/backlog cases. Preserve
the reported hang as unresolved unless actually reproduced. Return measured
numeric latency/memory/backlog/cadence proposals to Sinh for approval and freeze
them for before/after comparison. Component smoke results alone do not satisfy
that checkpoint or any complete AC-01–16 scenario. TTL-016 retains background
lifecycle, admission, notifications and camera scope.
