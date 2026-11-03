package com.sinh.timelapse

/** Shared presentation tokens only. No capture, timer, storage or transfer authority. */
object AppDesignPolicy {
    const val BACKGROUND = 0xFF090B0D.toInt()
    const val SURFACE = 0xFF1C2024.toInt()
    const val BORDER = 0xFF454C54.toInt()
    const val TEXT = 0xFFF4F5F6.toInt()
    const val MUTED = 0xFFB6BEC6.toInt()
    const val AMBER = 0xFFFFD166.toInt()
    const val AMBER_SURFACE = 0xFF383326.toInt()
    const val DANGER = 0xFFFFADB5.toInt()
    const val DANGER_SURFACE = 0xFF301C20.toInt()
    const val TARGET_DP = 48

    fun captureLabel(live: Boolean, active: String) = if (live) "● LIVE  $active" else "Return to Camera"
}
