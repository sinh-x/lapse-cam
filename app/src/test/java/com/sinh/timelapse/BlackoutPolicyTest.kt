package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class BlackoutPolicyTest {
    private class Fixture {
        var time = 0L
        val policy = BlackoutPolicy { time }
        val live = BlackoutPolicy.Input(live = true, cameraReady = true, foreground = true, focused = true)
        init { policy.update(live) }
        fun fire() { policy.timer?.let(policy::timeout) }
        fun cover() { time += BlackoutPolicy.IDLE_MILLIS; fire(); assertTrue(policy.covered) }
    }

    @Test fun exactMonotonicBoundaryAndRoutineEmissionsDoNotDeferIdle() {
        val f = Fixture(); val original = f.policy.timer
        for (t in 1L..29_999L step 250) {
            f.time = t
            // Clock/photo/export/sync changes do not change display eligibility.
            f.policy.update(f.live.copy())
            f.fire(); assertFalse(f.policy.covered); assertEquals(original, f.policy.timer)
        }
        f.time = 29_999; f.fire(); assertFalse(f.policy.covered)
        f.time = 30_000; f.fire(); assertTrue(f.policy.covered); assertNull(f.policy.timer)
        f.policy.update(f.live); assertTrue(f.policy.covered)
    }

    @Test fun genuineInteractionAndReturnRestartFullIntervalAndRejectOldTimer() {
        val f = Fixture(); val old = f.policy.timer!!
        f.time = 20_000; f.policy.interact()
        f.time = 30_000; f.policy.timeout(old); assertFalse(f.policy.covered)
        f.time = 49_999; f.fire(); assertFalse(f.policy.covered)
        f.time = 50_000; f.fire(); assertTrue(f.policy.covered)
        f.policy.interact(); assertFalse(f.policy.covered)
        assertEquals(80_000L, f.policy.timer!!.deadline)
    }

    @Test fun everyIneligibilityClearsImmediatelyAndRestorationWaitsFullDelay() {
        val values = Fixture().live.let { live -> listOf(
            live.copy(enabled = false), live.copy(live = false), live.copy(cameraReady = false),
            live.copy(foreground = false), live.copy(focused = false), live.copy(modal = true),
            live.copy(actionableError = true), live.copy(interacting = true),
        ) }
        for (value in values) {
            val f = Fixture(); val stale = f.policy.timer!!; f.cover()
            f.policy.update(value); assertFalse(f.policy.covered); assertNull(f.policy.timer)
            f.time += 100_000; f.policy.timeout(stale); assertFalse(f.policy.covered)
            f.policy.update(f.live); val due = f.time + 30_000
            assertEquals(due, f.policy.timer!!.deadline)
            f.time = due - 1; f.fire(); assertFalse(f.policy.covered)
            f.time = due; f.fire(); assertTrue(f.policy.covered)
        }
    }

    @Test fun heldInputNeverGetsCoveredAndFinalReleaseStartsFreshDelay() {
        val f = Fixture(); val stale = f.policy.timer!!
        f.time = 29_999; f.policy.update(f.live.copy(interacting = true))
        f.time = 90_000; f.policy.timeout(stale); assertFalse(f.policy.covered)
        f.policy.interact(); assertNull(f.policy.timer)
        f.policy.update(f.live); assertEquals(120_000L, f.policy.timer!!.deadline)
    }

    @Test fun nestedModalsAndPickerNeedBothFinalCloseAndFocus() {
        val f = Fixture(); f.cover()
        f.policy.update(f.live.copy(modal = true, focused = false))
        f.time += 30_000
        f.policy.update(f.live.copy(modal = true, focused = true)); assertNull(f.policy.timer)
        f.policy.update(f.live.copy(modal = false, focused = false)); assertNull(f.policy.timer)
        f.policy.update(f.live); assertEquals(f.time + 30_000, f.policy.timer!!.deadline)
    }

    @Test fun rolloverGapAndPauseResumeHaveNoStaleLive() {
        val f = Fixture(); f.cover()
        f.policy.update(f.live.copy(live = false))
        assertFalse(f.policy.covered)
        f.time += 5_000; f.policy.update(f.live)
        assertEquals(f.time + 30_000, f.policy.timer!!.deadline)
        f.policy.update(f.live.copy(cameraReady = false)); assertNull(f.policy.timer)
    }

    @Test fun terminalNoticeRevealsOnceButOrdinaryWorkTicksDoNotReset() {
        val f = Fixture()
        f.policy.observeTerminals(setOf("old:ended")); val original = f.policy.timer
        f.time = 10_000; f.policy.observeTerminals(setOf("old:ended")); assertEquals(original, f.policy.timer)
        f.cover()
        f.policy.observeTerminals(setOf("old:ended", "new:expiry:60000"))
        assertFalse(f.policy.covered); val restarted = f.policy.timer
        f.time += 1_000
        f.policy.observeTerminals(setOf("old:ended", "new:expiry:60000")); assertEquals(restarted, f.policy.timer)
        f.policy.observeTerminals(emptySet())
        f.policy.observeTerminals(setOf("new:expiry:60000")); assertEquals(restarted, f.policy.timer)
    }

    @Test fun stopDestroyRecreationAndQueuedCallbacksNeverRestoreOldOwner() {
        val f = Fixture(); val stale = f.policy.timer!!
        f.policy.update(f.live.copy(foreground = false))
        f.time = 60_000; f.policy.timeout(stale); assertFalse(f.policy.covered)
        f.policy.update(f.live); val next = f.policy.timer!!
        f.policy.close(); f.time = 120_000
        f.policy.timeout(next); f.policy.update(f.live); f.policy.interact()
        assertFalse(f.policy.covered); assertNull(f.policy.timer)
        val replacement = BlackoutPolicy { f.time }
        assertFalse(replacement.covered); assertNull(replacement.timer)
        replacement.update(f.live.copy(live = false)); assertNull(replacement.timer)
        replacement.update(f.live); assertEquals(150_000L, replacement.timer!!.deadline)
    }
}
