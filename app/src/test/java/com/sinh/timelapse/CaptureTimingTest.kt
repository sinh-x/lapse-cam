package com.sinh.timelapse

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTimingTest {
    private fun epoch(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun `clock uses 24 hour hours minutes and seconds`() {
        assertEquals("14:32:08", CaptureTiming.timestamp(epoch("2026-01-01T14:32:08Z"), "UTC"))
        assertEquals("00:00:00", CaptureTiming.timestamp(epoch("2026-01-01T00:00:00Z"), "UTC"))
        assertEquals("23:59:59", CaptureTiming.timestamp(epoch("2026-01-01T23:59:59Z"), "UTC"))
    }

    @Test fun `clock uses the session time zone across midnight`() {
        assertEquals("00:05:09", CaptureTiming.timestamp(epoch("2026-01-01T17:05:09Z"), "Asia/Ho_Chi_Minh"))
    }

    @Test fun `clock respects daylight saving transitions`() {
        assertEquals("01:59:59", CaptureTiming.timestamp(epoch("2026-03-08T06:59:59Z"), "America/New_York"))
        assertEquals("03:00:00", CaptureTiming.timestamp(epoch("2026-03-08T07:00:00Z"), "America/New_York"))
    }

    @Test fun `processing time does not add cadence drift`() {
        assertEquals(4_300L, CaptureTiming.nextDelayMillis(10_000, 10_700, 5_000))
        assertEquals(4_300L, CaptureTiming.nextDelayMillis(10_000, 15_700, 5_000))
    }

    @Test fun `slow photos skip missed slots without bursting`() {
        assertEquals(3_800L, CaptureTiming.nextDelayMillis(10_000, 26_200, 5_000))
        assertEquals(5_000L, CaptureTiming.nextDelayMillis(10_000, 25_000, 5_000))
    }

    @Test fun `monotonic timing is robust to an earlier now value`() {
        assertEquals(5_000L, CaptureTiming.nextDelayMillis(10_000, 9_000, 5_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero interval is rejected`() {
        CaptureTiming.nextDelayMillis(0, 0, 0)
    }

    @Test fun `video timeline advances at export fps not capture interval`() {
        assertEquals(0L, CaptureTiming.presentationTimeUs(0, 30))
        assertEquals(33_333L, CaptureTiming.presentationTimeUs(1, 30))
        assertEquals(1_000_000L, CaptureTiming.presentationTimeUs(30, 30))
        assertEquals(3_600_000_000L, CaptureTiming.presentationTimeUs(108_000, 30))
    }

    @Test fun `video timestamps are strictly increasing for supported frame rates`() {
        for (fps in listOf(15, 24, 30)) {
            val times = (0..1_000).map { CaptureTiming.presentationTimeUs(it, fps) }
            assertTrue(times.zipWithNext().all { (a, b) -> b > a })
        }
    }

    @Test fun `all intervals and frame rates preserve cadence and exact timeline`() {
        for (seconds in listOf(1, 2, 5, 10, 30, 60)) for (fps in listOf(15, 24, 30)) {
            val interval = seconds * 1_000L
            assertEquals(interval - 123, CaptureTiming.nextDelayMillis(10_000, 10_123 + interval * 4, interval))
            assertEquals(interval, CaptureTiming.nextDelayMillis(10_000, 10_000 + interval * 7, interval))
            repeat(100) { index ->
                assertEquals(index * 1_000_000L / fps, CaptureTiming.presentationTimeUs(index, fps))
            }
        }
    }

    @Test fun `lens policy blocks mismatches recovery and background without fallback`() {
        for (lens in SessionLens.entries) {
            assertEquals(lens, SessionLens.parse(lens.storedValue))
            assertTrue(CapturePolicy.canCapture(lens, lens, true, false))
            assertTrue(!CapturePolicy.canCapture(lens, null, true, false))
            assertTrue(!CapturePolicy.canCapture(lens, SessionLens.entries.first { it != lens }, true, false))
            assertTrue(!CapturePolicy.canCapture(lens, lens, false, false))
            assertTrue(!CapturePolicy.canCapture(lens, lens, true, true))
        }
        assertTrue(CapturePolicy.canSelectLens(false, false, false))
        assertTrue(!CapturePolicy.canSelectLens(true, false, false))
        assertTrue(!CapturePolicy.canSelectLens(false, true, false))
        assertTrue(!CapturePolicy.canSelectLens(false, false, true))
    }

    @Test fun `delayed camera provider callbacks cannot attach stale or unloaded lenses`() {
        assertTrue(CapturePolicy.isCurrentBinding(2, 2, SessionLens.FRONT, SessionLens.FRONT, true, true))
        assertTrue(!CapturePolicy.isCurrentBinding(1, 2, SessionLens.REAR, SessionLens.REAR, true, true))
        assertTrue(!CapturePolicy.isCurrentBinding(2, 2, SessionLens.REAR, SessionLens.FRONT, true, true))
        assertTrue(!CapturePolicy.isCurrentBinding(2, 2, SessionLens.FRONT, SessionLens.FRONT, false, true))
        assertTrue(!CapturePolicy.isCurrentBinding(2, 2, SessionLens.FRONT, SessionLens.FRONT, true, false))
    }

    @Test fun `storage cutoff includes exactly fifty MiB`() {
        assertTrue(!CapturePolicy.hasCaptureSpace(CapturePolicy.MIN_FREE_BYTES - 1))
        assertTrue(CapturePolicy.hasCaptureSpace(CapturePolicy.MIN_FREE_BYTES))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown lens is rejected`() { SessionLens.parse("sideways") }

    @Test(expected = IllegalArgumentException::class)
    fun `negative frame index is rejected`() { CaptureTiming.presentationTimeUs(-1, 30) }

    @Test(expected = IllegalArgumentException::class)
    fun `zero fps is rejected`() { CaptureTiming.presentationTimeUs(0, 0) }
}
