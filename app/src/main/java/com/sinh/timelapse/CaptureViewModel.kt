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
    private val workContext: WorkSessionCoordinator? = if (filesDirectory == application.filesDir) WorkSessionCoordinator.get(application) else null,
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
        val lastSaveMillis: Long = 0,
        val lastShutterGapMillis: Long = 0,
        val lastSwitchMillis: Long = 0,
        val capturing: Boolean = false,
        val savingPhoto: Boolean = false,
        val working: Boolean = false,
        val exporting: Boolean = false,
        val progress: Int = 0,
        val message: String = "Loading saved session…",
        val video: Uri? = null,
        val switching: Boolean = false,
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
    private val mediaWorker = Executors.newSingleThreadExecutor()
    private val mutableSessions = MutableLiveData<List<SessionReview>>(emptyList())
    val sessions: LiveData<List<SessionReview>> = mutableSessions
    @Volatile private var exportQueue: SessionExportQueue? = null
    private var reviewRunning: String? = null
    private var reviewProgress = 0
    private val manualReviews = linkedMapOf<String, SessionReview>()
    private val store by lazy { SessionStore(filesDirectory, epochMillis = epochMillis) }
    private val repository by lazy { SessionRepository(filesDirectory, store, epochMillis) }
    private var closeGate = SessionCloseGate()
    private var resumeAfterClose = false
    private var imageCapture: ImageCapture? = null
    @Volatile private var foreground = false
    private var cadenceStart = 0L
    private var captureRequest = 0L
    private var lastShutter = 0L
    private var switchingSince: Long? = null
    private var photoInFlight = false
    private var workSpan: String? = null
    private val reportedWorkSpans = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
                if (current.capturing && active != null && !session.split.allowsShutter(active)) {
                    requestClose(SessionCloseGate.Reason.ROLLOVER)
                }
                if (current.capturing && active != null && monotonicMillis() - lastCheckpoint >= 5_000) {
                    lastCheckpoint = monotonicMillis()
                    val span = workSpan
                    if (span != null) runCatching { workContext?.let { it.captureCheckpoint(span, it.now()) } }
                    worker.execute {
                        try { store.checkpoint(session, active, true); repository.checkpointSession(session, true); refreshManualReview(session) }
                        catch (error: Exception) { post { fail(error, true) } }
                    }
                }
            }
            val now = monotonicMillis()
            worker.execute {
                try {
                    exportQueue?.tick(now)
                    if (foreground) repository.records().filter { it.phase == SessionPhase.DELETING && it.error.isEmpty() }.forEach {
                        try { repository.continueDelete(it.id) }
                        catch (error: Exception) { repository.put(repository.get(it.id).copy(error = error.message ?: "Delete failed")) }
                    }
                    publishSessions()
                }
                catch (error: Exception) { post { update(current.copy(message = "Session queue needs attention: ${error.message}. Photos retained.")) } }
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
                check(exportQueue?.idle != false) { "Wait for the bounded export/cleanup step to finish before recovery" }
                exportQueue?.stopAdmission()
                repository.recover()
                manualReviews.clear()
                store.directories().filter { directory -> repository.records().none { it.id == directory.name } }.forEach { directory ->
                    val saved = store.load(directory, false)
                    refreshManualReview(saved, store.savedSummary(saved))
                }
                val session = repository.current() ?: store.latest(repository.records().map { it.id }.toSet())
                val snapshot = session?.let { store.snapshot(it) }
                val frames = snapshot?.count ?: 0
                // Ambiguous export cleanup must not hide a valid session or block its camera.
                // Every export retries reconciliation on this same serialized worker before encoding.
                val exporter = VideoExporter(getApplication(), filesDirectory, cacheDirectory)
                val exportRecoveryError = runCatching { exporter.reconcile() }.exceptionOrNull()
                exportQueue = SessionExportQueue(worker, mediaWorker, repository, exporter, availableBytes,
                    changed = { id, progress -> publishSessions(id, progress) }, published = { uri ->
                        rememberVideo(uri)
                        post { update(current.copy(video = uri)) }
                    }).also { it.setOpen(foreground, monotonicMillis()) }
                publishSessions()
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
        exportQueue?.setOpen(value, monotonicMillis())
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

    fun start(configuration: SamplingConfiguration, appearance: TimestampAppearance = TimestampAppearance(),
        split: SessionSplit = SessionSplit.OFF) {
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
                val session = store.create(configuration, lens, appearance, SessionSplit.forConfiguration(configuration, split))
                if (session.split != SessionSplit.OFF) repository.register(session) else refreshManualReview(session)
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
                try { store.checkpoint(session, active, true); repository.checkpointSession(session, true); refreshManualReview(session) }
                catch (error: Exception) { post { fail(error, true) } }
            }
        }
        workSpan = runCatching { workContext?.let { it.captureStarted(checkNotNull(current.session).directory.name, it.now()) } }.getOrNull()
        update(current.copy(capturing = true, message = "Capturing. Keep the app open and phone steady."))
        capturePhoto()
    }

    fun stop(message: String = "Capture paused. Export or resume when ready.") {
        resumeAfterClose = false
        captureRequest++
        main.removeCallbacks(nextCapture)
        if (current.capturing) {
            val span = workSpan
            workSpan = null
            if (span != null) runCatching { workContext?.let { it.captureStopped(span, it.now()) } }
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
                try { store.checkpoint(session, active, false); repository.checkpointSession(session, false); refreshManualReview(session) }
                catch (error: Exception) { post { fail(error, true) } }
            }
        }
    }

    fun finishSession() {
        if (current.session?.split == SessionSplit.OFF || current.working || current.recoveryBlocked) return
        requestClose(SessionCloseGate.Reason.FINISH)
    }

    private fun requestClose(reason: SessionCloseGate.Reason) {
        val session = current.session ?: return
        if (session.split == SessionSplit.OFF) return
        val continueCapture = reason == SessionCloseGate.Reason.ROLLOVER && current.capturing && foreground
        closeGate.request(reason)
        if (reason == SessionCloseGate.Reason.ROLLOVER && switchingSince == null) switchingSince = monotonicMillis()
        if (reason == SessionCloseGate.Reason.FINISH) switchingSince = null
        stop(if (reason == SessionCloseGate.Reason.ROLLOVER) "Switching session; draining photo…" else "Finishing; draining photo…")
        resumeAfterClose = continueCapture
        update(current.copy(switching = true))
        drainClose()
    }

    private fun drainClose() {
        if (!current.switching || current.recoveryBlocked) return
        val reason = closeGate.drain(current.savingPhoto || photoInFlight) ?: return
        val session = current.session ?: return
        val switchingStarted = monotonicMillis()
        update(current.copy(working = true))
        worker.execute {
            try {
                val next = repository.close(session, reason == SessionCloseGate.Reason.ROLLOVER)
                val snapshot = next?.let(store::snapshot)
                exportQueue?.pump()
                publishSessions()
                post {
                    val resume = resumeAfterClose && foreground
                    resumeAfterClose = false
                    closeGate = SessionCloseGate()
                    activeClock.restore(0)
                    update(current.copy(session = next, working = false, switching = false, frames = 0, selectedFrames = 0,
                        savedBytes = 0, activeMillis = if (next == null) null else 0, wallMillis = 0, unknownTail = false,
                        thinningStage = 0, nominalIntervalMillis = 500, missedSlots = 0,
                        message = "Session closed. Switching work: ${monotonicMillis() - switchingStarted}ms. Export queued; keep the app open."))
                    if (snapshot != null) applySnapshot(snapshot)
                    if (next != null && resume && cameraReady()) beginCapture() else switchingSince = null
                }
            } catch (error: Exception) { post { fail(error, true) } }
        }
    }

    private fun boundaryReached(): Boolean {
        val session = current.session ?: return false
        val active = knownActiveOrPause() ?: return true
        if (session.split.allowsShutter(active)) return false
        update(current.copy(savingPhoto = photoInFlight))
        requestClose(SessionCloseGate.Reason.ROLLOVER)
        return true
    }

    private fun capturePhoto() {
        if (!current.capturing || current.savingPhoto || !foreground || boundaryReached()) return
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
                        drainClose()
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
        if (boundaryReached()) return
        val capturedAt = epochMillis()
        val active = knownActiveOrPause() ?: return
        val request = captureRequest
        worker.execute {
            try {
                val intent = if (session.version == 3) store.beginFrame(session, capturedAt, active) else null
                post {
                    if (request != captureRequest || !current.capturing || !cameraReady()) {
                        update(current.copy(savingPhoto = false)); drainClose()
                    } else if (!boundaryReached()) takePreparedPhoto(camera, session, capturedAt, intent)
                }
            } catch (error: Exception) { post { fail(error, true) } }
        }
    }

    private fun takePreparedPhoto(camera: ImageCapture, session: CaptureSession, capturedAt: Long, intent: SessionStore.FrameIntent?) {
        val rawPhoto = File(session.directory, "capture.tmp.jpg")
        val now = monotonicMillis()
        update(current.copy(lastShutterGapMillis = if (lastShutter == 0L) 0 else (now - lastShutter).coerceAtLeast(0),
            lastSwitchMillis = switchingSince?.let { (now - it).coerceAtLeast(0) } ?: current.lastSwitchMillis))
        switchingSince = null
        lastShutter = now
        val span = workSpan
        val workShutter = runCatching { workContext?.now() }.getOrNull()
        photoInFlight = true
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            takePhoto(camera, rawPhoto, worker,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (!completed.compareAndSet(false, true)) return
                        try {
                            val bitmap = TimestampRenderer.render(rawPhoto, capturedAt, session.zoneId, session.lens, session.appearance)
                            val snapshot = try {
                                if (intent != null) store.completeFrame(session, intent, bitmap, activeClock.value())
                                else { store.saveFrame(session, bitmap, capturedAt); store.snapshot(session) }
                            } finally { bitmap.recycle() }
                            if (span != null && workShutter != null && reportedWorkSpans.add(span)) {
                                workContext?.photoCommitted(span, workShutter)
                            }
                            repository.refresh(session)
                            refreshManualReview(session, snapshot)
                            val saveLatency = (monotonicMillis() - lastShutter).coerceAtLeast(0)
                            post {
                                photoInFlight = false
                                val active = current.activeMillis
                                applySnapshot(snapshot)
                                update(current.copy(savingPhoto = false, activeMillis = active, lastSaveMillis = saveLatency))
                                drainClose()
                                if (current.capturing) {
                                    if (boundaryReached()) return@post
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
                        if (!completed.compareAndSet(false, true)) return
                        rawPhoto.delete()
                        post { photoInFlight = false; fail(exception); drainClose() }
                    }
                })
        } catch (error: Exception) {
            if (!completed.compareAndSet(false, true)) return
            worker.execute {
                rawPhoto.delete()
                post { photoInFlight = false; fail(error); drainClose() }
            }
        }
    }

    fun export() {
        val session = current.session ?: return
        if (current.busy || current.capturing || current.recoveryBlocked || current.frames == 0 || session.split != SessionSplit.OFF) return
        update(current.copy(exporting = true, progress = 0, message = "Exporting MP4. Keep the app open…"))
        worker.execute {
            try {
                check(exportQueue?.beginManual() == true) { "Automatic export busy. Try manual export when it finishes" }
                val frames = store.exportFrames(session)
                check(ExportSpace.hasHeadroom(availableBytes(session.directory), frames.size.toLong(), session.fps)) {
                    "Insufficient export headroom. Free space and retry"
                }
                mediaWorker.execute {
                    try {
                        val uri = VideoExporter(getApplication(), filesDirectory, cacheDirectory).export(session, frames) { progress ->
                            post { update(current.copy(progress = progress)) }
                        }
                        rememberVideo(uri, session.directory.name)
                        worker.execute { refreshManualReview(session); publishSessions() }
                        post { update(current.copy(exporting = false, video = uri,
                            message = "Saved to Movies/TimestampTimelapse. Photos kept for re-export.")) }
                    } catch (error: Exception) { post { fail(error) } }
                    finally { worker.execute { exportQueue?.endManual() } }
                }
            } catch (error: Exception) {
                exportQueue?.endManual()
                post { fail(error) }
            }
        }
    }

    private fun rememberVideo(uri: Uri, sessionId: String? = null) {
        val editor = getApplication<Application>().getSharedPreferences("exports", 0).edit().putString("lastVideo", uri.toString())
        if (sessionId != null) editor.putString("session:$sessionId", uri.toString())
        editor.apply()
    }

    private fun refreshManualReview(session: CaptureSession, snapshot: SessionStore.Snapshot = store.snapshot(session)) {
        if (session.split != SessionSplit.OFF) return
        val output = getApplication<Application>().getSharedPreferences("exports", 0).getString("session:${session.directory.name}", null)
        manualReviews[session.directory.name] = SessionReview(session.directory.name, session.startedAt,
            when (session.configuration) { is SamplingConfiguration.Adaptive -> "Adaptive · Off"; is SamplingConfiguration.Fixed -> "Fixed"; else -> "Legacy manual" },
            (session.configuration as? SamplingConfiguration.Adaptive)?.maxSeconds, session.fps, snapshot.activeMillis,
            snapshot.unknownTail, snapshot.selected, snapshot.count, snapshot.count, snapshot.bytes, snapshot.stage,
            "Saved · manual workflow", 0, 0, 0, "", output, false, true)
    }

    private fun publishSessions(running: String? = reviewRunning, progress: Int = reviewProgress) {
        reviewRunning = running
        reviewProgress = progress
        val records = repository.records()
        val reviews = manualReviews.values.filter { review -> records.none { it.id == review.id } } + records.map { record ->
            val session = repository.session(record)
            val remaining = repository.remaining(record)
            SessionReview(record.id, session.startedAt, when (session.configuration) {
                is SamplingConfiguration.Adaptive -> "Adaptive · ${session.split.storedValue}"
                is SamplingConfiguration.Fixed -> "Fixed"
                else -> "Legacy manual"
            },
                (session.configuration as? SamplingConfiguration.Adaptive)?.maxSeconds, session.fps,
                record.active.takeIf { session.version >= 3 }, record.unknownTail, record.selected, record.count, remaining.first,
                remaining.second, record.stage,
                if (record.cycle.remainingMillis > 0) "Retrying in ${(record.cycle.remainingMillis + 999) / 1000}s"
                else when (record.phase) {
                    SessionPhase.FINISHED -> "Finished · photos removed"
                    SessionPhase.DELETED -> "Photos deleted by user"
                    SessionPhase.NEEDS_ATTENTION -> "Needs attention"
                    else -> record.phase.name.lowercase().replaceFirstChar { it.uppercase() }
                }, if (running == record.id) progress else 0, record.exportCycle.attempts, record.cleanupCycle.attempts,
                record.error, record.output, (record.phase == SessionPhase.NEEDS_ATTENTION && record.cycle.exhausted) ||
                    (record.phase == SessionPhase.DELETING && record.error.isNotEmpty()),
                !record.busy && record.phase !in setOf(SessionPhase.DELETED, SessionPhase.FINISHED) && !record.cycle.reserved,
                verifiedOperation = record.operation.takeIf { record.receipt != null && record.output != null })
        }
        post {
            mutableSessions.value = reviews.map { review ->
                if (review.id == current.session?.directory?.name) review.copy(
                    status = if (current.capturing) "Active" else if (current.switching) "Closing" else "Paused",
                    activeMillis = current.activeMillis ?: review.activeMillis,
                    canDelete = !current.busy && !current.capturing && !current.switching && !current.recoveryBlocked)
                else review
            }
        }
    }

    fun retrySession(id: String) {
        worker.execute {
            try {
                val record = repository.get(id)
                if (record.phase == SessionPhase.DELETING && record.error.isNotEmpty()) repository.put(record.copy(error = ""))
                else exportQueue?.retry(id)
                publishSessions()
            }
            catch (error: Exception) { post { update(current.copy(message = "Retry: ${error.message}")) } }
        }
    }

    fun deleteSessionSources(id: String) {
        val selectedCurrent = current.session?.takeIf { it.directory.name == id }
        if (selectedCurrent != null && (current.busy || current.capturing || current.switching || current.recoveryBlocked)) return
        if (selectedCurrent != null) update(current.copy(working = true))
        worker.execute {
            try {
                check(foreground) { "Open the app to delete sources" }
                val existing = repository.records().firstOrNull { it.id == id }
                if (existing == null) {
                    val session = selectedCurrent ?: store.load(File(filesDirectory.canonicalFile, "sessions/$id"))
                    check(manualReviews.containsKey(id)) { "Unknown saved session" }
                    repository.deleteManual(session, manualReviews[id]?.output)
                } else {
                    if (selectedCurrent != null && existing.phase == SessionPhase.PAUSED) repository.close(selectedCurrent, false)
                    repository.deleteSources(id)
                }
                manualReviews.remove(id)
                post {
                    if (selectedCurrent != null) {
                        activeClock.restore(0)
                        update(current.copy(working = false, session = null, frames = 0, selectedFrames = 0, savedBytes = 0,
                            activeMillis = null, wallMillis = 0, unknownTail = false, thinningStage = 0,
                            message = "Source deletion confirmed. Exported videos unchanged; see Sessions for progress."))
                    }
                }
                publishSessions()
            } catch (error: Exception) {
                val record = repository.records().firstOrNull { it.id == id }
                if (record?.phase == SessionPhase.DELETING) repository.put(record.copy(error = error.message ?: "Delete failed"))
                post {
                    if (selectedCurrent != null && record?.phase == SessionPhase.DELETING) {
                        activeClock.restore(0)
                        update(current.copy(session = null, frames = 0, selectedFrames = 0, savedBytes = 0, activeMillis = null))
                    }
                    update(current.copy(working = if (selectedCurrent != null) false else current.working,
                        message = "Delete stopped: ${error.message}. Remaining sources retained."))
                }
                publishSessions()
            }
        }
    }

    fun openSessionVideo(id: String, result: (Uri?) -> Unit) {
        worker.execute {
            val output = repository.records().firstOrNull { it.id == id }?.output ?: manualReviews[id]?.output
            val uri = runCatching {
                val exact = Uri.parse(checkNotNull(output))
                checkNotNull(getApplication<Application>().contentResolver.openAssetFileDescriptor(exact, "r")).use { }
                exact
            }.getOrNull()
            post { result(uri) }
        }
    }

    fun discard() {
        current.session?.let { deleteSessionSources(it.directory.name) }
    }

    private fun fail(error: Exception, blockRecovery: Boolean = false) {
        stop()
        main.removeCallbacks(nextCapture)
        update(current.copy(loading = false, working = false, savingPhoto = photoInFlight,
            capturing = false, exporting = false, recoveryBlocked = current.recoveryBlocked || blockRecovery, message = "Error: ${error.message ?: error.javaClass.simpleName}. Saved photos are kept."))
        if (!blockRecovery) drainClose()
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
        exportQueue?.setOpen(false, monotonicMillis())
        mediaWorker.shutdown()
        // A media completion can still journal its bounded step on the owner.
        Thread {
            mediaWorker.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES)
            worker.shutdown()
        }.start()
        imageCapture = null
    }
}
