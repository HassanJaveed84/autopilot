package com.lifeadmin.autopilot.reminders

import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.notifications.NotificationPublisher

/**
 * Privacy-safe diagnostics: counts, versions and scheduler state only. Never includes task
 * titles, notes, steps or event summaries, so it can be copied into a bug report.
 */
class Diagnostics(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val alarms: AlarmDriver,
    private val notifications: NotificationPublisher,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val appVersion: String,
    private val sdkInt: Int,
) {
    suspend fun report(): String {
        val s = settings.current()
        val tasks = db.taskDao().getAll()
        val occurrences = db.occurrenceDao().getAll()
        val active = occurrences.filter { it.status == OccurrenceStatus.SCHEDULED || it.status == OccurrenceStatus.PLANNED }
        val h = notifications.health()
        return buildString {
            appendLine("Life Admin Autopilot diagnostics")
            appendLine("app=$appVersion sdk=$sdkInt db_schema=${AppDatabase.VERSION}")
            appendLine("now=${clock.now()} zone=${zoneProvider.current().id}")
            appendLine("tasks_total=${tasks.size} " + tasks.groupingBy { it.status.name }.eachCount().toSortedMap().entries.joinToString(" ") { "${it.key.lowercase()}=${it.value}" })
            appendLine("archived=${tasks.count { it.archivedAt != null }} snoozed=${tasks.count { (it.snoozedUntil ?: java.time.Instant.MIN) > clock.now() }}")
            appendLine("policies_enabled=${db.policyDao().getAll().count { it.enabled }} time_slots=${db.policyDao().getAllSlots().size}")
            appendLine("occurrences_active=${active.size} scheduled=${active.count { it.status == OccurrenceStatus.SCHEDULED }} planned_retry=${active.count { it.status == OccurrenceStatus.PLANNED }} exact=${active.count { it.exact }}")
            appendLine("next_due=${active.minOfOrNull { it.plannedAt } ?: "none"}")
            appendLine("ledger " + occurrences.groupingBy { it.status.name }.eachCount().toSortedMap().entries.joinToString(" ") { "${it.key.lowercase()}=${it.value}" })
            appendLine("notifications permission=${h.permissionGranted} app=${h.appNotificationsEnabled} reminder_channel=${h.reminderChannelEnabled} summary_channel=${h.summaryChannelEnabled} in_app_toggle=${s.allowReminderNotifications}")
            appendLine("exact_alarm_access=${alarms.canScheduleExact()} precision_mode=${s.precisionMode}")
            appendLine("quiet_hours enabled=${s.quietHours.enabled} ${s.quietHours.start}-${s.quietHours.end} behavior=${s.quietHoursBehavior} grace_min=${s.graceWindowMinutes} daily_cap=${s.maxNotificationsPerDay}")
            append("last_reconcile=${s.lastReconcileAt ?: "never"} ${s.lastReconcileSummary.orEmpty()}")
        }
    }
}
