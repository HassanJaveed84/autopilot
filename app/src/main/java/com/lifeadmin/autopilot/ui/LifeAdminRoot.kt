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
import com.lifeadmin.autopilot.ui.capture.CaptureSheet
import com.lifeadmin.autopilot.ui.capture.CaptureViewModel
import com.lifeadmin.autopilot.ui.detail.TaskDetailScreen
import com.lifeadmin.autopilot.ui.detail.TaskDetailViewModel
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
    healthBanner: @Composable (onOpenSettings: () -> Unit) -> Unit = {},
    healthSection: @Composable () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var capturing by rememberSaveable { mutableStateOf(false) }
    val topLevel = TopLevelDestination.entries.firstOrNull { d -> currentDestination?.hierarchy?.any { it.route == d.route } == true }

    val pendingTask by openTaskRequests.collectAsStateWithLifecycle()
    LaunchedEffect(pendingTask) {
        pendingTask?.let { id ->
            navController.navigate(Routes.taskDetail(id)) { launchSingleTop = true }
            onOpenTaskHandled()
        }
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
                TodayScreen(vm, snackbar, onOpenTask = ::openTask, onOpenInbox = { goTo(Routes.INBOX) }, healthBanner = { healthBanner { goTo(Routes.SETTINGS) } })
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
                SettingsScreen(vm, snackbar, healthSection = healthSection)
            }
            composable(Routes.TASK_DETAIL, arguments = listOf(navArgument("taskId") { type = NavType.StringType })) { entry ->
                val taskId = entry.arguments?.getString("taskId").orEmpty()
                val vm: TaskDetailViewModel = viewModel(key = "detail-$taskId", factory = factoryOf {
                    TaskDetailViewModel(taskId, container.taskRepository, container.taskActions, container.settingsRepository, container.clock, container.zoneProvider)
                })
                TaskDetailScreen(vm, onBack = { navController.popBackStack() }, onReminderEnabled = onReminderEnabled)
            }
        }
    }

    if (capturing) {
        val vm: CaptureViewModel = viewModel(factory = factoryOf { CaptureViewModel(container.taskActions, container.settingsRepository) })
        CaptureSheet(
            vm,
            onDismiss = { capturing = false },
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
