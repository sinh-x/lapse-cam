package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class WorkListPolicyTest {
    private fun record(id: String, created: Long, type: WorkType = WorkType.WORK, archived: Boolean = false) =
        WorkSession(id, created, id, type.key, 25, archived = archived)

    @Test fun defaultsAndTimestampTiesAreDeterministicInBothPartitions() {
        val active = listOf(record("z", 2), record("b", 1), record("a", 2))
        val all = active + active.map { it.copy(id = it.id + "-archived", archived = true) }
        for (archived in listOf(false, true)) {
            val suffix = if (archived) "-archived" else ""
            assertEquals(listOf("a", "z", "b").map { it + suffix }, WorkListPolicy.project(all, archived).map { it.id })
            assertEquals(listOf("b", "a", "z").map { it + suffix }, WorkListPolicy.project(all, archived, sort = WorkSort.OLDEST).map { it.id })
        }
        assertEquals(active, all.take(3))
        assertEquals(listOf("z", "b", "a"), active.map { it.id })
    }
    @Test fun allFiveTypesUseDeclaredOrderThenNewestAndId() {
        val records = WorkType.entries.reversed().flatMap { type ->
            listOf(record(type.key + "-z", 10, type), record(type.key + "-old", 1, type), record(type.key + "-a", 10, type))
        }
        assertEquals(WorkType.entries.flatMap { listOf(it.key + "-a", it.key + "-z", it.key + "-old") },
            WorkListPolicy.project(records, sort = WorkSort.TYPE).map { it.id })
    }
    @Test fun eachFilterSelectsExactPartitionMembershipAndTypeEditsRefreshProjection() {
        val records = WorkType.entries.map { record(it.key, 1, it) }
        val all = records + records.map { it.copy(id = it.id + "-archived", archived = true) }
        for (archived in listOf(false, true)) {
            assertEquals(5, WorkListPolicy.project(all, archived).size)
            WorkType.entries.forEach { type ->
                assertEquals(all.filter { it.archived == archived && it.type == type.key }, WorkListPolicy.project(all, archived, type.key))
            }
        }
        val target = records.first()
        val changed = WorkEditPolicy.update(WorkSnapshot(records = records), target, WorkEditBuffer(target).draft.copy(type = "study"))
        assertTrue(WorkListPolicy.project(changed.records, type = "work").isEmpty())
        assertEquals(2, WorkListPolicy.project(changed.records, type = "study").size)
    }
    @Test fun emptyListsAndExcludedRunningRecordDoNotMutateSource() {
        assertTrue(WorkListPolicy.project(emptyList()).isEmpty())
        val running = record("running", 1).copy(state = WorkState.RUNNING, start = WorkMoment("boot", 0, 100), deadline = 1_500_000)
        val snapshot = WorkSnapshot(records = listOf(running))
        assertTrue(WorkListPolicy.project(snapshot.records, type = "social").isEmpty())
        assertEquals(running, snapshot.running)
        assertThrows(IllegalArgumentException::class.java) { WorkListPolicy.project(snapshot.records, type = "unknown") }
    }
}
