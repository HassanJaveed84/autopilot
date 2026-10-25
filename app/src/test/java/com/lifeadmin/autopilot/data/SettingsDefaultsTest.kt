package com.lifeadmin.autopilot.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lifeadmin.autopilot.data.repository.DataStoreSettingsRepository
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalTime

/** The lighter default applies to new installs only; bedtime is a stored, editable preference. */
class SettingsDefaultsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun tearDown() = scope.cancel()

    private fun store(name: String) = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("$name.preferences_pb") }

    @Test fun aNewInstallUsesGentleAndSuggestsBedtimeAt2130() = runBlocking {
        val s = DataStoreSettingsRepository(store("fresh")).current()
        assertEquals(ReminderPreset.GENTLE, s.defaultPreset)
        assertEquals(LocalTime.of(21, 30), s.bedtime)
    }

    @Test fun anExistingInstallKeepsItsStoredPreset() = runBlocking {
        // What any earlier version wrote: every settings write persists every key, including the preset.
        val ds = store("existing")
        ds.edit {
            it[stringPreferencesKey("default_preset")] = "PERSISTENT"
            it[intPreferencesKey("settings_schema_version")] = 1
            it[stringPreferencesKey("quiet_start")] = "22:00"
        }
        val repo = DataStoreSettingsRepository(ds)
        val s = repo.current()
        assertEquals(ReminderPreset.PERSISTENT, s.defaultPreset)
        assertEquals("no bedtime stored yet → the suggestion", LocalTime.of(21, 30), s.bedtime)
        // Changing an unrelated setting (e.g. bedtime) never resets the preset.
        repo.update { it.copy(bedtime = LocalTime.of(22, 15)) }
        val after = DataStoreSettingsRepository(ds).current()
        assertEquals(ReminderPreset.PERSISTENT, after.defaultPreset)
        assertEquals(LocalTime.of(22, 15), after.bedtime)
        assertEquals(LocalTime.of(22, 0), after.quietHours.start)
    }
}
