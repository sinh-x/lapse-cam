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
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** State survives Activity recreation. Disk, JPEG processing and encoding never run on the UI thread. */
class CaptureViewModel @JvmOverloads constructor(
    application: Application,
    private val filesDirectory: File = application.filesDir,
    private val cacheDirectory: File = application.cacheDir,
    private val availableBytes: (File) -> Long = { StatFs(it.path).availableBytes },
    private val takePhoto: (ImageCapture, File, Executor, ImageCapture.OnImageSavedCallback) -> Unit = { camera, file, executor, callback ->
        camera.takePicture(ImageCapture.OutputFileOptions.Builder(file)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = false }).build(), executor, callback)
    },
) : AndroidViewModel(application) {
    data class State(
        val loading: Boolean = true,
        val session: CaptureSession? = null,
        val selectedLens: SessionLens = SessionLens.REAR,
        val boundLens: SessionLens? = null,
        val recoveryBlocked: Boolean = false,
        val cameraMessage: String = "",
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
        val lens: SessionLens get() = session?.lens ?: selectedLens
        val canSelectLens: Boolean get() = CapturePolicy.canSelectLens(session != null, busy, recoveryBlocked)
    }

    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState
    private val current: State get() = checkNotNull(mutableState.value)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val store by lazy { SessionStore(filesDirectory) }
    private var imageCapture: ImageCapture? = null
    private var foreground = false
    private var cadenceStart = 0L
    private var captureRequest = 0L
    @Volatile private var closed = false
    private val nextCapture = Runnable { capturePhoto() }

    init { recover() }

    fun retryRecovery() {
        if (current.busy || !current.recoveryBlocked) return
        update(current.copy(loading = true, message = "Retrying saved session recovery…"))
        recover()
    }

    private fun recover() {
        worker.execute {
            try {
                val session = store.latest()
                val frames = session?.let { store.frames(it).size } ?: 0
                // Ambiguous export cleanup must not hide a valid session or block its camera.
                // Every export retries reconciliation on this same serialized worker before encoding.
                val exportRecoveryError = runCatching { VideoExporter(getApplication(), filesDirectory, cacheDirectory).reconcile() }.exceptionOrNull()
                val video = getApplication<Application>().getSharedPreferences("exports", 0).getString("lastVideo", null)?.let(Uri::parse)
                post {
                    update(current.copy(loading = false, recoveryBlocked = false, session = session,
                        selectedLens = session?.lens ?: current.selectedLens, frames = frames, video = video,
                        message = if (session == null) "Ready. Choose an interval, then start capture."
                        else "Saved session recovered. Resume capture or export."))
                    if (exportRecoveryError != null) update(current.copy(
                        message = "Export recovery needs attention: ${exportRecoveryError.message}. Outputs retained; Export retries recovery."))
                }
            } catch (error: Exception) {
                post {
                    fail(error)
                    update(current.copy(recoveryBlocked = true))
                }
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) stop("Capture paused because the app left the foreground.")
    }

    fun selectLens(lens: SessionLens) {
        if (!current.canSelectLens || current.selectedLens == lens) return
        imageCapture = null
        update(current.copy(selectedLens = lens, boundLens = null, cameraMessage = ""))
    }

    fun attachCamera(capture: ImageCapture?, lens: SessionLens? = null, message: String = "") {
        val usable = capture != null && lens == current.lens && !current.loading && !current.recoveryBlocked
        imageCapture = if (usable) capture else null
        if (!usable) stop("Camera unavailable. Saved photos are kept.")
        update(current.copy(boundLens = if (usable) lens else null, cameraMessage = message))
    }

    private fun cameraReady(): Boolean = imageCapture != null && CapturePolicy.canCapture(
        current.lens, current.boundLens, foreground, current.loading || current.recoveryBlocked)

    fun start(intervalMillis: Long, fps: Int) {
        if (current.busy || current.capturing || current.recoveryBlocked) return
        if (!cameraReady()) {
            update(current.copy(message = "Allow camera access and wait for the preview first."))
            return
        }
        if (current.session != null) {
            beginCapture()
            return
        }
        val lens = current.lens
        val request = ++captureRequest
        update(current.copy(working = true, message = "Creating session…"))
        worker.execute {
            try {
                val session = store.create(intervalMillis, fps, lens)
                post {
                    update(current.copy(working = false, session = session, frames = 0))
                    if (request == captureRequest && cameraReady()) beginCapture()
                    else update(current.copy(message = "Session ready. Tap Resume capture."))
                }
            } catch (error: Exception) {
                post {
                    fail(error)
                    update(current.copy(recoveryBlocked = true))
                }
            }
        }
    }

    private fun beginCapture() {
        if (!cameraReady()) return
        cadenceStart = SystemClock.elapsedRealtime()
        update(current.copy(capturing = true, message = "Capturing. Keep the app open and phone steady."))
        capturePhoto()
    }

    fun stop(message: String = "Capture paused. Export or resume when ready.") {
        captureRequest++
        main.removeCallbacks(nextCapture)
        if (current.capturing) update(current.copy(capturing = false, message = message))
    }

    private fun capturePhoto() {
        if (!current.capturing || current.savingPhoto || !foreground) return
        if (!cameraReady()) return stop("Selected camera unavailable. Saved photos are kept.")
        val camera = imageCapture ?: return
        val session = current.session ?: return
        val request = captureRequest
        update(current.copy(savingPhoto = true))
        worker.execute {
            try {
                val enoughSpace = CapturePolicy.hasCaptureSpace(availableBytes(session.directory))
                post {
                    // Stop/background/camera loss while checking storage must not issue a later shutter.
                    if (request != captureRequest || !current.capturing || !cameraReady()) {
                        update(current.copy(savingPhoto = false))
                    } else if (!enoughSpace) {
                        update(current.copy(savingPhoto = false))
                        stop("Storage is almost full. Capture stopped; export or free space.")
                    } else requestPhoto(camera, session)
                }
            } catch (error: Exception) {
                post { fail(error) }
            }
        }
    }

    private fun requestPhoto(camera: ImageCapture, session: CaptureSession) {
        // Wall-clock time at the shutter request, not the accelerated video's playback time.
        val capturedAt = System.currentTimeMillis()
        val rawPhoto = File(session.directory, "capture.tmp.jpg")
        try {
            takePhoto(camera, rawPhoto, worker,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try {
                            val bitmap = TimestampRenderer.render(rawPhoto, capturedAt, session.zoneId, session.lens)
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
            worker.execute {
                rawPhoto.delete()
                post { fail(error) }
            }
        }
    }

    fun export() {
        val session = current.session ?: return
        if (current.busy || current.capturing || current.frames == 0) return
        update(current.copy(exporting = true, progress = 0, message = "Exporting MP4. Keep the app open…"))
        worker.execute {
            try {
                val uri = VideoExporter(getApplication(), filesDirectory, cacheDirectory).export(session, store.frames(session)) { progress ->
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
