package com.lifeadmin.autopilot.di

import android.content.Context
import android.util.Log
import com.lifeadmin.autopilot.BuildConfig
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.BackupRepository
import com.lifeadmin.autopilot.data.repository.DataStoreSettingsRepository
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.settingsDataStore
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.notifications.AndroidNotificationPublisher
import com.lifeadmin.autopilot.notifications.NotificationPublisher
import com.lifeadmin.autopilot.reminders.AlarmDriver
import com.lifeadmin.autopilot.reminders.AndroidAlarmDriver
import com.lifeadmin.autopilot.reminders.Diagnostics
import com.lifeadmin.autopilot.reminders.ReconcileWorker
import com.lifeadmin.autopilot.reminders.ReminderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    val alarmDriver: AlarmDriver = AndroidAlarmDriver(context),
    val notificationPublisher: NotificationPublisher = AndroidNotificationPublisher(context),
) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val reminderEngine: ReminderEngine =
        ReminderEngine(database, settingsRepository, alarmDriver, notificationPublisher, clock, zoneProvider, ids)

    val taskRepository: TaskRepository = TaskRepository(database, clock, ids, zoneProvider::current, reminderEngine)

    val taskActions: TaskActions = TaskActions(database, taskRepository, settingsRepository, clock, zoneProvider)

    val backupRepository: BackupRepository = BackupRepository(
        database, clock, ids, BuildConfig.VERSION_NAME,
        afterImport = { reconcileAll("import") },
    )

    val diagnostics: Diagnostics = Diagnostics(
        database, settingsRepository, alarmDriver, notificationPublisher, clock, zoneProvider,
        "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})", android.os.Build.VERSION.SDK_INT,
    )

    /** Re-derives OS alarms from the database. */
    suspend fun reconcileAll(reason: String) = reminderEngine.reconcileAll(reason)

    private var lastForegroundReconcile = 0L

    /** Called when the UI becomes visible: cheap, debounced recovery (e.g. after force-stop). */
    fun onForeground() {
        val now = System.currentTimeMillis()
        if (now - lastForegroundReconcile < 60_000) return
        lastForegroundReconcile = now
        appScope.launch { runCatching { reconcileAll("app_resume") } }
    }

    fun onAppStart() {
        runCatching { notificationPublisher.ensureChannels() }
        runCatching { ReconcileWorker.schedule(context) }.onFailure { Log.w("LifeAdmin", "WorkManager unavailable: ${it.javaClass.simpleName}") }
        lastForegroundReconcile = System.currentTimeMillis()
        appScope.launch { runCatching { reconcileAll("app_start") } }
    }
}
