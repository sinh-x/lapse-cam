package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class PhoneMotionPolicyTest {
    private class Fixture {
        var time = 0L
        val policy = PhoneMotionPolicy { time }
        fun still() { policy.acceleration(0f, 0f, 9.80665f); policy.rotation(0f, 0f, 0f) }
    }

    @Test fun gravityAndSmallTripodVibrationsDoNotTriggerMovement() {
        val f = Fixture(); f.still()
        repeat(200) { i ->
            f.time += 60
            f.policy.acceleration(if (i % 2 == 0) 0.15f else -0.15f, 0.1f, 9.80665f)
            f.policy.rotation(0.01f, -0.02f, 0.01f)
            assertFalse(f.policy.moving)
        }
    }

    @Test fun translationWakesImmediatelyThenRequiresTwoSecondsOfQuiet() {
        val f = Fixture(); f.still()
        f.time = 100; f.policy.acceleration(3f, 0f, 9.80665f)
        assertTrue(f.policy.moving)
        // Gyroscope silence alone cannot erase the recent significant acceleration.
        f.time = 2_099; f.policy.rotation(0f, 0f, 0f); assertTrue(f.policy.moving)
        f.time = 2_100; f.policy.rotation(0f, 0f, 0f); assertFalse(f.policy.moving)
    }

    @Test fun rotationInEitherDirectionAndRepeatedMotionKeepTheGateClosed() {
        val f = Fixture(); f.still()
        for (speed in listOf(0.7f, -0.7f, 1f)) {
            f.time += 1_000; f.policy.rotation(speed, 0f, 0f); assertTrue(f.policy.moving)
            f.time += 1_999; f.policy.rotation(0f, 0f, 0f); assertTrue(f.policy.moving)
        }
        f.time++; f.policy.rotation(0f, 0f, 0f); assertFalse(f.policy.moving)
    }

    @Test fun anyAxisCanDetectMotionAndAQuietSensorDoesNotOverrideTheOther() {
        for (axis in 0..2) {
            val f = Fixture(); f.still()
            val values = FloatArray(3); values[axis] = 0.7f
            f.policy.rotation(values[0], values[1], values[2]); assertTrue(f.policy.moving)
            f.time = 1_900; f.policy.acceleration(4f, 0f, 9.80665f)
            f.time = 2_100; f.policy.rotation(0f, 0f, 0f); assertTrue(f.policy.moving)
        }
    }

    @Test fun invalidSamplesDoNotCreateMotionOrPrematurelyDeclareStillness() {
        val f = Fixture(); f.policy.rotation(Float.NaN, 0f, 0f); assertFalse(f.policy.moving)
        f.policy.rotation(1f, 0f, 0f); assertTrue(f.policy.moving)
        f.time = 10_000
        f.policy.acceleration(0f, Float.POSITIVE_INFINITY, 0f)
        f.policy.rotation(Float.NaN, 0f, 0f); assertTrue(f.policy.moving)
        f.still(); assertFalse(f.policy.moving)
    }

    @Test fun resetDropsGravityAndMotionHistoryForTheNextForegroundOwner() {
        val f = Fixture(); f.still(); f.policy.rotation(1f, 0f, 0f)
        assertTrue(f.policy.moving)
        f.policy.reset(); assertFalse(f.policy.moving)
        // A different stationary orientation must be seeded as gravity, not pickup.
        f.policy.acceleration(9.80665f, 0f, 0f); assertFalse(f.policy.moving)
    }

    @Test fun classifierAndBlackoutPolicyRequireQuietThenFreshThirtySeconds() {
        val f = Fixture(); f.still()
        val display = BlackoutPolicy { f.time }
        val live = BlackoutPolicy.Input(live = true, cameraReady = true, foreground = true, focused = true)
        display.update(live)
        f.time = 30_000; display.timeout(display.timer!!); assertTrue(display.covered)
        f.policy.rotation(0f, 1f, 0f)
        display.update(live.copy(moving = f.policy.moving)); assertFalse(display.covered)
        f.time = 31_999; f.policy.rotation(0f, 0f, 0f)
        display.update(live.copy(moving = f.policy.moving)); assertNull(display.timer)
        f.time = 32_000; f.policy.rotation(0f, 0f, 0f)
        display.update(live.copy(moving = f.policy.moving)); assertEquals(62_000L, display.timer!!.deadline)
        val deadline = display.timer
        repeat(10) { f.time += 60; f.still(); display.update(live.copy(moving = f.policy.moving)) }
        assertEquals(deadline, display.timer) // Quiet sensor emissions are not user interaction.
    }
}
