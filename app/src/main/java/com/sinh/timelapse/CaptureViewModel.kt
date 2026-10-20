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
    private val monotonicMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val epochMillis: () -> Long = { System.currentTimeMillis() },
) : AndroidViewModel(application) {
    data class State(
        val loading: Boolean = true,
        val session: CaptureSession? = null,
        val selectedLens: SessionLens = SessionLens.REAR,
        val boundLens: SessionLens? = null,
        val recoveryBlocked: Boolean = false,
        val cameraMessage: String = "",
        val frames: Int = 0,
        val selectedFrames: Int = 0,
        val savedBytes: Long = 0,
        val activeMillis: Long? = null,
        val wallMillis: Long = 0,
        val unknownTail: Boolean = false,
        val thinningStage: Long = 0,
        val nominalIntervalMillis: Long = 500,
        val missedSlots: Long = 0,
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
    private val store by lazy { SessionStore(filesDirectory, epochMillis = epochMillis) }
    private var imageCapture: ImageCapture? = null
    private var foreground = false
    private var cadenceStart = 0L
    private var captureRequest = 0L
    private var lastShutter = 0L
    private var photoInFlight = false
    @Volatile private var closed = false
    private val nextCapture = Runnable { capturePhoto() }
    private val activeClock = ActiveRecordingClock(monotonicMillis)
    private var lastCheckpoint = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            val session = current.session
            if (session != null) {
                val active = if (session.version == 3) knownActiveOrPause() ?: current.activeMillis else null
                update(current.copy(activeMillis = active,
                    wallMillis = wallSpan(session.startedAt)))
                if (current.capturing && active != null && monotonicMillis() - lastCheckpoint >= 5_000) {
                    lastCheckpoint = monotonicMillis()
                    worker.execute {
                        try { store.checkpoint(session, active, true) }
                        catch (error: Exception) { post { fail(error, true) } }
                    }
                }
            }
            main.postDelayed(this, 1_000)
        }
    }
    private fun knownActiveOrPause(): Long? = try { activeClock.value() }
        catch (error: ArithmeticException) {
            activeClock.restore(current.activeMillis ?: 0)
            fail(error, true)
            null
        }

    private fun wallSpan(start: Long): Long = runCatching {
        Math.subtractExact(epochMillis(), start).coerceAtLeast(0)
    }.getOrDefault(0)

    private fun applySnapshot(snapshot: SessionStore.Snapshot) {
        update(current.copy(frames = snapshot.count, selectedFrames = snapshot.selected, savedBytes = snapshot.bytes,
            activeMillis = snapshot.activeMillis, unknownTail = snapshot.unknownTail,
            thinningStage = snapshot.stage, nominalIntervalMillis = snapshot.intervalMillis))
    }

    init { recover(); main.post(tick) }

    fun retryRecovery() {
        if (current.busy || !current.recoveryBlocked) return
        update(current.copy(loading = true, message = "Retrying saved session recovery…"))
        recover()
    }

    private fun recover() {
        worker.execute {
            try {
                val session = store.latest()
                val snapshot = session?.let { store.snapshot(it) }
                val frames = snapshot?.count ?: 0
                // Ambiguous export cleanup must not hide a valid session or block its camera.
                // Every export retries reconciliation on this same serialized worker before encoding.
                val exportRecoveryError = runCatching { VideoExporter(getApplication(), filesDirectory, cacheDirectory).reconcile() }.exceptionOrNull()
                val video = getApplication<Application>().getSharedPreferences("exports", 0).getString("lastVideo", null)?.let(Uri::parse)
                post {
                    update(current.copy(loading = false, recoveryBlocked = false, session = session,
                        selectedLens = session?.lens ?: current.selectedLens, frames = frames, video = video,
                        message = if (session == null) "Ready. Choose an interval, then start capture."
                        else "Saved session recovered. Resume capture or export."))
                    if (snapshot != null) {
                        activeClock.restore(snapshot.activeMillis ?: 0)
                        applySnapshot(snapshot)
                    }
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

    fun start(intervalMillis: Long, fps: Int) = start(SamplingConfiguration.Fixed(Math.toIntExact(intervalMillis / 1000), fps))

    fun start(configuration: SamplingConfiguration, appearance: TimestampAppearance = TimestampAppearance()) {
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
                val session = store.create(configuration, lens, appearance)
                val snapshot = store.snapshot(session)
                post {
                    activeClock.restore(0)
                    update(current.copy(working = false, session = session, frames = 0, missedSlots = 0))
                    applySnapshot(snapshot)
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
        cadenceStart = monotonicMillis()
        activeClock.start()
        current.session?.let { session ->
            val active = knownActiveOrPause() ?: return
            worker.execute {
                try { store.checkpoint(session, active, true) }
                catch (error: Exception) { post { fail(error, true) } }
            }
        }
        update(current.copy(capturing = true, message = "Capturing. Keep the app open and phone steady."))
        capturePhoto()
    }

    fun stop(message: String = "Capture paused. Export or resume when ready.") {
        captureRequest++
        main.removeCallbacks(nextCapture)
        if (current.capturing) {
            var pauseMessage = message
            val active = try { activeClock.stop() } catch (_: ArithmeticException) {
                val known = current.activeMillis ?: 0
                activeClock.restore(known)
                update(current.copy(recoveryBlocked = true))
                pauseMessage = "Active-time arithmetic exhausted. Paused with photos retained; inspect recovery metadata."
                known
            }
            val session = current.session
            update(current.copy(capturing = false, activeMillis = if (session?.version == 3) active else null, message = pauseMessage))
            if (session != null) worker.execute {
                try { store.checkpoint(session, active, false) }
                catch (error: Exception) { post { fail(error, true) } }
            }
        }
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
        val capturedAt = epochMillis()
        val active = knownActiveOrPause() ?: return
        val request = captureRequest
        worker.execute {
            try {
                val intent = if (session.version == 3) store.beginFrame(session, capturedAt, active) else null
                post {
                    if (request != captureRequest || !current.capturing || !cameraReady()) update(current.copy(savingPhoto = false))
                    else takePreparedPhoto(camera, session, capturedAt, intent)
                }
            } catch (error: Exception) { post { fail(error, true) } }
        }
    }

    private fun takePreparedPhoto(camera: ImageCapture, session: CaptureSession, capturedAt: Long, intent: SessionStore.FrameIntent?) {
        val rawPhoto = File(session.directory, "capture.tmp.jpg")
        lastShutter = monotonicMillis()
        photoInFlight = true
        try {
            takePhoto(camera, rawPhoto, worker,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try {
                            val bitmap = TimestampRenderer.render(rawPhoto, capturedAt, session.zoneId, session.lens, session.appearance)
                            val snapshot = try {
                                if (intent != null) store.completeFrame(session, intent, bitmap, activeClock.value())
                                else { store.saveFrame(session, bitmap, capturedAt); store.snapshot(session) }
                            } finally { bitmap.recycle() }
                            post {
                                photoInFlight = false
                                val active = current.activeMillis
                                applySnapshot(snapshot)
                                update(current.copy(savingPhoto = false, activeMillis = active))
                                if (current.capturing) {
                                    val now = monotonicMillis()
                                    val elapsed = (now - lastShutter).coerceAtLeast(0)
                                    val missed = (elapsed / snapshot.intervalMillis - 1).coerceAtLeast(0)
                                    val totalMissed = if (current.missedSlots > Long.MAX_VALUE - missed) Long.MAX_VALUE else current.missedSlots + missed
                                    update(current.copy(missedSlots = totalMissed))
                                    main.postDelayed(nextCapture,
                                        CaptureTiming.nextDelayMillis(cadenceStart, now, snapshot.intervalMillis))
                                }
                            }
                        } catch (error: Exception) {
                            post { photoInFlight = false; fail(error, session.version == 3) }
                        } finally {
                            rawPhoto.delete()
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        rawPhoto.delete()
                        post { photoInFlight = false; fail(exception) }
                    }
                })
        } catch (error: Exception) {
            worker.execute {
                rawPhoto.delete()
                post { photoInFlight = false; fail(error) }
            }
        }
    }

    fun export() {
        val session = current.session ?: return
        if (current.busy || current.capturing || current.recoveryBlocked || current.frames == 0) return
        update(current.copy(exporting = true, progress = 0, message = "Exporting MP4. Keep the app open…"))
        worker.execute {
            try {
                val frames = store.exportFrames(session)
                check(ExportSpace.hasHeadroom(availableBytes(session.directory), frames.size.toLong(), session.fps)) {
                    "Insufficient export headroom. Free space and retry"
                }
                val uri = VideoExporter(getApplication(), filesDirectory, cacheDirectory).export(session, frames) { progress ->
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
                post { activeClock.restore(0); update(current.copy(working = false, session = null, frames = 0,
                    selectedFrames = 0, savedBytes = 0, activeMillis = null, wallMillis = 0, unknownTail = false, thinningStage = 0,
                    message = "Session photos deleted. Exported videos are unchanged.")) }
            } catch (error: Exception) {
                post { fail(error) }
            }
        }
    }

    private fun fail(error: Exception, blockRecovery: Boolean = false) {
        stop()
        main.removeCallbacks(nextCapture)
        update(current.copy(loading = false, working = false, savingPhoto = photoInFlight,
            capturing = false, exporting = false, recoveryBlocked = current.recoveryBlocked || blockRecovery, message = "Error: ${error.message ?: error.javaClass.simpleName}. Saved photos are kept."))
    }

    private fun update(state: State) { mutableState.value = state }
    private fun post(action: () -> Unit) {
        if (!closed) main.post { if (!closed) action() }
    }

    override fun onCleared() {
        stop()
        closed = true
        main.removeCallbacksAndMessages(null)
        // Drain already-queued pause/checkpoint writes rather than dropping known active time.
        worker.shutdown()
        imageCapture = null
    }
}
