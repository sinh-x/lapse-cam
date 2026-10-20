package com.sinh.timelapse

import android.content.Context
import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a device or emulator with a surface-input H.264 encoder. Does not need camera access. */
@RunWith(AndroidJUnit4::class)
class MediaPipelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun sessionSurvivesRecreationAndIgnoresIncompletePhotos() {
        val root = File(context.cacheDir, "store-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val store = SessionStore(root)
            val session = store.create(5_000, 30)
            val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                assertEquals(1, store.saveFrame(session, bitmap, 1_000))
                assertEquals(2, store.saveFrame(session, bitmap, 2_000))
            } finally { bitmap.recycle() }
            File(session.directory, "frame_00000002_3000.jpg.tmp").writeText("interrupted write")
            File(session.directory, "capture.tmp.jpg").writeText("interrupted camera capture")
            val reopened = SessionStore(root)
            val recovered = checkNotNull(reopened.latest())
            assertEquals(session, recovered)
            assertEquals(2, reopened.frames(recovered).size)
            assertTrue(reopened.frames(recovered)[0].name.endsWith("_1000.jpg"))
            reopened.discard(recovered)
            assertNull(reopened.latest())
        } finally { root.deleteRecursively() }
    }

    @Test fun lensesRoundTripAndLegacyRecoveryNeverRewritesPhotos() {
        for (lens in SessionLens.entries) {
            val root = fixtureRoot("lens")
            try {
                val store = SessionStore(root)
                val session = store.create(2_000, 24, lens)
                val frame = File(session.directory, "frame_00000000_1000.jpg").apply { writeText("completed JPEG bytes") }
                val bytes = frame.readBytes()
                assertEquals(session, SessionStore(root).latest())
                val metadata = File(session.directory, "session.json")
                val legacy = JSONObject(metadata.readText()).put("version", 1).apply { remove("lens") }.toString()
                metadata.writeText(legacy)
                assertEquals(SessionLens.REAR, checkNotNull(SessionStore(root).latest()).lens)
                assertEquals(legacy, metadata.readText())
                assertArrayEquals(bytes, frame.readBytes())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun invalidMetadataBlocksOlderSessionFallbackAndPreservesEveryFile() {
        val root = fixtureRoot("invalid-session")
        try {
            val store = SessionStore(root)
            val older = store.create(5_000, 30, SessionLens.REAR)
            val newer = store.create(1_000, 15, SessionLens.FRONT)
            val metadata = File(newer.directory, "session.json")
            val valid = metadata.readText()
            File(newer.directory, "frame_00000000_1000.jpg").writeText("keep photo")
            val variants = listOf(
                JSONObject(valid).put("version", 999).toString(),
                JSONObject(valid).put("version", 2.5).toString(),
                JSONObject(valid).put("version", "2").toString(),
                JSONObject(valid).put("lens", "unknown").toString(),
                JSONObject(valid).put("lens", JSONObject.NULL).toString(),
                JSONObject(valid).apply { remove("lens") }.toString(),
                "{truncated",
            )
            for (bad in variants) {
                metadata.writeText(bad)
                val before = inventory(root)
                repeat(2) {
                    try {
                        SessionStore(root).latest()
                        fail("Malformed session must not be hidden by an older valid session")
                    } catch (expected: IllegalStateException) {
                        assertTrue(expected.message.orEmpty().contains("repair metadata"))
                    }
                    assertEquals(before, inventory(root))
                    assertTrue(older.directory.exists())
                }
            }
        } finally { root.deleteRecursively() }
    }

    private fun fixtureRoot(name: String) = File(context.cacheDir, "$name-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private fun inventory(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to it.readBytes().toList()
    }

    @Test fun rendererNormalizesExifAndBurnsTimestamp() {
        val root = File(context.cacheDir, "renderer-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val photo = File(root, "photo.jpg")
            photo.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            source.recycle()
            ExifInterface(photo).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val rendered = TimestampRenderer.render(photo, 0, "UTC")
            try {
                assertEquals(VIDEO_WIDTH, rendered.width)
                assertEquals(VIDEO_HEIGHT, rendered.height)
                assertTrue(Color.green(rendered.getPixel(360, 400)) > 220)
                // White clock glyphs exist in the lower-left region, not merely a preview overlay.
                var whitePixels = 0
                for (y in 1160 until 1260) for (x in 28 until 350) {
                    val color = rendered.getPixel(x, y)
                    if (Color.red(color) > 230 && Color.green(color) > 230 && Color.blue(color) > 230) whitePixels++
                }
                assertTrue("Timestamp must be burned into JPEG pixels", whitePixels > 200)
            } finally { rendered.recycle() }
        } finally { root.deleteRecursively() }
    }

    @Test fun frontSceneIsMirroredOnceAfterEveryExifOrientationAndClockStaysReadable() {
        val root = fixtureRoot("front-renderer")
        try {
            val photo = File(root, "asymmetric.jpg")
            val source = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(source)
            val paint = Paint()
            val colors = listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW)
            colors.forEachIndexed { index, color ->
                paint.color = color
                val x = (index % 2) * VIDEO_WIDTH / 2f
                val y = (index / 2) * VIDEO_HEIGHT / 2f
                canvas.drawRect(x, y, x + VIDEO_WIDTH / 2f, y + VIDEO_HEIGHT / 2f, paint)
            }
            photo.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            source.recycle()
            val clock = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
            TimestampRenderer.drawClock(Canvas(clock), "14:32:08")
            try {
                for (orientation in 1..8) {
                    ExifInterface(photo).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                        saveAttributes()
                    }
                    val rear = TimestampRenderer.render(photo, 52_328_000L, "UTC", SessionLens.REAR)
                    val front = TimestampRenderer.render(photo, 52_328_000L, "UTC", SessionLens.FRONT)
                    try {
                        val uprightQuadrants = listOf(
                            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
                            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
                            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
                            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
                            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
                            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
                        )[orientation - 1]
                        listOf(140 to 200, 560 to 200, 140 to 950, 560 to 950).forEachIndexed { index, (x, y) ->
                            val pixel = rear.getPixel(x, y)
                            val expected = uprightQuadrants[index]
                            assertTrue("Upright EXIF $orientation quadrant $index",
                                kotlin.math.abs(Color.red(pixel) - Color.red(expected)) < 10 &&
                                kotlin.math.abs(Color.green(pixel) - Color.green(expected)) < 10 &&
                                kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) < 10)
                        }
                        for (y in listOf(200, 950)) for (x in listOf(140, 560)) {
                            assertEquals("Scene must reflect exactly once for EXIF $orientation",
                                rear.getPixel(VIDEO_WIDTH - 1 - x, y), front.getPixel(x, y))
                        }
                        var glyphs = 0
                        for (y in 1160 until 1260) for (x in 28 until 350) {
                            if (clock.getPixel(x, y) == Color.WHITE) {
                                assertEquals("Readable front clock, EXIF $orientation", Color.WHITE, front.getPixel(x, y))
                                assertEquals(Color.WHITE, rear.getPixel(x, y))
                                glyphs++
                            }
                        }
                        assertTrue(glyphs > 200)
                    } finally { front.recycle(); rear.recycle() }
                }
            } finally { clock.recycle() }
        } finally { root.deleteRecursively() }
    }

    @Test fun everyFpsHasExactSamplesPtsAndSingleFrameDurationWithoutDuplicateImages() {
        val root = fixtureRoot("all-fps")
        try {
            for (fps in listOf(15, 24, 30)) for (count in listOf(1, 4)) {
                val store = SessionStore(File(root, "$fps-$count").apply { mkdirs() })
                val session = store.create(1_000, fps, SessionLens.FRONT)
                val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.BLUE)
                    canvas.drawRect(0f, 0f, VIDEO_WIDTH / 2f, VIDEO_HEIGHT.toFloat(), Paint().apply { color = Color.RED })
                    val raw = File(root, "raw-$fps-$count.jpg")
                    raw.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                    val front = TimestampRenderer.render(raw, 52_328_000L, "UTC", SessionLens.FRONT)
                    try { repeat(count) { store.saveFrame(session, front, 1_000L + it * 1_000) } }
                    finally { front.recycle() }
                } finally { bitmap.recycle() }
                val before = inventory(session.directory)
                val output = File(root, "$fps-$count.mp4")
                VideoExporter(context).encode(output, store.frames(session), fps) {}
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.path)
                    assertEquals(1, extractor.trackCount)
                    val format = extractor.getTrackFormat(0)
                    assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
                    assertEquals(VIDEO_WIDTH, format.getInteger(MediaFormat.KEY_WIDTH))
                    assertEquals(VIDEO_HEIGHT, format.getInteger(MediaFormat.KEY_HEIGHT))
                    assertTrue(kotlin.math.abs(format.getLong(MediaFormat.KEY_DURATION) - count * 1_000_000L / fps) <= 1_000)
                    extractor.selectTrack(0)
                    var samples = 0
                    var previous = -1L
                    while (extractor.sampleTime >= 0) {
                        val pts = extractor.sampleTime
                        assertTrue(pts > previous)
                        assertTrue(kotlin.math.abs(pts - samples * 1_000_000L / fps) <= 1)
                        previous = pts
                        samples++
                        if (!extractor.advance()) break
                    }
                    assertEquals(count, samples)
                } finally { extractor.release() }
                assertEquals(count, decodedFrameCount(output))
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(output.path)
                    val duration = checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                    assertTrue("Duration $duration ms at $fps fps", kotlin.math.abs(duration - count * 1_000.0 / fps) <= 1.0)
                    val decoded = checkNotNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                    try {
                        // Front scene was mirrored before stamping; export must NOT reflect it again.
                        assertTrue(Color.blue(decoded.getPixel(140, 200)) > 180)
                        assertTrue(Color.red(decoded.getPixel(560, 200)) > 180)
                        var white = 0
                        for (y in 1160 until 1260) for (x in 28 until 350) {
                            val pixel = decoded.getPixel(x, y)
                            if (Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200) white++
                        }
                        assertTrue("Encoded clock pixels", white > 200)
                    } finally { decoded.recycle() }
                } finally { retriever.release() }
                assertEquals(before, inventory(session.directory))
            }
        } finally { root.deleteRecursively() }
    }

    private fun decodedFrameCount(file: File): Int {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var inputEnded = false
            var frames = 0
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(checkNotNull(codec.getInputBuffer(index)), 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    if (info.size > 0) frames++
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return frames
                }
            }
            error("Decoder timed out")
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitState(model: CaptureViewModel, ready: (CaptureViewModel.State) -> Boolean): CaptureViewModel.State {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            var state: CaptureViewModel.State? = null
            onMain { state = model.state.value }
            state?.let { if (ready(it)) return it }
            Thread.sleep(10)
        }
        error("Timed out waiting for model state")
    }

    private fun fixtureModel(
        root: File,
        availableBytes: (File) -> Long = { CapturePolicy.MIN_FREE_BYTES },
        takePhoto: (ImageCapture, File, Executor, ImageCapture.OnImageSavedCallback) -> Unit = { _, _, executor, callback ->
            executor.execute { callback.onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "Fixture capture failure", null)) }
        },
    ): Pair<CaptureViewModel, ViewModelStore> {
        val store = ViewModelStore()
        lateinit var model: CaptureViewModel
        onMain {
            model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CaptureViewModel(
                    ApplicationProvider.getApplicationContext<Application>(), root, File(root, "cache"), availableBytes, takePhoto) as T
            })[CaptureViewModel::class.java]
        }
        awaitState(model) { !it.loading }
        return model to store
    }

    @Test fun modelFreezesSessionLensDuringCreationAndBlocksUnavailableLensWithoutFallback() {
        val root = fixtureRoot("model-lens")
        val (model, owner) = fixtureModel(root)
        try {
            onMain {
                model.setForeground(true)
                model.selectLens(SessionLens.FRONT)
                model.attachCamera(ImageCapture.Builder().build(), SessionLens.REAR)
                model.start(1_000, 15)
                assertNull(model.state.value?.session)
                model.attachCamera(ImageCapture.Builder().build(), SessionLens.FRONT)
                model.start(1_000, 15)
                model.selectLens(SessionLens.REAR) // Creation has already frozen front.
                model.stop()
            }
            val created = awaitState(model) { it.session != null && !it.busy }
            assertEquals(SessionLens.FRONT, created.session?.lens)
            assertFalse(created.capturing)
            onMain {
                model.attachCamera(null)
                model.selectLens(SessionLens.REAR)
                model.start(60_000, 30)
                assertEquals(SessionLens.FRONT, model.state.value?.lens)
                assertFalse(checkNotNull(model.state.value).capturing)
                assertFalse(checkNotNull(model.state.value).canSelectLens)
                model.discard() // Explicit confirmed-delete transition only.
            }
            awaitState(model) { it.session == null && !it.busy }
            onMain {
                model.selectLens(SessionLens.REAR)
                assertEquals(SessionLens.REAR, model.state.value?.lens)
            }
        } finally { onMain { owner.clear() }; root.deleteRecursively() }
    }

    @Test fun stopFinalizesAtMostInFlightPhotoAndRecreationNeverRestartsCapture() {
        val root = fixtureRoot("model-stop")
        val requests = AtomicInteger()
        val completion = AtomicReference<() -> Unit>()
        val (model, owner) = fixtureModel(root, takePhoto = { _, file, executor, callback ->
            requests.incrementAndGet()
            completion.set {
                executor.execute {
                    val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
                    try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
                    finally { bitmap.recycle() }
                    callback.onImageSaved(ImageCapture.OutputFileResults(null))
                }
            }
        })
        try {
            onMain {
                model.setForeground(true)
                model.selectLens(SessionLens.FRONT)
                model.attachCamera(ImageCapture.Builder().build(), SessionLens.FRONT)
                model.start(1_000, 15)
            }
            awaitState(model) { it.savingPhoto && completion.get() != null }
            onMain { model.stop(); model.setForeground(false); model.attachCamera(null) }
            checkNotNull(completion.get()).invoke()
            val stopped = awaitState(model) { it.frames == 1 && !it.busy }
            assertFalse(stopped.capturing)
            Thread.sleep(2_100)
            assertEquals(1, requests.get())
            onMain { model.setForeground(true) }
            assertFalse(awaitState(model) { !it.busy }.capturing)
            onMain { owner.clear() }
            val before = inventory(checkNotNull(stopped.session).directory)
            val (recovered, recoveredOwner) = fixtureModel(root)
            try {
                val state = awaitState(recovered) { !it.loading }
                assertEquals(SessionLens.FRONT, state.lens)
                assertEquals(1, state.frames)
                assertEquals(1_000L, state.session?.intervalMillis)
                assertEquals(15, state.session?.fps)
                assertFalse(state.capturing)
                assertEquals(before, inventory(checkNotNull(state.session).directory))
            } finally { onMain { recoveredOwner.clear() } }
        } finally { onMain { owner.clear() }; root.deleteRecursively() }
    }

    @Test fun storageCheckCannotIssueShutterAfterBackgroundAndLowSpacePermitsExplicitRetry() {
        val root = fixtureRoot("model-storage")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val space = java.util.concurrent.atomic.AtomicLong(CapturePolicy.MIN_FREE_BYTES - 1)
        val (model, owner) = fixtureModel(root, availableBytes = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            space.get()
        }, takePhoto = { _, _, executor, callback ->
            requests.incrementAndGet()
            executor.execute { callback.onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "Fixture camera error", null)) }
        })
        try {
            onMain {
                model.setForeground(true)
                model.attachCamera(ImageCapture.Builder().build(), SessionLens.REAR)
                model.start(1_000, 15)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            onMain { model.setForeground(false) }
            release.countDown()
            awaitState(model) { !it.busy }
            assertEquals(0, requests.get())
            onMain { model.setForeground(true); model.start(1_000, 15) }
            val low = awaitState(model) { !it.busy && !it.capturing }
            assertTrue(low.message.contains("Storage"))
            assertEquals(0, requests.get())
            space.set(CapturePolicy.MIN_FREE_BYTES)
            onMain { model.start(1_000, 15) }
            val failed = awaitState(model) { !it.busy && !it.capturing && it.message.contains("Fixture camera error") }
            assertEquals(1, requests.get())
            assertNotNull(failed.session)
            assertFalse(failed.working || failed.savingPhoto || failed.exporting)
        } finally { release.countDown(); onMain { owner.clear() }; root.deleteRecursively() }
    }

    @Test fun malformedSessionKeepsModelBlockedUntilMetadataIsRepairedAndRetried() {
        val root = fixtureRoot("model-recovery")
        val session = SessionStore(root).create(5_000, 24, SessionLens.FRONT)
        val metadata = File(session.directory, "session.json")
        val valid = metadata.readText()
        metadata.writeText("{broken")
        val (model, owner) = fixtureModel(root)
        try {
            onMain {
                assertTrue(checkNotNull(model.state.value).recoveryBlocked)
                model.selectLens(SessionLens.FRONT)
                model.attachCamera(ImageCapture.Builder().build(), SessionLens.REAR)
                model.setForeground(true)
                model.start(1_000, 30)
                assertNull(model.state.value?.session)
                assertFalse(checkNotNull(model.state.value).canSelectLens)
            }
            assertEquals("{broken", metadata.readText())
            metadata.writeText(valid)
            onMain { model.retryRecovery() }
            val recovered = awaitState(model) { !it.loading && !it.recoveryBlocked }
            assertEquals(SessionLens.FRONT, recovered.lens)
            assertFalse(recovered.capturing)
        } finally { onMain { owner.clear() }; root.deleteRecursively() }
    }

    private class SimulatedProcessDeath : Error()

    @Test fun interruptedExportsCleanOnlyTheirPendingOutputsAndPreservePublishedVideos() {
        val root = fixtureRoot("export-recovery")
        val allocatedUris = mutableMapOf<Uri, String>()
        try {
            val store = SessionStore(root)
            val session = store.create(1_000, 15, SessionLens.FRONT)
            val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
            try { store.saveFrame(session, bitmap, 1_000) } finally { bitmap.recycle() }
            val before = inventory(session.directory)
            val files = File(root, "private")
            val cache = File(root, "cache")
            val unrelatedCache = File(cache, "unrelated.mp4").apply { parentFile?.mkdirs(); writeText("not an attempt") }
            val unrelatedName = "Unrelated-${UUID.randomUUID()}.mp4"
            val unrelatedUri = insertFixtureVideo(unrelatedName, "Movies/TimestampTimelapse/", true)
            allocatedUris[unrelatedUri] = unrelatedName
            val publishedBytes = mutableMapOf<Uri, List<Byte>>()
            for (boundary in listOf("recorded", "encoded", "inserted", "copying", "copied", "published")) {
                val exporter = VideoExporter(context, files, cache) { if (it == boundary) throw SimulatedProcessDeath() }
                try {
                    exporter.export(session, store.frames(session)) {}
                    fail("Must simulate interruption at $boundary")
                } catch (_: SimulatedProcessDeath) { /* No normal exception cleanup: emulate process death. */ }
                val journal = checkNotNull(File(files, "export-attempts").listFiles()).single { it.extension == "json" }
                val id = JSONObject(journal.readText()).getString("id")
                val uri = fixtureVideoNamed("Timelapse-$id.mp4")
                if (uri != null) {
                    allocatedUris[uri] = "Timelapse-$id.mp4"
                    if (boundary == "published") {
                        publishedBytes[uri] = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes().toList() }
                    }
                }
                val recovery = VideoExporter(context, files, cache)
                recovery.reconcile()
                recovery.reconcile()
                assertFalse(journal.exists())
                assertFalse(File(cache, "export-attempts/$id.mp4").exists())
                if (boundary == "published") assertEquals(uri, fixtureVideoNamed("Timelapse-$id.mp4"))
                else assertNull(fixtureVideoNamed("Timelapse-$id.mp4"))
                assertEquals(before, inventory(session.directory))
                assertEquals("not an attempt", unrelatedCache.readText())
                assertEquals(unrelatedUri, fixtureVideoNamed(checkNotNull(videoName(unrelatedUri))))
                for ((published, bytes) in publishedBytes) {
                    assertEquals(bytes, checkNotNull(context.contentResolver.openInputStream(published)).use { it.readBytes().toList() })
                }
                // Explicit retry after reconciliation succeeds and does not alter source photos.
                val retry = recovery.export(session, store.frames(session)) {}
                allocatedUris[retry] = checkNotNull(videoName(retry))
                publishedBytes[retry] = checkNotNull(context.contentResolver.openInputStream(retry)).use { it.readBytes().toList() }
                assertEquals(before, inventory(session.directory))
            }
        } finally {
            // Test teardown only: exact URIs allocated by THIS isolated fixture, never discovered user media.
            allocatedUris.forEach { (uri, name) -> deleteFixtureVideoIfPresent(uri, name) }
            root.deleteRecursively()
        }
    }

    @Test fun ambiguousOwnershipAndCleanupFailureRetainRecordsForExplicitRetry() {
        val root = fixtureRoot("ambiguous-export")
        val id = UUID.randomUUID().toString()
        val name = "Timelapse-$id.mp4"
        val uri = insertFixtureVideo(name, "Movies/IsolatedTimelapseFixture/", true)
        var fixtureUriPresent = true
        try {
            val files = File(root, "private")
            val cache = File(root, "cache")
            val exporter = VideoExporter(context, files, cache)
            val record = File(files, "export-attempts/$id.json").apply {
                writeText(JSONObject().put("version", 1).put("id", id).put("uri", uri.toString()).toString())
            }
            val temporary = File(cache, "export-attempts/$id.mp4").apply { writeText("keep until ownership is known") }
            val before = inventory(root)
            try {
                exporter.reconcile()
                fail("Wrong path cannot prove ownership")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("ownership"))
            }
            assertEquals(before, inventory(root))
            assertEquals(uri, fixtureVideoNamed(name))
            // Remove ONLY our fixture row, then inject failure to remove its attributable cache directory.
            val deleted = context.contentResolver.delete(uri, null, null)
            fixtureUriPresent = false // Do not delete an already-removed URI again in finally.
            assertEquals("Delete only this fixture's allocated row", 1, deleted)
            assertNull(fixtureVideoNamed(name))
            temporary.delete()
            temporary.mkdirs()
            File(temporary, "obstruction").writeText("fixture cleanup failure")
            try {
                exporter.reconcile()
                fail("Failed cache cleanup must keep its durable record")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("cache"))
            }
            assertTrue(record.exists())
            temporary.deleteRecursively()
            exporter.reconcile()
            assertFalse(record.exists())
        } finally {
            if (fixtureUriPresent) context.contentResolver.delete(uri, null, null)
            root.deleteRecursively()
        }
    }

    @Test fun unattributedCacheAndMalformedJournalAreRetainedInsteadOfGuessedOrDeleted() {
        val root = fixtureRoot("unattributed-export")
        try {
            val files = File(root, "private")
            val cache = File(root, "cache")
            val exporter = VideoExporter(context, files, cache)
            val orphan = File(cache, "export-attempts/${UUID.randomUUID()}.mp4").apply { writeText("unattributed") }
            var before = inventory(root)
            try { exporter.reconcile(); fail("Missing ownership record must be reported") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("Unattributed")) }
            assertEquals(before, inventory(root))
            orphan.delete() // Remove only injected fixture, not an automatic recovery operation.
            val record = File(files, "export-attempts/${UUID.randomUUID()}.json").apply { writeText("{broken") }
            before = inventory(root)
            try { exporter.reconcile(); fail("Malformed recovery record must be retained") }
            catch (_: org.json.JSONException) { /* Preserve/report unknown metadata. */ }
            assertEquals(before, inventory(root))
            record.delete()
            exporter.reconcile()
        } finally { root.deleteRecursively() }
    }

    @Test fun ordinaryExportFailureRetainsPhotosAndAllowsRetry() {
        val root = fixtureRoot("ordinary-export-failure")
        var retry: Uri? = null
        try {
            val store = SessionStore(root)
            val session = store.create(1_000, 24)
            val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
            try { store.saveFrame(session, bitmap, 1_000) } finally { bitmap.recycle() }
            val before = inventory(session.directory)
            val files = File(root, "private")
            val cache = File(root, "cache")
            val exporter = VideoExporter(context, files, cache) { if (it == "copied") throw java.io.IOException("Fixture publication failure") }
            try {
                exporter.export(session, store.frames(session)) {}
                fail("Injected failure must be reported")
            } catch (expected: java.io.IOException) {
                assertTrue(expected.message.orEmpty().contains("Fixture"))
            }
            assertEquals(before, inventory(session.directory))
            assertTrue(checkNotNull(File(files, "export-attempts").listFiles()).isEmpty())
            retry = VideoExporter(context, files, cache).export(session, store.frames(session)) {}
            assertEquals(before, inventory(session.directory))
        } finally {
            retry?.let { context.contentResolver.delete(it, null, null) }
            root.deleteRecursively()
        }
    }

    /** Collection lookup first: Android may reject a second delete of an already-removed URI.
     * Names are UUID allocations recorded by this fixture, never a prefix or discovered user row.
     */
    private fun deleteFixtureVideoIfPresent(uri: Uri, name: String) {
        val found = fixtureVideoNamed(name) ?: return
        check(found == uri) { "Fixture identity changed; retain rather than delete another row" }
        assertEquals(1, context.contentResolver.delete(uri,
            "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ? AND " +
                "${MediaStore.Video.Media.RELATIVE_PATH} = ?",
            arrayOf(name, context.packageName, "Movies/TimestampTimelapse/")))
    }

    private fun insertFixtureVideo(name: String, path: String, pending: Boolean): Uri =
        checkNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, path)
            put(MediaStore.Video.Media.IS_PENDING, if (pending) 1 else 0)
        }))

    private fun fixtureVideoNamed(name: String): Uri? {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.DISPLAY_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return checkNotNull(context.contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID), query, null)).use {
            if (it.moveToFirst()) android.content.ContentUris.withAppendedId(collection, it.getLong(0)) else null
        }
    }

    private fun videoName(uri: Uri): String? = context.contentResolver.query(uri,
        arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    @Test fun failedAtomicPhotoSaveKeepsCompletedFramesAndPermitsRetry() {
        val root = fixtureRoot("save-failure")
        val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val store = SessionStore(root)
            val session = store.create(1_000, 15, SessionLens.FRONT)
            store.saveFrame(session, bitmap, 1_000)
            val before = inventory(session.directory)
            val obstruction = File(session.directory, "frame_00000001_2000.jpg.tmp").apply { mkdirs() }
            try {
                store.saveFrame(session, bitmap, 2_000)
                fail("Injected save failure must be visible")
            } catch (expected: java.io.IOException) {
                assertEquals(before, inventory(session.directory))
            }
            obstruction.delete()
            assertEquals(2, store.saveFrame(session, bitmap, 2_000))
        } finally { bitmap.recycle(); root.deleteRecursively() }
    }

    @Test fun mp4HasCorrectFrameCountTimestampsAndUprightPixels() {
        val root = File(context.cacheDir, "video-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val store = SessionStore(root)
            val session = store.create(5_000, 30)
            val bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            try {
                canvas.drawColor(Color.BLUE)
                paint.color = Color.RED
                canvas.drawRect(0f, 0f, VIDEO_WIDTH.toFloat(), VIDEO_HEIGHT / 2f, paint)
                TimestampRenderer.drawClock(canvas, "14:32:08")
                repeat(6) { store.saveFrame(session, bitmap, 1_000L + it * 5_000) }
            } finally { bitmap.recycle() }
            val output = File(root, "test.mp4")
            var progress = 0
            VideoExporter(context).encode(output, store.frames(session), session.fps) { progress = it }
            assertEquals(100, progress)
            assertTrue(output.length() > 0)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertEquals("video/avc", format.getString("mime"))
                assertEquals(VIDEO_WIDTH, format.getInteger("width"))
                assertEquals(VIDEO_HEIGHT, format.getInteger("height"))
                extractor.selectTrack(0)
                val timestamps = mutableListOf<Long>()
                while (extractor.sampleTime >= 0) {
                    timestamps += extractor.sampleTime
                    if (!extractor.advance()) break
                }
                assertEquals(6, timestamps.size)
                timestamps.forEachIndexed { index, pts ->
                    assertTrue("Unexpected frame time $pts", kotlin.math.abs(pts - CaptureTiming.presentationTimeUs(index, 30)) <= 1)
                }
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.path)
                val decoded = checkNotNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                try {
                    val top = decoded.getPixel(decoded.width / 2, decoded.height / 4)
                    val bottom = decoded.getPixel(decoded.width / 2, decoded.height * 3 / 4)
                    assertTrue("Video must not be vertically flipped", Color.red(top) > 180 && Color.blue(top) < 80)
                    assertTrue("Video must not be vertically flipped", Color.blue(bottom) > 180 && Color.red(bottom) < 80)
                } finally { decoded.recycle() }
            } finally { retriever.release() }
        } finally { root.deleteRecursively() }
    }
}
