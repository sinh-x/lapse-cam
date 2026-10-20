package com.sinh.timelapse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimestampAppearanceTest {
    @Test fun all216AppearancesFitSafeMarginsWithFullTextAndPadding() {
        var count = 0
        for (family in StampFamily.entries) for (bold in listOf(false, true)) for (size in listOf(32, 44, 60))
            for (position in StampPosition.entries) for (date in listOf(false, true)) {
                val style = TimestampAppearance(family, bold, size, position, date)
                val layout = TimestampLayout.layout(1_729_944_599_000, "UTC", style)
                assertTrue(layout.box.left >= 28f && layout.box.top >= 28f)
                assertTrue(layout.box.right <= 692f && layout.box.bottom <= 1252f)
                assertEquals(if (date) 2 else 1, layout.lines.size)
                layout.lines.forEachIndexed { index, text ->
                    assertTrue(layout.textPaint.measureText(text) <= layout.box.width() - 32f + 0.001f)
                    assertTrue(layout.baselines[index] + layout.textPaint.fontMetrics.ascent >= layout.box.top + 16f - 0.001f)
                    assertTrue(layout.baselines[index] + layout.textPaint.fontMetrics.descent <= layout.box.bottom - 16f + 0.001f)
                }
                assertEquals(size.toFloat(), layout.textPaint.textSize, 0f)
                count++
            }
        assertEquals(216, count)
    }

    @Test fun defaultSharedOutputIsPixelIdenticalToLegacyClockGeometry() {
        val legacy = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        val shared = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            TimestampRenderer.drawClock(Canvas(legacy), "14:32:08")
            TimestampLayout.draw(Canvas(shared), 52_328_000, "UTC", TimestampAppearance())
            assertTrue("Legacy stamp geometry and pixels must stay unchanged", legacy.sameAs(shared))
        } finally { legacy.recycle(); shared.recycle() }
    }

    @Test fun dateUsesFrozenZoneAcrossMidnightDstAndSystemZoneChanges() {
        val style = TimestampAppearance(date = true)
        assertEquals(listOf("1970-01-01", "00:00:00"), TimestampLayout.lines(0, "UTC", style))
        assertEquals(listOf("1969-12-31", "16:00:00"), TimestampLayout.lines(0, "America/Los_Angeles", style))
        assertEquals(listOf("1970-01-01", "23:59:59"), TimestampLayout.lines(86_399_000, "UTC", style))
        assertEquals(listOf("1970-01-02", "00:00:00"), TimestampLayout.lines(86_400_000, "UTC", style))
        assertEquals(listOf("2024-03-10", "01:59:59"), TimestampLayout.lines(
            Instant.parse("2024-03-10T06:59:59Z").toEpochMilli(), "America/New_York", style))
        assertEquals(listOf("2024-03-10", "03:00:00"), TimestampLayout.lines(
            Instant.parse("2024-03-10T07:00:00Z").toEpochMilli(), "America/New_York", style))
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(listOf("1970-01-01", "00:00:00"), TimestampLayout.lines(0, "UTC", style))
            assertEquals(listOf("00:00:00"), TimestampLayout.lines(0, "UTC", style.copy(date = false)))
        } finally { java.util.TimeZone.setDefault(previous) }
    }

    @Test fun previewUsesIdenticalOutputPixelsAndInverseScaledGeometryAtMultipleSizes() {
        val expected = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        val style = TimestampAppearance(StampFamily.SANS_SERIF, false, 60, StampPosition.TOP_CENTER, true)
        try {
            val layout = TimestampLayout.draw(Canvas(expected), 0, "UTC", style)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val preview = TimestampPreview(ApplicationProvider.getApplicationContext()).apply {
                    appearance = style; epochMillis = 0; zone = "UTC"
                    layout(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT)
                }
                preview.draw(Canvas(actual))
            }
            assertTrue(expected.sameAs(actual))
            for ((width, height) in listOf(180 to 320, 360 to 640, 720 to 1280, 1080 to 1920)) {
                val transform = Matrix().apply { setScale(width / 720f, height / 1280f) }
                val inverse = Matrix(); assertTrue(transform.invert(inverse))
                val box = RectF(layout.box)
                transform.mapRect(box); inverse.mapRect(box)
                assertEquals(layout.box.left, box.left, 1f)
                assertEquals(layout.box.top, box.top, 1f)
                assertEquals(layout.box.right, box.right, 1f)
                assertEquals(layout.box.bottom, box.bottom, 1f)
            }
            assertEquals(Color.WHITE, layout.textPaint.color)
        } finally { expected.recycle(); actual.recycle() }
    }
}
