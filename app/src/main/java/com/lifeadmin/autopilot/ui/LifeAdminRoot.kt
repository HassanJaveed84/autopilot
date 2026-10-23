package com.lifeadmin.autopilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.ui.ai.AiSettingsScreen
import com.lifeadmin.autopilot.ui.ai.AiSettingsViewModel
import com.lifeadmin.autopilot.ui.capture.CaptureSheet
import com.lifeadmin.autopilot.ui.capture.CaptureViewModel
import com.lifeadmin.autopilot.ui.components.AutopilotSnackbar
import com.lifeadmin.autopilot.ui.components.CaptureBar
import com.lifeadmin.autopilot.ui.components.FlightNavBar
import com.lifeadmin.autopilot.ui.components.NavItem
import com.lifeadmin.autopilot.ui.components.PaperBackground
import com.lifeadmin.autopilot.ui.components.showToast
import com.lifeadmin.autopilot.ui.detail.ResearchNav
import com.lifeadmin.autopilot.ui.detail.TaskDetailScreen
import com.lifeadmin.autopilot.ui.research.ResearchBriefScreen
import com.lifeadmin.autopilot.ui.research.ResearchBriefViewModel
import com.lifeadmin.autopilot.ui.review.ProposalReviewScreen
import com.lifeadmin.autopilot.ui.review.ProposalReviewViewModel
import com.lifeadmin.autopilot.ui.research2.ResearchSessionScreen
import com.lifeadmin.autopilot.ui.research2.Session2ViewModel
import com.lifeadmin.autopilot.ui.research2.UsageScreen
import com.lifeadmin.autopilot.ui.research2.UsageViewModel
import com.lifeadmin.autopilot.ui.detail.TaskDetailViewModel
import com.lifeadmin.autopilot.ui.health.ReminderHealthScreen
import com.lifeadmin.autopilot.ui.health.ReminderHealthViewModel
import com.lifeadmin.autopilot.ui.health.rememberHealthIssue
import com.lifeadmin.autopilot.ui.navigation.Routes
import com.lifeadmin.autopilot.ui.navigation.TopLevelDestination
import com.lifeadmin.autopilot.ui.plan.PlanScreen
import com.lifeadmin.autopilot.ui.plan.PlanViewModel
import com.lifeadmin.autopilot.ui.settings.LicensesScreen
import com.lifeadmin.autopilot.ui.settings.SettingsScreen
import com.lifeadmin.autopilot.ui.settings.SettingsViewModel
import com.lifeadmin.autopilot.ui.tasks.InboxScreen
import com.lifeadmin.autopilot.ui.tasks.TaskListViewModel
import com.lifeadmin.autopilot.ui.tasks.TasksScreen
import com.lifeadmin.autopilot.ui.today.TodayScreen
import com.lifeadmin.autopilot.ui.today.TodayViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App shell: three doing tabs (Today / Inbox / Tasks) with the persistent capture bar above them,
 * Settings as a header button, and the pushed destinations (task detail, plan, health, AI
 * settings). [openTaskRequests] carries validated task ids from notification taps.
 */
@Composable
fun LifeAdminRoot(
    container: AppContainer,
    openTaskRequests: StateFlow<String?> = MutableStateFlow(null),
    onOpenTaskHandled: () -> Unit = {},
    onReminderEnabled: () -> Unit = {},
    /** Health UI is opt-in so UI tests can render the shell without reading device state. */
    showHealth: Boolean = false,
    openHealthRequests: StateFlow<Boolean> = MutableStateFlow(false),
    onOpenHealthHandled: () -> Unit = {},
    requestNotificationPermission: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var capturing by rememberSaveable { mutableStateOf(false) }
    // Text handed to the capture sheet by an example prompt on the empty Today screen.
    var capturePrefill by rememberSaveable { mutableStateOf<String?>(null) }
    // The card of a task just saved from Capture wears a green ring for a moment (Today only).
    var justSaved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(justSaved) { if (justSaved != null) { kotlinx.coroutines.delay(2_800); justSaved = null } }
    val topLevel = TopLevelDestination.entries.firstOrNull { d -> currentDestination?.hierarchy?.any { it.route == d.route } == true }
    val healthIssue = if (showHealth) rememberHealthIssue(container) else false
    val inboxCount by remember(container) {
        container.taskRepository.observeSummaries()
            .map { list -> list.count { it.task.status == com.lifeadmin.autopilot.domain.model.TaskStatus.INBOX && it.task.archivedAt == null } }
            // The badge must never take the shell down: the app itself never closes its database, but
            // tests close theirs while the composition is still alive.
            .catch { emit(0) }
    }.collectAsStateWithLifecycle(0)

    val pendingTask by openTaskRequests.collectAsStateWithLifecycle()
    // Wait until NavHost has installed its graph (cold start from a notification tap).
    val graphReady = backStack != null
    LaunchedEffect(pendingTask, graphReady) {
        val id = pendingTask ?: return@LaunchedEffect
        if (!graphReady) return@LaunchedEffect
        navController.navigate(Routes.taskDetail(id)) { launchSingleTop = true }
        onOpenTaskHandled()
    }

    val pendingHealth by openHealthRequests.collectAsStateWithLifecycle()
    LaunchedEffect(pendingHealth, graphReady) {
        if (!pendingHealth || !graphReady) return@LaunchedEffect
        navController.navigate(Routes.HEALTH) { launchSingleTop = true }
        onOpenHealthHandled()
    }

    fun openTask(id: String) = navController.navigate(Routes.taskDetail(id)) { launchSingleTop = true }
    fun goTo(route: String) = navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun openSettings() = navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
    fun openHealth() = navController.navigate(Routes.HEALTH) { launchSingleTop = true }
    fun openAiSettings() = navController.navigate(Routes.AI_SETTINGS) { launchSingleTop = true }

    PaperBackground {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) { data -> AutopilotSnackbar(data) } },
            bottomBar = {
                if (topLevel != null) {
                    Column(Modifier.navigationBarsPadding()) {
                        CaptureBar(onClick = { capturing = true })
                        FlightNavBar(
                            items = TopLevelDestination.entries.map { NavItem(it.route, it.label, it.icon, badge = if (it == TopLevelDestination.INBOX) inboxCount else 0) },
                            selected = topLevel.route,
                            onSelect = ::goTo,
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(navController, startDestination = Routes.TODAY, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                composable(Routes.TODAY) {
                    val vm: TodayViewModel = viewModel(factory = factoryOf {
                        TodayViewModel(container.taskRepository, container.settingsRepository, container.taskActions, container.clock, container.zoneProvider)
                    })
                    TodayScreen(
                        vm, snackbar, onOpenTask = ::openTask, onOpenInbox = { goTo(Routes.INBOX) },
                        onOpenSettings = ::openSettings, healthIssue = healthIssue, showHealth = showHealth, container = container, onOpenHealth = ::openHealth,
                        onOpenResearch = { navController.navigate(Routes.investigations()) { launchSingleTop = true } },
                        onCaptureWithText = { text -> capturePrefill = text; capturing = true },
                        highlightTaskId = justSaved,
                    )
                }
                composable(Routes.INBOX) {
                    val vm: TaskListViewModel = viewModel(key = "inbox", factory = factoryOf {
                        TaskListViewModel(container.taskRepository, container.clock, container.zoneProvider, inboxOnly = true, settings = container.settingsRepository)
                    })
                    InboxScreen(vm, snackbar, onOpenTask = ::openTask, onOpenSettings = ::openSettings, healthIssue = healthIssue, onPlanTask = { id -> navController.navigate(Routes.planTask(id)) { launchSingleTop = true } })
                }
                composable(Routes.TASKS) {
                    val vm: TaskListViewModel = viewModel(key = "tasks", factory = factoryOf {
                        TaskListViewModel(container.taskRepository, container.clock, container.zoneProvider, inboxOnly = false)
                    })
                    TasksScreen(vm, snackbar, onOpenTask = ::openTask, onOpenSettings = ::openSettings, healthIssue = healthIssue)
                }
                composable(Routes.SETTINGS) {
                    val vm: SettingsViewModel = viewModel(factory = factoryOf {
                        SettingsViewModel(container.settingsRepository, container.backupRepository) { container.reconcileAll("settings") }
                    })
                    SettingsScreen(
                        vm, snackbar, onBack = { navController.popBackStack() },
                        showHealth = showHealth, container = container, onOpenHealth = ::openHealth,
                        onOpenAiSettings = ::openAiSettings, onOpenLicenses = { navController.navigate(Routes.LICENSES) { launchSingleTop = true } },
                    )
                }
                composable(Routes.LICENSES) { LicensesScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.AI_SETTINGS) {
                    val vm: AiSettingsViewModel = viewModel(factory = factoryOf {
                        AiSettingsViewModel(container.settingsRepository, container.apiKeyStore, container.aiPlanning, container.clock, container.investigationService, container.researchRepository, container.investigations, forgetResearchClient = { (container.researchProvider as? com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchProvider)?.reset() })
                    })
                    AiSettingsScreen(vm, onBack = { navController.popBackStack() }, onOpenUsage = { navController.navigate(Routes.aiUsage()) { launchSingleTop = true } })
                }
                composable(Routes.AI_USAGE, arguments = listOf(navArgument("session") { type = NavType.StringType; defaultValue = "" })) { entry ->
                    val focus = entry.arguments?.getString("session").orEmpty().ifBlank { null }
                    val vm: UsageViewModel = viewModel(key = "ai-usage-$focus", factory = factoryOf {
                        UsageViewModel(container.usageLedger, container.sessionRepository, container.settingsRepository, container.clock, container.zoneProvider, focus)
                    })
                    UsageScreen(vm, zone = container.zoneProvider.current(), onBack = { navController.popBackStack() })
                }
                composable(
                    Routes.PLAN,
                    arguments = listOf(
                        navArgument("text") { type = NavType.StringType; defaultValue = "" },
                        navArgument("auto") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) { entry ->
                    val text = entry.arguments?.getString("text").orEmpty()
                    val auto = entry.arguments?.getBoolean("auto") ?: false
                    PlanDestination(container, navController, targetTaskId = null, initialText = text, autoRequest = auto, onReminderEnabled = onReminderEnabled)
                }
                composable(Routes.PLAN_TASK, arguments = listOf(navArgument("taskId") { type = NavType.StringType })) { entry ->
                    val taskId = entry.arguments?.getString("taskId").orEmpty()
                    PlanDestination(container, navController, targetTaskId = taskId, initialText = "", autoRequest = false, onReminderEnabled = onReminderEnabled)
                }
                composable(Routes.HEALTH) {
                    val vm: ReminderHealthViewModel = viewModel(factory = factoryOf { ReminderHealthViewModel(container) })
                    ReminderHealthScreen(
                        vm, zone = container.zoneProvider.current(), now = container.clock::now,
                        onBack = { navController.popBackStack() }, requestPermission = requestNotificationPermission,
                    )
                }
                composable(Routes.TASK_DETAIL, arguments = listOf(navArgument("taskId") { type = NavType.StringType })) { entry ->
                    val taskId = entry.arguments?.getString("taskId").orEmpty()
                    val vm: TaskDetailViewModel = viewModel(key = "detail-$taskId", factory = factoryOf {
                        TaskDetailViewModel(
                            taskId, container.taskRepository, container.taskActions, container.settingsRepository, container.clock, container.zoneProvider, container.hintDismissals,
                            researchBriefs = container.researchRepository.observeForTask(taskId),
                            investigations = container.investigations.observeForTask(taskId), threadRun = container.investigationService.state,
                            proposalSets = container.proposalSets.observeForTarget(taskId), provenance = container.proposalSets.observeProvenance(taskId),
                        )
                    })
                    TaskDetailScreen(
                        vm, onBack = { navController.popBackStack() }, onReminderEnabled = onReminderEnabled,
                        onMoveForward = { navController.navigate(Routes.planTask(taskId)) { launchSingleTop = true } },
                        onOpenReview = { setId -> navController.navigate(Routes.review(taskId, setId)) { launchSingleTop = true } },
                        onOpenAiSettings = ::openAiSettings,
                        research = ResearchNav(
                            onResearch = { navController.navigate(Routes.investigationNew(taskId)) { launchSingleTop = true } },
                            onOpenBrief = { briefId -> navController.navigate(Routes.researchBrief(taskId, briefId)) { launchSingleTop = true } },
                            onOpenHistory = { navController.navigate(Routes.investigations(taskId)) { launchSingleTop = true } },
                            onOpenInvestigation = { id -> navController.navigate(Routes.investigation(id)) { launchSingleTop = true } },
                        ),
                    )
                }
                composable(Routes.INVESTIGATIONS, arguments = listOf(navArgument("task") { type = NavType.StringType; defaultValue = "" })) { entry ->
                    val task = entry.arguments?.getString("task").orEmpty().ifBlank { null }
                    val vm: com.lifeadmin.autopilot.ui.investigation.InvestigationHomeViewModel = viewModel(key = "investigations-$task", factory = factoryOf {
                        com.lifeadmin.autopilot.ui.investigation.InvestigationHomeViewModel(container.investigations, container.taskRepository, container.investigationService, container.clock, task)
                    })
                    com.lifeadmin.autopilot.ui.investigation.InvestigationHomeScreen(
                        vm, zone = container.zoneProvider.current(), title = if (task != null) "Research for this task" else "Research",
                        onBack = { navController.popBackStack() },
                        onOpen = { id -> navController.navigate(Routes.investigation(id)) { launchSingleTop = true } },
                        onNew = { navController.navigate(Routes.investigationNew(task)) { launchSingleTop = true } },
                    )
                }
                composable(
                    Routes.INVESTIGATION_NEW,
                    arguments = listOf(navArgument("task") { type = NavType.StringType; defaultValue = "" }, navArgument("q") { type = NavType.StringType; defaultValue = "" }),
                ) { entry ->
                    val task = entry.arguments?.getString("task").orEmpty().ifBlank { null }
                    val q = entry.arguments?.getString("q").orEmpty()
                    InvestigationThreadDestination(container, navController, investigationId = null, taskId = task, question = q, key = "investigation-new-$task")
                }
                composable(Routes.INVESTIGATION, arguments = listOf(navArgument("investigationId") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("investigationId").orEmpty()
                    InvestigationThreadDestination(container, navController, investigationId = id, taskId = null, question = "", key = "investigation-$id")
                }
                composable(Routes.RESEARCH2_SESSION, arguments = listOf(navArgument("taskId") { type = NavType.StringType }, navArgument("sessionId") { type = NavType.StringType })) { entry ->
                    val taskId = entry.arguments?.getString("taskId").orEmpty()
                    val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                    val vm: Session2ViewModel = viewModel(
                        key = "research2-session-$sessionId",
                        factory = viewModelFactory {
                            initializer { Session2ViewModel(createSavedStateHandle(), taskId, sessionId, container.sessionRepository, container.taskRepository, container.settingsRepository, container.networkStatus, container.clock, container.researchProposalSets, container.proposalSets) }
                        },
                    )
                    ResearchSessionScreen(
                        vm, zone = container.zoneProvider.current(),
                        onBack = { navController.popBackStack() },
                        onReview = { setId -> navController.navigate(Routes.review(taskId, setId)) { launchSingleTop = true } },
                        onOpenUsage = { navController.navigate(Routes.aiUsage(sessionId)) { launchSingleTop = true } },
                    )
                }
                composable(Routes.REVIEW, arguments = listOf(navArgument("taskId") { type = NavType.StringType }, navArgument("setId") { type = NavType.StringType })) { entry ->
                    val taskId = entry.arguments?.getString("taskId").orEmpty()
                    val setId = entry.arguments?.getString("setId").orEmpty()
                    val vm: ProposalReviewViewModel = viewModel(
                        key = "review-$setId",
                        factory = viewModelFactory {
                            initializer { ProposalReviewViewModel(createSavedStateHandle(), taskId, setId, container.proposalSets, container.taskRepository, container.proposalApplier, container.clock, container.zoneProvider) }
                        },
                    )
                    ProposalReviewScreen(
                        vm,
                        onBack = { navController.popBackStack() },
                        onOpenTask = { if (!navController.popBackStack(Routes.TASK_DETAIL, inclusive = false)) navController.navigate(Routes.taskDetail(taskId)) { launchSingleTop = true } },
                        onOpenInvestigation = { id -> navController.navigate(Routes.investigation(id)) { launchSingleTop = true } },
                    )
                }
                composable(Routes.RESEARCH_BRIEF, arguments = listOf(navArgument("taskId") { type = NavType.StringType }, navArgument("briefId") { type = NavType.StringType })) { entry ->
                    val taskId = entry.arguments?.getString("taskId").orEmpty()
                    val briefId = entry.arguments?.getString("briefId").orEmpty()
                    val vm: ResearchBriefViewModel = viewModel(
                        key = "research-brief-$briefId",
                        factory = viewModelFactory { initializer { ResearchBriefViewModel(createSavedStateHandle(), taskId, briefId, container.researchRepository, container.taskRepository, container.networkStatus, container.clock) } },
                    )
                    ResearchBriefScreen(
                        vm, zone = container.zoneProvider.current(),
                        onBack = { navController.popBackStack() },
                        onResearchAgain = { question -> navController.navigate(Routes.investigationNew(taskId, question)) { launchSingleTop = true } },
                    )
                }
            }
        }
    }

    if (capturing) {
        val prefill = capturePrefill
        val vm: CaptureViewModel = viewModel(factory = factoryOf {
            CaptureViewModel(container.taskActions, container.taskRepository, container.settingsRepository, container.apiKeyStore, container.aiPlanning, container.clock, container.zoneProvider)
        })
        LaunchedEffect(prefill) { if (prefill != null) { vm.prefill(prefill); capturePrefill = null } }
        CaptureSheet(
            vm,
            onDismiss = { capturing = false },
            onPlanWithAi = { text ->
                capturing = false
                navController.navigate(Routes.plan(text, auto = true)) { launchSingleTop = true }
            },
            onOpenAiSettings = { capturing = false; openAiSettings() },
            onSaved = { id, withReminder, firstNudge ->
                capturing = false
                justSaved = id
                if (withReminder) onReminderEnabled()
                scope.launch {
                    val message = when {
                        !withReminder -> "Saved to Inbox."
                        firstNudge != null -> "Saved. First nudge $firstNudge."
                        else -> "Saved."
                    }
                    val r = snackbar.showToast(message, actionLabel = "Open")
                    if (r == SnackbarResult.ActionPerformed) openTask(id)
                }
            },
        )
    }
}

@Composable
private fun PlanDestination(
    container: AppContainer,
    navController: NavHostController,
    targetTaskId: String?,
    initialText: String,
    autoRequest: Boolean,
    onReminderEnabled: () -> Unit,
) {
    val vm: PlanViewModel = viewModel(
        key = "plan-${targetTaskId ?: "new"}",
        factory = viewModelFactory {
            initializer {
                PlanViewModel(
                    createSavedStateHandle(), container.aiPlanning, container.taskRepository, container.taskActions,
                    container.settingsRepository, container.clock, container.zoneProvider, targetTaskId, initialText, autoRequest,
                    proposalSets = container.proposalSets, applier = container.proposalApplier,
                )
            }
        },
    )
    PlanScreen(
        vm, zone = container.zoneProvider.current(), now = container.clock::now,
        onBack = { navController.popBackStack() },
        onOpenTask = { id ->
            // Replace the plan screen with the task (or return to it for an edit).
            if (targetTaskId != null) navController.popBackStack()
            else navController.navigate(Routes.taskDetail(id)) { popUpTo(Routes.PLAN) { inclusive = true } }
        },
        onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) { launchSingleTop = true } },
        onReminderEnabled = onReminderEnabled,
        // Planning on a saved task hands its proposals to the one Proposal Review; the plan screen leaves the stack.
        onOpenReview = { setId -> if (targetTaskId != null) navController.navigate(Routes.review(targetTaskId, setId)) { popUpTo(Routes.PLAN_TASK) { inclusive = true } } },
    )
}

@Composable
private fun InvestigationThreadDestination(container: AppContainer, navController: androidx.navigation.NavHostController, investigationId: String?, taskId: String?, question: String, key: String) {
    val vm: com.lifeadmin.autopilot.ui.investigation.InvestigationThreadViewModel = viewModel(
        key = key,
        factory = viewModelFactory {
            initializer {
                com.lifeadmin.autopilot.ui.investigation.InvestigationThreadViewModel(
                    createSavedStateHandle(), investigationId, taskId, question, container.investigationService, container.investigations, container.taskRepository,
                    container.settingsRepository, container.usageLedger, container.investigationProposals, container.networkStatus, container.clock, container.zoneProvider,
                )
            }
        },
    )
    com.lifeadmin.autopilot.ui.investigation.InvestigationThreadScreen(
        vm, zone = container.zoneProvider.current(), now = container.clock::now,
        onBack = { navController.popBackStack() },
        onReview = { tId, setId -> navController.navigate(Routes.review(tId, setId)) { launchSingleTop = true } },
        onOpenTask = { tId -> navController.navigate(Routes.taskDetail(tId)) { launchSingleTop = true } },
        onOpenLegacy = { invId -> vm.state.value.taskId?.let { tId -> navController.navigate(Routes.research2Session(tId, invId)) { launchSingleTop = true } } },
        onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) { launchSingleTop = true } },
    )
}
