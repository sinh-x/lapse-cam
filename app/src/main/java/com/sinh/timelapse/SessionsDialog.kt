package com.sinh.timelapse

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Full-window in-Activity destination: no Activity lifecycle/camera ownership change. */
internal class SessionsDialog(activity: Activity, private val browser: SessionsView, closed: () -> Unit) : Dialog(activity) {
    private val back = OnBackInvokedCallback { navigateBack() }
    init {
        setContentView(browser); setCanceledOnTouchOutside(false)
        browser.onClose = { dismiss() }
        browser.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.rgb(16, 25, 22)))
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        setOnDismissListener { browser.onClose = null; closed() }
    }
    private fun navigateBack() { if (!browser.back()) dismiss() }
    override fun cancel() = navigateBack()
    override fun onStart() {
        super.onStart(); window?.setLayout(-1, -1)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
        window?.decorView?.requestApplyInsets()
    }
    override fun onStop() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back); super.onStop()
    }
}
