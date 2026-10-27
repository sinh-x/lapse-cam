package com.sinh.timelapse

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeboxRecordingLinkTest {
    @Test fun productionCaptureHooksLinkAAndBWithoutCouplingWorkToCamera() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val root = File(app.cacheDir, "production-links-${UUID.randomUUID()}").apply { check(mkdir()) }
        val elapsed = java.util.concurrent.atomic.AtomicLong(500)
        val coordinator = WorkSessionCoordinator(app, root, remindersEnabled = false, clock = { WorkMoment("fixture-boot", elapsed.get(), 100_000 + elapsed.get()) })
        val vmOwner = androidx.lifecycle.ViewModelStore()
        lateinit var model: CaptureViewModel
        var created: CaptureViewModel? = null
        fun onMain(action: () -> Unit) = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        fun await(predicate: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 15_000
            while (android.os.SystemClock.elapsedRealtime() < end) {
                var ready = false; onMain { ready = predicate() }
                if (ready) return
                Thread.sleep(20)
            }
            error("Production link fixture did not become ready")
        }
        try {
            await { coordinator.state.value?.loaded == true }
            val a = WorkDraft(title = "A", minutes = "1")
            val b = WorkDraft(title = "B", minutes = "1")
            onMain { coordinator.save(a) {} }
            await { coordinator.state.value!!.snapshot.records.any { it.id == a.id } }
            onMain { coordinator.save(b) {}; coordinator.start(a.id) }
            await { coordinator.state.value!!.snapshot.running?.id == a.id }
            onMain {
                model = androidx.lifecycle.ViewModelProvider(vmOwner, object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = CaptureViewModel(app, root, File(root, "cache"),
                        availableBytes = { CapturePolicy.MIN_FREE_BYTES },
                        takePhoto = { _, file, executor, callback -> executor.execute {
                            val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
                            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } } finally { bitmap.recycle() }
                            callback.onImageSaved(androidx.camera.core.ImageCapture.OutputFileResults(null))
                        } }, monotonicMillis = elapsed::get, epochMillis = { 100_000 + elapsed.get() }, workContext = coordinator) as T
                })[CaptureViewModel::class.java]
                created = model
            }
            await { !model.state.value!!.loading }
            elapsed.set(1000)
            onMain {
                model.setForeground(true)
                model.attachCamera(androidx.camera.core.ImageCapture.Builder().build(), SessionLens.REAR)
                model.start(SamplingConfiguration.Fixed(60, 30))
            }
            await { model.state.value!!.frames == 1 && coordinator.state.value!!.snapshot.spans.singleOrNull()?.firstPhoto != null }
            val recording = model.state.value!!.session!!.directory.name
            elapsed.set(2000); onMain { coordinator.end(a.id) }
            await { coordinator.state.value!!.snapshot.running == null }
            onMain { assertTrue(model.state.value!!.capturing) }
            elapsed.set(3000); onMain { coordinator.start(b.id) }
            await { coordinator.state.value!!.snapshot.running?.id == b.id }
            elapsed.set(4000); onMain { coordinator.end(b.id) }
            await { coordinator.state.value!!.snapshot.running == null }
            onMain { assertTrue(model.state.value!!.capturing); assertEquals(recording, model.state.value!!.session!!.directory.name) }
            elapsed.set(5000); onMain { model.stop() }
            await { coordinator.state.value!!.snapshot.spans.singleOrNull()?.endElapsed == 5000L }
            val links = coordinator.state.value!!.snapshot.links(coordinator.now())
            assertEquals(listOf(1000L to 2000L, 3000L to 4000L), links.map { it.startElapsed to it.endElapsed })
            assertTrue(links.all { it.recordingId == recording })
            assertEquals(2, WorkSessionStore(root).load().records.size)
            assertEquals(1, WorkSessionStore(root).load().spans.size)
        } finally {
            onMain { vmOwner.clear() }
            created?.let { captureModel ->
                val worker = CaptureViewModel::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(captureModel) as java.util.concurrent.ExecutorService
                check(worker.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS))
            }
            coordinator.closeFixture(); check(root.deleteRecursively())
        }
    }

    @Test fun exactSegmentsReplayAndVerifiedManualCleanupRetainSeparateContextBytes() {
        val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "work-links-${UUID.randomUUID()}").apply { check(mkdir()) }
        val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val media = SessionStore(root)
            val one = media.create(SamplingConfiguration.Fixed(1, 30), SessionLens.REAR, TimestampAppearance())
            media.completeFrame(one, media.beginFrame(one, 100_010, 10), bitmap, 20)
            val two = media.create(SamplingConfiguration.Fixed(1, 30), SessionLens.REAR, TimestampAppearance())
            media.completeFrame(two, media.beginFrame(two, 100_030, 30), bitmap, 40)
            fun moment(ms: Long) = WorkMoment("fixture-boot", ms, 100_000 + ms)
            val work = WorkDraft(title = "Synthetic rollover context").validated(0)
            var snapshot = TimeboxPolicy.start(WorkSnapshot(records = listOf(work)), work.id, moment(0))
            snapshot = TimeboxPolicy.end(snapshot, work.id, moment(50)).copy(spans = listOf(
                RecordingSpan(UUID.randomUUID().toString(), one.directory.name, moment(0), moment(10), 20),
                RecordingSpan(UUID.randomUUID().toString(), two.directory.name, moment(25), moment(30), 40)))
            val store = WorkSessionStore(root)
            store.commit(snapshot)
            val links = WorkSessionStore(root).load().links(moment(100))
            assertEquals(listOf(10L to 20L, 30L to 40L), links.map { it.startElapsed to it.endElapsed })
            val before = File(root, "work-context/state.json").readBytes()
            val repository = SessionRepository(root, media)
            repository.deleteManual(one)
            assertArrayEquals(before, File(root, "work-context/state.json").readBytes())
            assertEquals(links, WorkSessionStore(root).load().links(moment(200)))
            assertTrue(media.exportFrames(two).isNotEmpty())
            assertFalse(one.directory.listFiles()!!.any { it.name.contains("work") || it.name.contains("timebox") })
            val remaining = media.exportFrames(two).associate { it.name to it.readBytes().toList() }
            assertEquals(links, store.archive(work.id, true).links(moment(200)))
            assertEquals(links, WorkSessionStore(root).load().links(moment(200)))
            val original = store.load().records.single()
            val edited = WorkEditBuffer(original).draft.copy(title = "Corrected context", type = "study", outcome = "Reviewed")
            val updated = store.update(store.editTarget(original.id).copy(draft = edited))
            assertEquals(original.copy(title = "Corrected context", type = "study", outcome = "Reviewed"), updated.records.single())
            assertEquals(links, updated.links(moment(200)))
            assertEquals(snapshot.spans, updated.spans)
            assertEquals(remaining, media.exportFrames(two).associate { it.name to it.readBytes().toList() })
            assertEquals(updated, WorkSessionStore(root).load())
            store.delete(work.id)
            val deleted = WorkSessionStore(root).load()
            assertTrue(deleted.links(moment(200)).isEmpty())
            assertEquals(snapshot.spans, deleted.spans)
            assertEquals(remaining, media.exportFrames(two).associate { it.name to it.readBytes().toList() })
        } finally { bitmap.recycle(); check(root.deleteRecursively()) }
    }
    @Test fun previewNoPhotoAndInterruptedTailNeverInventCoverage() {
        val work = WorkDraft(title = "A", minutes = "1").validated(0)
        val at = WorkMoment("fixture", 100, 100_000)
        val snapshot = TimeboxPolicy.start(WorkSnapshot(records = listOf(work)), work.id, at)
        val noPhoto = RecordingSpan(UUID.randomUUID().toString(), "recording", at)
        assertTrue(snapshot.copy(spans = listOf(noPhoto)).links(at.copy(elapsed = 10_000)).isEmpty())
        val interrupted = noPhoto.copy(firstPhoto = at.copy(elapsed = 200), endElapsed = 500, unknownTail = true)
        val link = snapshot.copy(spans = listOf(interrupted)).links(at.copy(elapsed = 999_999)).single()
        assertEquals(200L, link.startElapsed); assertEquals(500L, link.endElapsed); assertTrue(link.unknownTail)
    }
}
