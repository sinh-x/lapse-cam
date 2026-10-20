package com.sinh.timelapse

import android.graphics.Bitmap
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.time.ZoneId
import java.util.UUID

const val VIDEO_WIDTH = 720
const val VIDEO_HEIGHT = 1280

/** Stable semantic identity; never persist CameraX selectors or enum ordinals. */
enum class SessionLens(val storedValue: String) {
    REAR("rear"), FRONT("front");

    companion object {
        fun parse(value: String): SessionLens = entries.firstOrNull { it.storedValue == value }
            ?: throw IllegalArgumentException("Unknown camera lens: $value")
    }
}

/** Shared by UI/model gates and host fixtures. Camera loss must not disable saved-photo export. */
internal object CapturePolicy {
    const val MIN_FREE_BYTES = 50L * 1024 * 1024
    fun canSelectLens(hasSession: Boolean, busy: Boolean, blocked: Boolean) = !hasSession && !busy && !blocked
    fun canCapture(requested: SessionLens, bound: SessionLens?, foreground: Boolean, blocked: Boolean) =
        foreground && !blocked && requested == bound
    fun hasCaptureSpace(bytes: Long) = bytes >= MIN_FREE_BYTES
    fun isCurrentBinding(request: Long, activeRequest: Long, requestedLens: SessionLens,
        currentLens: SessionLens, foreground: Boolean, permittedAndLoaded: Boolean) =
        request == activeRequest && requestedLens == currentLens && foreground && permittedAndLoaded
}

data class CaptureSession(
    val directory: File,
    val startedAt: Long,
    val intervalMillis: Long,
    val fps: Int,
    val zoneId: String,
    val lens: SessionLens = SessionLens.REAR,
    val configuration: SamplingConfiguration? = null,
    val appearance: TimestampAppearance = TimestampAppearance(),
    val version: Int = 2,
)

/** All calls are serialized on the capture worker; a completed JPEG is the source of truth. */
class SessionStore(filesDirectory: File,
    private val onHistoricalScan: () -> Unit = {},
    private val epochMillis: () -> Long = { System.currentTimeMillis() },
    private val checkpoint: (String) -> Unit = {},
) {
    data class Snapshot(val count: Int, val bytes: Long, val selected: Int, val stage: Long,
        val activeMillis: Long?, val unknownTail: Boolean, val intervalMillis: Long)
    data class FrameIntent(val id: Long, val epoch: Long, val activeMillis: Long, val name: String)
    private data class Progress(var nextId: Long = 0, var lastCommittedId: Long = -1, var count: Int = 0, var bytes: Long = 0,
        var active: Long = 0, var running: Boolean = false, var unknown: Boolean = false,
        var selector: AdaptiveSampling? = null)
    private val progress = mutableMapOf<File, Progress>()
    private data class LegacyProgress(var count: Int, var bytes: Long, var nextSequence: Int)
    private val legacyProgress = mutableMapOf<File, LegacyProgress>()
    private fun legacyState(session: CaptureSession): LegacyProgress = legacyProgress.getOrPut(session.directory) {
        val files = frames(session)
        LegacyProgress(files.size, files.sumOf { it.length() },
            files.lastOrNull()?.name?.substringAfter("frame_")?.substringBefore('_')?.toInt()?.let { Math.addExact(it, 1) } ?: 0)
    }
    private val root = File(filesDirectory, "sessions").apply { check(mkdirs() || isDirectory) { "Cannot open saved sessions" } }

    fun create(intervalMillis: Long, fps: Int, lens: SessionLens = SessionLens.REAR): CaptureSession {
        require(intervalMillis >= 1_000 && fps in 1..30)
        val now = epochMillis()
        val directory = File(root, "$now-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val session = CaptureSession(directory, now, intervalMillis, fps, ZoneId.systemDefault().id, lens)
        val json = JSONObject()
            .put("version", 2)
            .put("lens", lens.storedValue)
            .put("startedAt", now)
            .put("intervalMillis", intervalMillis)
            .put("fps", fps)
            .put("zoneId", session.zoneId)
        val atomicFile = AtomicFile(File(directory, "session.json"))
        val stream = atomicFile.startWrite()
        try {
            stream.write(json.toString(2).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw error
        }
        return session
    }

    fun latest(): CaptureSession? = scannedFiles(root)
        .filter { it.isDirectory }
        .map { directory ->
            try {
                val json = JSONObject(AtomicFile(File(directory, "session.json")).readFully().toString(Charsets.UTF_8))
                val version = json.get("version")
                require(version is Int) { "Session version must be an integer" }
                val lens = when (version) {
                    1 -> SessionLens.REAR
                    2, 3 -> SessionLens.parse(json.get("lens") as? String ?: error("Missing session lens"))
                    else -> error("Unsupported session version")
                }
                val interval = json.getLong("intervalMillis")
                val fps = json.getInt("fps")
                require(interval >= (if (version == 3) 500 else 1_000) && fps in 1..30)
                val zone = ZoneId.of(json.getString("zoneId")).id
                if (version == 3) {
                    check(strictLong(json, "intervalMillis") == interval && strictLong(json, "fps") == fps.toLong())
                    check(strictLong(json, "startedAt") >= 0)
                    val config = when (json.getString("mode")) {
                        "adaptive" -> {
                            check(interval == 500L)
                            SamplingConfiguration.Adaptive(Math.toIntExact(strictLong(json, "maxSeconds")), fps)
                        }
                        "fixed" -> {
                            check(interval % 1000 == 0L && strictLong(json, "maxSeconds") == 0L)
                            SamplingConfiguration.Fixed(Math.toIntExact(interval / 1000), fps)
                        }
                        else -> error("Unknown sampling mode")
                    }
                    val style = TimestampAppearance(StampFamily.valueOf(json.getString("family")),
                        strictBoolean(json, "bold"), Math.toIntExact(strictLong(json, "size")),
                        StampPosition.valueOf(json.getString("position")), strictBoolean(json, "date"))
                    CaptureSession(directory, json.getLong("startedAt"), interval, fps, zone, lens, config, style, 3)
                        .also { recoverProgress(it) }
                } else CaptureSession(directory, json.getLong("startedAt"), interval, fps, zone, lens)
            } catch (error: Exception) {
                // Never hide an unreadable session by silently selecting an older one.
                throw IllegalStateException("Cannot recover session ${directory.name}. Keep its photos; repair metadata then retry recovery", error)
            }
        }.maxByOrNull { it.startedAt }

    private fun scannedFiles(directory: File): Array<File> {
        onHistoricalScan()
        return checkNotNull(directory.listFiles()) { "Cannot list saved sessions/photos; retained" }
    }

    fun frames(session: CaptureSession): List<File> = scannedFiles(session.directory)
        .filter { it.isFile && it.name.matches(Regex("frame_[0-9]{8,19}_[0-9]+\\.jpg")) }
        .sortedBy { it.name }

    fun saveFrame(session: CaptureSession, bitmap: Bitmap, capturedAt: Long): Int {
        require(session.version < 3) { "Version 3 requires durable frame intent" }
        val cached = legacyState(session)
        // High water is cached once, never rescan historical photos on the steady save path.
        val sequence = cached.nextSequence
        check(sequence < 100_000_000) { "Session is full" }
        val name = "frame_${sequence.toString().padStart(8, '0')}_$capturedAt.jpg"
        val target = File(session.directory, name)
        val temporary = File(session.directory, "$name.tmp")
        try {
            temporary.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "Could not save photo" }
                output.fd.sync()
            }
            check(!target.exists() && temporary.renameTo(target)) { "Could not finalize photo" }
        } finally {
            temporary.delete()
        }
        cached.nextSequence = Math.addExact(sequence, 1)
        cached.count = Math.addExact(cached.count, 1)
        cached.bytes = Math.addExact(cached.bytes, target.length())
        return cached.count
    }

    /** New sessions use v3; the original overload remains the exact legacy v2 writer. */
    fun create(configuration: SamplingConfiguration, lens: SessionLens, appearance: TimestampAppearance): CaptureSession {
        val now = epochMillis()
        require(now >= 0)
        val interval = when (configuration) {
            is SamplingConfiguration.Adaptive -> 500L
            is SamplingConfiguration.Fixed -> configuration.intervalMillis
        }
        val directory = File(root, "$now-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val session = CaptureSession(directory, now, interval, configuration.fps, ZoneId.systemDefault().id,
            lens, configuration, appearance, 3)
        val json = JSONObject().put("version", 3).put("lens", lens.storedValue).put("startedAt", now)
            .put("intervalMillis", interval).put("fps", configuration.fps).put("zoneId", session.zoneId)
            .put("mode", if (configuration is SamplingConfiguration.Adaptive) "adaptive" else "fixed")
            .put("maxSeconds", (configuration as? SamplingConfiguration.Adaptive)?.maxSeconds ?: 0)
            .put("family", appearance.family.name).put("bold", appearance.bold).put("size", appearance.size)
            .put("position", appearance.position.name).put("date", appearance.date)
        // Empty progress first: a published session header always has a durable checkpoint.
        val state = Progress(selector = (configuration as? SamplingConfiguration.Adaptive)?.let(::AdaptiveSampling))
        writeProgress(session, state)
        writeJson(File(directory, "session.json"), json)
        progress[directory] = state
        return session
    }

    private fun writeJson(target: File, json: JSONObject) {
        val atomic = AtomicFile(target)
        checkpoint("before-atomic:${target.name}")
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            checkpoint("atomic-written:${target.name}")
            atomic.finishWrite(output)
            checkpoint("atomic-finished:${target.name}")
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }

    private fun readJson(target: File) = JSONObject(AtomicFile(target).readFully().toString(Charsets.UTF_8))
    private fun state(session: CaptureSession) = checkNotNull(progress[session.directory]) { "Session must be recovered before use" }
    private fun selection(state: Progress) = JSONArray().also { result ->
        state.selector?.selectedFrames()?.forEach { result.put(it.id) }
    }
    private fun writeProgress(session: CaptureSession, state: Progress) = writeJson(File(session.directory, "progress.json"),
        JSONObject().put("version", 1).put("nextId", state.nextId).put("lastId", state.lastCommittedId).put("count", state.count).put("bytes", state.bytes)
            .put("active", state.active).put("running", state.running).put("unknown", state.unknown)
            .put("stage", state.selector?.thinningStage ?: 0).put("selected", selection(state)))

    fun snapshot(session: CaptureSession): Snapshot {
        if (session.version < 3) {
            val cached = legacyState(session)
            return Snapshot(cached.count, cached.bytes, cached.count, 0, null, false, session.intervalMillis)
        }
        val state = state(session)
        return Snapshot(state.count, state.bytes, state.selector?.selectedCount ?: state.count,
            state.selector?.thinningStage ?: 0, state.active, state.unknown,
            state.selector?.intervalMillis ?: session.intervalMillis)
    }

    fun checkpoint(session: CaptureSession, activeMillis: Long, running: Boolean) {
        if (session.version < 3) return
        val state = state(session)
        require(activeMillis >= 0)
        // A timer checkpoint may have queued behind a later JPEG completion; never regress it.
        state.active = maxOf(state.active, activeMillis)
        state.running = running
        writeProgress(session, state)
        checkpoint("checkpoint")
    }

    /** Durable identity before the shutter; failed requests consume their ID, never reuse it. */
    fun beginFrame(session: CaptureSession, epoch: Long, activeMillis: Long): FrameIntent {
        require(session.version == 3 && epoch >= 0 && activeMillis >= 0)
        val state = state(session)
        require(activeMillis >= state.active) { "Active request time moved backwards" }
        val id = state.nextId
        state.nextId = Math.addExact(id, 1)
        state.active = maxOf(state.active, activeMillis)
        writeProgress(session, state)
        checkpoint("reserved")
        val intent = FrameIntent(id, epoch, activeMillis, "frame_${id.toString().padStart(19, '0')}_$epoch.jpg")
        writeJson(File(session.directory, "intent_$id.json"), JSONObject().put("version", 1).put("id", id)
            .put("epoch", epoch).put("active", activeMillis).put("name", intent.name))
        checkpoint("intent")
        return intent
    }

    fun completeFrame(session: CaptureSession, intent: FrameIntent, bitmap: Bitmap,
        knownActiveMillis: Long = intent.activeMillis): Snapshot {
        require(knownActiveMillis >= intent.activeMillis)
        val state = state(session)
        check(intent.id < state.nextId && intent.id > state.lastCommittedId) { "Duplicate or out-of-order frame commit" }
        require(bitmap.width == VIDEO_WIDTH && bitmap.height == VIDEO_HEIGHT)
        check(readIntent(File(session.directory, "intent_${intent.id}.json")) == intent)
        val target = File(session.directory, intent.name)
        check(!target.exists()) { "Frame already exists; recover before retry" }
        val temporary = File(session.directory, "${intent.name}.tmp")
        try {
            temporary.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "Could not save photo" }
                output.fd.sync()
            }
            checkpoint("jpeg-written")
            check(temporary.renameTo(target)) { "Could not finalize photo" }
            checkpoint("jpeg-renamed")
            applyCompleted(state, intent, target.length())
            state.active = maxOf(state.active, knownActiveMillis)
            writeProgress(session, state) // count, bytes, selection and stage in one atomic transaction.
            checkpoint("committed")
            return snapshot(session)
        } finally { temporary.delete() }
    }

    private fun applyCompleted(state: Progress, intent: FrameIntent, bytes: Long) {
        check(intent.id > state.lastCommittedId)
        val count = Math.addExact(state.count, 1)
        val totalBytes = Math.addExact(state.bytes, bytes)
        state.selector?.onCommitted(CommittedFrame(intent.id, intent.activeMillis))
        state.lastCommittedId = intent.id
        state.count = count
        state.bytes = totalBytes
        state.active = maxOf(state.active, intent.activeMillis)
    }

    private fun readIntent(file: File): FrameIntent {
        val json = readJson(file)
        check(json.get("version") == 1)
        val id = strictLong(json, "id")
        val epoch = strictLong(json, "epoch")
        val active = strictLong(json, "active")
        check(id >= 0 && epoch >= 0 && active >= 0)
        val name = "frame_${id.toString().padStart(19, '0')}_$epoch.jpg"
        check(file.name == "intent_$id.json" && json.getString("name") == name)
        return FrameIntent(id, epoch, active, name)
    }

    private fun strictBoolean(json: JSONObject, key: String): Boolean =
        json.get(key) as? Boolean ?: error("Invalid boolean $key")

    private fun strictLong(json: JSONObject, key: String): Long {
        val value = json.get(key)
        check(value is Long || value is Int) { "Invalid integer $key" }
        return (value as Number).toLong()
    }

    /** Startup only: replay identifiable completed JPEGs, never guess provenance or delete photos. */
    private fun recoverProgress(session: CaptureSession) {
        val json = readJson(File(session.directory, "progress.json"))
        check(json.get("version") == 1)
        val state = Progress(nextId = strictLong(json, "nextId"), active = strictLong(json, "active"),
            unknown = strictBoolean(json, "unknown") || strictBoolean(json, "running"),
            selector = (session.configuration as? SamplingConfiguration.Adaptive)?.let(::AdaptiveSampling))
        check(state.nextId >= 0 && state.active >= 0)
        val files = scannedFiles(session.directory)
        val intents = files.filter { it.name.matches(Regex("intent_[0-9]+\\.json(?:\\.bak)?")) }
            .map { it.name.removeSuffix(".bak") }.distinct()
            .map { readIntent(File(session.directory, it)) }.sortedBy { it.id }
        val known = intents.associateBy { it.name }
        val completed = files.filter { it.isFile && it.name.endsWith(".jpg") && it.name != "capture.tmp.jpg" }
        check(completed.all { it.name in known }) { "Unattributed completed JPEG; retained" }
        var previousOffset = -1L
        intents.forEach { intent ->
            check(intent.id < state.nextId && intent.activeMillis >= previousOffset) { "Contradictory frame provenance" }
            previousOffset = intent.activeMillis
            val file = File(session.directory, intent.name)
            if (file.exists()) {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                check(bounds.outWidth == VIDEO_WIDTH && bounds.outHeight == VIDEO_HEIGHT) { "Unreadable committed JPEG; retained" }
                applyCompleted(state, intent, file.length())
            }
        }
        // A checkpoint prefix must exactly replay; extra JPEGs are a recoverable interrupted commit.
        val savedCount = Math.toIntExact(strictLong(json, "count"))
        check(savedCount in 0..state.count)
        val prefix = Progress(selector = (session.configuration as? SamplingConfiguration.Adaptive)?.let(::AdaptiveSampling))
        intents.asSequence().filter { File(session.directory, it.name).exists() }.take(savedCount)
            .forEach { applyCompleted(prefix, it, File(session.directory, it.name).length()) }
        val savedSelection = json.getJSONArray("selected")
        for (index in 0 until savedSelection.length()) check(savedSelection.get(index) is Int || savedSelection.get(index) is Long)
        check(strictLong(json, "active") >= prefix.active)
        check(prefix.lastCommittedId == strictLong(json, "lastId"))
        check(prefix.bytes == strictLong(json, "bytes") && selection(prefix).toString() == savedSelection.toString()
            && (prefix.selector?.thinningStage ?: 0L) == strictLong(json, "stage")) { "Contradictory selection checkpoint; retained" }
        progress[session.directory] = state
        if (strictBoolean(json, "running") || state.count != savedCount) writeProgress(session, state)
    }

    /** A caller-owned immutable ordered export snapshot; no file substitution or restamping. */
    fun exportFrames(session: CaptureSession): List<File> {
        if (session.configuration !is SamplingConfiguration.Adaptive) {
            val files = frames(session).toList()
            check(files.size == snapshot(session).count) { "Committed JPEG missing; photos retained" }
            if (session.version == 3) files.forEach { file ->
                val id = file.name.substringAfter("frame_").substringBefore('_').toLong()
                check(readIntent(File(session.directory, "intent_$id.json")).name == file.name) { "Unknown frame provenance; retained" }
            }
            return files
        }
        val selected = state(session).selector!!.selectedFrames()
        // Resolve only selected IDs; no historical directory scan per completion.
        return selected.map { frame ->
            val intent = readIntent(File(session.directory, "intent_${frame.id}.json"))
            check(intent.activeMillis == frame.activeOffsetMillis)
            File(session.directory, intent.name).also { check(it.isFile) { "Selected JPEG missing; photos retained" } }
        }
    }

    fun discard(session: CaptureSession) {
        check(session.directory.deleteRecursively()) { "Could not delete session" }
        progress.remove(session.directory)
        legacyProgress.remove(session.directory)
    }
}
