package com.sinh.timelapse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated private directories only; no camera, permission changes, real disk filling or user data. */
@RunWith(AndroidJUnit4::class)
class AdaptiveSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private class ProcessLoss : Error()
    private fun root() = File(context.cacheDir, "adaptive-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private fun bitmap() = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
    private fun photos(session: CaptureSession) = session.directory.listFiles()!!.filter { it.name.endsWith(".jpg") }
        .associate { it.name to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) } }
    private fun add(store: SessionStore, session: CaptureSession, image: Bitmap, epoch: Long, active: Long) =
        store.completeFrame(session, store.beginFrame(session, epoch, active), image)

    @Test fun thinningRetainsAllPhotosAndExactFrozenSelectionAcrossRecoveryWithoutSteadyScans() {
        val root = root()
        val image = bitmap()
        var scans = 0
        try {
            val store = SessionStore(root, onHistoricalScan = { scans++ })
            val style = TimestampAppearance(StampFamily.SERIF, false, 60, StampPosition.TOP_RIGHT, true)
            val session = store.create(SamplingConfiguration.Adaptive(5, 15), SessionLens.FRONT, style)
            store.checkpoint(session, 0, true)
            repeat(76) { add(store, session, image, 100_000L - it, it * 500L) }
            val snapshot = store.snapshot(session)
            assertEquals(0, scans)
            assertEquals(76, snapshot.count)
            assertEquals(39, snapshot.selected)
            assertEquals(1L, snapshot.stage)
            assertEquals(1000L, snapshot.intervalMillis)
            val selected = store.exportFrames(session).map { it.name }
            assertEquals(0, scans)
            assertEquals((0L..74L step 2).toList() + 75L, selected.map { it.substringAfter("frame_").substringBefore('_').toLong() })
            val before = photos(session)
            assertEquals(76, before.size)
            store.checkpoint(session, 40_000, false)
            val reopened = SessionStore(root)
            val recovered = checkNotNull(reopened.latest())
            assertEquals(session, recovered)
            assertEquals(selected, reopened.exportFrames(recovered).map { it.name })
            assertEquals(40_000L, reopened.snapshot(recovered).activeMillis)
            assertFalse(reopened.snapshot(recovered).unknownTail)
            assertEquals(before, photos(recovered))
        } finally { image.recycle(); root.deleteRecursively() }
    }

    @Test fun everyFrameTransactionInterruptionReplaysCompletedJpegExactlyOnce() {
        for (boundary in listOf("reserved", "intent", "jpeg-written", "jpeg-renamed", "committed")) {
            val root = root()
            val image = bitmap()
            try {
                var armed = false
                val store = SessionStore(root, checkpoint = { if (armed && it == boundary) throw ProcessLoss() })
                val session = store.create(SamplingConfiguration.Adaptive(5, 15), SessionLens.REAR, TimestampAppearance())
                add(store, session, image, 1000, 100)
                store.checkpoint(session, 500, true)
                armed = true
                try { add(store, session, image, 2000, 700); fail("Must interrupt at $boundary") }
                catch (_: ProcessLoss) { }
                val before = photos(session)
                val reopened = SessionStore(root)
                val recovered = checkNotNull(reopened.latest())
                val count = if (boundary in listOf("jpeg-renamed", "committed")) 2 else 1
                assertEquals(count, reopened.snapshot(recovered).count)
                assertTrue(reopened.snapshot(recovered).unknownTail)
                assertEquals(before, photos(recovered))
                val again = SessionStore(root)
                assertEquals(count, again.snapshot(checkNotNull(again.latest())).count)
                // ID 1 was consumed even when its capture never completed.
                assertEquals(2L, reopened.beginFrame(recovered, 3000, 800).id)
            } finally { image.recycle(); root.deleteRecursively() }
        }
    }

    @Test fun atomicCheckpointInterruptionsKeepOldOrCompleteNewStateNeverAPartialMixture() {
        for (boundary in listOf("before-atomic:progress.json", "atomic-written:progress.json", "atomic-finished:progress.json")) {
            val root = root()
            try {
                var armed = false
                val store = SessionStore(root, checkpoint = { if (armed && it == boundary) throw ProcessLoss() })
                val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance())
                store.checkpoint(session, 1000, false)
                armed = true
                try { store.checkpoint(session, 2000, true); fail("Must interrupt at $boundary") }
                catch (_: ProcessLoss) { }
                val reopened = SessionStore(root)
                val recovered = checkNotNull(reopened.latest())
                val finished = boundary.startsWith("atomic-finished")
                assertEquals(if (finished) 2000L else 1000L, reopened.snapshot(recovered).activeMillis)
                assertEquals(finished, reopened.snapshot(recovered).unknownTail)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun duplicateAndOutOfOrderCommitsCannotMutateSelectionOrPhotos() {
        val root = root(); val image = bitmap()
        try {
            val store = SessionStore(root)
            val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance())
            val old = store.beginFrame(session, 1000, 0)
            val latest = store.beginFrame(session, 2000, 500)
            store.completeFrame(session, latest, image)
            val before = photos(session)
            assertThrows(IllegalStateException::class.java) { store.completeFrame(session, latest, image) }
            assertThrows(IllegalStateException::class.java) { store.completeFrame(session, old, image) }
            assertEquals(1, store.snapshot(session).count)
            assertEquals(before, photos(session))
        } finally { image.recycle(); root.deleteRecursively() }
    }

    @Test fun failedOrIncompleteRequestsAreNotSelectedAndNeverReuseIds() {
        val root = root(); val image = bitmap()
        try {
            val store = SessionStore(root)
            val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance())
            val failed = store.beginFrame(session, 1000, 10)
            File(session.directory, "${failed.name}.tmp").writeText("incomplete JPEG")
            File(session.directory, "capture.tmp.jpg").writeText("incomplete camera capture")
            add(store, session, image, 900, 20)
            val reopened = SessionStore(root)
            val recovered = checkNotNull(reopened.latest())
            assertEquals(1, reopened.snapshot(recovered).selected)
            assertEquals(1L, reopened.exportFrames(recovered).single().name.substringAfter("frame_").substringBefore('_').toLong())
            assertEquals(2L, reopened.beginFrame(recovered, 800, 30).id)
            assertTrue(File(session.directory, "${failed.name}.tmp").exists())
        } finally { image.recycle(); root.deleteRecursively() }
    }

    @Test fun unknownMissingAndContradictoryProvenanceBlockWithoutDeletingPhotosOrFallingBack() {
        for (fault in listOf("unknown", "missing-intent", "missing-photo", "selection", "active", "fractional")) {
            val root = root(); val image = bitmap()
            try {
                val store = SessionStore(root)
                store.create(1000, 30) // An older valid legacy session is never substituted.
                val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.FRONT, TimestampAppearance())
                add(store, session, image, 1000, 100)
                when (fault) {
                    "unknown" -> File(session.directory, "unattributed.jpg").writeBytes(store.exportFrames(session).single().readBytes())
                    "missing-intent" -> File(session.directory, "intent_0.json").delete()
                    "missing-photo" -> store.exportFrames(session).single().delete() // isolated fault injection only
                    else -> {
                        val file = File(session.directory, "progress.json")
                        val json = JSONObject(file.readText())
                        when (fault) {
                            "selection" -> json.put("selected", org.json.JSONArray().put(999))
                            "active" -> json.put("active", 0)
                            "fractional" -> json.put("nextId", 1.5)
                        }
                        file.writeText(json.toString())
                    }
                }
                val before = photos(session)
                repeat(2) {
                    assertThrows(IllegalStateException::class.java) { SessionStore(root).latest() }
                    assertEquals(before, photos(session))
                }
            } finally { image.recycle(); root.deleteRecursively() }
        }
    }

    @Test fun recoveredRunningCheckpointDisclosesTailAndExcludesDowntimeButNormalPauseDoesNot() {
        val root = root()
        try {
            val store = SessionStore(root)
            val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance())
            store.checkpoint(session, 600_000, true)
            val recoveredStore = SessionStore(root)
            val recovered = checkNotNull(recoveredStore.latest())
            assertEquals(600_000L, recoveredStore.snapshot(recovered).activeMillis)
            assertTrue(recoveredStore.snapshot(recovered).unknownTail)
            assertEquals(600_000L, SessionStore(root).let { it.snapshot(checkNotNull(it.latest())).activeMillis })
            recoveredStore.checkpoint(recovered, 1_200_000, false)
            recoveredStore.checkpoint(recovered, 1_100_000, false) // queued stale timer cannot reduce known time
            assertEquals(1_200_000L, recoveredStore.snapshot(recovered).activeMillis)
            assertTrue(recoveredStore.snapshot(recovered).unknownTail) // warning is durable for this session
        } finally { root.deleteRecursively() }
    }

    @Test fun versionThreeFixedRemainsUncappedAndLegacyHistoricalTimeIsUnavailable() {
        val root = root(); val image = bitmap()
        try {
            val store = SessionStore(root)
            val legacy = store.create(1000, 15)
            assertNull(store.snapshot(legacy).activeMillis)
            val session = store.create(SamplingConfiguration.Fixed(1, 15), SessionLens.REAR, TimestampAppearance())
            repeat(76) { add(store, session, image, it + 1000L, it * 1000L) }
            assertEquals(76, store.snapshot(session).selected)
            assertEquals(0L, store.snapshot(session).stage)
            assertEquals(76, store.exportFrames(session).size)
            assertEquals(session, SessionStore(root).latest())
        } finally { image.recycle(); root.deleteRecursively() }
    }

    @Test fun exportRejectsInsufficientHeadroomAndOverBudgetBeforeAllocatingAnyAttempt() {
        val root = root(); val image = bitmap()
        try {
            val store = SessionStore(root)
            val session = store.create(SamplingConfiguration.Adaptive(5, 15), SessionLens.REAR, TimestampAppearance())
            add(store, session, image, 1000, 0)
            val before = photos(session)
            val exporter = VideoExporter(context, File(root, "private"), File(root, "cache"),
                availableBytes = { ExportSpace.requiredBytes(1, 15) - 1 })
            assertThrows(IllegalStateException::class.java) { exporter.export(session, store.exportFrames(session)) {} }
            assertThrows(IllegalArgumentException::class.java) { exporter.export(session, List(76) { store.exportFrames(session).single() }) {} }
            assertTrue(File(root, "private/export-attempts").listFiles()!!.isEmpty())
            val atThreshold = VideoExporter(context, File(root, "private"), File(root, "cache"),
                availableBytes = { ExportSpace.requiredBytes(1, 15) },
                checkpoint = { if (it == "recorded") throw ProcessLoss() })
            assertThrows(ProcessLoss::class.java) { atThreshold.export(session, store.exportFrames(session)) {} }
            assertEquals(1, File(root, "private/export-attempts").listFiles()!!.size)
            atThreshold.reconcile()
            assertEquals(before, photos(session))
        } finally { image.recycle(); root.deleteRecursively() }
    }

    @Test fun liveModelShowsTenPauseFiveTenMinutesAndRecoversPausedWithFrozenConfiguration() {
        val root = root()
        val monotonic = java.util.concurrent.atomic.AtomicLong(0)
        val wall = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val completion = java.util.concurrent.atomic.AtomicReference<() -> Unit>()
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
            error("Timed out waiting for live model")
        }
        try {
            onMain {
                model = androidx.lifecycle.ViewModelProvider(owner, object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = CaptureViewModel(
                        ApplicationProvider.getApplicationContext<android.app.Application>(), root, File(root, "cache"),
                        availableBytes = { CapturePolicy.MIN_FREE_BYTES },
                        takePhoto = { _, file, executor, callback ->
                            requests.incrementAndGet()
                            completion.set {
                                executor.execute {
                                    val image = bitmap()
                                    try { file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                                    finally { image.recycle() }
                                    callback.onImageSaved(androidx.camera.core.ImageCapture.OutputFileResults(null))
                                }
                            }
                        }, monotonicMillis = monotonic::get, epochMillis = wall::get) as T
                })[CaptureViewModel::class.java]
            }
            await { !it.loading }
            val style = TimestampAppearance(date = true, position = StampPosition.TOP_RIGHT)
            onMain {
                model.setForeground(true)
                model.selectLens(SessionLens.FRONT)
                model.attachCamera(androidx.camera.core.ImageCapture.Builder().build(), SessionLens.FRONT)
                model.start(SamplingConfiguration.Adaptive(5, 24), style)
            }
            await { it.savingPhoto && completion.get() != null }
            monotonic.addAndGet(600_000); wall.addAndGet(600_000)
            val first = await { it.activeMillis == 600_000L && it.wallMillis == 600_000L }
            assertEquals(0, first.frames) // active time advances even before a JPEG completes
            assertEquals(1, requests.get()) // slow camera: no overlapping shutters or catch-up
            onMain { model.stop() }
            completion.getAndSet(null).invoke()
            await { !it.busy && it.frames == 1 }
            monotonic.addAndGet(300_000); wall.addAndGet(300_000)
            val paused = await { it.wallMillis == 900_000L }
            assertEquals(600_000L, paused.activeMillis)
            onMain { model.start(SamplingConfiguration.Fixed(60, 15), TimestampAppearance()) }
            await { it.savingPhoto && completion.get() != null }
            monotonic.addAndGet(600_000); wall.addAndGet(600_000)
            val second = await { it.activeMillis == 1_200_000L && it.wallMillis == 1_500_000L }
            assertEquals(SamplingConfiguration.Adaptive(5, 24), second.session?.configuration)
            assertEquals(style, second.session?.appearance)
            assertEquals(SessionLens.FRONT, second.lens)
            assertEquals(2, requests.get())
            onMain { model.stop() }
            completion.getAndSet(null).invoke()
            val finished = await { !it.busy && it.frames == 2 }
            assertEquals(2, finished.selectedFrames)
            onMain { owner.clear() }
            val reopened = SessionStore(root)
            val recovered = checkNotNull(reopened.latest())
            assertEquals(1_200_000L, reopened.snapshot(recovered).activeMillis)
            assertFalse(reopened.snapshot(recovered).unknownTail)
            assertEquals(style, recovered.appearance)
        } finally { onMain { owner.clear() }; root.deleteRecursively() }
    }

    @Test fun adaptiveMediaUsesExactlyFrozenSelectionAtEveryFpsWithoutModifyingPhotos() {
        for (fps in listOf(15, 24, 30)) for (count in listOf(1, 2, 5 * fps, 5 * fps + 1)) {
            val root = root(); val image = bitmap()
            try {
                val store = SessionStore(root)
                val session = store.create(SamplingConfiguration.Adaptive(5, fps), SessionLens.REAR, TimestampAppearance())
                repeat(count) { add(store, session, image, 1000L + it, it * 500L) }
                val selected = store.exportFrames(session)
                val before = photos(session)
                val output = File(root, "adaptive.mp4")
                val exporter = VideoExporter(context)
                exporter.encode(output, selected, fps) {}
                exporter.validateEncoded(output, selected.size, fps, 5)
                assertThrows(IllegalStateException::class.java) { exporter.validateEncoded(output, selected.size + 1, fps, 5) }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.path)
                    assertEquals(1, extractor.trackCount)
                    val format = extractor.getTrackFormat(0)
                    val duration = format.getLong(MediaFormat.KEY_DURATION)
                    assertTrue(duration <= 5_000_000)
                    assertTrue(kotlin.math.abs(duration - selected.size * 1_000_000L / fps) <= 1000)
                    extractor.selectTrack(0)
                    var count = 0
                    while (extractor.sampleTime >= 0) {
                        assertTrue(kotlin.math.abs(extractor.sampleTime - CaptureTiming.presentationTimeUs(count, fps)) <= 1)
                        count++
                        if (!extractor.advance()) break
                    }
                    assertEquals(selected.size, count)
                } finally { extractor.release() }
                assertEquals(before, photos(session))
            } finally { image.recycle(); root.deleteRecursively() }
        }
    }
}
