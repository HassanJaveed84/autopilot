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
    val defaultPreset: ReminderPreset = ReminderPreset.PERSISTENT,
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
    /** Optional AI planning (M2). Off by default; the app is fully usable without it. */
    val aiEnabled: Boolean = false,
    /** When the user accepted the "text is sent to Anthropic" disclosure; null = not yet. */
    val aiConsentAt: Instant? = null,
    /** Model id for AI planning; blank = [com.lifeadmin.autopilot.ai.AiConfig.DEFAULT_MODEL]. */
    val aiModel: String = "",
    /** Local-day request counter used to cap accidental spend ([com.lifeadmin.autopilot.ai.AiConfig.MAX_REQUESTS_PER_DAY]). */
    val aiUsageDay: String = "",
    val aiUsageCount: Int = 0,
    val lastReconcileAt: Instant? = null,
    val lastReconcileSummary: String? = null,
) {
    companion object {
        val MORNING: LocalTime = LocalTime.of(9, 0)
    }
}
