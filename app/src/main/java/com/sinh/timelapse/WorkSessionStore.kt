package com.sinh.timelapse

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Confined to WorkSessionCoordinator's single executor. Nothing in sessions/ is owned here. */
internal class WorkSessionStore(files: File, private val fault: (String) -> Unit = {}) {
    private val parent = files.canonicalFile
    private val root = File(parent, "work-context")
    private val target = File(root, "state.json")
    private var loaded = false
    private var existed = false
    private var knownBytes: ByteArray? = null
    private var blocked: Exception? = null
    var snapshot = WorkSnapshot(); private set

    private fun safePaths() {
        check(!Files.isSymbolicLink(root.toPath()) && root.canonicalFile == root.absoluteFile) { "Aliased work root retained" }
        if (root.exists()) {
            check(root.isDirectory)
            check(root.listFiles()!!.all { file ->
                file.name in setOf("state.json", "state.json.new") && file.isFile &&
                    !Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile &&
                    android.system.Os.lstat(file.path).st_nlink == 1L
            }) { "Unknown, backup or aliased work data retained" }
        }
        check(!File(target.path + ".new").exists()) { "Interrupted work commit retained; recovery required" }
    }
    fun load(): WorkSnapshot {
        blocked?.let { throw IllegalStateException("Work recovery blocked; bytes retained", it) }
        if (loaded) return snapshot
        try {
            safePaths()
            snapshot = if (target.exists()) {
                knownBytes = target.readBytes()
                val raw = checkNotNull(knownBytes).toString(Charsets.UTF_8)
                val json = JSONObject(raw)
                val version = Math.toIntExact(number(json, "version"))
                decode(json).also { check(encode(it, version).toString() == raw) { "Noncanonical/ambiguous work data retained" } }
            } else WorkSnapshot()
            existed = target.exists(); loaded = true
            return snapshot
        } catch (error: Exception) { blocked = error; throw error }
    }
    fun commit(next: WorkSnapshot): WorkSnapshot {
        load()
        if (next == snapshot) return snapshot
        require(next.deletedIds.containsAll(snapshot.deletedIds)) { "Deleted identities cannot be forgotten or reused" }
        validate(next)
        safePaths()
        check(!existed || target.exists()) { "Work data disappeared; retained state not replaced" }
        if (existed && !target.readBytes().contentEquals(knownBytes)) {
            val error = IllegalStateException("Work bytes changed outside the owner; retained")
            blocked = error; throw error
        }
        check(root.isDirectory || root.mkdir())
        val bytes = encode(next).toString().toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(target)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            fault("before-publish")
            atomic.finishWrite(stream); stream = null
            existed = true
            check(target.readBytes().contentEquals(bytes)) { "Work readback mismatch" }
            snapshot = next
            knownBytes = bytes
            fault("after-publish")
            return snapshot
        } catch (error: Exception) {
            if (stream != null) atomic.failWrite(stream)
            // Publication may have happened: never overwrite/retry from stale memory.
            loaded = false
            throw error
        }
    }
    fun saveDraft(draft: WorkDraft): WorkSnapshot {
        val old = load()
        check(draft.id !in old.deletedIds) { "This timebox was deleted; create a new draft" }
        if (old.records.any { it.id == draft.id }) return old // delayed autosave after publication
        check(old.draft == null || old.draft.id == draft.id) { "A different draft is retained" }
        return commit(old.copy(draft = draft))
    }
    fun save(id: String, createdAt: Long): WorkSnapshot {
        val old = load()
        check(id !in old.deletedIds) { "This timebox was deleted; create a new draft" }
        if (old.records.any { it.id == id }) return old
        val draft = checkNotNull(old.draft).also { check(it.id == id) }
        return commit(old.copy(draft = null, records = old.records + draft.validated(createdAt)))
    }
    fun discard(id: String): WorkSnapshot {
        val old = load()
        return if (old.draft?.id == id) commit(old.copy(draft = null)) else old
    }

    fun archive(id: String, archived: Boolean): WorkSnapshot = commit(WorkManagementPolicy.archive(load(), id, archived))
    fun delete(id: String): WorkSnapshot = commit(WorkManagementPolicy.delete(load(), id))

    companion object {
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        internal fun validate(value: WorkSnapshot, sharedMetadata: Boolean = true) {
            require(value.records.map { it.id }.toSet().size == value.records.size)
            require(value.records.count { it.state == WorkState.RUNNING } <= 1)
            value.deletedIds.forEach(::uuid)
            require(value.records.none { it.id in value.deletedIds } && value.draft?.id !in value.deletedIds)
            value.draft?.let { draft ->
                uuid(draft.id); require(value.records.none { record -> record.id == draft.id }); draft.type?.let(WorkType::fromKey)
                // Invalid form values remain drafts, but never silently replace unpaired UTF-16 on disk.
                require(listOf(draft.title, draft.minutes, draft.outcome, draft.description).all {
                    SyncPolicy.validText(it, 0, Int.MAX_VALUE)
                })
            }
            value.records.forEach { work ->
                uuid(work.id); require(work.createdAt >= 0)
                if (!sharedMetadata) require(work.title == work.title.trim() &&
                    (work.outcome == null || work.outcome.isNotBlank()) && work.description.isEmpty())
                mapOf("title" to work.title, "type" to work.type, "description" to work.description,
                    "expectedOutcome" to (work.outcome ?: "")).forEach(SyncPolicy::validate)
                require(WorkDraft(work.id, work.title, work.type, work.plannedMinutes.toString(), work.outcome ?: "", work.description)
                    .validated(work.createdAt).copy(title = work.title, outcome = work.outcome, state = work.state, start = work.start, deadline = work.deadline,
                        endElapsed = work.endElapsed, reason = work.reason, confirmedElapsed = work.confirmedElapsed, archived = work.archived) == work)
                require(!work.archived || work.state != WorkState.RUNNING)
                require(work.confirmedElapsed in 0..work.plannedMinutes * 60_000L)
                when (work.state) {
                    WorkState.PLANNED -> require(work.start == null && work.deadline == null && work.endElapsed == null && work.reason == null && work.confirmedElapsed == 0L)
                    WorkState.RUNNING, WorkState.ENDED -> {
                        val start = checkNotNull(work.start)
                        require(work.deadline == Math.addExact(start.elapsed, work.plannedMinutes * 60_000L))
                        if (work.state == WorkState.RUNNING) require(work.reason == null && work.endElapsed == null)
                        else {
                            require(work.reason != null && work.endElapsed != null && work.endElapsed in start.elapsed..work.deadline!!)
                            require(work.confirmedElapsed == work.endElapsed - start.elapsed)
                            if (work.reason == WorkEnd.EXPIRY) require(work.endElapsed == work.deadline)
                        }
                    }
                }
            }
            require(value.spans.map { it.id }.toSet().size == value.spans.size)
            require(value.spans.count { it.endElapsed == null } <= 1)
            value.spans.forEach { span ->
                uuid(span.id)
                require(span.recordingId.matches(Regex("[0-9]+-[0-9a-f-]{36}")))
                span.firstPhoto?.let { require(it.boot == span.admitted.boot && it.elapsed >= span.admitted.elapsed) }
                span.endElapsed?.let { require(it >= (span.firstPhoto ?: span.admitted).elapsed) }
                span.confirmedThrough?.let { confirmed ->
                    require(span.firstPhoto != null && confirmed >= span.firstPhoto.elapsed)
                    require(span.endElapsed == null || confirmed <= span.endElapsed)
                }
                require(!span.unknownTail || span.endElapsed != null)
            }
        }
        private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { json -> pairs.forEach { json.put(it.first, it.second ?: JSONObject.NULL) } }
        private fun moment(value: WorkMoment?) = value?.let { obj("boot" to it.boot, "elapsed" to it.elapsed, "epoch" to it.epoch) }
        private fun draft(value: WorkDraft?, version: Int) = value?.let { value ->
            obj("id" to value.id, "title" to value.title, "type" to value.type, "minutes" to value.minutes, "outcome" to value.outcome).also {
                if (version >= 3) it.put("description", value.description)
            }
        }
        private fun encode(value: WorkSnapshot, version: Int = 3): JSONObject = obj("version" to version, "draft" to draft(value.draft, version),
            "records" to JSONArray().also { array -> value.records.forEach { work -> array.put(obj(
                "id" to work.id, "createdAt" to work.createdAt, "title" to work.title, "type" to work.type,
                "minutes" to work.plannedMinutes, "outcome" to work.outcome, "state" to work.state.name,
                "start" to moment(work.start), "deadline" to work.deadline, "end" to work.endElapsed,
                "reason" to work.reason?.name, "confirmed" to work.confirmedElapsed).also {
                    if (version >= 2) it.put("archived", work.archived)
                    if (version >= 3) it.put("description", work.description)
                }) } },
            "spans" to JSONArray().also { array -> value.spans.forEach { span -> array.put(obj(
                "id" to span.id, "recording" to span.recordingId, "admitted" to moment(span.admitted),
                "first" to moment(span.firstPhoto), "end" to span.endElapsed, "unknownTail" to span.unknownTail, "confirmedThrough" to span.confirmedThrough)) } }).also {
                if (version >= 2) it.put("deletedIds", JSONArray(value.deletedIds.sorted()))
            }
        private fun keys(json: JSONObject, vararg names: String) { require(json.keys().asSequence().toSet() == names.toSet()) }
        private fun text(json: JSONObject, name: String): String = json.get(name) as String
        private fun number(json: JSONObject, name: String): Long {
            val value = json.get(name); require(value is Long || value is Int); return (value as Number).toLong()
        }
        private fun nullableNumber(json: JSONObject, name: String) = if (json.isNull(name)) null else number(json, name)
        private fun nullableText(json: JSONObject, name: String) = if (json.isNull(name)) null else text(json, name)
        private fun readMoment(json: JSONObject): WorkMoment {
            keys(json, "boot", "elapsed", "epoch"); return WorkMoment(text(json, "boot"), number(json, "elapsed"), number(json, "epoch"))
        }
        private fun decode(json: JSONObject): WorkSnapshot {
            val version = number(json, "version")
            require(version in 1L..3L) { "Unsupported work storage version; retained" }
            if (version == 1L) keys(json, "version", "draft", "records", "spans")
            else keys(json, "version", "draft", "records", "spans", "deletedIds")
            val draft = if (json.isNull("draft")) null else json.getJSONObject("draft").let {
                val names = arrayOf("id", "title", "type", "minutes", "outcome")
                keys(it, *(if (version >= 3L) names + "description" else names))
                WorkDraft(text(it, "id"), text(it, "title"), nullableText(it, "type"), text(it, "minutes"), text(it, "outcome"),
                    if (version >= 3L) text(it, "description") else "")
            }
            fun <T> array(name: String, read: (JSONObject) -> T): List<T> = json.getJSONArray(name).let { a -> (0 until a.length()).map { read(a.getJSONObject(it)) } }
            val records = array("records") {
                val names = arrayOf("id", "createdAt", "title", "type", "minutes", "outcome", "state", "start", "deadline", "end", "reason", "confirmed")
                keys(it, *(names + (if (version >= 2L) arrayOf("archived") else emptyArray()) +
                    (if (version >= 3L) arrayOf("description") else emptyArray())))
                WorkSession(text(it, "id"), number(it, "createdAt"), text(it, "title"), text(it, "type"), Math.toIntExact(number(it, "minutes")),
                    nullableText(it, "outcome"), WorkState.valueOf(text(it, "state")),
                    if (it.isNull("start")) null else readMoment(it.getJSONObject("start")), nullableNumber(it, "deadline"), nullableNumber(it, "end"),
                    nullableText(it, "reason")?.let(WorkEnd::valueOf), number(it, "confirmed"), if (version == 1L) false else it.get("archived") as Boolean,
                    if (version >= 3L) text(it, "description") else "")
            }
            val spans = array("spans") {
                keys(it, "id", "recording", "admitted", "first", "end", "unknownTail", "confirmedThrough")
                RecordingSpan(text(it, "id"), text(it, "recording"), readMoment(it.getJSONObject("admitted")),
                    if (it.isNull("first")) null else readMoment(it.getJSONObject("first")), nullableNumber(it, "end"), it.get("unknownTail") as Boolean, nullableNumber(it, "confirmedThrough"))
            }
            val deleted = if (version == 1L) emptyList() else json.getJSONArray("deletedIds").let { a ->
                (0 until a.length()).map { a.get(it) as String }
            }
            require(deleted.toSet().size == deleted.size) { "Duplicate deleted identity; retained" }
            return WorkSnapshot(draft, records, spans, deleted.toSet()).also { validate(it, version >= 3L) }
        }
    }
}
