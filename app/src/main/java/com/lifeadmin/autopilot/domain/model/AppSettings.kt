package com.lifeadmin.autopilot.domain.model

import java.time.Instant
import java.time.LocalTime

data class QuietHours(
    val enabled: Boolean = true,
    val start: LocalTime = LocalTime.of(21, 0),
    val end: LocalTime = LocalTime.of(8, 0),
) {
    /** Start == end means an empty window. */
    val isEffective: Boolean get() = enabled && start != end
}

data class AppSettings(
    val quietHours: QuietHours = QuietHours(),
    val quietHoursBehavior: QuietHoursBehavior = QuietHoursBehavior.DEFER_AND_COALESCE,
    val defaultSnooze: SnoozePreset = SnoozePreset.TOMORROW_MORNING,
    /**
     * Follow-up for a capture with no timing in its words. Gentle for new installs; an existing
     * install keeps what it has stored (every settings write persists this key, and the engine
     * writes settings on its first reconcile, so any install that has run before has it stored).
     */
    val defaultPreset: ReminderPreset = ReminderPreset.GENTLE,
    val allowReminderNotifications: Boolean = true,
    val lockScreenPrivacy: Boolean = false,
    /** Try exact alarms when the user granted special access. Off by default (battery + policy). */
    val precisionMode: Boolean = false,
    /** Late reminders within this window are still delivered individually after reboot/offline. */
    val graceWindowMinutes: Int = 240,
    /** Hard cap on reminder notifications per rolling 24h, across all tasks. */
    val maxNotificationsPerDay: Int = 30,
    val onboardingCompleted: Boolean = false,
    val notificationPermissionRequested: Boolean = false,
    val lastReconcileAt: Instant? = null,
    val lastReconcileSummary: String? = null,
    /** Light/dark preference for the UI; SYSTEM follows the phone. Presentation only. */
    val appearance: Appearance = Appearance.SYSTEM,
    /** When "before bed", "at bedtime" and "every night" nudge. Editable in Settings. */
    val bedtime: LocalTime = DEFAULT_BEDTIME,
) {
    companion object {
        val MORNING: LocalTime = LocalTime.of(9, 0)
        val DEFAULT_BEDTIME: LocalTime = LocalTime.of(21, 30)
    }
}
