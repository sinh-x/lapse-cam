package com.sinh.timelapse

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Native bottom-anchored grouped sheet. The Activity's preview/camera owner stays mounted. */
internal class CameraSettingsSheet(context: Context, private val capture: View, private val timestamp: View,
    timestampFirst: Boolean = false) : Dialog(context) {
    private val lock = TextView(context).apply { setTextColor(Color.WHITE); textSize = 14f }
    init {
        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)); setBackgroundColor(Color.rgb(16, 25, 22))
        }
        val handle = object : Button(context) {
            override fun performClick(): Boolean = super.performClick()
        }.apply {
            text = "Camera settings — dismiss"; contentDescription = "Dismiss camera settings, or swipe down"; textSize = 16f; minHeight = dp(48)
            setOnClickListener { dismiss() }
            var start = 0f
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { start = event.rawY; true }
                    MotionEvent.ACTION_UP -> { if (event.rawY - start > dp(48)) dismiss() else performClick(); true }
                    else -> true
                }
            }
        }
        panel.addView(handle); panel.addView(lock)
        val groups = LinearLayout(context)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        (capture.parent as? ViewGroup)?.removeView(capture)
        (timestamp.parent as? ViewGroup)?.removeView(timestamp)
        content.addView(capture); content.addView(timestamp)
        fun select(stamp: Boolean) { capture.visibility = if (stamp) View.GONE else View.VISIBLE; timestamp.visibility = if (stamp) View.VISIBLE else View.GONE }
        groups.addView(Button(context).apply { text = "Capture"; setOnClickListener { select(false) } }, LinearLayout.LayoutParams(0, -2, 1f))
        groups.addView(Button(context).apply { text = "Timestamp"; setOnClickListener { select(true) } }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(groups)
        panel.addView(ScrollView(context).apply { isFillViewport = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(Button(context).apply { text = "Done"; setOnClickListener { dismiss() } })
        setContentView(panel)
        setCanceledOnTouchOutside(true)
        select(timestampFirst)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    override fun show() {
        super.show()
        window?.setLayout(-1, (context.resources.displayMetrics.heightPixels * 0.85).toInt())
    }
    fun setLocked(locked: Boolean) {
        lock.text = if (locked) "Session settings locked, including while paused/recovered. Saved session values are read-only." else "New-session settings. Work minutes are separate from maximum MP4 seconds."
    }
}
