package com.sinh.timelapse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
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
