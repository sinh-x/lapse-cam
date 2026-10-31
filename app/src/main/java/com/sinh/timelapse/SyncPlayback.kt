package com.sinh.timelapse

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated bounded range reads through the same strict HTTPS transport. No local video mirror. */
internal object SyncPlayback {
    fun show(activity: Activity, client: SyncTransport, path: String, size: Long) {
        val thread = HandlerThread("private-sync-playback").apply { start() }
        val media = Handler(thread.looper)
        val main = Handler(activity.mainLooper)
        val closed = AtomicBoolean(false)
        var player: MediaPlayer? = null
        val surface = SurfaceView(activity)
        val status = TextView(activity).apply { text = "Loading authenticated archived MP4…" }
        val seek = SeekBar(activity).apply { max = 1000 }
        val toggle = Button(activity).apply { text = "Pause / Play" }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(surface, LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.density * 240).toInt()))
            addView(status); addView(seek); addView(toggle)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Archived video — capture links are not seek offsets")
            .setView(panel).setPositiveButton("Close", null).create()
        val source = object : MediaDataSource() {
            override fun getSize() = size
            @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                check(!closed.get())
                if (position >= size) return -1
                require(position >= 0 && offset >= 0 && length >= 0 && offset + length <= buffer.size)
                if (length == 0) return 0
                val n = minOf(length.toLong(), 256 * 1024L, size - position).toInt()
                val bytes = client.request("GET", path, headers = mapOf("Range" to "bytes=$position-${position + n - 1}"))
                check(bytes.size == n)
                bytes.copyInto(buffer, offset); return n
            }
            override fun close() { closed.set(true) }
        }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                media.post {
                    if (closed.get()) return@post
                    try {
                        player = MediaPlayer().apply {
                            setDataSource(source); setSurface(holder.surface)
                            setOnPreparedListener { p ->
                                if (!closed.get()) { p.start(); main.post { status.text = "Authenticated range playback; use the seek bar" } }
                            }
                            setOnErrorListener { _, _, _ -> main.post { status.text = "Playback unavailable; archive and local media unchanged" }; true }
                            prepareAsync()
                        }
                    } catch (_: Exception) { main.post { status.text = "Playback unavailable; check Wi-Fi, authorization and server" } }
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { media.post { runCatching { player?.setSurface(null) } } }
        })
        toggle.setOnClickListener { media.post { runCatching { player?.let { if (it.isPlaying) it.pause() else it.start() } } } }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) media.post { runCatching { player?.let { it.seekTo(it.duration.toLong() * progress / 1000, MediaPlayer.SEEK_CLOSEST_SYNC) } } }
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        dialog.setOnDismissListener {
            closed.set(true)
            media.post { runCatching { player?.release() }; source.close(); thread.quitSafely() }
        }
        if (activity.isDestroyed) { source.close(); thread.quitSafely() } else dialog.show()
    }
}
