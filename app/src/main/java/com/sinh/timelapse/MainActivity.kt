package com.sinh.timelapse

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.Surface
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import android.text.InputType
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.Lifecycle
import java.time.ZoneId
import java.util.Locale
import kotlin.math.min

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class MainActivity : ComponentActivity() {
    private val model: CaptureViewModel by viewModels()
    private val work by lazy { WorkSessionCoordinator.get(application) }
    private val sync by lazy { SyncCoordinator.get(application) }
    private var sharedView: SharedHistoryView? = null
    private var downloadVideo: org.json.JSONObject? = null
    private var downloadMobileConsent = false
    private var downloadPickerPending = false
    private val createSyncDownload = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val video = downloadVideo; downloadVideo = null
        if (uri != null && video != null) sync.download(video, uri, downloadMobileConsent)
        downloadMobileConsent = false
        downloadPickerPending = false
        updateBlackout()
    }
    private lateinit var preview: PreviewView
    private lateinit var clock: TimestampPreview
    private lateinit var mode: Spinner
    private lateinit var maxSeconds: EditText
    private lateinit var split: Spinner
    private lateinit var finish: Button
    private lateinit var backlog: TextView
    private var backlogSource: List<SessionReview>? = null
    private var backlogMode = -1
    private var attentionSource: List<SessionReview>? = null
    private var actionableSessionCount = 0
    private lateinit var sessionsButton: Button
    private lateinit var workButton: Button
    private lateinit var attentionNotice: TextView
    private lateinit var processingStatus: TextView
    private var lastAssociationHints: List<Triple<String, String, String?>>? = null
    private var associationReady = false
    private var lastWorkRecords: List<WorkSession>? = null
    private var sessionAttentionDetails: TextView? = null
    private var sessionsView: SessionsView? = null
    private var sessionsDialog: SessionsDialog? = null
    private var topBrowser = "camera"
    private var displayedSession: String? = null
    private lateinit var timestampSettings: Button
    private var appearance = TimestampAppearance()
    private val defaults by lazy { CaptureDefaults(getSharedPreferences("capture-defaults", 0)) }
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var statistics: TextView
    private lateinit var live: TextView
    private lateinit var workTitle: TextView
    private lateinit var workRemaining: TextView
    private lateinit var workEnd: Button
    private lateinit var capturePanel: LinearLayout
    private lateinit var timestampPanel: LinearLayout
    private var sheet: CameraSettingsSheet? = null
    private var workView: WorkSessionsView? = null
    private var workDialog: WorkDialog? = null
    private lateinit var interval: Spinner
    private lateinit var fps: Spinner
    private lateinit var lens: Spinner
    private lateinit var recovery: Button
    private lateinit var capture: Button
    private lateinit var lensSwitch: Button
    private lateinit var modeChip: Button
    private lateinit var cadenceChip: Button
    private lateinit var fpsChip: Button
    private var cameraFocus: CameraFocusController? = null
    private lateinit var export: Button
    private lateinit var discard: Button
    private lateinit var open: Button
    private lateinit var permission: Button
    private val intervals = listOf(1, 2, 5, 10, 30, 60)
    private val frameRates = listOf(15, 24, 30)
    private var requestedLens: SessionLens? = null
    private var bindingGeneration = 0L
    private var activityForeground = false
    private var activityFocused = false
    private var activityDestroyed = false
    private val blackout = BlackoutPolicy { android.os.SystemClock.elapsedRealtime() }
    private val phoneMotion = PhoneMotionPolicy { android.os.SystemClock.elapsedRealtime() }
    private val motionSensors by lazy { getSystemService(SensorManager::class.java) }
    private var motionMonitoring = false
    private var motionSensorStartedAt = 0L
    private var phoneMoving = false
    private val motionListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            if (!motionMonitoring || activityDestroyed || event.timestamp < motionSensorStartedAt) return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> phoneMotion.acceleration(event.values[0], event.values[1], event.values[2])
                Sensor.TYPE_GYROSCOPE -> phoneMotion.rotation(event.values[0], event.values[1], event.values[2])
            }
            if (phoneMoving != phoneMotion.moving) {
                phoneMoving = phoneMotion.moving
                updateBlackout()
            }
        }
    }
    private lateinit var cameraUi: ViewGroup
    private lateinit var blackoutCover: FrameLayout
    private lateinit var blackoutBanner: TextView
    private lateinit var blackoutTitle: TextView
    private lateinit var blackoutRemaining: TextView
    private lateinit var blackoutRemainingLabel: TextView
    private lateinit var blackoutRecording: TextView
    private lateinit var blackoutToggle: CheckBox
    private var blackoutLive = false
    private var blackoutCameraReady = false
    private var blackoutError = false
    private var modalDepth = 0
    private var touchActive = false
    private var consumingTouch = false
    private val heldKeys = mutableSetOf<Int>()
    private val consumedKeys = mutableSetOf<Int>()
    private var armedBlackout: BlackoutPolicy.Timer? = null
    private var blackoutCallback: Runnable? = null
    private var savedBarAppearance = 0
    private var savedBarBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
    private val blackoutBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { returnFromBlackout() }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var nextWorkTick = 0L
    private var pendingWorkId: String? = null
    private val clockTick = object : Runnable {
        override fun run() {
            clock.zone = model.state.value?.session?.zoneId ?: ZoneId.systemDefault().id
            clock.epochMillis = System.currentTimeMillis()
            clock.appearance = model.state.value?.session?.appearance ?: appearance
            clock.invalidate()
            if (android.os.SystemClock.elapsedRealtime() >= nextWorkTick) {
                nextWorkTick = android.os.SystemClock.elapsedRealtime() + 1000
                work.reconcile()
                renderWork()
                sessionsView?.refreshDates()
                workView?.refreshDates()
            }
            handler.postDelayed(this, 250)
        }
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        work.reconcile()
        if (allowed) Toast.makeText(this, "Notification permission allowed. See End reminder settings for background timing status.", Toast.LENGTH_LONG).show()
        else showBlackoutModal(AlertDialog.Builder(this).setTitle("Notifications are not allowed")
            .setMessage("The timebox still works, but its end alert cannot appear. You can enable this app's notifications in Android settings. Background timing is a separate setting.")
            .setNegativeButton("Not now", null).setPositiveButton("Open notification settings") { _, _ -> openTimeboxNotificationSettings() }.create())
    }
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) { invalidateCamera(); bindCamera() }
        else {
            invalidateCamera(); permission.visibility = View.VISIBLE
            Toast.makeText(this, "Camera access is required to capture. You can still export saved photos.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, blackoutBack)
        // Use Android's public Window boundary; ComponentActivity's key override is restricted.
        val originalCallback = window.callback
        window.callback = object : Window.Callback by originalCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                dispatchBlackoutKey(event) { originalCallback.dispatchKeyEvent(event) }
        }
        model.state.observe(this) { state ->
            renderCapture(state); bindCamera()
            sessionsView?.loading(!state.loading && !state.recoveryBlocked,
                if (state.recoveryBlocked) "Session metadata unavailable. Retained data; see diagnostics and recovery on Camera." else "Loading saved sessions…")
            val ready = !state.loading && !state.recoveryBlocked
            if (ready && !associationReady) requestAssociationBackfill(model.sessions.value.orEmpty())
            associationReady = ready
            renderBacklog()
            renderProcessing()
        }
        model.sessions.observe(this) { sessions ->
            sessionsView?.render(sessions)
            if (model.state.value?.let { !it.loading && !it.recoveryBlocked } == true) requestAssociationBackfill(sessions)
            renderProcessing()
            renderBacklog()
            renderWork()
        }
        work.state.observe(this) { renderWork(); sessionsView?.render(model.sessions.value.orEmpty()) }
        model.outputReviewError.observe(this) { sessionsView?.evidenceUnavailable(it != null); renderAttentionDestinations() }
        model.processing.observe(this) { renderProcessing() }
        sync.state.observe(this) { sharedView?.render(it) }
        downloadVideo = savedInstanceState?.getString("syncDownloadVideo")?.let { org.json.JSONObject(it) }
        downloadMobileConsent = savedInstanceState?.getBoolean("syncDownloadMobile") ?: false
        downloadPickerPending = savedInstanceState?.getBoolean("syncDownloadPending") ?: false
        updateBlackout()
        pendingWorkId = intent.getStringExtra("workId")
        fun restoreTimeboxes() {
            if (savedInstanceState?.getBoolean("workOpen") != true) return
            showWork(savedInstanceState.getString("workId"))
            workView?.restore(savedInstanceState.getString("workPage") ?: "list", savedInstanceState.getString("workId"), savedInstanceState.getString("workSetupReturn"), savedInstanceState.getBoolean("workArchivedView"), savedInstanceState.getString("workTypeFilter"), savedInstanceState.getString("workSort"))
            savedInstanceState.getBundle("timeboxBrowser")?.let { workView?.restoreBrowserState(it) }
        }
        fun restoreSessions() { savedInstanceState?.getBundle("sessionsBrowser")?.let { showSessions(); sessionsView?.restoreState(it) } }
        if (savedInstanceState?.getString("browserTop") == "timeboxes") { restoreSessions(); restoreTimeboxes() }
        else { restoreTimeboxes(); restoreSessions() }
        if (savedInstanceState == null) routeSessions(intent)
        if (savedInstanceState?.getBoolean("sheetOpen") == true) showSettings(savedInstanceState.getBoolean("sheetTimestamp"))
        if (!hasCameraPermission() && savedInstanceState == null) requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun renderCapture(state: CaptureViewModel.State) {
        val session = state.session
        if (session == null && displayedSession != null) split.setSelection(SessionSplit.entries.indexOf(defaults.split))
        displayedSession = session?.directory?.name
        val selectedFps = session?.fps ?: frameRates[fps.selectedItemPosition]
        val seconds = state.selectedFrames.toDouble() / selectedFps
        val active = state.activeMillis?.let(::duration) ?: if (session == null) "00:00:00" else "unavailable (legacy)"
        statistics.text = String.format(Locale.ROOT,
            "Active recording: %s%s\nWall span: %s (wall-clock based)\nEstimated video: %.3fs · %d fps\nSelected: %d / %d saved photos · %.1f MiB\nNominal interval: %.3fs · stage %d · missed slots: %d\nLast shutter gap: %dms · save completion: %dms · switch-to-shutter: %dms",
            active, if (state.unknownTail) " (last-saved; interruption tail unknown)" else "", duration(state.wallMillis), seconds,
            selectedFps, state.selectedFrames, state.frames, state.savedBytes / 1048576.0, state.nominalIntervalMillis / 1000.0,
            state.thinningStage, state.missedSlots, state.lastShutterGapMillis, state.lastSaveMillis, state.lastSwitchMillis)
        summary.text = String.format(Locale.ROOT, "Capture %s · %d photos · est. MP4 %.1fs", active, state.frames, seconds)
        // Errors stay on Camera. Routine diagnostics/export progress live in Recording details.
        val message = listOf(state.focusError, state.cameraMessage, state.message).distinct().filter { it.isNotEmpty() }.joinToString("\n")
        val safetyError = state.blackoutSafetyError
        val actionable = safetyError || state.messageImpact == CaptureMessageImpact.INDEPENDENT
        status.visibility = if (actionable) View.VISIBLE else View.GONE
        // Independent diagnostic refreshes are not a stream of new accessible incidents.
        status.accessibilityLiveRegion = if (safetyError) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
        if (status.text.toString() != message) status.text = message
        val ui = CameraUiPolicy.project(CameraUiPolicy.Input(state.capturing, session != null, state.busy, state.switching,
            state.recoveryBlocked, state.boundLens == state.lens, session?.split != null && session.split != SessionSplit.OFF, state.loading, state.working))
        capture.text = when { state.focusing -> "Holding focus…"; state.focusError.isNotEmpty() -> "Retry focus"; else -> ui.primary }
        capture.contentDescription = capture.text; capture.isEnabled = ui.enabled
        lensSwitch.isEnabled = state.canSelectLens
        lensSwitch.text = if (state.canSelectLens) "Switch camera" else "Camera locked"
        lensSwitch.contentDescription = "${state.lens.storedValue} camera. ${lensSwitch.text}"
                val liveLabel = when {
            ui.live -> "● LIVE"
            state.recoveryBlocked || state.cameraMessage.isNotEmpty() -> "Error"
            state.focusing -> "Holding focus…"
            state.focusError.isNotEmpty() -> "Focus needs attention"
            state.switching -> "Switching"
            state.working -> "Starting / Finishing"
            state.loading -> "Loading"
            session != null -> "Paused"
            else -> "Ready"
        }
        if (live.text.toString() != liveLabel) live.text = liveLabel
        val liveDescription = if (ui.live) "Recording timelapse" else liveLabel
        if (live.contentDescription != liveDescription) live.contentDescription = liveDescription
        live.setBackgroundColor(if (ui.live) Color.rgb(170, 0, 0) else Color.TRANSPARENT)
        finish.visibility = if (ui.finish) View.VISIBLE else View.GONE; finish.isEnabled = ui.finishEnabled
        export.isEnabled = !ui.finish && !state.busy && !state.capturing && !state.recoveryBlocked && state.frames > 0
        export.text = if (ui.finish) "Automatic export on Finish" else if (state.exporting) "Exporting ${state.progress}%" else "Export MP4"
        discard.isEnabled = !state.busy && !state.capturing && session != null
        open.isEnabled = state.video != null && !state.exporting
        mode.isEnabled = state.canSelectLens
        maxSeconds.isEnabled = state.canSelectLens && mode.selectedItemPosition == 0
        split.isEnabled = state.canSelectLens && mode.selectedItemPosition == 0
        interval.isEnabled = state.canSelectLens && mode.selectedItemPosition == 1
        fps.isEnabled = state.canSelectLens; lens.isEnabled = state.canSelectLens
        // Settings entry remains inspectable; mutations inside the sheet remain locked.
        timestampSettings.isEnabled = true
        setChildrenEnabled(timestampPanel, state.canSelectLens)
        sheet?.setLocked(!state.canSelectLens)
        lens.setSelection(SessionLens.entries.indexOf(state.lens))
        recovery.visibility = if (state.recoveryBlocked) View.VISIBLE else View.GONE; recovery.isEnabled = !state.busy
        if (session != null) {
            mode.setSelection(if (session.configuration is SamplingConfiguration.Adaptive) 0 else 1)
            split.setSelection(SessionSplit.entries.indexOf(session.split))
            (session.configuration as? SamplingConfiguration.Adaptive)?.let { if (maxSeconds.text.toString() != it.maxSeconds.toString()) maxSeconds.setText(it.maxSeconds.toString()) }
            clock.appearance = session.appearance
            interval.setSelection(intervals.indexOf((session.intervalMillis / 1000).toInt()).coerceAtLeast(0))
            fps.setSelection(frameRates.indexOf(session.fps).coerceAtLeast(0))
            updateTimestampControls(session.appearance)
        }
        refreshSettingChips()
        renderBlackoutContent(state)
        if (state.capturing || state.savingPhoto || state.exporting || state.focusing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        blackoutLive = ui.live
        blackoutCameraReady = state.boundLens == state.lens && !state.loading && !state.recoveryBlocked && !state.switching && !state.working
        blackoutError = safetyError
        updateBlackout()
    }

    private fun buildUi(saved: Bundle?) {
        appearance = defaults.appearance()
        capturePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lens = spinner(listOf("Rear camera", "Front camera")).apply {
            contentDescription = "Session camera (fixed until session photos are deleted)"
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { model.selectLens(SessionLens.entries[position]) }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        mode = spinner(listOf("Adaptive — capped video", "Fixed interval — uncapped video")).apply { contentDescription = "Capture mode"; setSelection(saved?.getInt("mode") ?: if (defaults.adaptive) 0 else 1) }
        maxSeconds = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE); setText(saved?.getString("cap") ?: defaults.maxSeconds.toString()); contentDescription = "Maximum video duration in seconds, 5 to 600" }
        split = spinner(listOf("Off — keep photos, manual export", "2 hours", "3 hours (default)")).apply { contentDescription = "Adaptive automatic session split"; setSelection(saved?.getInt("split") ?: SessionSplit.entries.indexOf(defaults.split)) }
        interval = spinner(intervals.map { "Every ${it}s" }).apply { contentDescription = "Photo capture interval"; setSelection(saved?.getInt("interval") ?: intervals.indexOf(defaults.interval).coerceAtLeast(0)) }
        fps = spinner(frameRates.map { "$it fps" }).apply { contentDescription = "Export frames per second"; setSelection(saved?.getInt("fps") ?: frameRates.indexOf(defaults.fps).coerceAtLeast(0)) }
        listOf(label("Capture mode", 14f), mode, label("Maximum Adaptive MP4 seconds (5–600), not timebox minutes", 14f), maxSeconds,
            label("Adaptive auto-split (active recording time)", 14f), split, interval, fps).forEach { capturePanel.addView(it) }
        capturePanel.addView(label("With 2h/3h, Pause retains the session; Finish exports. Only verified output permits source cleanup. Off/Fixed/legacy remain manual. Foreground only; no cadence/storage guarantee.", 14f))
        blackoutToggle = CheckBox(this).apply {
            text = "Blackout screen while recording"; setTextColor(Color.WHITE); minHeight = dp(48)
            isChecked = defaults.blackoutEnabled
            setOnCheckedChangeListener { _, enabled -> defaults.blackoutEnabled = enabled; updateBlackout() }
        }
        capturePanel.addView(blackoutToggle)
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val enabled = model.state.value?.canSelectLens == true
                maxSeconds.isEnabled = enabled && position == 0; split.isEnabled = enabled && position == 0; interval.isEnabled = enabled && position == 1
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        timestampPanel = buildTimestampPanel()
        timestampSettings = Button(this).apply { text = "Timestamp"; AppTheme.button(this); setOnClickListener { showSettings(true) } }
        statistics = label("0 photos", 14f)
        backlog = label("Sessions", 14f)
        status = label("", 13f).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        attentionNotice = label("", 14f).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        summary = label("Capture 00:00:00 · 0 photos", 12f)
        export = Button(this).apply { text = "Export MP4"; setOnClickListener { model.export() } }
        open = Button(this).apply { text = "Open last video"; setOnClickListener { model.state.value?.video?.let(::openVideoUri) } }
        discard = Button(this).apply { text = "Delete photos / New session"; setOnClickListener {
            val id = model.state.value?.session?.directory?.name ?: return@setOnClickListener
            confirmDelete(id, "Remove only this session's inventoried photos and cancel its queued work. Exported videos are unchanged. Unknown files stop deletion.")
        } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(AppDesignPolicy.BACKGROUND); setPadding(dp(8), 0, dp(8), 0)
        }
        root.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                    blackout.interact(); updateBlackout()
                }
                return super.onRequestSendAccessibilityEvent(host, child, event)
            }
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(dp(8) + bars.left, bars.top, dp(8) + bars.right, bars.bottom); insets
        }
        val card = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        workTitle = label("No timebox running", 14f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        card.addView(workTitle, LinearLayout.LayoutParams(0, -2, 1f))
        workRemaining = label("", 16f).apply { setTextColor(AppDesignPolicy.AMBER) }
        card.addView(workRemaining)
        workButton = Button(this).apply { text = "Timeboxes"; AppTheme.button(this); setOnClickListener { showWork() } }
        card.addView(workButton)
        // End remains an explicit action in the running timebox, never an accidental strip tap.
        workEnd = Button(this).apply { text = "End"; contentDescription = "End timebox"; setOnClickListener { work.state.value?.snapshot?.running?.id?.let(work::end) } }
        AppTheme.card(card)
        val hud = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        live = label("Ready", 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        hud.addView(live, LinearLayout.LayoutParams(0, -2, 1f))
        hud.addView(Button(this).apply { text = "⚙"; contentDescription = "Camera settings"; AppTheme.button(this); setOnClickListener { showSettings() } })
        root.addView(hud); root.addView(card)
        // HUD is a sibling outside the exact output frame, never a child of the JPEG/MP4 renderer.
        val previewContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); contentDescription = "Camera preview"; minimumHeight = dp(160) }
        val videoFrame = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        videoFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        clock = TimestampPreview(this).apply { appearance = this@MainActivity.appearance }
        videoFrame.addView(clock, FrameLayout.LayoutParams(-1, -1))
        previewContainer.addView(videoFrame, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        previewContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = min(right - left, (bottom - top) * 9 / 16).coerceAtLeast(0)
            val height = width * 16 / 9
            if (videoFrame.width != width || videoFrame.height != height) videoFrame.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
        root.addView(previewContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        permission = Button(this).apply {
            text = "Enable camera"; visibility = if (hasCameraPermission()) View.GONE else View.VISIBLE
            setOnClickListener { if (hasCameraPermission()) { invalidateCamera(); bindCamera() } else requestCamera.launch(Manifest.permission.CAMERA) }
        }
        recovery = Button(this).apply { text = "Retry saved session recovery"; visibility = View.GONE; setOnClickListener { model.retryRecovery() } }
        processingStatus = label("Loading media processing status…", 13f)
        root.addView(permission); root.addView(recovery); root.addView(status); root.addView(attentionNotice); root.addView(summary)
        // Routine processing is available in Sessions, not an extra Camera row.
        modeChip = Button(this).apply { AppTheme.button(this, primary = true); setOnClickListener { showSettings() } }
        cadenceChip = Button(this).apply { AppTheme.button(this); setOnClickListener { showSettings() } }
        fpsChip = Button(this).apply { AppTheme.button(this); setOnClickListener { showSettings() } }
        root.addView(modeChip, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val chips = LinearLayout(this)
        listOf(cadenceChip, fpsChip, timestampSettings).forEach { chips.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(chips)
        capture = Button(this).apply {
            text = "Start capture"; minHeight = dp(72); minWidth = dp(72); textSize = 12f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(220, 40, 61)); setStroke(dp(3), AppDesignPolicy.TEXT) }
            setTextColor(Color.WHITE); setOnClickListener { captureAction() }
        }
        finish = Button(this).apply { text = "Finish & export"; AppTheme.button(this, primary = true); visibility = View.GONE; setOnClickListener { model.finishSession() } }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sessionsButton = Button(this).apply { text = "Sessions"; AppTheme.button(this); setOnClickListener { showSessions() } }
        lensSwitch = Button(this).apply { text = "Switch camera"; AppTheme.button(this); setOnClickListener {
            val state = model.state.value ?: return@setOnClickListener
            if (state.canSelectLens) model.selectLens(if (state.lens == SessionLens.REAR) SessionLens.FRONT else SessionLens.REAR)
        } }
        controls.addView(sessionsButton, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(capture, LinearLayout.LayoutParams(dp(96), dp(96)))
        controls.addView(lensSwitch, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        val destinations = LinearLayout(this)
        destinations.addView(Button(this).apply { text = "Details"; AppTheme.button(this); setOnClickListener { showRecordingDetails() } }, LinearLayout.LayoutParams(0, -2, 1f))
        destinations.addView(Button(this).apply { text = "Private sync"; AppTheme.button(this); setOnClickListener { showSharedHistory() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(destinations)
        root.addView(finish)
        cameraUi = root
        blackoutBanner = label("LIVE — recording", 16f).apply {
            gravity = Gravity.CENTER; background = AppTheme.shape(this, AppDesignPolicy.DANGER_SURFACE, AppDesignPolicy.DANGER_SURFACE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        blackoutCover = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE
            isClickable = true; isFocusable = true; isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Recording timelapse. Timelapse recording continues."
            setOnClickListener { returnFromBlackout() }
            setPadding(0, dp(16), 0, 0)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top + dp(16), bars.right, bars.bottom); insets
            }
            addView(blackoutBanner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        blackoutTitle = label("No timebox running", 18f).apply { gravity = Gravity.CENTER; AppTheme.text(this, muted = true) }
        blackoutRemaining = label("", 56f).apply {
            gravity = Gravity.CENTER; typeface = android.graphics.Typeface.MONOSPACE; maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(24, 56, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        blackoutRemainingLabel = label("Timebox remaining", 14f).apply { gravity = Gravity.CENTER; AppTheme.text(this, muted = true) }
        blackoutRecording = label("Active recording\n00:00:00", 18f).apply { gravity = Gravity.CENTER; typeface = android.graphics.Typeface.MONOSPACE }
        val blackoutContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(blackoutTitle); addView(blackoutRemaining, LinearLayout.LayoutParams(-1, dp(112))); addView(blackoutRemainingLabel)
        }
        blackoutCover.addView(ScrollView(this).apply { isFillViewport = true; addView(blackoutContent) },
            FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(72); bottomMargin = dp(120) })
        blackoutCover.addView(blackoutRecording, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(32) })
        // One accessible reveal node; none of the presentation children can reach hidden controls.
        listOf(blackoutTitle, blackoutRemaining, blackoutRemainingLabel, blackoutRecording).forEach {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        ViewCompat.replaceAccessibilityAction(blackoutCover, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            "Return to Camera; timelapse recording continues") { _, _ -> returnFromBlackout(); true }
        val host = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ScrollView(this@MainActivity).apply { isFillViewport = true; addView(root) }, FrameLayout.LayoutParams(-1, -1))
            addView(blackoutCover, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(host); root.requestApplyInsets(); blackoutCover.requestApplyInsets()
    }

    /** Top-level lifetime plus actual Activity focus covers nested Dialog windows too. */
    private fun showBlackoutModal(dialog: Dialog, dismissed: () -> Unit = {}) {
        modalDepth++; updateBlackout()
        dialog.setOnDismissListener { modalDepth--; dismissed(); updateBlackout() }
        dialog.show()
    }

    private fun updateMotionSensors(enabled: Boolean) {
        if (motionMonitoring == enabled) return
        motionMonitoring = enabled
        motionSensors.unregisterListener(motionListener)
        phoneMotion.reset(); phoneMoving = false
        if (enabled) {
            motionSensorStartedAt = android.os.SystemClock.elapsedRealtimeNanos()
            // Ordinary foreground rate only; no high-rate/background sensor permission.
            // Missing or denied sensors leave the existing timer/tap behavior available.
            for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
                val sensor = motionSensors.getDefaultSensor(type) ?: continue
                try { motionSensors.registerListener(motionListener, sensor, SensorManager.SENSOR_DELAY_UI, handler) }
                catch (_: RuntimeException) { /* Best-effort optional motion signal. */ }
            }
        }
    }

    private fun updateBlackout() {
        if (!::blackoutCover.isInitialized || activityDestroyed) return
        val modal = modalDepth > 0 || downloadPickerPending
        val error = blackoutError
        val deliveryAvailable = activityForeground && activityFocused && !modal && pendingWorkId == null
        val sessionNotices = model.attention.deliver(deliveryAvailable)
        val workNotices = work.attention.deliver(deliveryAvailable)
        updateMotionSensors(defaults.blackoutEnabled && blackoutLive && blackoutCameraReady &&
            activityForeground && activityFocused && !modal && !error)
        blackout.update(BlackoutPolicy.Input(
            enabled = defaults.blackoutEnabled, live = blackoutLive, cameraReady = blackoutCameraReady,
            foreground = activityForeground, focused = activityFocused,
            modal = modal, actionableError = error, moving = phoneMoving,
            // A return gesture keeps an existing cover until completion. If a real error
            // removes it first, held input still blocks rearming. Consumption markers survive
            // focus loss to swallow orphaned UP events, but are not indefinitely held input.
            interacting = (!blackout.covered || sessionNotices > 0 || workNotices > 0) && (touchActive || heldKeys.isNotEmpty()),
        ))
        if (sessionNotices > 0 || workNotices > 0) blackout.interact()
        renderBlackout() // Reveal and restore bars before exposing/announcing the notice.
        renderAttentionDestinations()
        if (sessionNotices > 0 || workNotices > 0) {
            val destinations = listOfNotNull("Sessions".takeIf { sessionNotices > 0 }, "Timeboxes".takeIf { workNotices > 0 }).joinToString(" and ")
            attentionNotice.visibility = View.VISIBLE
            attentionNotice.text = "New attention in $destinations. Open $destinations for details; recording controls are unchanged."
        }
    }

    private fun renderAttentionDestinations() {
        val values = model.sessions.value.orEmpty()
        if (attentionSource !== values) {
            attentionSource = values
            actionableSessionCount = values.distinctBy { it.id }.count { it.needsAttention }
        }
        val sessions = actionableSessionCount
        val sessionNotices = model.attention.snapshot.count
        val workCount = maxOf(work.attention.snapshot.count, if (work.state.value?.error != null) 1 else 0)
        val sessionsLabel = if (sessions > 0) "Sessions · attention ($sessions)"
            else if (sessionNotices > 0) "Sessions · notices ($sessionNotices)" else "Sessions"
        val workLabel = if (workCount > 0) "Timeboxes · attention ($workCount)" else "Timeboxes"
        if (sessionsButton.text.toString() != sessionsLabel) sessionsButton.text = sessionsLabel
        if (workButton.text.toString() != workLabel) workButton.text = workLabel
        sessionAttentionDetails?.let { view ->
            val detail = model.attention.snapshot.details.values.distinct().joinToString("\n")
            if (view.text.toString() != detail) view.text = detail
            (view.parent as? View)?.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        }
        if (sessions == 0 && sessionNotices == 0 && workCount == 0) attentionNotice.visibility = View.GONE
    }

    private fun renderBlackoutContent(captureState: CaptureViewModel.State? = model.state.value) {
        if (!::blackoutRecording.isInitialized) return
        val state = work.state.value
        val running = state?.snapshot?.running
        val display = CaptureDisplayPolicy.blackout(running?.title,
            running?.let { runCatching { WorkSessionsView.remaining(it.remaining(work.now())) }.getOrNull() },
            captureState?.activeMillis ?: if (captureState?.session == null) 0L else null,
            captureState?.unknownTail == true, state?.loaded == true && state.error == null)
        if (blackoutTitle.text.toString() != display.title) blackoutTitle.text = display.title
        if (blackoutRemaining.text.toString() != display.remaining) blackoutRemaining.text = display.remaining
        val hasCountdown = display.remaining.isNotEmpty()
        blackoutRemaining.visibility = if (hasCountdown) View.VISIBLE else View.GONE
        blackoutRemainingLabel.visibility = blackoutRemaining.visibility
        val recording = "Active recording\n${display.recording}"
        if (blackoutRecording.text.toString() != recording) blackoutRecording.text = recording
        blackoutCover.contentDescription = "Recording timelapse. ${display.title}. ${display.remaining}. $recording. Return to Camera; recording continues."
    }

    private fun renderBlackout() {
        val visible = blackout.covered
        if (::blackoutCover.isInitialized && (blackoutCover.visibility == View.VISIBLE) != visible) {
            if (visible) {
                cameraUi.clearFocus()
                cameraUi.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                cameraUi.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                window.insetsController?.let { controller ->
                    savedBarAppearance = controller.systemBarsAppearance
                    savedBarBehavior = controller.systemBarsBehavior
                    controller.setSystemBarsAppearance(0, LIGHT_SYSTEM_BARS)
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(android.view.WindowInsets.Type.systemBars())
                }
                blackoutCover.visibility = View.VISIBLE
                blackoutCover.requestApplyInsets(); blackoutCover.requestFocus()
            } else {
                blackoutCover.visibility = View.GONE
                cameraUi.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                cameraUi.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                // Neutral focus: never restore Pause/Finish or a navigation button.
                cameraUi.isFocusableInTouchMode = true; cameraUi.requestFocus()
                window.insetsController?.let { controller ->
                    controller.show(android.view.WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior = savedBarBehavior
                    controller.setSystemBarsAppearance(savedBarAppearance, LIGHT_SYSTEM_BARS)
                }
            }
        }
        blackoutBack.isEnabled = visible
        val timer = blackout.timer
        if (timer != armedBlackout) {
            blackoutCallback?.let(handler::removeCallbacks)
            armedBlackout = timer
            blackoutCallback = timer?.let { expected -> Runnable {
                if (activityDestroyed || armedBlackout != expected) return@Runnable
                // Latest model/window signals are applied before accepting this generation.
                updateBlackout()
                blackout.timeout(expected)
                renderBlackout()
            } }
            blackoutCallback?.let { handler.postDelayed(it, (timer!!.deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
        }
    }

    private fun returnFromBlackout() {
        // Accessibility/programmatic clicks cannot split a still-held physical gesture/key.
        if (touchActive || heldKeys.isNotEmpty()) return
        blackout.interact(); updateBlackout()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        activityFocused = hasFocus
        if (!hasFocus) { touchActive = false; heldKeys.clear() }
        updateBlackout()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (consumingTouch || blackout.covered) {
            consumingTouch = true
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchActive = true
            // Keep consuming even if an error/pause removes the cover during this gesture.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                consumingTouch = false; touchActive = false
                blackout.interact(); updateBlackout()
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchActive = true; blackout.interact(); updateBlackout()
        }
        return try { super.dispatchTouchEvent(event) } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchActive = false; blackout.interact(); updateBlackout()
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (blackout.covered) {
            // Hover must still reach the accessible return node; scrolling cannot reach Camera.
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) return true
        } else {
            blackout.interact(); updateBlackout()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun dispatchBlackoutKey(event: KeyEvent, dispatch: () -> Boolean): Boolean {
        val key = event.keyCode
        val activation = key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            key == KeyEvent.KEYCODE_SPACE || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_BACK
        if (key in consumedKeys || blackout.covered) {
            if (event.action == KeyEvent.ACTION_DOWN) { consumedKeys.add(key); heldKeys.add(key) }
            if (event.action == KeyEvent.ACTION_UP) {
                consumedKeys.remove(key); heldKeys.remove(key)
                if (activation && heldKeys.isEmpty()) returnFromBlackout() else updateBlackout()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) { heldKeys.add(key); blackout.interact(); updateBlackout() }
        return try { dispatch() } finally {
            if (event.action == KeyEvent.ACTION_UP) { heldKeys.remove(key); blackout.interact(); updateBlackout() }
        }
    }

    private fun captureAction() {
        if (model.state.value?.capturing == true) model.stop()
        else if (model.state.value?.session != null) model.start(1_000, 30)
        else try {
            val rate = frameRates[fps.selectedItemPosition]
            val config = if (mode.selectedItemPosition == 0) SamplingConfiguration.parseAdaptive(maxSeconds.text.toString(), rate.toString())
                else SamplingConfiguration.Fixed(intervals[interval.selectedItemPosition], rate)
            defaults.rememberCapture(config, intervals[interval.selectedItemPosition], SessionSplit.entries[split.selectedItemPosition])
            defaults.rememberAppearance(appearance)
            model.start(config, appearance, SessionSplit.entries[split.selectedItemPosition])
        } catch (_: IllegalArgumentException) {
            showSettings(); maxSeconds.error = "Enter a whole number from 5 to 600"; maxSeconds.requestFocus()
            Toast.makeText(this, "Invalid settings; no session created.", Toast.LENGTH_LONG).show()
        }
    }
    private fun showSettings(timestamp: Boolean = false) {
        if (sheet?.isShowing == true) return
        sheet = CameraSettingsSheet(this, capturePanel, timestampPanel, timestamp).apply {
            setLocked(model.state.value?.canSelectLens != true)
            showBlackoutModal(this) { sheet = null; refreshSettingChips() }
        }
    }
    private fun refreshSettingChips() {
        val locked = model.state.value?.canSelectLens != true
        modeChip.text = (if (mode.selectedItemPosition == 0) "ADAPTIVE" else "FIXED") + if (locked) " · Locked" else " ›"
        cadenceChip.text = if (mode.selectedItemPosition == 0) "${maxSeconds.text}s video cap" else "${intervals[interval.selectedItemPosition]}s interval"
        fpsChip.text = "${frameRates[fps.selectedItemPosition]} fps"
    }
    private var stampFields: List<Spinner> = emptyList()
    private var stampDate: CheckBox? = null
    private var updatingStamp = false
    private fun buildTimestampPanel(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val family = spinner(listOf("Monospace", "Sans-serif", "Serif"))
        val weight = spinner(listOf("Regular", "Bold"))
        val size = spinner(listOf("32 output pixels", "44 output pixels", "60 output pixels"))
        val position = spinner(listOf("Top left", "Top center", "Top right", "Bottom left", "Bottom center", "Bottom right"))
        val date = CheckBox(this).apply { text = "Date yyyy-MM-dd above HH:mm:ss"; setTextColor(Color.WHITE); minHeight = dp(48) }
        stampFields = listOf(family, weight, size, position); stampDate = date
        updateTimestampControls(appearance)
        fun changed() {
            if (updatingStamp || model.state.value?.canSelectLens != true) return
            appearance = TimestampAppearance(StampFamily.entries[family.selectedItemPosition], weight.selectedItemPosition == 1,
                listOf(32, 44, 60)[size.selectedItemPosition], StampPosition.entries[position.selectedItemPosition], date.isChecked)
            clock.appearance = appearance; clock.invalidate(); defaults.rememberAppearance(appearance)
        }
        panel.addView(label("Exact output-space geometry; timestamp appearance is frozen at Start.", 14f))
        listOf("Font family" to family, "Font style" to weight, "Output size" to size, "Position" to position).forEach { (name, field) ->
            field.contentDescription = "Timestamp $name"; panel.addView(label(name, 14f)); panel.addView(field)
            field.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = changed()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        panel.addView(date); date.setOnCheckedChangeListener { _, _ -> changed() }
        panel.addView(Button(this).apply { text = "Reset to defaults"; setOnClickListener {
            if (model.state.value?.canSelectLens == true) {
                appearance = TimestampAppearance(); updateTimestampControls(appearance)
                clock.appearance = appearance; clock.invalidate(); defaults.rememberAppearance(appearance)
            }
        } })
        return panel
    }
    private fun updateTimestampControls(value: TimestampAppearance) {
        if (stampFields.isEmpty()) return
        updatingStamp = true
        stampFields[0].setSelection(value.family.ordinal); stampFields[1].setSelection(if (value.bold) 1 else 0)
        stampFields[2].setSelection(listOf(32, 44, 60).indexOf(value.size)); stampFields[3].setSelection(value.position.ordinal)
        stampDate?.isChecked = value.date
        updatingStamp = false
    }
    private fun setChildrenEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) setChildrenEnabled(view.getChildAt(i), enabled)
    }
    private fun showRecordingDetails() {
        (statistics.parent as? android.view.ViewGroup)?.removeView(statistics)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(Color.rgb(16, 25, 22)); addView(statistics) }
        panel.addView(label(model.state.value?.message ?: "Ready", 14f))
        showBlackoutModal(AlertDialog.Builder(this).setTitle("Recording details").setView(ScrollView(this).apply { addView(panel) }).setPositiveButton("Close", null).create())
    }
    private fun configureTimeboxNotifications() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) openTimeboxNotificationSettings()
        else requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun openTimeboxNotificationSettings() {
        val notifications = getSystemService(android.app.NotificationManager::class.java)
        val settings = if (notifications.areNotificationsEnabled()) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, TimeboxAlarmScheduler.CHANNEL)
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try { startActivity(settings) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "Open Android Settings → Apps → Timestamp Timelapse → Notifications.", Toast.LENGTH_LONG).show() }
    }
    private fun configureBackgroundTiming() {
        if (getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()) {
            Toast.makeText(this, "Background reminder timing is already allowed.", Toast.LENGTH_LONG).show(); work.reconcile(); return
        }
        try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName"))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "Background timing settings are unavailable on this device. Reopen the app to reconcile expiry.", Toast.LENGTH_LONG).show() }
    }
    private fun showSharedHistory() {
        val view = SharedHistoryView(this, sync) { video, mobile ->
            downloadVideo = org.json.JSONObject(video.toString()); downloadMobileConsent = mobile
            downloadPickerPending = true; updateBlackout()
            try { createSyncDownload.launch("Timelapse-${video.getString("id")}.mp4") }
            catch (error: RuntimeException) { downloadPickerPending = false; updateBlackout(); throw error }
        }
        sharedView = view
        sync.state.value?.let(view::render)
        AlertDialog.Builder(this).setTitle("Private shared history").setView(view).setPositiveButton("Close", null)
            .create().also { showBlackoutModal(it) { sharedView = null } }
    }

    private fun showWork(id: String? = null) {
        if (workDialog != null) return
        val began = android.os.SystemClock.elapsedRealtime()
        val view = WorkSessionsView(this, work, { model.sessions.value.orEmpty() }, ::openSessionVideo,
            ::configureTimeboxNotifications, ::configureBackgroundTiming, id)
        val constructed = android.os.SystemClock.elapsedRealtime()
        workView = view; topBrowser = "timeboxes"
        modalDepth++; updateBlackout()
        val admitted = android.os.SystemClock.elapsedRealtime()
        workDialog = WorkDialog(this, view) {
            workView = null; workDialog = null; topBrowser = if (sessionsView != null) "sessions" else "camera"; modalDepth--; updateBlackout()
        }.apply { show() }
        debugBrowserTiming("Timeboxes", began, constructed, admitted)
    }
    private fun renderWork() {
        if (!::workTitle.isInitialized) return
        val state = work.state.value ?: return
        val running = state.snapshot.running
        val title = running?.title ?: "No timebox running"
        if (workTitle.text.toString() != title) workTitle.text = title
        workRemaining.text = state.error ?: if (running == null) "" else runCatching { "Remaining ${WorkSessionsView.remaining(running.remaining(work.now()))}" }.getOrDefault("")
        workRemaining.visibility = if (workRemaining.text.isEmpty()) View.GONE else View.VISIBLE
        workEnd.visibility = if (running == null) View.GONE else View.VISIBLE; workEnd.isEnabled = state.loaded
        workView?.render(state)
        renderBlackoutContent()
        if (state.loaded && lastWorkRecords !== state.snapshot.records) {
            lastWorkRecords = state.snapshot.records
            blackout.observeTerminals(state.snapshot.records.filter { it.state == WorkState.ENDED }
                .map { "${it.id}:${it.reason}:${it.endElapsed}" }.toSet())
        }
        updateBlackout()
        if (state.loaded && pendingWorkId != null) { val id = pendingWorkId; pendingWorkId = null; showWork(id) }
    }
    private fun renderBacklog() {
        val state = model.state.value
        val values = model.sessions.value.orEmpty()
        val mode = if (state?.recoveryBlocked == true) 1 else if (state?.loading != false) 0 else 2
        if (backlogSource === values && backlogMode == mode) return
        backlogSource = values; backlogMode = mode
        backlog.text = when {
            state?.recoveryBlocked == true -> "Session metadata unavailable; totals unknown. Data retained."
            state?.loading != false -> "Loading session metadata; totals not yet available."
            else -> String.format(Locale.ROOT, "Sessions: %d · retained %.1f MiB · needs attention: %d", values.size,
                values.sumOf { it.bytes } / 1048576.0, values.distinctBy { it.id }.count { it.needsAttention })
        }
    }
    private fun requestAssociationBackfill(values: List<SessionReview>) {
        val hints = values.mapNotNull { review -> review.output?.let { Triple(review.id, it, review.verifiedOperation) } }
        if (hints != lastAssociationHints) { lastAssociationHints = hints; sync.backfillAssociations(values) }
    }
    private fun renderProcessing() {
        if (!::processingStatus.isInitialized) return
        val active = model.processing.value.orEmpty()
        val values = model.sessions.value.orEmpty()
        val text = if (model.state.value?.loading != false) "Loading media processing status…"
            else if (model.state.value?.recoveryBlocked == true) "Media status unavailable; saved data retained"
            else if (active.isNotEmpty()) active.joinToString("\n") {
                "${it.stage.label} · ${it.sessionId}${it.percent?.let { p -> " · $p%" } ?: " · in progress (indeterminate)"}"
            } else if (values.any { it.processing.wait == ProcessingWait.RETRY_DELAY }) "Media processing waiting for automatic retry"
            else if (values.any { it.processing.wait != ProcessingWait.NONE }) "Media processing queued / waiting"
            else if (values.any { it.needsAttention }) "Media idle · Sessions needs attention"
            else "Media processing idle · see Sessions for completed work"
        if (processingStatus.text.toString() != text) processingStatus.text = text
        if (model.state.value?.exporting == true) export.text = active.firstOrNull()?.let {
            "${it.stage.label}${it.percent?.let { p -> " $p%" } ?: "…"}"
        } ?: "Export finishing…"
    }
    private fun showSessions() {
        if (sessionsView != null) return
        val began = android.os.SystemClock.elapsedRealtime()
        val view = SessionsView(this, open = { openSessionVideo(it.id) }, retry = { model.retrySession(it.id) }, delete = {
            confirmDelete(it.id, "Delete ${it.retained} retained source photos and cancel queued work? Published video and other sessions are unchanged. Unknown files stop deletion.")
        }, linkedWork = ::showRecordingLinks, linkSummary = { id ->
            val links = runCatching { work.state.value?.snapshot?.links(work.now())?.filter { it.recordingId == id }.orEmpty() }.getOrDefault(emptyList())
            if (work.state.value?.error != null) "Timebox context unavailable; retained" else "Linked timeboxes: ${links.map { it.workId }.distinct().size}"
        }, checkOutput = model::recheckVideo)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(backlog, processingStatus, export, open, discard).forEach { (it.parent as? ViewGroup)?.removeView(it); actions.addView(it) }
        view.addView(ScrollView(this).apply { addView(actions) }, LinearLayout.LayoutParams(-1, dp(128)))
        sessionsView = view; sessionAttentionDetails = view.diagnostics; topBrowser = "sessions"
        val state = model.state.value
        view.loading(state?.let { !it.loading && !it.recoveryBlocked } == true,
            if (state?.recoveryBlocked == true) "Session metadata unavailable. Data retained; see Camera recovery." else "Loading saved sessions…")
        view.evidenceUnavailable(model.outputReviewError.value != null)
        view.render(model.sessions.value.orEmpty())
        val constructed = android.os.SystemClock.elapsedRealtime()
        modalDepth++; updateBlackout()
        val admitted = android.os.SystemClock.elapsedRealtime()
        sessionsDialog = SessionsDialog(this, view) {
            sessionsView = null; sessionsDialog = null; sessionAttentionDetails = null
            topBrowser = if (workView != null) "timeboxes" else "camera"; modalDepth--; updateBlackout()
        }.also { it.show() }
        debugBrowserTiming("Sessions", began, constructed, admitted)
    }
    private fun debugBrowserTiming(surface: String, began: Long, constructed: Long, admitted: Long) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.util.Log.d("TTL018UI", "$surface construct=${constructed - began} modal=${admitted - constructed} window=${android.os.SystemClock.elapsedRealtime() - admitted}")
        }
    }
    private fun routeSessions(value: Intent) {
        val id = value.getStringExtra("sessionId")
        if (id != null || value.getBooleanExtra("sessionsAttention", false)) {
            showSessions()
            if (id != null) sessionsView?.showSession(id) else sessionsView?.showAttention()
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); routeSessions(intent) }
    private fun showRecordingLinks(id: String) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 25, 22)); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val links = runCatching { work.state.value?.snapshot?.links(work.now())?.filter { it.recordingId == id }.orEmpty() }.getOrDefault(emptyList())
        panel.addView(label("Capture-time context, not MP4 seek positions. ${if (links.isEmpty()) "No known overlaps." else ""}", 14f))
        links.forEach { link ->
            panel.addView(label("Timebox ${link.workId}\n${WorkSessionsView.linkText(link)}", 14f))
            panel.addView(Button(this).apply { text = "Open timebox ${link.workId}"; setOnClickListener { showWork(link.workId) } })
        }
        showBlackoutModal(AlertDialog.Builder(this).setTitle("Recording $id").setView(ScrollView(this).apply { addView(panel) }).setPositiveButton("Close", null).create())
    }
    private fun confirmDelete(id: String, explanation: String) {
        showBlackoutModal(AlertDialog.Builder(this).setTitle("Delete source photos?").setMessage("Session $id\n$explanation")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete source photos") { _, _ -> model.deleteSessionSources(id) }.create())
    }
    private fun openSessionVideo(id: String) { model.openSessionVideo(id) { uri -> if (!isDestroyed) {
        if (uri == null) {
            workView?.markUnavailable(id)
            Toast.makeText(this, "Output missing or unreadable. Timebox context and retained sources are unchanged. See Sessions for the observed state.", Toast.LENGTH_LONG).show()
        } else openVideoUri(uri)
    } } }
    private fun openVideoUri(uri: android.net.Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "No video player installed. Find the MP4 in Movies/TimestampTimelapse.", Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this, "Video unavailable. See Sessions; removed photos cannot be restored.", Toast.LENGTH_LONG).show() }
    }
    private fun invalidateCamera() { bindingGeneration++; requestedLens = null; model.attachCamera(null); cameraFocus?.release(); cameraFocus = null }
    private fun bindCamera() {
        val state = model.state.value ?: return
        if (!activityForeground || !hasCameraPermission() || state.loading || state.recoveryBlocked) return
        val selected = state.lens
        if (requestedLens == selected) return
        requestedLens = selected
        val generation = ++bindingGeneration
        model.attachCamera(null)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val latest = model.state.value ?: return@addListener
            if (!CapturePolicy.isCurrentBinding(generation, bindingGeneration, selected, latest.lens,
                    activityForeground && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                    hasCameraPermission() && !latest.loading && !latest.recoveryBlocked)) return@addListener
            try {
                val provider = future.get()
                provider.unbindAll()
                val selector = if (selected == SessionLens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                check(provider.hasCamera(selector)) { "Selected ${selected.storedValue} camera is unavailable; no fallback used" }
                cameraFocus?.release()
                val focus = CameraFocusController(ContextCompat.getMainExecutor(this))
                cameraFocus = focus
                val previewBuilder = Preview.Builder().setTargetRotation(Surface.ROTATION_0)
                Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(focus.captureCallback)
                val previewUseCase = previewBuilder.build().apply { surfaceProvider = preview.surfaceProvider }
                @Suppress("DEPRECATION")
                val captureBuilder = ImageCapture.Builder().setTargetResolution(Size(VIDEO_WIDTH, VIDEO_HEIGHT)).setTargetRotation(Surface.ROTATION_0)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                val imageCapture = captureBuilder.build()
                val group = UseCaseGroup.Builder().setViewPort(ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0).setScaleType(ViewPort.FILL_CENTER).build())
                    .addUseCase(previewUseCase).addUseCase(imageCapture).build()
                val camera = provider.bindToLifecycle(this, selector, group)
                focus.attach(camera)
                camera.cameraInfo.cameraState.observe(this) { cameraState ->
                    if (cameraFocus === focus && cameraState.error != null) {
                        model.attachCamera(null, message = "Camera disconnected. Photos retained. Retry camera.")
                        permission.text = "Retry camera"; permission.visibility = View.VISIBLE
                    }
                }
                model.attachCamera(imageCapture, selected, focus = focus); permission.visibility = View.GONE
            } catch (error: Exception) {
                model.attachCamera(null, message = "Camera error: ${error.message}. Saved-photo export remains available.")
                permission.text = "Retry camera"; permission.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("mode", mode.selectedItemPosition); outState.putString("cap", maxSeconds.text.toString())
        outState.putInt("split", split.selectedItemPosition); outState.putInt("interval", interval.selectedItemPosition); outState.putInt("fps", fps.selectedItemPosition)
        outState.putBoolean("sheetOpen", sheet?.isShowing == true)
        outState.putBoolean("sheetTimestamp", sheet?.isTimestamp == true)
        outState.putBoolean("workOpen", workView != null); outState.putString("workPage", workView?.savedPage()); outState.putString("workId", workView?.selectedId()); outState.putString("workSetupReturn", workView?.setupReturnId()); outState.putBoolean("workArchivedView", workView?.isShowingArchived() == true)
        outState.putString("workTypeFilter", workView?.activityTypeFilter()); outState.putString("workSort", workView?.listSort())
        outState.putString("browserTop", topBrowser)
        outState.putBundle("sessionsBrowser", sessionsView?.saveState())
        outState.putBundle("timeboxBrowser", workView?.saveBrowserState())
        outState.putString("syncDownloadVideo", downloadVideo?.toString())
        outState.putBoolean("syncDownloadMobile", downloadMobileConsent)
        outState.putBoolean("syncDownloadPending", downloadPickerPending)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart(); activityForeground = true; model.setForeground(true); work.reconcile(); handler.post(clockTick); bindCamera(); updateBlackout()
    }
    override fun onStop() {
        activityForeground = false; activityFocused = false; touchActive = false; heldKeys.clear()
        updateBlackout()
        invalidateCamera(); model.setForeground(false); handler.removeCallbacks(clockTick); super.onStop()
    }
    override fun onDestroy() {
        activityDestroyed = true; updateMotionSensors(false); blackout.close(); renderBlackout()
        sheet?.dismiss(); workDialog?.dismiss(); sessionsDialog?.dismiss(); super.onDestroy()
    }
    private companion object {
        const val LIGHT_SYSTEM_BARS = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    }
    private fun duration(millis: Long): String { val s = millis.coerceAtLeast(0) / 1000; return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) }
    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; AppTheme.text(this) }
    private fun spinner(values: List<String>) = Spinner(this).apply {
        minimumHeight = dp(48)
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { isSingleLine = false; setTextColor(Color.WHITE); minHeight = dp(48) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply { isSingleLine = false; minHeight = dp(48) }
        }
    }
}
