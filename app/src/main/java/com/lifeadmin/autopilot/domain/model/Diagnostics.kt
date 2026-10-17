package com.lifeadmin.autopilot.domain.model

import java.time.Duration
import java.time.Instant

/**
 * One step in a reminder's delivery lifecycle. Recorded to the diagnostics ledger so a late or
 * silent reminder can be explained afterwards. Never contains task titles, notes or steps.
 *
 * Important: [POSTED] only means `NotificationManager.notify()` returned without error (and, when
 * available, that the notification was listed as active right after). It is **not** proof that a
 * person saw, heard or felt it.
 */
enum class DiagnosticType {
    /** Alarm handed to AlarmManager (new, changed, or re-registered during recovery). */
    SCHEDULED,
    /** AlarmManager refused the alarm; occurrence kept PLANNED for retry. */
    SCHEDULE_FAILED,
    /** The alarm broadcast reached our receiver (timestamp taken before any locking). */
    ALARM_RECEIVED,
    /** A received alarm needed no post: duplicate, stale generation, early, or no longer eligible. */
    ALARM_IGNORED,
    /** A full reconcile ran (app start/resume, boot, time change, periodic, …). */
    RECONCILE,
    /** About to call NotificationManager.notify(). */
    POST_REQUESTED,
    /** notify() returned; `outcome` says whether it was listed as active afterwards. */
    POSTED,
    /** notify() threw or the platform refused (permission, app/channel disabled). */
    POST_FAILED,
    /** Deliberately not shown (in-app switch off, daily cap, blocked before posting). */
    SUPPRESSED,
    /** Too late beyond the grace window; not shown. */
    MISSED,
    /** Task snoozed / snooze cleared. */
    SNOOZED,
    UNSNOOZED,
    /** Occurrence canceled; `outcome` carries the reason (task_done, task_paused, snoozed, …). */
    CANCELED,
    /** Self-test: notification posted immediately from the health screen. */
    TEST_NOTIFICATION,
    /** Self-test: one-shot alarm scheduled through the real AlarmManager path. */
    TEST_SCHEDULED,
    /** Self-test alarm broadcast received. */
    TEST_ALARM_RECEIVED,
}

data class DiagnosticEntry(
    val id: String,
    /** Actual wall-clock time the step happened. */
    val at: Instant,
    val type: DiagnosticType,
    val occurrenceId: String? = null,
    val taskId: String? = null,
    /** When the reminder was due (effective planned instant). */
    val plannedAt: Instant? = null,
    /** Trigger time handed to AlarmManager. */
    val triggerAt: Instant? = null,
    /** What caused this step: "alarm", "mutation", "reconcile:<reason>", "test". */
    val source: String? = null,
    /** Exact (true) vs approximate/best-effort (false) alarm, when relevant. */
    val exact: Boolean? = null,
    /** `at - plannedAt` for receipt/post steps; negative means early. */
    val lateness: Duration? = null,
    /** Short machine-readable result/reason code. */
    val outcome: String? = null,
    /** Compact environment snapshot or error class name. Never task content. */
    val detail: String? = null,
)

enum class RingerMode { NORMAL, VIBRATE, SILENT, UNKNOWN }

/** Do Not Disturb state as reported by NotificationManager.getCurrentInterruptionFilter(). */
enum class InterruptionFilter { ALL, PRIORITY, ALARMS, NONE, UNKNOWN }

/**
 * Device state that decides whether a posted notification is actually noticeable, read through
 * public Android APIs only. Nullable = not available on this OS version / unknown.
 */
data class DeliveryEnvironment(
    val ringerMode: RingerMode = RingerMode.UNKNOWN,
    val interruptionFilter: InterruptionFilter = InterruptionFilter.UNKNOWN,
    val notificationVolume: Int? = null,
    val notificationVolumeMax: Int? = null,
    val channelExists: Boolean = false,
    val channelImportance: Int? = null,
    val channelHasSound: Boolean? = null,
    val channelVibrates: Boolean? = null,
    val channelBypassesDnd: Boolean? = null,
    val deviceIdle: Boolean? = null,
    val powerSave: Boolean? = null,
    val ignoringBatteryOptimizations: Boolean? = null,
    val backgroundRestricted: Boolean? = null,
    /** UsageStatsManager standby bucket (10 active … 45 restricted). */
    val standbyBucket: Int? = null,
    val canScheduleExact: Boolean = false,
) {
    /** Compact, privacy-safe one-line form for the diagnostics ledger and exports. */
    fun compact(): String = buildString {
        append("ringer=").append(ringerMode.name.lowercase())
        append(" dnd=").append(interruptionFilter.name.lowercase())
        notificationVolume?.let { append(" vol=").append(it).append('/').append(notificationVolumeMax ?: "?") }
        append(" channel=")
        if (!channelExists) append("missing") else {
            append("imp").append(channelImportance ?: "?")
            append(",sound=").append(channelHasSound.yn())
            append(",vibrate=").append(channelVibrates.yn())
            append(",bypassDnd=").append(channelBypassesDnd.yn())
        }
        append(" idle=").append(deviceIdle.yn())
        append(" saver=").append(powerSave.yn())
        append(" battOptExempt=").append(ignoringBatteryOptimizations.yn())
        append(" bgRestricted=").append(backgroundRestricted.yn())
        standbyBucket?.let { append(" bucket=").append(it) }
        append(" exactAllowed=").append(if (canScheduleExact) "y" else "n")
    }

    private fun Boolean?.yn() = when (this) { true -> "y"; false -> "n"; null -> "?" }
}
