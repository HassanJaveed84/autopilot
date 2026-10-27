package com.lifeadmin.autopilot.ui.applink

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.lifeadmin.autopilot.share.applink.HandoffIssue
import com.lifeadmin.autopilot.share.applink.HandoffProblem
import com.lifeadmin.autopilot.share.applink.LinkHandoff
import com.lifeadmin.autopilot.share.applink.TestLinkParser
import com.lifeadmin.autopilot.share.applink.TestLinkProblem
import com.lifeadmin.autopilot.share.applink.TestLinkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the latest link reached this screen. */
enum class Delivery { NEW_SCREEN, OPEN_SCREEN, RESTORED }

data class AppLinkTestUi(
    /** Null only for a launch that carried no link at all. */
    val result: TestLinkResult?,
    /** The intent was an `https` VIEW, which is what a tapped App Link delivers. */
    val viewIntent: Boolean,
    /** Package that started the activity, as Android reports it (for example `com.openai.chatgpt`). */
    val openedBy: String?,
    val delivery: Delivery,
    /** Links delivered to this screen so far, counted to make repeats visible. Display only. */
    val received: Int,
)

/**
 * The App Link test screen's state. It holds only the validated message or handoff fields (or the
 * refusal category), never the raw URL, and has no repository, network or AI dependency, so a link
 * cannot change anything.
 * The saved fields survive process death; a restored screen shows the same link without re-reading the intent.
 */
class AppLinkTestViewModel(private val saved: SavedStateHandle, private val parser: TestLinkParser) : ViewModel() {
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<AppLinkTestUi> = _state

    fun onLink(url: String?, viewIntent: Boolean, openedBy: String?, delivery: Delivery) {
        val result = url?.let(parser::parse)
        val received = (saved.get<Int>(KEY_COUNT) ?: 0) + 1
        saved[KEY_MESSAGE] = (result as? TestLinkResult.Received)?.message
        saved[KEY_PROBLEM] = (result as? TestLinkResult.Rejected)?.problem?.name
        val handoff = (result as? TestLinkResult.HandoffReceived)?.handoff
        saved[KEY_TASK] = handoff?.task
        saved[KEY_REF] = handoff?.ref
        saved[KEY_DECISIONS] = handoff?.decisions?.let(::ArrayList)
        saved[KEY_STEPS] = handoff?.steps?.let(::ArrayList)
        saved[KEY_NOTES] = handoff?.notes?.let(::ArrayList)
        val issue = (result as? TestLinkResult.HandoffRejected)?.issue
        saved[KEY_ISSUE] = issue?.let { arrayListOf(it.problem.name, it.field?.toString().orEmpty(), it.fieldsReceived.toString()) }
        saved[KEY_VIEW] = viewIntent
        saved[KEY_BY] = openedBy
        saved[KEY_COUNT] = received
        _state.value = AppLinkTestUi(result, viewIntent, openedBy, delivery, received)
    }

    private fun restore(): AppLinkTestUi {
        val count = saved.get<Int>(KEY_COUNT) ?: 0
        val message = saved.get<String>(KEY_MESSAGE)
        val problem = saved.get<String>(KEY_PROBLEM)?.let { p -> TestLinkProblem.entries.firstOrNull { it.name == p } }
        val task = saved.get<String>(KEY_TASK)
        val issue = saved.get<ArrayList<String>>(KEY_ISSUE)?.let { (p, field, fields) ->
            HandoffProblem.entries.firstOrNull { it.name == p }?.let { HandoffIssue(it, field.toIntOrNull(), fields.toInt()) }
        }
        fun list(key: String) = saved.get<ArrayList<String>>(key).orEmpty().toList()
        val result = when {
            message != null -> TestLinkResult.Received(message)
            problem != null -> TestLinkResult.Rejected(problem)
            task != null -> TestLinkResult.HandoffReceived(LinkHandoff(task, list(KEY_DECISIONS), list(KEY_STEPS), list(KEY_NOTES), saved.get<String>(KEY_REF)))
            issue != null -> TestLinkResult.HandoffRejected(issue)
            else -> null
        }
        return AppLinkTestUi(result, saved.get<Boolean>(KEY_VIEW) ?: false, saved.get<String>(KEY_BY), if (count > 0) Delivery.RESTORED else Delivery.NEW_SCREEN, count)
    }

    private companion object {
        const val KEY_MESSAGE = "app_link_message"
        const val KEY_PROBLEM = "app_link_problem"
        const val KEY_VIEW = "app_link_view"
        const val KEY_BY = "app_link_by"
        const val KEY_COUNT = "app_link_count"
        const val KEY_TASK = "app_link_handoff_task"
        const val KEY_REF = "app_link_handoff_ref"
        const val KEY_DECISIONS = "app_link_handoff_decisions"
        const val KEY_STEPS = "app_link_handoff_steps"
        const val KEY_NOTES = "app_link_handoff_notes"
        const val KEY_ISSUE = "app_link_handoff_issue"
    }
}
