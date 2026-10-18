package com.lifeadmin.autopilot.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lifeadmin.autopilot.di.AppContainer
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.lifeadmin.autopilot.ui.ai.AiSettingsScreen
import com.lifeadmin.autopilot.ui.ai.AiSettingsViewModel
import com.lifeadmin.autopilot.ui.capture.CaptureSheet
import com.lifeadmin.autopilot.ui.plan.PlanScreen
import com.lifeadmin.autopilot.ui.plan.PlanViewModel
import com.lifeadmin.autopilot.ui.capture.CaptureViewModel
import com.lifeadmin.autopilot.ui.detail.TaskDetailScreen
import com.lifeadmin.autopilot.ui.detail.TaskDetailViewModel
import com.lifeadmin.autopilot.ui.health.HealthBanner
import com.lifeadmin.autopilot.ui.health.HealthSummaryCard
import com.lifeadmin.autopilot.ui.health.ReminderHealthScreen
import com.lifeadmin.autopilot.ui.health.ReminderHealthViewModel
import com.lifeadmin.autopilot.ui.navigation.Routes
import com.lifeadmin.autopilot.ui.navigation.TopLevelDestination
import com.lifeadmin.autopilot.ui.settings.SettingsScreen
import com.lifeadmin.autopilot.ui.settings.SettingsViewModel
import com.lifeadmin.autopilot.ui.tasks.InboxScreen
import com.lifeadmin.autopilot.ui.tasks.TaskListViewModel
import com.lifeadmin.autopilot.ui.tasks.TasksScreen
import com.lifeadmin.autopilot.ui.today.TodayScreen
import com.lifeadmin.autopilot.ui.today.TodayViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * App shell: bottom navigation (Today / Inbox / Tasks / Settings), quick-capture FAB and task
 * detail destination. [openTaskRequests] carries validated task ids from notification taps.
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
    val topLevel = TopLevelDestination.entries.firstOrNull { d -> currentDestination?.hierarchy?.any { it.route == d.route } == true }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (topLevel != null && topLevel != TopLevelDestination.SETTINGS) {
                ExtendedFloatingActionButton(
                    onClick = { capturing = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Capture") },
                    modifier = Modifier.testTag("fab_capture"),
                )
            }
        },
        bottomBar = {
            if (topLevel != null) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = topLevel == dest,
                            onClick = { goTo(dest.route) },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(dest.label) },
                            modifier = Modifier.testTag("nav_${dest.route}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Routes.TODAY, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(Routes.TODAY) {
                val vm: TodayViewModel = viewModel(factory = factoryOf {
                    TodayViewModel(container.taskRepository, container.settingsRepository, container.taskActions, container.clock, container.zoneProvider)
                })
                TodayScreen(vm, snackbar, onOpenTask = ::openTask, onOpenInbox = { goTo(Routes.INBOX) }, healthBanner = {
                    if (showHealth) HealthBanner(container) { navController.navigate(Routes.HEALTH) { launchSingleTop = true } }
                })
            }
            composable(Routes.INBOX) {
                val vm: TaskListViewModel = viewModel(key = "inbox", factory = factoryOf {
                    TaskListViewModel(container.taskRepository, container.clock, container.zoneProvider, inboxOnly = true)
                })
                InboxScreen(vm, snackbar, onOpenTask = ::openTask)
            }
            composable(Routes.TASKS) {
                val vm: TaskListViewModel = viewModel(key = "tasks", factory = factoryOf {
                    TaskListViewModel(container.taskRepository, container.clock, container.zoneProvider, inboxOnly = false)
                })
                TasksScreen(vm, snackbar, onOpenTask = ::openTask)
            }
            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(factory = factoryOf {
                    SettingsViewModel(container.settingsRepository, container.backupRepository) { container.reconcileAll("settings") }
                })
                SettingsScreen(vm, snackbar, healthSection = {
                    if (showHealth) HealthSummaryCard(container) { navController.navigate(Routes.HEALTH) { launchSingleTop = true } }
                }, onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) { launchSingleTop = true } })
            }
            composable(Routes.AI_SETTINGS) {
                val vm: AiSettingsViewModel = viewModel(factory = factoryOf {
                    AiSettingsViewModel(container.settingsRepository, container.apiKeyStore, container.aiPlanning, container.clock)
                })
                AiSettingsScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.PLAN, arguments = listOf(navArgument("text") { type = NavType.StringType; defaultValue = "" })) { entry ->
                val text = entry.arguments?.getString("text").orEmpty()
                PlanDestination(container, navController, targetTaskId = null, initialText = text, onReminderEnabled = onReminderEnabled)
            }
            composable(Routes.PLAN_TASK, arguments = listOf(navArgument("taskId") { type = NavType.StringType })) { entry ->
                val taskId = entry.arguments?.getString("taskId").orEmpty()
                PlanDestination(container, navController, targetTaskId = taskId, initialText = "", onReminderEnabled = onReminderEnabled)
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
                    TaskDetailViewModel(taskId, container.taskRepository, container.taskActions, container.settingsRepository, container.clock, container.zoneProvider, container.hintDismissals)
                })
                TaskDetailScreen(
                    vm, onBack = { navController.popBackStack() }, onReminderEnabled = onReminderEnabled,
                    onMoveForward = { navController.navigate(Routes.planTask(taskId)) { launchSingleTop = true } },
                )
            }
        }
    }

    if (capturing) {
        val vm: CaptureViewModel = viewModel(factory = factoryOf { CaptureViewModel(container.taskActions, container.settingsRepository) })
        CaptureSheet(
            vm,
            onDismiss = { capturing = false },
            onPlanWithAi = { text ->
                capturing = false
                navController.navigate(Routes.plan(text)) { launchSingleTop = true }
            },
            onSaved = { id, withReminder ->
                capturing = false
                if (withReminder) onReminderEnabled()
                scope.launch {
                    val r = snackbar.showSnackbar(if (withReminder) "Saved with reminders" else "Saved to Inbox", actionLabel = "Open")
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
    onReminderEnabled: () -> Unit,
) {
    val vm: PlanViewModel = viewModel(
        key = "plan-${targetTaskId ?: "new"}",
        factory = viewModelFactory {
            initializer {
                PlanViewModel(
                    createSavedStateHandle(), container.aiPlanning, container.taskRepository, container.taskActions,
                    container.settingsRepository, container.clock, container.zoneProvider, targetTaskId, initialText,
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
    )
}
