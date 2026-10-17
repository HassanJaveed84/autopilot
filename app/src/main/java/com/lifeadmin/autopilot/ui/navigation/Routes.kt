package com.lifeadmin.autopilot.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val TODAY = "today"
    const val INBOX = "inbox"
    const val TASKS = "tasks"
    const val SETTINGS = "settings"
    const val TASK_DETAIL = "task/{taskId}"
    const val HEALTH = "health"
    fun taskDetail(taskId: String) = "task/$taskId"
}

enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    TODAY(Routes.TODAY, "Today", Icons.Filled.Home),
    INBOX(Routes.INBOX, "Inbox", Icons.Filled.MailOutline),
    TASKS(Routes.TASKS, "Tasks", @Suppress("DEPRECATION") Icons.Filled.List),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
}
