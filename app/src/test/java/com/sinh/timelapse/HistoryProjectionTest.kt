package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HistoryProjectionTest {
    private val today = LocalDate.of(2026, 11, 1)
    private val zone = ZoneId.of("UTC")
    private fun epoch(day: LocalDate, hour: Int = 12) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun review(id: String, date: LocalDate = today) = SessionReview(id, epoch(date), "Adaptive", 30, 15,
        0, false, 0, 0, 0, 0, 0, "Saved", 0, 0, 0, "", null, false, false)
    private fun work(id: String, start: LocalDate?, saved: LocalDate = today.minusDays(10)) =
        WorkSession(id, epoch(saved), id, WorkType.WORK.key, 25,
            start = start?.let { WorkMoment("boot", 0, epoch(it)) })

    @Test fun sessionsDeduplicateOrderAndKeepTodayEmpty() {
        val old = today.minusDays(1)
        val result = SessionHistory.project(listOf(review("b", old), review("a", old), review("a", old)), today, zone)
        assertTrue(result.today.items.isEmpty())
        assertEquals(listOf("a", "b"), result.dates.single().items.map { it.id })
        assertTrue(SessionHistory.project(emptyList(), today, zone).dates.isEmpty())
    }
    @Test fun phoneTimezoneUsesStartInstantAndPreservesIdentity() {
        val late = review("one").copy(startedAt = Instant.parse("2026-11-01T00:30:00Z").toEpochMilli())
        val west = SessionHistory.project(listOf(late), today, ZoneId.of("America/Los_Angeles"))
        assertEquals(today.minusDays(1), west.dates.single().date)
        assertEquals("one", west.dates.single().items.single().id)
    }
    @Test fun unstartedFirstAndStartNeverFallsBackToCreated() {
        val records = listOf(work("today", today), work("old", today.minusDays(1), today), work("orphan", null))
        val result = TimeboxHistory.project(records, false, null, WorkSort.NEWEST, today, zone)
        assertEquals(listOf("orphan"), result.unstarted.map { it.id })
        assertEquals(listOf("today"), result.today.map { it.id })
        assertEquals(today.minusDays(1), result.dates.single().date)
        assertEquals("old", result.dates.single().items.single().id)
    }
    @Test fun sortsStayWithinPortionsDatesAndRespectPartitionFilter() {
        val records = listOf(work("orphan-new", null, today), work("orphan-old", null),
            work("today", today), work("old", today.minusDays(1)), work("archived", null).copy(archived = true))
        WorkSort.entries.forEach { sort ->
            val result = TimeboxHistory.project(records, false, "work", sort, today, zone)
            assertEquals(2, result.unstarted.size); assertEquals("today", result.today.single().id)
            assertEquals("old", result.dates.single().items.single().id)
        }
        assertEquals("orphan-old", TimeboxHistory.project(records, false, null, WorkSort.OLDEST, today, zone).unstarted.first().id)
        assertEquals("archived", TimeboxHistory.project(records, true, null, WorkSort.TYPE, today, zone).unstarted.single().id)
        assertTrue(TimeboxHistory.project(records, false, "study", WorkSort.NEWEST, today, zone).unstarted.isEmpty())
    }
    @Test fun attentionIsTypedAllDateAndOncePerSession() {
        val failed = review("failed", today.minusDays(4)).copy(processing = SessionProcessing(issues = listOf(
            SessionIssue(ProcessingStage.UNKNOWN, "failure"), SessionIssue(ProcessingStage.DELETING, "blocked"))))
        val queued = review("queued").copy(error = "automatic retry", canRetry = true,
            processing = SessionProcessing(wait = ProcessingWait.RETRY_DELAY))
        val result = SessionHistory.project(listOf(failed, failed, queued), today, zone, true)
        assertTrue(result.today.items.isEmpty()); assertEquals(1, result.dates.single().items.size)
        assertEquals(ProcessingStage.UNKNOWN, failed.processing.issues.first().stage)
    }
    @Test fun delayedCleanupIsNotActiveAndFailureStageNotGuessed() {
        val record = SessionRecord("one", SessionPhase.CLEANUP, operation = "operation", cleanupOnly = true,
            cleanupCycle = AttemptCycle(1, 10000, false), error = "encoding word is not provenance")
        val waiting = SessionProcessingProjection.project(record, null, true)
        assertNull(waiting.active); assertEquals(ProcessingWait.RETRY_DELAY, waiting.wait)
        assertTrue(waiting.issues.isEmpty())
        val failed = SessionProcessingProjection.project(record.copy(phase = SessionPhase.NEEDS_ATTENTION), null, true)
        assertEquals(ProcessingStage.UNKNOWN, failed.issues.single().stage)
        assertNull(SessionProcessingProjection.project(record, ActiveProcessing("one", "obsolete", ProcessingStage.CLEANUP), true).active)
    }
    @Test fun outputEvidenceIsIdentityBoundAndUnknownCannotResolveKnownFailure() {
        val id = SessionOutputIdentity("one", "content://video/1", "op")
        val missing = OutputObservation(id, OutputAvailability.MISSING, 10, "missing")
        val unknown = OutputObservation(id, OutputAvailability.UNKNOWN, 20, "unavailable")
        assertEquals(OutputAvailability.MISSING, missing.merge(unknown).availability)
        assertEquals(10L, missing.merge(unknown).checkedAt)
        assertEquals(20L, missing.merge(unknown).lastAttemptAt)
        val readable = OutputObservation(id, OutputAvailability.READABLE, 30)
        assertFalse(missing.merge(readable).availability.actionable)
        val review = review("one").copy(output = id.uri, verifiedOperation = id.operation, outputObservation = missing)
        assertTrue(review.needsAttention)
        assertFalse(review.copy(output = "content://video/2").needsAttention)
        assertEquals(readable, readable.merge(missing))
    }
    @Test fun progressIsOnlyDeterminateForEncoding() {
        assertEquals(100, ActiveProcessing("one", "op", ProcessingStage.EXPORTING, 100).percent)
        assertThrows(IllegalArgumentException::class.java) { ActiveProcessing("one", "op", ProcessingStage.VERIFYING, 100) }
        assertNull(ActiveProcessing("one", "op", ProcessingStage.VERIFYING).percent)
    }
}
