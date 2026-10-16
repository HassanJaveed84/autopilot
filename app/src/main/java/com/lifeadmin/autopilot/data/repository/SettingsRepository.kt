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
            aiEnabled = false,
            lastReconcileAt = this[LAST_RECONCILE]?.let(Instant::ofEpochMilli),
            lastReconcileSummary = this[LAST_RECONCILE_SUMMARY],
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
        s.lastReconcileAt?.let { this[LAST_RECONCILE] = it.toEpochMilli() } ?: remove(LAST_RECONCILE)
        s.lastReconcileSummary?.let { this[LAST_RECONCILE_SUMMARY] = it } ?: remove(LAST_RECONCILE_SUMMARY)
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
