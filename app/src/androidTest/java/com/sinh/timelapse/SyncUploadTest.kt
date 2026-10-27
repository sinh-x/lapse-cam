package com.sinh.timelapse

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Deterministic protocol fixtures: no network, Gallery, camera, timers or production sync owner. */
class SyncUploadTest {
    private val device = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun item(data: ByteArray) = json("description" to json("size" to data.size, "sha256" to SyncUpload.sha(data), "recording" to ""),
        "create" to UUID.randomUUID().toString(), "finish" to UUID.randomUUID().toString(), "missingMutation" to UUID.randomUUID().toString(),
        "complete" to false, "missing" to false, "offset" to 0, "error" to "")
    private inner class Server(private val data: ByteArray) : SyncHttp {
        var manifest: JSONObject? = null
        var lose: String? = null
        var puts = 0; var creates = 0; var completes = 0; var largest = 0
        val received = java.io.ByteArrayOutputStream()
        val mutations = mutableMapOf<String, String>()
        override fun request(method: String, path: String, body: ByteArray?, mutation: String?, headers: Map<String, String>): ByteArray {
            val suffix = path.removePrefix("/v1/uploads/$id")
            if (method == "GET") return manifest?.toString()?.toByteArray() ?: throw SyncTransport.HttpError(404)
            val operation = when {
                method == "PUT" -> {
                    puts++; val m = checkNotNull(manifest); val bytes = checkNotNull(body)
                    check(headers["X-Offset"] == received.size().toString()); check(headers["X-Chunk-SHA256"] == SyncUpload.sha(bytes))
                    largest = maxOf(largest, bytes.size); received.write(bytes)
                    m.getJSONArray("chunks").put(json("size" to bytes.size, "sha256" to SyncUpload.sha(bytes)))
                    m.put("offset", received.size()); "chunk"
                }
                suffix == "" -> {
                    creates++
                    manifest = JSONObject(String(checkNotNull(body))).put("id", id).put("device", device).put("offset", 0)
                        .put("chunks", JSONArray()).put("complete", false).put("missing", false).put("syncedAt", 0)
                    "create"
                }
                suffix == "/complete" -> {
                    completes++; check(received.toByteArray().contentEquals(data))
                    checkNotNull(manifest).put("complete", true); "finish"
                }
                suffix == "/missing" -> { checkNotNull(manifest).put("missing", true); "missingMutation" }
                else -> error("Unexpected fixture operation")
            }
            if (operation != "chunk") {
                val key = checkNotNull(mutation)
                val old = mutations.putIfAbsent(operation, key); check(old == null || old == key)
            }
            if (lose == operation) { lose = null; throw IOException("Lost fixture acknowledgment") }
            return checkNotNull(manifest).toString().toByteArray()
        }
    }
    @Test fun lostBeginChunkAndCompleteRepliesReconcileSameLogicalIdentity() {
        val data = ByteArray(2 * 1024 * 1024 + 7) { (it % 251).toByte() }
        for (boundary in listOf("create", "chunk", "finish")) {
            val queue = item(data); val server = Server(data); server.lose = boundary
            var offset: Long? = null
            repeat(8) {
                if (!queue.getBoolean("complete")) runCatching {
                    offset = SyncUpload.step(server, id, queue, device, { data.inputStream() }, offset) { it(queue) }.verifiedOffset
                }
            }
            assertTrue(boundary, queue.getBoolean("complete")); assertArrayEquals(data, server.received.toByteArray())
            assertEquals(1, server.creates); assertEquals(3, server.puts); assertEquals(1, server.completes)
            assertEquals(queue.getString("create"), server.mutations["create"])
            assertEquals(queue.getString("finish"), server.mutations["finish"])
            assertTrue(server.largest <= 1024 * 1024)
        }
    }
    @Test fun changedPrefixOrNamespaceCannotReuseProgress() {
        val data = ByteArray(1024 * 1024 + 1) { 42 }
        val queue = item(data); val server = Server(data)
        SyncUpload.step(server, id, queue, device, { data.inputStream() }, null) { it(queue) }
        val changed = data.copyOf().apply { this[0] = 43 }
        assertTrue(runCatching { SyncUpload.step(server, id, queue, device, { changed.inputStream() }, null) { it(queue) } }.isFailure)
        assertEquals(1, server.puts); assertFalse(queue.getBoolean("complete"))
        checkNotNull(server.manifest).put("device", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        assertTrue(runCatching { SyncUpload.step(server, id, queue, device, { data.inputStream() }, null) { it(queue) } }.isFailure)
        assertEquals(1, server.puts)
    }
    @Test fun sourceDisappearanceNeverFabricatesAnArchive() {
        val data = byteArrayOf(1, 2, 3); val queue = item(data); val server = Server(data)
        SyncUpload.step(server, id, queue, device, null, null) { it(queue) }
        assertTrue(queue.getBoolean("missing")); assertFalse(queue.getBoolean("complete")); assertEquals(0, server.creates)
    }
    @Test fun acknowledgedArchiveSurvivesMissingSource() {
        val data = byteArrayOf(1, 2, 3); val queue = item(data); val server = Server(data)
        repeat(2) { SyncUpload.step(server, id, queue, device, { data.inputStream() }, null) { it(queue) } }
        assertTrue(queue.getBoolean("complete"))
        SyncUpload.step(server, id, queue, device, null, null) { it(queue) }
        assertTrue(queue.getBoolean("missing")); assertTrue(queue.getBoolean("complete"))
        assertTrue(checkNotNull(server.manifest).getBoolean("complete")); assertArrayEquals(data, server.received.toByteArray())
    }
    @Test fun localCommitFailureDoesNotInventSuccessAndLostReceiptIsRecoverable() {
        val data = byteArrayOf(1, 2, 3); val queue = item(data); val server = Server(data)
        assertTrue(runCatching { SyncUpload.step(server, id, queue, device, { data.inputStream() }, null) { error("Injected durable commit failure") } }.isFailure)
        assertFalse(queue.getBoolean("complete")); assertEquals(0L, queue.getLong("offset"))
        SyncUpload.step(server, id, queue, device, { data.inputStream() }, null) { it(queue) }
        assertTrue(queue.getBoolean("complete")); assertEquals(1, server.puts); assertEquals(1, server.creates)
    }
}
