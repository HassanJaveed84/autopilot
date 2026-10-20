package com.lifeadmin.autopilot.di

import android.content.Context
import android.util.Log
import com.lifeadmin.autopilot.BuildConfig
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.AndroidNetworkStatus
import com.lifeadmin.autopilot.ai.NetworkStatus
import com.lifeadmin.autopilot.ai.TaskPlanningProvider
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchProvider
import com.lifeadmin.autopilot.ai.anthropic.AnthropicTaskPlanningProvider
import com.lifeadmin.autopilot.ai.research.ResearchProvider
import com.lifeadmin.autopilot.ai.research.ResearchService
import com.lifeadmin.autopilot.ai.security.ApiKeyStore
import com.lifeadmin.autopilot.ai.security.EncryptedApiKeyStore
import com.lifeadmin.autopilot.domain.usecase.HintDismissals
import com.lifeadmin.autopilot.domain.usecase.SharedPrefsHintDismissals
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.BackupRepository
import com.lifeadmin.autopilot.data.repository.DataStoreSettingsRepository
import com.lifeadmin.autopilot.data.repository.ResearchRepository
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.settingsDataStore
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.notifications.AndroidNotificationPublisher
import com.lifeadmin.autopilot.notifications.NotificationPublisher
import com.lifeadmin.autopilot.reminders.AlarmDriver
import com.lifeadmin.autopilot.reminders.AndroidAlarmDriver
import com.lifeadmin.autopilot.reminders.AndroidEnvironmentProbe
import com.lifeadmin.autopilot.reminders.Diagnostics
import com.lifeadmin.autopilot.reminders.EnvironmentProbe
import com.lifeadmin.autopilot.reminders.ReminderSelfTest
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
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
    val environmentProbe: EnvironmentProbe = AndroidEnvironmentProbe(context, alarmDriver),
    val apiKeyStore: ApiKeyStore = EncryptedApiKeyStore(context),
    val planningProvider: TaskPlanningProvider = AnthropicTaskPlanningProvider(),
    val researchProvider: ResearchProvider = AnthropicResearchProvider(),
    val networkStatus: NetworkStatus = AndroidNetworkStatus(context),
    val hintDismissals: HintDismissals = SharedPrefsHintDismissals(context),
) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val diagnosticsLog: RoomDiagnosticsLog = RoomDiagnosticsLog(database, clock, ids)

    val reminderEngine: ReminderEngine =
        ReminderEngine(database, settingsRepository, alarmDriver, notificationPublisher, clock, zoneProvider, ids, diagnosticsLog, environmentProbe)

    val selfTest: ReminderSelfTest = ReminderSelfTest(
        diagnosticsLog, alarmDriver, notificationPublisher, environmentProbe, settingsRepository, clock, zoneProvider, ids,
        history = { diagnosticsLog.forOccurrence(it) },
    )

    val taskRepository: TaskRepository = TaskRepository(database, clock, ids, zoneProvider::current, reminderEngine, diagnosticsLog)

    val taskActions: TaskActions = TaskActions(database, taskRepository, settingsRepository, clock, zoneProvider)

    /** Optional AI planning (M2). Never writes tasks; approval goes through [taskRepository]. */
    val aiPlanning: AiPlanningService = AiPlanningService(
        settingsRepository, apiKeyStore, planningProvider, taskRepository, clock, zoneProvider, ids, networkStatus,
    )

    /** Saved research briefs (M3); readable offline. */
    val researchRepository: ResearchRepository = ResearchRepository(database, clock, ids)

    /** Consented web research (M3). One run at a time, in app scope so it survives navigation. */
    val research: ResearchService = ResearchService(
        settingsRepository, apiKeyStore, aiPlanning, researchProvider, taskRepository, researchRepository,
        clock, zoneProvider, ids, appScope, networkStatus,
    )

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
