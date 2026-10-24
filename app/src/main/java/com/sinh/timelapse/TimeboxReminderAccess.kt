package com.sinh.timelapse

/** Separate platform gates: permission to display an alert is not permission to wake at a deadline. */
data class TimeboxReminderAccess(
    val notificationPermission: Boolean,
    val appNotifications: Boolean,
    val reminderChannel: Boolean,
    val backgroundTiming: Boolean,
) {
    val notificationsAllowed: Boolean get() = notificationPermission && appNotifications && reminderChannel
    val notificationStatus: String get() = when {
        notificationsAllowed -> "Allowed"
        !notificationPermission -> "Not allowed"
        else -> "Blocked in Android settings"
    }
    val notificationAction: String get() = when {
        notificationsAllowed -> "Notifications allowed"
        notificationPermission -> "Open notification settings"
        else -> "Allow end notification"
    }
    val timingAction: String get() = if (backgroundTiming) "Background timing allowed" else "Allow background timing"
    fun availability(): String {
        val missing = listOfNotNull(if (!notificationsAllowed) "end notifications" else null, if (!backgroundTiming) "background timing" else null)
        return if (missing.isEmpty()) "" else "Background reminder unavailable: ${missing.joinToString(" and ")} not allowed. Reopen to reconcile expiry."
    }
}
