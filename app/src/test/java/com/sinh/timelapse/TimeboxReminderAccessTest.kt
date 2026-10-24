package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class TimeboxReminderAccessTest {
    @Test fun notificationAndBackgroundTimingAreIndependentAndBothAreDisclosed() {
        for (permission in listOf(false, true)) for (app in listOf(false, true)) for (channel in listOf(false, true)) for (timing in listOf(false, true)) {
            val access = TimeboxReminderAccess(permission, app, channel, timing)
            assertEquals(permission && app && channel, access.notificationsAllowed)
            assertEquals(access.notificationsAllowed && timing, access.availability().isEmpty())
            if (!access.notificationsAllowed) assertTrue(access.availability().contains("end notifications"))
            if (!timing) assertTrue(access.availability().contains("background timing"))
            if (access.notificationsAllowed) assertEquals("Notifications allowed", access.notificationAction)
            else assertFalse(access.notificationAction == "Notifications allowed")
            assertEquals(if (timing) "Background timing allowed" else "Allow background timing", access.timingAction)
        }
    }
    @Test fun blockedChannelDirectsToSettingsInsteadOfAnotherRuntimePermissionPrompt() {
        val access = TimeboxReminderAccess(true, true, false, true)
        assertEquals("Blocked in Android settings", access.notificationStatus)
        assertEquals("Open notification settings", access.notificationAction)
        assertFalse(access.notificationsAllowed)
    }
}
