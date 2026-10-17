package com.lifeadmin.autopilot.testing

import com.lifeadmin.autopilot.notifications.NotificationHealth
import com.lifeadmin.autopilot.notifications.NotificationPublisher
import com.lifeadmin.autopilot.notifications.PostResult
import com.lifeadmin.autopilot.notifications.ReminderNotification
import com.lifeadmin.autopilot.notifications.TestNotification
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.reminders.EnvironmentProbe
import com.lifeadmin.autopilot.reminders.AlarmDriver
import com.lifeadmin.autopilot.reminders.AlarmRequest
import com.lifeadmin.autopilot.reminders.AlarmScheduleResult
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory AlarmManager: one alarm per occurrence id, like PendingIntent identity. */
class FakeAlarmDriver(var exactAllowed: Boolean = false, var failing: Boolean = false) : AlarmDriver {
    val alarms = ConcurrentHashMap<String, AlarmRequest>()
    val exactFlags = ConcurrentHashMap<String, Boolean>()
    override fun canScheduleExact() = exactAllowed
    override fun schedule(request: AlarmRequest): AlarmScheduleResult {
        if (failing) return AlarmScheduleResult(false, false, "IllegalStateException")
        alarms[request.occurrenceId] = request
        val exact = request.preferExact && exactAllowed
        exactFlags[request.occurrenceId] = exact
        return AlarmScheduleResult(true, exact, if (request.preferExact && !exact) "exact_unavailable" else null)
    }
    override fun cancel(occurrenceId: String) { alarms.remove(occurrenceId); exactFlags.remove(occurrenceId) }

    fun fired(occurrenceId: String) { alarms.remove(occurrenceId) }

    val tests = ConcurrentHashMap<String, Pair<Instant, Boolean>>()
    override fun scheduleTest(testId: String, triggerAt: Instant, preferExact: Boolean): AlarmScheduleResult {
        if (failing) return AlarmScheduleResult(false, false, "IllegalStateException")
        val exact = preferExact && exactAllowed
        tests[testId] = triggerAt to exact
        return AlarmScheduleResult(true, exact, if (preferExact && !exact) "exact_unavailable" else null)
    }

    /** Simulates reboot / force-stop: the OS forgets every alarm. */
    fun clearAll() = alarms.clear()

    fun single(): AlarmRequest = alarms.values.single()
    fun forTask(taskId: String): List<AlarmRequest> = alarms.values.filter { it.taskId == taskId }
    fun earliest(): AlarmRequest? = alarms.values.minByOrNull { it.triggerAt }
    fun dueAt(): Instant? = earliest()?.triggerAt
}

class FakeNotificationPublisher : NotificationPublisher {
    var health = NotificationHealth(true, true, true, true)
    val posted = CopyOnWriteArrayList<ReminderNotification>()
    val summaries = CopyOnWriteArrayList<Pair<String, String>>()
    /** Notifications currently visible, keyed by task id (one per task, like the real tag). */
    val visible = ConcurrentHashMap<String, ReminderNotification>()
    override fun health() = health
    override fun ensureChannels() = Unit
    override fun postReminder(notification: ReminderNotification): PostResult {
        if (!health.canPostReminders) return PostResult.BLOCKED_PERMISSION
        posted += notification
        visible[notification.taskId] = notification
        return PostResult.POSTED
    }
    override fun postSummary(title: String, body: String): PostResult {
        if (!health.permissionGranted || !health.summaryChannelEnabled) return PostResult.BLOCKED_CHANNEL_DISABLED
        summaries += title to body
        return PostResult.POSTED
    }
    override fun cancelForTask(taskId: String) { visible.remove(taskId) }
    override fun isShowing(taskId: String): Boolean? = visible.containsKey(taskId)

    val tests = CopyOnWriteArrayList<TestNotification>()
    override fun postTest(notification: TestNotification): PostResult {
        if (!health.canPostReminders) return PostResult.BLOCKED_PERMISSION
        tests += notification
        return PostResult.POSTED
    }
    override fun isTestShowing(testId: String): Boolean? = tests.any { it.testId == testId }
}

/** Fixed environment snapshot for engine/assessor tests. */
class FakeEnvironmentProbe(var env: DeliveryEnvironment = DeliveryEnvironment()) : EnvironmentProbe {
    override fun snapshot(): DeliveryEnvironment = env
}
