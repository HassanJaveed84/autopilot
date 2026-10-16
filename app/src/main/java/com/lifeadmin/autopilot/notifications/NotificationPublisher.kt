package com.lifeadmin.autopilot.notifications

import java.time.Instant

data class NotificationHealth(
    val permissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val reminderChannelEnabled: Boolean,
    val summaryChannelEnabled: Boolean,
) {
    val canPostReminders: Boolean get() = permissionGranted && appNotificationsEnabled && reminderChannelEnabled
}

enum class PostResult { POSTED, BLOCKED_PERMISSION, BLOCKED_APP_DISABLED, BLOCKED_CHANNEL_DISABLED, BLOCKED_BY_SETTING, FAILED }

data class ReminderNotification(
    val taskId: String,
    val occurrenceId: String,
    val title: String,
    val body: String,
    val plannedAt: Instant,
    val late: Boolean,
    val redactOnLockScreen: Boolean,
)

interface NotificationPublisher {
    fun health(): NotificationHealth
    fun ensureChannels()
    fun postReminder(notification: ReminderNotification): PostResult
    fun postSummary(title: String, body: String): PostResult
    fun cancelForTask(taskId: String)
}
