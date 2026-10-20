package com.sinh.timelapse

import java.math.BigInteger
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test fun `Adaptive cadence doubles by stage and saturates without overflow`() {
        val expected = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
        expected.forEachIndexed { stage, interval ->
            assertEquals(interval, CaptureTiming.adaptiveIntervalMillis(stage.toLong()))
            if (stage > 0) assertEquals(interval, CaptureTiming.doubledAdaptiveIntervalMillis(expected[stage - 1]))
        }
        for (stage in listOf(63L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE)) {
            assertEquals(60_000L, CaptureTiming.adaptiveIntervalMillis(stage))
        }
        for (interval in listOf(30_000L, 60_000L, Long.MAX_VALUE / 2, Long.MAX_VALUE)) {
            assertEquals(60_000L, CaptureTiming.doubledAdaptiveIntervalMillis(interval))
        }
        assertEquals(59_998L, CaptureTiming.doubledAdaptiveIntervalMillis(29_999))
        assertThrows(IllegalArgumentException::class.java) { CaptureTiming.adaptiveIntervalMillis(-1) }
        assertThrows(IllegalArgumentException::class.java) { CaptureTiming.doubledAdaptiveIntervalMillis(0) }
        assertThrows(IllegalArgumentException::class.java) { CaptureTiming.doubledAdaptiveIntervalMillis(Long.MIN_VALUE) }
    }

    @Test fun `Adaptive intervals preserve anchor skip slow slots and explicitly reanchor on resume`() {
        val anchor = 10_000L
        for (stage in 0L..10L) {
            val interval = CaptureTiming.adaptiveIntervalMillis(stage)
            assertEquals(interval - 123, CaptureTiming.nextDelayMillis(anchor, anchor + 123, interval))
            assertEquals(interval - 123, CaptureTiming.nextDelayMillis(anchor, anchor + interval * 25 + 123, interval))
            assertEquals(interval, CaptureTiming.nextDelayMillis(anchor, anchor + interval * 25, interval))
            // Explicit resume starts a new segment; it does not inherit time spent paused.
            val resumeAnchor = anchor + interval * 25 + 123
            assertEquals(interval, CaptureTiming.nextDelayMillis(resumeAnchor, resumeAnchor, interval))
            assertEquals(interval - 7, CaptureTiming.nextDelayMillis(resumeAnchor, resumeAnchor + 7, interval))
        }
    }

    @Test fun `thinning changes interval without changing active segment anchor`() {
        val sampling = AdaptiveSampling()
        val anchor = 10_000L
        repeat(900) { sampling.onCommitted(CommittedFrame(it.toLong(), it * 500L)) }
        assertEquals(377L, CaptureTiming.nextDelayMillis(anchor, 460_123, sampling.intervalMillis))
        sampling.onCommitted(CommittedFrame(900, 450_123))
        assertEquals(1_000L, sampling.intervalMillis)
        assertEquals(877L, CaptureTiming.nextDelayMillis(anchor, 460_123, sampling.intervalMillis))
        assertEquals(1_000L, CaptureTiming.nextDelayMillis(460_123, 460_123, sampling.intervalMillis))
    }

    @Test fun `checked budget rejects Long extremes rather than wrapping into a valid cap`() {
        for (seconds in listOf(Long.MIN_VALUE, -1L, 0L, 4L, 601L, Long.MAX_VALUE / 30, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { CaptureTiming.adaptiveFrameBudget(seconds, 30) }
        }
        for (fps in listOf(Long.MIN_VALUE, 0L, 14L, 31L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { CaptureTiming.adaptiveFrameBudget(30, fps) }
        }
        for (seconds in listOf(5L, 30L, 600L)) for (fps in listOf(15L, 24L, 30L)) {
            assertEquals((seconds * fps).toInt(), CaptureTiming.adaptiveFrameBudget(seconds, fps))
        }
    }

    @Test fun `gap bound comparison matches arbitrary precision at overflow boundaries`() {
        val gaps = listOf(0L, 1L, 2L, 3L, 500L, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE)
        for (selected in gaps) for (completed in gaps) for (stage in listOf(0L, 1L, 2L, 7L, 62L, 63L, 64L, Long.MAX_VALUE)) {
            // Beyond 63, every positive Long gap fits if completed gap is positive.
            val bound = BigInteger.valueOf(completed).shiftLeft(minOf(stage, 64L).toInt())
            assertEquals("selected=$selected completed=$completed stage=$stage",
                BigInteger.valueOf(selected) <= bound,
                CaptureTiming.isWithinAdaptiveGapBound(selected, completed, stage))
        }
        assertThrows(IllegalArgumentException::class.java) { CaptureTiming.isWithinAdaptiveGapBound(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { CaptureTiming.isWithinAdaptiveGapBound(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { CaptureTiming.isWithinAdaptiveGapBound(0, 0, -1) }
    }

    @Test fun `PTS remains exact for all rates through maximum frame index`() {
        for (fps in listOf(15, 24, 30)) for (index in listOf(0, 1, 2, 74, 75, 899, 900, 17_999, 18_000, Int.MAX_VALUE)) {
            val expected = BigInteger.valueOf(index.toLong()).multiply(BigInteger.valueOf(1_000_000L))
                .divide(BigInteger.valueOf(fps.toLong())).longValueExact()
            assertEquals(expected, CaptureTiming.presentationTimeUs(index, fps))
        }
    }
}
