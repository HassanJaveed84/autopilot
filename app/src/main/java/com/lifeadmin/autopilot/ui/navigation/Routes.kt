package com.lifeadmin.autopilot.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.lifeadmin.autopilot.ui.components.AutopilotIcons

object Routes {
    const val TODAY = "today"
    const val INBOX = "inbox"
    const val TASKS = "tasks"
    const val SETTINGS = "settings"
    const val TASK_DETAIL = "task/{taskId}"
    const val HEALTH = "health"
    const val AI_SETTINGS = "ai_settings"
    const val LICENSES = "licenses"
    const val PLAN = "plan?text={text}&auto={auto}"
    const val PLAN_TASK = "plan/task/{taskId}"
    fun taskDetail(taskId: String) = "task/$taskId"
    fun plan(text: String, auto: Boolean = false) = "plan?text=" + android.net.Uri.encode(text) + "&auto=" + auto
    fun planTask(taskId: String) = "plan/task/$taskId"
}

/** The three doing tabs. Settings is a header button, not a tab. */
enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    TODAY(Routes.TODAY, "Today", AutopilotIcons.Sun),
    INBOX(Routes.INBOX, "Inbox", AutopilotIcons.Tray),
    TASKS(Routes.TASKS, "Tasks", AutopilotIcons.List),
}
