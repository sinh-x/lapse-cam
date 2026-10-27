package com.sinh.timelapse

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.*
import android.text.InputType
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import java.time.ZoneId
import java.util.Locale
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val model: CaptureViewModel by viewModels()
    private val work by lazy { WorkSessionCoordinator.get(application) }
    private lateinit var preview: PreviewView
    private lateinit var clock: TimestampPreview
    private lateinit var mode: Spinner
    private lateinit var maxSeconds: EditText
    private lateinit var split: Spinner
    private lateinit var finish: Button
    private lateinit var backlog: TextView
    private var sessionsView: SessionsView? = null
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
    private lateinit var export: Button
    private lateinit var discard: Button
    private lateinit var open: Button
    private lateinit var permission: Button
    private val intervals = listOf(1, 2, 5, 10, 30, 60)
    private val frameRates = listOf(15, 24, 30)
    private var requestedLens: SessionLens? = null
    private var bindingGeneration = 0L
    private var activityForeground = false
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
            }
            handler.postDelayed(this, 250)
        }
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        work.reconcile()
        if (allowed) Toast.makeText(this, "Notification permission allowed. See End reminder settings for background timing status.", Toast.LENGTH_LONG).show()
        else AlertDialog.Builder(this).setTitle("Notifications are not allowed")
            .setMessage("The timebox still works, but its end alert cannot appear. You can enable this app's notifications in Android settings. Background timing is a separate setting.")
            .setNegativeButton("Not now", null).setPositiveButton("Open notification settings") { _, _ -> openTimeboxNotificationSettings() }.show()
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
        model.state.observe(this) { state -> renderCapture(state); bindCamera() }
        model.sessions.observe(this) { sessions ->
            sessionsView?.render(sessions)
            backlog.text = String.format(Locale.ROOT, "Sessions: %d · retained %.1f MiB · needs attention: %d", sessions.size,
                sessions.sumOf { it.bytes } / 1048576.0, sessions.count { it.canRetry })
            renderWork()
        }
        work.state.observe(this) { renderWork(); sessionsView?.render(model.sessions.value.orEmpty()) }
        pendingWorkId = intent.getStringExtra("workId")
        if (savedInstanceState?.getBoolean("workOpen") == true) {
            showWork(savedInstanceState.getString("workId"))
            workView?.restore(savedInstanceState.getString("workPage") ?: "list", savedInstanceState.getString("workId"), savedInstanceState.getString("workSetupReturn"), savedInstanceState.getBoolean("workArchivedView"), savedInstanceState.getString("workTypeFilter"), savedInstanceState.getString("workSort"))
        }
        if (savedInstanceState?.getBoolean("sheetOpen") == true) showSettings()
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
        val message = listOf(state.message, state.cameraMessage).filter { it.isNotEmpty() }.joinToString("\n")
        val actionable = state.recoveryBlocked || state.cameraMessage.isNotEmpty() || listOf("error", "failed", "storage", "attention", "insufficient", "unavailable").any { message.contains(it, true) }
        status.visibility = if (actionable) View.VISIBLE else View.GONE
        if (status.text.toString() != message) status.text = message
        val ui = CameraUiPolicy.project(CameraUiPolicy.Input(state.capturing, session != null, state.busy, state.switching,
            state.recoveryBlocked, state.boundLens == state.lens, session?.split != null && session.split != SessionSplit.OFF, state.loading, state.working))
        capture.text = ui.primary; capture.contentDescription = ui.primary; capture.isEnabled = ui.enabled
        val liveLabel = when {
            ui.live -> "● LIVE"
            state.recoveryBlocked || state.cameraMessage.isNotEmpty() -> "Error"
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
        // Preserve TTL-006's mutation-entry lock; gear still permits read-only inspection.
        timestampSettings.isEnabled = state.canSelectLens
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
        if (state.capturing || state.savingPhoto || state.exporting) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        listOf(lens, label("Capture mode", 14f), mode, label("Maximum Adaptive MP4 seconds (5–600), not work minutes", 14f), maxSeconds,
            label("Adaptive auto-split (active recording time)", 14f), split, interval, fps).forEach { capturePanel.addView(it) }
        capturePanel.addView(label("With 2h/3h, Pause retains the session; Finish exports. Only verified output permits source cleanup. Off/Fixed/legacy remain manual. Foreground only; no cadence/storage guarantee.", 14f))
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val enabled = model.state.value?.canSelectLens == true
                maxSeconds.isEnabled = enabled && position == 0; split.isEnabled = enabled && position == 0; interval.isEnabled = enabled && position == 1
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        timestampPanel = buildTimestampPanel()
        timestampSettings = Button(this).apply { text = "Timestamp appearance…"; setOnClickListener { showSettings(true) } }
        statistics = label("0 photos", 14f)
        backlog = label("Sessions", 14f)
        status = label("", 13f).apply { visibility = View.GONE; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        summary = label("Capture 00:00:00 · 0 photos", 12f)
        export = Button(this).apply { text = "Export MP4"; setOnClickListener { model.export() } }
        open = Button(this).apply { text = "Open last video"; setOnClickListener { model.state.value?.video?.let(::openVideoUri) } }
        discard = Button(this).apply { text = "Delete photos / New session"; setOnClickListener {
            val id = model.state.value?.session?.directory?.name ?: return@setOnClickListener
            confirmDelete(id, "Remove only this session's inventoried photos and cancel its queued work. Exported videos are unchanged. Unknown files stop deletion.")
        } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 25, 22)); setPadding(dp(8), 0, dp(8), 0)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(dp(8) + bars.left, bars.top, dp(8) + bars.right, bars.bottom); insets
        }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cardTop = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        workTitle = label("No timebox running", 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        cardTop.addView(workTitle, LinearLayout.LayoutParams(0, -2, 1f))
        cardTop.addView(Button(this).apply { text = "Work"; setOnClickListener { showWork() } }, LinearLayout.LayoutParams(-2, -2))
        card.addView(cardTop)
        val timing = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        workRemaining = label("", 14f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        timing.addView(workRemaining, LinearLayout.LayoutParams(0, -2, 1f))
        workEnd = Button(this).apply { text = "End"; contentDescription = "End timebox"; setOnClickListener { work.state.value?.snapshot?.running?.id?.let(work::end) } }
        timing.addView(workEnd); card.addView(timing); root.addView(card)
        val hud = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        hud.addView(Button(this).apply { text = "⚙"; contentDescription = "Camera settings"; minWidth = dp(48); setOnClickListener { showSettings() } })
        live = label("Ready", 14f).apply { gravity = Gravity.END; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        hud.addView(live, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(hud)
        // HUD is a sibling outside the exact output frame, never a child of the JPEG/MP4 renderer.
        val previewContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); contentDescription = "Camera preview" }
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
        root.addView(permission); root.addView(recovery); root.addView(status); root.addView(summary)
        capture = Button(this).apply {
            text = "Start capture"; minHeight = dp(72); minWidth = dp(72); textSize = 12f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(255, 205, 205)) }
            setTextColor(Color.BLACK); setOnClickListener { captureAction() }
        }
        finish = Button(this).apply { text = "Finish session\nand export"; visibility = View.GONE; setOnClickListener { model.finishSession() } }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(Button(this).apply { text = "Sessions"; setOnClickListener { showSessions() } }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(capture, LinearLayout.LayoutParams(dp(96), dp(96)))
        controls.addView(finish, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        root.addView(Button(this).apply { text = "Recording details"; setOnClickListener { showRecordingDetails() } })
        setContentView(root); root.requestApplyInsets()
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
            setLocked(model.state.value?.canSelectLens != true); setOnDismissListener { sheet = null }; show()
        }
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
        AlertDialog.Builder(this).setTitle("Recording details").setView(ScrollView(this).apply { addView(panel) }).setPositiveButton("Close", null).show()
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
    private fun showWork(id: String? = null) {
        if (workDialog != null) return
        val view = WorkSessionsView(this, work, { model.sessions.value.orEmpty() }, ::openSessionVideo,
            ::configureTimeboxNotifications, ::configureBackgroundTiming, id)
        workView = view
        workDialog = WorkDialog(this, view) { workView = null; workDialog = null }.apply { show() }
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
        if (state.loaded && pendingWorkId != null) { val id = pendingWorkId; pendingWorkId = null; showWork(id) }
    }
    private fun showSessions() {
        if (sessionsView != null) return
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 25, 22)) }
        listOf(backlog, export, open, discard).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it); panel.addView(it) }
        val view = SessionsView(this, open = { openSessionVideo(it.id) }, retry = { model.retrySession(it.id) }, delete = {
            confirmDelete(it.id, "Delete ${it.retained} retained source photos and cancel queued work? Published video and other sessions are unchanged. Unknown files stop deletion.")
        }, linkedWork = { id -> showRecordingLinks(id) }, linkSummary = { id ->
            val links = runCatching { work.state.value?.snapshot?.links(work.now())?.filter { it.recordingId == id }.orEmpty() }.getOrDefault(emptyList())
            if (work.state.value?.error != null) "Work context unavailable; retained" else "Linked timeboxes: ${links.map { it.workId }.distinct().size}"
        })
        panel.addView(view, LinearLayout.LayoutParams(-1, 0, 1f)); sessionsView = view; view.render(model.sessions.value.orEmpty())
        AlertDialog.Builder(this).setTitle("Sessions").setView(panel).setPositiveButton("Close", null).create().apply {
            setOnDismissListener { sessionsView = null }; setOnShowListener { window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.90).toInt()) }; show()
        }
    }
    private fun showRecordingLinks(id: String) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 25, 22)); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val links = runCatching { work.state.value?.snapshot?.links(work.now())?.filter { it.recordingId == id }.orEmpty() }.getOrDefault(emptyList())
        panel.addView(label("Capture-time context, not MP4 seek positions. ${if (links.isEmpty()) "No known overlaps." else ""}", 14f))
        links.forEach { link ->
            panel.addView(label("Timebox ${link.workId}\n${WorkSessionsView.linkText(link)}", 14f))
            panel.addView(Button(this).apply { text = "Open timebox ${link.workId}"; setOnClickListener { showWork(link.workId) } })
        }
        AlertDialog.Builder(this).setTitle("Recording $id").setView(ScrollView(this).apply { addView(panel) }).setPositiveButton("Close", null).show()
    }
    private fun confirmDelete(id: String, explanation: String) {
        AlertDialog.Builder(this).setTitle("Delete source photos?").setMessage("Session $id\n$explanation")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete source photos") { _, _ -> model.deleteSessionSources(id) }.show()
    }
    private fun openSessionVideo(id: String) { model.openSessionVideo(id) { uri -> if (!isDestroyed) {
        if (uri == null) {
            workView?.markUnavailable(id)
            Toast.makeText(this, "Output missing or unreadable. Work context and retained sources are unchanged.", Toast.LENGTH_LONG).show()
        } else openVideoUri(uri)
    } } }
    private fun openVideoUri(uri: android.net.Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "No video player installed. Find the MP4 in Movies/TimestampTimelapse.", Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this, "Video unavailable. See Sessions; removed photos cannot be restored.", Toast.LENGTH_LONG).show() }
    }
    private fun invalidateCamera() { bindingGeneration++; requestedLens = null; model.attachCamera(null) }
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
                val previewUseCase = Preview.Builder().setTargetRotation(Surface.ROTATION_0).build().apply { surfaceProvider = preview.surfaceProvider }
                @Suppress("DEPRECATION")
                val imageCapture = ImageCapture.Builder().setTargetResolution(Size(VIDEO_WIDTH, VIDEO_HEIGHT)).setTargetRotation(Surface.ROTATION_0)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val group = UseCaseGroup.Builder().setViewPort(ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0).setScaleType(ViewPort.FILL_CENTER).build())
                    .addUseCase(previewUseCase).addUseCase(imageCapture).build()
                provider.bindToLifecycle(this, selector, group)
                model.attachCamera(imageCapture, selected); permission.visibility = View.GONE
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
        outState.putBoolean("workOpen", workView != null); outState.putString("workPage", workView?.savedPage()); outState.putString("workId", workView?.selectedId()); outState.putString("workSetupReturn", workView?.setupReturnId()); outState.putBoolean("workArchivedView", workView?.isShowingArchived() == true)
        outState.putString("workTypeFilter", workView?.activityTypeFilter()); outState.putString("workSort", workView?.listSort())
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart(); activityForeground = true; model.setForeground(true); work.reconcile(); handler.post(clockTick); bindCamera()
    }
    override fun onStop() {
        activityForeground = false; invalidateCamera(); model.setForeground(false); handler.removeCallbacks(clockTick); super.onStop()
    }
    override fun onDestroy() { sheet?.dismiss(); workDialog?.dismiss(); super.onDestroy() }
    private fun duration(millis: Long): String { val s = millis.coerceAtLeast(0) / 1000; return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) }
    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE) }
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
