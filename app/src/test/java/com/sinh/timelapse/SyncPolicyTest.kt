package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class SyncPolicyTest {
    private fun work() = WorkDraft(title = "Source", outcome = "Expected", description = "Notes").validated(100)

    @Test fun descriptivePatchCannotAffectExecutionEvenWhileRunning() {
        val original = work()
        val running = TimeboxPolicy.start(WorkSnapshot(records = listOf(original)), original.id, WorkMoment("boot", 1000, 100)).running!!
        val changed = SyncPolicy.patch(running, mapOf("title" to " Remote ", "type" to "study",
            "description" to "<script>display as text</script>", "expectedOutcome" to " "))
        assertEquals(" Remote ", changed.title)
        assertEquals(" ", changed.outcome)
        assertEquals(running, changed.copy(title = running.title, type = running.type,
            description = running.description, outcome = running.outcome))
        for (field in listOf("state", "plannedMinutes", "deadline", "start", "links", "archived", "deleted", "device"))
            assertThrows(IllegalArgumentException::class.java) { SyncPolicy.patch(running, mapOf(field to "forged")) }
    }
    @Test fun limitsUseUnicodeScalarsWithoutTruncationOrReplacement() {
        SyncPolicy.validate("title", "😀".repeat(120))
        SyncPolicy.validate("description", "😀".repeat(2000))
        SyncPolicy.validate("expectedOutcome", "")
        for ((field, value) in listOf("title" to "😀".repeat(121), "title" to " \n", "type" to "custom",
            "description" to "x".repeat(2001), "title" to "\uD800", "expectedOutcome" to "\uDC00"))
            assertThrows(IllegalArgumentException::class.java) { SyncPolicy.validate(field, value) }
        assertTrue(WorkDraft(title = "a", description = "\uD800").errors().containsKey("description"))
        assertTrue(WorkDraft(title = "a", description = "😀".repeat(2001)).errors().containsKey("description"))
        assertEquals("Notes", work().description)
        assertEquals("Notes", work().copyDraft().description)
    }
    @Test fun backgroundIsWifiOnlyAndCellularNeedsThisManualAttemptConfirmation() {
        for (enabled in listOf(false, true)) for (enrolled in listOf(false, true))
            for (network in SyncPolicy.Network.entries) for (manual in listOf(false, true)) for (confirmed in listOf(false, true)) {
                val expected = enabled && enrolled && (network == SyncPolicy.Network.WIFI ||
                    (network == SyncPolicy.Network.MOBILE && manual && confirmed))
                assertEquals(expected, SyncPolicy.mayTransfer(enabled, enrolled, network, manual, confirmed))
            }
    }
    @Test fun copyAsNewDoesNotTransferExecutionOrArchiveIdentity() {
        val original = work()
        val started = TimeboxPolicy.start(WorkSnapshot(records = listOf(original)), original.id, WorkMoment("boot", 1000, 100))
        val ended = TimeboxPolicy.end(started, original.id, WorkMoment("boot", 2000, 1100)).records.single().copy(archived = true)
        val copy = SyncPolicy.copyAsNew(ended, 5000)
        assertNotEquals(ended.id, copy.id)
        assertEquals(WorkState.PLANNED, copy.state); assertFalse(copy.archived)
        assertNull(copy.start); assertNull(copy.deadline); assertNull(copy.endElapsed); assertNull(copy.reason)
        assertEquals(0L, copy.confirmedElapsed)
        assertEquals(ended.title, copy.title); assertEquals(ended.type, copy.type)
        assertEquals(ended.description, copy.description); assertEquals(ended.outcome, copy.outcome)
        assertEquals(ended.plannedMinutes, copy.plannedMinutes)
    }
    @Test fun deviceNamespacePreventsEqualLocalUuidCollisions() {
        val local = work().id
        val first = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val second = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        assertNotEquals(SyncPolicy.key(first, local), SyncPolicy.key(second, local))
        for (unsafe in listOf("../escape", first.uppercase(), ""))
            assertThrows(IllegalArgumentException::class.java) { SyncPolicy.key(unsafe, local) }
    }
}
