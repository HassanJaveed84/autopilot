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
    /** Actual posting time — shown as the notification time (not the planned time). */
    val postedAt: Instant = plannedAt,
    /** Local due time label, e.g. "18:25" or "Fri 17 Oct 18:25". */
    val dueLabel: String = "",
    /** Where the Snooze action lands, e.g. "tomorrow 09:00" or "11:42"; blank hides it from the label. */
    val snoozeLabel: String = "",
)

data class TestNotification(
    val testId: String,
    val title: String,
    val body: String,
    val postedAt: Instant,
)

interface NotificationPublisher {
    fun health(): NotificationHealth
    fun ensureChannels()
    fun postReminder(notification: ReminderNotification): PostResult
    fun postSummary(title: String, body: String): PostResult
    fun cancelForTask(taskId: String)

    /** Self-test notification on the real reminder channel (same sound/vibration settings). */
    fun postTest(notification: TestNotification): PostResult = PostResult.FAILED

    /** Whether the test notification is currently listed as active; null if the OS can't tell. */
    fun isTestShowing(testId: String): Boolean? = null
}
