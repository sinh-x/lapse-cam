package com.sinh.timelapse

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SessionRolloverTest {
    private fun fixture(body: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "rollover-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try { body(root) } finally { check(root.deleteRecursively()) }
    }
    private fun save(store: SessionStore, session: CaptureSession, offset: Long) {
        val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        try { store.completeFrame(session, store.beginFrame(session, 123, offset), bitmap) }
        finally { bitmap.recycle() }
    }

    @Test fun successorIsIndependentAndLateFrameStaysWithOldSession() = fixture { root ->
        val store = SessionStore(root, epochMillis = { 100 })
        val repo = SessionRepository(root, store, { 100 })
        repo.recover()
        var session = store.create(SamplingConfiguration.Adaptive(5, 15), SessionLens.FRONT,
            TimestampAppearance(), SessionSplit.TWO_HOURS, zoneId = "Pacific/Auckland")
        repo.register(session)
        repeat(3) {
            val old = session
            val intent = store.beginFrame(old, 500, 7_199_999)
            // Boundary arrives before this already-admitted frame finishes.
            val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
            try { store.completeFrame(old, intent, bitmap, 7_200_000) } finally { bitmap.recycle() }
            session = repo.close(old, true)!!
            assertNotEquals(old.directory, session.directory)
            assertTrue(File(old.directory, intent.name).isFile)
            assertFalse(File(session.directory, intent.name).exists())
            assertEquals(old.zoneId, session.zoneId)
            assertEquals(old.configuration, session.configuration)
            assertEquals(old.appearance, session.appearance)
            assertEquals(0, store.snapshot(session).count)
            assertEquals(0L, store.snapshot(session).stage)
            assertEquals(0L, store.snapshot(session).activeMillis)
            assertEquals(500L, store.snapshot(session).intervalMillis)
            assertEquals(SessionPhase.QUEUED, repo.get(old.directory.name).phase)
            assertEquals(session.directory, repo.close(old, true)!!.directory)
        }
    }

    @Test fun finishEmptyAndPauseRemainDistinctAcrossRecovery() = fixture { root ->
        var store = SessionStore(root)
        var repo = SessionRepository(root, store)
        repo.recover()
        val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance(), SessionSplit.DEFAULT)
        repo.register(session)
        store.checkpoint(session, 600_000, true)
        repo.checkpointSession(session, true)
        store = SessionStore(root)
        repo = SessionRepository(root, store)
        repo.recover()
        val paused = repo.current()!!
        assertEquals(session.directory, paused.directory)
        assertTrue(store.snapshot(paused).unknownTail)
        assertEquals(600_000L, store.snapshot(paused).activeMillis)
        assertEquals(SessionPhase.PAUSED, repo.get(session.directory.name).phase)
        assertNull(repo.close(paused, false))
        assertEquals(SessionPhase.EMPTY, repo.get(session.directory.name).phase)
        assertNull(repo.current())
    }

    @Test fun everyClosureCheckpointRecoversOneSuccessor() {
        listOf("closing", "selection-frozen", "successor-created", "index").forEach { failure -> fixture { root ->
            val store = SessionStore(root)
            var armed = false
            val repo = SessionRepository(root, store, checkpoint = { if (armed && it == failure) throw IllegalStateException("injected") })
            repo.recover()
            val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance(), SessionSplit.DEFAULT)
            repo.register(session)
            save(store, session, 0)
            armed = true
            runCatching { repo.close(session, true) }
            repeat(2) {
                val recoveredStore = SessionStore(root)
                val recovered = SessionRepository(root, recoveredStore)
                recovered.recover()
                assertEquals(2, recovered.records().size)
                assertEquals(1, recovered.records().count { it.phase == SessionPhase.PAUSED })
                assertEquals(1, recovered.records().count { it.phase == SessionPhase.QUEUED })
                assertEquals(0, recoveredStore.snapshot(recovered.current()!!).count)
            }
        } }
    }

    @Test fun liveOneHertzBoundaryDrainsOnePhotoWhileFailedExportDoesNotBlockNextSession() = fixture { root ->
        val monotonic = java.util.concurrent.atomic.AtomicLong(0)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val completion = java.util.concurrent.atomic.AtomicReference<() -> Unit>()
        val duplicate = java.util.concurrent.atomic.AtomicReference<() -> Unit>()
        val owner = androidx.lifecycle.ViewModelStore()
        lateinit var model: CaptureViewModel
        fun onMain(action: () -> Unit) = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        fun await(ready: (CaptureViewModel.State) -> Boolean): CaptureViewModel.State {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                var state: CaptureViewModel.State? = null
                onMain { state = model.state.value }
                if (state != null && ready(state!!)) return state!!
                Thread.sleep(10)
            }
            error("Timed out waiting for rollover model")
        }
        try {
            onMain {
                model = androidx.lifecycle.ViewModelProvider(owner, object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = CaptureViewModel(
                        ApplicationProvider.getApplicationContext<android.app.Application>(), root, File(root, "cache"),
                        availableBytes = { CapturePolicy.MIN_FREE_BYTES }, // Capture allowed; real Gallery encode blocked by H.
                        takePhoto = { _, file, executor, callback ->
                            requests.incrementAndGet()
                            duplicate.set { executor.execute { callback.onImageSaved(androidx.camera.core.ImageCapture.OutputFileResults(null)) } }
                            completion.set {
                                executor.execute {
                                    val image = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
                                    try { file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                                    finally { image.recycle() }
                                    callback.onImageSaved(androidx.camera.core.ImageCapture.OutputFileResults(null))
                                }
                            }
                        }, monotonicMillis = monotonic::get, epochMillis = { 100 }) as T
                })[CaptureViewModel::class.java]
            }
            await { !it.loading }
            onMain {
                model.setForeground(true)
                model.attachCamera(androidx.camera.core.ImageCapture.Builder().build(), SessionLens.REAR)
                model.start(SamplingConfiguration.Adaptive(5, 15), split = SessionSplit.TWO_HOURS)
            }
            val first = await { it.savingPhoto && completion.get() != null }
            val old = first.session!!
            monotonic.set(7_200_000)
            val draining = await { it.switching }
            assertFalse(draining.capturing)
            assertEquals(1, requests.get())
            val staleCallback = duplicate.get()
            completion.getAndSet(null).invoke()
            val next = await { it.capturing && it.session?.directory != old.directory && completion.get() != null }
            assertEquals(2, requests.get())
            assertEquals(0, next.frames)
            assertEquals(0L, next.activeMillis)
            assertEquals(old.zoneId, next.session?.zoneId)
            staleCallback.invoke()
            onMain { model.stop() }
            completion.getAndSet(null).invoke()
            val paused = await { !it.busy && it.frames == 1 }
            assertFalse(paused.recoveryBlocked)
            assertEquals(1, SessionStore(root).frames(old).size)
            onMain { model.finishSession() }
            await { it.session == null && !it.busy }
        } finally { onMain { owner.clear() } }
    }

    @Test fun sessionsViewKeepsIdentityFocusAndReachableButtonsAtLargeFont() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = ApplicationProvider.getApplicationContext<Context>()
            val config = android.content.res.Configuration(base.resources.configuration).apply { fontScale = 2f; screenWidthDp = 360 }
            val context = base.createConfigurationContext(config)
            val invoked = mutableListOf<String>()
            val view = SessionsView(context, { invoked.add("open:${it.id}") }, { invoked.add("retry:${it.id}") }, { invoked.add("delete:${it.id}") })
            val review = SessionReview("100-${UUID.randomUUID()}", 100, "Adaptive · 3h", 30, 30, 5_000, true,
                2, 3, 3, 1024, 1, "Needs attention", 0, 3, 0, "Retained failure", null, true, true)
            view.render(listOf(review))
            fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val buttons = descendants(view).filterIsInstance<android.widget.Button>()
            val retry = buttons.single { it.text.toString() == "Retry" }
            retry.performClick()
            val delete = buttons.single { it.text.toString() == "Delete source photos…" }
            delete.performClick()
            assertEquals(listOf("retry:${review.id}", "delete:${review.id}"), invoked)
            view.render(listOf(review.copy(status = "Exporting", canDelete = false, canRetry = false)))
            assertSame(retry, descendants(view).filterIsInstance<android.widget.Button>().single { it.text.toString() == "Retry" })
            assertFalse(retry.isEnabled)
            assertFalse(delete.isEnabled)
            val width = (360 * context.resources.displayMetrics.density).toInt()
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY))
            assertTrue(view.getChildAt(0).measuredHeight > 0)
            assertTrue(buttons.all { it.measuredWidth <= width })
        }
    }

    @Test fun oldSessionsNeverOptInAndInvalidNewSplitIsRetained() = fixture { root ->
        val store = SessionStore(root)
        val legacy = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance())
        val bytes = File(legacy.directory, "session.json").readBytes()
        val repo = SessionRepository(root, store)
        repo.recover()
        assertTrue(repo.records().isEmpty())
        assertArrayEquals(bytes, File(legacy.directory, "session.json").readBytes())
        val json = SessionRepository.readJson(File(legacy.directory, "session.json"))
            .put("lifecycleVersion", 1).put("split", "unknown")
        SessionRepository.atomicJson(File(legacy.directory, "session.json"), json)
        assertThrows(IllegalStateException::class.java) { store.latest() }
        assertTrue(File(legacy.directory, "session.json").exists())
    }
}
