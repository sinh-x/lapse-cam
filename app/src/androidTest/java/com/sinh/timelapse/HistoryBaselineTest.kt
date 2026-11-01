package com.sinh.timelapse

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in, disposable component baseline, NOT a full-app/camera/UAT or hang-fix test.
 * Invoke one method/process and one historySize at a time. Refuses the normal package.
 * No real history/defaults are loaded. The media method owns only its UUID cache root/output.
 */
class HistoryBaselineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val n: Int get() = InstrumentationRegistry.getArguments().getString("historySize", "100").toInt().also {
        require(it in setOf(100, 1000, 10000))
    }
    private fun isolated() {
        org.junit.Assume.assumeTrue("Explicit opt-in benchmark, not routine regression",
            InstrumentationRegistry.getArguments().getString("ttl018Baseline") == "true")
        check(context.packageName == "com.sinh.timelapse.ttl018baseline") { "Isolated baseline APK required" }
    }
    private fun report(value: JSONObject) {
        val line = value.put("fixture", "ttl018-component-v2").put("epoch", System.currentTimeMillis()).toString()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "TTL018_BASELINE $line\n") })
        File(context.filesDir, "ttl018-baseline.jsonl").appendText(line + "\n")
    }
    private fun descendants(view: View): Int = 1 + if (view is ViewGroup)
        (0 until view.childCount).sumOf { descendants(view.getChildAt(it)) } else 0

    private class Probe {
        private val main = Handler(Looper.getMainLooper())
        private val live = AtomicBoolean(true)
        private val pending = AtomicInteger()
        val maxPending = AtomicInteger()
        val latencies = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val frameGaps = mutableListOf<Long>()
        private var previous = 0L
        private val frame = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (previous != 0L) frameGaps.add((time - previous) / 1_000_000)
                previous = time
                if (live.get()) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        private val thread = Thread {
            while (live.get()) {
                // One outstanding probe only: the benchmark must not manufacture a UI backlog.
                if (pending.compareAndSet(0, 1)) {
                    val start = SystemClock.elapsedRealtime()
                    maxPending.set(1)
                    main.post { pending.set(0); latencies.add(SystemClock.elapsedRealtime() - start) }
                }
                Thread.sleep(16)
            }
        }
        fun start() { main.post { Choreographer.getInstance().postFrameCallback(frame) }; thread.start() }
        fun stop(): JSONObject {
            live.set(false); thread.join(); instrumentationIdle()
            val sorted = synchronized(latencies) { latencies.sorted() }
            fun percentile(p: Double) = sorted.getOrElse(((sorted.size - 1) * p).toInt().coerceAtLeast(0)) { -1 }
            return JSONObject().put("probeP50Millis", percentile(.50)).put("probeP95Millis", percentile(.95))
                .put("probeMaxMillis", sorted.lastOrNull() ?: -1).put("probeMaxPending", maxPending.get())
                .put("maxFrameGapMillis", frameGaps.maxOrNull() ?: -1)
        }
        private fun instrumentationIdle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun history(kind: String) {
        isolated()
        val count = n
        val now = System.currentTimeMillis()
        val reviews = (0 until count).map { index ->
            SessionReview("baseline-$index", now - index.toLong() * 86_400_000 / 10,
                "Adaptive", 5, 15, 10_000, false, 20, 20, 20, 100_000, 0,
                if (index % 10 == 0) "Needs attention" else "Finished", 0, 3, 0,
                if (index % 10 == 0) "Synthetic legacy failure; stage unknown" else "", null, index % 10 == 0, false)
        }
        val timeboxes = (0 until count).map { index ->
            val saved = now - index.toLong() * 86_400_000 / 10
            val unstarted = kind == "timeboxes-unstarted" || index % 10 == 0
            WorkSession("timebox-$index", saved, "Synthetic $index", WorkType.WORK.key, 25,
                state = if (unstarted) WorkState.PLANNED else WorkState.ENDED,
                start = if (unstarted) null else WorkMoment("fixture", 1000, saved),
                deadline = if (unstarted) null else 1_501_000,
                endElapsed = if (unstarted) null else 2000,
                reason = if (unstarted) null else WorkEnd.USER)
        }
        val root = File(context.cacheDir, "history-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val owner = if (kind == "sessions") null else WorkSessionCoordinator(context.applicationContext as Application,
            root, remindersEnabled = false, clock = { WorkMoment("fixture", 2000, now) })
        if (owner != null) {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (owner.state.value?.loaded != true && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
            check(owner.state.value?.loaded == true)
        }
        report(JSONObject().put("event", "start").put("kind", kind).put("count", count))
        ActivityScenario.launch<HistoryBenchmarkActivity>(Intent(context, HistoryBenchmarkActivity::class.java)).use { scenario ->
            Thread.sleep(200)
            val probe = Probe().also { it.start() }
            Thread.sleep(100) // Let the frame probe establish its pre-work anchor.
            var mounted = 0
            var cold = 0L
            var warm = 0L
            var heap = 0L
            var workView: WorkSessionsView? = null
            scenario.onActivity { activity ->
                val begin = SystemClock.elapsedRealtime()
                val view: View = if (kind == "sessions") SessionsView(activity).apply { render(reviews) }
                    else WorkSessionsView(activity, checkNotNull(owner), { emptyList() }, {}, {}, {}).also {
                        workView = it
                        it.render(WorkSessionCoordinator.State(WorkSnapshot(records = timeboxes), loaded = true))
                    }
                activity.setContentView(view)
                val width = activity.resources.displayMetrics.widthPixels
                val height = activity.resources.displayMetrics.heightPixels
                fun layout() {
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                }
                layout()
                cold = SystemClock.elapsedRealtime() - begin
                mounted = descendants(view)
                val repeatStart = SystemClock.elapsedRealtime()
                if (view is SessionsView) view.render(reviews)
                else workView!!.render(WorkSessionCoordinator.State(WorkSnapshot(records = timeboxes), loaded = true))
                layout()
                warm = SystemClock.elapsedRealtime() - repeatStart
                heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            }
            Thread.sleep(200)
            val result = probe.stop()
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            report(result.put("kind", kind).put("count", count).put("mountLayoutMillis", cold)
                .put("repeatRenderLayoutMillis", warm).put("mountedViews", mounted)
                .put("javaHeapBytes", heap).put("processPssKiB", memory.totalPss)
                .put("scope", "component host, synthetic metadata; no capture or full-app hydration; probe queue is not production backlog"))
            scenario.onActivity { workView?.dispose() }
        }
        // Drain only this fixture's coordinator before removing its root.
        if (owner != null) {
            val worker = WorkSessionCoordinator::class.java.getDeclaredField("worker").apply { isAccessible = true }
                .get(owner) as java.util.concurrent.ExecutorService
            worker.shutdown(); check(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
        check(root.deleteRecursively())
    }
    @Test fun sessionsHistory() = history("sessions")
    @Test fun timeboxesHistory() = history("timeboxes-started")
    @Test fun unstartedHistory() = history("timeboxes-unstarted")

    @Test fun slowMediaAndMultiStepCleanup() {
        isolated()
        val root = File(context.cacheDir, "media-baseline-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val ownerThread = Executors.newSingleThreadExecutor()
        val media = Executors.newSingleThreadExecutor()
        val pending = AtomicInteger()
        val maxPending = AtomicInteger()
        val longestOwner = java.util.concurrent.atomic.AtomicLong()
        val owner = Executor { command ->
            val depth = pending.incrementAndGet(); maxPending.updateAndGet { maxOf(it, depth) }
            ownerThread.execute {
                pending.decrementAndGet()
                val start = SystemClock.elapsedRealtime()
                try { command.run() } finally {
                    val elapsed = SystemClock.elapsedRealtime() - start
                    longestOwner.updateAndGet { maxOf(it, elapsed) }
                }
            }
        }
        val done = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val verifies = AtomicInteger()
        val cleanupSteps = AtomicInteger()
        val progressEvents = AtomicInteger()
        val setupMillis = java.util.concurrent.atomic.AtomicLong()
        val exporter = VideoExporter(context, root, root)
        val store = SessionStore(root)
        val repo = SessionRepository(root, store)
        var output: android.net.Uri? = null
        var operation: String? = null
        val begin = SystemClock.elapsedRealtime()
        val probe = Probe().also { it.start() }
        report(JSONObject().put("event", "start").put("kind", "slow-media-cleanup").put("frames", 20))
        owner.execute {
            try {
                repo.recover()
                val session = store.create(SamplingConfiguration.Adaptive(5, 15), SessionLens.REAR,
                    TimestampAppearance(), SessionSplit.DEFAULT)
                repo.register(session)
                val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
                try {
                    repeat(20) { index ->
                        bitmap.eraseColor(android.graphics.Color.rgb(index * 10, 60, 80))
                        store.completeFrame(session, store.beginFrame(session, System.currentTimeMillis(), index * 500L), bitmap)
                    }
                } finally { bitmap.recycle() }
                repo.close(session, false)
                operation = repo.get(session.directory.name).operation
                setupMillis.set(SystemClock.elapsedRealtime() - begin)
                val queue = SessionExportQueue(owner, media, repo, exporter, { Long.MAX_VALUE }, { _, _ ->
                    progressEvents.incrementAndGet()
                    val record = repo.get(session.directory.name)
                    if (record.phase == SessionPhase.CLEANUP) cleanupSteps.incrementAndGet()
                    if (record.phase == SessionPhase.FINISHED) done.countDown()
                    if (record.phase == SessionPhase.NEEDS_ATTENTION || record.error.isNotEmpty()) {
                        failure.set(IllegalStateException(record.error)); done.countDown()
                    }
                }, verify = { s, op, uri, count ->
                    verifies.incrementAndGet()
                    Thread.sleep(200) // Explicit slow-media injection, not an observed storage fault.
                    exporter.verifyPublished(s, op, uri, count)
                }, published = { output = it })
                queue.setOpen(true, 0)
            } catch (error: Throwable) { failure.set(error); done.countDown() }
        }
        try {
            assertTrue("Disposable media baseline timed out; retain root $root", done.await(180, TimeUnit.SECONDS))
            val result = probe.stop()
            failure.get()?.let { throw AssertionError("Media fixture failed; retain $root", it) }
            report(result.put("kind", "slow-media-cleanup").put("frames", 20)
                .put("elapsedMillis", SystemClock.elapsedRealtime() - begin).put("verificationCalls", verifies.get())
                .put("cleanupNotifications", cleanupSteps.get()).put("reviewNotifications", progressEvents.get())
                .put("maxOwnerPending", maxPending.get()).put("longestOwnerTaskMillis", longestOwner.get())
                .put("fixtureSetupMillis", setupMillis.get())
                .put("injectedDelayPerVerifyMillis", 200).put("operation", operation)
                .put("scope", "real encoder/verification/serialized cleanup; generated JPEGs, NOT camera cadence"))
            assertTrue(verifies.get() > 2)
            output?.let { check(context.contentResolver.delete(it, null, null) == 1) }
            output = null
        } finally {
            probe.stop()
            ownerThread.shutdown(); media.shutdown()
            val drained = ownerThread.awaitTermination(10, TimeUnit.SECONDS) && media.awaitTermination(10, TimeUnit.SECONDS)
            if (drained && failure.get() == null && output == null && done.count == 0L) check(root.deleteRecursively())
        }
    }
}
