package com.sinh.timelapse

import android.os.Looper

/** Sync effects are forbidden on the UI/camera admission thread, including accidental future callers. */
internal object SyncThreads {
    fun io() { check(Looper.myLooper() != Looper.getMainLooper()) { "Sync I/O requires its separate background owner" } }
}
