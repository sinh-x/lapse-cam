package com.sinh.timelapse

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

internal interface TimeboxReminders {
    fun availability(): String
    fun access(): TimeboxReminderAccess? = null
    fun schedule(work: WorkSession)
    fun cancel(id: String)
    fun notifyEnded(id: String)
}

internal class TimeboxAlarmScheduler(private val context: Context) : TimeboxReminders {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    init { notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Timebox reminders", NotificationManager.IMPORTANCE_DEFAULT)) }
    override fun access() = TimeboxReminderAccess(
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        notifications.areNotificationsEnabled(),
        notifications.getNotificationChannel(CHANNEL)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true,
        alarms.canScheduleExactAlarms(),
    )
    override fun availability(): String = access().availability()
    private fun pending(id: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, TimeboxAlarmReceiver::class.java).setData(Uri.parse("timelapse://timebox/$id")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun schedule(work: WorkSession) { alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, checkNotNull(work.deadline), pending(work.id)) }
    override fun cancel(id: String) { alarms.cancel(pending(id)) }
    override fun notifyEnded(id: String) {
        if (availability().isNotEmpty()) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("timelapse://work/$id")).putExtra("workId", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(id, 1, Notification.Builder(context, CHANNEL)
            .setSmallIcon(com.sinh.timelapse.R.drawable.ic_timelapse)
            .setContentTitle("Timebox ended").setContentText("Open Work to review or create a new timebox.")
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(open).build())
    }
    companion object { const val CHANNEL = "work-timebox" }
}

class TimeboxAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "timelapse" || uri.host != "timebox") return
        val id = uri.lastPathSegment ?: return
        if (runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false).not()) return
        val pending = goAsync()
        WorkSessionCoordinator.get(context.applicationContext as android.app.Application).expire(id) { pending.finish() }
    }
}
