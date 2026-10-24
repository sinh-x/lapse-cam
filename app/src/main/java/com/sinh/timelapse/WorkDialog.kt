package com.sinh.timelapse

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** A dynamic editor must not use AlertDialog's initial read-only/ALT_FOCUSABLE_IM inference. */
internal class WorkDialog(activity: Activity, private val workView: WorkSessionsView, closed: () -> Unit) : Dialog(activity) {
    private val heading = TextView(activity).apply { textSize = 20f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val backCallback = OnBackInvokedCallback {
        if (window?.decorView?.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) hideKeyboard() else navigateBack()
    }
    init {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 25, 22))
            isFocusableInTouchMode = true
        }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = ImageButton(activity, null, android.R.attr.borderlessButtonStyle).apply {
            setImageResource(R.drawable.ic_arrow_back); contentDescription = "Back"
            setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { navigateBack() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        root.addView(workView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(root)
        // No implicit draft-discard/close from an accidental outside tap.
        setCanceledOnTouchOutside(false)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.rgb(16, 25, 22)))
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        workView.onPageChanged = { updateHeading() }
        updateHeading()
        setOnDismissListener { hideKeyboard(); workView.dispose(); closed() }
    }
    private fun updateHeading() {
        heading.text = when (workView.savedPage()) { "setup" -> "New timebox"; "detail" -> "Timebox details"; else -> if (workView.isShowingArchived()) "Archived timeboxes" else "Work" }
    }
    private fun navigateBack() {
        hideKeyboard()
        if (!workView.back()) dismiss()
    }
    private fun hideKeyboard() {
        val decor = window?.decorView ?: return
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(decor.windowToken, 0)
    }
    override fun onStart() {
        super.onStart()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
    }
    override fun onStop() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        super.onStop()
    }
    override fun show() {
        super.show()
        window?.setLayout(-1, (context.resources.displayMetrics.heightPixels * 0.94).toInt())
        window?.decorView?.requestApplyInsets()
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
