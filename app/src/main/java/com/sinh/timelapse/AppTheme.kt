package com.sinh.timelapse

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView

/** Native Views styling shared by Camera and every browser. Never attaches action listeners. */
internal object AppTheme {
    fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
    fun shape(view: View, color: Int = AppDesignPolicy.SURFACE, border: Int = AppDesignPolicy.BORDER) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(view, 8).toFloat(); setStroke(dp(view, 1).coerceAtLeast(1), border)
    }
    fun text(view: TextView, heading: Boolean = false, muted: Boolean = false) {
        view.setTextColor(if (muted) AppDesignPolicy.MUTED else AppDesignPolicy.TEXT)
        if (heading) { view.textSize = 20f; view.setTypeface(null, Typeface.BOLD) }
    }
    fun card(view: View, attention: Boolean = false) {
        view.background = shape(view, if (attention) AppDesignPolicy.DANGER_SURFACE else AppDesignPolicy.SURFACE)
        view.setPadding(dp(view, 12), dp(view, 10), dp(view, 12), dp(view, 10))
    }
    fun button(view: Button, primary: Boolean = false, destructive: Boolean = false) {
        val foreground = when { destructive -> AppDesignPolicy.DANGER; primary -> AppDesignPolicy.AMBER; else -> AppDesignPolicy.TEXT }
        val fill = when { destructive -> AppDesignPolicy.DANGER_SURFACE; primary -> AppDesignPolicy.AMBER_SURFACE; else -> AppDesignPolicy.SURFACE }
        view.isAllCaps = false; view.minHeight = dp(view, AppDesignPolicy.TARGET_DP)
        view.minimumWidth = dp(view, AppDesignPolicy.TARGET_DP)
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(AppDesignPolicy.MUTED, foreground)))
        view.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(view, fill, if (primary) foreground else AppDesignPolicy.BORDER), null)
        view.setPadding(dp(view, 12), dp(view, 8), dp(view, 12), dp(view, 8))
    }
}
