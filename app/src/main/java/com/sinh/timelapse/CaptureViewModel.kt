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
        val messageImpact: CaptureMessageImpact = CaptureMessageImpact.ROUTINE,
        val queueSafetyBlocked: Boolean = false,
        val sessionSafetyBlocked: Boolean = false,
        val video: Uri? = null,
        val switching: Boolean = false,
    ) {
        val blackoutSafetyError: Boolean get() = recoveryBlocked || cameraMessage.isNotEmpty() ||
            queueSafetyBlocked || sessionSafetyBlocked || messageImpact == CaptureMessageImpact.CAPTURE_SAFETY ||
            messageImpact == CaptureMessageImpact.UNKNOWN
        val busy: Boolean get() = loading || working || savingPhoto || exporting
        val lens: SessionLens get() = session?.lens ?: selectedLens
        val canSelectLens: Boolean get() = CapturePolicy.canSelectLens(session != null, busy, recoveryBlocked)
    }

    internal val attention = DisplayAttention()
    private var attentionHydrated = false // Only completed repository recovery seeds saved backlog.
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
    private var manualRevision = 0L
    private var lastReviewKey: List<Any?>? = null
    private var recordReviewKey: Pair<Long, Boolean>? = null
    private var reviewRecords = emptyList<SessionRecord>()
    private var recordReviews = emptyList<SessionReview>()
    private var recordOperations = emptyMap<String, String>()
    private var outputReviewDiagnostic: String? = null
    private var attentionRevision = -1L
    private var manualActive: ActiveProcessing? = null
    private var deletionActive: ActiveProcessing? = null
    private val manualIssues = mutableMapOf<String, SessionIssue>()
    private val pendingManualEvidence = mutableMapOf<String, ManualOutputFailure>()
    private val mutableProcessing = MutableLiveData<List<ActiveProcessing>>(emptyList())
    val processing: LiveData<List<ActiveProcessing>> = mutableProcessing
    private data class ReviewDelivery(val values: List<SessionReview>, val active: List<ActiveProcessing>)
    private val reviewUpdates = LatestUpdate<ReviewDelivery>(Executor { main.post(it) }) { delivery ->
        if (!closed) {
            val projected = delivery.values.map { review ->
                if (review.id == current.session?.directory?.name) review.copy(
                    status = if (current.capturing) "Active" else if (current.switching) "Closing" else "Paused",
                    activeMillis = current.activeMillis ?: review.activeMillis,
                    canDelete = !current.busy && !current.capturing && !current.switching && !current.recoveryBlocked)
                else review
            }
            if (mutableSessions.value != projected) mutableSessions.value = projected
            if (mutableProcessing.value != delivery.active) mutableProcessing.value = delivery.active
            delivery.active.firstOrNull { it.sessionId == current.session?.directory?.name && current.exporting }?.let {
                if (current.progress != (it.percent ?: 0)) update(current.copy(progress = it.percent ?: 0))
            }
        }
    }
    private val maintenancePending = java.util.concurrent.atomic.AtomicBoolean()
    private val manualProgress = LatestUpdate<ActiveProcessing>(worker) { progress ->
        val active = manualActive
        if (active?.operation == progress.operation && active.stage == ProcessingStage.EXPORTING &&
            (progress.percent ?: 0) >= (active.percent ?: 0)) { manualActive = progress; publishSessions() }
    }
    private val manualReviews = linkedMapOf<String, SessionReview>()
    private val outputEvidence by lazy { OutputObservationStore(filesDirectory) }
    val outputReviewError = MutableLiveData<String?>(null)
    private data class ReadRequest(val id: String, val token: String, val callback: ((Uri?) -> Unit)? = null)
    private var readInFlight: Pair<ReadRequest, SessionOutputIdentity>? = null
    private var pendingRead: ReadRequest? = null
    private val readEntries = linkedMapOf<String, String>()
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
            if (maintenancePending.compareAndSet(false, true)) worker.execute {
                try {
                    exportQueue?.tick(now)
                    if (foreground) repository.records().filter { it.phase == SessionPhase.DELETING && it.error.isEmpty() }.forEach {
                        deletionActive = ActiveProcessing(it.id, it.operation, ProcessingStage.DELETING); publishSessions()
                        try { repository.continueDelete(it.id) }
                        catch (error: Exception) { repository.put(repository.get(it.id).copy(error = error.message ?: "Delete failed")) }
                        finally { deletionActive = null }
                    }
                    publishSessions()
                    post {
                        attention.resolve("queue")
                        if (current.queueSafetyBlocked) update(current.copy(queueSafetyBlocked = false,
                            messageImpact = if (current.messageImpact == CaptureMessageImpact.UNKNOWN)
                                CaptureMessageImpact.INDEPENDENT else current.messageImpact))
                    }
                }
                catch (error: Exception) { post {
                    // This boundary includes shared index writes/readback. Independence is not
                    // proven on an exception; block display until a complete successful pass.
                    val detail = "Session queue needs attention: ${error.message}. Photos retained."
                    attention.failure("queue", detail)
                    update(current.copy(message = detail, messageImpact = CaptureMessageImpact.UNKNOWN, queueSafetyBlocked = true))
                } }
                finally { maintenancePending.set(false) }
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
        update(current.copy(loading = true, messageImpact = CaptureMessageImpact.ROUTINE, message = "Retrying saved session recovery…"))
        recover()
    }

    private fun recover() {
        worker.execute {
            try {
                check(exportQueue?.idle != false) { "Wait for the bounded export/cleanup step to finish before recovery" }
                exportQueue?.stopAdmission()
                repository.recover()
                val recoveredRecords = repository.records()
                post {
                    val unsafe = SessionAttentionPolicy.observe(attention, recoveredRecords, baseline = !attentionHydrated)
                    attentionHydrated = true
                    if (current.sessionSafetyBlocked != unsafe) update(current.copy(sessionSafetyBlocked = unsafe))
                }
                manualReviews.clear()
                val indexedIds = recoveredRecords.mapTo(HashSet()) { it.id }
                store.directories().filter { directory -> directory.name !in indexedIds }.forEach { directory ->
                    val saved = store.load(directory, false)
                    refreshManualReview(saved, store.savedSummary(saved))
                }
                val session = repository.current() ?: store.latest(repository.records().map { it.id }.toSet())
                val snapshot = session?.let { store.snapshot(it) }
                val frames = snapshot?.count ?: 0
                // Ambiguous export cleanup must not hide a valid session or block its camera.
                // Every export retries reconciliation on this same serialized worker before encoding.
                val exporter = VideoExporter(getApplication(), filesDirectory, cacheDirectory).apply {
                    stageChanged = { operation, stage -> worker.execute { exportQueue?.reportStage(stage, operation) } }
                }
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
                        messageImpact = CaptureMessageImpact.ROUTINE,
                        message = if (session == null) "Ready. Choose an interval, then start capture."
                        else "Saved session recovered. Resume capture or export."))
                    if (snapshot != null) {
                        activeClock.restore(snapshot.activeMillis ?: 0)
                        applySnapshot(snapshot)
                    }
                    if (exportRecoveryError != null) {
                        val detail = "Export recovery needs attention: ${exportRecoveryError.message}. Outputs retained; Export retries recovery."
                        attention.failure("export-recovery", detail)
                        update(current.copy(message = detail, messageImpact = CaptureMessageImpact.INDEPENDENT))
                    } else attention.resolve("export-recovery")
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
        if (!usable) stop("Camera unavailable. Saved photos are kept.", CaptureMessageImpact.CAPTURE_SAFETY)
        update(current.copy(boundLens = if (usable) lens else null, cameraMessage = message))
    }

    private fun cameraReady(): Boolean = imageCapture != null && CapturePolicy.canCapture(
        current.lens, current.boundLens, foreground, current.loading || current.recoveryBlocked)

    fun start(intervalMillis: Long, fps: Int) = start(SamplingConfiguration.Fixed(Math.toIntExact(intervalMillis / 1000), fps))

    fun start(configuration: SamplingConfiguration, appearance: TimestampAppearance = TimestampAppearance(),
        split: SessionSplit = SessionSplit.OFF) {
        if (current.busy || current.capturing || current.recoveryBlocked) return
        if (!cameraReady()) {
            update(current.copy(messageImpact = CaptureMessageImpact.CAPTURE_SAFETY, message = "Allow camera access and wait for the preview first."))
            return
        }
        if (current.session != null) {
            beginCapture()
            return
        }
        val lens = current.lens
        val request = ++captureRequest
        update(current.copy(working = true, messageImpact = CaptureMessageImpact.ROUTINE, message = "Creating session…"))
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
                    else update(current.copy(messageImpact = CaptureMessageImpact.ROUTINE, message = "Session ready. Tap Resume capture."))
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
        update(current.copy(capturing = true, messageImpact = CaptureMessageImpact.ROUTINE,
            message = "Capturing. Keep the app open and phone steady."))
        capturePhoto()
    }

    fun stop(message: String = "Capture paused. Export or resume when ready.", impact: CaptureMessageImpact = CaptureMessageImpact.ROUTINE) {
        resumeAfterClose = false
        captureRequest++
        main.removeCallbacks(nextCapture)
        if (current.capturing) {
            val span = workSpan
            workSpan = null
            if (span != null) runCatching { workContext?.let { it.captureStopped(span, it.now()) } }
            var pauseMessage = message
            var pauseImpact = impact
            val active = try { activeClock.stop() } catch (_: ArithmeticException) {
                val known = current.activeMillis ?: 0
                activeClock.restore(known)
                update(current.copy(recoveryBlocked = true))
                pauseImpact = CaptureMessageImpact.CAPTURE_SAFETY
                pauseMessage = "Active-time arithmetic exhausted. Paused with photos retained; inspect recovery metadata."
                known
            }
            val session = current.session
            update(current.copy(capturing = false, activeMillis = if (session?.version == 3) active else null, messageImpact = pauseImpact, message = pauseMessage))
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
                        messageImpact = CaptureMessageImpact.ROUTINE,
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
        if (!cameraReady()) return stop("Selected camera unavailable. Saved photos are kept.", CaptureMessageImpact.CAPTURE_SAFETY)
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
                        stop("Storage is almost full. Capture stopped; export or free space.", CaptureMessageImpact.CAPTURE_SAFETY)
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
        update(current.copy(exporting = true, progress = 0, messageImpact = CaptureMessageImpact.ROUTINE, message = "Exporting MP4. Keep the app open…"))
        worker.execute {
            var admitted = false
            try {
                admitted = exportQueue?.beginManual() == true
                check(admitted) { "Automatic export busy. Try manual export when it finishes" }
                val operation = java.util.UUID.randomUUID().toString()
                manualActive = ActiveProcessing(session.directory.name, operation, ProcessingStage.EXPORTING)
                publishSessions()
                val frames = store.exportFrames(session)
                check(ExportSpace.hasHeadroom(availableBytes(session.directory), frames.size.toLong(), session.fps)) {
                    "Insufficient export headroom. Free space and retry"
                }
                mediaWorker.execute {
                    try {
                        val uri = VideoExporter(getApplication(), filesDirectory, cacheDirectory).apply {
                            stageChanged = { op, stage -> worker.execute {
                                manualActive?.takeIf { it.operation == op }?.let {
                                    manualActive = it.copy(stage = stage, percent = null); publishSessions()
                                }
                            } }
                        }.export(session, frames, operation) { progress ->
                            manualProgress.offer(ActiveProcessing(session.directory.name, operation, ProcessingStage.EXPORTING, progress.coerceIn(0, 100)))
                        }
                        rememberVideo(uri, session.directory.name)
                        worker.execute {
                            val active = manualActive?.takeIf { it.sessionId == session.directory.name && it.operation == operation }
                            if (active != null) runCatching {
                                if (outputEvidence.resolveManualFailure(session.directory.name, operation, active.operation)) {
                                    pendingManualEvidence.remove(session.directory.name); manualIssues.remove(session.directory.name)
                                }
                            }.onFailure(::reportOutputEvidenceFailure)
                            manualActive = null; refreshManualReview(session); publishSessions()
                        }
                        post { update(current.copy(exporting = false, video = uri, messageImpact = CaptureMessageImpact.ROUTINE,
                            message = "Saved to Movies/TimestampTimelapse. Photos kept for re-export.")) }
                    } catch (error: Exception) {
                        worker.execute {
                            rememberManualOutputFailure(session.directory.name, operation, error)
                            manualActive = null; publishSessions()
                        }
                        post { fail(error) }
                    }
                    finally { worker.execute { manualActive = null; exportQueue?.endManual(); publishSessions() } }
                }
            } catch (error: Exception) {
                if (admitted) manualActive?.operation?.let { rememberManualOutputFailure(session.directory.name, it, error) }
                manualActive = null; if (admitted) exportQueue?.endManual(); publishSessions()
                post { fail(error) }
            }
        }
    }

    private fun reportOutputEvidenceFailure(error: Throwable) {
        post {
            val detail = "Output review evidence unavailable: ${error.message}. Diagnostics retained where possible; count may be incomplete."
            outputReviewError.value = detail; attention.failure("output-review", detail)
        }
    }
    private fun rememberManualOutputFailure(sessionId: String, operation: String, error: Exception) {
        val active = manualActive?.takeIf { it.sessionId == sessionId && it.operation == operation } ?: return
        val failure = ManualOutputFailure(sessionId, operation, active.stage, epochMillis().coerceAtLeast(0),
            error.message?.takeIf { it.isNotBlank() } ?: "Manual export failed; retained source photos unchanged")
        pendingManualEvidence[sessionId] = failure; manualIssues[sessionId] = failure.issue
        runCatching {
            if (outputEvidence.recordManualFailure(failure, active.operation)) pendingManualEvidence.remove(sessionId)
        }.onFailure(::reportOutputEvidenceFailure)
    }

    private fun rememberVideo(uri: Uri, sessionId: String? = null) {
        val editor = getApplication<Application>().getSharedPreferences("exports", 0).edit().putString("lastVideo", uri.toString())
        if (sessionId != null) editor.putString("session:$sessionId", uri.toString())
        editor.apply()
    }

    private fun refreshManualReview(session: CaptureSession, snapshot: SessionStore.Snapshot = store.snapshot(session)) {
        if (session.split != SessionSplit.OFF) return
        val output = getApplication<Application>().getSharedPreferences("exports", 0).getString("session:${session.directory.name}", null)
        val nextReview = SessionReview(session.directory.name, session.startedAt,
            when (session.configuration) { is SamplingConfiguration.Adaptive -> "Adaptive · Off"; is SamplingConfiguration.Fixed -> "Fixed"; else -> "Legacy manual" },
            (session.configuration as? SamplingConfiguration.Adaptive)?.maxSeconds, session.fps, snapshot.activeMillis,
            snapshot.unknownTail, snapshot.selected, snapshot.count, snapshot.count, snapshot.bytes, snapshot.stage,
            "Saved · manual workflow", 0, 0, 0, "", output, false, true)
        if (manualReviews[session.directory.name] != nextReview) { manualReviews[session.directory.name] = nextReview; manualRevision++ }
    }

    private fun publishSessions(running: String? = reviewRunning, progress: Int = reviewProgress) {
        reviewRunning = running
        reviewProgress = progress
        val evidenceError = runCatching { outputEvidence.load() }.exceptionOrNull()?.let {
            "Output review unavailable: ${it.message}. Existing evidence retained; attention count may be incomplete."
        }
        if (evidenceError == null) {
            manualIssues.clear()
            outputEvidence.manualFailures().forEach { manualIssues[it.sessionId] = it.issue }
        }
        pendingManualEvidence.values.forEach { manualIssues[it.sessionId] = it.issue }
        val key = listOf(repository.revision, manualRevision, reviewRunning, reviewProgress, outputEvidence.revision,
            manualActive, deletionActive, exportQueue?.active, manualIssues.toMap(), foreground, evidenceError)
        if (key == lastReviewKey) return
        val baseKey = repository.revision to foreground
        if (recordReviewKey != baseKey) {
        reviewRecords = repository.records()
        recordOperations = reviewRecords.associate { it.id to it.operation }
        recordReviews = reviewRecords.map { record ->
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
                }, 0, record.exportCycle.attempts, record.cleanupCycle.attempts,
                record.error, record.output, (record.phase == SessionPhase.NEEDS_ATTENTION && record.cycle.exhausted) ||
                    (record.phase == SessionPhase.DELETING && record.error.isNotEmpty()),
                !record.busy && record.phase !in setOf(SessionPhase.DELETED, SessionPhase.FINISHED) && !record.cycle.reserved,
                verifiedOperation = record.operation.takeIf { record.receipt != null && record.output != null },
                processing = SessionProcessingProjection.project(record, null, foreground))
        }
        recordReviewKey = baseKey
        }
        val records = reviewRecords
        val activeBySession = listOfNotNull(exportQueue?.active, deletionActive).associateBy { it.sessionId }
        val reviews = manualReviews.values.filter { it.id !in recordOperations }.map { review -> review.copy(processing = SessionProcessing(
            active = manualActive?.takeIf { it.sessionId == review.id } ?: deletionActive?.takeIf { it.sessionId == review.id },
            issues = listOfNotNull(manualIssues[review.id]), hydrated = true)) } + recordReviews.map { review ->
            val active = activeBySession[review.id]?.takeIf { it.operation == recordOperations[review.id] }
            if (active == null) review else review.copy(progress = active.percent ?: 0,
                processing = review.processing.copy(active = active, wait = ProcessingWait.NONE))
        }
        val decorated = reviews.map { review ->
            val observation = if (evidenceError == null && review.output != null) outputEvidence.observation(review.outputIdentity)
                else review.outputObservation
            val manualIssue = manualIssues[review.id]
            val processing = if (manualIssue != null && manualIssue !in review.processing.issues)
                review.processing.copy(issues = review.processing.issues + manualIssue) else review.processing
            if (observation == review.outputObservation && processing == review.processing) review
                else review.copy(outputObservation = observation, processing = processing)
        }
        val observeAttention = attentionRevision != repository.revision
        attentionRevision = repository.revision
        val observationChanged = outputReviewDiagnostic != evidenceError
        outputReviewDiagnostic = evidenceError
        if (observeAttention || observationChanged) post {
            if (outputReviewError.value != evidenceError) outputReviewError.value = evidenceError
            if (evidenceError != null) attention.failure("output-review", evidenceError) else attention.resolve("output-review")
            if (observeAttention) {
                val unsafe = SessionAttentionPolicy.observe(attention, records)
                if (current.sessionSafetyBlocked != unsafe) update(current.copy(sessionSafetyBlocked = unsafe))
            }
        }
        lastReviewKey = key // Failed loads/projections must never poison the retry/diagnostic seam.
        reviewUpdates.offer(ReviewDelivery(decorated, listOfNotNull(exportQueue?.active, manualActive, deletionActive).distinct()))
    }

    fun retrySession(id: String) {
        worker.execute {
            try {
                val record = repository.get(id)
                post { attention.resolve("retry:$id") }
                val admitted = if (record.phase == SessionPhase.DELETING && record.error.isNotEmpty()) {
                    repository.put(record.copy(error = "")); true
                } else exportQueue?.retry(id) == true
                // Only an admitted explicit cycle, never an automatic attempt or rejected tap.
                if (admitted) post { attention.resolve(SessionAttentionPolicy.key(record)) }
                publishSessions()
            }
            catch (error: Exception) { post {
                val detail = "Retry: ${error.message}"
                attention.failure("retry:$id", detail)
                update(current.copy(message = detail, messageImpact = CaptureMessageImpact.UNKNOWN, queueSafetyBlocked = true))
            } }
        }
    }

    fun deleteSessionSources(id: String) {
        val selectedCurrent = current.session?.takeIf { it.directory.name == id }
        if (selectedCurrent != null && (current.busy || current.capturing || current.switching || current.recoveryBlocked)) return
        if (selectedCurrent != null) update(current.copy(working = true))
        attention.resolve("delete:$id")
        worker.execute {
            try {
                check(foreground) { "Open the app to delete sources" }
                val existing = repository.records().firstOrNull { it.id == id }
                deletionActive = ActiveProcessing(id, existing?.operation ?: "user-delete:$id", ProcessingStage.DELETING)
                publishSessions()
                existing?.let { record -> post { attention.resolve(SessionAttentionPolicy.key(record, SessionAttentionPolicy.Category.DELETE)) } }
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
                            messageImpact = CaptureMessageImpact.ROUTINE,
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
                    val detail = "Delete stopped: ${error.message}. Remaining sources retained."
                    attention.failure(if (record?.phase == SessionPhase.DELETING) SessionAttentionPolicy.key(record) else "delete:$id", detail)
                    // Includes shared-index/store failures, so remain conservative until the
                    // next successful queue pass. Ordinary per-record delete errors are notices.
                    update(current.copy(working = if (selectedCurrent != null) false else current.working,
                        message = detail, messageImpact = CaptureMessageImpact.UNKNOWN, queueSafetyBlocked = true))
                }
                publishSessions()
            } finally { deletionActive = null; publishSessions() }
        }
    }

    fun recheckVideo(id: String, entryToken: String) {
        worker.execute { admitRead(ReadRequest(id, entryToken)) }
    }
    fun openSessionVideo(id: String, result: (Uri?) -> Unit) {
        worker.execute { admitRead(ReadRequest(id, java.util.UUID.randomUUID().toString(), result)) }
    }
    private fun outputIdentity(id: String): SessionOutputIdentity? {
        val record = repository.records().firstOrNull { it.id == id }
        return if (record != null) record.output?.let { SessionOutputIdentity(id, it, record.operation.takeIf { record.receipt != null }) }
            else manualReviews[id]?.outputIdentity
    }
    /** One admitted lightweight read plus one latest pending detail/Open request; no history scan. */
    private fun admitRead(request: ReadRequest) {
        if (closed) return
        if (readEntries[request.id] == request.token && request.callback == null) return
        val identity = outputIdentity(request.id)
        if (identity == null) { request.callback?.let { post { it(null) } }; return }
        readEntries.remove(request.id); readEntries[request.id] = request.token
        while (readEntries.size > 64) readEntries.remove(readEntries.keys.first())
        val active = readInFlight
        if (active != null) {
            if (active.second == identity) {
                if (request.callback != null) readInFlight = request to identity
            } else {
                pendingRead?.callback?.let { post { it(null) } }
                pendingRead = request
            }
            return
        }
        readInFlight = request to identity
        mediaWorker.execute {
            val at = epochMillis().coerceAtLeast(0)
            val observation = try {
                checkNotNull(getApplication<Application>().contentResolver.openAssetFileDescriptor(Uri.parse(identity.uri), "r")).use { }
                OutputObservation(identity, OutputAvailability.READABLE, at)
            } catch (error: SecurityException) {
                OutputObservation(identity, OutputAvailability.PERMISSION_BLOCKED, at, error.message ?: "Read permission denied")
            } catch (error: java.io.FileNotFoundException) {
                val missing = runCatching {
                    getApplication<Application>().contentResolver.query(Uri.parse(identity.uri), arrayOf("_id"), null, null, null)?.use { it.count == 0 } == true
                }.getOrDefault(false)
                OutputObservation(identity, if (missing) OutputAvailability.MISSING else OutputAvailability.UNREADABLE,
                    at, error.message ?: "Expected video cannot be read")
            } catch (error: java.io.IOException) {
                OutputObservation(identity, OutputAvailability.UNREADABLE, at, error.message ?: "Read failed")
            } catch (error: Exception) {
                OutputObservation(identity, OutputAvailability.UNKNOWN, at, error.message ?: "Availability check inconclusive")
            }
            worker.execute {
                val callback = readInFlight?.first?.callback
                readInFlight = null
                val current = outputIdentity(identity.sessionId)
                runCatching { outputEvidence.record(observation, current) }.onFailure { error -> post {
                    val detail = "Output review unavailable: ${error.message}. Evidence retained; count may be incomplete."
                    outputReviewError.value = detail; attention.failure("output-review", detail)
                } }
                publishSessions()
                callback?.let { post { it(if (current == identity && observation.availability == OutputAvailability.READABLE) Uri.parse(identity.uri) else null) } }
                val next = pendingRead; pendingRead = null
                if (next != null) { readEntries.remove(next.id); admitRead(next) }
            }
        }
    }

    fun discard() {
        current.session?.let { deleteSessionSources(it.directory.name) }
    }

    private fun fail(error: Exception, blockRecovery: Boolean = false) {
        stop()
        main.removeCallbacks(nextCapture)
        update(current.copy(loading = false, working = false, savingPhoto = photoInFlight,
            capturing = false, exporting = false, recoveryBlocked = current.recoveryBlocked || blockRecovery,
            messageImpact = CaptureMessageImpact.CAPTURE_SAFETY, message = "Error: ${error.message ?: error.javaClass.simpleName}. Saved photos are kept."))
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
