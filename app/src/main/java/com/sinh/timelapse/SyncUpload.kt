package com.sinh.timelapse

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

internal interface SyncHttp {
    fun request(method: String, path: String, body: ByteArray? = null, mutation: String? = null,
        headers: Map<String, String> = emptyMap()): ByteArray
    fun get(path: String) = JSONObject(String(request("GET", path), Charsets.UTF_8))
}

/** One bounded resumable step. Every ID/description is already durable before this is called. */
internal object SyncUpload {
    data class Result(val progressed: Boolean, val verifiedOffset: Long?)
    fun step(client: SyncHttp, id: String, item: JSONObject, device: String, open: (() -> InputStream)?,
        verifiedOffset: Long?, commit: ((JSONObject) -> Unit) -> Unit): Result {
        SyncPolicy.key(device, id)
        val path = "/v1/uploads/$id"
        val desc = item.getJSONObject("description")
        var remote = try { client.get(path) } catch (e: SyncTransport.HttpError) {
            if (e.status != 404) throw e
            if (open == null) { commit { it.put("missing", true) }; return Result(false, null) }
            JSONObject(String(client.request("POST", path, desc.toString().toByteArray(), item.getString("create")), Charsets.UTF_8))
        }
        check(remote.getLong("size") == desc.getLong("size") && remote.getString("sha256") == desc.getString("sha256") &&
            remote.getString("recording") == desc.getString("recording") && remote.getString("id") == id && remote.getString("device") == device)
        if (remote.getBoolean("complete")) commit { it.put("complete", true).put("offset", desc.getLong("size")).put("error", "") }
        if (open == null) {
            client.request("POST", "$path/missing", ByteArray(0), item.getString("missingMutation"))
            commit { it.put("missing", true) }; return Result(false, null)
        }
        if (remote.getBoolean("complete")) return Result(false, desc.getLong("size"))
        val offset = remote.getLong("offset")
        check(offset in 0..desc.getLong("size"))
        open().use { input ->
            if (verifiedOffset != offset) {
                var verified = 0L
                (remote.optJSONArray("chunks") ?: JSONArray()).objects().forEach { chunk ->
                    val length = chunk.getInt("size"); check(length in 1..8 * 1024 * 1024)
                    val bytes = input.readNBytes(length); check(bytes.size == length)
                    check(sha(bytes) == chunk.getString("sha256")); verified = Math.addExact(verified, length.toLong())
                }
                check(verified == offset)
            } else {
                var skipped = 0L
                while (skipped < offset) {
                    val n = input.skip(offset - skipped)
                    if (n == 0L) { check(input.read() != -1); skipped++ } else skipped += n
                }
            }
            if (offset < desc.getLong("size")) {
                val length = minOf(1024 * 1024L, desc.getLong("size") - offset).toInt()
                val bytes = input.readNBytes(length); check(bytes.size == length)
                remote = JSONObject(String(client.request("PUT", "$path/chunk", bytes, headers = mapOf(
                    "Content-Type" to "application/octet-stream", "X-Offset" to offset.toString(), "X-Chunk-SHA256" to sha(bytes))), Charsets.UTF_8))
                check(remote.getLong("offset") == offset + length)
                commit { it.put("offset", offset + length).put("error", "") }
                return Result(true, offset + length)
            }
            remote = JSONObject(String(client.request("POST", "$path/complete", ByteArray(0), item.getString("finish")), Charsets.UTF_8))
            check(remote.getBoolean("complete") && remote.getString("sha256") == desc.getString("sha256") &&
                remote.getLong("size") == desc.getLong("size") && remote.getString("id") == id && remote.getString("device") == device)
            commit { it.put("complete", true).put("offset", offset).put("error", "") }
            return Result(true, offset)
        }
    }
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
