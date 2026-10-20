package com.sinh.timelapse

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.provider.MediaStore
import android.view.Surface
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import org.json.JSONObject

class VideoExporter(
    private val context: Context,
    filesDirectory: File = context.filesDir,
    cacheDirectory: File = context.cacheDir,
    private val availableBytes: (File) -> Long = { android.os.StatFs(it.path).availableBytes },
    private val checkpoint: (String) -> Unit = {},
) {
    private val attempts = File(filesDirectory, "export-attempts").apply { check(mkdirs() || isDirectory) }
    private val cache = File(cacheDirectory, "export-attempts").apply { check(mkdirs() || isDirectory) }

    private data class Attempt(val id: String, val uri: Uri? = null) {
        val name: String get() = "Timelapse-$id.mp4"
    }

    companion object {
        private val exportLock = Any()
        private val MOVIE_PATH = "${Environment.DIRECTORY_MOVIES}/TimestampTimelapse/"
    }

    /** Startup and export share the same serialization boundary; no broad cache/media sweep. */
    fun reconcile() = synchronized(exportLock) {
        val records = checkNotNull(attempts.listFiles()) { "Cannot list export recovery records" }
            .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            .map { it.name.removeSuffix(".bak").removeSuffix(".json") }.distinct()
        records.forEach { id ->
            check(UUID.fromString(id).toString() == id) { "Unknown export record; retained for inspection" }
            val json = JSONObject(AtomicFile(File(attempts, "$id.json")).readFully().toString(Charsets.UTF_8))
            check(json.get("version") == 1 && json.get("id") == id) { "Invalid export record; retained" }
            reconcileAttempt(Attempt(id, if (json.has("uri")) Uri.parse(json.getString("uri")) else null))
        }
        check(checkNotNull(cache.listFiles()).none { it.name.endsWith(".mp4") }) {
            "Unattributed export cache retained; inspect it before retrying export"
        }
    }

    fun export(session: CaptureSession, frames: List<File>, onProgress: (Int) -> Unit): Uri = synchronized(exportLock) {
        val selected = frames.toList()
        require(selected.isNotEmpty()) { "Capture at least one photo first" }
        val adaptive = session.configuration as? SamplingConfiguration.Adaptive
        require(adaptive == null || selected.size <= adaptive.frameBudget) { "Selection exceeds video cap" }
        require(selected.distinct().size == selected.size && selected.all { it.isFile && it.parentFile == session.directory }
            && selected.zipWithNext().all { (first, second) -> first.name < second.name }) { "Invalid selected photos; retained" }
        val headroom = ExportSpace.requiredBytes(selected.size.toLong(), session.fps)
        check(availableBytes(cache) >= headroom && availableBytes(Environment.getExternalStorageDirectory()) >= headroom) {
            "Insufficient export headroom. Photos retained; free space and retry"
        }
        reconcile()
        var attempt = Attempt(UUID.randomUUID().toString())
        writeAttempt(attempt) // Durable identity BEFORE encoding or inserting any external output.
        val temporary = File(cache, "${attempt.id}.mp4")
        try {
            checkpoint("recorded")
            encode(temporary, selected, session.fps, onProgress)
            validateEncoded(temporary, selected.size, session.fps, adaptive?.maxSeconds)
            checkpoint("encoded")
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, attempt.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, MOVIE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            })) { "Could not create Gallery video" }
            checkpoint("inserted") // An interrupted insert is located by exact UUID AND owner, never a prefix.
            attempt = attempt.copy(uri = uri)
            writeAttempt(attempt)
            checkNotNull(resolver.openOutputStream(uri, "w")).use { target ->
                temporary.inputStream().use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var size = source.read(buffer)
                    while (size >= 0) {
                        target.write(buffer, 0, size)
                        checkpoint("copying")
                        size = source.read(buffer)
                    }
                }
            }
            checkpoint("copied")
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null) == 1) { "Could not publish Gallery video" }
            checkpoint("published")
            reconcileAttempt(attempt) // A published row is ALWAYS retained, even after process interruption.
            uri
        } catch (error: Exception) {
            runCatching { reconcileAttempt(attempt) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun writeAttempt(attempt: Attempt) {
        val json = JSONObject().put("version", 1).put("id", attempt.id)
        attempt.uri?.let { json.put("uri", it.toString()) }
        val file = AtomicFile(File(attempts, "${attempt.id}.json"))
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun reconcileAttempt(attempt: Attempt) {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.IS_PENDING,
            MediaStore.Video.Media.OWNER_PACKAGE_NAME)
        val pending = mutableListOf<Uri>()
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.DISPLAY_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(attempt.name))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        checkNotNull(resolver.query(collection, projection, query, null)) {
            "Cannot inspect interrupted export; retained"
        }.use { rows ->
            check(rows.count <= 1) { "Ambiguous interrupted export; retained" }
            while (rows.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(collection, rows.getLong(0))
                check((attempt.uri == null || attempt.uri == uri) && rows.getString(1) == attempt.name &&
                    rows.getString(2) == MOVIE_PATH && rows.getString(4) == context.packageName) {
                    "Cannot prove interrupted export ownership; retained"
                }
                if (rows.getInt(3) == 1) pending += uri
                else check(rows.getInt(3) == 0) { "Unknown publication state; retained" }
            }
        }
        for (uri in pending) {
            check(resolver.delete(uri,
                "${MediaStore.Video.Media.IS_PENDING} = ? AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ? AND " +
                    "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?",
                arrayOf("1", context.packageName, attempt.name, MOVIE_PATH)) == 1) {
                "Could not clean pending export; recovery record retained"
            }
        }
        val temporary = File(cache, "${attempt.id}.mp4")
        check(!temporary.exists() || temporary.delete()) { "Could not clean export cache; record retained" }
        AtomicFile(File(attempts, "${attempt.id}.json")).delete()
        check(!File(attempts, "${attempt.id}.json").exists() && !File(attempts, "${attempt.id}.json.bak").exists()) {
            "Could not remove export record"
        }
    }

    /** Reject a codec/container that cannot honor the contract BEFORE publishing its output. */
    internal fun validateEncoded(output: File, count: Int, fps: Int, maxSeconds: Int? = null) {
        require(count > 0 && fps in 1..30)
        val expectedDuration = CaptureTiming.presentationTimeUs(count, fps)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            check(extractor.trackCount == 1) { "Unexpected video tracks" }
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC &&
                format.getInteger(MediaFormat.KEY_WIDTH) == VIDEO_WIDTH && format.getInteger(MediaFormat.KEY_HEIGHT) == VIDEO_HEIGHT)
            val duration = format.getLong(MediaFormat.KEY_DURATION)
            check(duration >= 0 && kotlin.math.abs(duration - expectedDuration) <= 1000 &&
                (maxSeconds == null || duration <= maxSeconds * 1_000_000L)) { "Encoder did not honor video duration/cap; photos retained" }
            extractor.selectTrack(0)
            var samples = 0
            var previous = -1L
            while (extractor.sampleTime >= 0) {
                val pts = extractor.sampleTime
                check(samples < count && pts > previous &&
                    kotlin.math.abs(pts - CaptureTiming.presentationTimeUs(samples, fps)) <= 1) { "Unexpected video timing" }
                previous = pts
                samples++
                if (!extractor.advance()) break
            }
            check(samples == count) { "Unexpected encoded sample count" }
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.path)
            val durationMillis = checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            check(kotlin.math.abs(durationMillis - count * 1000.0 / fps) <= 1.0 &&
                (maxSeconds == null || durationMillis <= maxSeconds * 1000L)) { "Reported video duration/cap mismatch" }
        } finally { retriever.release() }
    }

    internal fun encode(output: File, frames: List<File>, fps: Int, onProgress: (Int) -> Unit) {
        require(frames.isNotEmpty() && fps in 1..30)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var surface: Surface? = null
        var renderer: CodecSurface? = null
        var codecStarted = false
        var muxerStarted = false
        var track = -1
        var sampleCount = 0
        var submittedFrames = 0
        var configBuffers = 0
        var eosFlags = 0
        val recentSamplePts = ArrayDeque<Long>()
        try {
            val writer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            renderer = CodecSurface(surface)
            codec.start()
            codecStarted = true
            val bufferInfo = MediaCodec.BufferInfo()

            fun drain(endOfStream: Boolean) {
                val deadline = System.nanoTime() + 30_000_000_000L
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "Export cancelled" }
                    when (val index = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            check(System.nanoTime() < deadline) { "Video encoder timed out" }
                        }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder changed format twice" }
                            track = writer.addTrack(codec.outputFormat)
                            writer.start()
                            muxerStarted = true
                        }
                        else -> if (index >= 0) {
                            try {
                                val buffer = checkNotNull(codec.getOutputBuffer(index))
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    configBuffers++
                                    bufferInfo.size = 0
                                }
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosFlags = bufferInfo.flags
                                if (bufferInfo.size > 0) {
                                    check(muxerStarted) { "Encoder emitted data before its format" }
                                    buffer.position(bufferInfo.offset)
                                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                                    val sample = MediaCodec.BufferInfo().apply {
                                        set(bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs,
                                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                                    }
                                    writer.writeSampleData(track, buffer, sample)
                                    sampleCount++
                                    if (recentSamplePts.size == 8) recentSamplePts.removeFirst()
                                    recentSamplePts.addLast(sample.presentationTimeUs)
                                }
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            frames.forEachIndexed { index, frame ->
                check(!Thread.currentThread().isInterrupted) { "Export cancelled" }
                drain(false)
                val bitmap = checkNotNull(BitmapFactory.decodeFile(frame.path)) { "Unreadable photo: ${frame.name}" }
                try {
                    require(bitmap.width == VIDEO_WIDTH && bitmap.height == VIDEO_HEIGHT) { "Unexpected photo dimensions" }
                    renderer.draw(bitmap, CaptureTiming.presentationTimeUs(index, fps) * 1_000L)
                    submittedFrames++
                } finally {
                    bitmap.recycle()
                }
                drain(false)
                onProgress((index + 1) * 100 / frames.size)
            }
            codec.signalEndOfInputStream()
            drain(true)
            check(muxerStarted && sampleCount == frames.size) {
                "Encoder produced an unexpected frame count: codec=${codec.name}, fps=$fps, " +
                    "expected=${frames.size}, submitted=$submittedFrames, samples=$sampleCount, " +
                    "configBuffers=$configBuffers, eosFlags=$eosFlags, recentPtsUs=$recentSamplePts"
            }
            // MediaMuxer uses a zero-sized EOS sample's PTS as the final sample end time.
            // This gives a one-photo video nonzero duration WITHOUT duplicating an image sample.
            writer.writeSampleData(track, ByteBuffer.allocate(0), MediaCodec.BufferInfo().apply {
                set(0, 0, CaptureTiming.presentationTimeUs(frames.size, fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            })
            writer.stop()
            muxerStarted = false
        } finally {
            runCatching { renderer?.close() }
            runCatching { surface?.release() }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

}
