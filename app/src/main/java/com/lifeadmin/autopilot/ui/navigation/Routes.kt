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
    const val LICENSES = "licenses"
    const val RESEARCH_BRIEF = "task/{taskId}/research/{briefId}"
    const val RESEARCH2_SESSION = "task/{taskId}/research2/{sessionId}"
    /** The one Proposal Review for ChatGPT, saved planning and saved research sets. */
    const val REVIEW = "task/{taskId}/review/{setId}"
    /** Saved research (read-only): history (optionally one task's) and one thread. */
    const val INVESTIGATIONS = "research?task={task}"
    const val INVESTIGATION = "research/thread/{investigationId}"
    fun investigations(taskId: String? = null) = "research?task=" + android.net.Uri.encode(taskId.orEmpty())
    fun investigation(investigationId: String) = "research/thread/$investigationId"
    fun taskDetail(taskId: String) = "task/$taskId"
    fun researchBrief(taskId: String, briefId: String) = "task/$taskId/research/$briefId"
    fun research2Session(taskId: String, sessionId: String) = "task/$taskId/research2/$sessionId"
    fun review(taskId: String, setId: String) = "task/$taskId/review/$setId"
}

/** The three doing tabs. Settings is a header button, not a tab. */
enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    TODAY(Routes.TODAY, "Today", AutopilotIcons.Sun),
    INBOX(Routes.INBOX, "Inbox", AutopilotIcons.Tray),
    TASKS(Routes.TASKS, "Tasks", AutopilotIcons.List),
}
