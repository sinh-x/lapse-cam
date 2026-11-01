package com.sinh.timelapse

import android.content.Context
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
    private fun waitFor(scenario: ActivityScenario<MainActivity>, predicate: (CaptureViewModel.State) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 180_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            scenario.onActivity { ready = predicate(ViewModelProvider(it)[CaptureViewModel::class.java].state.value!!) }
            if (ready) return
            Thread.sleep(25)
        }
        fail("Baseline state timeout; retain all fixture data")
    }

    @Test fun foregroundCaptureAndBrowse() {
        admitted()
        check(context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        val fixture = JSONObject(marker.readText())
        check(fixture.getJSONArray("sessions").length() == size)
        report(JSONObject().put("event", "app-start").put("syntheticSessions", size))
        val start = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { !it.loading && !it.recoveryBlocked && it.boundLens == it.lens }
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
            scenario.onActivity {
                model = ViewModelProvider(it)[CaptureViewModel::class.java]
                owner = replace<ExecutorService>(model, "worker") { old -> OwnerProbe(old) } as OwnerProbe
                main = replace<Handler>(model, "main") { ModelMainProbe() } as ModelMainProbe
                initialFrames = model.state.value!!.frames; lastFrames = initialFrames
                model.state.observeForever(observer)
            }
            Thread.sleep(3000) // App-idle comparison, no capture or browser.
            val idleOwner = owner.maxQueued.get()
            val idleMain = main.maxPending.get()
            scenario.onActivity { model.start(SamplingConfiguration.Adaptive(30, 15), split = SessionSplit.OFF) }
            waitFor(scenario) { it.capturing && it.frames >= initialFrames + 3 }
            scenario.onActivity { captureId = model.state.value!!.session!!.directory.name }
            Thread.sleep(12_000)
            report(JSONObject().put("event", "healthy-capture").put("syntheticSessions", size)
                .put("requestGaps", summary(gaps)).put("saveLatency", summary(saves)))
            val sessionOpens = mutableListOf<Long>()
            val sessionBacks = mutableListOf<Long>()
            val workOpens = mutableListOf<Long>()
            // Original 10k Sessions renderer already exceeded the observation window/heap headroom.
            // Do not repeat that known runaway while the camera holds valuable disposable samples.
            fun openToDraw(button: String, field: String): Long {
                val begin = SystemClock.elapsedRealtime()
                val drawn = CountDownLatch(1)
                val at = AtomicLong()
                scenario.onActivity { activity ->
                    WorkUiFixtures.field<Button>(activity, button).performClick()
                    val root = WorkUiFixtures.field<android.view.View>(activity, field).rootView
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
                return at.get() - begin
            }
            fun backToFeedback(): Long {
                val begin = SystemClock.elapsedRealtime()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                val feedback = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post { feedback.countDown() }
                check(feedback.await(60, TimeUnit.SECONDS))
                return SystemClock.elapsedRealtime() - begin
            }
            if (size <= 1000) repeat(3) {
                sessionOpens.add(openToDraw("sessionsButton", "sessionsView"))
                sessionBacks.add(backToFeedback())
                Thread.sleep(500)
            }
            if (size <= 1000) {
                workOpens.add(openToDraw("workButton", "workView"))
                backToFeedback()
            }
            Thread.sleep(3000)
            scenario.onActivity { model.stop("Disposable baseline paused; photos retained") }
            waitFor(scenario) { !it.savingPhoto && !it.capturing }
            val barrier = CountDownLatch(1); owner.execute { barrier.countDown() }; check(barrier.await(30, TimeUnit.SECONDS))
            var state: CaptureViewModel.State? = null
            scenario.onActivity { state = model.state.value; model.state.removeObserver(observer) }
            val outcome = checkNotNull(state)
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val directory = outcome.session!!.directory
            val jpegCount = directory.listFiles()!!.count { it.name.endsWith(".jpg") && it.name.startsWith("frame_") }
            val sample = directory.listFiles()!!.first { it.name.startsWith("frame_") && it.name.endsWith(".jpg") }
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sample.path, dimensions)
            report(JSONObject().put("event", "app-complete").put("syntheticSessions", size)
                .put("cameraUsableMillis", usable).put("sessionOpenToFirstDraw", summary(sessionOpens))
                .put("sessionBackToMainFeedback", summary(sessionBacks)).put("timeboxOpenToFirstDraw", summary(workOpens))
                .put("sessionOpenNotRun", if (size > 1000) "10k eager component already exceeded safe observation window" else "")
                .put("idleOwnerMaxQueued", idleOwner).put("idleModelMainMaxPending", idleMain)
                .put("ownerMaxQueued", owner.maxQueued.get()).put("ownerTasks", summary(owner.taskMillis))
                .put("modelMainMaxPending", main.maxPending.get()).put("modelMainLatency", summary(main.latencies))
                .put("requestGaps", summary(gaps)).put("saveLatency", summary(saves))
                .put("newCommittedPhotos", outcome.frames - initialFrames).put("retainedJpegs", jpegCount)
                .put("sampleWidth", dimensions.outWidth).put("sampleHeight", dimensions.outHeight)
                .put("missedSlots", outcome.missedSlots).put("processPssKiB", memory.totalPss)
                .put("sessionId", captureId).put("durationMillis", SystemClock.elapsedRealtime() - start)
                .put("limitations", "software shutter-request cadence, not hardware sensor timestamps; first-draw callback not GPU presentation/human UAT; test-only executor/handler wrappers after hydration"))
            val captures = strings(fixture.getJSONArray("captures")).toMutableSet().apply { add(checkNotNull(captureId)) }
            fixture.put("captures", JSONArray(captures.toList()))
            SessionRepository.atomicJson(marker, fixture)
        }
    }
}
