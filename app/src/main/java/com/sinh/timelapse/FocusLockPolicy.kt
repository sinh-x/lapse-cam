package com.sinh.timelapse

/** Monotonic, generation-bound admission; Camera2 observations are the only hold evidence. */
class FocusLockPolicy(private val now: () -> Long) {
    enum class State { IDLE, ACQUIRING, HELD, FAILED }
    @Volatile var state = State.IDLE; private set
    @Volatile var generation = 0L; private set
    var deadline = 0L; private set
    private var confirmations = 0
    fun begin(): Long {
        generation++; state = State.ACQUIRING; confirmations = 0
        deadline = now() + TIMEOUT_MILLIS
        return generation
    }
    fun current(token: Long) = token == generation && state in setOf(State.ACQUIRING, State.HELD)
    fun observe(token: Long, stable: Boolean): State {
        if (!current(token)) return state
        if (state == State.ACQUIRING) {
            if (now() >= deadline) state = State.FAILED
            else { confirmations = if (stable) confirmations + 1 else 0; if (confirmations >= 2) state = State.HELD }
        } else if (!stable) state = State.FAILED
        return state
    }
    fun timeout(token: Long): Boolean {
        if (token != generation || state != State.ACQUIRING || now() < deadline) return false
        state = State.FAILED; return true
    }
    fun fail(token: Long): Boolean {
        if (!current(token)) return false
        state = State.FAILED; return true
    }
    fun cancel() { generation++; state = State.IDLE; confirmations = 0 }
    companion object {
        const val TIMEOUT_MILLIS = 5_000L
        fun stable(fixed: Boolean, afOff: Boolean, actual: Float?, target: Float?, stationary: Boolean): Boolean =
            afOff && (fixed || (stationary && actual != null && target != null && actual.isFinite() && target.isFinite() &&
                actual >= 0 && target >= 0 && kotlin.math.abs(actual - target) <= .01f))
    }
}

/** Main-thread port. A successful callback can later report loss, but never silently reacquires. */
interface CaptureFocus {
    val held: Boolean
    fun acquire(result: (Boolean, String) -> Unit)
    fun release()
}
