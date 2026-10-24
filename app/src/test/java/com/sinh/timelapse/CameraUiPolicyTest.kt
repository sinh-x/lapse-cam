package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class CameraUiPolicyTest {
    @Test fun fullStateTruthTableHasModelDrivenLiveAndPauseDuringExport() {
        val flags = listOf(false, true)
        for (capturing in flags) for (session in flags) for (busy in flags) for (switching in flags)
            for (blocked in flags) for (ready in flags) for (split in flags) for (loading in flags) for (working in flags) {
                val result = CameraUiPolicy.project(CameraUiPolicy.Input(capturing, session, busy, switching, blocked, ready, split, loading, working))
                assertEquals(capturing, result.live)
                assertEquals(capturing || (!busy && !switching && !blocked && ready), result.enabled)
                assertEquals(if (capturing) "Pause capture" else if (session) "Resume capture" else "Start capture", result.primary)
                assertEquals(session && split, result.finish)
                assertEquals(session && split && !loading && !working && !switching && !blocked, result.finishEnabled)
            }
    }
    @Test fun contrastMeetsAaWithoutRelyingOnRedAlone() {
        fun luminance(r: Int, g: Int, b: Int): Double {
            fun linear(c: Int): Double { val s = c / 255.0; return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
        }
        val background = luminance(16, 25, 22)
        assertTrue(1.05 / (background + 0.05) >= 4.5)
        assertTrue(1.05 / (luminance(170, 0, 0) + 0.05) >= 4.5)
        assertTrue((luminance(255, 205, 205) + 0.05) / 0.05 >= 4.5)
    }
}
