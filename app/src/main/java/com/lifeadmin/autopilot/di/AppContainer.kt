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
    /** M3A.2 structured research calls; defaults to the Anthropic adapter sharing [researchProvider]'s client. */
    val researchCalls: com.lifeadmin.autopilot.ai.research2.ResearchCallProvider? = null,
    val networkStatus: NetworkStatus = AndroidNetworkStatus(context),
    val hintDismissals: HintDismissals = SharedPrefsHintDismissals(context),
    /** ChatGPT share import (proof of concept): fetches only chatgpt.com/share links, on request. */
    val shareImporter: com.lifeadmin.autopilot.share.chatgpt.ShareImporter = com.lifeadmin.autopilot.share.chatgpt.ShareImporter(),
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

    /** Local AI usage ledger (M3A.2): one row per billable call, planning and research alike. */
    val usageLedger: com.lifeadmin.autopilot.ai.usage.UsageLedger = com.lifeadmin.autopilot.data.repository.RoomUsageLedger(database)

    /** Optional AI planning (M2). Never writes tasks; approval goes through [taskRepository]. */
    val aiPlanning: AiPlanningService = AiPlanningService(
        settingsRepository, apiKeyStore, planningProvider, taskRepository, clock, zoneProvider, ids, ledger = usageLedger, network = networkStatus,
    )

    /** Saved research briefs (M3); readable offline. */
    val researchRepository: ResearchRepository = ResearchRepository(database, clock, ids)

    /** Saved M3A.2 research sessions, read through their v7 investigations; read-only since M4. */
    val sessionRepository: com.lifeadmin.autopilot.data.repository.ResearchSessionRepository = com.lifeadmin.autopilot.data.repository.ResearchSessionRepository(database)

    /** Durable drafts (production kickoff M1): never tasks, never reminders. */
    val draftRepository: com.lifeadmin.autopilot.data.repository.DraftRepository = com.lifeadmin.autopilot.data.repository.DraftRepository(database, clock, ids)

    /** Proposal sets and provenance records (M1). Saving a set never touches a task. */
    val proposalSets: com.lifeadmin.autopilot.data.repository.ProposalSetRepository = com.lifeadmin.autopilot.data.repository.ProposalSetRepository(database)

    /** The one write path from a proposal to a task: transactional, conflict-aware, undoable (M1). */
    val proposalApplier: com.lifeadmin.autopilot.data.repository.ProposalApplier =
        com.lifeadmin.autopilot.data.repository.ProposalApplier(database, taskRepository, proposalSets, clock, ids)

    /** ChatGPT round trip: an incoming `ap=v1` link becomes a pending proposal set for review, never a direct write. */
    val handoffImporter: com.lifeadmin.autopilot.share.applink.HandoffImporter =
        com.lifeadmin.autopilot.share.applink.HandoffImporter(taskRepository, proposalSets, proposalApplier, clock)

    /** Legacy M3A.2 session proposals as canonical proposal sets (M2): still reviewable from a saved session. */
    val researchProposalSets: com.lifeadmin.autopilot.ai.research2.ResearchProposalSets =
        com.lifeadmin.autopilot.ai.research2.ResearchProposalSets(sessionRepository, proposalSets, proposalApplier, clock)

    /** Legacy read path: research saved by the retired M4 engine (and migrated M3A.2 sessions). */
    val investigations: com.lifeadmin.autopilot.data.repository.InvestigationRepository = com.lifeadmin.autopilot.data.repository.InvestigationRepository(database)

    /** Research Online threads (AI-first), in the same Room tables; readable offline. */
    val researchThreads: com.lifeadmin.autopilot.data.repository.AfThreadRepository = com.lifeadmin.autopilot.data.repository.AfThreadRepository(database)

    /** Research Online: clarification, authorization and one research turn at a time, in app scope so it survives navigation. */
    val researchOnline: com.lifeadmin.autopilot.ai.aifirst.ResearchOnlineService = com.lifeadmin.autopilot.ai.aifirst.ResearchOnlineService(
        settingsRepository, apiKeyStore, aiPlanning,
        researchCalls ?: com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchCalls(researchProvider as? AnthropicResearchProvider ?: AnthropicResearchProvider()),
        taskRepository, researchThreads, investigations, usageLedger, proposalSets, proposalApplier, clock, zoneProvider, ids, appScope, networkStatus,
    )

    /** Proposals of legacy M4 turns, still reviewed and applied through the one Proposal Review. */
    val investigationProposals: com.lifeadmin.autopilot.ai.investigation.InvestigationProposalSets =
        com.lifeadmin.autopilot.ai.investigation.InvestigationProposalSets(investigations, proposalSets, proposalApplier, clock)

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
        // Calls left "in flight" by a previous process are interrupted (usage unknown, nothing re-sent), and a turn still marked running belongs to a process that died: it becomes Interrupted with its last checkpoint.
        appScope.launch { runCatching { researchOnline.recoverInterrupted() } }
    }
}
