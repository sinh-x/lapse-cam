package com.sinh.timelapse

/** Opt-in applies only at creation. Missing legacy metadata is OFF, never the UI default. */
enum class SessionSplit(val storedValue: String, val limitMillis: Long?) {
    OFF("off", null), TWO_HOURS("2h", 7_200_000), THREE_HOURS("3h", 10_800_000);

    fun allowsShutter(activeMillis: Long): Boolean {
        require(activeMillis >= 0)
        return limitMillis == null || activeMillis < limitMillis
    }

    companion object {
        val DEFAULT = THREE_HOURS
        fun parse(value: String): SessionSplit = entries.singleOrNull { it.storedValue == value }
            ?: throw IllegalArgumentException("Unknown session split: $value")
        fun forConfiguration(configuration: SamplingConfiguration, requested: SessionSplit): SessionSplit =
            if (configuration is SamplingConfiguration.Adaptive) requested else OFF
    }
}

/** A filename alone is not provenance: only canonical IDs below the durable reservation ceiling qualify. */
internal object SessionIntentPolicy {
    fun isReservedSidecar(name: String, nextId: Long): Boolean {
        require(nextId >= 0)
        val base = name.removeSuffix(".bak")
        if (!base.startsWith("intent_") || !base.endsWith(".json")) return false
        val id = base.removePrefix("intent_").removeSuffix(".json").toLongOrNull() ?: return false
        return id >= 0 && id < nextId && base == "intent_$id.json"
    }
}

/** Persist reserve() BEFORE effects. A reserved slot is consumed even if the process dies. */
data class AttemptCycle(val attempts: Int = 0, val remainingMillis: Long = 0, val reserved: Boolean = false) {
    init {
        require(attempts in 0..3 && remainingMillis in 0..30_000)
        require(!reserved || (attempts > 0 && remainingMillis == 0L))
        require(attempts != 0 || remainingMillis == 0L)
        require(attempts != 3 || remainingMillis == 0L)
    }
    val exhausted: Boolean get() = attempts == 3 && !reserved
    val due: Boolean get() = !reserved && !exhausted && remainingMillis == 0L
    fun reserve(): AttemptCycle {
        check(due) { "Attempt not eligible" }
        return AttemptCycle(attempts + 1, 0, true)
    }
    fun failed(): AttemptCycle {
        check(reserved) { "No reserved attempt" }
        return AttemptCycle(attempts, when (attempts) { 1 -> 10_000; 2 -> 30_000; else -> 0 })
    }
    /** Feed only measured app-open elapsed time, not wall time or downtime. */
    fun elapse(eligibleMillis: Long): AttemptCycle {
        require(eligibleMillis >= 0)
        return copy(remainingMillis = (remainingMillis - eligibleMillis).coerceAtLeast(0))
    }
    fun manualRetry(): AttemptCycle {
        check(!reserved && exhausted) { "Only exhausted inactive work can start a new cycle" }
        return AttemptCycle()
    }
}

/** Fair metadata-only scan. Delayed/exhausted work is skipped without allocating another history list. */
internal object SessionQueuePolicy {
    fun <T> nextDue(records: List<T>, after: String?, identity: (T) -> String, due: (T) -> Boolean): T? {
        if (records.isEmpty()) return null
        val start = (records.indexOfFirst { identity(it) == after } + 1) % records.size
        for (offset in records.indices) {
            val index = ((start.toLong() + offset) % records.size).toInt()
            val candidate = records[index]
            if (due(candidate)) return candidate
        }
        return null
    }
}

/** Single-owner close gate: pause never closes, finish wins over a pending rollover. */
class SessionCloseGate {
    enum class Reason { ROLLOVER, FINISH }
    var reason: Reason? = null
        private set
    private var delivered = false
    fun request(reason: Reason) {
        if (!delivered && (this.reason == null || reason == Reason.FINISH)) this.reason = reason
    }
    fun drain(inFlight: Boolean): Reason? {
        if (inFlight || delivered) return null
        return reason?.also { delivered = true }
    }
}
