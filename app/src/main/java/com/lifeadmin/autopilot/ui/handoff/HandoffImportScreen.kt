package com.lifeadmin.autopilot.ui.handoff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.share.applink.HandoffDestination
import com.lifeadmin.autopilot.share.applink.ImportRefusal
import com.lifeadmin.autopilot.share.applink.LinkHandoff
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.GroupDivider
import com.lifeadmin.autopilot.ui.components.GroupRow
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.RowGroup
import com.lifeadmin.autopilot.ui.review.ReviewPresenter
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/**
 * Every import stage except the review itself (docs/CHATGPT_ROUND_TRIP_UX.md §3, §5): the task picker,
 * and one card for each outcome that ends here. The exit always returns to the app that opened the link.
 */
@Composable
fun HandoffImportScreen(
    state: ImportUi,
    candidates: List<TaskSummary>,
    query: String,
    exitLabel: String,
    onQuery: (String) -> Unit,
    onChoose: (String) -> Unit,
    onCreateTask: () -> Unit,
    onChange: () -> Unit,
    onCancelChange: () -> Unit,
    onExit: () -> Unit,
) {
    val c = AutopilotTheme.colors
    val picking = state.stage == ImportStage.CHOOSE
    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                if (picking && state.changing) RoundIconButton(AutopilotIcons.Back, contentDescription = "Back to the review", onClick = onCancelChange, modifier = Modifier.testTag("handoff_back"))
                else RoundIconButton(AutopilotIcons.Close, contentDescription = exitLabel, onClick = onExit, modifier = Modifier.testTag("handoff_close"))
                Text(
                    if (picking) "Where should this go?" else "From ChatGPT",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }.testTag("handoff_heading"),
                )
            }
        },
        bottomBar = {
            if (!picking && state.stage != ImportStage.WORKING) {
                Surface(color = c.paper) {
                    PrimaryButton(exitLabel, onClick = onExit, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).testTag("handoff_exit"))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("handoff_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.stage) {
                ImportStage.WORKING -> item { Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("handoff_working")) }
                ImportStage.CHOOSE -> picker(state, candidates, query, onQuery, onChoose, onCreateTask)
                ImportStage.NOTHING_NEW -> item {
                    Outcome(
                        "Already in Autopilot",
                        "Everything ChatGPT sent is already on this task. Nothing to add, and nothing was changed.",
                        tag = "handoff_nothing_new",
                    ) { LinkButton("Choose another task", onClick = onChange, modifier = Modifier.testTag("handoff_change")) }
                }
                ImportStage.REFUSED -> item { refusal(state.refusal).let { (t, b) -> Outcome(t, b, tag = "handoff_refused") } }
                ImportStage.FAILED -> item { Outcome("Something went wrong", "Your tasks weren't changed. Tap the link in ChatGPT again.", tag = "handoff_failed") }
                ImportStage.EMPTY -> item { Outcome("Nothing to bring in", "Ask ChatGPT to send your decisions to Autopilot, then tap its Open in Autopilot link.", tag = "handoff_empty") }
                ImportStage.REVIEW -> Unit
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.picker(
    state: ImportUi,
    candidates: List<TaskSummary>,
    query: String,
    onQuery: (String) -> Unit,
    onChoose: (String) -> Unit,
    onCreateTask: () -> Unit,
) {
    val handoff = state.handoff ?: return
    item {
        val c = AutopilotTheme.colors
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ChatGPT sent ${contentSummary(handoff)} about “${handoff.task}”.", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.testTag("handoff_summary"))
            Text(
                when (state.why) {
                    HandoffDestination.Why.NO_REFERENCE -> "The link doesn't say which task it's for. Choose one, or start a new task."
                    HandoffDestination.Why.NOT_FOUND -> "We couldn't find that task in Autopilot. It may have been deleted. Choose another, or start a new task."
                    null -> "Choose the task these changes are for."
                },
                style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.testTag("handoff_why"),
            )
            Text("Nothing changes until you review and apply.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
        }
    }
    item {
        OutlinedTextField(
            value = query, onValueChange = onQuery, label = { Text("Search your tasks") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("handoff_search"), shape = RoundedCornerShape(14.dp),
        )
    }
    item {
        RowGroup {
            GroupRow(
                title = "New task “${handoff.task}”", subtitle = "Starts in your Inbox with no reminders", onClick = onCreateTask,
                icon = AutopilotIcons.Plus, modifier = Modifier.testTag("handoff_new_task"),
            )
        }
    }
    if (candidates.isEmpty()) {
        item { Text(if (query.isBlank()) "No open tasks." else "No open task matches “$query”.", style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink2, modifier = Modifier.testTag("handoff_no_tasks")) }
    } else {
        item {
            RowGroup(Modifier.testTag("handoff_tasks")) {
                candidates.forEachIndexed { i, s ->
                    if (i > 0) GroupDivider()
                    val steps = if (s.stepsTotal == 0) null else "${s.stepsTotal} ${if (s.stepsTotal == 1) "step" else "steps"}"
                    GroupRow(
                        title = s.task.title, subtitle = listOfNotNull(ReviewPresenter.statusLabel(s.task.status.name), steps).joinToString(" · "),
                        onClick = { onChoose(s.task.id) }, modifier = Modifier.testTag("handoff_pick_${s.task.id}"),
                    )
                }
            }
        }
    }
}

/**
 * The destination line inside Proposal Review: which task the changes go to, a way to change it
 * while nothing has been settled, and the cases worth a word (another title, a repeat link).
 */
@Composable
fun DestinationCard(
    taskTitle: String?,
    handoff: LinkHandoff?,
    titleDiffers: Boolean,
    existed: Boolean,
    settled: Boolean,
    onChange: () -> Unit,
    onOpenTask: () -> Unit,
) {
    val c = AutopilotTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.routeSoft).padding(14.dp).testTag("handoff_destination"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AutopilotIcons.Route, contentDescription = null, tint = c.onRouteSoft, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Adding to", style = MaterialTheme.typography.labelMedium, color = c.onRouteSoft)
                Text(taskTitle ?: "…", style = MaterialTheme.typography.titleSmall, color = c.onRouteSoft, modifier = Modifier.testTag("handoff_destination_title"))
            }
            if (!settled) LinkButton("Change", onClick = onChange, color = c.onRouteSoft, modifier = Modifier.testTag("handoff_change"))
        }
        if (titleDiffers && handoff != null) Text("ChatGPT called it “${handoff.task}”.", style = MaterialTheme.typography.bodySmall, color = c.onRouteSoft, modifier = Modifier.testTag("handoff_title_differs"))
        if (existed) Text("You've already brought this in from ChatGPT; it's shown as you left it. Nothing is added twice.", style = MaterialTheme.typography.bodySmall, color = c.onRouteSoft, modifier = Modifier.testTag("handoff_repeat"))
        if (settled) LinkButton("Open the task in Autopilot", onClick = onOpenTask, color = c.onRouteSoft, modifier = Modifier.testTag("handoff_open_task"))
    }
}

@Composable
private fun Outcome(title: String, body: String, tag: String, extra: (@Composable () -> Unit)? = null) {
    val c = AutopilotTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Title and explanation read as one announcement; the optional action stays its own target.
        Column(Modifier.semantics(mergeDescendants = true) { heading() }.testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        }
        extra?.invoke()
    }
}

internal fun refusal(reason: ImportRefusal?): Pair<String, String> = when (reason) {
    ImportRefusal.TOO_BIG -> "That's more than one link can carry" to
        "Ask ChatGPT for fewer or shorter items: up to 10 steps, 5 decisions and 5 notes, each under 200 characters. Nothing was cut short or saved."
    ImportRefusal.NEWER_VERSION -> "This link needs a newer Autopilot" to "Update Autopilot, then tap the link again. Nothing was changed."
    ImportRefusal.NOT_A_LINK, ImportRefusal.NO_HANDOFF, ImportRefusal.MALFORMED, null ->
        "This link didn't come through right" to "Ask ChatGPT to send it to Autopilot again. Nothing was changed."
}

internal fun contentSummary(h: LinkHandoff): String {
    fun n(count: Int, one: String, many: String) = if (count == 0) null else "$count ${if (count == 1) one else many}"
    val parts = listOfNotNull(n(h.steps.size, "step", "steps"), n(h.decisions.size, "decision", "decisions"), n(h.notes.size, "note", "notes"))
    return if (parts.size <= 1) parts.firstOrNull().orEmpty() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}
