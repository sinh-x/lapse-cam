package com.sinh.timelapse

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class VideoExporter(private val context: Context) {
    fun export(session: CaptureSession, frames: List<File>, onProgress: (Int) -> Unit): Uri {
        require(frames.isNotEmpty()) { "Capture at least one photo first" }
        val temporary = File.createTempFile("timelapse-", ".mp4", context.cacheDir)
        try {
            encode(temporary, frames, session.fps, onProgress)
            return publish(temporary)
        } finally {
            temporary.delete()
        }
    }

    internal fun encode(output: File, frames: List<File>, fps: Int, onProgress: (Int) -> Unit) {
        require(frames.isNotEmpty() && fps in 1..30)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var surface: Surface? = null
        var renderer: CodecSurface? = null
        var codecStarted = false
        var muxerStarted = false
        var track = -1
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
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                                if (bufferInfo.size > 0) {
                                    check(muxerStarted) { "Encoder emitted data before its format" }
                                    buffer.position(bufferInfo.offset)
                                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                                    writer.writeSampleData(track, buffer, bufferInfo)
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
                } finally {
                    bitmap.recycle()
                }
                drain(false)
                onProgress((index + 1) * 100 / frames.size)
            }
            codec.signalEndOfInputStream()
            drain(true)
            check(muxerStarted) { "Encoder produced no video" }
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

    private fun publish(file: File): Uri {
        val resolver = context.contentResolver
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Timelapse-$time-${UUID.randomUUID().toString().take(8)}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TimestampTimelapse")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
            "Could not create Gallery video"
        }
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { target ->
                file.inputStream().use { it.copyTo(target) }
            }
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null) == 1) { "Could not publish Gallery video" }
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
