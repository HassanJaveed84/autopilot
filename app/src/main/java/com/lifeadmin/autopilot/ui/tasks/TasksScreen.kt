package com.lifeadmin.autopilot.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.PageTitle
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.ScreenHeader
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.TaskCard
import com.lifeadmin.autopilot.ui.detail.ConfirmDialog
import com.lifeadmin.autopilot.ui.detail.FollowUpSheet
import com.lifeadmin.autopilot.ui.detail.WaitingSheet
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun doneWithUndo(scope: CoroutineScope, snackbar: SnackbarHostState, vm: TaskListViewModel, s: TaskSummary, message: String = "Done. Nudges stopped.") {
    vm.complete(s.task.id) {
        scope.launch {
            val r = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.reopen(s.task.id)
        }
    }
}

/** Tasks: search, status filters with counts, a sort toggle and the card list. */
@Composable
fun TasksScreen(
    viewModel: TaskListViewModel,
    snackbar: SnackbarHostState,
    onOpenTask: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    healthIssue: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }

    LazyColumn(Modifier.fillMaxSize().testTag("tasks_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item { ScreenHeader(state.dateText, onOpenSettings, Modifier.statusBarsPadding(), healthDot = healthIssue) }
        item { PageTitle("Tasks", Modifier.padding(top = 14.dp, bottom = 16.dp)) }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().testTag("search_field"),
                placeholder = { Text("Search titles and notes", color = c.ink2) },
                leadingIcon = { Icon(AutopilotIcons.Search, contentDescription = null, tint = c.ink2) },
                trailingIcon = { if (state.query.isNotEmpty()) RoundIconButton(AutopilotIcons.Close, contentDescription = "Clear search", onClick = { viewModel.setQuery("") }) },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.sunken, unfocusedContainerColor = c.sunken,
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, cursorColor = c.ink,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TaskFilter.entries.forEach { f ->
                    ChoiceChip(f.label, selected = state.filter == f, count = (state.counts[f] ?: 0).toString(), onClick = { viewModel.setFilter(f) }, modifier = Modifier.testTag("filter_${f.name.lowercase()}"))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.tasks.size == 1) "1 task" else "${state.tasks.size} tasks", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, modifier = Modifier.weight(1f))
                Row(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Change sort order", onClick = viewModel::cycleSort).semantics { contentDescription = "Sorted by ${state.sort.label}" }.testTag("tasks_sort").padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(AutopilotIcons.Sort, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
                    Text(state.sort.label, style = MaterialTheme.typography.labelLarge, color = c.ink)
                }
            }
        }
        if (state.tasks.isEmpty() && !state.loading) {
            item { EmptyList(if (state.query.isBlank()) "Nothing here" else "Nothing matches “${state.query}”", if (state.query.isBlank()) "Tasks with this status will show up here." else "Search looks through titles and notes.") }
        }
        items(state.tasks, key = { it.task.id }) { s ->
            TaskCard(
                s, state.now, state.zone,
                onOpen = { onOpenTask(s.task.id) },
                onToggleDone = if (s.task.archivedAt != null) null else if (s.task.status.isTerminal) ({ viewModel.reopen(s.task.id) }) else ({ doneWithUndo(scope, snackbar, viewModel, s) }),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
internal fun EmptyList(title: String, body: String, icon: ImageVector = AutopilotIcons.List, tintDone: Boolean = false) {
    val c = AutopilotTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IconCircle(icon, tint = if (tintDone) c.onDoneSoft else c.ink2, background = if (tintDone) c.doneSoft else c.sunken, size = if (tintDone) 88.dp else 64.dp)
        Text(title, style = if (tintDone) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

/** Inbox triage: each note gets four big next moves (Nudge me · Plan with AI · Waiting on… · Done already) and Delete. */
@Composable
fun InboxScreen(
    viewModel: TaskListViewModel,
    snackbar: SnackbarHostState,
    onOpenTask: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    healthIssue: Boolean = false,
    onPlanTask: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val scope = rememberCoroutineScope()
    var nudging by remember { mutableStateOf<TaskSummary?>(null) }
    var waiting by remember { mutableStateOf<TaskSummary?>(null) }
    var deleting by remember { mutableStateOf<TaskSummary?>(null) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }

    LazyColumn(Modifier.fillMaxSize().testTag("inbox_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item { ScreenHeader(state.dateText, onOpenSettings, Modifier.statusBarsPadding(), healthDot = healthIssue) }
        item { PageTitle("Inbox", Modifier.padding(top = 14.dp, bottom = 8.dp)) }
        item { Text("Things you captured without a nudge. Give each one a next move — or let it go.", style = MaterialTheme.typography.bodyLarge, color = c.ink2) }
        if (state.tasks.isEmpty() && !state.loading) {
            item { EmptyList("Inbox clear", "When you capture something and switch off its nudge, it waits here until you decide what it needs.", icon = AutopilotIcons.Tray, tintDone = true) }
        }
        items(state.tasks, key = { it.task.id }) { s ->
            SurfaceCard(modifier = Modifier.padding(top = 20.dp), radius = 24.dp, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp)) {
                Text(
                    s.task.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 25.sp), color = c.ink,
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open task") { onOpenTask(s.task.id) },
                )
                Text("Captured ${Format.relativeLower(s.task.createdAt, state.now, state.zone)}", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 4.dp))
                s.task.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 6.dp), maxLines = 3) }
                // Two columns normally; one column at large text so no label is cut to "Nudge…".
                val stacked = LocalDensity.current.fontScale >= 1.3f
                val triage: List<@Composable RowScope.() -> Unit> = listOf(
                    { TriageButton(AutopilotIcons.Bell, "Nudge me", c.signalSoft, c.onSignalSoft, Modifier.weight(1f).testTag("inbox_nudge")) { nudging = s } },
                    { TriageButton(AutopilotIcons.Route, "Plan with AI", c.routeSoft, c.onRouteSoft, Modifier.weight(1f).testTag("inbox_plan")) { onPlanTask(s.task.id) } },
                    { TriageButton(AutopilotIcons.Hourglass, "Waiting on…", c.sunken, c.ink, Modifier.weight(1f).testTag("inbox_waiting")) { waiting = s } },
                    { TriageButton(AutopilotIcons.Check, "Done already", c.sunken, c.ink, Modifier.weight(1f).testTag("inbox_done")) { doneWithUndo(scope, snackbar, viewModel, s, "Marked done. Nice.") } },
                )
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    triage.chunked(if (stacked) 1 else 2).forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { group.forEach { it() } }
                    }
                }
                Row(
                    Modifier.padding(top = 4.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = { deleting = s }).testTag("inbox_delete").padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(AutopilotIcons.Trash, contentDescription = null, tint = c.ink2, modifier = Modifier.size(18.dp))
                    Text("Delete", style = MaterialTheme.typography.labelLarge, color = c.ink2)
                }
            }
        }
    }

    nudging?.let { s ->
        val now = viewModel.now()
        val zone = viewModel.zone()
        FollowUpSheet(
            initial = s.policy?.takeIf { it.enabled } ?: ReminderPresets.applyTo(ReminderPolicy(id = "", taskId = s.task.id, lastEditedAt = now), settings.defaultPreset, now.atZone(zone).toLocalDate()).copy(enabled = true),
            settings = settings, now = now, zone = zone,
            onDismiss = { nudging = null },
            onSave = { policy, onError ->
                if (!policy.enabled) { onError("Choose a follow-up to move this note to your tasks, or leave it in the Inbox."); return@FollowUpSheet }
                viewModel.nudge(s.task.id, policy, onDone = {
                    nudging = null
                    scope.launch { snackbar.showSnackbar("Moved to Tasks. It will nudge you ${com.lifeadmin.autopilot.ui.components.TaskPresentation.scheduleShort(policy, zone, now)}.") }
                }, onError = onError)
            },
            contextText = "Turns this note into a task that nudges you.",
            saveLabel = "Make it a task",
            offHint = "stays in Inbox",
        )
    }
    waiting?.let { s ->
        WaitingSheet(s.task, viewModel.now(), viewModel.zone(), onDismiss = { waiting = null }) { reason, checkIn ->
            viewModel.wait(s.task.id, reason, checkIn); waiting = null
            scope.launch { snackbar.showSnackbar("Waiting" + (reason?.let { " on $it" } ?: "") + (checkIn?.let { ". Check-in ${Format.relativeLower(it, viewModel.now(), viewModel.zone())}." } ?: ".")) }
        }
    }
    deleting?.let { s ->
        ConfirmDialog("Delete this note?", "“${s.task.title}” will be removed permanently.", "Delete", onDismiss = { deleting = null }) {
            deleting = null; viewModel.delete(s.task.id)
            scope.launch { snackbar.showSnackbar("Deleted.") }
        }
    }
}

@Composable
private fun TriageButton(icon: ImageVector, label: String, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = fg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
