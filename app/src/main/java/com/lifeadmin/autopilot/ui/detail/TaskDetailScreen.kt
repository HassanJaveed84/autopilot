package com.lifeadmin.autopilot.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.label
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.scheduling.PolicyDescriber
import com.lifeadmin.autopilot.ui.components.EmptyState
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.MetaChip
import com.lifeadmin.autopilot.ui.components.isOverdue
import com.lifeadmin.autopilot.ui.components.isSnoozed
import java.time.Instant
import java.time.ZoneId

private enum class DetailDialog { EDIT, DEADLINE, WAITING, SNOOZE, POLICY, DELETE, CANCEL }

@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    onBack: () -> Unit,
    onReminderEnabled: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DetailDialog?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is DetailEvent.Message -> snackbar.showSnackbar(e.text)
                DetailEvent.Deleted -> onBack()
                DetailEvent.ReminderEnabled -> onReminderEnabled()
            }
        }
    }

    val details = state.details
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (details != null) {
                        IconButton(onClick = { dialog = DetailDialog.EDIT }) { Icon(Icons.Filled.Edit, contentDescription = "Edit task") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (details.task.archivedAt == null) {
                                DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; viewModel.archive() })
                            } else {
                                DropdownMenuItem(text = { Text("Restore from archive") }, onClick = { menu = false; viewModel.unarchive() })
                            }
                            if (!details.task.status.isTerminal) {
                                DropdownMenuItem(text = { Text("Cancel task") }, onClick = { menu = false; dialog = DetailDialog.CANCEL })
                            }
                            DropdownMenuItem(text = { Text("Delete permanently") }, onClick = { menu = false; dialog = DetailDialog.DELETE })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Unit
            details == null -> EmptyState("Task not found", "It may have been deleted.", Modifier.padding(padding))
            else -> DetailContent(details, state.now, state.zone, viewModel, Modifier.padding(padding), openDialog = { dialog = it })
        }
    }

    val d = details ?: return
    when (dialog) {
        DetailDialog.EDIT -> EditDetailsDialog(d.task, onDismiss = { dialog = null }) { t, n, p -> viewModel.saveDetails(t, n, p); dialog = null }
        DetailDialog.DEADLINE -> DeadlineDialog(d.task, state.now, state.zone, onDismiss = { dialog = null }) { type, date, instant ->
            viewModel.setDeadline(type, date, instant); dialog = null
        }
        DetailDialog.WAITING -> WaitingDialog(d.task, state.now, state.zone, onDismiss = { dialog = null }) { reason, checkIn ->
            viewModel.setWaiting(reason, checkIn); dialog = null
        }
        DetailDialog.SNOOZE -> SnoozeDialog(
            now = viewModel.now(), zone = state.zone,
            preview = { until, remind -> viewModel.previewSnooze(until, remind) },
            onDismiss = { dialog = null },
        ) { until, remind -> viewModel.snooze(until, remind); dialog = null }
        DetailDialog.POLICY -> PolicyEditorDialog(
            initial = d.policy ?: ReminderPolicy(id = "", taskId = d.task.id, lastEditedAt = state.now),
            taskStatus = d.task.status,
            now = viewModel.now(), zone = state.zone,
            preview = viewModel::previewPolicy,
            onDismiss = { dialog = null },
        ) { policy, onError -> viewModel.savePolicy(policy, onSaved = { dialog = null }, onError = onError) }
        DetailDialog.DELETE -> ConfirmDialog(
            "Delete task?", "This permanently removes the task, its checklist, reminders and history. Archive instead if you may need it later.", "Delete",
            onDismiss = { dialog = null },
        ) { dialog = null; viewModel.delete() }
        DetailDialog.CANCEL -> ConfirmDialog(
            "Cancel task?", "The task will stop reminding you. You can reopen it later from its history.", "Cancel task",
            onDismiss = { dialog = null },
        ) { dialog = null; viewModel.changeStatus(TaskStatus.CANCELED) }
        null -> Unit
    }
}

@Composable
private fun DetailContent(
    d: TaskDetails,
    now: Instant,
    zone: ZoneId,
    vm: TaskDetailViewModel,
    modifier: Modifier,
    openDialog: (DetailDialog) -> Unit,
) {
    val task = d.task
    LazyColumn(modifier.fillMaxSize().testTag("detail_list"), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(task.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("detail_title"))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaChip(task.status.label())
                    if (task.archivedAt != null) MetaChip("Archived")
                    if (task.isSnoozed(now)) MetaChip("Snoozed until ${Format.relative(task.snoozedUntil!!, now, zone)}", emphasis = true)
                    MetaChip("${task.priority.name.lowercase().replaceFirstChar { it.uppercase() }} priority")
                }
                task.notes?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp)) }
                if (task.status == TaskStatus.WAITING) {
                    Text(
                        buildString {
                            append("Waiting")
                            task.waitingReason?.let { append(" for: ").append(it) }
                            task.waitingCheckInAt?.let { append(" · check in ").append(Format.relative(it, now, zone)) }
                        },
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        item { StatusActions(d, now, vm, openDialog) }
        item {
            DetailCard("Deadline", action = "Edit", onAction = { openDialog(DetailDialog.DEADLINE) }) {
                val text = Format.deadline(task, now, zone) ?: "No deadline"
                Text(text + if (task.isOverdue(now, zone)) " · overdue" else "", style = MaterialTheme.typography.bodyLarge)
            }
        }
        item { RemindersCard(d, now, zone, onEdit = { openDialog(DetailDialog.POLICY) }) }
        item { ChecklistCard(d, vm) }
        item {
            Text("Activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
        }
        items(d.events.take(50), key = { it.id }) { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(Format.relative(e.at, now, zone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.38f))
                Text(e.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.62f))
            }
        }
    }
}

@Composable
private fun StatusActions(d: TaskDetails, now: Instant, vm: TaskDetailViewModel, openDialog: (DetailDialog) -> Unit) {
    val task = d.task
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (task.status) {
            TaskStatus.DONE, TaskStatus.CANCELED -> Button(onClick = vm::reopen, modifier = Modifier.testTag("action_reopen")) { Text("Reopen") }
            else -> {
                Button(onClick = vm::complete, modifier = Modifier.testTag("action_done")) { Text("Done") }
                when (task.status) {
                    TaskStatus.INBOX -> FilledTonalButton(onClick = { vm.changeStatus(TaskStatus.ACTIONABLE) }, modifier = Modifier.testTag("action_actionable")) { Text("Make actionable") }
                    TaskStatus.WAITING, TaskStatus.PAUSED -> FilledTonalButton(onClick = { vm.changeStatus(TaskStatus.ACTIONABLE) }, modifier = Modifier.testTag("action_resume")) { Text("Resume") }
                    else -> Unit
                }
                if (task.isSnoozed(now)) {
                    OutlinedButton(onClick = vm::clearSnooze) { Text("Unsnooze") }
                } else if (task.status != TaskStatus.PAUSED) {
                    OutlinedButton(onClick = { openDialog(DetailDialog.SNOOZE) }, modifier = Modifier.testTag("action_snooze")) { Text("Snooze…") }
                }
                if (task.status == TaskStatus.WAITING) {
                    OutlinedButton(onClick = { openDialog(DetailDialog.WAITING) }) { Text("Edit check-in") }
                } else if (task.status == TaskStatus.ACTIONABLE || task.status == TaskStatus.INBOX) {
                    OutlinedButton(onClick = { openDialog(DetailDialog.WAITING) }, modifier = Modifier.testTag("action_waiting")) { Text("Waiting…") }
                }
                if (task.status == TaskStatus.ACTIONABLE) {
                    OutlinedButton(onClick = { vm.changeStatus(TaskStatus.PAUSED) }, modifier = Modifier.testTag("action_pause")) { Text("Pause") }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, action: String? = null, onAction: () -> Unit = {}, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                if (action != null) TextButton(onClick = onAction) { Text(action) }
            }
            content()
        }
    }
}

@Composable
private fun RemindersCard(d: TaskDetails, now: Instant, zone: ZoneId, onEdit: () -> Unit) {
    DetailCard("Reminders", action = if (d.policy?.enabled == true) "Edit" else "Set up", onAction = onEdit) {
        Text(PolicyDescriber.describe(d.policy, zone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("policy_summary"))
        val next = d.nextReminder
        val nextText = when {
            next != null -> {
                val kind = when (next.kind) {
                    OccurrenceKind.POLICY -> "Next reminder"
                    OccurrenceKind.CHECK_IN -> "Check-in"
                    OccurrenceKind.SNOOZE_END -> "Snooze ends"
                }
                val mode = when {
                    next.status == OccurrenceStatus.PLANNED -> " · not yet handed to Android (will retry)"
                    next.exact -> " · precise timing"
                    else -> " · approximate timing (may arrive late)"
                }
                "$kind: ${Format.full(next.plannedAt, zone)}$mode"
            }
            d.task.status.isTerminal -> "No reminders — task is ${d.task.status.label().lowercase()}"
            d.task.status == TaskStatus.PAUSED -> "Paused — no reminders until resumed"
            d.task.status == TaskStatus.WAITING && d.task.waitingCheckInAt == null -> "Waiting — no check-in scheduled"
            d.policy?.enabled == true -> "No upcoming reminder"
            else -> null
        }
        nextText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp).testTag("next_reminder")) }
        val ledger = d.recentOccurrences.filter { it.status.isSettled }.take(5)
        if (ledger.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            ledger.forEach { o ->
                val what = when (o.status) {
                    OccurrenceStatus.DELIVERED -> "Delivered"
                    OccurrenceStatus.SUPPRESSED -> "Not shown (${o.note ?: "notifications blocked"})"
                    OccurrenceStatus.MISSED -> "Missed (phone off or app stopped)"
                    else -> o.status.name.lowercase()
                }
                Text("${Format.relative(o.plannedAt, now, zone)} · $what", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChecklistCard(d: TaskDetails, vm: TaskDetailViewModel) {
    var newStep by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val done = d.steps.count { it.isDone }
    DetailCard(if (d.steps.isEmpty()) "Checklist" else "Checklist · $done/${d.steps.size}") {
        d.steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).toggleable(step.isDone, role = Role.Checkbox, onValueChange = { vm.toggleStep(step.id, it) }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = step.isDone, onCheckedChange = null)
                    Text(
                        step.title,
                        modifier = Modifier.padding(start = 8.dp),
                        textDecoration = if (step.isDone) TextDecoration.LineThrough else null,
                        color = if (step.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (index > 0) IconButton(onClick = { vm.moveStep(step.id, -1) }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${step.title} up") }
                if (index < d.steps.lastIndex) IconButton(onClick = { vm.moveStep(step.id, 1) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${step.title} down") }
                IconButton(onClick = { vm.deleteStep(step.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove step ${step.title}") }
            }
        }
        fun add() {
            if (newStep.isBlank()) return
            vm.addStep(newStep) { error = it }
            newStep = ""
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newStep,
                onValueChange = { newStep = it.take(300); error = null },
                modifier = Modifier.weight(1f).testTag("step_input"),
                placeholder = { Text("Add a step") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            IconButton(onClick = { add() }, enabled = newStep.isNotBlank(), modifier = Modifier.testTag("step_add")) { Icon(Icons.Filled.Add, contentDescription = "Add step") }
        }
        if (d.steps.isNotEmpty() && done == d.steps.size && !d.task.status.isTerminal) {
            Text(
                "All steps are checked. Mark the task Done when you're finished — it won't close automatically.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
