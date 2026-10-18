package com.sinh.timelapse

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.util.concurrent.Executors

/** State survives Activity recreation. Disk, JPEG processing and encoding never run on the UI thread. */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val loading: Boolean = true,
        val session: CaptureSession? = null,
        val frames: Int = 0,
        val capturing: Boolean = false,
        val savingPhoto: Boolean = false,
        val working: Boolean = false,
        val exporting: Boolean = false,
        val progress: Int = 0,
        val message: String = "Loading saved session…",
        val video: Uri? = null,
    ) {
        val busy: Boolean get() = loading || working || savingPhoto || exporting
    }

    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState
    private val current: State get() = checkNotNull(mutableState.value)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val store = SessionStore(application.filesDir)
    private var imageCapture: ImageCapture? = null
    private var foreground = false
    private var cadenceStart = 0L
    @Volatile private var closed = false
    private val nextCapture = Runnable { capturePhoto() }

    init {
        worker.execute {
            try {
                val session = store.latest()
                val frames = session?.let { store.frames(it).size } ?: 0
                val video = application.getSharedPreferences("exports", 0).getString("lastVideo", null)?.let(Uri::parse)
                post {
                    update(current.copy(loading = false, session = session, frames = frames, video = video,
                        message = if (session == null) "Ready. Choose an interval, then start capture."
                        else "Saved session recovered. Resume capture or export."))
                }
            } catch (error: Exception) {
                post { fail(error) }
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) stop("Capture paused because the app left the foreground.")
    }

    fun attachCamera(capture: ImageCapture?) {
        imageCapture = capture
        if (capture == null) stop("Camera unavailable. Saved photos are kept.")
    }

    fun start(intervalMillis: Long, fps: Int) {
        if (current.busy || current.capturing) return
        if (!foreground || imageCapture == null) {
            update(current.copy(message = "Allow camera access and wait for the preview first."))
            return
        }
        if (current.session != null) {
            beginCapture()
            return
        }
        update(current.copy(working = true, message = "Creating session…"))
        worker.execute {
            try {
                val session = store.create(intervalMillis, fps)
                post {
                    update(current.copy(working = false, session = session, frames = 0))
                    if (foreground && imageCapture != null) beginCapture()
                    else update(current.copy(message = "Session ready. Tap Resume capture."))
                }
            } catch (error: Exception) {
                post { fail(error) }
            }
        }
    }

    private fun beginCapture() {
        cadenceStart = SystemClock.elapsedRealtime()
        update(current.copy(capturing = true, message = "Capturing. Keep the app open and phone steady."))
        capturePhoto()
    }

    fun stop(message: String = "Capture paused. Export or resume when ready.") {
        main.removeCallbacks(nextCapture)
        if (current.capturing) update(current.copy(capturing = false, message = message))
    }

    private fun capturePhoto() {
        if (!current.capturing || current.savingPhoto || !foreground) return
        val camera = imageCapture ?: return stop("Camera unavailable.")
        val session = current.session ?: return
        if (StatFs(session.directory.path).availableBytes < 50L * 1024 * 1024) {
            stop("Storage is almost full. Capture stopped; export or free space.")
            return
        }
        // Wall-clock time at the shutter request, not the accelerated video's playback time.
        val capturedAt = System.currentTimeMillis()
        val rawPhoto = File(session.directory, "capture.tmp.jpg")
        update(current.copy(savingPhoto = true))
        try {
            camera.takePicture(ImageCapture.OutputFileOptions.Builder(rawPhoto).build(), worker,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try {
                            val bitmap = TimestampRenderer.render(rawPhoto, capturedAt, session.zoneId)
                            val count = try { store.saveFrame(session, bitmap, capturedAt) } finally { bitmap.recycle() }
                            post {
                                update(current.copy(frames = count, savingPhoto = false))
                                if (current.capturing) main.postDelayed(nextCapture,
                                    CaptureTiming.nextDelayMillis(cadenceStart, SystemClock.elapsedRealtime(), session.intervalMillis))
                            }
                        } catch (error: Exception) {
                            post { fail(error) }
                        } finally {
                            rawPhoto.delete()
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        rawPhoto.delete()
                        post { fail(exception) }
                    }
                })
        } catch (error: Exception) {
            rawPhoto.delete()
            fail(error)
        }
    }

    fun export() {
        val session = current.session ?: return
        if (current.busy || current.capturing || current.frames == 0) return
        update(current.copy(exporting = true, progress = 0, message = "Exporting MP4. Keep the app open…"))
        worker.execute {
            try {
                val uri = VideoExporter(getApplication()).export(session, store.frames(session)) { progress ->
                    post { update(current.copy(progress = progress)) }
                }
                getApplication<Application>().getSharedPreferences("exports", 0).edit()
                    .putString("lastVideo", uri.toString()).apply()
                post { update(current.copy(exporting = false, video = uri,
                    message = "Saved to Movies/TimestampTimelapse. Photos kept for re-export.")) }
            } catch (error: Exception) {
                post { fail(error) }
            }
        }
    }

    fun discard() {
        val session = current.session ?: return
        if (current.busy || current.capturing) return
        update(current.copy(working = true))
        worker.execute {
            try {
                store.discard(session)
                post { update(current.copy(working = false, session = null, frames = 0,
                    message = "Session photos deleted. Exported videos are unchanged.")) }
            } catch (error: Exception) {
                post { fail(error) }
            }
        }
    }

    private fun fail(error: Exception) {
        main.removeCallbacks(nextCapture)
        update(current.copy(loading = false, working = false, savingPhoto = false,
            capturing = false, exporting = false, message = "Error: ${error.message ?: error.javaClass.simpleName}. Saved photos are kept."))
    }

    private fun update(state: State) { mutableState.value = state }
    private fun post(action: () -> Unit) {
        if (!closed) main.post { if (!closed) action() }
    }

    override fun onCleared() {
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        imageCapture = null
    }
}
