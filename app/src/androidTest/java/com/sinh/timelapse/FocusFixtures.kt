package com.sinh.timelapse

/** Explicit synthetic focus evidence for injected-image tests, never physical camera proof. */
internal class FocusFixtures(private val immediate: Boolean = true) : CaptureFocus {
    override var held = false
    var acquisitions = 0; private set
    var callback: ((Boolean, String) -> Unit)? = null; private set
    override fun acquire(result: (Boolean, String) -> Unit) {
        acquisitions++; callback = result
        if (immediate) { held = true; result(true, "") }
    }
    fun success() { held = true; callback?.invoke(true, "") }
    fun lose() { held = false; callback?.invoke(false, "Synthetic focus loss. Photos retained. Retry.") }
    override fun release() { held = false; callback = null }
}
