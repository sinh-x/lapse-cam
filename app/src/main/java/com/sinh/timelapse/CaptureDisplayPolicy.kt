package com.sinh.timelapse

import java.util.Locale

/** Formats owner snapshots only. Never advances a timer, starts work, or writes a checkpoint. */
internal object CaptureDisplayPolicy {
    fun duration(millis: Long?): String {
        if (millis == null) return "Unavailable (legacy)"
        val seconds = millis.coerceAtLeast(0) / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
    /** Routine owner emissions may include subsecond save snapshots. Do not flicker a seconds
     * label forward/backward between those and the 1Hz owner tick. Safety transitions bypass
     * the presentation throttle; this does not extrapolate elapsed time. */
    class ActiveLabel {
        private var identity: Triple<String?, Boolean, Boolean>? = null
        private var nextRefresh = 0L
        private var label = ""
        fun project(id: String?, live: Boolean, unknownTail: Boolean, activeMillis: Long?, now: Long): String {
            val key = Triple(id, live, unknownTail)
            if (identity != key || now >= nextRefresh) {
                identity = key; nextRefresh = now + 1000
                label = duration(activeMillis) + if (unknownTail) " · last saved; tail unknown" else ""
            }
            return label
        }
    }
    data class Blackout(val title: String, val remaining: String, val recording: String)
    fun blackout(title: String?, remaining: String?, activeMillis: Long?, unknownTail: Boolean, timeboxReady: Boolean = true) =
        Blackout(if (!timeboxReady) "Timebox status unavailable" else title ?: "No timebox running",
            if (timeboxReady && title != null) remaining.orEmpty() else "",
            duration(activeMillis) + if (unknownTail) " · last saved; tail unknown" else "")
}
