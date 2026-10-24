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
    /** Web research (M3): version of the one-time explainer the user accepted; 0 = not yet. */
    val researchExplainerVersion: Int = 0,
    /** Local-day research request counter, separate from planning ([com.lifeadmin.autopilot.ai.research.ResearchConfig.MAX_REQUESTS_PER_DAY]). */
    val researchUsageDay: String = "",
    val researchUsageCount: Int = 0,
    /** M3A.2: last chosen soft spending limit per research, in cents; 0 = no limit (the default). */
    val researchSpendLimitCents: Int = 0,
    /** M3A.2: optional monthly AI budget in cents for the usage dashboard; 0 = none. Soft: a note at 80 %, a confirmation at 100 %. */
    val aiMonthlyBudgetCents: Int = 0,
    /** M3A.2: model overrides per depth mode; blank = the mode's preset. */
    val researchQuickModel: String = "",
    val researchThoroughModel: String = "",
    val researchDeepModel: String = "",
    /** M4 (R48): model override for the new Standard depth; blank = the preset's model. Existing overrides above keep their meaning. */
    val researchStandardModel: String = "",
    /** M4: saved default depth (a [com.lifeadmin.autopilot.ai.investigation.ResearchDepth] name); blank = the recommended depth for each objective. */
    val researchDefaultDepth: String = "",
    /** M4: saved default search budget; 0 = follows the depth's suggestion (OD-3). */
    val researchDefaultSearches: Int = 0,
    /** M4: saved default reasoning effort (low/medium/high); blank = the provider's default. Applied only where the model supports it (RES-135). */
    val researchEffort: String = "",
    val lastReconcileAt: Instant? = null,
    val lastReconcileSummary: String? = null,
    /** Light/dark preference for the UI; SYSTEM follows the phone. Presentation only. */
    val appearance: Appearance = Appearance.SYSTEM,
) {
    companion object {
        val MORNING: LocalTime = LocalTime.of(9, 0)
    }
}
