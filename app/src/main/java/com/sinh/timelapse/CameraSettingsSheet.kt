package com.sinh.timelapse

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Bottom sheet, retaining the mounted camera and the same setting controls on every visit. */
internal class CameraSettingsSheet(context: Context, private val capture: View, private val timestamp: View,
    timestampFirst: Boolean = false) : Dialog(context) {
    var isTimestamp = timestampFirst; private set
    private val lock = TextView(context).apply { AppTheme.text(this, muted = true); textSize = 14f }
    init {
        fun dp(n: Int) = AppTheme.dp(capture, n)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)); background = AppTheme.shape(this)
        }
        val handle = Button(context).apply {
            text = "Camera settings  ×"; contentDescription = "Dismiss camera settings, or swipe down"; textSize = 18f
            AppTheme.button(this); setOnClickListener { dismiss() }
            var start = 0f
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { start = event.rawY; true }
                    MotionEvent.ACTION_UP -> { if (event.rawY - start > dp(48)) dismiss() else view.performClick(); true }
                    else -> true
                }
            }
        }
        panel.addView(handle)
        val groups = LinearLayout(context)
        val captureTab = Button(context).apply { text = "Capture" }
        val timestampTab = Button(context).apply { text = "Timestamp" }
        fun select(stamp: Boolean) {
            isTimestamp = stamp
            capture.visibility = if (stamp) View.GONE else View.VISIBLE
            timestamp.visibility = if (stamp) View.VISIBLE else View.GONE
            captureTab.isSelected = !stamp; timestampTab.isSelected = stamp
            AppTheme.button(captureTab, primary = !stamp); AppTheme.button(timestampTab, primary = stamp)
        }
        captureTab.setOnClickListener { select(false) }; timestampTab.setOnClickListener { select(true) }
        groups.addView(captureTab, LinearLayout.LayoutParams(0, -2, 1f))
        groups.addView(timestampTab, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(groups); panel.addView(lock)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        (capture.parent as? ViewGroup)?.removeView(capture); (timestamp.parent as? ViewGroup)?.removeView(timestamp)
        content.addView(capture); content.addView(timestamp)
        panel.addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(Button(context).apply { text = "Done"; AppTheme.button(this, primary = true); setOnClickListener { dismiss() } })
        panel.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(dp(16) + bars.left, dp(8), dp(16) + bars.right, dp(16) + maxOf(bars.bottom, ime.bottom)); insets
        }
        setContentView(panel); setCanceledOnTouchOutside(true); select(timestampFirst)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    override fun show() {
        super.show(); window?.setLayout(-1, (context.resources.displayMetrics.heightPixels * 0.85).toInt())
        window?.decorView?.requestApplyInsets()
    }
    fun setLocked(locked: Boolean) {
        lock.text = if (locked) "SESSION LOCKED · Values are read-only, including while paused/recovered. Blackout remains editable."
            else "NEW SESSION · Values freeze at Start. Timebox minutes are separate from MP4 seconds."
    }
}
