package com.sinh.timelapse

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.EditText
import android.widget.CheckBox
import android.widget.ScrollView
import android.text.InputType
import android.widget.Toast
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
    private lateinit var preview: PreviewView
    private lateinit var clock: TimestampPreview
    private lateinit var mode: Spinner
    private lateinit var maxSeconds: EditText
    private lateinit var timestampSettings: Button
    private var appearance = TimestampAppearance()
    private val preferences by lazy { getSharedPreferences("capture-defaults", 0) }
    private lateinit var status: TextView
    private lateinit var statistics: TextView
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
    private val clockTick = object : Runnable {
        override fun run() {
            val zone = model.state.value?.session?.zoneId ?: ZoneId.systemDefault().id
            clock.zone = zone
            clock.epochMillis = System.currentTimeMillis()
            clock.appearance = model.state.value?.session?.appearance ?: appearance
            clock.invalidate()
            handler.postDelayed(this, 250)
        }
    }
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) {
            invalidateCamera()
            bindCamera()
        } else {
            invalidateCamera()
            permission.visibility = View.VISIBLE
            Toast.makeText(this, "Camera access is required to capture. You can still export saved photos.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        model.state.observe(this) { state ->
            val session = state.session
            val selectedFps = session?.fps ?: frameRates[fps.selectedItemPosition]
            val seconds = state.selectedFrames.toDouble() / selectedFps
            val active = state.activeMillis?.let(::duration) ?: if (session == null) "00:00:00" else "unavailable (legacy)"
            statistics.text = String.format(Locale.ROOT,
                "Active recording: %s%s\nWall span: %s (wall-clock based)\nEstimated video: %.3fs · %d fps\nSelected: %d / %d saved photos · %.1f MiB\nNominal interval: %.3fs · stage %d · missed slots: %d",
                active, if (state.unknownTail) " (last-saved; interruption tail unknown)" else "",
                duration(state.wallMillis), seconds, selectedFps, state.selectedFrames, state.frames,
                state.savedBytes / 1048576.0, state.nominalIntervalMillis / 1000.0, state.thinningStage, state.missedSlots)
            val message = if (state.exporting) "${state.message} ${state.progress}%"
                else listOf(state.message, state.cameraMessage).filter { it.isNotEmpty() }.joinToString("\n")
            if (status.text.toString() != message) status.text = message
            capture.text = when {
                state.capturing -> "Stop capture"
                session != null -> "Resume capture"
                else -> "Start capture"
            }
            capture.isEnabled = state.capturing || (!state.busy && !state.recoveryBlocked && state.boundLens == state.lens)
            export.isEnabled = !state.busy && !state.capturing && !state.recoveryBlocked && state.frames > 0
            export.text = if (state.exporting) "Exporting ${state.progress}%" else "Export MP4"
            discard.isEnabled = !state.busy && !state.capturing && session != null
            open.isEnabled = state.video != null && !state.exporting
            mode.isEnabled = state.canSelectLens
            maxSeconds.isEnabled = state.canSelectLens && mode.selectedItemPosition == 0
            timestampSettings.isEnabled = state.canSelectLens
            interval.isEnabled = state.canSelectLens && mode.selectedItemPosition == 1
            fps.isEnabled = state.canSelectLens
            lens.isEnabled = state.canSelectLens
            lens.setSelection(SessionLens.entries.indexOf(state.lens))
            recovery.visibility = if (state.recoveryBlocked) View.VISIBLE else View.GONE
            recovery.isEnabled = !state.busy
            if (session != null) {
                mode.setSelection(if (session.configuration is SamplingConfiguration.Adaptive) 0 else 1)
                (session.configuration as? SamplingConfiguration.Adaptive)?.let { maxSeconds.setText(it.maxSeconds.toString()) }
                clock.appearance = session.appearance
                interval.setSelection(intervals.indexOf((session.intervalMillis / 1000).toInt()).coerceAtLeast(0))
                fps.setSelection(frameRates.indexOf(session.fps).coerceAtLeast(0))
            }
            if (state.capturing || state.savingPhoto || state.exporting) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            bindCamera()
        }
        if (!hasCameraPermission() && savedInstanceState == null) requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(16, 25, 22))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom)
            insets
        }
        root.addView(label("Timestamp Timelapse", 23f))
        root.addView(label("Clock time burned into every photo · 720 × 1280 MP4", 12f))
        val previewContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val videoFrame = FrameLayout(this)
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        videoFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        appearance = runCatching {
            TimestampAppearance(StampFamily.valueOf(preferences.getString("family", "MONOSPACE")!!),
                preferences.getBoolean("bold", true), preferences.getInt("size", 44),
                StampPosition.valueOf(preferences.getString("position", "BOTTOM_LEFT")!!), preferences.getBoolean("date", false))
        }.getOrDefault(TimestampAppearance())
        clock = TimestampPreview(this).apply { appearance = this@MainActivity.appearance }
        videoFrame.addView(clock, FrameLayout.LayoutParams(-1, -1))
        previewContainer.addView(videoFrame, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        previewContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = min(right - left, (bottom - top) * 9 / 16)
            val height = width * 16 / 9
            if (videoFrame.width != width || videoFrame.height != height) {
                videoFrame.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            }
        }
        root.addView(previewContainer, LinearLayout.LayoutParams(-1, dp(400)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        permission = Button(this).apply {
            text = "Enable camera"
            visibility = if (hasCameraPermission()) View.GONE else View.VISIBLE
            setOnClickListener {
                if (hasCameraPermission()) {
                    invalidateCamera()
                    bindCamera()
                } else requestCamera.launch(Manifest.permission.CAMERA)
            }
        }
        root.addView(permission)
        recovery = Button(this).apply {
            text = "Retry saved session recovery"
            visibility = View.GONE
            setOnClickListener { model.retryRecovery() }
        }
        root.addView(recovery)
        lens = spinner(listOf("Rear camera", "Front camera")).apply {
            contentDescription = "Session camera (fixed until session photos are deleted)"
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    model.selectLens(SessionLens.entries[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(lens)
        root.addView(label("New-session settings (locked after Start)", 16f))
        mode = spinner(listOf("Adaptive — capped video", "Fixed interval — uncapped video")).apply {
            contentDescription = "Capture mode"
            setSelection(if (preferences.getBoolean("adaptive", true)) 0 else 1)
        }
        root.addView(mode)
        root.addView(label("Maximum Adaptive video seconds (whole number 5–600)", 14f))
        maxSeconds = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(preferences.getInt("maxSeconds", 30).toString())
            setTextColor(Color.WHITE)
            contentDescription = "Maximum video duration in seconds, 5 to 600"
        }
        root.addView(maxSeconds)
        root.addView(label("Cap limits video only, not recording or storage. All completed photos are kept. Thinning can shorten the video estimate. Camera speed is not guaranteed.", 13f))
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        interval = spinner(intervals.map { "Every ${it}s" }).apply { setSelection(intervals.indexOf(preferences.getInt("interval", 5)).coerceAtLeast(0)) }
        fps = spinner(frameRates.map { "$it fps" }).apply { setSelection(frameRates.indexOf(preferences.getInt("fps", 30)).coerceAtLeast(0)) }
        interval.contentDescription = "Photo capture interval"
        fps.contentDescription = "Export frames per second"
        settings.addView(interval, LinearLayout.LayoutParams(-1, -2))
        settings.addView(fps, LinearLayout.LayoutParams(-1, -2))
        root.addView(settings)
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val enabled = model.state.value?.canSelectLens == true
                maxSeconds.isEnabled = enabled && position == 0
                interval.isEnabled = enabled && position == 1
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        timestampSettings = Button(this).apply {
            text = "Timestamp appearance…"
            setOnClickListener { showTimestampSettings() }
        }
        root.addView(timestampSettings)
        statistics = label("0 photos", 14f)
        status = label("Ready", 13f).apply { minLines = 2; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(statistics)
        root.addView(status)
        capture = Button(this).apply {
            text = "Start capture"
            setOnClickListener {
                if (model.state.value?.capturing == true) model.stop()
                else if (model.state.value?.session != null) model.start(1_000, 30)
                else {
                    try {
                        val rate = frameRates[fps.selectedItemPosition]
                        val config = if (mode.selectedItemPosition == 0) SamplingConfiguration.parseAdaptive(maxSeconds.text.toString(), rate.toString())
                            else SamplingConfiguration.Fixed(intervals[interval.selectedItemPosition], rate)
                        preferences.edit().putBoolean("adaptive", config is SamplingConfiguration.Adaptive)
                            .putInt("maxSeconds", (config as? SamplingConfiguration.Adaptive)?.maxSeconds ?: preferences.getInt("maxSeconds", 30))
                            .putInt("fps", rate).putInt("interval", intervals[interval.selectedItemPosition]).apply()
                        rememberAppearance()
                        model.start(config, appearance)
                    } catch (_: IllegalArgumentException) {
                        maxSeconds.error = "Enter a whole number from 5 to 600"
                        Toast.makeText(this@MainActivity, "Invalid settings; no session created.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        root.addView(capture)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        export = Button(this).apply { text = "Export MP4"; setOnClickListener { model.export() } }
        open = Button(this).apply { text = "Open last video"; setOnClickListener { openVideo() } }
        actions.addView(export, LinearLayout.LayoutParams(-1, -2))
        actions.addView(open, LinearLayout.LayoutParams(-1, -2))
        root.addView(actions)
        discard = Button(this).apply {
            text = "Delete photos / New session"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Delete session photos?")
                    .setMessage("This removes all photos in the current session so you can start a new one. Exported videos are not deleted.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete photos") { _, _ -> model.discard() }
                    .show()
            }
        }
        root.addView(discard)
        root.addView(label("Foreground only. Leaving the app pauses capture. No audio.", 11f))
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        root.requestApplyInsets()
    }

    private fun duration(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    private fun rememberAppearance() {
        preferences.edit().putString("family", appearance.family.name).putBoolean("bold", appearance.bold)
            .putInt("size", appearance.size).putString("position", appearance.position.name)
            .putBoolean("date", appearance.date).apply()
    }

    private fun showTimestampSettings() {
        if (model.state.value?.canSelectLens != true) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(16, 25, 22))
        }
        val family = spinner(listOf("Monospace", "Sans-serif", "Serif")).apply {
            contentDescription = "Timestamp font family"; setSelection(appearance.family.ordinal)
        }
        val weight = spinner(listOf("Regular", "Bold")).apply {
            contentDescription = "Timestamp font style"; setSelection(if (appearance.bold) 1 else 0)
        }
        val size = spinner(listOf("32 output pixels", "44 output pixels", "60 output pixels")).apply {
            contentDescription = "Timestamp font size"; setSelection(listOf(32, 44, 60).indexOf(appearance.size))
        }
        val position = spinner(listOf("Top left", "Top center", "Top right", "Bottom left", "Bottom center", "Bottom right")).apply {
            contentDescription = "Timestamp position"; setSelection(appearance.position.ordinal)
        }
        val date = CheckBox(this).apply {
            text = "Date yyyy-MM-dd above HH:mm:ss"; setTextColor(Color.WHITE); isChecked = appearance.date
        }
        fun changed() {
            appearance = TimestampAppearance(StampFamily.entries[family.selectedItemPosition], weight.selectedItemPosition == 1,
                listOf(32, 44, 60)[size.selectedItemPosition], StampPosition.entries[position.selectedItemPosition], date.isChecked)
            clock.appearance = appearance; clock.invalidate(); rememberAppearance()
        }
        panel.addView(label("Preview uses exact output-space geometry. Appearance is frozen when capture starts; saved photos cannot be restyled.", 14f))
        listOf("Font family" to family, "Font style" to weight, "Output size" to size, "Position" to position).forEach { (title, view) ->
            panel.addView(label(title, 14f)); panel.addView(view)
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = changed()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        panel.addView(date)
        date.setOnCheckedChangeListener { _, _ -> changed() }
        panel.addView(Button(this).apply {
            text = "Reset to defaults"
            setOnClickListener {
                family.setSelection(0); weight.setSelection(1); size.setSelection(1); position.setSelection(3); date.isChecked = false
                changed()
            }
        })
        AlertDialog.Builder(this).setTitle("Timestamp appearance")
            .setView(ScrollView(this).apply { addView(panel) }).setPositiveButton("Done", null).show()
    }

    private fun invalidateCamera() {
        bindingGeneration++
        requestedLens = null
        model.attachCamera(null)
    }

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
                provider.unbindAll() // Do not leave the previous lens preview showing after a failed switch.
                val selector = if (selected == SessionLens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
                check(provider.hasCamera(selector)) { "Selected ${selected.storedValue} camera is unavailable; no fallback used" }
                val previewUseCase = Preview.Builder().setTargetRotation(Surface.ROTATION_0).build().apply {
                    surfaceProvider = preview.surfaceProvider
                }
                @Suppress("DEPRECATION")
                val imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(VIDEO_WIDTH, VIDEO_HEIGHT))
                    .setTargetRotation(Surface.ROTATION_0)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val group = UseCaseGroup.Builder()
                    .setViewPort(ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0)
                        .setScaleType(ViewPort.FILL_CENTER).build())
                    .addUseCase(previewUseCase).addUseCase(imageCapture).build()
                provider.bindToLifecycle(this, selector, group)
                // CameraX mirrors front PreviewView by default; do not apply another UI reflection.
                model.attachCamera(imageCapture, selected)
                permission.visibility = View.GONE
            } catch (error: Exception) {
                model.attachCamera(null, message = "Camera error: ${error.message}. Saved-photo export remains available.")
                permission.text = "Retry camera"
                permission.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openVideo() {
        val uri = model.state.value?.video ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No video player installed. Find the MP4 in Movies/TimestampTimelapse.", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Video is no longer available. Export it again.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        activityForeground = true
        model.setForeground(true)
        handler.post(clockTick)
        bindCamera()
    }

    override fun onStop() {
        activityForeground = false
        invalidateCamera()
        model.setForeground(false)
        handler.removeCallbacks(clockTick)
        super.onStop()
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
    }
    private fun spinner(values: List<String>) = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    isSingleLine = false
                    setTextColor(Color.WHITE)
                    minHeight = dp(48)
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply { isSingleLine = false; minHeight = dp(48) }
        }
    }
}
