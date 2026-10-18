package com.sinh.timelapse

import android.graphics.Bitmap
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.UUID

const val VIDEO_WIDTH = 720
const val VIDEO_HEIGHT = 1280

data class CaptureSession(
    val directory: File,
    val startedAt: Long,
    val intervalMillis: Long,
    val fps: Int,
    val zoneId: String,
)

/** All calls are serialized on the capture worker; a completed JPEG is the source of truth. */
class SessionStore(filesDirectory: File) {
    private val root = File(filesDirectory, "sessions").apply { mkdirs() }

    fun create(intervalMillis: Long, fps: Int): CaptureSession {
        require(intervalMillis >= 1_000 && fps in 1..30)
        val now = System.currentTimeMillis()
        val directory = File(root, "$now-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val session = CaptureSession(directory, now, intervalMillis, fps, ZoneId.systemDefault().id)
        val json = JSONObject()
            .put("version", 1)
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

    fun latest(): CaptureSession? = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { directory ->
            runCatching {
                val json = JSONObject(AtomicFile(File(directory, "session.json")).readFully().toString(Charsets.UTF_8))
                require(json.getInt("version") == 1)
                val interval = json.getLong("intervalMillis")
                val fps = json.getInt("fps")
                require(interval >= 1_000 && fps in 1..30)
                val zone = ZoneId.of(json.getString("zoneId")).id
                CaptureSession(directory, json.getLong("startedAt"), interval, fps, zone)
            }.getOrNull()
        }.maxByOrNull { it.startedAt }

    fun frames(session: CaptureSession): List<File> = session.directory.listFiles().orEmpty()
        .filter { it.name.matches(Regex("frame_[0-9]{8}_[0-9]+\\.jpg")) }
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
