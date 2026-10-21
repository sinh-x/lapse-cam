package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class SessionLifecycleTest {
    @Test fun splitIsExplicitStrictAndAdaptiveOnly() {
        assertEquals(SessionSplit.THREE_HOURS, SessionSplit.DEFAULT)
        SessionSplit.entries.forEach { assertEquals(it, SessionSplit.parse(it.storedValue)) }
        listOf("", "3", "3H", " 3h", "1h").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { SessionSplit.parse(bad) }
        }
        assertEquals(SessionSplit.OFF, SessionSplit.forConfiguration(SamplingConfiguration.Fixed(1), SessionSplit.DEFAULT))
        assertEquals(SessionSplit.DEFAULT, SessionSplit.forConfiguration(SamplingConfiguration.Adaptive(), SessionSplit.DEFAULT))
    }

    @Test fun exactBoundariesAndIndependentOneSecondDetection() {
        listOf(SessionSplit.TWO_HOURS, SessionSplit.THREE_HOURS).forEach { split ->
            val limit = split.limitMillis!!
            assertTrue(split.allowsShutter(limit - 1))
            assertFalse(split.allowsShutter(limit))
            assertFalse(split.allowsShutter(limit + 1))
            for (phase in 0L..999L) {
                val firstTick = generateSequence(limit - 1_000 + phase) { it + 1_000 }
                    .first { !split.allowsShutter(it) }
                assertTrue(firstTick - limit in 0..999)
            }
        }
        assertTrue(SessionSplit.OFF.allowsShutter(Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { SessionSplit.DEFAULT.allowsShutter(-1) }
    }

    @Test fun closeDrainsOldFrameOnceAndFinishSuppressesSuccessor() {
        val gate = SessionCloseGate()
        gate.request(SessionCloseGate.Reason.ROLLOVER)
        assertNull(gate.drain(true))
        gate.request(SessionCloseGate.Reason.FINISH)
        assertEquals(SessionCloseGate.Reason.FINISH, gate.drain(false))
        repeat(3) { gate.request(SessionCloseGate.Reason.ROLLOVER); assertNull(gate.drain(false)) }
    }

    @Test fun freshSuccessorsHaveIndependentFrozenBudgetsAndEndpoints() {
        for (fps in listOf(15, 24, 30)) for (cap in listOf(5, 30, 600)) {
            val config = SamplingConfiguration.Adaptive(cap, fps)
            repeat(3) {
                val selector = AdaptiveSampling(config)
                assertEquals(0L, selector.thinningStage)
                assertEquals(500L, selector.intervalMillis)
                assertEquals(0, selector.selectedCount)
                repeat(config.frameBudget + 10) { id -> selector.onCommitted(CommittedFrame(id.toLong(), id * 500L)) }
                assertTrue(selector.selectedCount <= config.frameBudget)
                assertEquals(0L, selector.selectedFrames().first().id)
                assertEquals(config.frameBudget + 9L, selector.selectedFrames().last().id)
            }
        }
    }

    @Test fun retriesReserveBeforeEffectsAndNeverExceedThree() {
        var cycle = AttemptCycle()
        for (attempt in 1..3) {
            assertTrue(cycle.due)
            cycle = cycle.reserve()
            assertEquals(attempt, cycle.attempts)
            assertFalse(cycle.due)
            assertThrows(IllegalStateException::class.java) { cycle.reserve() }
            // Reconstructed reserved state consumes the slot, it does not reset it.
            cycle = AttemptCycle(cycle.attempts, cycle.remainingMillis, cycle.reserved).failed()
            val delay = when (attempt) { 1 -> 10_000L; 2 -> 30_000L; else -> 0L }
            assertEquals(delay, cycle.remainingMillis)
            if (delay > 0) {
                cycle = cycle.elapse(delay - 1)
                assertFalse(cycle.due)
                cycle = cycle.elapse(1)
            }
        }
        assertTrue(cycle.exhausted)
        assertFalse(cycle.elapse(Long.MAX_VALUE).due)
        assertEquals(AttemptCycle(), cycle.manualRetry())
    }

    @Test fun fairQueueSkipsLargeDelayedBacklogWithoutSnapshotHistory() {
        val records = (0 until 100_000).map { it.toString() }
        assertEquals("99999", SessionQueuePolicy.nextDue(records, null, { it }) { it == "99999" })
        assertEquals("0", SessionQueuePolicy.nextDue(records, "99999", { it }) { true })
        assertEquals("101", SessionQueuePolicy.nextDue(records, "100", { it }) { true })
        assertNull(SessionQueuePolicy.nextDue(records, "0", { it }) { false })
        assertNull(SessionQueuePolicy.nextDue(emptyList<String>(), null, { it }) { true })
        assertEquals("0", SessionQueuePolicy.nextDue(records, "missing", { it }) { true })
    }

    @Test fun onlyCanonicalReservedIntentNamesHaveRecoveryAuthority() {
        assertTrue(SessionIntentPolicy.isReservedSidecar("intent_0.json", 1))
        assertTrue(SessionIntentPolicy.isReservedSidecar("intent_0.json.bak", 1))
        assertTrue(SessionIntentPolicy.isReservedSidecar("intent_${Long.MAX_VALUE - 1}.json", Long.MAX_VALUE))
        assertFalse(SessionIntentPolicy.isReservedSidecar("intent_0.json", 0))
        listOf("intent_1.json", "intent_999999.json", "intent_${Long.MAX_VALUE}.json",
            "intent_9223372036854775808.json", "intent_000.json", "intent_00.json.bak", "intent_+0.json",
            "intent_-1.json", "intent_ 0.json", "intent_0.json.new", "intent_0.json.bak.bak", "other_0.json")
            .forEach { assertFalse(it, SessionIntentPolicy.isReservedSidecar(it, 1)) }
        assertFalse(SessionIntentPolicy.isReservedSidecar("intent_${Long.MAX_VALUE}.json", Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { SessionIntentPolicy.isReservedSidecar("intent_0.json", -1) }
    }

    @Test fun downtimeAndCleanupCyclesDoNotResetExportAttempts() {
        val export = AttemptCycle().reserve().failed()
        assertEquals(export, export.elapse(0))
        val cleanup = AttemptCycle().reserve().failed()
        assertEquals(1, cleanup.attempts)
        assertEquals(1, export.attempts)
        assertThrows(IllegalStateException::class.java) { export.manualRetry() }
        assertThrows(IllegalArgumentException::class.java) { AttemptCycle(4) }
        assertThrows(IllegalArgumentException::class.java) { AttemptCycle(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { AttemptCycle(1, 1, true) }
        assertThrows(IllegalArgumentException::class.java) { export.elapse(-1) }
    }
}
