package com.lifeadmin.autopilot.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ui.components.showToast
import kotlinx.coroutines.launch
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.StallAction
import com.lifeadmin.autopilot.domain.usecase.StallHint
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSnackbar
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.GroupDivider
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.StatusPill
import com.lifeadmin.autopilot.ui.components.StatusTone
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.TaskPresentation
import com.lifeadmin.autopilot.ui.components.color
import com.lifeadmin.autopilot.ui.components.icon
import com.lifeadmin.autopilot.ui.components.isOverdue
import com.lifeadmin.autopilot.ui.components.isSnoozed
import com.lifeadmin.autopilot.ui.components.provenanceBorder
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.Instant
import java.time.ZoneId

private enum class DetailDialog { EDIT, DEADLINE, WAITING, SNOOZE, POLICY, DELETE, CANCEL, SMALL_STEP, MORE }

/**
 * Task detail: status pill, title and notes, a plain-language follow-up card, one full-width
 * primary action (Mark done / Reopen), secondary tiles (Snooze / Waiting on… / Pause with their
 * contextual inverses), inline steps with a Next marker, deadline and priority rows, the AI card
 * and the activity history. Every action goes through the existing repository methods.
 */
@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    onBack: () -> Unit,
    onReminderEnabled: () -> Unit = {},
    /** Opens "Move this forward"; null hides the AI card (e.g. in tests). */
    onMoveForward: (() -> Unit)? = null,
    onOpenAiSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<DetailDialog?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is DetailEvent.Message -> snackbar.showToast(e.text)
                DetailEvent.Deleted -> onBack()
                DetailEvent.ReminderEnabled -> onReminderEnabled()
            }
        }
    }

    val details = state.details
    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) { data -> AutopilotSnackbar(data) } },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding))
            details == null -> Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) { RoundIconButton(AutopilotIcons.Back, "Back", onBack) }
                Text("Task not found", style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text("It may have been deleted.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            }
            else -> DetailContent(
                details, state.now, state.zone, viewModel, Modifier.padding(padding), onBack = onBack,
                openDialog = { dialog = it }, stallHint = state.stallHint, onMoveForward = onMoveForward, onOpenAiSettings = onOpenAiSettings,
                aiEnabled = state.settings.aiEnabled,
                onDone = {
                    viewModel.complete()
                    scope.launch {
                        val r = snackbar.showToast("Done. Nudges stopped.", actionLabel = "Undo")
                        if (r == SnackbarResult.ActionPerformed) viewModel.reopen()
                    }
                },
                onPause = {
                    viewModel.changeStatus(TaskStatus.PAUSED)
                    scope.launch {
                        val r = snackbar.showToast("Paused. No nudges until you resume.", actionLabel = "Undo")
                        if (r == SnackbarResult.ActionPerformed) viewModel.changeStatus(TaskStatus.ACTIONABLE)
                    }
                },
            )
        }
    }

    val d = details ?: return
    when (dialog) {
        DetailDialog.EDIT -> EditDetailsDialog(d.task, onDismiss = { dialog = null }) { t, n, p -> viewModel.saveDetails(t, n, p); dialog = null }
        DetailDialog.DEADLINE -> DeadlineDialog(d.task, state.now, state.zone, onDismiss = { dialog = null }) { type, date, instant ->
            viewModel.setDeadline(type, date, instant); dialog = null
        }
        DetailDialog.WAITING -> WaitingSheet(d.task, state.now, state.zone, onDismiss = { dialog = null }) { reason, checkIn ->
            viewModel.setWaiting(reason, checkIn); dialog = null
        }
        DetailDialog.SNOOZE -> SnoozeSheet(
            now = viewModel.now(), zone = state.zone,
            onDismiss = { dialog = null },
        ) { until, remind ->
            viewModel.snooze(until, remind); dialog = null
            scope.launch {
                val r = snackbar.showToast("Snoozed until ${Format.relativeLower(until, state.now, state.zone)}.", actionLabel = "Undo")
                if (r == SnackbarResult.ActionPerformed) viewModel.clearSnooze()
            }
        }
        DetailDialog.POLICY -> FollowUpSheet(
            initial = d.policy ?: ReminderPolicy(id = "", taskId = d.task.id, lastEditedAt = state.now),
            settings = state.settings,
            now = viewModel.now(), zone = state.zone,
            onDismiss = { dialog = null },
            onSave = { policy, onError -> viewModel.savePolicy(policy, onSaved = { dialog = null }, onError = onError) },
            contextText = "For “${d.task.title}”.",
            taskStatus = d.task.status,
        )
        DetailDialog.DELETE -> ConfirmDialog(
            "Delete task?", "This permanently removes the task, its checklist, reminders and history. Archive instead if you may need it later.", "Delete",
            onDismiss = { dialog = null },
        ) { dialog = null; viewModel.delete() }
        DetailDialog.CANCEL -> ConfirmDialog(
            "Cancel task?", "The task will stop nudging you. You can reopen it later from its history.", "Cancel task",
            onDismiss = { dialog = null },
        ) { dialog = null; viewModel.changeStatus(TaskStatus.CANCELED) }
        DetailDialog.SMALL_STEP -> SmallStepDialog(onDismiss = { dialog = null }) { step ->
            viewModel.addStep(step) {}
            dialog = null
        }
        DetailDialog.MORE -> MoreActionsSheet(
            title = d.task.title,
            archived = d.task.archivedAt != null,
            terminal = d.task.status.isTerminal,
            canMoveToInbox = false, // INBOX is a starting state only (TaskStateMachine); "Pause" is the way to park a task.
            onDismiss = { dialog = null },
            onEdit = { dialog = DetailDialog.EDIT },
            onMoveToInbox = { dialog = null },
            onArchive = { dialog = null; viewModel.archive() },
            onUnarchive = { dialog = null; viewModel.unarchive() },
            onCancel = { dialog = DetailDialog.CANCEL },
            onDelete = { dialog = DetailDialog.DELETE },
        )
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
    onBack: () -> Unit,
    openDialog: (DetailDialog) -> Unit,
    stallHint: StallHint?,
    onMoveForward: (() -> Unit)?,
    onOpenAiSettings: () -> Unit,
    aiEnabled: Boolean,
    onDone: () -> Unit,
    onPause: () -> Unit,
) {
    val c = AutopilotTheme.colors
    val task = d.task
    val summary = TaskSummary(task, d.steps.count { it.isDone }, d.steps.size, d.steps.sortedBy { it.sortOrder }.firstOrNull { !it.isDone }?.title, d.nextReminder, d.policy)
    val pill = TaskPresentation.pill(summary, now, zone)
    val isDone = task.status.isTerminal
    var showHistory by rememberSaveable { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize().testTag("detail_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AutopilotIcons.Back, contentDescription = "Back", onClick = onBack, modifier = Modifier.offset(x = (-8).dp))
                Spacer(Modifier.weight(1f))
                RoundIconButton(AutopilotIcons.Pencil, contentDescription = "Edit task", onClick = { openDialog(DetailDialog.EDIT) })
                RoundIconButton(AutopilotIcons.More, contentDescription = "More options", onClick = { openDialog(DetailDialog.MORE) }, modifier = Modifier.offset(x = 8.dp))
            }
        }
        item {
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val (bg, fg) = when (pill.tone) {
                    StatusTone.SIGNAL -> c.signalSoft to c.onSignalSoft
                    StatusTone.DONE -> c.doneSoft to c.onDoneSoft
                    StatusTone.DANGER -> c.dangerSoft to c.onDangerSoft
                    StatusTone.MUTED -> c.sunken to c.ink
                }
                StatusPill(pill.text, pill.glyph.icon(), bg, fg, modifier = Modifier.semantics { contentDescription = "Status: ${pill.text}" })
                if (task.priority == Priority.HIGH && !isDone) StatusPill("High priority", AutopilotIcons.Flag, Color.Transparent, c.ink, border = c.lineStrong)
                if (task.isSnoozed(now)) StatusPill("Until ${Format.relativeLower(task.snoozedUntil!!, now, zone)}", AutopilotIcons.Moon, c.sunken, c.ink)
            }
            Text(task.title, style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp).semantics { heading() }.testTag("detail_title"))
            task.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = c.ink2) }
        }
        item { FollowUpCard(d, summary, now, zone, vm, openDialog) }
        item {
            Column(Modifier.padding(top = 14.dp)) {
                if (isDone) {
                    PrimaryButton(if (task.status == TaskStatus.DONE) "Mark as not done" else "Reopen", onClick = vm::reopen, icon = AutopilotIcons.Refresh, modifier = Modifier.fillMaxWidth().testTag("action_reopen"))
                } else {
                    PrimaryButton("Mark done", onClick = onDone, icon = AutopilotIcons.Check, modifier = Modifier.fillMaxWidth().testTag("action_done"))
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (task.isSnoozed(now)) ActionTile(AutopilotIcons.Sun, "Wake", Modifier.weight(1f).testTag("action_unsnooze")) { vm.clearSnooze() }
                        else ActionTile(AutopilotIcons.Moon, "Snooze", Modifier.weight(1f).testTag("action_snooze"), enabled = task.status != TaskStatus.PAUSED) { openDialog(DetailDialog.SNOOZE) }
                        when (task.status) {
                            TaskStatus.WAITING -> ActionTile(AutopilotIcons.Play, "Stop waiting", Modifier.weight(1f).testTag("action_resume")) { vm.changeStatus(TaskStatus.ACTIONABLE) }
                            TaskStatus.PAUSED -> ActionTile(AutopilotIcons.Hourglass, "Waiting on…", Modifier.weight(1f), enabled = false) {}
                            else -> ActionTile(AutopilotIcons.Hourglass, "Waiting on…", Modifier.weight(1f).testTag("action_waiting")) { openDialog(DetailDialog.WAITING) }
                        }
                        when (task.status) {
                            TaskStatus.PAUSED -> ActionTile(AutopilotIcons.Play, "Resume", Modifier.weight(1f).testTag("action_resume")) { vm.changeStatus(TaskStatus.ACTIONABLE) }
                            TaskStatus.INBOX -> ActionTile(AutopilotIcons.Play, "Make actionable", Modifier.weight(1f).testTag("action_actionable")) { vm.changeStatus(TaskStatus.ACTIONABLE) }
                            TaskStatus.ACTIONABLE -> ActionTile(AutopilotIcons.Pause, "Pause", Modifier.weight(1f).testTag("action_pause"), onClick = onPause)
                            else -> ActionTile(AutopilotIcons.Pause, "Pause", Modifier.weight(1f), enabled = false) {}
                        }
                    }
                }
            }
        }
        if (stallHint != null) item { StallHintCard(stallHint, vm, openDialog) }
        item { StepsCard(d, vm) }
        item { DeadlineAndPriorityCard(d, now, zone, vm, openDialog) }
        if (onMoveForward != null && !task.status.isTerminal) item { AiCard(aiEnabled, onMoveForward, onOpenAiSettings) }
        item {
            LinkButton(if (showHistory) "Hide history" else "History · ${d.events.size} entries", onClick = { showHistory = !showHistory }, modifier = Modifier.padding(top = 12.dp).testTag("detail_history_toggle"))
        }
        if (showHistory) {
            item { RecentDeliveries(d, now, zone) }
            items(d.events.take(50), key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(Format.relative(e.at, now, zone), style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.weight(0.38f))
                    Text(e.summary, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(0.62f))
                }
            }
        }
    }
}

/** "Nudging 09:00 & 18:00 daily · until it's done · next today 09:00 · Change" in plain words. */
@Composable
private fun FollowUpCard(d: TaskDetails, summary: TaskSummary, now: Instant, zone: ZoneId, vm: TaskDetailViewModel, openDialog: (DetailDialog) -> Unit) {
    val c = AutopilotTheme.colors
    val task = d.task
    val policy = d.policy
    val next = d.nextReminder
    data class Fu(val icon: ImageVector, val bg: Color, val fg: Color, val title: String, val sub: String, val action: String?, val onAction: (() -> Unit)?)
    val mode = when {
        next == null -> ""
        next.status == OccurrenceStatus.PLANNED -> " · not yet handed to Android (will retry)"
        next.exact -> " · precise timing"
        else -> " · approximate timing"
    }
    val fu = when {
        task.status.isTerminal -> Fu(AutopilotIcons.Check, c.doneSoft, c.onDoneSoft, if (task.status == TaskStatus.DONE) "Done" else "Canceled", "Nudges stopped.", null, null)
        task.archivedAt != null -> Fu(AutopilotIcons.Archive, c.sunken, c.ink, "Archived", "Restore it from More actions to continue.", null, null)
        task.status == TaskStatus.WAITING -> Fu(
            AutopilotIcons.Hourglass, c.sunken, c.ink,
            task.waitingReason?.takeIf { it.isNotBlank() }?.let { "Waiting on $it" } ?: "Waiting",
            when {
                task.waitingCheckInAt == null -> "No check-in set. Nudges pause until you resume."
                next?.kind == OccurrenceKind.CHECK_IN -> "One check-in ${Format.relativeLower(next.plannedAt, now, zone)}$mode. No nudges until then."
                task.waitingCheckInAt!! <= now -> "Check-in was due ${Format.relativeLower(task.waitingCheckInAt, now, zone)}."
                else -> "One check-in ${Format.relativeLower(task.waitingCheckInAt, now, zone)}."
            },
            "Edit", { openDialog(DetailDialog.WAITING) },
        )
        task.status == TaskStatus.PAUSED -> Fu(AutopilotIcons.Pause, c.sunken, c.ink, "Paused", "No nudges until you resume.", "Resume", { vm.changeStatus(TaskStatus.ACTIONABLE) })
        task.isSnoozed(now) -> Fu(
            AutopilotIcons.Moon, c.sunken, c.ink, "Snoozed until ${Format.relativeLower(task.snoozedUntil!!, now, zone)}",
            "Then: " + (policy?.takeIf { it.enabled }?.let { TaskPresentation.scheduleSentence(it, zone, now) } ?: "no nudges") + (if (next?.kind == OccurrenceKind.SNOOZE_END) " · reminder when the snooze ends" else "") + ".",
            "Wake", { vm.clearSnooze() },
        )
        policy == null || !policy.enabled -> Fu(AutopilotIcons.BellOff, c.sunken, c.ink, "No nudges", if (task.status == TaskStatus.INBOX) "In Inbox. Add a follow-up so this doesn't slip." else "Add a follow-up so this doesn't slip.", "Add", { openDialog(DetailDialog.POLICY) })
        next == null -> Fu(AutopilotIcons.Bell, c.signalSoft, c.onSignalSoft, "Nudging " + TaskPresentation.scheduleShort(policy, zone, now), "No upcoming nudge for this schedule.", "Change", { openDialog(DetailDialog.POLICY) })
        else -> Fu(
            AutopilotIcons.Bell, c.signalSoft, c.onSignalSoft, "Nudging " + TaskPresentation.scheduleShort(policy, zone, now),
            (if (policy.kind == ReminderKind.ONCE) "Once" else "Until it's done") + " · next ${Format.relativeLower(next.plannedAt, now, zone)}$mode",
            "Change", { openDialog(DetailDialog.POLICY) },
        )
    }
    // At large text the action link moves under the sentence so the text keeps its width.
    val largeText = LocalDensity.current.fontScale >= 1.3f
    SurfaceCard(modifier = Modifier.padding(top = 18.dp), contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconCircle(fu.icon, tint = fu.fg, background = fu.bg, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(fu.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = c.ink, modifier = Modifier.testTag("policy_summary"))
                Text(fu.sub, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, modifier = Modifier.testTag("next_reminder"))
                if (largeText && fu.action != null && fu.onAction != null) LinkButton(fu.action, onClick = fu.onAction, modifier = Modifier.testTag("card_action_reminders"))
            }
            if (!largeText && fu.action != null && fu.onAction != null) LinkButton(fu.action, onClick = fu.onAction, modifier = Modifier.testTag("card_action_reminders"))
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = AutopilotTheme.colors
    Column(
        modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) c.ink else c.ink3, modifier = Modifier.size(22.dp))
        // Two lines at large text so "Waiting on…" is never cut to "Waitin…".
        val lines = if (LocalDensity.current.fontScale >= 1.3f) 2 else 1
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) c.ink else c.ink3, maxLines = lines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun StepsCard(d: TaskDetails, vm: TaskDetailViewModel) {
    val c = AutopilotTheme.colors
    var newStep by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val done = d.steps.count { it.isDone }
    val firstOpen = d.steps.sortedBy { it.sortOrder }.firstOrNull { !it.isDone }
    fun add() {
        if (newStep.isBlank()) return
        vm.addStep(newStep) { error = it }
        newStep = ""
    }
    // Reorder and delete live behind "Edit" so the resting list is a plain checklist (the board).
    var editing by rememberSaveable { mutableStateOf(false) }
    SurfaceCard(modifier = Modifier.padding(top = 22.dp), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Steps", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            Text(if (d.steps.isEmpty()) "None yet" else "$done of ${d.steps.size} done", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2)
            if (d.steps.size > 1 || editing) LinkButton(if (editing) "Done" else "Edit", onClick = { editing = !editing }, modifier = Modifier.testTag("steps_edit"))
        }
        d.steps.forEachIndexed { index, step ->
            GroupDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                StepCheck(step.isDone, label = (if (step.isDone) "Mark not done: " else "Mark done: ") + step.title) { vm.toggleStep(step.id, it) }
                Text(
                    step.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (step.isDone) c.ink3 else c.ink,
                    textDecoration = if (step.isDone) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
                if (firstOpen?.id == step.id) {
                    Text("Next", style = MaterialTheme.typography.labelSmall, color = c.onSignalSoft, modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.signalSoft).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                if (editing) {
                    if (index > 0) RoundIconButton(AutopilotIcons.ChevronDown, contentDescription = "Move ${step.title} up", tint = c.ink3, onClick = { vm.moveStep(step.id, -1) }, modifier = Modifier.size(40.dp).rotate(180f))
                    if (index < d.steps.lastIndex) RoundIconButton(AutopilotIcons.ChevronDown, contentDescription = "Move ${step.title} down", tint = c.ink3, onClick = { vm.moveStep(step.id, 1) }, modifier = Modifier.size(40.dp))
                    RoundIconButton(AutopilotIcons.Trash, contentDescription = "Remove step ${step.title}", tint = c.ink3, onClick = { vm.deleteStep(step.id) }, modifier = Modifier.size(40.dp))
                }
            }
        }
        GroupDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(AutopilotIcons.Plus, contentDescription = null, tint = c.ink3, modifier = Modifier.size(20.dp)) }
            OutlinedTextField(
                value = newStep,
                onValueChange = { newStep = it.take(300); error = null },
                modifier = Modifier.weight(1f).testTag("step_input"),
                placeholder = { Text("Add a step", color = c.ink3) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = c.ink),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            // One plus on the left is the design; the add button appears only once there is text to add.
            if (newStep.isNotBlank()) RoundIconButton(AutopilotIcons.Plus, contentDescription = "Add step", tint = c.ink, onClick = { add() }, modifier = Modifier.testTag("step_add"))
        }
        if (d.steps.isNotEmpty() && done == d.steps.size && !d.task.status.isTerminal) {
            Text("All steps are checked. Mark the task done when you're finished — it won't close automatically.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun StepCheck(checked: Boolean, label: String, onToggle: (Boolean) -> Unit) {
    val c = AutopilotTheme.colors
    Box(
        Modifier.size(48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (checked) c.done else Color.Transparent).border(2.dp, if (checked) c.done else c.lineStrong, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(AutopilotIcons.Check, contentDescription = null, tint = c.onDone, modifier = Modifier.size(14.dp)) }
    }
}

@Composable
private fun DeadlineAndPriorityCard(d: TaskDetails, now: Instant, zone: ZoneId, vm: TaskDetailViewModel, openDialog: (DetailDialog) -> Unit) {
    val c = AutopilotTheme.colors
    val task = d.task
    var prioOpen by rememberSaveable { mutableStateOf(false) }
    val today = now.atZone(zone).toLocalDate()
    val dueValue = when (task.deadlineType) {
        DeadlineType.NONE -> "None"
        DeadlineType.DATE_ONLY -> task.deadlineLocalDate?.let { "${Format.date(it, today)} · ${Format.daysUntil(it, today)}" } ?: "None"
        DeadlineType.EXACT -> task.deadlineInstant?.let { Format.relative(it, now, zone) } ?: "None"
    }
    val overdue = task.isOverdue(now, zone)
    SurfaceCard(modifier = Modifier.padding(top = 12.dp), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClickLabel = "Edit deadline") { openDialog(DetailDialog.DEADLINE) }.testTag("card_action_deadline").padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(if (overdue) AutopilotIcons.Warn else AutopilotIcons.Calendar, contentDescription = null, tint = if (overdue) c.danger else c.ink2, modifier = Modifier.size(22.dp))
            Text("Deadline", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
            Text(dueValue + if (overdue) " · overdue" else "", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = if (overdue) c.danger else if (task.deadlineType == DeadlineType.NONE) c.ink2 else c.ink)
        }
        GroupDivider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClickLabel = "Change priority") { prioOpen = !prioOpen }.testTag("card_action_priority").padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(AutopilotIcons.Flag, contentDescription = null, tint = c.ink2, modifier = Modifier.size(22.dp))
            Text("Priority", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
            Text(task.priority.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink)
        }
        if (prioOpen) {
            Row(Modifier.padding(start = 48.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { p ->
                    ChoiceChip(p.name.lowercase().replaceFirstChar { it.uppercase() }, selected = task.priority == p, onClick = { vm.saveDetails(task.title, task.notes, p); prioOpen = false })
                }
            }
        }
    }
}

@Composable
private fun AiCard(aiEnabled: Boolean, onMoveForward: () -> Unit, onOpenAiSettings: () -> Unit) {
    val c = AutopilotTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).provenanceBorder(suggested = aiEnabled, radius = 20.dp).padding(16.dp).testTag("detail_ai_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            IconCircle(AutopilotIcons.Route, tint = if (aiEnabled) c.route else c.ink2, background = if (aiEnabled) c.routeSoft else c.sunken)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (aiEnabled) "Stuck? Ask Claude for a next step" else "AI suggestions are off", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = c.ink)
                Text(
                    if (aiEnabled) "Suggests missing steps or a better date. You choose what to apply. Sends this task only."
                    else "Turn on AI planning to get suggested steps and dates for a task like this.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2,
                )
            }
        }
        SecondaryButton(
            if (aiEnabled) "Suggest" else "Set up AI planning",
            onClick = if (aiEnabled) onMoveForward else onOpenAiSettings,
            route = aiEnabled, minHeight = 44.dp,
            modifier = Modifier.padding(start = 52.dp).testTag("action_move_forward"),
        )
    }
}

@Composable
private fun RecentDeliveries(d: TaskDetails, now: Instant, zone: ZoneId) {
    val c = AutopilotTheme.colors
    val ledger = d.recentOccurrences.filter { it.status.isSettled }.take(5)
    if (ledger.isEmpty()) return
    Column(Modifier.padding(bottom = 8.dp)) {
        Text("Recent nudges", style = MaterialTheme.typography.labelLarge, color = c.ink2)
        ledger.forEach { o ->
            val what = when (o.status) {
                OccurrenceStatus.DELIVERED -> "Delivered"
                OccurrenceStatus.SUPPRESSED -> "Not shown (${o.note ?: "notifications blocked"})"
                OccurrenceStatus.MISSED -> "Missed (phone off or app stopped)"
                else -> o.status.name.lowercase()
            }
            Text("${Format.relative(o.plannedAt, now, zone)} · $what", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
    }
}

@Composable
private fun StallHintCard(hint: StallHint, vm: TaskDetailViewModel, openDialog: (DetailDialog) -> Unit) {
    val c = AutopilotTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(20.dp)).background(c.signalSoft).padding(16.dp).testTag("stall_hint")) {
        Text(hint.message, style = MaterialTheme.typography.bodyLarge, color = c.onSignalSoft)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            hint.actions.forEach { a ->
                SecondaryButton(
                    when (a) {
                        StallAction.BREAK_DOWN -> "Add a smaller step"
                        StallAction.MOVE_DATE -> "Move the date"
                        StallAction.MARK_WAITING -> "Mark waiting"
                        StallAction.PAUSE -> "Pause"
                        StallAction.CHECK_IN -> "Update check-in"
                    },
                    onClick = {
                        when (a) {
                            StallAction.BREAK_DOWN -> openDialog(DetailDialog.SMALL_STEP)
                            StallAction.MOVE_DATE -> openDialog(DetailDialog.DEADLINE)
                            StallAction.MARK_WAITING, StallAction.CHECK_IN -> openDialog(DetailDialog.WAITING)
                            StallAction.PAUSE -> vm.changeStatus(TaskStatus.PAUSED)
                        }
                    },
                    minHeight = 44.dp, modifier = Modifier.testTag("stall_${a.name.lowercase()}"),
                )
            }
            LinkButton("Not now", onClick = { vm.dismissHint(hint) }, modifier = Modifier.testTag("stall_dismiss"))
        }
    }
}

@Composable
private fun SmallStepDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Smallest next step") },
        text = {
            Column {
                Text("Something you could do in five minutes, e.g. “Look up train times”.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = text, onValueChange = { text = it.take(300) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("small_step_input"), singleLine = true)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onAdd(text.trim()) }, enabled = text.isNotBlank()) { Text("Add step") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
