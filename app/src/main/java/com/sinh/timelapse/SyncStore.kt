package com.sinh.timelapse

import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

/** Only SyncCoordinator's executor may access this store. Never owns work/media stores. */
internal class SyncStore(files: File, private val mayInitialize: () -> Boolean = { true }, private val fault: (String) -> Unit = {}) {
    init { SyncThreads.io() }
    private val root = File(files.canonicalFile, "private-sync")
    private val file = File(root, "state.json")
    private var known: ByteArray? = null
    private var state: JSONObject? = null
    private var blocked = false

    private fun paths() {
        check(!Files.isSymbolicLink(root.toPath()) && root.canonicalFile == root.absoluteFile)
        if (root.exists()) check(root.isDirectory && (Os.lstat(root.path).st_mode and 511) == 448 && root.listFiles()!!.all {
            it.name == "state.json" && it.isFile && !Files.isSymbolicLink(it.toPath()) &&
                Os.lstat(it.path).st_nlink == 1L && (Os.lstat(it.path).st_mode and 511) == 384
        }) { "Unknown/interrupted sync files retained; recovery required" }
    }
    fun load(): JSONObject {
        SyncThreads.io()
        check(!blocked) { "Sync recovery required; bytes retained" }
        try {
            paths()
            val bytes = if (file.exists()) file.readBytes() else null
            check(bytes != null || !root.exists()) { "Initialized sync state missing; retained root requires recovery" }
            if (state != null) check(bytes.contentEquals(known)) { "Sync bytes changed outside owner" }
            if (state == null) {
                check(bytes != null || mayInitialize()) { "Existing credential identity without sync state; recovery required" }
                state = if (bytes == null) JSONObject().put("version", 1)
                    .put("device", UUID.randomUUID().toString()).put("enabled", false)
                    .put("endpoint", "").put("credential", "").put("pending", JSONArray())
                    .put("sources", JSONObject()).put("exports", JSONObject())
                    .put("history", JSONObject().put("records", JSONArray()).put("videos", JSONArray()))
                    .put("lastSuccess", 0).put("lowStorage", false)
                else JSONObject(bytes.toString(Charsets.UTF_8)).also {
                    check(it.getInt("version") == 1) { "Unsupported sync version retained" }
                    check(it.toString().toByteArray().contentEquals(bytes)) { "Ambiguous sync state retained" }
                }
                validate(checkNotNull(state)); known = bytes
                if (bytes == null) commit(checkNotNull(state)) // Identity durable before enrollment.
            }
            return JSONObject(checkNotNull(state).toString())
        } catch (e: Exception) { blocked = true; throw e }
    }
    private fun validate(s: JSONObject) {
        check(s.keys().asSequence().toSet() == setOf("version", "device", "enabled", "endpoint", "credential",
            "pending", "sources", "exports", "history", "lastSuccess", "lowStorage"))
        check(s.get("version") == 1)
        check(UUID.fromString(s.getString("device")).toString() == s.getString("device"))
        check(s.get("enabled") is Boolean); check(s.get("endpoint") is String); check(s.get("credential") is String)
        check((s.get("lastSuccess") is Long || s.get("lastSuccess") is Int) && s.getLong("lastSuccess") >= 0)
        check(s.get("lowStorage") is Boolean)
        s.getJSONObject("sources"); s.getJSONObject("exports"); s.getJSONObject("history")
        val ids = mutableSetOf<String>()
        s.getJSONArray("pending").objects().forEach {
            check(it.keys().asSequence().toSet() == setOf("mutation", "path", "body") ||
                (it.keys().asSequence().toSet() == setOf("mutation", "path", "body", "rejected", "status") && it.get("rejected") == true && it.get("status") == 400))
            val id = it.getString("mutation")
            check(UUID.fromString(id).toString() == id && ids.add(id))
            check(it.getString("path") == "/v1/source" || it.getString("path") == "/v1/patch")
            check(it.getString("body").toByteArray().size <= 2 * 1024 * 1024)
        }
    }
    fun commit(next: JSONObject) {
        SyncThreads.io()
        check(!blocked); validate(next); paths()
        check((if (file.exists()) file.readBytes() else null).contentEquals(known)) { "External sync change retained" }
        check(root.isDirectory || root.mkdir()); Os.chmod(root.path, 448)
        val tmp = File(root, "state.new")
        val bytes = next.toString().toByteArray(Charsets.UTF_8)
        try {
            check(tmp.createNewFile()); Os.chmod(tmp.path, 384)
            FileOutputStream(tmp).use { it.write(bytes); fault("write"); it.fd.sync() }
            fault("sync"); Os.rename(tmp.path, file.path); fault("rename")
            val fd = Os.open(root.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
            fault("directory-sync")
            known = bytes; state = JSONObject(next.toString())
        } catch (e: Exception) { blocked = true; throw e }
    }
    fun update(change: (JSONObject) -> Unit) { val next = load(); change(next); commit(next) }
    fun enqueue(path: String, body: String) = update { enqueue(it, path, body) }
    companion object {
        fun enqueue(state: JSONObject, path: String, body: String) {
            state.getJSONArray("pending").put(JSONObject().put("mutation", UUID.randomUUID().toString())
                .put("path", path).put("body", body))
        }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.strings(): List<String> = keys().asSequence().toList()
internal fun json(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { o.put(it.first, it.second ?: JSONObject.NULL) } }
