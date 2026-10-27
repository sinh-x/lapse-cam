package com.sinh.timelapse

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SyncStoreTest {
    private fun fixture(block: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sync-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun identityQueueAndExactRequestsSurviveRecreation() = fixture { root ->
        val first = SyncStore(root)
        val id = first.load().getString("device")
        repeat(100) { first.enqueue("/v1/patch", "{\"fixture\":$it}") }
        val before = first.load().getJSONArray("pending").toString()
        val next = SyncStore(root).load()
        assertEquals(id, next.getString("device")); assertEquals(before, next.getJSONArray("pending").toString())
        assertEquals(100, next.getJSONArray("pending").length())
        assertFalse(next.getBoolean("enabled"))
    }
    @Test fun unknownAndExternalStateRetainedWithoutReset() = fixture { root ->
        val store = SyncStore(root); store.load()
        val file = File(root, "private-sync/state.json")
        file.writeText(JSONObject(file.readText()).put("version", 77).toString())
        val bytes = file.readBytes()
        assertTrue(runCatching { store.load() }.isFailure)
        assertTrue(runCatching { SyncStore(root).load() }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun eachPublicationFaultNeverAcknowledgesOrReusesOldOwner() = fixture { root ->
        for (boundary in listOf("write", "sync", "rename", "directory-sync")) {
            val folder = File(root, boundary).apply { mkdir() }
            SyncStore(folder).load()
            val store = SyncStore(folder) { if (it == boundary) error("Injected") }
            store.load()
            assertTrue(runCatching { store.enqueue("/v1/patch", "{}") }.isFailure)
            assertTrue(runCatching { store.load() }.isFailure)
            val recovered = runCatching { SyncStore(folder).load() }
            if (boundary == "rename" || boundary == "directory-sync") assertEquals(1, recovered.getOrThrow().getJSONArray("pending").length())
            else assertTrue(recovered.isFailure) // interrupted bytes retained, not auto-purged
        }
    }
    @Test fun projectionExcludesDraftRetainsDeletionAndNeverClobbersDescriptions() = fixture { root ->
        val store = SyncStore(root)
        val work = WorkDraft(title = "Owned").validated(10)
        val moment = WorkMoment("boot", 20, 30)
        val snapshot = WorkSnapshot(draft = WorkDraft(title = "Private unfinished"), records = listOf(work))
        store.update { SyncProjection.observe(it, snapshot, moment) }
        store.update { SyncProjection.observe(it, snapshot, moment) }
        assertEquals(1, store.load().getJSONArray("pending").length())
        store.update { SyncProjection.observe(it, WorkManagementPolicy.delete(snapshot, work.id), moment) }
        val queue = store.load().getJSONArray("pending").objects()
        assertEquals(2, queue.size)
        assertFalse(queue.toString().contains("Private unfinished"))
        assertTrue(JSONArray(queue.last().getString("body")).getJSONObject(0).getJSONObject("source").getBoolean("deleted"))
        val fields = JSONObject()
        mapOf("title" to "Remote", "type" to "work", "description" to "Text", "expectedOutcome" to "Goal").forEach { (k,v) -> fields.put(k, json("value" to v)) }
        val running = TimeboxPolicy.start(WorkSnapshot(records = listOf(work)), work.id, moment).records.single()
        val patched = SyncProjection.metadata(running, fields)
        assertEquals(running.copy(title = "Remote", type = "work", description = "Text", outcome = "Goal"), patched)
    }
    @Test fun missingInitializedStateAndUnknownInventoryAreRetained() = fixture { root ->
        SyncStore(root).load()
        val state = File(root, "private-sync/state.json")
        assertTrue(state.delete())
        assertTrue(runCatching { SyncStore(root).load() }.isFailure)
        assertFalse(state.exists())
        val id = UUID.randomUUID().toString(); val recording = "100-${UUID.randomUUID()}"
        SyncMediaInventory.record(root, id, recording, 2, 15)
        val record = File(root, "export-inventory/$id.json"); val bytes = record.readBytes()
        SyncMediaInventory.associate(root, id, recording)
        assertArrayEquals(bytes, record.readBytes())
        assertTrue(runCatching { SyncMediaInventory.associate(root, id, "200-${UUID.randomUUID()}") }.isFailure)
        File(root, "export-inventory/unknown.new").writeText("retained")
        assertTrue(runCatching { SyncMediaInventory.record(root, UUID.randomUUID().toString(), recording, 2, 15) }.isFailure)
        assertArrayEquals(bytes, record.readBytes())
    }
    @Test fun inventoryAliasesNeverRewriteOrDeleteTargets() = fixture { root ->
        val id = UUID.randomUUID().toString(); val recording = "100-${UUID.randomUUID()}"
        SyncMediaInventory.record(root, id, recording, 1, 15)
        val target = File(root, "export-inventory/$id.json"); val before = target.readBytes()
        val other = File(root, "other").apply { writeText("valuable fixture") }
        val alias = File(root, "export-inventory/${UUID.randomUUID()}.json")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), other.toPath())
        assertTrue(runCatching { SyncMediaInventory.record(root, id, recording, 1, 15) }.isFailure)
        assertEquals("valuable fixture", other.readText()); assertArrayEquals(before, target.readBytes())
        java.nio.file.Files.delete(alias.toPath())
    }
    @Test fun uiThreadCannotCreateOrReadSyncStorage() = fixture { root ->
        var rejected = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { rejected = runCatching { SyncStore(root).load() }.isFailure }
        assertTrue(rejected); assertFalse(File(root, "private-sync").exists())
    }
    @Test fun endpointAndKeystoreRejectUnsafeEnrollment() {
        listOf("http://host.tail.ts.net:8443", "https://host.example:8443", "https://host.tail.ts.net:8443/path",
            "https://token@host.tail.ts.net:8443", "https://host.tail.ts.net:443", "https://host.tail.ts.net:8443?token=x").forEach {
            assertTrue(runCatching { SyncTransport.validateEndpoint(it) }.isFailure)
        }
        val token = "ab".repeat(32)
        val alias = "sync-fixture-${UUID.randomUUID()}"
        val keystore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val encrypted = SyncCredential.encrypt(token, alias)
            assertFalse(encrypted.contains(token)); assertEquals(token, SyncCredential.decrypt(encrypted, alias))
            assertTrue(runCatching { SyncCredential.decrypt(encrypted.dropLast(2), alias) }.isFailure)
            keystore.deleteEntry(alias)
            assertTrue(runCatching { SyncCredential.decrypt(encrypted, alias) }.isFailure)
            assertFalse(keystore.containsAlias(alias))
        } finally { if (keystore.containsAlias(alias)) keystore.deleteEntry(alias) }
    }
}
