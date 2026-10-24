package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class WorkSessionTest {
    @Test fun titleLimitsUseCodePointsAndNeverTruncate() {
        for (text in listOf("", " \n ", "x".repeat(121), "😀".repeat(121)))
            assertTrue(WorkDraft(title = text).errors().containsKey("title"))
        for (text in listOf("x", "😀".repeat(120))) assertEquals(text, WorkDraft(title = " $text ").validated(1).title)
    }
    @Test fun optionalOutcomeAndStableTypes() {
        assertNull(WorkDraft(title = "a", outcome = " \n ").validated(0).outcome)
        assertEquals(" a\nb ", WorkDraft(title = "a", outcome = " a\nb ").validated(0).outcome)
        assertTrue(WorkDraft(title = "a", outcome = "😀".repeat(2000)).errors().isEmpty())
        assertTrue(WorkDraft(title = "a", outcome = "😀".repeat(2001)).errors().containsKey("outcome"))
        assertEquals("to-be-sorted", WorkDraft(title = "a").validated(0).type)
        WorkType.entries.forEach { assertEquals(it.key, WorkDraft(title = "a", type = it.key).validated(0).type) }
        assertFalse(WorkDraft(title = "a", type = "0").errors().isEmpty())
    }
    @Test fun durationsAreStrictAndOriginalPlanIsRetained() {
        for (text in listOf("", "0", "-1", "1441", "1.0", "1e2", "999999999999999", " 25 ", "x"))
            assertTrue(text, WorkDraft(title = "a", minutes = text).errors().containsKey("minutes"))
        for (minutes in listOf(1, 25, 45, 60, 90, 1440))
            assertEquals(minutes, WorkDraft(title = "a", minutes = "$minutes").validated(0).plannedMinutes)
        val draft = WorkDraft(title = "a")
        val saved = draft.validated(100)
        assertEquals(draft.id, saved.id); assertEquals(WorkState.PLANNED, saved.state); assertNull(saved.start)
        assertNotEquals(saved.id, saved.copyDraft().id)
    }
}
