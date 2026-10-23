package com.lifeadmin.autopilot.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lifeadmin.autopilot.domain.model.Appearance
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.QuietHours
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.SnoozePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalTime

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings = settings.first()
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** Typed settings persisted in Preferences DataStore. */
class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {

    override val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs.write(next)
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            quietHours = QuietHours(
                enabled = this[QUIET_ENABLED] ?: d.quietHours.enabled,
                start = this[QUIET_START]?.let(::parseTime) ?: d.quietHours.start,
                end = this[QUIET_END]?.let(::parseTime) ?: d.quietHours.end,
            ),
            quietHoursBehavior = enumOr(this[QUIET_BEHAVIOR], d.quietHoursBehavior),
            defaultSnooze = enumOr(this[DEFAULT_SNOOZE], d.defaultSnooze),
            defaultPreset = enumOr(this[DEFAULT_PRESET], d.defaultPreset),
            allowReminderNotifications = this[ALLOW_NOTIFICATIONS] ?: d.allowReminderNotifications,
            lockScreenPrivacy = this[LOCK_PRIVACY] ?: d.lockScreenPrivacy,
            precisionMode = this[PRECISION] ?: d.precisionMode,
            graceWindowMinutes = this[GRACE] ?: d.graceWindowMinutes,
            maxNotificationsPerDay = this[MAX_PER_DAY] ?: d.maxNotificationsPerDay,
            onboardingCompleted = this[ONBOARDED] ?: d.onboardingCompleted,
            notificationPermissionRequested = this[PERMISSION_ASKED] ?: d.notificationPermissionRequested,
            aiEnabled = this[AI_ENABLED] ?: d.aiEnabled,
            aiConsentAt = this[AI_CONSENT_AT]?.let(Instant::ofEpochMilli),
            aiModel = this[AI_MODEL] ?: d.aiModel,
            aiUsageDay = this[AI_USAGE_DAY] ?: d.aiUsageDay,
            aiUsageCount = this[AI_USAGE_COUNT] ?: d.aiUsageCount,
            researchExplainerVersion = this[RESEARCH_EXPLAINER] ?: d.researchExplainerVersion,
            researchUsageDay = this[RESEARCH_USAGE_DAY] ?: d.researchUsageDay,
            researchUsageCount = this[RESEARCH_USAGE_COUNT] ?: d.researchUsageCount,
            researchSpendLimitCents = this[RESEARCH_SPEND_LIMIT] ?: d.researchSpendLimitCents,
            aiMonthlyBudgetCents = this[AI_MONTHLY_BUDGET] ?: d.aiMonthlyBudgetCents,
            researchQuickModel = this[RESEARCH_MODEL_QUICK] ?: d.researchQuickModel,
            researchThoroughModel = this[RESEARCH_MODEL_THOROUGH] ?: d.researchThoroughModel,
            researchDeepModel = this[RESEARCH_MODEL_DEEP] ?: d.researchDeepModel,
            researchStandardModel = this[RESEARCH_MODEL_STANDARD] ?: d.researchStandardModel,
            researchDefaultDepth = this[RESEARCH_DEFAULT_DEPTH] ?: d.researchDefaultDepth,
            researchDefaultSearches = this[RESEARCH_DEFAULT_SEARCHES] ?: d.researchDefaultSearches,
            researchEffort = this[RESEARCH_EFFORT] ?: d.researchEffort,
            lastReconcileAt = this[LAST_RECONCILE]?.let(Instant::ofEpochMilli),
            lastReconcileSummary = this[LAST_RECONCILE_SUMMARY],
            appearance = enumOr(this[APPEARANCE], d.appearance),
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
        this[QUIET_ENABLED] = s.quietHours.enabled
        this[QUIET_START] = s.quietHours.start.toString()
        this[QUIET_END] = s.quietHours.end.toString()
        this[QUIET_BEHAVIOR] = s.quietHoursBehavior.name
        this[DEFAULT_SNOOZE] = s.defaultSnooze.name
        this[DEFAULT_PRESET] = s.defaultPreset.name
        this[ALLOW_NOTIFICATIONS] = s.allowReminderNotifications
        this[LOCK_PRIVACY] = s.lockScreenPrivacy
        this[PRECISION] = s.precisionMode
        this[GRACE] = s.graceWindowMinutes
        this[MAX_PER_DAY] = s.maxNotificationsPerDay
        this[ONBOARDED] = s.onboardingCompleted
        this[PERMISSION_ASKED] = s.notificationPermissionRequested
        this[SCHEMA] = SETTINGS_SCHEMA_VERSION
        this[AI_ENABLED] = s.aiEnabled
        s.aiConsentAt?.let { this[AI_CONSENT_AT] = it.toEpochMilli() } ?: remove(AI_CONSENT_AT)
        this[AI_MODEL] = s.aiModel
        this[AI_USAGE_DAY] = s.aiUsageDay
        this[AI_USAGE_COUNT] = s.aiUsageCount
        this[RESEARCH_EXPLAINER] = s.researchExplainerVersion
        this[RESEARCH_USAGE_DAY] = s.researchUsageDay
        this[RESEARCH_USAGE_COUNT] = s.researchUsageCount
        this[RESEARCH_SPEND_LIMIT] = s.researchSpendLimitCents
        this[AI_MONTHLY_BUDGET] = s.aiMonthlyBudgetCents
        this[RESEARCH_MODEL_QUICK] = s.researchQuickModel
        this[RESEARCH_MODEL_THOROUGH] = s.researchThoroughModel
        this[RESEARCH_MODEL_DEEP] = s.researchDeepModel
        this[RESEARCH_MODEL_STANDARD] = s.researchStandardModel
        this[RESEARCH_DEFAULT_DEPTH] = s.researchDefaultDepth
        this[RESEARCH_DEFAULT_SEARCHES] = s.researchDefaultSearches
        this[RESEARCH_EFFORT] = s.researchEffort
        s.lastReconcileAt?.let { this[LAST_RECONCILE] = it.toEpochMilli() } ?: remove(LAST_RECONCILE)
        s.lastReconcileSummary?.let { this[LAST_RECONCILE_SUMMARY] = it } ?: remove(LAST_RECONCILE_SUMMARY)
        this[APPEARANCE] = s.appearance.name
    }

    companion object {
        const val SETTINGS_SCHEMA_VERSION = 1
        private val QUIET_ENABLED = booleanPreferencesKey("quiet_enabled")
        private val QUIET_START = stringPreferencesKey("quiet_start")
        private val QUIET_END = stringPreferencesKey("quiet_end")
        private val QUIET_BEHAVIOR = stringPreferencesKey("quiet_behavior")
        private val DEFAULT_SNOOZE = stringPreferencesKey("default_snooze")
        private val DEFAULT_PRESET = stringPreferencesKey("default_preset")
        private val ALLOW_NOTIFICATIONS = booleanPreferencesKey("allow_notifications")
        private val LOCK_PRIVACY = booleanPreferencesKey("lock_screen_privacy")
        private val PRECISION = booleanPreferencesKey("precision_mode")
        private val GRACE = intPreferencesKey("grace_minutes")
        private val MAX_PER_DAY = intPreferencesKey("max_per_day")
        private val ONBOARDED = booleanPreferencesKey("onboarded")
        private val PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
        private val SCHEMA = intPreferencesKey("settings_schema_version")
        private val LAST_RECONCILE = longPreferencesKey("last_reconcile_at")
        private val LAST_RECONCILE_SUMMARY = stringPreferencesKey("last_reconcile_summary")
        // AI settings never include the API key; that lives encrypted in ApiKeyStore.
        private val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        private val AI_CONSENT_AT = longPreferencesKey("ai_consent_at")
        private val AI_MODEL = stringPreferencesKey("ai_model")
        private val AI_USAGE_DAY = stringPreferencesKey("ai_usage_day")
        private val RESEARCH_EXPLAINER = intPreferencesKey("research_explainer_version")
        private val RESEARCH_USAGE_DAY = stringPreferencesKey("research_usage_day")
        private val RESEARCH_USAGE_COUNT = intPreferencesKey("research_usage_count")
        private val AI_USAGE_COUNT = intPreferencesKey("ai_usage_count")
        private val RESEARCH_SPEND_LIMIT = intPreferencesKey("research_spend_limit_cents")
        private val AI_MONTHLY_BUDGET = intPreferencesKey("ai_monthly_budget_cents")
        private val RESEARCH_MODEL_QUICK = stringPreferencesKey("research_model_quick")
        private val RESEARCH_MODEL_THOROUGH = stringPreferencesKey("research_model_thorough")
        private val RESEARCH_MODEL_DEEP = stringPreferencesKey("research_model_deep")
        private val RESEARCH_MODEL_STANDARD = stringPreferencesKey("research_model_standard")
        private val RESEARCH_DEFAULT_DEPTH = stringPreferencesKey("research_default_depth")
        private val RESEARCH_DEFAULT_SEARCHES = intPreferencesKey("research_default_searches")
        private val RESEARCH_EFFORT = stringPreferencesKey("research_effort")
        private val APPEARANCE = stringPreferencesKey("appearance")

        private fun parseTime(raw: String): LocalTime? = runCatching { LocalTime.parse(raw) }.getOrNull()
        private inline fun <reified E : Enum<E>> enumOr(raw: String?, default: E): E =
            raw?.let { r -> enumValues<E>().firstOrNull { it.name == r } } ?: default
    }
}

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** In-memory implementation for tests and previews. */
class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun current(): AppSettings = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
}
