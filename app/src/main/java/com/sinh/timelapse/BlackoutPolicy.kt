package com.sinh.timelapse

/** Activity-owned display policy. No wall clock, capture commands or persisted idle state. */
internal class BlackoutPolicy(private val now: () -> Long) {
    data class Input(
        val enabled: Boolean = true,
        val live: Boolean = false,
        val cameraReady: Boolean = false,
        val foreground: Boolean = false,
        val focused: Boolean = false,
        val modal: Boolean = false,
        val actionableError: Boolean = false,
        val interacting: Boolean = false,
    ) {
        val eligible: Boolean get() = enabled && live && cameraReady && foreground && focused &&
            !modal && !actionableError && !interacting
    }
    data class Timer(val generation: Long, val deadline: Long)

    private var input = Input()
    private var generation = 0L
    private var closed = false
    private var knownTerminals: Set<String>? = null
    var covered = false
        private set
    var timer: Timer? = null
        private set

    /** Identical eligible state emissions deliberately retain the original deadline. */
    fun update(value: Input) {
        val changed = input.eligible != value.eligible
        input = value
        if (!closed && changed) reset()
    }

    /** Genuine input, completed return, or a one-shot notice starts a fresh interval. */
    fun interact() { if (!closed) reset() }

    /** Terminal identities include the work ID and end marker, not tick/remaining values. */
    fun observeTerminals(identities: Set<String>) {
        val previous = knownTerminals
        knownTerminals = previous.orEmpty() + identities
        if (previous != null && (identities - previous).isNotEmpty() && input.foreground) interact()
    }

    /** Both identity and eligibility are rechecked even if a removed callback was already queued. */
    fun timeout(expected: Timer) {
        if (closed || !input.eligible || timer != expected || expected.generation != generation) return
        if (now() < expected.deadline) return
        timer = null
        covered = true
    }

    fun close() {
        closed = true
        generation++
        timer = null
        covered = false
    }

    private fun reset() {
        generation++
        covered = false
        timer = if (input.eligible) Timer(generation, now() + IDLE_MILLIS) else null
    }

    companion object { const val IDLE_MILLIS = 30_000L }
}
