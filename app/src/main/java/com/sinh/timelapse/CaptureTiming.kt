package com.sinh.timelapse

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object CaptureTiming {
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    fun timestamp(epochMillis: Long, zoneId: String): String =
        clockFormat.withZone(ZoneId.of(zoneId)).format(Instant.ofEpochMilli(epochMillis))

    /** Stay on the original cadence; skip missed slots rather than capturing a burst. */
    fun nextDelayMillis(startMillis: Long, nowMillis: Long, intervalMillis: Long): Long {
        require(intervalMillis > 0)
        val elapsed = (nowMillis - startMillis).coerceAtLeast(0)
        return intervalMillis - elapsed % intervalMillis
    }

    /** Checked Adaptive budget; Long inputs also reject overflow-sized external values. */
    fun adaptiveFrameBudget(maxSeconds: Long, fps: Long): Int {
        require(maxSeconds in 5L..600L && fps in listOf(15L, 24L, 30L))
        val budget = Math.multiplyExact(maxSeconds, fps)
        require(budget in 75L..18_000L)
        return Math.toIntExact(budget)
    }

    /** Saturate before multiplying, including when given a near-Long.MAX_VALUE interval. */
    fun doubledAdaptiveIntervalMillis(intervalMillis: Long): Long {
        require(intervalMillis > 0)
        return if (intervalMillis >= 30_000L) 60_000L else intervalMillis * 2L
    }

    fun adaptiveIntervalMillis(thinningStage: Long): Long {
        require(thinningStage >= 0)
        return if (thinningStage >= 7) 60_000L else 500L shl thinningStage.toInt()
    }

    /**
     * Compare selectedGap <= 2^stage * completedGap without overflowing either product.
     * Nonnegative active offsets make adjacent subtraction safe. Ceil-halving the left
     * side is equivalent to doubling the right; at most 63 halvings cover any Long gap.
     * For fewer than two selected frames the caller supplies a selected gap of zero.
     */
    fun isWithinAdaptiveGapBound(selectedGapMillis: Long, completedGapMillis: Long, thinningStage: Long): Boolean {
        require(selectedGapMillis >= 0 && completedGapMillis >= 0 && thinningStage >= 0)
        if (selectedGapMillis == 0L) return true
        if (completedGapMillis == 0L) return false
        var scaledGap = selectedGapMillis
        repeat(minOf(thinningStage, 63L).toInt()) {
            scaledGap = (scaledGap ushr 1) + (scaledGap and 1L)
        }
        return scaledGap <= completedGapMillis
    }

    fun presentationTimeUs(frameIndex: Int, fps: Int): Long {
        require(frameIndex >= 0 && fps > 0)
        return frameIndex.toLong() * 1_000_000L / fps
    }
}
