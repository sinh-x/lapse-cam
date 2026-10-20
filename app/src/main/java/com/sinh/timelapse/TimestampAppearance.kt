package com.sinh.timelapse

/** Stable names, not Android Typeface objects or enum ordinals, are persisted per session. */
enum class StampFamily { MONOSPACE, SANS_SERIF, SERIF }
enum class StampPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

data class TimestampAppearance(
    val family: StampFamily = StampFamily.MONOSPACE,
    val bold: Boolean = true,
    val size: Int = 44,
    val position: StampPosition = StampPosition.BOTTOM_LEFT,
    val date: Boolean = false,
) {
    init { require(size in listOf(32, 44, 60)) }
}

/** UI and durable checkpoints use monotonic offsets, never wall-clock differences for active time. */
class ActiveRecordingClock(private val now: () -> Long) {
    private var accumulated = 0L
    private var segment: Long? = null
    @Synchronized fun restore(millis: Long) { require(millis >= 0); accumulated = millis; segment = null }
    @Synchronized fun start() { if (segment == null) segment = now() }
    @Synchronized fun value(): Long = Math.addExact(accumulated, segment?.let { (now() - it).coerceAtLeast(0) } ?: 0)
    @Synchronized fun stop(): Long { accumulated = value(); segment = null; return accumulated }
}

object ExportSpace {
    const val CAPTURE_MIN_BYTES = 50L * 1024 * 1024
    fun hasHeadroom(available: Long, count: Long, fps: Int): Boolean = available >= requiredBytes(count, fps)
    /** H=50MiB + 2*ceil(1.25*625000*count/fps), entirely integer/checked. */
    fun requiredBytes(count: Long, fps: Int): Long {
        require(count >= 0 && fps > 0)
        val numerator = Math.multiplyExact(count, 3_125_000L)
        val denominator = Math.multiplyExact(fps.toLong(), 4L)
        val estimate = Math.addExact(numerator / denominator, if (numerator % denominator == 0L) 0L else 1L)
        return Math.addExact(CAPTURE_MIN_BYTES, Math.multiplyExact(estimate, 2L))
    }
}
