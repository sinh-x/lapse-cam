package com.sinh.timelapse

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** UUID-cache fixtures only; no enrollment, server, network or real saved metadata. */
class SyncProjectionObservationTest {
    private fun fixture(body: (SyncStore, File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile,
            "source-observation-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { body(SyncStore(root), File(root, "private-sync/state.json")) }
        finally { check(root.deleteRecursively()) }
    }
    @Test fun identicalObservationIsBytePreservingWithoutLosingLaterSourceTransitions() = fixture { store, target ->
        val work = WorkDraft(title = "Fixture").validated(100)
        val snapshot = WorkSnapshot(records = listOf(work))
        val first = store.load()
        assertTrue(SyncProjection.observe(first, snapshot, WorkMoment("boot", 1000, 100)))
        store.commit(first)
        val bytes = target.readBytes()
        val same = store.load()
        assertFalse(SyncProjection.observe(same, snapshot, WorkMoment("boot", 2000, 1100)))
        store.validateUnchanged()
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(1, same.getJSONArray("pending").length())
        val started = TimeboxPolicy.start(snapshot, work.id, WorkMoment("boot", 2000, 1100))
        assertTrue(SyncProjection.observe(same, started, WorkMoment("boot", 2100, 1200)))
        store.commit(same)
        assertEquals(2, store.load().getJSONArray("pending").length())
        assertEquals(2L, store.load().getJSONObject("sources").getJSONObject(work.id).getLong("sequence"))
    }
    @Test fun noopValidationStillDetectsExternalMutationAndBlocksFurtherWrites() = fixture { store, target ->
        store.load()
        val external = org.json.JSONObject(target.readText()).put("lastSuccess", 123)
        target.writeText(external.toString()); val bytes = target.readBytes()
        assertThrows(IllegalStateException::class.java) { store.validateUnchanged() }
        assertThrows(IllegalStateException::class.java) { store.load() }
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun changedOpenCaptureRangesAreNeverCoalescedAsNoops() = fixture { store, _ ->
        val work = WorkDraft(title = "Running").validated(100)
        val start = WorkMoment("boot", 1000, 100)
        val running = TimeboxPolicy.start(WorkSnapshot(records = listOf(work)), work.id, start)
        val span = RecordingSpan(UUID.randomUUID().toString(), "123-${UUID.randomUUID()}", start,
            firstPhoto = WorkMoment("boot", 1100, 200))
        val snapshot = running.copy(spans = listOf(span))
        val next = store.load()
        assertTrue(SyncProjection.observe(next, snapshot, WorkMoment("boot", 1200, 300)))
        assertTrue(SyncProjection.observe(next, snapshot, WorkMoment("boot", 1300, 400)))
        assertEquals(2, next.getJSONArray("pending").length())
        val source = next.getJSONObject("sources").getJSONObject(work.id).getJSONObject("source")
        assertEquals(1300L, source.getJSONArray("links").getJSONObject(0).getLong("endElapsed"))
        store.commit(next)
    }
    @Test fun deletionStillQueuesExactlyOnceAndCannotBeReused() = fixture { store, _ ->
        val work = WorkDraft(title = "Fixture").validated(100)
        val original = WorkSnapshot(records = listOf(work))
        val next = store.load()
        assertTrue(SyncProjection.observe(next, original, WorkMoment("boot", 1000, 100)))
        val deleted = original.copy(records = emptyList(), deletedIds = setOf(work.id))
        assertTrue(SyncProjection.observe(next, deleted, WorkMoment("boot", 1000, 100)))
        assertFalse(SyncProjection.observe(next, deleted, WorkMoment("boot", 1000, 100)))
        assertEquals(2, next.getJSONArray("pending").length())
        assertThrows(IllegalStateException::class.java) { SyncProjection.observe(next, original, WorkMoment("boot", 1000, 100)) }
        store.commit(next)
    }
}
