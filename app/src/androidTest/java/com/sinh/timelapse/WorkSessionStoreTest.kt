package com.sinh.timelapse

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkSessionStoreTest {
    private fun isolated(test: (File) -> Unit) {
        val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "work-store-${UUID.randomUUID()}")
        check(root.mkdir())
        try { test(root) } finally { check(root.deleteRecursively()) }
    }
    @Test fun v1LoadsWithoutRewriteThenArchiveMigratesAtomicallyAndPreservesDraftAndMedia() = isolated { root ->
        val original = WorkDraft(title = "Legacy intention", minutes = "45")
        val draft = WorkDraft(title = " \n", minutes = "bad", outcome = "a\nb")
        val store = WorkSessionStore(root)
        store.saveDraft(original); store.save(original.id, 123); store.saveDraft(draft)
        val file = File(root, "work-context/state.json")
        val oldJson = org.json.JSONObject(file.readText()).put("version", 1)
        oldJson.remove("deletedIds")
        oldJson.getJSONArray("records").getJSONObject(0).apply { remove("archived"); remove("description") }
        oldJson.getJSONObject("draft").remove("description")
        file.writeText(oldJson.toString())
        val before = file.readBytes()
        val media = File(root, "sessions/owned-sources").apply { mkdirs() }
        val photo = File(media, "owned.jpg").apply { writeText("owned source fixture") }
        val reopened = WorkSessionStore(root)
        assertEquals(draft, reopened.load().draft)
        assertFalse(reopened.load().records.single().archived)
        assertArrayEquals(before, file.readBytes()) // read never silently rewrites v1
        reopened.archive(original.id, true)
        val migrated = WorkSessionStore(root).load()
        assertTrue(migrated.records.single().archived)
        assertEquals(original.id, migrated.records.single().id)
        assertEquals(45, migrated.records.single().plannedMinutes)
        assertEquals(draft, migrated.draft)
        assertEquals(3, org.json.JSONObject(file.readText()).getInt("version"))
        assertEquals("owned source fixture", photo.readText())
        reopened.archive(original.id, false)
        assertFalse(WorkSessionStore(root).load().records.single().archived)
    }
    @Test fun archiveDeletePublicationFaultsKeepEvidenceAndRetiredIdsCannotResurrect() = isolated { root ->
        val draft = WorkDraft(title = "Delete this owned fixture")
        val store = WorkSessionStore(root)
        store.saveDraft(draft); store.save(draft.id, 123)
        val original = store.load()
        val file = File(root, "work-context/state.json")
        val before = file.readBytes()
        val faultBefore = WorkSessionStore(root) { if (it == "before-publish") error("injected") }
        assertThrows(IllegalStateException::class.java) { faultBefore.archive(draft.id, true) }
        assertArrayEquals(before, file.readBytes())
        val faultAfter = WorkSessionStore(root) { if (it == "after-publish") error("injected") }
        assertThrows(IllegalStateException::class.java) { faultAfter.delete(draft.id) }
        val reopened = WorkSessionStore(root)
        val deleted = reopened.load()
        assertTrue(deleted.records.isEmpty()); assertEquals(setOf(draft.id), deleted.deletedIds)
        assertFalse(file.readText().contains(draft.title))
        assertEquals(deleted, reopened.delete(draft.id))
        assertThrows(IllegalStateException::class.java) { reopened.saveDraft(draft) }
        assertThrows(IllegalStateException::class.java) { reopened.save(draft.id, 456) }
        assertThrows(IllegalArgumentException::class.java) { reopened.commit(original) }
    }
    @Test fun runningManagementAndMalformedV2AreRejectedWithoutReset() = isolated { root ->
        val store = WorkSessionStore(root)
        val draft = WorkDraft(title = "Running fixture")
        store.saveDraft(draft); store.save(draft.id, 123)
        store.commit(TimeboxPolicy.start(store.load(), draft.id, WorkMoment("boot", 0, 123)))
        val file = File(root, "work-context/state.json"); val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.archive(draft.id, true) }
        assertThrows(IllegalArgumentException::class.java) { store.delete(draft.id) }
        assertArrayEquals(before, file.readBytes())
        val malformed = org.json.JSONObject(file.readText())
        malformed.getJSONArray("records").getJSONObject(0).put("archived", true)
        file.writeText(malformed.toString()); val corrupt = file.readBytes()
        assertThrows(Exception::class.java) { WorkSessionStore(root).load() }
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test fun rawDraftRestartAtomicSaveReplayAndDiscard() = isolated { root ->
        val draft = WorkDraft(title = " \n", minutes = "1e4", outcome = "a\nb")
        WorkSessionStore(root).saveDraft(draft)
        var store = WorkSessionStore(root)
        assertEquals(draft, store.load().draft)
        val valid = draft.copy(title = "A", minutes = "25")
        store.saveDraft(valid); store.save(valid.id, 123)
        store = WorkSessionStore(root)
        assertEquals(1, store.save(valid.id, 456).records.size)
        assertEquals(123L, store.load().records.single().createdAt)
        assertNull(store.saveDraft(valid).draft) // stale autosave cannot resurrect consumed draft
        val second = WorkDraft(title = "B")
        store.saveDraft(second); store.discard(second.id)
        assertNull(WorkSessionStore(root).load().draft)
        assertEquals(1, store.load().records.size)
        assertFalse(File(root, "sessions").exists())
    }
    @Test fun faultsBeforeAndAfterPublicationKeepExactlyOneIdentity() = isolated { root ->
        val draft = WorkDraft(title = "A")
        WorkSessionStore(root).saveDraft(draft)
        val before = File(root, "work-context/state.json").readBytes()
        val beforeFault = WorkSessionStore(root) { if (it == "before-publish") error("injected") }
        assertThrows(IllegalStateException::class.java) { beforeFault.save(draft.id, 123) }
        assertArrayEquals(before, File(root, "work-context/state.json").readBytes())
        val afterFault = WorkSessionStore(root) { if (it == "after-publish") error("injected") }
        assertThrows(IllegalStateException::class.java) { afterFault.save(draft.id, 123) }
        val recovered = WorkSessionStore(root).save(draft.id, 999)
        assertEquals(1, recovered.records.size); assertEquals(123L, recovered.records.single().createdAt)
    }
    @Test fun externallyChangedBytesAreNeverOverwrittenFromCachedSnapshot() = isolated { root ->
        val store = WorkSessionStore(root)
        val draft = WorkDraft(title = "A")
        store.saveDraft(draft)
        val file = File(root, "work-context/state.json")
        file.writeText("{corrupt retained bytes")
        assertThrows(Exception::class.java) { store.saveDraft(draft.copy(title = "B")) }
        assertEquals("{corrupt retained bytes", file.readText())
    }
    @Test fun corruptUnsupportedAndAliasedBytesAreRetainedAndBlockWrites() = isolated { root ->
        val folder = File(root, "work-context").apply { mkdir() }
        val file = File(folder, "state.json")
        for (text in listOf("{broken", "{\"version\":4,\"draft\":null,\"records\":[],\"spans\":[]}")) {
            file.writeText(text)
            val store = WorkSessionStore(root)
            assertThrows(Exception::class.java) { store.load() }
            assertThrows(Exception::class.java) { store.saveDraft(WorkDraft()) }
            assertEquals(text, file.readText())
        }
        file.delete()
        val owned = File(root, "owned.json").apply { writeText("retained") }
        android.system.Os.symlink(owned.path, file.path)
        assertThrows(Exception::class.java) { WorkSessionStore(root).load() }
        assertEquals("retained", owned.readText())
        file.delete()
    }
}
