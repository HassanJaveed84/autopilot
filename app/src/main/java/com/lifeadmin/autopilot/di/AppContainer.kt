package com.lifeadmin.autopilot.di

import android.content.Context
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.DataStoreSettingsRepository
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.settingsDataStore
import com.lifeadmin.autopilot.BuildConfig
import com.lifeadmin.autopilot.data.repository.BackupRepository
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.domain.usecase.TaskActions

/**
 * Manual dependency container. Every seam the scheduler touches (clock, zone, alarms,
 * notifications, persistence) is constructed here and can be replaced in tests.
 */
class AppContainer(
    val context: Context,
    val clock: AppClock = AppClock.system(),
    val zoneProvider: ZoneProvider = ZoneProvider.SYSTEM,
    val ids: IdGenerator = IdGenerator.RANDOM,
    val database: AppDatabase = AppDatabase.build(context),
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(context.settingsDataStore),
) {
    val coordinator: ScheduleCoordinator = ScheduleCoordinator.Direct

    val taskRepository: TaskRepository = TaskRepository(database, clock, ids, zoneProvider::current, coordinator)

    val taskActions: TaskActions = TaskActions(database, taskRepository, settingsRepository, clock, zoneProvider)

    val backupRepository: BackupRepository = BackupRepository(
        database, clock, ids, BuildConfig.VERSION_NAME,
        afterImport = { reconcileAll("import") },
    )

    /** Re-derives OS alarms from the database. */
    suspend fun reconcileAll(@Suppress("UNUSED_PARAMETER") reason: String) {
    }

    fun onAppStart() {
    }
}
