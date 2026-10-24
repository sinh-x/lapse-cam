package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkManagementPolicyTest {
    private val a = WorkDraft(title = "A", minutes = "1").validated(100)
    private val b = WorkDraft(title = "B").validated(200)
    private fun now(elapsed: Long) = WorkMoment("boot", elapsed, 100_000 + elapsed)

    @Test fun plannedArchiveRestoreIsIdempotentAndOriginalPlanDoesNotChange() {
        val original = WorkSnapshot(records = listOf(a, b))
        val archived = WorkManagementPolicy.archive(original, a.id, true)
        assertTrue(archived.records.first().archived)
        assertEquals(1, archived.records.first().plannedMinutes)
        assertEquals(archived, WorkManagementPolicy.archive(archived, a.id, true))
        assertEquals(original, WorkManagementPolicy.archive(archived, a.id, false))
        assertEquals("1 minute", a.plannedDurationLabel); assertEquals("25 minutes", b.plannedDurationLabel)
    }
    @Test fun runningCannotBeArchivedDeletedOrHiddenAndArchivedCannotStart() {
        val original = WorkSnapshot(records = listOf(a))
        val running = TimeboxPolicy.start(original, a.id, now(0))
        assertThrows(IllegalArgumentException::class.java) { WorkManagementPolicy.archive(running, a.id, true) }
        assertThrows(IllegalArgumentException::class.java) { WorkManagementPolicy.delete(running, a.id) }
        val archived = WorkManagementPolicy.archive(original, a.id, true)
        assertThrows(IllegalArgumentException::class.java) { TimeboxPolicy.start(archived, a.id, now(0)) }
        assertEquals(a.id, TimeboxPolicy.start(WorkManagementPolicy.archive(archived, a.id, false), a.id, now(0)).running!!.id)
    }
    @Test fun archiveRetainsLinksButExplicitDeleteOnlyRemovesSelectedWorkContext() {
        var original = WorkSnapshot(records = listOf(a, b))
        original = TimeboxPolicy.start(original, a.id, now(0))
        original = TimeboxPolicy.end(original, a.id, now(10))
        original = TimeboxPolicy.start(original, b.id, now(20))
        original = TimeboxPolicy.end(original, b.id, now(30))
        original = original.copy(spans = listOf(RecordingSpan(UUID.randomUUID().toString(), "recording", now(0), now(0), 40)))
        val links = original.links(now(100))
        val archived = WorkManagementPolicy.archive(original, a.id, true)
        assertEquals(links, archived.links(now(100)))
        val deleted = WorkManagementPolicy.delete(archived, a.id)
        assertEquals(listOf(b.id), deleted.records.map { it.id })
        assertEquals(links.filter { it.workId == b.id }, deleted.links(now(100)))
        assertEquals(original.spans, deleted.spans)
        assertEquals(setOf(a.id), deleted.deletedIds)
        assertEquals(deleted, WorkManagementPolicy.delete(deleted, a.id))
    }
    @Test fun copyOfArchivedEndedTimeboxCreatesNewUnarchivedPlannedIdentity() {
        val ended = TimeboxPolicy.end(TimeboxPolicy.start(WorkSnapshot(records = listOf(a)), a.id, now(0)), a.id, now(10)).records.single()
        val copied = ended.copy(archived = true).copyDraft().validated(999)
        assertNotEquals(a.id, copied.id); assertFalse(copied.archived)
        assertEquals(WorkState.PLANNED, copied.state); assertNull(copied.start)
        assertEquals(a.title, copied.title); assertEquals(a.plannedMinutes, copied.plannedMinutes)
    }
}
