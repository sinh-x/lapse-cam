package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class SessionContractTest {
    @Test fun activeTimeExcludesPauseAndRecoveredDowntime() {
        var now = 0L
        val clock = ActiveRecordingClock { now }
        clock.start()
        now += 600_000
        assertEquals(600_000L, clock.stop())
        now += 300_000
        assertEquals(600_000L, clock.value())
        clock.start()
        now += 600_000
        assertEquals(1_200_000L, clock.stop())
        val recovered = ActiveRecordingClock { now }
        recovered.restore(clock.value())
        now += 7 * 86_400_000L
        assertEquals(1_200_000L, recovered.value())
        recovered.start()
        now += 1000
        assertEquals(1_201_000L, recovered.value())
    }

    @Test fun startAndStopAreIdempotentAndKnownCheckpointDoesNotGuessTail() {
        var now = 50L
        val clock = ActiveRecordingClock { now }
        clock.restore(2000)
        clock.start()
        now += 1000
        clock.start()
        assertEquals(3000L, clock.stop())
        now += 1000
        assertEquals(3000L, clock.stop())
        clock.restore(2500)
        assertEquals(2500L, clock.value())
    }

    @Test fun activeArithmeticNeverWraps() {
        var now = 0L
        val clock = ActiveRecordingClock { now }
        assertThrows(IllegalArgumentException::class.java) { clock.restore(-1) }
        clock.restore(Long.MAX_VALUE)
        clock.start()
        now = 1
        assertThrows(ArithmeticException::class.java) { clock.value() }
    }

    @Test fun exportHeadroomUsesExactCeilingAndTwoOutputCopies() {
        for (fps in listOf(15, 24, 30)) for (count in listOf(0L, 1L, 2L, 75L, 900L, 18_000L)) {
            val expected = ExportSpace.CAPTURE_MIN_BYTES + 2 * kotlin.math.ceil(781_250.0 * count / fps).toLong()
            val threshold = ExportSpace.requiredBytes(count, fps)
            assertEquals(expected, threshold)
            assertFalse(ExportSpace.hasHeadroom(threshold - 1, count, fps))
            assertTrue(ExportSpace.hasHeadroom(threshold, count, fps))
        }
        assertEquals(ExportSpace.CAPTURE_MIN_BYTES + 46_875_000, ExportSpace.requiredBytes(900, 30))
    }

    @Test fun exportHeadroomRejectsInvalidAndOverflowingInput() {
        assertThrows(IllegalArgumentException::class.java) { ExportSpace.requiredBytes(-1, 30) }
        assertThrows(IllegalArgumentException::class.java) { ExportSpace.requiredBytes(1, 0) }
        assertThrows(ArithmeticException::class.java) { ExportSpace.requiredBytes(Long.MAX_VALUE, 30) }
    }

    @Test fun appearanceDefaultsAndAll216ConfigurationsAreValid() {
        assertEquals(TimestampAppearance(StampFamily.MONOSPACE, true, 44, StampPosition.BOTTOM_LEFT, false), TimestampAppearance())
        val configurations = mutableSetOf<TimestampAppearance>()
        for (family in StampFamily.entries) for (bold in listOf(false, true)) for (size in listOf(32, 44, 60))
            for (position in StampPosition.entries) for (date in listOf(false, true)) {
                configurations += TimestampAppearance(family, bold, size, position, date)
            }
        assertEquals(216, configurations.size)
        for (size in listOf(-1, 0, 31, 45, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { TimestampAppearance(size = size) }
    }
}
