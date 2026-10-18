package com.sinh.timelapse

import android.graphics.Bitmap
import android.util.AtomicFile
import org.json.JSONObject
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
)

/** All calls are serialized on the capture worker; a completed JPEG is the source of truth. */
class SessionStore(filesDirectory: File) {
    private val root = File(filesDirectory, "sessions").apply { check(mkdirs() || isDirectory) { "Cannot open saved sessions" } }

    fun create(intervalMillis: Long, fps: Int, lens: SessionLens = SessionLens.REAR): CaptureSession {
        require(intervalMillis >= 1_000 && fps in 1..30)
        val now = System.currentTimeMillis()
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

    fun latest(): CaptureSession? = checkNotNull(root.listFiles()) { "Cannot list saved sessions; photos retained" }
        .filter { it.isDirectory }
        .map { directory ->
            try {
                val json = JSONObject(AtomicFile(File(directory, "session.json")).readFully().toString(Charsets.UTF_8))
                val version = json.get("version")
                require(version is Int) { "Session version must be an integer" }
                val lens = when (version) {
                    1 -> SessionLens.REAR
                    2 -> SessionLens.parse(json.get("lens") as? String ?: error("Missing session lens"))
                    else -> error("Unsupported session version")
                }
                val interval = json.getLong("intervalMillis")
                val fps = json.getInt("fps")
                require(interval >= 1_000 && fps in 1..30)
                val zone = ZoneId.of(json.getString("zoneId")).id
                CaptureSession(directory, json.getLong("startedAt"), interval, fps, zone, lens)
            } catch (error: Exception) {
                // Never hide an unreadable session by silently selecting an older one.
                throw IllegalStateException("Cannot recover session ${directory.name}. Keep its photos; repair metadata then retry recovery", error)
            }
        }.maxByOrNull { it.startedAt }

    fun frames(session: CaptureSession): List<File> = checkNotNull(session.directory.listFiles()) { "Cannot read saved photos" }
        .filter { it.isFile && it.name.matches(Regex("frame_[0-9]{8}_[0-9]+\\.jpg")) }
        .sortedBy { it.name }

    fun saveFrame(session: CaptureSession, bitmap: Bitmap, capturedAt: Long): Int {
        val existing = frames(session)
        // Do not reuse sequence numbers even if an earlier JPEG was manually removed.
        val sequence = existing.lastOrNull()?.name?.substringAfter("frame_")?.substringBefore('_')?.toInt()?.plus(1) ?: 0
        check(sequence < 100_000_000) { "Session is full" }
        val name = "frame_${sequence.toString().padStart(8, '0')}_$capturedAt.jpg"
        val target = File(session.directory, name)
        val temporary = File(session.directory, "$name.tmp")
        try {
            temporary.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "Could not save photo" }
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Could not finalize photo" }
        } finally {
            temporary.delete()
        }
        return existing.size + 1
    }

    fun discard(session: CaptureSession) {
        check(session.directory.deleteRecursively()) { "Could not delete session" }
    }
}
