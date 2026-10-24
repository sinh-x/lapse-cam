package com.sinh.timelapse

/** Pure projection: export activity never hides active capture or disables Pause. */
object CameraUiPolicy {
    data class Input(val capturing: Boolean, val hasSession: Boolean, val busy: Boolean,
        val switching: Boolean, val recoveryBlocked: Boolean, val cameraReady: Boolean,
        val split: Boolean, val loading: Boolean = false, val working: Boolean = false)
    data class Output(val primary: String, val enabled: Boolean, val live: Boolean, val finish: Boolean, val finishEnabled: Boolean)
    fun project(input: Input) = with(input) {
        Output(if (capturing) "Pause capture" else if (hasSession) "Resume capture" else "Start capture",
            capturing || (!busy && !switching && !recoveryBlocked && cameraReady), capturing,
            hasSession && split, hasSession && split && !loading && !working && !switching && !recoveryBlocked)
    }
}
