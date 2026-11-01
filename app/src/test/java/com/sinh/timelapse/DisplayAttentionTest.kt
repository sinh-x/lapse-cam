package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class DisplayAttentionTest {
    @Test fun hydrationSeedsOnlySavedBacklogAndDoesNotSwallowNewStartupFailures() {
        val owner = DisplayAttention()
        // An empty loading placeholder is never passed to the repository baseline seam.
        val saved = SessionRecord("old", phase = SessionPhase.NEEDS_ATTENTION, error = "cleanup failure", cleanupOnly = true)
        SessionAttentionPolicy.observe(owner, listOf(saved), baseline = true)
        assertEquals(1, owner.snapshot.count)
        assertEquals(0, owner.deliver(true))
        owner.failure("export-recovery", "new failure during initialization")
        assertEquals(1, owner.deliver(true))
        assertEquals(2, owner.snapshot.count)
    }

    @Test fun repeatedAutomaticFailuresTextProgressAndPartialOmissionAreNotNewEpisodes() {
        val owner = DisplayAttention()
        val record = SessionRecord("old", phase = SessionPhase.QUEUED, error = "first")
        SessionAttentionPolicy.observe(owner, listOf(record))
        assertEquals(1, owner.deliver(true))
        repeat(100) {
            SessionAttentionPolicy.observe(owner, emptyList())
            SessionAttentionPolicy.observe(owner, listOf(record.copy(error = "detail $it", exportCycle = AttemptCycle(attempts = 2))))
            assertEquals(0, owner.deliver(true))
        }
        assertEquals(1, owner.snapshot.count)
        assertEquals("detail 99", owner.snapshot.details.values.single())
    }

    @Test fun retryOnlyErrorOnlyAndCategoryTransitionsHaveExplicitResolution() {
        val owner = DisplayAttention()
        val retryOnly = SessionRecord("retry", phase = SessionPhase.NEEDS_ATTENTION)
        val errorOnly = SessionRecord("error", phase = SessionPhase.QUEUED, error = "failure")
        SessionAttentionPolicy.observe(owner, listOf(retryOnly, errorOnly))
        assertEquals(2, owner.deliver(true))
        // Automatic transient clean-looking state does not prove success.
        SessionAttentionPolicy.observe(owner, listOf(errorOnly.copy(error = "")))
        assertEquals(2, owner.snapshot.count)
        SessionAttentionPolicy.observe(owner, listOf(errorOnly.copy(cleanupOnly = true, error = "cleanup")))
        assertEquals(2, owner.snapshot.count) // export replaced by cleanup; retry remains
        assertEquals(1, owner.deliver(true))
        SessionAttentionPolicy.observe(owner, listOf(errorOnly.copy(phase = SessionPhase.FINISHED)))
        assertEquals(1, owner.snapshot.count)
        owner.resolve(SessionAttentionPolicy.key(retryOnly)) // explicit new user retry cycle
        SessionAttentionPolicy.observe(owner, listOf(retryOnly))
        assertEquals(1, owner.deliver(true))
    }

    @Test fun workTicksDeduplicateButDifferentItemsAndResolvedRecurrenceNotify() {
        val owner = DisplayAttention()
        repeat(30) { owner.failure("store", "write failure $it") }
        assertEquals(1, owner.deliver(true))
        owner.failure("save:item-a", "same text")
        owner.failure("save:item-b", "same text")
        assertEquals(2, owner.deliver(true))
        owner.resolve("save:item-a")
        owner.failure("save:item-a", "same text")
        assertEquals(1, owner.deliver(true))
    }

    @Test fun pendingSurvivesBackgroundModalReattachmentAndEvenResolutionWithoutAcknowledgingData() {
        val owner = DisplayAttention()
        owner.failure("a", "failure")
        repeat(10) { assertEquals(0, owner.deliver(false)) }
        owner.resolve("a")
        assertEquals(1, owner.snapshot.pending)
        assertEquals(1, owner.deliver(true))
        assertEquals(0, owner.deliver(true)) // new Activity observes same owner
        owner.failure("b", "failure")
        assertEquals(1, owner.deliver(true))
        assertEquals(mapOf("b" to "failure"), owner.snapshot.details)
    }

    @Test fun historicalQueueCategoriesAreIndependentButCaptureOwnerErrorsAreConservative() {
        val owner = DisplayAttention()
        for (phase in listOf(SessionPhase.QUEUED, SessionPhase.EXPORTING, SessionPhase.VERIFYING,
            SessionPhase.CLEANUP, SessionPhase.NEEDS_ATTENTION, SessionPhase.DELETING)) {
            assertFalse(SessionAttentionPolicy.observe(owner, listOf(SessionRecord(phase.name, phase, error = "storage error"))))
        }
        for (phase in listOf(SessionPhase.ACTIVE, SessionPhase.PAUSED, SessionPhase.CLOSING)) {
            assertTrue(SessionAttentionPolicy.observe(owner, listOf(SessionRecord(phase.name, phase, error = "plain diagnostic"))))
        }
    }

    @Test fun deliveryAtTwentySecondsResetsDeadlineExactlyOnceAndCannotOverrideSafety() {
        var now = 0L
        val policy = BlackoutPolicy { now }
        val input = BlackoutPolicy.Input(live = true, cameraReady = true, foreground = true, focused = true)
        policy.update(input)
        val stale = policy.timer!!
        val owner = DisplayAttention()
        now = 20_000
        owner.failure("a", "failure")
        if (owner.deliver(true) > 0) policy.interact()
        assertEquals(50_000L, policy.timer!!.deadline)
        repeat(20) { owner.failure("a", "different text"); if (owner.deliver(true) > 0) policy.interact() }
        now = 49_999; policy.timeout(policy.timer!!); assertFalse(policy.covered)
        now = 50_000; policy.timeout(stale); assertFalse(policy.covered)
        policy.timeout(policy.timer!!); assertTrue(policy.covered)
        owner.failure("a", "again"); assertEquals(0, owner.deliver(true)); assertTrue(policy.covered)
        owner.failure("b", "new"); if (owner.deliver(true) > 0) policy.interact()
        assertFalse(policy.covered); assertEquals(80_000L, policy.timer!!.deadline)
        policy.update(input.copy(actionableError = true))
        owner.failure("c", "new"); if (owner.deliver(true) > 0) policy.interact()
        assertNull(policy.timer); assertFalse(policy.covered)
    }
}
