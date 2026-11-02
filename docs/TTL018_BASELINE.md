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

## Full-application foreground baseline

`AppBaselineTest` uses the same opt-in and isolated-package guards. Its
`seedMetadata` method runs in a separate instrumentation process **before**
launching any application owner. Grow the synthetic fixture monotonically:
100 → 1000 → 10000 via `historySize`. It constructs valid saved metadata, a
sparse 10% historical attention fixture, no synthetic Today sessions, and an even
mix of unstarted/older-started Timeboxes. It publishes one index rather than
manufacturing thousands of videos. Only the initial empty isolated store or its
exact recorded fixture identities/index hash are accepted. Previously captured
fixture JPEGs and recording spans are preserved. Never invoke the seeder while
an app Activity/owner is running. The canonical data path is required for the
existing atomic writer; Android's `/data/user/0` alias is not passed as authority.

A deliberately narrow `adoptInterrupted=true` path exists for separately
approved reconciliation of the original interrupted **100-directory**, empty
seed. It requires no index/marker/Timeboxes history, exactly 100 matching empty
Adaptive metadata directories, exact expected sidecar names, and zero progress
or photos. It is not ordinary recovery, automatic retry, general record repair,
or permission to adopt unknown files. Any interrupted larger seed must be
retained and explicitly reconciled, not replayed with this flag.

After seeding, explicitly select `foregroundCaptureAndBrowse` in a new process.
It requires a separately authorized CAMERA grant for the isolated package and
an unlocked selected Pixel pointed at non-sensitive subject matter. It launches
the actual `MainActivity`, waits at most 180s for usable model/camera state,
collects idle/healthy foreground Adaptive 500ms capture, browses the actual
Sessions/Timeboxes surfaces, pauses and retains the JPEGs. Capture uses split
Off: no automatic export or source cleanup of these camera samples is admitted.
The generated-media fixture above owns its own independent disposable sources.

The full-app observer decorates the model's executor and Handler **in the test
only, after hydration**. Handles captured by a producer before decoration do not
pass through that wrapper; do not claim complete processing-owner queue depth
from the capture-only observer. Queued-owner depth/task durations and immediate model
message depth/latency are attributed to that model, not Work/sync or the entire
Android message queue. Future cadence/tick timers are excluded from immediate
message depth. This instrumentation adds overhead and is not a production
telemetry API or a second processing authority. No durable state machine or
processing admission is replaced. Keep its fixture/source version with results.

Full-app revision v1 used Android UI-idle waits. They can fail to return under
continuous periodic rendering and cannot prove an input hang. Revision v2 introduced bounded first-draw callbacks and main feedback after
Back, but its original `ActivityScenario.onActivity` calls still waited for
idleness internally (confirmed in the pinned AndroidX core 1.6.1 bytecode).
Those old intervals include test-framework idle waiting, not just interaction
latency. Measurement revision **direct-main-v1** retains the actual resumed
Activity and dispatches timed actions with `Instrumentation.runOnMainSync`,
without an idle prerequisite. ActivityScenario is used only for launch/close.
Compare and label revisions separately; do not pool their latency percentiles.
Draw callbacks are not GPU/display-presentation measurements. Three opening samples/one Timeboxes sample are descriptive only,
not reliable population percentiles. By default the harness does not mount the known runaway original 10k Sessions
surface during capture; record that omission explicitly rather than claiming it
passed. After a separately authorized candidate update, `includeLargeBrowser=true`
can lift that skip. `openingSamples=30` collects the minimum opening sample count
used by the frozen budget plan; raw arrays and dispatch/click/draw timing splits
are retained with aggregate results. Use `healthySeconds=30` for the required
same-fixture healthy-capture comparison (the default 12s is a smoke sample only).
The run records healthy/browsing durations, committed-photo counts/rate ratio
and separately labelled model-reported missed slots. The latter is not a
hardware-sensor missed-frame counter. A supported blackout reveal action is
performed before opening a browser if needed, and capture/cover state is checked
through each browser/Back transition. If a retained recording is approaching its
Adaptive frame cap, `prepareFreshCaptureFixture` allocates a new empty manual
recording in a separate process in this same opt-in isolated package. It requires
known, stopped fixture captures and an unchanged synthetic index; it never resets
an old recording, its diagnostics, or attempt budgets. Allocation is journalled
before creation; an interrupted allocation blocks another preparation. Debug
`TTL018UI` logs split construction, modal admission and window attachment without
logging record content.
Do not retroactively treat earlier three-sample runs as 30-sample percentiles.

Camera metrics are actual committed JPEG counts/dimensions plus **software
shutter-request** gaps/save times. They are not hardware sensor timestamps.
Previously interrupted runs can leave a recovered unknown tail: retain that
state instead of resetting it for a cleaner result. If the usable-state wait fails, record that predicate failure and do not infer
whether hydration, recovery, permissions or camera binding was the exact blocker
without evidence. The current harness checks keyguard before admission, samples
model state on timeout and records foreground/lifecycle at capture admission.
It starts a fresh **isolated task** (not cleared app data), preventing stale saved
browser/task state from contaminating ordinary-entry measurement. First draw
and input readiness are distinct: wait for browser window focus before injecting
Back, and wait for that exact window to detach before the next opening. A global
UI-idle wait or a draw without settled focus must not be used to infer an app hang
or route Back into the underlying Camera Activity. Never bypass keyguard or
change security settings to make a measurement run. Optional explicit
`keepScreenAwake=true` holds only the isolated test Activity's window awake while
it remains started and keyguard is not showing. It does not unlock/wake/bypass a
locked device, alter OS timeout/security settings or admit background capture;
the callback is removed and the idle window flag restored in a finally block.
Final records label this test-only condition and the actual sampling stage/
nominal interval, which must be considered when comparing cadence.

Perfetto app-filtered track events and SurfaceFlinger frame timelines can be
recorded under separately authorized tracing scope. On devices without tracefs,
`atrace` may be unavailable; do not change security settings to enable it.
Perfetto config access may require `/data/misc/perfetto-configs` rather than
`/data/local/tmp`. Bound trace duration, preserve failed attempts, retrieve only
the exact newly allocated trace, and stop only the recording session you started.
Trace creation/file size is not trace analysis or a confirmed root cause.

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
