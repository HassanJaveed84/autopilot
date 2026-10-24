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
    const val RESEARCH_BRIEF = "task/{taskId}/research/{briefId}"
    const val RESEARCH2_SESSION = "task/{taskId}/research2/{sessionId}"
    /** The one Proposal Review for planning and research sets (M2). */
    const val REVIEW = "task/{taskId}/review/{setId}"
    const val AI_USAGE = "ai_settings/usage?session={session}"
    /** Investigation Threads (M4): history (optionally one task's), a new thread, one thread. */
    const val INVESTIGATIONS = "research?task={task}"
    const val INVESTIGATION_NEW = "research/new?task={task}&q={q}"
    const val INVESTIGATION = "research/thread/{investigationId}"
    fun investigations(taskId: String? = null) = "research?task=" + android.net.Uri.encode(taskId.orEmpty())
    fun investigationNew(taskId: String? = null, question: String = "") = "research/new?task=" + android.net.Uri.encode(taskId.orEmpty()) + "&q=" + android.net.Uri.encode(question)
    fun investigation(investigationId: String) = "research/thread/$investigationId"
    fun taskDetail(taskId: String) = "task/$taskId"
    fun researchBrief(taskId: String, briefId: String) = "task/$taskId/research/$briefId"
    fun research2Session(taskId: String, sessionId: String) = "task/$taskId/research2/$sessionId"
    fun review(taskId: String, setId: String) = "task/$taskId/review/$setId"
    fun aiUsage(sessionId: String? = null) = "ai_settings/usage?session=" + android.net.Uri.encode(sessionId.orEmpty())
    fun plan(text: String, auto: Boolean = false) = "plan?text=" + android.net.Uri.encode(text) + "&auto=" + auto
    fun planTask(taskId: String) = "plan/task/$taskId"
}

/** The three doing tabs. Settings is a header button, not a tab. */
enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    TODAY(Routes.TODAY, "Today", AutopilotIcons.Sun),
    INBOX(Routes.INBOX, "Inbox", AutopilotIcons.Tray),
    TASKS(Routes.TASKS, "Tasks", AutopilotIcons.List),
}
