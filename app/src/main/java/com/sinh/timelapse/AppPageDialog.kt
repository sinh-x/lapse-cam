package com.sinh.timelapse

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** In-Activity page, keeping the camera mounted. Back dismisses only this destination. */
internal class AppPageDialog(activity: Activity, title: String, content: View, closed: () -> Unit = {}) : Dialog(activity) {
    private val captureReturn = CaptureReturnView(activity)
    var onCamera: (() -> Unit)? = null
    fun renderCapture(state: CaptureViewModel.State?) = captureReturn.render(state)
    init {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(AppDesignPolicy.BACKGROUND) }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(activity).apply { text = "‹"; contentDescription = "Back"; AppTheme.button(this); setOnClickListener { dismiss() } })
        header.addView(TextView(activity).apply { text = title; AppTheme.text(this, heading = true) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        captureReturn.setOnClickListener { onCamera?.invoke() ?: dismiss() }; root.addView(captureReturn)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime()); val gap = AppTheme.dp(view, 12)
            view.setPadding(gap + bars.left, bars.top, gap + bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        setContentView(root); setCanceledOnTouchOutside(false)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(AppDesignPolicy.BACKGROUND))
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        setOnDismissListener { closed() }
    }
    override fun show() { super.show(); window?.setLayout(-1, -1); window?.decorView?.requestApplyInsets() }
}
