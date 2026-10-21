package com.sinh.timelapse

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal enum class SessionPhase {
    PAUSED, ACTIVE, CLOSING, QUEUED, EXPORTING, VERIFYING, CLEANUP, FINISHED, EMPTY, NEEDS_ATTENTION, DELETING, DELETED
}

/** Compact disk-backed summaries. Full source inventories live separately, one per closed session. */
internal data class SessionRecord(
    val id: String,
    val phase: SessionPhase = SessionPhase.PAUSED,
    val successor: String? = null,
    val operation: String = UUID.randomUUID().toString(),
    val count: Int = 0,
    val selected: Int = 0,
    val bytes: Long = 0,
    val active: Long = 0,
    val unknownTail: Boolean = false,
    val stage: Long = 0,
    val exportCycle: AttemptCycle = AttemptCycle(),
    val cleanupCycle: AttemptCycle = AttemptCycle(),
    val cleanupOnly: Boolean = false,
    val output: String? = null,
    val receipt: String? = null,
    val error: String = "",
) {
    val busy: Boolean get() = phase in setOf(SessionPhase.ACTIVE, SessionPhase.CLOSING,
        SessionPhase.EXPORTING, SessionPhase.VERIFYING, SessionPhase.CLEANUP, SessionPhase.DELETING)
    val cycle: AttemptCycle get() = if (cleanupOnly) cleanupCycle else exportCycle
    fun withCycle(cycle: AttemptCycle) = if (cleanupOnly) copy(cleanupCycle = cycle) else copy(exportCycle = cycle)
    fun withSnapshot(value: SessionStore.Snapshot) = copy(count = value.count, selected = value.selected,
        bytes = value.bytes, active = value.activeMillis ?: 0, unknownTail = value.unknownTail, stage = value.stage)
}

/** Every call is confined to the model's serial file/transition executor, never the codec executor. */
internal class SessionRepository(
    filesDirectory: File,
    val store: SessionStore,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val checkpoint: (String) -> Unit = {},
) {
    private val root = File(filesDirectory.canonicalFile, "sessions")
    private val index = File(filesDirectory.canonicalFile, "session-index.json")
    private var records = linkedMapOf<String, SessionRecord>()
    private val headers = mutableMapOf<String, CaptureSession>()
    private val remainingCache = mutableMapOf<String, Pair<Int, Long>>()
    fun records(): List<SessionRecord> = records.values.toList()
    fun get(id: String): SessionRecord = checkNotNull(records[id]) { "Unknown session $id" }
    fun session(record: SessionRecord, recover: Boolean = false): CaptureSession =
        if (recover) store.load(File(root, record.id)).also { headers[record.id] = it }
        else headers.getOrPut(record.id) { store.load(File(root, record.id), false) }
    fun current(): CaptureSession? = records.values.singleOrNull {
        it.phase in setOf(SessionPhase.ACTIVE, SessionPhase.PAUSED, SessionPhase.CLOSING)
    }?.let { session(it, true) }
    fun put(record: SessionRecord) {
        val old = records[record.id]
        commit(LinkedHashMap(records).apply { put(record.id, record) })
        if (old == null || old.count != record.count || old.bytes != record.bytes) remainingCache.remove(record.id)
    }
    private fun commit(next: LinkedHashMap<String, SessionRecord>) {
        check(next.values.count { it.phase in setOf(SessionPhase.ACTIVE, SessionPhase.PAUSED, SessionPhase.CLOSING) } <= 1) {
            "Conflicting capture owners; retained"
        }
        atomicJson(index, JSONObject().put("version", 1).put("records", JSONArray().also { array ->
            next.values.forEach { array.put(encode(it)) }
        }))
        records = next
        checkpoint("index")
    }

    fun recover() {
        headers.clear()
        remainingCache.clear()
        check(index.exists() || File(index.path + ".bak").exists() || records.isEmpty()) { "Session index disappeared; retain and reconcile" }
        if (index.exists() || File(index.path + ".bak").exists()) {
            val json = readJson(index)
            check(json.get("version") == 1)
            val array = json.getJSONArray("records")
            val loaded = linkedMapOf<String, SessionRecord>()
            for (i in 0 until array.length()) {
                val record = decode(array.getJSONObject(i))
                check(loaded.put(record.id, record) == null) { "Duplicate session identity" }
            }
            records = loaded
        }
        val successorIds = records.values.mapNotNull { it.successor }.toSet()
        // A crash after header publication but before initial registration adopts only explicit opt-in.
        store.directories().filter { it.name !in records && it.name !in successorIds }.forEach { directory ->
            val session = store.load(directory, false)
            if (session.split != SessionSplit.OFF) {
                val recovered = store.load(directory)
                val progress = readJson(File(directory, "progress.json"))
                check(store.snapshot(recovered).count == 0 && integer(progress, "nextId") == 0L && integer(progress, "active") == 0L &&
                    directory.listFiles()!!.all { it.name in setOf("session.json", "progress.json") }) {
                    "Unindexed nonempty/closed session; retain data and reconcile its original operation identity"
                }
                register(recovered)
            }
        }
        records.values.filter { it.phase == SessionPhase.CLOSING }.toList().forEach { finishClose(it) }
        records.values.filter { it.phase == SessionPhase.ACTIVE }.toList().forEach { put(it.copy(phase = SessionPhase.PAUSED)) }
        check(records.values.count { it.phase == SessionPhase.PAUSED } <= 1)
        records.values.filter { it.phase == SessionPhase.PAUSED }.forEach { session(it, true) }
    }

    fun register(session: CaptureSession) {
        require(session.split != SessionSplit.OFF)
        put(SessionRecord(session.directory.name).withSnapshot(store.snapshot(session)))
    }
    fun refresh(session: CaptureSession) {
        if (session.split != SessionSplit.OFF) put(get(session.directory.name).withSnapshot(store.snapshot(session)))
    }
    fun checkpointSession(session: CaptureSession, running: Boolean) {
        if (session.split == SessionSplit.OFF) return
        val old = get(session.directory.name)
        check(old.phase == SessionPhase.PAUSED || old.phase == SessionPhase.ACTIVE)
        put(old.withSnapshot(store.snapshot(session)).copy(phase = if (running) SessionPhase.ACTIVE else SessionPhase.PAUSED))
    }

    /** Caller has stopped admission and drained the one pre-boundary frame before this intent. */
    fun close(session: CaptureSession, successor: Boolean): CaptureSession? {
        val old = get(session.directory.name)
        if (old.phase == SessionPhase.CLOSING) return finishClose(old)
        if (old.phase != SessionPhase.ACTIVE && old.phase != SessionPhase.PAUSED) return old.successor?.let { this.session(get(it)) }
        val next = if (successor) "${epochMillis()}-${UUID.randomUUID()}" else null
        val closing = old.withSnapshot(store.snapshot(session)).copy(phase = SessionPhase.CLOSING, successor = next)
        put(closing) // Durable successor identity precedes folder creation.
        checkpoint("closing")
        return finishClose(closing)
    }

    private fun finishClose(record: SessionRecord): CaptureSession? {
        val old = session(record, true)
        val snapshot = store.snapshot(old)
        // Phase 3 consumes this immutable selected list; never resolves latest() from an export callback.
        val selected = store.exportFrames(old)
        freezeInventory(old)
        val manifest = JSONObject().put("version", 1).put("session", record.id).put("operation", record.operation)
            .put("fps", old.fps).put("cap", (old.configuration as SamplingConfiguration.Adaptive).maxSeconds)
            .put("selected", JSONArray(selected.map { it.name }))
            .put("inventory", sha256(File(old.directory, "sources.tsv")))
        val target = File(old.directory, "closed.json")
        if (target.exists() || File(target.path + ".bak").exists()) check(readJson(target).toString() == manifest.toString()) { "Closed selection changed" }
        else atomicJson(target, manifest)
        checkpoint("selection-frozen")
        val next = record.successor?.let { identity ->
            store.create(old.configuration, old.lens, old.appearance, old.split, identity, old.zoneId)
        }
        checkpoint("successor-created")
        val updated = LinkedHashMap(records)
        updated[record.id] = record.withSnapshot(snapshot).copy(phase = if (snapshot.count == 0) SessionPhase.EMPTY else SessionPhase.QUEUED)
        next?.let { updated[it.directory.name] = SessionRecord(it.directory.name).withSnapshot(store.snapshot(it)) }
        commit(updated) // Old closure, queue publication and exactly one successor become visible together.
        store.release(old)
        return next
    }

    fun closedSelection(record: SessionRecord): Pair<CaptureSession, List<File>> {
        check(record.phase !in setOf(SessionPhase.ACTIVE, SessionPhase.PAUSED, SessionPhase.CLOSING))
        val session = session(record)
        val json = readJson(File(session.directory, "closed.json"))
        check(json.get("version") == 1 && json.getString("session") == record.id && json.getString("operation") == record.operation)
        check(json.getInt("fps") == session.fps && json.getInt("cap") == (session.configuration as SamplingConfiguration.Adaptive).maxSeconds)
        val names = json.getJSONArray("selected")
        val files = (0 until names.length()).map { index -> ownedFile(session, names.getString(index)) }
        check(files.size == record.selected && files.distinct().size == files.size && files.zipWithNext().all { it.first.name < it.second.name })
        return session to files
    }

    private fun freezeInventory(session: CaptureSession) {
        val inventory = File(session.directory, "sources.tsv")
        if (inventory.exists() || File(inventory.path + ".bak").exists()) {
            AtomicFile(inventory).openRead().close()
            return
        }
        val photos = session.directory.listFiles()!!.filter { it.name.matches(Regex("frame_[0-9]{8,19}_[0-9]+\\.jpg")) }
        val snapshot = store.snapshot(session)
        check(photos.size == snapshot.count && photos.sumOf { it.length() } == snapshot.bytes) { "Source inventory changed" }
        val atomic = AtomicFile(inventory)
        val stream = atomic.startWrite()
        try {
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            session.directory.listFiles()!!.sortedBy { it.name }.filter { isSourceName(it.name) && store.ownsSource(session, it) }.forEach { file ->
                check(file.isFile && file.canonicalFile == file.absoluteFile) { "Aliased source; retained" }
                writer.write("${file.name}\t${file.length()}\t${sha256(file)}\n")
            }
            writer.flush()
            atomic.finishWrite(stream)
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
        checkpoint("inventory-frozen")
    }

    /** Receipt binds both frozen selection and all retained selected/unselected sources. */
    fun receipt(record: SessionRecord, verified: VideoExporter.PublishedVerification): SessionRecord {
        val session = session(record)
        val manifest = File(session.directory, "closed.json")
        val inventory = File(session.directory, "sources.tsv")
        check(readJson(manifest).getString("inventory") == sha256(inventory))
        check(verified.count == record.selected && verified.fps == session.fps &&
            verified.cap == (session.configuration as SamplingConfiguration.Adaptive).maxSeconds)
        val json = JSONObject().put("version", 1).put("verified", true).put("session", record.id).put("operation", record.operation)
            .put("selection", sha256(manifest)).put("inventory", sha256(inventory))
            .put("uri", verified.uri.toString()).put("outputSha256", verified.sha256)
            .put("count", verified.count).put("fps", verified.fps).put("cap", verified.cap)
        atomicJson(File(session.directory, "receipt.json"), json)
        checkpoint("receipt")
        return record.copy(phase = SessionPhase.CLEANUP, cleanupOnly = true, output = verified.uri.toString(),
            receipt = sha256(File(session.directory, "receipt.json")), exportCycle = record.exportCycle.copy(reserved = false),
            cleanupCycle = AttemptCycle(), error = "")
    }

    fun validateReceipt(record: SessionRecord, verified: VideoExporter.PublishedVerification) {
        check(record.cleanupOnly && record.receipt != null && record.output == verified.uri.toString())
        val session = session(record)
        val receipt = File(session.directory, "receipt.json")
        check(sha256(receipt) == record.receipt) { "Receipt changed; sources retained" }
        val json = readJson(receipt)
        check(json.get("version") == 1 && json.get("verified") == true && json.getString("session") == record.id && json.getString("operation") == record.operation &&
            json.getString("uri") == verified.uri.toString() && json.getString("outputSha256") == verified.sha256 &&
            json.getInt("count") == verified.count && json.getInt("fps") == verified.fps && json.getInt("cap") == verified.cap &&
            json.getString("selection") == sha256(File(session.directory, "closed.json")) &&
            json.getString("inventory") == sha256(File(session.directory, "sources.tsv"))) { "Contradictory receipt; sources retained" }
    }

    /** Single bounded owner step. Output has just been reverified on the separate media worker. */
    fun cleanup(record: SessionRecord, verified: VideoExporter.PublishedVerification): Boolean {
        check(record.phase == SessionPhase.CLEANUP && record.cycle.reserved)
        validateReceipt(record, verified)
        return deleteInventory(record)
    }

    private fun isSourceName(name: String) = name.matches(Regex("frame_[0-9]{8,19}_[0-9]+\\.jpg|intent_[0-9]+\\.json(?:\\.bak)?"))

    /** Exact inventory only; progress is durable BEFORE deletion, making missing-after-delete replay safe. */
    private fun deleteInventory(record: SessionRecord): Boolean {
        remainingCache.remove(record.id)
        val session = session(record)
        val directory = session.directory
        val inventory = File(directory, "sources.tsv")
        check(readJson(File(directory, "closed.json")).getString("inventory") == sha256(inventory)) { "Inventory changed; stopped" }
        val knownMetadata = setOf("session.json", "progress.json", "closed.json", "sources.tsv", "receipt.json", "cleanup.json")
        val names = HashSet<String>()
        inventory.forEachLine { line -> check(names.add(line.substringBefore('\t'))) { "Duplicate source inventory entry" } }
        // Unknown files and ALL aliases block before any deletion in this step.
        check(directory.listFiles()!!.all { file ->
            file.isFile && file.canonicalFile == file.absoluteFile &&
                (file.name in names || file.name.removeSuffix(".bak") in knownMetadata)
        }) { "Unknown or aliased session file; cleanup stopped" }
        val cursorFile = File(directory, "cleanup.json")
        val pending = if (cursorFile.exists() || File(cursorFile.path + ".bak").exists()) readJson(cursorFile) else
            JSONObject().put("version", 1).put("operation", record.operation).put("inventory", sha256(inventory))
                .put("next", 0).put("pending", false).put("remaining", record.count).put("bytes", record.bytes)
        check(pending.get("version") == 1 && pending.getString("operation") == record.operation && pending.getString("inventory") == sha256(inventory))
        var next = Math.toIntExact(integer(pending, "next"))
        check(next >= 0)
        var lines = 0
        var deleted = 0
        inventory.bufferedReader().use { reader ->
          while (true) {
            val line = reader.readLine() ?: break
            if (deleted == 16) return false
            val index = lines++
            val parts = line.split('\t')
            check(parts.size == 3 && isSourceName(parts[0]) && parts[1].toLong() >= 0 && parts[2].matches(Regex("[0-9a-f]{64}")))
            val file = File(directory, parts[0])
            check(file.canonicalFile == file.absoluteFile)
            if (index < next) check(!file.exists()) { "Deleted source reappeared; stopped" }
            else {
                check(index == next)
                val interrupted = pending.get("pending") == true
                if (file.exists()) check(file.isFile && file.length() == parts[1].toLong() && sha256(file) == parts[2]) { "Source inventory changed; stopped" }
                else check(interrupted) { "Unexplained missing source; stopped" }
                pending.put("pending", true)
                atomicJson(cursorFile, pending)
                checkpoint("before-source-delete")
                check(!file.exists() || file.delete()) { "Cannot delete inventoried source" }
                checkpoint("source-deleted")
                next++
                if (file.name.endsWith(".jpg")) {
                    pending.put("remaining", integer(pending, "remaining") - 1)
                    pending.put("bytes", integer(pending, "bytes") - parts[1].toLong())
                    check(integer(pending, "remaining") >= 0 && integer(pending, "bytes") >= 0)
                }
                pending.put("next", next).put("pending", false)
                atomicJson(cursorFile, pending)
                checkpoint("cleanup-checkpoint")
                deleted++
            }
          }
        }
        check(next == lines)
        return true
    }

    /** Only after terminal state is durably committed; keep header, summary and verified receipt. */
    fun compact(id: String) {
        val record = get(id)
        check(record.phase == SessionPhase.FINISHED || record.phase == SessionPhase.DELETED)
        runCatching {
            val directory = session(record).directory
            listOf("closed.json", "sources.tsv", "cleanup.json", "progress.json").forEach { name ->
                listOf(name, "$name.bak").forEach { exact ->
                    val file = File(directory, exact)
                    if (file.exists()) {
                        check(file.isFile && file.canonicalFile == file.absoluteFile) { "Aliased summary metadata; retained" }
                        check(file.delete()) { "Could not compact source metadata; retained" }
                    }
                }
            }
        }.onFailure { android.util.Log.w("SessionRepository", "Terminal source metadata retained for $id", it) }
    }

    fun remaining(record: SessionRecord): Pair<Int, Long> {
        if (record.phase in setOf(SessionPhase.FINISHED, SessionPhase.DELETED)) return 0 to 0L
        remainingCache[record.id]?.let { return it }
        val cursor = File(session(record).directory, "cleanup.json")
        if (!cursor.exists() && !File(cursor.path + ".bak").exists()) return (record.count to record.bytes).also { remainingCache[record.id] = it }
        val json = readJson(cursor)
        check(json.getString("operation") == record.operation)
        val count = Math.toIntExact(integer(json, "remaining"))
        val bytes = integer(json, "bytes")
        check(count in 0..record.count && bytes in 0..record.bytes)
        return (count to bytes).also { remainingCache[record.id] = it }
    }

    fun cleanupComplete(record: SessionRecord): Boolean {
        val directory = session(record).directory
        val cursor = File(directory, "cleanup.json")
        if (!cursor.exists() && !File(cursor.path + ".bak").exists()) return false
        val json = readJson(cursor)
        val inventory = File(directory, "sources.tsv")
        var count = 0
        inventory.forEachLine { count++ }
        return json.get("version") == 1 && json.getString("operation") == record.operation &&
            json.getString("inventory") == sha256(inventory) && integer(json, "next") == count.toLong() && json.get("pending") == false
    }

    /** Legacy/manual sessions enter the index ONLY on an explicit confirmed source-delete action. */
    fun deleteManual(session: CaptureSession, output: String? = null) {
        check(session.split == SessionSplit.OFF && records[session.directory.name] == null)
        val record = SessionRecord(session.directory.name, phase = SessionPhase.DELETING, output = output).withSnapshot(store.snapshot(session))
        freezeInventory(session)
        atomicJson(File(session.directory, "closed.json"), JSONObject().put("version", 1).put("session", record.id)
            .put("operation", record.operation).put("inventory", sha256(File(session.directory, "sources.tsv"))))
        put(record) // Confirmation/identity persists before any deletion; never auto-export this record.
        store.release(session)
        if (deleteInventory(record)) { put(record.copy(phase = SessionPhase.DELETED)); compact(record.id) }
    }

    /** Confirmed user action; no Gallery effect. Eligibility is cancelled durably before sources. */
    fun deleteSources(id: String) {
        var record = get(id)
        check(!record.busy && !record.cycle.reserved && record.phase != SessionPhase.PAUSED) { "Session is active or busy" }
        if (record.phase != SessionPhase.DELETED) {
            record = record.copy(phase = SessionPhase.DELETING)
            put(record)
        }
        if (deleteInventory(record)) { put(record.copy(phase = SessionPhase.DELETED, error = "")); compact(record.id) }
    }

    fun continueDelete(id: String): Boolean {
        val record = get(id)
        check(record.phase == SessionPhase.DELETING)
        val done = deleteInventory(record)
        if (done) { put(record.copy(phase = SessionPhase.DELETED, error = "")); compact(record.id) }
        return done
    }

    private fun encode(record: SessionRecord): JSONObject = JSONObject().put("id", record.id).put("phase", record.phase.name)
        .put("successor", record.successor).put("operation", record.operation).put("count", record.count).put("selected", record.selected)
        .put("bytes", record.bytes).put("active", record.active).put("unknown", record.unknownTail).put("stage", record.stage)
        .put("export", encodeCycle(record.exportCycle)).put("cleanup", encodeCycle(record.cleanupCycle))
        .put("cleanupOnly", record.cleanupOnly).put("output", record.output).put("receipt", record.receipt).put("error", record.error)
    private fun encodeCycle(cycle: AttemptCycle) = JSONObject().put("attempts", cycle.attempts)
        .put("remaining", cycle.remainingMillis).put("reserved", cycle.reserved)
    private fun decodeCycle(json: JSONObject) = AttemptCycle(Math.toIntExact(integer(json, "attempts")), integer(json, "remaining"), boolean(json, "reserved"))
    private fun decode(json: JSONObject): SessionRecord {
        val id = json.getString("id")
        require(id.matches(Regex("[0-9]+-[0-9a-f-]{36}")))
        require(UUID.fromString(id.substringAfter('-')).toString() == id.substringAfter('-'))
        val operation = json.getString("operation")
        require(UUID.fromString(operation).toString() == operation)
        val record = SessionRecord(id, SessionPhase.valueOf(json.getString("phase")), optional(json, "successor"), operation,
            Math.toIntExact(integer(json, "count")), Math.toIntExact(integer(json, "selected")), integer(json, "bytes"), integer(json, "active"),
            boolean(json, "unknown"), integer(json, "stage"), decodeCycle(json.getJSONObject("export")), decodeCycle(json.getJSONObject("cleanup")),
            boolean(json, "cleanupOnly"), optional(json, "output"), optional(json, "receipt"), json.getString("error"))
        require(record.count >= 0 && record.selected in 0..record.count && record.bytes >= 0 && record.active >= 0 && record.stage >= 0)
        record.successor?.let {
            require(it.matches(Regex("[0-9]+-[0-9a-f-]{36}")) && it != id)
            require(UUID.fromString(it.substringAfter('-')).toString() == it.substringAfter('-'))
        }
        require(!record.cleanupOnly || (record.output != null && record.receipt != null))
        return record
    }

    companion object {
        fun atomicJson(file: File, json: JSONObject) {
            val atomic = AtomicFile(file)
            check(file.canonicalFile == file.absoluteFile) { "Aliased metadata; retained" }
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
            check(atomic.readFully().contentEquals(bytes)) { "Metadata commit could not be confirmed; retained" }
        }
        fun readJson(file: File) = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        fun integer(json: JSONObject, key: String): Long {
            val value = json.get(key)
            check(value is Int || value is Long) { "Invalid integer $key" }
            return (value as Number).toLong()
        }
        private fun boolean(json: JSONObject, key: String) = json.get(key) as? Boolean ?: error("Invalid boolean $key")
        private fun optional(json: JSONObject, key: String) = if (json.has(key)) json.get(key) as? String ?: error("Invalid $key") else null
        fun ownedFile(session: CaptureSession, name: String): File {
            require(name.matches(Regex("frame_[0-9]{19}_[0-9]+\\.jpg"))) { "Unknown source name" }
            return File(session.directory, name).also {
                check(it.canonicalFile == it.absoluteFile && it.parentFile == session.directory) { "Aliased source; retained" }
            }
        }
        fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
