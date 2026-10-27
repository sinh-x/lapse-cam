package com.sinh.timelapse

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import java.io.FileOutputStream
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Additive immutable export provenance. Not the split-session index and never a media deleter. */
internal object SyncMediaInventory {
    private val lock = Any()
    private val moviePath = "${Environment.DIRECTORY_MOVIES}/TimestampTimelapse/"
    private fun paths(root: File) {
        SyncThreads.io()
        check(!Files.isSymbolicLink(root.toPath()) && root.canonicalFile == root.absoluteFile)
        if (root.exists()) check(root.isDirectory && (Os.lstat(root.path).st_mode and 511) == 448 && root.listFiles()!!.all {
            it.name.matches(Regex("[0-9a-f-]{36}\\.json")) && it.isFile && !Files.isSymbolicLink(it.toPath()) &&
                Os.lstat(it.path).st_nlink == 1L && (Os.lstat(it.path).st_mode and 511) == 384
        }) { "Unknown/interrupted/aliased inventory retained" }
    }
    private fun readRecord(root: File, id: String): JSONObject? {
        paths(root)
        val target = File(root, "$id.json")
        if (!target.exists()) return null
        val bytes = target.readBytes(); val value = JSONObject(String(bytes, Charsets.UTF_8))
        check(value.toString().toByteArray().contentEquals(bytes) && value.get("version") == 1 && value.getString("id") == id &&
            value.keys().asSequence().toSet() == setOf("version", "id", "recording", "count", "fps"))
        check(value.getString("recording").matches(Regex("[0-9]+-[0-9a-f-]{36}")))
        return value
    }
    private fun publish(root: File, id: String, value: JSONObject) {
        paths(root)
        if (!root.exists()) { check(root.mkdir()); Os.chmod(root.path, 448) }
        val target = File(root, "$id.json"); check(!target.exists())
        val tmp = File(root, "$id.new"); check(tmp.createNewFile()); Os.chmod(tmp.path, 384)
        FileOutputStream(tmp).use { it.write(value.toString().toByteArray()); it.fd.sync() }
        Os.rename(tmp.path, target.path)
        val fd = Os.open(root.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    fun record(files: File, id: String, recording: String, count: Int, fps: Int) = synchronized(lock) {
        check(UUID.fromString(id).toString() == id)
        check(recording.matches(Regex("[0-9]+-[0-9a-f-]{36}")) && count > 0 && fps in 1..30)
        val root = File(files.canonicalFile, "export-inventory")
        val value = json("version" to 1, "id" to id, "recording" to recording, "count" to count, "fps" to fps)
        val existing = readRecord(root, id)
        if (existing == null) publish(root, id, value) else check(existing.toString() == value.toString())
    }
    /** Backfill only the exact URI already associated by the media owner; never infer from dates. */
    fun associate(files: File, id: String, recording: String) = synchronized(lock) {
        check(UUID.fromString(id).toString() == id && recording.matches(Regex("[0-9]+-[0-9a-f-]{36}")))
        val root = File(files.canonicalFile, "export-inventory")
        val current = readRecord(root, id)
        if (current == null) publish(root, id, json("version" to 1, "id" to id, "recording" to recording, "count" to 0, "fps" to 0))
        else check(current.getString("recording") == recording)
    }
    data class Output(val id: String, val uri: Uri, val recording: String)
    fun published(context: Context): List<Output> {
        SyncThreads.io()
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.IS_PENDING} = 0")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName, moviePath))
        }
        val result = mutableListOf<Output>()
        checkNotNull(context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.OWNER_PACKAGE_NAME,
                MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.IS_PENDING), query, null)).use { rows ->
            while (rows.moveToNext()) {
                check(rows.getString(2) == context.packageName && rows.getString(3) == moviePath && rows.getInt(4) == 0)
                val name = rows.getString(1) ?: continue
                if (!name.matches(Regex("Timelapse-[0-9a-f-]{36}\\.mp4"))) continue
                val id = name.removePrefix("Timelapse-").removeSuffix(".mp4")
                if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) continue
                check(result.none { it.id == id }) { "Ambiguous export identity retained" }
                val recording = synchronized(lock) {
                    val root = File(context.filesDir.canonicalFile, "export-inventory")
                    readRecord(root, id)?.getString("recording") ?: ""
                }
                result += Output(id, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, rows.getLong(0)), recording)
            }
        }
        return result
    }
    /** Reopen actual published bytes. Only a single H.264 MP4 track is eligible; hash with bounded memory. */
    fun describe(context: Context, output: Output): JSONObject {
        SyncThreads.io()
        val extractor = MediaExtractor()
        try {
            checkNotNull(context.contentResolver.openFileDescriptor(output.uri, "r")).use {
                extractor.setDataSource(it.fileDescriptor)
                check(extractor.trackCount == 1)
                val format = extractor.getTrackFormat(0)
                check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
                check(format.getInteger(MediaFormat.KEY_WIDTH) == VIDEO_WIDTH && format.getInteger(MediaFormat.KEY_HEIGHT) == VIDEO_HEIGHT)
                check(format.getLong(MediaFormat.KEY_DURATION) > 0)
            }
        } finally { extractor.release() }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        checkNotNull(context.contentResolver.openInputStream(output.uri)).use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = source.read(buffer); if (n < 0) break; size = Math.addExact(size, n.toLong()); digest.update(buffer, 0, n) }
        }
        check(size > 0)
        return json("size" to size, "sha256" to digest.digest().joinToString("") { "%02x".format(it) }, "recording" to output.recording)
    }
}
