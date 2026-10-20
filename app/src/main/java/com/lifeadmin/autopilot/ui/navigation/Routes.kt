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
    const val RESEARCH_HISTORY = "task/{taskId}/research"
    const val RESEARCH_NEW = "task/{taskId}/research/new?q={q}"
    const val RESEARCH_RUN = "task/{taskId}/research/run/{runId}?q={q}"
    const val RESEARCH_BRIEF = "task/{taskId}/research/{briefId}"
    const val RESEARCH_APPLY = "task/{taskId}/research/{briefId}/apply?sel={sel}"
    fun taskDetail(taskId: String) = "task/$taskId"
    fun researchHistory(taskId: String) = "task/$taskId/research"
    fun researchNew(taskId: String, question: String = "") = "task/$taskId/research/new?q=" + android.net.Uri.encode(question)
    fun researchRun(taskId: String, runId: String, question: String) = "task/$taskId/research/run/$runId?q=" + android.net.Uri.encode(question)
    fun researchBrief(taskId: String, briefId: String) = "task/$taskId/research/$briefId"
    fun researchApply(taskId: String, briefId: String, selected: Collection<String>) = "task/$taskId/research/$briefId/apply?sel=" + android.net.Uri.encode(selected.joinToString(","))
    fun plan(text: String, auto: Boolean = false) = "plan?text=" + android.net.Uri.encode(text) + "&auto=" + auto
    fun planTask(taskId: String) = "plan/task/$taskId"
}

/** The three doing tabs. Settings is a header button, not a tab. */
enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    TODAY(Routes.TODAY, "Today", AutopilotIcons.Sun),
    INBOX(Routes.INBOX, "Inbox", AutopilotIcons.Tray),
    TASKS(Routes.TASKS, "Tasks", AutopilotIcons.List),
}
