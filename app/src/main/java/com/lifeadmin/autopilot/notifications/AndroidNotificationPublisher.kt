package com.lifeadmin.autopilot.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lifeadmin.autopilot.MainActivity
import com.lifeadmin.autopilot.R
import com.lifeadmin.autopilot.receivers.NotificationActionReceiver
import com.lifeadmin.autopilot.reminders.IntentContract

/**
 * Posts reminder notifications. One notification per task (tag = task id); a newer reminder for
 * the same task replaces the older one. Swiping a notification away changes nothing.
 */
class AndroidNotificationPublisher(private val context: Context) : NotificationPublisher {
    private val manager = NotificationManagerCompat.from(context)

    override fun ensureChannels() {
        val reminders = NotificationChannel(CHANNEL_REMINDERS, "Task reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Follow-up reminders for open tasks. Change sound or importance here."
        }
        val summary = NotificationChannel(CHANNEL_SUMMARY, "Summaries", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Combined notices, e.g. reminders that came due while the phone was off."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(reminders, summary))
    }

    override fun health(): NotificationHealth {
        val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        fun channelOn(id: String) = manager.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
        return NotificationHealth(
            permissionGranted = permission,
            appNotificationsEnabled = manager.areNotificationsEnabled(),
            reminderChannelEnabled = channelOn(CHANNEL_REMINDERS),
            summaryChannelEnabled = channelOn(CHANNEL_SUMMARY),
        )
    }

    override fun postReminder(notification: ReminderNotification): PostResult {
        val h = health()
        if (!h.permissionGranted) return PostResult.BLOCKED_PERMISSION
        if (!h.appNotificationsEnabled) return PostResult.BLOCKED_APP_DISABLED
        if (!h.reminderChannelEnabled) return PostResult.BLOCKED_CHANNEL_DISABLED
        ensureChannels()
        val taskId = notification.taskId
        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(notification.title)
            .setContentText(if (notification.late) "Late reminder · ${notification.body}" else notification.body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setWhen(notification.plannedAt.toEpochMilli())
            .setShowWhen(true)
            .setContentIntent(openTaskIntent(taskId))
            .addAction(0, "Done", actionIntent(taskId, IntentContract.ACTION_DONE))
            .addAction(0, "Snooze", actionIntent(taskId, IntentContract.ACTION_SNOOZE))
            .addAction(0, "Open", openTaskIntent(taskId))
        if (notification.redactOnLockScreen) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_REMINDERS)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Task reminder")
                        .setContentText("Unlock to see details")
                        .build(),
                )
        }
        return try {
            manager.notify(taskId, NOTIFICATION_ID, builder.build())
            PostResult.POSTED
        } catch (e: SecurityException) {
            PostResult.BLOCKED_PERMISSION
        }
    }

    override fun postSummary(title: String, body: String): PostResult {
        val h = health()
        if (!h.permissionGranted) return PostResult.BLOCKED_PERMISSION
        if (!h.appNotificationsEnabled) return PostResult.BLOCKED_APP_DISABLED
        if (!h.summaryChannelEnabled) return PostResult.BLOCKED_CHANNEL_DISABLED
        ensureChannels()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_SUMMARY)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return try {
            manager.notify(SUMMARY_TAG, NOTIFICATION_ID, n)
            PostResult.POSTED
        } catch (e: SecurityException) {
            PostResult.BLOCKED_PERMISSION
        }
    }

    override fun cancelForTask(taskId: String) = manager.cancel(taskId, NOTIFICATION_ID)

    private fun openTaskIntent(taskId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(IntentContract.taskUri(taskId, "open"))
            .putExtra(IntentContract.EXTRA_TASK_ID, taskId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(taskId: String, action: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .setData(IntentContract.taskUri(taskId, action.substringAfterLast('.').lowercase()))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_REMINDERS = "action_reminders"
        const val CHANNEL_SUMMARY = "summary_updates"
        const val NOTIFICATION_ID = 1001
        const val SUMMARY_TAG = "summary"
    }
}
