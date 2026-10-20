package com.sinh.timelapse

/** Immutable sampling values only; session creation, other frozen settings and persistence are separate. */
sealed interface SamplingConfiguration {
    val fps: Int

    data class Adaptive(val maxSeconds: Int = 30, override val fps: Int = 30) : SamplingConfiguration {
        val frameBudget: Int = CaptureTiming.adaptiveFrameBudget(maxSeconds.toLong(), fps.toLong())
    }

    /** Fixed capture has no Adaptive duration cap or frame budget. */
    data class Fixed(val intervalSeconds: Int, override val fps: Int = 30) : SamplingConfiguration {
        init {
            require(intervalSeconds in listOf(1, 2, 5, 10, 30, 60))
            require(fps in listOf(15, 24, 30))
        }

        val intervalMillis: Long get() = intervalSeconds * 1_000L
    }

    companion object {
        /** Strict whole decimal values: no signs, whitespace, fractions or overflowing integers. */
        fun parseAdaptive(maxSeconds: String = "30", fps: String = "30"): Adaptive =
            Adaptive(decimalInteger(maxSeconds), decimalInteger(fps))

        private fun decimalInteger(value: String): Int {
            require(value.isNotEmpty() && value.all { it in '0'..'9' })
            return requireNotNull(value.toIntOrNull()) { "Whole decimal integer is out of range" }
        }
    }
}

/** IDs are nonnegative, strictly increasing commit sequence numbers, not wall-clock timestamps. */
data class CommittedFrame(val id: Long, val activeOffsetMillis: Long) {
    init {
        require(id >= 0 && activeOffsetMillis >= 0)
    }
}

/**
 * Pure, single-owner streaming selector (FR-3, NFR-2/6). Call only after a photo commits.
 *
 * Commits must arrive in increasing ID and nondecreasing active-time order. An ID at or below
 * the latest committed ID is an already-applied replay prefix and is ignored, even if thinned
 * out. This high-water contract avoids a growing seen-ID set; it is not an out-of-order sorter.
 * Reconstruct by replaying the ordered committed stream from an empty selector.
 *
 * Only B+1 frame slots are retained. Ordinary append is O(1); thinning compacts that same
 * array in O(B), never consults historical frames/files and never deletes source photos.
 * Snapshots are caller-owned copies; do not retain a snapshot for every completion.
 * Persistence/atomic journal integration and failed-shutter handling belong to the caller.
 */
class AdaptiveSampling(val configuration: SamplingConfiguration.Adaptive = SamplingConfiguration.Adaptive()) {
    private val frames = arrayOfNulls<CommittedFrame>(configuration.frameBudget + 1)
    var selectedCount: Int = 0
        private set
    var thinningStage: Long = 0
        private set
    var maxCompletedGapMillis: Long = 0
        private set

    val intervalMillis: Long get() = CaptureTiming.adaptiveIntervalMillis(thinningStage)

    // Structural diagnostics for bounded-memory model tests, not a historical-event cache.
    internal val candidateCapacity: Int get() = frames.size
    internal var maximumCandidateCount: Int = 0
        private set

    fun selectedFrames(): List<CommittedFrame> = List(selectedCount) { checkNotNull(frames[it]) }

    /** Returns false for an already-applied replay ID; invalid new events leave state unchanged. */
    fun onCommitted(frame: CommittedFrame): Boolean {
        val latest = if (selectedCount == 0) null else checkNotNull(frames[selectedCount - 1])
        if (latest != null && frame.id <= latest.id) return false
        require(latest == null || frame.activeOffsetMillis >= latest.activeOffsetMillis)
        val thin = selectedCount == configuration.frameBudget
        // Check before mutation: exhausted arithmetic must not leave a partial transition.
        val nextStage = if (thin) Math.addExact(thinningStage, 1L) else thinningStage
        if (latest != null) {
            maxCompletedGapMillis = maxOf(maxCompletedGapMillis, frame.activeOffsetMillis - latest.activeOffsetMillis)
        }
        frames[selectedCount++] = frame
        maximumCandidateCount = maxOf(maximumCandidateCount, selectedCount)
        if (thin) {
            val candidateCount = selectedCount
            var retained = 0
            var index = 0
            while (index < candidateCount) {
                frames[retained++] = frames[index]
                index += 2
            }
            // Odd B means the latest candidate has an odd index and needs explicit retention.
            if ((candidateCount - 1) % 2 != 0) frames[retained++] = frame
            for (unused in retained until candidateCount) frames[unused] = null
            selectedCount = retained
            thinningStage = nextStage
        }
        return true
    }
}
