package com.sinh.timelapse

import kotlin.math.exp
import kotlin.math.sqrt

/** Best-effort inertial motion, not scene motion or proof of absolute position/velocity. */
internal class PhoneMotionPolicy(private val now: () -> Long) {
    private var gravity: DoubleArray? = null
    private var lastAccelerationAt: Long? = null
    private var lastMotionAt: Long? = null
    var moving = false
        private set

    fun acceleration(x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val time = now()
        val sample = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())
        val previous = gravity
        val significant = if (previous == null) {
            // Seed the gravity vector without interpreting gravity itself as movement.
            gravity = sample.copyOf()
            kotlin.math.abs(norm(sample) - GRAVITY) >= ACCELERATION_THRESHOLD
        } else {
            val residual = DoubleArray(3) { sample[it] - previous[it] }
            val dt = (time - (lastAccelerationAt ?: time)).coerceIn(0, 1_000)
            val alpha = exp(-dt.toDouble() / GRAVITY_FILTER_MILLIS)
            gravity = DoubleArray(3) { alpha * previous[it] + (1 - alpha) * sample[it] }
            norm(residual) >= ACCELERATION_THRESHOLD
        }
        lastAccelerationAt = time
        observe(significant, time)
    }

    fun rotation(x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        observe(norm(doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())) >= ROTATION_THRESHOLD, now())
    }

    fun reset() {
        gravity = null; lastAccelerationAt = null; lastMotionAt = null; moving = false
    }

    private fun observe(significant: Boolean, time: Long) {
        if (significant) { lastMotionAt = time; moving = true }
        else if (moving && time - checkNotNull(lastMotionAt) >= SETTLE_MILLIS) moving = false
    }

    private fun norm(values: DoubleArray) = sqrt(values.sumOf { it * it })

    companion object {
        const val SETTLE_MILLIS = 2_000L
        const val ACCELERATION_THRESHOLD = 1.5 // m/s² after low-pass gravity removal
        const val ROTATION_THRESHOLD = 0.35 // rad/s, about 20 degrees/s
        private const val GRAVITY = 9.80665
        private const val GRAVITY_FILTER_MILLIS = 800.0
    }
}
