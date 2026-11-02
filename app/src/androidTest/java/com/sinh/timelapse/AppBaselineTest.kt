package com.sinh.timelapse

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.*
import android.view.KeyEvent
import android.widget.Button
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Explicit isolated foreground baseline. Run seed then capture in SEPARATE processes per size. */
class AppBaselineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val marker: File get() = File(context.filesDir.canonicalFile, "ttl018-app-fixture.json")
    private val size: Int get() = InstrumentationRegistry.getArguments().getString("historySize", "100").toInt().also {
        require(it in setOf(100, 1000, 10000))
    }
    private fun admitted() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("ttl018Baseline") == "true")
        check(context.packageName == "com.sinh.timelapse.ttl018baseline")
        require(InstrumentationRegistry.getArguments().getString("profileMethods") != "true") {
            "Method sampling disabled after stalled API-37 diagnostic; use non-suspending timing probes"
        }
    }
    private fun report(value: JSONObject) {
        val line = value.put("fixture", "ttl018-app-v2").put("epoch", System.currentTimeMillis()).toString()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "TTL018_APP_BASELINE $line\n") })
        File(context.filesDir, "ttl018-baseline.jsonl").appendText(line + "\n")
    }
    private fun strings(value: JSONArray) = (0 until value.length()).map { value.getString(it) }

    @Test fun seedMetadata() {
        admitted()
        // No live Activity, coordinator or model is constructed by this method.
        val files = context.filesDir.canonicalFile
        val store = SessionStore(files)
        val index = File(files, "session-index.json")
        val workStore = WorkSessionStore(files)
        val old = workStore.load()
        val prior = if (marker.exists()) JSONObject(marker.readText()) else null
        val oldIds = prior?.getJSONArray("sessions")?.let(::strings).orEmpty()
        val captures = prior?.getJSONArray("captures")?.let(::strings).orEmpty()
        val adopt = InstrumentationRegistry.getArguments().getString("adoptInterrupted") == "true"
        val adopted = if (adopt) store.directories().sortedBy { it.name } else emptyList()
        if (adopt) {
            check(prior == null && !index.exists() && size == 100 && adopted.size == 100 && old.records.isEmpty())
            adopted.forEach { directory ->
                check(directory.listFiles()!!.map { it.name }.toSet() == setOf("session.json", "progress.json"))
                val session = store.load(directory, false)
                check(session.configuration == SamplingConfiguration.Adaptive(30, 15) && session.split == SessionSplit.DEFAULT)
                check(session.lens == SessionLens.REAR && session.appearance == TimestampAppearance())
                val progress = SessionRepository.readJson(File(directory, "progress.json"))
                check(progress.getLong("count") == 0L && progress.getLong("bytes") == 0L &&
                    progress.getLong("nextId") == 0L && progress.getLong("lastId") == -1L &&
                    progress.getLong("active") == 0L && progress.getLong("stage") == 0L &&
                    !progress.getBoolean("running") && !progress.getBoolean("unknown") &&
                    progress.getJSONArray("selected").length() == 0)
            }
        }
        val actualIds = store.directories().map { it.name }.toSet()
        check(actualIds == (oldIds + captures + adopted.map { it.name }).toSet()) { "Unknown session data retained; no fixture overwrite" }
        check(old.draft == null && old.deletedIds.isEmpty() && old.spans.all { it.recordingId in captures }) {
            "Unknown Timeboxes data retained"
        }
        if (prior == null) {
            check(!index.exists() && old.records.isEmpty()) { "Fresh isolated context required" }
            check(context.getSharedPreferences("exports", 0).all.isEmpty())
        } else {
            check(SessionRepository.sha256(index) == prior.getString("indexSha")) { "Index changed; retain and reconcile" }
            check(old.records.map { it.id }.toSet() == strings(prior.getJSONArray("timeboxes")).toSet())
        }
        check(size > oldIds.size) { "Fixture grows once: 100 then 1000 then 10000; never overwrite history" }
        val now = System.currentTimeMillis()
        val records = if (index.exists()) SessionRepository.readJson(index).getJSONArray("records") else JSONArray()
        val nextIds = oldIds.toMutableList()
        val nextWork = old.records.toMutableList()
        report(JSONObject().put("event", "seed-start").put("syntheticSessions", size))
        for (i in oldIds.size until size) {
            val started = adopted.getOrNull(i)?.let { store.load(it, false).startedAt }
                ?: (now - (i % 30 + 1L) * 86_400_000) // No Today in synthetic history.
            val id = adopted.getOrNull(i)?.name ?: "$started-${UUID.randomUUID()}"
            if (adopted.isEmpty()) store.create(SamplingConfiguration.Adaptive(30, 15), SessionLens.REAR, TimestampAppearance(),
                SessionSplit.DEFAULT, id)
            nextIds.add(id)
            val failed = i % 10 == 0
            records.put(JSONObject().put("id", id).put("phase", if (failed) "NEEDS_ATTENTION" else "FINISHED")
                .put("operation", UUID.randomUUID().toString()).put("count", 0).put("selected", 0)
                .put("bytes", 0).put("active", 0).put("unknown", false).put("stage", 0)
                .put("export", JSONObject().put("attempts", if (failed) 3 else 0).put("remaining", 0).put("reserved", false))
                .put("cleanup", JSONObject().put("attempts", 0).put("remaining", 0).put("reserved", false))
                .put("cleanupOnly", false).put("error", if (failed) "Synthetic historical failure; no source media" else ""))
            val workId = UUID.randomUUID().toString()
            val unstarted = i % 2 == 0
            val start = if (unstarted) null else WorkMoment("fixture", 1000, started)
            nextWork.add(WorkSession(workId, started, "Baseline $i", WorkType.WORK.key, 25,
                state = if (unstarted) WorkState.PLANNED else WorkState.ENDED, start = start,
                deadline = start?.let { 1_501_000L }, endElapsed = start?.let { 61_000L },
                reason = start?.let { WorkEnd.USER }, confirmedElapsed = if (unstarted) 0 else 60_000))
        }
        // Exact, synthetic fixture construction only, before any application owner exists.
        SessionRepository.atomicJson(index, JSONObject().put("version", 1).put("records", records))
        workStore.commit(old.copy(records = nextWork))
        SessionRepository.atomicJson(marker, JSONObject().put("version", 1).put("sessions", JSONArray(nextIds))
            .put("timeboxes", JSONArray(nextWork.map { it.id })).put("captures", JSONArray(captures))
            .put("indexSha", SessionRepository.sha256(index)))
        report(JSONObject().put("event", "seed-complete").put("syntheticSessions", size)
            .put("timeboxes", nextWork.size).put("retainedCaptureSessions", captures.size))
    }

    @Test fun prepareFreshCaptureFixture() {
        admitted()
        // Separate process before launch; retain every prior capture, failure and synthetic record.
        val files = context.filesDir.canonicalFile
        val store = SessionStore(files)
        val fixture = JSONObject(marker.readText())
        check(fixture.getInt("version") == 1 && !fixture.has("pendingCaptureFixture"))
        val synthetic = strings(fixture.getJSONArray("sessions"))
        val captures = strings(fixture.getJSONArray("captures"))
        check(synthetic.size == size && store.directories().map { it.name }.toSet() == (synthetic + captures).toSet())
        check(SessionRepository.sha256(File(files, "session-index.json")) == fixture.getString("indexSha"))
        val snapshot = WorkSessionStore(files).load()
        check(snapshot.running == null && snapshot.draft == null && snapshot.deletedIds.isEmpty())
        check(snapshot.records.map { it.id }.toSet() == strings(fixture.getJSONArray("timeboxes")).toSet())
        check(snapshot.spans.all { it.recordingId in captures })
        val previousStarts = captures.map { id ->
            val directory = File(File(files, "sessions"), id)
            val previous = store.load(directory, false)
            check(previous.split == SessionSplit.OFF && previous.configuration == SamplingConfiguration.Adaptive(30, 15))
            check(!SessionRepository.readJson(File(directory, "progress.json")).getBoolean("running"))
            previous.startedAt
        }
        val now = System.currentTimeMillis()
        check(previousStarts.all { it < now }) { "Wall clock moved backwards; retain existing fixtures" }
        val id = "$now-${UUID.randomUUID()}"
        // Journal allocation first. Interrupted preparation blocks further preparation, never silent adoption.
        fixture.put("captures", JSONArray(captures + id)).put("pendingCaptureFixture", id)
        SessionRepository.atomicJson(marker, fixture)
        val session = store.create(SamplingConfiguration.Adaptive(30, 15), SessionLens.REAR,
            TimestampAppearance(), SessionSplit.OFF, id)
        check(store.snapshot(session).count == 0)
        fixture.remove("pendingCaptureFixture")
        SessionRepository.atomicJson(marker, fixture)
        report(JSONObject().put("event", "fresh-capture-prepared").put("sessionId", id)
            .put("retainedPriorCaptureSessions", captures.size).put("syntheticSessions", size))
    }

    private class OwnerProbe(private val delegate: ExecutorService) : AbstractExecutorService() {
        val queued = AtomicInteger(); val maxQueued = AtomicInteger()
        val taskMillis = java.util.Collections.synchronizedList(mutableListOf<Long>())
        override fun execute(command: Runnable) {
            val depth = queued.incrementAndGet(); maxQueued.updateAndGet { maxOf(it, depth) }
            try { delegate.execute {
                queued.decrementAndGet()
                val start = SystemClock.elapsedRealtime()
                try { command.run() } finally { taskMillis.add(SystemClock.elapsedRealtime() - start) }
            } } catch (error: Throwable) { queued.decrementAndGet(); throw error }
        }
        override fun shutdown() = delegate.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
        override fun isShutdown() = delegate.isShutdown
        override fun isTerminated() = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = delegate.awaitTermination(timeout, unit)
    }
    private class ModelMainProbe : Handler(Looper.getMainLooper()) {
        private val messages = java.util.IdentityHashMap<Message, Long>()
        val maxPending = AtomicInteger()
        val latencies = java.util.Collections.synchronizedList(mutableListOf<Long>())
        override fun sendMessageAtTime(message: Message, uptimeMillis: Long): Boolean {
            // Exclude future cadence/tick timers, count immediate model deliveries only.
            val immediate = uptimeMillis <= SystemClock.uptimeMillis() + 1
            if (immediate) synchronized(messages) {
                messages[message] = SystemClock.elapsedRealtime()
                maxPending.updateAndGet { maxOf(it, messages.size) }
            }
            val sent = super.sendMessageAtTime(message, uptimeMillis)
            if (!sent) synchronized(messages) { messages.remove(message) }
            return sent
        }
        override fun dispatchMessage(message: Message) {
            synchronized(messages) { messages.remove(message) }?.let { latencies.add(SystemClock.elapsedRealtime() - it) }
            super.dispatchMessage(message)
        }
    }
    private fun <T> replace(model: CaptureViewModel, name: String, replacement: (T) -> T): T {
        val field = CaptureViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val old = field.get(model) as T
        return replacement(old).also { field.set(model, it) }
    }
    private fun values(list: Collection<Long>) = synchronized(list) { list.toList() }
    private fun summary(list: Collection<Long>): JSONObject {
        val sorted = values(list).sorted()
        fun p(percent: Double) = sorted.getOrElse(kotlin.math.ceil((sorted.size - 1).coerceAtLeast(0) * percent).toInt()) { -1 }
        return JSONObject().put("samples", sorted.size).put("p50Millis", p(.5)).put("p95Millis", p(.95))
            .put("maxMillis", sorted.lastOrNull() ?: -1)
    }
    private fun waitFor(activity: MainActivity, predicate: (CaptureViewModel.State) -> Boolean) {
        var observed: CaptureViewModel.State? = null
        val end = SystemClock.elapsedRealtime() + 180_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrumentation.runOnMainSync {
                check(!activity.isDestroyed) { "Observed Activity was destroyed" }
                observed = ViewModelProvider(activity)[CaptureViewModel::class.java].state.value!!
                ready = predicate(checkNotNull(observed))
            }
            if (ready) return
            Thread.sleep(25)
        }
        observed?.let { report(JSONObject().put("event", "state-timeout").put("loading", it.loading)
            .put("recoveryBlocked", it.recoveryBlocked).put("message", it.message).put("cameraMessage", it.cameraMessage)
            .put("boundLens", it.boundLens?.name).put("capturing", it.capturing)) }
        fail("Baseline state timeout; retain all fixture data")
    }

    /** No capture or processing actions: isolate lifecycle events before/after test telemetry. */
    @Test fun startupLifecycleDiagnostics() {
        admitted()
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        check(!keyguard.isKeyguardLocked) { "Unlock selected Pixel before lifecycle diagnosis" }
        val events = mutableListOf<String>()
        val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            events.add("${SystemClock.elapsedRealtime()}:${activity.javaClass.name}:$stage:finishing=${activity.isFinishing}")
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                var foregroundObserved = true
                fun sample(name: String) {
                    scenario.onActivity { activity ->
                        val state = ViewModelProvider(activity)[CaptureViewModel::class.java].state.value!!
                        foregroundObserved = WorkUiFixtures.field<Boolean>(activity, "activityForeground")
                        report(JSONObject().put("event", "lifecycle-diagnostic").put("point", name)
                            .put("lifecycle", activity.lifecycle.currentState.name)
                            .put("foreground", WorkUiFixtures.field<Boolean>(activity, "activityForeground"))
                            .put("focused", activity.hasWindowFocus()).put("keyguard", keyguard.isKeyguardLocked)
                            .put("loading", state.loading).put("blocked", state.recoveryBlocked)
                            .put("boundLens", state.boundLens?.name).put("message", state.message)
                            .put("resumedActivities", JSONArray(monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).map { it.javaClass.name }))
                            .put("transitions", JSONArray(events.toList())))
                    }
                }
                sample("launched")
                Thread.sleep(3000); sample("idle-without-probes")
                lateinit var model: CaptureViewModel
                lateinit var originalWorker: ExecutorService
                lateinit var originalMain: Handler
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[CaptureViewModel::class.java]
                    val workerField = CaptureViewModel::class.java.getDeclaredField("worker").apply { isAccessible = true }
                    originalWorker = workerField.get(model) as ExecutorService
                    workerField.set(model, OwnerProbe(originalWorker))
                    val mainField = CaptureViewModel::class.java.getDeclaredField("main").apply { isAccessible = true }
                    originalMain = mainField.get(model) as Handler
                    mainField.set(model, ModelMainProbe())
                }
                Thread.sleep(3000); sample("idle-with-probes")
                val samples = InstrumentationRegistry.getArguments().getString("diagnosticSamples", "0").toInt().also { require(it in 0..12) }
                for (index in 0 until samples) {
                    Thread.sleep(3000); sample("extended-$index")
                    if (!foregroundObserved) break
                }
                scenario.onActivity {
                    CaptureViewModel::class.java.getDeclaredField("worker").apply { isAccessible = true }.set(model, originalWorker)
                    CaptureViewModel::class.java.getDeclaredField("main").apply { isAccessible = true }.set(model, originalMain)
                }
            }
        } finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
    }

    @Test fun foregroundCaptureAndBrowse() {
        admitted()
        check(context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            "Selected device is locked; no app readiness/capture measurement admitted"
        }
        val fixture = JSONObject(marker.readText())
        check(fixture.getJSONArray("sessions").length() == size)
        report(JSONObject().put("event", "app-start").put("syntheticSessions", size))
        val start = SystemClock.elapsedRealtime()
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(launch).use {
            lateinit var observedActivity: MainActivity
            instrumentation.runOnMainSync {
                observedActivity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
            }
            fun onActivity(action: (MainActivity) -> Unit) = instrumentation.runOnMainSync {
                check(!observedActivity.isDestroyed) { "Observed Activity was destroyed" }
                action(observedActivity)
            }
            val holdScreen = InstrumentationRegistry.getArguments().getString("keepScreenAwake") == "true"
            val holdHandler = Handler(Looper.getMainLooper())
            var holdActivity: MainActivity? = null
            onActivity { holdActivity = it }
            val hold = object : Runnable {
                override fun run() {
                    val activity = holdActivity
                    if (activity != null && !activity.isDestroyed && !activity.isFinishing &&
                        activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
                        !activity.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    holdHandler.postDelayed(this, 250)
                }
            }
            if (holdScreen) holdHandler.post(hold)
            try {
            waitFor(observedActivity) { !it.loading && !it.recoveryBlocked && it.boundLens == it.lens }
            val usable = SystemClock.elapsedRealtime() - start
            lateinit var model: CaptureViewModel
            lateinit var owner: OwnerProbe
            lateinit var main: ModelMainProbe
            val gaps = mutableListOf<Long>()
            val saves = mutableListOf<Long>()
            var initialFrames = 0
            var lastFrames = 0
            var captureId: String? = null
            val observer = Observer<CaptureViewModel.State> { state ->
                if (!state.savingPhoto && state.frames > lastFrames) {
                    lastFrames = state.frames
                    if (state.lastShutterGapMillis > 0) gaps.add(state.lastShutterGapMillis)
                    saves.add(state.lastSaveMillis)
                }
            }
            onActivity {
                model = ViewModelProvider(it)[CaptureViewModel::class.java]
                owner = replace<ExecutorService>(model, "worker") { old -> OwnerProbe(old) } as OwnerProbe
                main = replace<Handler>(model, "main") { ModelMainProbe() } as ModelMainProbe
                initialFrames = model.state.value!!.frames; lastFrames = initialFrames
                model.state.observeForever(observer)
            }
            Thread.sleep(3000) // App-idle comparison, no capture or browser.
            val idleOwner = owner.maxQueued.get()
            val idleMain = main.maxPending.get()
            onActivity { activity ->
                val foreground = WorkUiFixtures.field<Boolean>(activity, "activityForeground")
                report(JSONObject().put("event", "capture-admission").put("activityForeground", foreground)
                    .put("lifecycle", activity.lifecycle.currentState.name).put("windowFocused", activity.hasWindowFocus())
                    .put("boundLens", model.state.value!!.boundLens?.name))
                check(foreground && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    "Activity lost foreground before capture admission; do not infer camera latency"
                }
                model.start(SamplingConfiguration.Adaptive(30, 15), split = SessionSplit.OFF)
            }
            waitFor(observedActivity) { it.capturing && it.frames >= initialFrames + 3 }
            var healthyFramesStart = 0
            var healthyStartedAt = 0L
            var healthyGapStart = 0
            var healthySaveStart = 0
            var healthyMissedStart = 0L
            var healthyMissedEnd = 0L
            onActivity {
                captureId = model.state.value!!.session!!.directory.name
                healthyFramesStart = model.state.value!!.frames; healthyStartedAt = SystemClock.elapsedRealtime()
                healthyGapStart = gaps.size; healthySaveStart = saves.size
                healthyMissedStart = model.state.value!!.missedSlots
            }
            val healthySeconds = InstrumentationRegistry.getArguments().getString("healthySeconds", "12").toInt().also { require(it in 12..60) }
            Thread.sleep(healthySeconds * 1000L)
            var healthyFramesEnd = 0
            var healthyEndedAt = 0L
            var healthyGaps = emptyList<Long>()
            var healthySaves = emptyList<Long>()
            onActivity {
                healthyFramesEnd = model.state.value!!.frames; healthyEndedAt = SystemClock.elapsedRealtime()
                healthyMissedEnd = model.state.value!!.missedSlots
                healthyGaps = gaps.drop(healthyGapStart); healthySaves = saves.drop(healthySaveStart)
            }
            val healthyDuration = healthyEndedAt - healthyStartedAt
            val healthyPhotos = healthyFramesEnd - healthyFramesStart
            report(JSONObject().put("event", "healthy-capture").put("syntheticSessions", size)
                .put("durationMillis", healthyDuration).put("committedPhotos", healthyPhotos)
                .put("requestGaps", summary(healthyGaps)).put("saveLatency", summary(healthySaves)))
            val sessionOpens = mutableListOf<Long>()
            val sessionBacks = mutableListOf<Long>()
            val workOpens = mutableListOf<Long>()
            // Opt-in after-change runs may lift the original renderer's safety skip explicitly.
            val includeLarge = InstrumentationRegistry.getArguments().getString("includeLargeBrowser") == "true"
            val samples = InstrumentationRegistry.getArguments().getString("openingSamples", "3").toInt().also { require(it in 1..30) }
            var currentRoot: android.view.View? = null
            val openTimings = JSONArray()
            fun openToDraw(button: String, field: String): Long {
                onActivity { activity ->
                    val cover = WorkUiFixtures.field<android.view.View>(activity, "blackoutCover")
                    if (cover.visibility == android.view.View.VISIBLE) cover.performClick() // Supported reveal action, not a capture control.
                    check(model.state.value!!.capturing) { "Capture paused before browser navigation" }
                }
                val begin = SystemClock.elapsedRealtime()
                val drawn = CountDownLatch(1)
                val focused = CountDownLatch(1)
                val at = AtomicLong()
                var dispatchedAt = 0L
                var returnedAt = 0L
                onActivity { activity ->
                    dispatchedAt = SystemClock.elapsedRealtime()
                    WorkUiFixtures.field<Button>(activity, button).performClick()
                    returnedAt = SystemClock.elapsedRealtime()
                    check(WorkUiFixtures.field<android.view.View>(activity, "blackoutCover").visibility != android.view.View.VISIBLE)
                    check(model.state.value!!.capturing) { "Browser navigation paused capture" }
                    val root = WorkUiFixtures.field<android.view.View>(activity, field).rootView
                    currentRoot = root
                    val focusListener = object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                        override fun onWindowFocusChanged(hasFocus: Boolean) {
                            if (hasFocus) {
                                focused.countDown()
                                root.post { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnWindowFocusChangeListener(this) }
                            }
                        }
                    }
                    if (root.hasWindowFocus()) focused.countDown() else root.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
                    val listener = object : android.view.ViewTreeObserver.OnDrawListener {
                        override fun onDraw() {
                            if (at.compareAndSet(0, SystemClock.elapsedRealtime())) {
                                drawn.countDown()
                                root.post { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(this) }
                            }
                        }
                    }
                    root.viewTreeObserver.addOnDrawListener(listener)
                    root.invalidate()
                }
                check(drawn.await(60, TimeUnit.SECONDS)) { "First draw timeout; no UI-idle inference" }
                check(focused.await(60, TimeUnit.SECONDS)) { "Window focus timeout; no Back injection into underlying Camera" }
                openTimings.put(JSONObject().put("surface", button).put("dispatchWaitMillis", dispatchedAt - begin)
                    .put("clickExecutionMillis", returnedAt - dispatchedAt).put("postActionDrawMillis", at.get() - returnedAt))
                return at.get() - begin
            }
            fun backToFeedback(): Long {
                val begin = SystemClock.elapsedRealtime()
                val detached = CountDownLatch(1)
                onActivity {
                    val root = checkNotNull(currentRoot)
                    if (!root.isAttachedToWindow) detached.countDown()
                    else root.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: android.view.View) = Unit
                        override fun onViewDetachedFromWindow(view: android.view.View) {
                            view.removeOnAttachStateChangeListener(this); detached.countDown()
                        }
                    })
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                check(detached.await(60, TimeUnit.SECONDS)) { "Browser Back did not detach its window" }
                val feedback = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post { feedback.countDown() }
                check(feedback.await(60, TimeUnit.SECONDS))
                val elapsed = SystemClock.elapsedRealtime() - begin
                onActivity { activity ->
                    check(WorkUiFixtures.field<android.view.View>(activity, "blackoutCover").visibility != android.view.View.VISIBLE)
                    check(model.state.value!!.capturing) { "Returning to Camera paused capture" }
                }
                return elapsed
            }
            if (size <= 1000 || includeLarge) repeat(samples) {
                sessionOpens.add(openToDraw("sessionsButton", "sessionsView"))
                sessionBacks.add(backToFeedback())
                Thread.sleep(500)
            }
            if (size <= 1000 || includeLarge) repeat(samples) {
                workOpens.add(openToDraw("workButton", "workView"))
                backToFeedback()
            }
            Thread.sleep(3000)
            onActivity { model.stop("Disposable baseline paused; photos retained") }
            waitFor(observedActivity) { !it.savingPhoto && !it.capturing }
            val barrier = CountDownLatch(1); owner.execute { barrier.countDown() }; check(barrier.await(30, TimeUnit.SECONDS))
            var state: CaptureViewModel.State? = null
            onActivity { state = model.state.value; model.state.removeObserver(observer) }
            val outcome = checkNotNull(state)
            val browsingDuration = SystemClock.elapsedRealtime() - healthyEndedAt
            val healthyRate = if (healthyDuration > 0) healthyPhotos * 1000.0 / healthyDuration else 0.0
            val browsingPhotos = outcome.frames - healthyFramesEnd
            val browsingRate = if (browsingDuration > 0) browsingPhotos * 1000.0 / browsingDuration else 0.0
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val directory = outcome.session!!.directory
            val jpegCount = directory.listFiles()!!.count { it.name.endsWith(".jpg") && it.name.startsWith("frame_") }
            val sample = directory.listFiles()!!.first { it.name.startsWith("frame_") && it.name.endsWith(".jpg") }
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sample.path, dimensions)
            report(JSONObject().put("event", "app-complete").put("syntheticSessions", size)
                .put("cameraUsableMillis", usable).put("sessionOpenToFirstDraw", summary(sessionOpens))
                .put("sessionBackToMainFeedback", summary(sessionBacks)).put("timeboxOpenToFirstDraw", summary(workOpens))
                .put("sessionOpenSamplesMillis", JSONArray(sessionOpens)).put("sessionBackSamplesMillis", JSONArray(sessionBacks))
                .put("timeboxOpenSamplesMillis", JSONArray(workOpens)).put("openTimings", openTimings)
                .put("requestGapSamplesMillis", JSONArray(gaps))
                .put("saveLatencySamplesMillis", JSONArray(saves))
                .put("sessionOpenNotRun", if (size > 1000 && !includeLarge) "10k eager component already exceeded safe observation window" else "")
                .put("idleOwnerMaxQueued", idleOwner).put("idleModelMainMaxPending", idleMain)
                .put("ownerMaxQueued", owner.maxQueued.get()).put("ownerTasks", summary(owner.taskMillis))
                .put("modelMainMaxPending", main.maxPending.get()).put("modelMainLatency", summary(main.latencies))
                .put("requestGaps", summary(gaps)).put("saveLatency", summary(saves))
                .put("newCommittedPhotos", outcome.frames - initialFrames).put("retainedJpegs", jpegCount)
                .put("sampleWidth", dimensions.outWidth).put("sampleHeight", dimensions.outHeight)
                .put("missedSlots", outcome.missedSlots).put("processPssKiB", memory.totalPss)
                .put("sessionId", captureId).put("durationMillis", SystemClock.elapsedRealtime() - start)
                .put("testWindowKeepScreenOn", holdScreen)
                .put("measurementRevision", "direct-main-v1")
                .put("nominalIntervalMillis", outcome.nominalIntervalMillis).put("samplingStage", outcome.thinningStage)
                .put("healthyDurationMillis", healthyDuration).put("healthyCommittedPhotos", healthyPhotos)
                .put("healthyModelMissedSlots", healthyMissedEnd - healthyMissedStart)
                .put("browsingModelMissedSlots", outcome.missedSlots - healthyMissedEnd)
                .put("healthyRequestGaps", summary(healthyGaps)).put("healthySaveLatency", summary(healthySaves))
                .put("browsingDurationMillis", browsingDuration).put("browsingCommittedPhotos", browsingPhotos)
                .put("photoRateRatio", if (healthyRate > 0) browsingRate / healthyRate else 0.0)
                .put("cadenceComparable", healthyRate > 0 && outcome.nominalIntervalMillis == 500L && outcome.thinningStage == 0L)
                .put("limitations", "software shutter-request cadence, not hardware sensor timestamps; first-draw callback not GPU presentation/human UAT; test-only executor/handler wrappers after hydration"))
            val captures = strings(fixture.getJSONArray("captures")).toMutableSet().apply { add(checkNotNull(captureId)) }
            fixture.put("captures", JSONArray(captures.toList()))
            SessionRepository.atomicJson(marker, fixture)
            } finally {
                holdHandler.removeCallbacksAndMessages(null)
                instrumentation.runOnMainSync {
                    holdActivity?.takeIf { !it.isDestroyed }?.let { activity ->
                        val state = ViewModelProvider(activity)[CaptureViewModel::class.java].state.value
                        if (state?.capturing != true && state?.savingPhoto != true && state?.exporting != true)
                            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                holdActivity = null
            }
        }
    }
}
