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

    fun presentationTimeUs(frameIndex: Int, fps: Int): Long {
        require(frameIndex >= 0 && fps > 0)
        return frameIndex.toLong() * 1_000_000L / fps
    }
}
