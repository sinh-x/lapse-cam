package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class CaptureDisplayPolicyTest {
    @Test fun timeboxAndRecordingUseSeparateSnapshotsWithoutClockArithmetic() {
        val live = CaptureDisplayPolicy.blackout("Write proposal", "24:18", 1_122_000, false)
        assertEquals("24:18", live.remaining); assertEquals("00:18:42", live.recording)
        val paused = CaptureDisplayPolicy.blackout("Write proposal", "24:17", 1_122_000, false)
        assertEquals(live.recording, paused.recording); assertNotEquals(live.remaining, paused.remaining)
    }
    @Test fun noTimeboxAndUnknownEvidenceNeverInventCountdowns() {
        assertEquals("No timebox running", CaptureDisplayPolicy.blackout(null, null, 0, false).title)
        val unavailable = CaptureDisplayPolicy.blackout("Old task", "24:18", null, true, false)
        assertEquals("Timebox status unavailable", unavailable.title); assertEquals("", unavailable.remaining)
        assertTrue(unavailable.recording.contains("legacy")); assertTrue(unavailable.recording.contains("tail unknown"))
    }
    @Test fun formattingFloorsRecordingSecondsAndDoesNotWrapHours() {
        assertEquals("00:00:00", CaptureDisplayPolicy.duration(-1))
        assertEquals("00:00:01", CaptureDisplayPolicy.duration(1999))
        assertEquals("100:00:00", CaptureDisplayPolicy.duration(360_000_000))
    }
}
