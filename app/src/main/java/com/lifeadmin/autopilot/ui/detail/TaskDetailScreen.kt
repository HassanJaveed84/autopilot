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
import androidx.compose.ui.platform.LocalContext
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
import com.lifeadmin.autopilot.domain.scheduling.RoutineCycle
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
import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.MainLinkPill
import com.lifeadmin.autopilot.ui.components.StepDraft
import com.lifeadmin.autopilot.ui.components.StepEditorSheet
import com.lifeadmin.autopilot.ui.components.openWebLink
import com.lifeadmin.autopilot.ui.components.stepHint
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
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.Instant
import java.time.ZoneId

private enum class DetailDialog { EDIT, DEADLINE, WAITING, SNOOZE, POLICY, DELETE, CANCEL, SMALL_STEP, MORE }

/**
 * Task detail: status pill, title and notes, a plain-language follow-up card, one full-width
 * primary action (Mark done / Reopen), secondary tiles (Snooze / Waiting on… / Pause with their
 * contextual inverses), inline steps with a Next marker, deadline and priority rows, Discuss in
 * ChatGPT, saved research (read-only, when there is some) and the activity history. Every action goes through the existing repository methods.
 */
@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    onBack: () -> Unit,
    onReminderEnabled: () -> Unit = {},
    /** Saved-research navigation; null hides the research card. */
    research: ResearchNav? = null,
    /** Opens the canonical Proposal Review for a pending set (M2); null hides the banners. */
    onOpenReview: ((setId: String) -> Unit)? = null,
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
            state.loading -> Box(Modifier.fillMaxSize().padding(padding).testTag("detail_loading"))
            details == null -> Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) { RoundIconButton(AutopilotIcons.Back, "Back", onBack) }
                Text("Task not found", style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text("It may have been deleted.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            }
            else -> DetailContent(
                details, state.now, state.zone, viewModel, Modifier.padding(padding), onBack = onBack,
                openDialog = { dialog = it }, stallHint = state.stallHint,
                research = research, state = state, onOpenReview = onOpenReview,
                onDone = {
                    viewModel.complete()
                    scope.launch {
                        val r = snackbar.showToast("Done. Nudges stopped.", actionLabel = "Undo")
                        if (r == SnackbarResult.ActionPerformed) viewModel.reopen()
                    }
                },
                onDoneForNow = {
                    val label = RoutineCycle.label(viewModel.now(), viewModel.zone())
                    viewModel.finishCycle {
                        scope.launch {
                            val r = snackbar.showToast("$label. The routine continues.", actionLabel = "Undo")
                            if (r == SnackbarResult.ActionPerformed) viewModel.undoFinishCycle()
                        }
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
    research: ResearchNav?,
    state: TaskDetailUiState,
    onDone: () -> Unit,
    onPause: () -> Unit,
    onOpenReview: ((String) -> Unit)? = null,
    onDoneForNow: () -> Unit = {},
) {
    val c = AutopilotTheme.colors
    val task = d.task
    var provenanceOpen by rememberSaveable { mutableStateOf<String?>(null) }
    provenanceOpen?.let { id ->
        val record = state.provenance[id]
        if (record == null) provenanceOpen = null
        else com.lifeadmin.autopilot.ui.review.ProvenanceSheet(record, zone, onDismiss = { provenanceOpen = null }, onOpenInvestigation = research?.onOpenInvestigation)
    }
    val summary = TaskSummary(task, d.steps.count { it.isDone }, d.steps.size, d.steps.sortedBy { it.sortOrder }.firstOrNull { !it.isDone }?.title, d.nextReminder, d.policy)
    val pill = TaskPresentation.pill(summary, now, zone)
    val isDone = task.status.isTerminal
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var discussOpen by rememberSaveable { mutableStateOf(false) }
    if (discussOpen) DiscussSheet(d, zone, onDismiss = { discussOpen = false })

    // Rich Steps: the step sheet (tap a title) and the step editor (Edit in the sheet).
    val context = LocalContext.current
    val stepsLocked = task.status.isTerminal || task.archivedAt != null
    var openStepId by rememberSaveable { mutableStateOf<String?>(null) }
    var editStepId by rememberSaveable { mutableStateOf<String?>(null) }
    val openLink: (StepLink) -> Unit = { link -> if (!openWebLink(context, link.url)) vm.linkFailed() }
    editStepId?.let { id ->
        val step = d.steps.firstOrNull { it.id == id }
        if (step == null || stepsLocked) editStepId = null
        else StepEditorSheet(
            heading = if (step.description != null || step.links.isNotEmpty()) "Edit step" else "Step details",
            initial = StepDraft.of(step.title, step.description, step.links),
            onDismiss = { editStepId = null },
            onSave = { edit -> vm.updateStep(step.id, edit.title, edit.description, edit.links) { editStepId = null } },
        )
    }
    if (editStepId == null) openStepId?.let { id ->
        val step = d.steps.firstOrNull { it.id == id }
        if (step == null) openStepId = null
        else StepDetailSheet(
            step = step, locked = stepsLocked, provenance = step.provenanceId?.let { state.provenance[it] },
            onToggle = { vm.toggleStep(step.id, it) },
            onOpenLink = openLink,
            onEdit = { editStepId = step.id },
            onShowProvenance = { step.provenanceId?.let { pid -> openStepId = null; provenanceOpen = pid } },
            onDismiss = { openStepId = null },
        )
    }

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
        if (onOpenReview != null && !isDone) {
            state.openSets.forEach { set -> item(key = "review-" + set.id) { ReviewBanner(set, onOpenReview) } }
        }
        item { FollowUpCard(d, summary, now, zone, vm, openDialog) }
        item {
            Column(Modifier.padding(top = 14.dp)) {
                if (isDone) {
                    PrimaryButton(if (task.status == TaskStatus.DONE) "Mark as not done" else "Reopen", onClick = vm::reopen, icon = AutopilotIcons.Refresh, modifier = Modifier.fillMaxWidth().testTag("action_reopen"))
                } else if (RoutineCycle.canFinishCycle(task, d.policy)) {
                    // A routine: the cycle is answered here; Mark done (permanent) stays one tap away.
                    if (RoutineCycle.isCycleDone(task, now)) {
                        SecondaryButton("Undo “${RoutineCycle.label(now, zone)}”", onClick = vm::undoFinishCycle, icon = AutopilotIcons.Refresh, modifier = Modifier.fillMaxWidth().testTag("action_undo_done_for_now"))
                    } else {
                        PrimaryButton(RoutineCycle.label(now, zone), onClick = onDoneForNow, icon = AutopilotIcons.Check, modifier = Modifier.fillMaxWidth().testTag("action_done_for_now"))
                    }
                    SecondaryButton("Mark done · ends the routine", onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("action_done"))
                } else {
                    PrimaryButton("Mark done", onClick = onDone, icon = AutopilotIcons.Check, modifier = Modifier.fillMaxWidth().testTag("action_done"))
                }
                if (!isDone) {
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
        item { StepsCard(d, vm, state.provenance, onShowProvenance = { provenanceOpen = it }, onOpenStep = { openStepId = it }, onOpenLink = openLink) }
        item { DeadlineAndPriorityCard(d, now, zone, vm, openDialog) }
        // The one AI entry point: it needs no key and changes nothing here.
        if (!isDone) item { DiscussCard(onClick = { discussOpen = true }) }
        if (research != null && (state.briefs.isNotEmpty() || state.investigations.isNotEmpty())) item { ResearchCard(state, now, zone, research) }
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
/** One banner per open proposal set (handoff §2): the saved task stays separate from pending changes until the user reviews them. */
@Composable
private fun ReviewBanner(set: com.lifeadmin.autopilot.domain.proposal.ProposalSet, onOpenReview: (String) -> Unit) {
    val c = AutopilotTheme.colors
    val n = set.pending.size
    val from = when (set.source) {
        com.lifeadmin.autopilot.domain.proposal.ProposalSource.PLANNING -> "Plan with AI"
        com.lifeadmin.autopilot.domain.proposal.ProposalSource.RESEARCH -> "research"
        com.lifeadmin.autopilot.domain.proposal.ProposalSource.HANDOFF -> "ChatGPT"
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.routeSoft)
            .clickable(role = Role.Button, onClickLabel = "Review $n proposed ${if (n == 1) "change" else "changes"}") { onOpenReview(set.id) }
            .padding(14.dp).testTag("detail_review_banner_${set.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AutopilotIcons.Route, contentDescription = null, tint = c.onRouteSoft, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text("$n ${if (n == 1) "change" else "changes"} waiting for your review", style = MaterialTheme.typography.titleSmall, color = c.onRouteSoft)
            Text("From $from · nothing is applied until you say so", style = MaterialTheme.typography.bodySmall, color = c.onRouteSoft)
        }
        Icon(AutopilotIcons.ChevronRight, contentDescription = null, tint = c.onRouteSoft, modifier = Modifier.size(20.dp))
    }
}

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
        TaskPresentation.isCycleDone(summary, now) -> Fu(
            AutopilotIcons.Check, c.doneSoft, c.onDoneSoft,
            RoutineCycle.label(now, zone) + (next?.let { " · next ${Format.relativeLower(it.plannedAt, now, zone)}" } ?: ""),
            "The routine continues: ${policy?.let { TaskPresentation.scheduleShort(it, zone, now) } ?: "no nudges"}.",
            "Undo", { vm.undoFinishCycle() },
        )
        task.isSnoozed(now) -> Fu(
            AutopilotIcons.Moon, c.sunken, c.ink, "Snoozed until ${Format.relativeLower(task.snoozedUntil!!, now, zone)}",
            "Then: " + (policy?.takeIf { it.enabled }?.let { TaskPresentation.scheduleSentence(it, zone, now) } ?: "no nudges") + (if (next?.kind == OccurrenceKind.SNOOZE_END) " · reminder when the snooze ends" else "") + ".",
            "Wake", { vm.clearSnooze() },
        )
        policy == null || !policy.enabled -> Fu(AutopilotIcons.BellOff, c.sunken, c.ink, "No nudges", if (task.status == TaskStatus.INBOX) "In Inbox. Add a follow-up so this doesn't slip." else "Add a follow-up so this doesn't slip.", "Add", { openDialog(DetailDialog.POLICY) })
        next == null -> Fu(AutopilotIcons.Bell, c.signalSoft, c.onSignalSoft, "Nudging " + TaskPresentation.scheduleShort(policy, zone, now), "No upcoming nudge for this schedule.", "Change", { openDialog(DetailDialog.POLICY) })
        else -> Fu(
            AutopilotIcons.Bell, c.signalSoft, c.onSignalSoft, "Nudging " + TaskPresentation.scheduleShort(policy, zone, now),
            (if (policy.kind == ReminderKind.ONCE) "Once" else if (policy.isRoutine) "Every day, a routine" else "Until it's done") +
                " · next ${Format.relativeLower(next.plannedAt, now, zone)}$mode" + if (policy.ringInQuietHours) " · may ring in quiet hours" else "",
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
private fun StepsCard(
    d: TaskDetails,
    vm: TaskDetailViewModel,
    provenance: Map<String, com.lifeadmin.autopilot.domain.proposal.ProvenanceRecord> = emptyMap(),
    onShowProvenance: (String) -> Unit = {},
    onOpenStep: (String) -> Unit = {},
    onOpenLink: (StepLink) -> Unit = {},
) {
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
    // A done, cancelled or archived task keeps its steps read-only; "Reopen" (or restore) unlocks them.
    val locked = d.task.status.isTerminal || d.task.archivedAt != null
    if (locked && editing) editing = false
    SurfaceCard(modifier = Modifier.padding(top = 22.dp), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Steps", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            Text(if (d.steps.isEmpty()) "None yet" else "$done of ${d.steps.size} done", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2)
            if (!locked && (d.steps.size > 1 || editing)) LinkButton(if (editing) "Done" else "Edit", onClick = { editing = !editing }, modifier = Modifier.testTag("steps_edit"))
        }
        d.steps.forEachIndexed { index, step ->
            GroupDivider()
            // Hybrid A+B (Rich Steps): the box only ticks, the title area only opens the step's sheet,
            // the main-link pill only opens the link. Three separate targets, nothing nested.
            val mainLink = step.links.firstOrNull()?.takeIf { !step.isDone && !editing }
            val hint = stepHint(step, mainLinkShown = mainLink != null)
            val rich = step.description != null || step.links.isNotEmpty()
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("step_row_${step.id}"), verticalAlignment = Alignment.Top) {
                StepCheck(step.isDone, label = (if (step.isDone) "Mark not done: " else "Mark done: ") + step.title, enabled = !locked) { vm.toggleStep(step.id, it) }
                Column(Modifier.weight(1f).padding(end = 6.dp)) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClickLabel = "Open step details") { onOpenStep(step.id) }
                            .padding(vertical = 12.dp).testTag("step_open_${step.id}"),
                        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                step.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (step.isDone) c.ink3 else c.ink,
                                textDecoration = if (step.isDone) TextDecoration.LineThrough else null,
                            )
                            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.testTag("step_hint_${step.id}")) }
                        }
                        if (firstOpen?.id == step.id) {
                            Text("Next", style = MaterialTheme.typography.labelSmall, color = c.onSignalSoft, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.signalSoft).padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        // Decorative only; at large text it would cost the title its width, and the title is the target anyway.
                        if (rich && !editing && LocalDensity.current.fontScale < 1.3f) Icon(AutopilotIcons.ChevronRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(20.dp))
                    }
                    mainLink?.let { link -> MainLinkPill(link, onOpen = { onOpenLink(link) }, modifier = Modifier.padding(bottom = 8.dp).testTag("step_main_link_${step.id}")) }
                }
                // Provenance is a property of the content, shown lightly (INT-032): one marker, the full record on tap.
                step.provenanceId?.let { pid ->
                    val record = provenance[pid]
                    if (record != null && !editing) {
                        RoundIconButton(
                            AutopilotIcons.Info, tint = c.ink3, onClick = { onShowProvenance(pid) }, modifier = Modifier.padding(top = 4.dp).size(40.dp).testTag("step_prov_${step.id}"),
                            contentDescription = when (record.source) {
                                com.lifeadmin.autopilot.domain.proposal.ProposalSource.RESEARCH -> "From research"
                                com.lifeadmin.autopilot.domain.proposal.ProposalSource.PLANNING -> "From Plan with AI"
                                com.lifeadmin.autopilot.domain.proposal.ProposalSource.HANDOFF -> "From ChatGPT"
                            } + ": where this step came from",
                        )
                    }
                }
                if (editing) {
                    Row(Modifier.padding(top = 4.dp)) {
                        if (index > 0) RoundIconButton(AutopilotIcons.ChevronDown, contentDescription = "Move ${step.title} up", tint = c.ink3, onClick = { vm.moveStep(step.id, -1) }, modifier = Modifier.size(40.dp).rotate(180f))
                        if (index < d.steps.lastIndex) RoundIconButton(AutopilotIcons.ChevronDown, contentDescription = "Move ${step.title} down", tint = c.ink3, onClick = { vm.moveStep(step.id, 1) }, modifier = Modifier.size(40.dp))
                        RoundIconButton(AutopilotIcons.Trash, contentDescription = "Remove step ${step.title}", tint = c.ink3, onClick = { vm.deleteStep(step.id) }, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }
        if (locked) {
            GroupDivider()
            Text(
                if (d.task.archivedAt != null) "Restore the task from the archive to change its steps." else "Reopen the task to change its steps.",
                style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp).testTag("steps_locked"),
            )
        } else {
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
        }
        if (d.steps.isNotEmpty() && done == d.steps.size && !d.task.status.isTerminal) {
            Text("All steps are checked. Mark the task done when you're finished — it won't close automatically.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun StepCheck(checked: Boolean, label: String, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    val c = AutopilotTheme.colors
    Box(
        Modifier.size(48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle).semantics { contentDescription = label },
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
    val dueValue = Format.deadlineDetail(task, now, zone)
    val overdue = task.isOverdue(now, zone)
    SurfaceCard(modifier = Modifier.padding(top = 12.dp), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClickLabel = "Edit deadline") { openDialog(DetailDialog.DEADLINE) }.testTag("card_action_deadline").padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(if (overdue) AutopilotIcons.Warn else AutopilotIcons.Calendar, contentDescription = null, tint = if (overdue) c.danger else c.ink2, modifier = Modifier.size(22.dp))
            Text("Deadline", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
            Text(dueValue, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = if (overdue) c.danger else if (task.deadlineType == DeadlineType.NONE) c.ink2 else c.ink)
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

/** Saved-research navigation from the task (read-only); optional so the detail screen renders without it. */
data class ResearchNav(
    val onOpenBrief: (briefId: String) -> Unit,
    val onOpenHistory: () -> Unit,
    val onOpenInvestigation: (investigationId: String) -> Unit = {},
)

/** Saved research for this task from earlier versions: the latest, and a link to all. Read-only and offline. */
@Composable
private fun ResearchCard(state: TaskDetailUiState, now: Instant, zone: ZoneId, nav: ResearchNav) {
    val c = AutopilotTheme.colors
    val latestBrief = state.briefs.firstOrNull()
    val latestThread = state.investigations.firstOrNull()
    // Show whichever is newer: a research thread or a legacy M3A brief.
    val latest = if (latestThread != null && (latestBrief == null || latestThread.updatedAtMs >= latestBrief.createdAt.toEpochMilli())) null else latestBrief
    val total = state.briefs.size + state.investigations.size
    SurfaceCard(radius = 20.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(top = 12.dp).testTag("detail_research_card")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Saved research", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            LinkButton(if (total > 1) "All $total" else "History", onClick = nav.onOpenHistory, modifier = Modifier.padding(end = 8.dp).testTag("detail_research_history"))
        }
        if (latestThread != null && latest == null) {
            GroupDivider()
            val searches = latestThread.searches
            val sources = latestThread.sources
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.onOpenInvestigation(latestThread.id) }.padding(16.dp).testTag("detail_research_latest"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    ("Researched " + Format.relative(Instant.ofEpochMilli(latestThread.updatedAtMs), now, zone) + " · ${latestThread.turnCount} ${if (latestThread.turnCount == 1) "turn" else "turns"}" +
                        (if (searches > 0) " · $searches ${if (searches == 1) "search" else "searches"}" else "") + (if (sources > 0) " · $sources ${if (sources == 1) "source" else "sources"}" else "")).uppercase(),
                    style = com.lifeadmin.autopilot.ui.theme.TimeTextStyle.copy(fontSize = 12.sp, lineHeight = 16.sp), color = c.ink2,
                )
                Text(latestThread.title, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                latestThread.preview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
        if (latest != null) {
            GroupDivider()
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.onOpenBrief(latest.id) }.padding(16.dp).testTag("detail_research_latest"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    ("Researched " + Format.relative(latest.createdAt, now, zone) + " · ${latest.searchCount} ${if (latest.searchCount == 1) "search" else "searches"} · ${latest.sourceCount} ${if (latest.sourceCount == 1) "source" else "sources"}" +
                        (if (latest.isStale(now)) " · may be out of date" else "")).uppercase(),
                    style = com.lifeadmin.autopilot.ui.theme.TimeTextStyle.copy(fontSize = 12.sp, lineHeight = 16.sp), color = c.ink2,
                )
                Text(latest.question, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                latest.content.quickAnswer.firstOrNull()?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if (latest.applied.isNotEmpty()) Text("${latest.applied.size} applied to this task", style = MaterialTheme.typography.labelSmall, color = c.onDoneSoft)
            }
        }
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
                    minHeight = 44.dp, tint = c.onSignalSoft, modifier = Modifier.testTag("stall_${a.name.lowercase()}"),
                )
            }
            LinkButton("Not now", onClick = { vm.dismissHint(hint) }, color = c.onSignalSoft, modifier = Modifier.testTag("stall_dismiss"))
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
