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
    private lateinit var clock: TextView
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
            clock.text = CaptureTiming.timestamp(System.currentTimeMillis(), zone)
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
            val seconds = state.frames.toDouble() / selectedFps
            statistics.text = String.format(Locale.ROOT, "%d photos  ·  %.1fs video  ·  %d fps", state.frames, seconds, selectedFps)
            status.text = if (state.exporting) "${state.message} ${state.progress}%"
                else listOf(state.message, state.cameraMessage).filter { it.isNotEmpty() }.joinToString("\n")
            capture.text = when {
                state.capturing -> "Stop capture"
                session != null -> "Resume capture"
                else -> "Start capture"
            }
            capture.isEnabled = state.capturing || (!state.busy && !state.recoveryBlocked && state.boundLens == state.lens)
            export.isEnabled = !state.busy && !state.capturing && state.frames > 0
            export.text = if (state.exporting) "Exporting ${state.progress}%" else "Export MP4"
            discard.isEnabled = !state.busy && !state.capturing && session != null
            open.isEnabled = state.video != null && !state.exporting
            interval.isEnabled = state.canSelectLens
            fps.isEnabled = state.canSelectLens
            lens.isEnabled = state.canSelectLens
            lens.setSelection(SessionLens.entries.indexOf(state.lens))
            recovery.visibility = if (state.recoveryBlocked) View.VISIBLE else View.GONE
            recovery.isEnabled = !state.busy
            if (session != null) {
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
        clock = label("00:00:00", 18f).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(0xB4000000.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        videoFrame.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        previewContainer.addView(videoFrame, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        previewContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = min(right - left, (bottom - top) * 9 / 16)
            val height = width * 16 / 9
            if (videoFrame.width != width || videoFrame.height != height) {
                videoFrame.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            }
        }
        root.addView(previewContainer, LinearLayout.LayoutParams(-1, 0, 1f).apply {
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
        val settings = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        interval = spinner(intervals.map { "Every ${it}s" }).apply { setSelection(2) }
        fps = spinner(frameRates.map { "$it fps" }).apply { setSelection(2) }
        interval.contentDescription = "Photo capture interval"
        fps.contentDescription = "Export frames per second"
        settings.addView(interval, LinearLayout.LayoutParams(0, dp(48), 1f))
        settings.addView(fps, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(settings)
        statistics = label("0 photos", 14f)
        status = label("Ready", 13f).apply { minLines = 2; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(statistics)
        root.addView(status)
        capture = Button(this).apply {
            text = "Start capture"
            setOnClickListener {
                if (model.state.value?.capturing == true) model.stop()
                else model.start(intervals[interval.selectedItemPosition] * 1_000L, frameRates[fps.selectedItemPosition])
            }
        }
        root.addView(capture)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        export = Button(this).apply { text = "Export MP4"; setOnClickListener { model.export() } }
        open = Button(this).apply { text = "Open last video"; setOnClickListener { openVideo() } }
        actions.addView(export, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(open, LinearLayout.LayoutParams(0, -2, 1f))
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
        setContentView(root)
        root.requestApplyInsets()
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
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
    }
}
