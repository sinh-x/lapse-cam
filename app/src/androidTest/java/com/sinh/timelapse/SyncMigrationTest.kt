package com.sinh.timelapse

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncMigrationTest {
    private fun isolated(test: (File) -> Unit) {
        val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "sync-migration-${UUID.randomUUID()}")
        check(root.mkdir())
        try { test(root) } finally { check(root.deleteRecursively()) }
    }
    @Test fun v2ReadIsBytePreservingAndV3PatchPreservesTimingAndLinks() = isolated { root ->
        val work = WorkDraft(title = "Legacy running source").validated(100)
        val recording = "123-${UUID.randomUUID()}"
        val span = RecordingSpan(UUID.randomUUID().toString(), recording, WorkMoment("boot", 1000, 100),
            WorkMoment("boot", 1100, 200), 2000)
        val snapshot = TimeboxPolicy.start(WorkSnapshot(records = listOf(work), spans = listOf(span)), work.id, WorkMoment("boot", 1000, 100))
            .copy(draft = WorkDraft(title = "Raw draft", minutes = "bad"), deletedIds = setOf(UUID.randomUUID().toString()))
        WorkSessionStore(root).commit(snapshot)
        val target = File(root, "work-context/state.json")
        val legacy = JSONObject(target.readText()).put("version", 2)
        legacy.getJSONObject("draft").remove("description")
        legacy.getJSONArray("records").getJSONObject(0).remove("description")
        target.writeText(legacy.toString())
        val bytes = target.readBytes()
        val store = WorkSessionStore(root)
        val loaded = store.load()
        assertEquals(snapshot, loaded); assertArrayEquals(bytes, target.readBytes())
        val running = loaded.records.single()
        val changed = SyncPolicy.patch(running, mapOf("description" to "Shared notes", "title" to " Remote title ", "expectedOutcome" to " "))
        store.commit(loaded.replace(changed))
        val reopened = WorkSessionStore(root).load()
        assertEquals(3, JSONObject(target.readText()).getInt("version"))
        assertEquals(changed, reopened.records.single())
        assertEquals(loaded.deletedIds, reopened.deletedIds); assertEquals(loaded.draft, reopened.draft)
        assertEquals(loaded.spans, reopened.spans)
        assertEquals(loaded.links(WorkMoment("boot", 2000, 1100)), reopened.links(WorkMoment("boot", 2000, 1100)))
        assertFalse(File(root, "sessions").exists())
    }
    @Test fun v3DescriptionFaultsNeverAcknowledgePartialMetadata() = isolated { root ->
        val draft = WorkDraft(title = "Fixture", description = "Original description")
        val store = WorkSessionStore(root); store.saveDraft(draft); store.save(draft.id, 100)
        val original = store.load()
        val changed = original.replace(SyncPolicy.patch(original.records.single(), mapOf("description" to "Acknowledged description")))
        val target = File(root, "work-context/state.json"); val bytes = target.readBytes()
        val before = WorkSessionStore(root) { if (it == "before-publish") error("injected") }
        assertThrows(IllegalStateException::class.java) { before.commit(changed) }
        assertArrayEquals(bytes, target.readBytes())
        val after = WorkSessionStore(root) { if (it == "after-publish") error("injected") }
        assertThrows(IllegalStateException::class.java) { after.commit(changed) }
        assertEquals(changed, WorkSessionStore(root).load())
    }
    @Test fun invalidV3DescriptionsRetainBytesAndBlockWrites() = isolated { root ->
        val draft = WorkDraft(title = "Fixture", description = "Notes")
        val store = WorkSessionStore(root); store.saveDraft(draft); store.save(draft.id, 100)
        val target = File(root, "work-context/state.json")
        val originalBytes = target.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.saveDraft(WorkDraft(title = "\uD800")) }
        assertArrayEquals(originalBytes, target.readBytes())
        val bad = JSONObject(target.readText()); bad.getJSONArray("records").getJSONObject(0).put("description", "😀".repeat(2001))
        target.writeText(bad.toString()); val bytes = target.readBytes()
        val reopened = WorkSessionStore(root)
        assertThrows(Exception::class.java) { reopened.load() }
        assertThrows(Exception::class.java) { reopened.saveDraft(WorkDraft(title = "Replacement")) }
        assertArrayEquals(bytes, target.readBytes())
    }
}
