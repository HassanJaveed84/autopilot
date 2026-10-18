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
    const val AI_SETTINGS = "ai_settings"
    const val PLAN = "plan?text={text}"
    const val PLAN_TASK = "plan/task/{taskId}"
    fun taskDetail(taskId: String) = "task/$taskId"
    fun plan(text: String) = "plan?text=" + android.net.Uri.encode(text)
    fun planTask(taskId: String) = "plan/task/$taskId"
}

enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    TODAY(Routes.TODAY, "Today", Icons.Filled.Home),
    INBOX(Routes.INBOX, "Inbox", Icons.Filled.MailOutline),
    TASKS(Routes.TASKS, "Tasks", @Suppress("DEPRECATION") Icons.Filled.List),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
}
