package com.sinh.timelapse

import android.content.Context
import android.widget.Button

/** A read-only indicator plus explicit navigation; never a recording control. */
internal class CaptureReturnView(context: Context) : Button(context) {
    init { text = "Return to Camera"; AppTheme.button(this) }
    fun render(state: CaptureViewModel.State?) {
        val active = CaptureDisplayPolicy.duration(state?.activeMillis ?: if (state?.session == null) 0L else null)
        val label = if (state?.capturing == true) "● LIVE  $active · Return to Camera"
            else if (state?.session != null) "Paused  $active · Return to Camera" else "Return to Camera"
        if (text.toString() != label) text = label
        val color = if (state?.capturing == true) AppDesignPolicy.DANGER_SURFACE else AppDesignPolicy.SURFACE
        if (tag != color) { background = AppTheme.shape(this, color); tag = color }
    }
}
