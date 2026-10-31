package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class WorkEditPolicyTest {
    private val work = WorkDraft(title = "Original", type = "study", minutes = "1", outcome = "Read", description = "Existing shared notes").validated(123)
    private val draft = WorkDraft(title = "Unsaved create", minutes = "45")
    private val snapshot = WorkSnapshot(draft = draft, records = listOf(work), deletedIds = setOf("deleted"))
    private fun now(elapsed: Long, boot: String = "boot") = WorkMoment(boot, elapsed, 100_000 + elapsed)
    private fun edited(value: WorkSession) = WorkEditBuffer(value).draft.copy(title = "Changed", type = "work", outcome = "Done")

    @Test fun plannedUpdateChangesOnlyAllowedFieldsInPlaceAndPreservesCreateDraft() {
        val other = work.copy(id = "other")
        val original = snapshot.copy(records = listOf(other, work))
        val result = WorkEditPolicy.update(original, work, edited(work).copy(minutes = "45"))
        assertEquals(original.copy(records = listOf(other, work.copy(title = "Changed", type = "work", outcome = "Done", plannedMinutes = 45))), result)
        assertEquals(draft, result.draft)
        assertEquals(work, original.records.last())
    }
    @Test fun everyEndedReasonLocksDurationAndPreservesTimingArchiveAndLinks() {
        val running = TimeboxPolicy.start(snapshot, work.id, now(0))
        val ended = listOf(TimeboxPolicy.end(running, work.id, now(10)),
            TimeboxPolicy.reconcile(running, now(60_000)),
            TimeboxPolicy.reconcile(TimeboxPolicy.reconcile(running, now(30_000)), now(100, "reboot")))
        ended.forEach { value ->
            val before = value.copy(records = value.records.map { it.copy(archived = true) },
                spans = listOf(RecordingSpan("span", "recording", now(0), now(0), 60_000)))
            val target = before.records.single()
            val result = WorkEditPolicy.update(before, target, edited(target))
            assertEquals(before.copy(records = listOf(target.copy(title = "Changed", type = "work", outcome = "Done"))), result)
            assertEquals(before.links(now(70_000)), result.links(now(70_000)))
            assertThrows(IllegalArgumentException::class.java) { WorkEditPolicy.update(before, target, edited(target).copy(minutes = "2")) }
        }
    }
    @Test fun rejectsRunningIdentityChangesAndEveryStaleTargetWithoutResurrection() {
        val mutations = listOf(TimeboxPolicy.start(snapshot, work.id, now(0)),
            WorkManagementPolicy.archive(snapshot, work.id, true),
            snapshot.replace(work.copy(title = "Newer")), WorkManagementPolicy.delete(snapshot, work.id))
        mutations.forEach { newer ->
            assertThrows(IllegalArgumentException::class.java) { WorkEditPolicy.update(newer, work, edited(work)) }
        }
        val archived = work.copy(archived = true)
        assertThrows(IllegalArgumentException::class.java) { WorkEditPolicy.update(snapshot, archived, edited(archived)) }
        val running = mutations.first().records.single()
        assertThrows(IllegalArgumentException::class.java) { WorkEditPolicy.update(mutations.first(), running, edited(running)) }
        assertThrows(IllegalArgumentException::class.java) { WorkEditPolicy.update(snapshot, work, edited(work).copy(id = "new")) }
    }
    @Test fun validationEnforcesCodePointAndWholeMinuteLimitsWithoutTruncating() {
        val initial = edited(work)
        listOf(initial.copy(title = " "), initial.copy(title = "😀".repeat(121)), initial.copy(outcome = "😀".repeat(2001)), initial.copy(description = "😀".repeat(2001)),
            initial.copy(minutes = "0"), initial.copy(minutes = "1441"), initial.copy(minutes = "1.5"), initial.copy(minutes = "abc")).forEach {
            assertThrows(IllegalArgumentException::class.java) { WorkEditPolicy.update(snapshot, work, it) }
        }
        listOf("1", "1440").forEach { minutes ->
            val result = WorkEditPolicy.update(snapshot, work, initial.copy(title = "😀".repeat(120), outcome = "😀".repeat(2000), minutes = minutes))
            assertEquals("😀".repeat(120), result.records.single().title)
            assertEquals("😀".repeat(2000), result.records.single().outcome)
        }
    }
    @Test fun savedEditorRetainsSharedDescriptionAndCanUpdateItWithoutChangingTiming() {
        val buffer = WorkEditBuffer(work)
        assertEquals(work.description, buffer.draft.description)
        assertFalse(buffer.changed)
        assertEquals(snapshot, WorkEditPolicy.update(snapshot, work, buffer.draft))
        val renamed = WorkEditPolicy.update(snapshot, work, buffer.draft.copy(title = "Renamed"))
        assertEquals(work.copy(title = "Renamed"), renamed.records.single())
        val changed = WorkEditPolicy.update(snapshot, work, buffer.draft.copy(description = "Updated notes"))
        assertEquals(snapshot.copy(records = listOf(work.copy(description = "Updated notes"))), changed)
        assertEquals(draft, changed.draft)
    }
    @Test fun incomingSharedDescriptionInvalidatesTheOldSavedEditor() {
        val buffer = WorkEditBuffer(work)
        val shared = SyncPolicy.patch(work, mapOf("description" to "Newer remote notes"))
        val newer = snapshot.replace(shared)
        assertThrows(IllegalArgumentException::class.java) {
            WorkEditPolicy.update(newer, work, buffer.draft.copy(title = "Stale edit"))
        }
        assertEquals("Newer remote notes", newer.records.single().description)
        assertEquals(work.description, buffer.draft.description)
    }
    @Test fun bufferIsSeparateUnchangedSaveIsNoOpAndOptionalValuesNormalize() {
        val buffer = WorkEditBuffer(work)
        assertFalse(buffer.changed)
        assertTrue(buffer.copy(draft = buffer.draft.copy(title = "Different")).changed)
        assertEquals(snapshot, WorkEditPolicy.update(snapshot, work, buffer.draft))
        val result = WorkEditPolicy.update(snapshot, work, buffer.draft.copy(title = " Trimmed ", outcome = " ", type = null))
        assertEquals("Trimmed", result.records.single().title)
        assertNull(result.records.single().outcome)
        assertEquals(WorkType.UNSORTED.key, result.records.single().type)
        assertEquals(draft, result.draft)
    }
}
