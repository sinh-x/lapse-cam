package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class TimeboxPolicyTest {
    private val a = WorkDraft(title = "A", minutes = "1").validated(1)
    private val b = WorkDraft(title = "B").validated(2)
    private val now = WorkMoment("boot:1", 1_000, 100_000)
    private fun running() = TimeboxPolicy.start(WorkSnapshot(records = listOf(a, b)), a.id, now)
    @Test fun deadlineBoundariesAndWallChanges() {
        val running = running()
        assertEquals(WorkState.RUNNING, TimeboxPolicy.reconcile(running, now.copy(elapsed = 60_999, epoch = 1)).running!!.state)
        for (elapsed in listOf(61_000L, 61_001L, 100_000L)) {
            val ended = TimeboxPolicy.reconcile(running, now.copy(elapsed = elapsed)).records.first()
            assertEquals(WorkEnd.EXPIRY, ended.reason); assertEquals(61_000L, ended.endElapsed)
            assertEquals(1, ended.plannedMinutes)
        }
        assertEquals(60_000L, running.running!!.remaining(now.copy(epoch = Long.MAX_VALUE)))
    }
    @Test fun oneRunningReplacementIsExplicitAtomicAndTerminal() {
        val old = running()
        assertEquals(old, TimeboxPolicy.start(old, a.id, now))
        assertThrows(IllegalArgumentException::class.java) { TimeboxPolicy.start(old, b.id, now) }
        val switched = TimeboxPolicy.start(old, b.id, now.copy(elapsed = 2000), a.id)
        assertEquals(b.id, switched.running!!.id); assertEquals(WorkEnd.USER, switched.records.first().reason)
        assertEquals(switched, TimeboxPolicy.end(switched, a.id, now.copy(elapsed = 3000)))
        assertThrows(IllegalArgumentException::class.java) { TimeboxPolicy.start(switched, a.id, now.copy(elapsed = 4000)) }
        val ended = TimeboxPolicy.end(switched, b.id, now.copy(elapsed = 4000))
        assertEquals(ended, TimeboxPolicy.end(ended, b.id, now.copy(elapsed = 5000)))
    }
    @Test fun rebootUsesOnlyConfirmedLowerBoundAndCheckedArithmetic() {
        val old = running().let { it.replace(it.running!!.copy(confirmedElapsed = 500)) }
        val ended = TimeboxPolicy.reconcile(old, WorkMoment("boot:2", 10, 999_999)).records.first()
        assertEquals(WorkEnd.REBOOT, ended.reason); assertTrue(ended.uncertain); assertEquals(1500L, ended.endElapsed)
        assertThrows(ArithmeticException::class.java) { TimeboxPolicy.start(WorkSnapshot(records = listOf(a)), a.id, now.copy(elapsed = Long.MAX_VALUE)) }
    }
}
