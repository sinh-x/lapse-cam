package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class FocusLockPolicyTest {
    private var now = 0L
    private val policy = FocusLockPolicy { now }
    @Test fun twoCurrentStableObservationsAdmitFixedOrManualFocus() {
        val token = policy.begin()
        assertEquals(FocusLockPolicy.State.ACQUIRING, policy.observe(token, true))
        assertEquals(FocusLockPolicy.State.HELD, policy.observe(token, true))
        now = 60_000
        assertFalse(policy.timeout(token))
        assertEquals(FocusLockPolicy.State.HELD, policy.observe(token, true))
    }
    @Test fun unstableAcquisitionResetsConfirmationAndLossFailsClosed() {
        val token = policy.begin()
        policy.observe(token, true); policy.observe(token, false)
        assertEquals(FocusLockPolicy.State.ACQUIRING, policy.observe(token, true))
        policy.observe(token, true)
        assertEquals(FocusLockPolicy.State.FAILED, policy.observe(token, false))
        assertEquals(FocusLockPolicy.State.FAILED, policy.observe(token, true))
    }
    @Test fun exactFiveSecondDeadlineCannotAdmitLateSuccess() {
        val token = policy.begin(); policy.observe(token, true); now = 5_000
        assertEquals(FocusLockPolicy.State.FAILED, policy.observe(token, true))
    }
    @Test fun timeoutAndUnsupportedFailureAreTerminalUntilExplicitRetry() {
        var token = policy.begin(); now = 4_999; assertFalse(policy.timeout(token))
        now++; assertTrue(policy.timeout(token)); assertFalse(policy.fail(token))
        token = policy.begin(); assertTrue(policy.fail(token))
        assertFalse(policy.current(token)); assertEquals(FocusLockPolicy.State.FAILED, policy.observe(token, true))
    }
    @Test fun cancellationAndReplacementRejectEveryOldCallback() {
        val old = policy.begin(); policy.cancel(); val current = policy.begin()
        assertFalse(policy.fail(old)); now = 5_000; assertFalse(policy.timeout(old))
        assertEquals(FocusLockPolicy.State.ACQUIRING, policy.observe(old, true))
        assertTrue(policy.current(current))
        policy.cancel(); assertEquals(FocusLockPolicy.State.IDLE, policy.observe(current, true))
    }
    @Test fun evidenceRequiresAfOffAndStationaryMeasuredDistanceUnlessFixedOptics() {
        assertTrue(FocusLockPolicy.stable(true, true, null, null, false))
        assertFalse(FocusLockPolicy.stable(true, false, null, null, true))
        assertTrue(FocusLockPolicy.stable(false, true, 1.5f, 1.5f, true))
        assertFalse(FocusLockPolicy.stable(false, true, 1.5f, 1.5f, false))
        assertFalse(FocusLockPolicy.stable(false, true, null, 1.5f, true))
        assertFalse(FocusLockPolicy.stable(false, true, 1.7f, 1.5f, true))
        assertFalse(FocusLockPolicy.stable(false, true, Float.NaN, Float.NaN, true))
    }
    @Test fun uninterruptedHoldDoesNotNeedAnotherAcquireAcrossRolloverOrBrowsing() {
        val token = policy.begin(); policy.observe(token, true); policy.observe(token, true)
        repeat(1000) { now += 1000; policy.observe(token, true) }
        assertEquals(token, policy.generation); assertEquals(FocusLockPolicy.State.HELD, policy.state)
    }
}
