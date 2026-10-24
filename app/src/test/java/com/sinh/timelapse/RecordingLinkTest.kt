package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecordingLinkTest {
    private fun moment(ms: Long) = WorkMoment("boot:1", ms, ms + 100_000)
    private fun work(title: String, start: Long, end: Long): WorkSession {
        val record = WorkDraft(title = title).validated(0)
        return TimeboxPolicy.end(TimeboxPolicy.start(WorkSnapshot(records = listOf(record)), record.id, moment(start)), record.id, moment(end)).records.single()
    }
    private fun span(recording: String, start: Long, end: Long) = RecordingSpan(UUID.randomUUID().toString(), recording,
        moment(start), moment(start), end)
    @Test fun manyToManyHalfOpenRangesExcludeGapsAndEquality() {
        val a = work("A", 10, 20); val b = work("B", 30, 40)
        val snapshot = WorkSnapshot(records = listOf(a, b), spans = listOf(span("recording", 0, 40)))
        val links = snapshot.links(moment(100))
        assertEquals(listOf(10L to 20L, 30L to 40L), links.map { it.startElapsed to it.endElapsed })
        assertEquals(links, snapshot.links(moment(200)))
        assertTrue(snapshot.copy(spans = listOf(span("recording", 20, 30))).links(moment(100)).isEmpty())
    }
    @Test fun rolloverNoPhotosBootMismatchAndUnknownTail() {
        val work = work("A", 10, 90)
        val spans = listOf(span("one", 0, 20), span("two", 30, 50))
        val base = WorkSnapshot(records = listOf(work), spans = spans)
        assertEquals(listOf("one", "two"), base.links(moment(100)).map { it.recordingId })
        assertTrue(base.copy(spans = spans.map { it.copy(firstPhoto = null) }).links(moment(100)).isEmpty())
        assertTrue(base.copy(spans = spans.map { it.copy(firstPhoto = it.firstPhoto!!.copy(boot = "boot:2")) }).links(moment(100)).isEmpty())
        assertTrue(base.copy(spans = spans.map { it.copy(unknownTail = true) }).links(moment(100)).all { it.unknownTail })
    }
    @Test fun openSpansClampToLogicalWorkDeadlineNotLateCallback() {
        val planned = WorkDraft(title = "A", minutes = "1").validated(0)
        val running = TimeboxPolicy.start(WorkSnapshot(records = listOf(planned)), planned.id, moment(0))
        val snapshot = running.copy(spans = listOf(span("one", 0, 99_999).copy(endElapsed = null)))
        assertEquals(60_000L, snapshot.links(moment(99_999)).single().endElapsed)
    }
}
