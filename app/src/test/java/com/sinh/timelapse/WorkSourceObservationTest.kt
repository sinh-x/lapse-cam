package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkSourceObservationTest {
    private val now = WorkMoment("boot", 2000, 1100)
    @Test fun unlinkedCaptureCheckpointsAndDraftsAreExactSourceNoops() {
        val planned = WorkDraft(title = "Planned").validated(100)
        val original = WorkSnapshot(records = listOf(planned))
        val span = RecordingSpan(UUID.randomUUID().toString(), "123-${UUID.randomUUID()}",
            WorkMoment("boot", 1000, 100), WorkMoment("boot", 1100, 200), confirmedThrough = 1900)
        val changed = original.copy(spans = listOf(span), draft = WorkDraft(title = "Unsaved draft"))
        assertEquals(WorkSourceObservation.of(original, now), WorkSourceObservation.of(changed, now))
    }
    @Test fun liveLinkAdvancesAndClosedUnknownTailsAreNotNoops() {
        val work = WorkDraft(title = "Running").validated(100)
        val running = TimeboxPolicy.start(WorkSnapshot(records = listOf(work)), work.id, WorkMoment("boot", 1000, 100))
        val span = RecordingSpan(UUID.randomUUID().toString(), "123-${UUID.randomUUID()}",
            WorkMoment("boot", 1000, 100), WorkMoment("boot", 1100, 200))
        val snapshot = running.copy(spans = listOf(span))
        assertNotEquals(WorkSourceObservation.of(snapshot, now), WorkSourceObservation.of(snapshot, now.copy(elapsed = 2100, epoch = 1200)))
        assertNotEquals(WorkSourceObservation.of(snapshot, now),
            WorkSourceObservation.of(snapshot.copy(spans = listOf(span.copy(endElapsed = 1900, unknownTail = true))), now))
    }
    @Test fun metadataArchiveAndDeletionChangesRemainObservable() {
        val work = WorkDraft(title = "Planned").validated(100)
        val original = WorkSnapshot(records = listOf(work))
        val key = WorkSourceObservation.of(original, now)
        assertNotEquals(key, WorkSourceObservation.of(original.copy(records = listOf(work.copy(title = "Edited"))), now))
        assertNotEquals(key, WorkSourceObservation.of(original.copy(records = listOf(work.copy(archived = true))), now))
        assertNotEquals(key, WorkSourceObservation.of(WorkSnapshot(deletedIds = setOf(work.id)), now))
    }
    @Test fun keyRetainsItsOwnCollectionSnapshot() {
        val work = WorkDraft(title = "Planned").validated(100)
        val records = mutableListOf(work)
        val snapshot = WorkSnapshot(records = records)
        val before = WorkSourceObservation.of(snapshot, now)
        records.clear()
        assertEquals(listOf(work), before.records)
        assertNotEquals(before, WorkSourceObservation.of(snapshot, now))
    }
}
