package com.lifeadmin.autopilot.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.ai.DraftReminder
import com.lifeadmin.autopilot.ai.DraftReminderKind
import com.lifeadmin.autopilot.ai.DraftStep
import com.lifeadmin.autopilot.ai.NoticeLevel
import com.lifeadmin.autopilot.ai.PlanningMode
import com.lifeadmin.autopilot.ai.ProposalDraft
import com.lifeadmin.autopilot.ai.ProposalValidator
import com.lifeadmin.autopilot.ai.Provenance
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver
import com.lifeadmin.autopilot.domain.scheduling.PolicyDescriber
import com.lifeadmin.autopilot.domain.scheduling.ReminderProjection
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.DateSelectDialog
import com.lifeadmin.autopilot.ui.components.DateTimeSelectDialog
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.InkSwitch
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.ProvenanceTag
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.TimeSelectDialog
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.components.provenanceBorder
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Built per use so they follow a locale change while the app runs.
internal val dayFmt: DateTimeFormatter get() = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
internal val dayTimeFmt: DateTimeFormatter get() = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())
internal val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/**
 * All review sections, as LazyColumn items. Dashed indigo = suggested by AI; the moment a field
 * is edited it turns solid with "Edited by you". Edits go through [update]; nothing is saved.
 */
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.reviewItems(
    draft: ProposalDraft,
    current: TaskDetails?,
    zone: ZoneId,
    today: LocalDate,
    now: Instant,
    update: ((ProposalDraft) -> ProposalDraft) -> Unit,
) {
    val isEdit = draft.mode == PlanningMode.FOLLOW_THROUGH
    item { Banner(isEdit) }
    item { OriginalTextCard(draft.originalText, isEdit) }
    if (draft.advice.isNotBlank()) item { TipCard(draft.advice) }
    if (draft.notices.isNotEmpty()) item { Notices(draft) }

    // Title and notes
    item {
        SurfaceCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), modifier = Modifier.testTag("review_task")) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (isEdit && current != null) {
                    if (draft.title.trim() == current.task.title) Text("Keeps the current title: ${current.task.title}", style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink2)
                    else {
                        DiffLine("Now", current.task.title)
                        ApplyRow("Use the suggested title", draft.applyTitle, "review_apply_title") { on -> update { it.copy(applyTitle = on) } }
                        if (draft.applyTitle) ProvenanceField("Task", draft.title, suggested = !draft.titleEdited, tag = "review_title", bold = true) { v -> update { it.copy(title = v.take(ProposalValidator.MAX_TITLE), titleEdited = true) } }
                    }
                } else {
                    ProvenanceField("Task", draft.title, suggested = !draft.titleEdited, tag = "review_title", bold = true, error = draft.title.isBlank()) { v -> update { it.copy(title = v.take(ProposalValidator.MAX_TITLE), titleEdited = true) } }
                    ProvenanceField("Notes", draft.notes, suggested = !draft.notesEdited && draft.notes.isNotBlank(), tag = "review_notes", placeholder = "Add notes") { v -> update { it.copy(notes = v.take(ProposalValidator.MAX_NOTES), notesEdited = true) } }
                }
            }
        }
    }
    if (isEdit && current != null && draft.notes.isNotBlank() && draft.notes.trim() != current.task.notes.orEmpty()) item {
        ReviewCard("Notes", tag = "review_notes_diff") {
            DiffLine("Now", current.task.notes ?: "(none)")
            ApplyRow("Replace notes with the suggestion", draft.applyNotes, "review_apply_notes") { on -> update { it.copy(applyNotes = on) } }
            if (draft.applyNotes) ProvenanceField("New notes", draft.notes, suggested = !draft.notesEdited, tag = "review_notes") { v -> update { it.copy(notes = v.take(ProposalValidator.MAX_NOTES), notesEdited = true) } }
        }
    }

    item { StepsCard(draft, isEdit, update) }
    item { ReminderCard(draft, current, isEdit, zone, today, now, update) }
    item { DeadlineCard(draft, current, isEdit, zone, today, update) }

    if (draft.waiting.apply || draft.waiting.waitingFor.isNotBlank()) item {
        ReviewCard("Waiting on someone", tag = "review_waiting") {
            Text(
                "This sounds event-triggered" + (draft.waiting.waitingFor.takeIf { it.isNotBlank() }?.let { ": waiting for $it" } ?: "") +
                    ". A Waiting task doesn't send its regular nudges" + (draft.waiting.checkInLocal?.let { "; you'll be reminded to check in ${it.format(dayTimeFmt)}." } ?: "; no check-in date is set, so add one if you want a nudge."),
                style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink2,
            )
            ApplyRow("Mark as Waiting", draft.waiting.apply, "review_apply_waiting") { on -> update { it.copy(waiting = it.waiting.copy(apply = on)) } }
        }
    }

    if (draft.priority != null) item {
        ReviewCard("Priority", tag = "review_priority") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProvenanceTag(suggested = true)
                Text(
                    draft.priority.lowercase().replaceFirstChar { it.uppercase() } + (draft.priorityReason.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ApplyRow("Use this priority", draft.applyPriority, "review_apply_priority") { on -> update { it.copy(applyPriority = on) } }
        }
    }

    if (draft.assumptions.isNotEmpty() || draft.questions.isNotEmpty()) item {
        ReviewCard("Check these", tag = "review_assumptions") {
            draft.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            if (draft.questions.isNotEmpty()) {
                Text("Open questions", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                draft.questions.forEach { Text("? $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun Banner(isEdit: Boolean) {
    val c = AutopilotTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.routeSoft).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(AutopilotIcons.Route, contentDescription = null, tint = c.onRouteSoft, modifier = Modifier.size(22.dp))
        Text(
            if (isEdit) "Claude suggested these from this task. Dashed means suggested; edit anything and it becomes yours. Nothing changes until you tap Apply."
            else "Claude drafted this from your words. Dashed means suggested; edit anything and it becomes yours. Nothing is saved until you tap Save task.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.onRouteSoft,
        )
    }
}

@Composable
internal fun TipCard(text: String) {
    val c = AutopilotTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.sunken).padding(14.dp).testTag("review_advice"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(AutopilotIcons.Info, contentDescription = null, tint = c.ink2, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("TIP · CHANGES NOTHING", style = MaterialTheme.typography.labelSmall, color = c.ink2)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        }
    }
}

@Composable
internal fun Notices(draft: ProposalDraft) {
    val c = AutopilotTheme.colors
    Column(Modifier.testTag("review_notices"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        draft.notices.forEach { n ->
            val warn = n.level == NoticeLevel.WARNING
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (warn) c.signalSoft else c.sunken).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (warn) AutopilotIcons.Warn else AutopilotIcons.Info, contentDescription = null, tint = if (warn) c.onSignalSoft else c.ink2, modifier = Modifier.size(18.dp))
                Text(n.text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = if (warn) c.onSignalSoft else c.ink2)
            }
        }
    }
}

/** A labelled field with the provenance edge and tag. */
@Composable
private fun ProvenanceField(
    label: String,
    value: String,
    suggested: Boolean,
    tag: String,
    bold: Boolean = false,
    placeholder: String? = null,
    error: Boolean = false,
    onChange: (String) -> Unit,
) {
    val c = AutopilotTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.weight(1f))
            if (value.isNotBlank()) ProvenanceTag(suggested = suggested)
        }
        OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().provenanceBorder(suggested = suggested, radius = 14.dp).testTag(tag),
            textStyle = if (bold) MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp) else MaterialTheme.typography.bodyLarge,
            placeholder = placeholder?.let { { Text(it, color = c.ink3) } },
            singleLine = false, maxLines = if (bold) 3 else 6, isError = error, shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, errorBorderColor = c.danger,
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, cursorColor = c.ink,
            ),
        )
    }
}

@Composable
private fun OriginalTextCard(text: String, isEdit: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val c = AutopilotTheme.colors
    if (text.isBlank()) return
    Column(Modifier.padding(horizontal = 4.dp).testTag("review_original")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { expanded = !expanded }
                .semantics { contentDescription = if (expanded) "Hide your words" else "Show your words" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (expanded) AutopilotIcons.ChevronDown else AutopilotIcons.ChevronRight, contentDescription = null, tint = c.ink2, modifier = Modifier.size(18.dp))
            Text(if (isEdit) "Your request" else "Your words", style = MaterialTheme.typography.labelLarge, color = c.ink2)
        }
        Text(
            "“$text”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = c.ink2,
            maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp).testTag("review_original_text"),
        )
    }
}

@Composable
private fun StepsCard(draft: ProposalDraft, isEdit: Boolean, update: ((ProposalDraft) -> ProposalDraft) -> Unit) {
    val c = AutopilotTheme.colors
    var newStep by rememberSaveable { mutableStateOf("") }
    val included = draft.steps.count { it.include }
    SurfaceCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), modifier = Modifier.testTag("review_steps")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(if (isEdit) "Steps to add" else "Steps", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            Text("$included of ${draft.steps.size} included", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
        if (draft.steps.isEmpty()) Text(if (isEdit) "No new steps suggested." else "No steps — this is a single action.", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        draft.steps.forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBox(
                    checked = step.include, label = (if (step.include) "Leave out step " else "Include step ") + (i + 1),
                    onToggle = { on -> update { d -> d.copy(steps = d.steps.mapIndexed { j, s -> if (j == i) s.copy(include = on) else s }) } },
                    modifier = Modifier.testTag("review_step_check_$i"),
                )
                OutlinedTextField(
                    value = step.text,
                    onValueChange = { v -> update { d -> d.copy(steps = d.steps.mapIndexed { j, s -> if (j == i) s.copy(text = v.take(ProposalValidator.MAX_STEP), edited = true) else s }) } },
                    modifier = Modifier.weight(1f).provenanceBorder(suggested = !step.edited, radius = 12.dp).semantics { contentDescription = "Step ${i + 1}" }.testTag("review_step_$i"),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (step.include) c.ink else c.ink3, textDecoration = if (step.include) null else TextDecoration.LineThrough),
                    singleLine = false, maxLines = 3, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = c.ink),
                )
                RoundIconButton(AutopilotIcons.Close, contentDescription = "Remove step ${i + 1}", tint = c.ink3, onClick = { update { d -> d.copy(steps = d.steps.filterIndexed { j, _ -> j != i }) } })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, start = 4.dp)) {
            OutlinedTextField(
                value = newStep, onValueChange = { newStep = it.take(ProposalValidator.MAX_STEP) }, placeholder = { Text("Add a step", color = c.ink3) },
                modifier = Modifier.weight(1f).testTag("review_step_new"), singleLine = true, shape = RoundedCornerShape(12.dp),
            )
            RoundIconButton(
                AutopilotIcons.Plus, contentDescription = "Add step",
                onClick = { if (newStep.isNotBlank()) { val t = newStep.trim(); update { d -> d.copy(steps = d.steps + DraftStep(t, edited = true)) }; newStep = "" } },
                tint = if (newStep.isNotBlank()) c.ink else c.ink3,
            )
        }
        if (draft.nextStep.isNotBlank()) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text("Next step", style = MaterialTheme.typography.labelLarge, color = c.ink2)
                Text(draft.nextStep, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("review_next_step"))
                if (!ProposalValidator.nextStepListed(draft)) {
                    ApplyRow("Add it as the first step", draft.addNextStep, "review_add_next_step") { on -> update { it.copy(addNextStep = on) } }
                }
            }
        }
    }
}

@Composable
private fun StepBox(checked: Boolean, label: String, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    Box(
        modifier.size(48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (checked) c.inverse else Color.Transparent)
                .border(2.dp, if (checked) c.inverse else c.lineStrong, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(AutopilotIcons.Check, contentDescription = null, tint = c.onInverse, modifier = Modifier.size(14.dp)) }
    }
}

@Composable
private fun DeadlineCard(draft: ProposalDraft, current: TaskDetails?, isEdit: Boolean, zone: ZoneId, today: LocalDate, update: ((ProposalDraft) -> ProposalDraft) -> Unit) {
    val c = AutopilotTheme.colors
    val d = draft.deadline
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    SurfaceCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), modifier = Modifier.testTag("review_deadline")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(AutopilotIcons.Calendar, contentDescription = null, tint = c.ink2, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                val date = d.localDate
                if (date == null) {
                    Text("No deadline", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = c.ink, modifier = Modifier.testTag("review_deadline_none"))
                    Text(if (d.sourcePhrase.isNotBlank()) "No firm date for “${d.sourcePhrase}”." else "No date in your words.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                } else {
                    Text(
                        "Deadline " + date.format(dayFmt) + (d.localTime?.let { " at ${it.format(hhmm)}" } ?: ""),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = c.ink, modifier = Modifier.testTag("review_deadline_value"),
                    )
                    Text(
                        when {
                            draft.deadlineEdited -> "Added by you"
                            d.provenance == Provenance.EXPLICIT -> "From your words" + (if (d.sourcePhrase.isNotBlank()) ": “${d.sourcePhrase}”" else "")
                            else -> "Suggested date — only set if you turn it on"
                        },
                        style = MaterialTheme.typography.bodySmall, color = if (draft.deadlineEdited || d.provenance == Provenance.EXPLICIT) c.ink2 else c.route,
                        modifier = Modifier.testTag("review_deadline_provenance"),
                    )
                }
            }
            LinkButton(if (d.localDate == null) "Add" else "Change", onClick = { pickDate = true })
        }
        if (isEdit && current != null) DiffLine("Now", currentDeadline(current, zone), Modifier.padding(top = 8.dp))
        if (d.localDate != null) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkButton(if (d.time == null) "Add time" else "Change time", onClick = { pickTime = true })
                if (d.time != null) LinkButton("Date only", onClick = { update { it.copy(deadline = it.deadline.copy(time = null), deadlineEdited = true) } })
                LinkButton("Remove", onClick = { update { it.copy(deadline = it.deadline.copy(date = null, time = null, apply = false), deadlineEdited = true) } })
            }
            ApplyRow(if (isEdit) "Change the deadline to this" else "Set this deadline", d.apply, "review_apply_deadline") { on -> update { it.copy(deadline = it.deadline.copy(apply = on)) } }
        }
    }
    if (pickDate) DateSelectDialog(initial = d.localDate ?: today, onDismiss = { pickDate = false }) { picked ->
        pickDate = false
        // A date the user picked themselves is explicit by definition.
        update { it.copy(deadline = it.deadline.copy(date = picked.toString(), provenance = Provenance.EXPLICIT, apply = true), deadlineEdited = true) }
    }
    if (pickTime) TimeSelectDialog(initial = d.localTime ?: LocalTime.of(17, 0), onDismiss = { pickTime = false }) { t ->
        pickTime = false
        update { it.copy(deadline = it.deadline.copy(time = t.format(hhmm)), deadlineEdited = true) }
    }
}

/** The draft reminder as a policy, so the preview uses the engine's own planner. */
private fun DraftReminder.toPolicy(now: Instant, zone: ZoneId): ReminderPolicy? {
    val base = ReminderPolicy(id = "", taskId = "", lastEditedAt = now, enabled = true)
    return when (kind) {
        DraftReminderKind.OFF -> null
        DraftReminderKind.ONCE -> onceLocal?.let { base.copy(kind = ReminderKind.ONCE, atInstant = LocalTimeResolver.resolve(it.toLocalDate(), it.toLocalTime(), zone)) }
        DraftReminderKind.DAILY -> base.copy(kind = ReminderKind.DAILY_TIMES, dayInterval = 1, times = localTimes, startLocalDate = startLocalDate)
        DraftReminderKind.EVERY_N_DAYS -> base.copy(kind = ReminderKind.EVERY_N_DAYS, dayInterval = dayInterval.coerceAtLeast(2), times = localTimes, startLocalDate = startLocalDate)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderCard(draft: ProposalDraft, current: TaskDetails?, isEdit: Boolean, zone: ZoneId, today: LocalDate, now: Instant, update: ((ProposalDraft) -> ProposalDraft) -> Unit) {
    val c = AutopilotTheme.colors
    val r = draft.reminder
    var editTime by remember { mutableStateOf<Int?>(null) }
    var addTime by remember { mutableStateOf(false) }
    var pickOnce by remember { mutableStateOf(false) }
    var fineTune by rememberSaveable { mutableStateOf(false) }
    fun setReminder(transform: (DraftReminder) -> DraftReminder) = update { it.copy(reminder = transform(it.reminder), reminderEdited = true) }
    val suggested = !draft.reminderEdited
    val provenanceText = when {
        draft.reminderEdited -> "Edited by you"
        r.provenance == Provenance.EXPLICIT -> "From your words"
        else -> "Suggested"
    }
    val settingsForPreview = remember { com.lifeadmin.autopilot.domain.model.AppSettings() }
    val preview = remember(r, now, zone) { r.toPolicy(now, zone)?.let { ReminderProjection.upcomingForNewTask(it, settingsForPreview, now, zone, 3) }.orEmpty() }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).provenanceBorder(suggested = suggested && r.kind != DraftReminderKind.OFF, radius = 20.dp).padding(16.dp).testTag("review_reminders"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(AutopilotIcons.Bell, contentDescription = null, tint = c.signal, modifier = Modifier.size(20.dp))
            Text("Follow-up", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(start = 8.dp).weight(1f).semantics { heading() })
            if (r.kind != DraftReminderKind.OFF) ProvenanceTag(suggested = suggested, suggestedText = provenanceText, modifier = Modifier.testTag("review_reminder_provenance"))
        }
        if (isEdit && current != null) DiffLine("Now", PolicyDescriber.describe(current.policy, zone))
        if (r.requestedPhrase.isNotBlank() && !draft.reminderEdited && r.kind != DraftReminderKind.OFF) {
            Text(buildString { append("You said “"); append(r.requestedPhrase); append("”") }, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2)
        }
        if (r.provenance == Provenance.EXPLICIT && r.kind != DraftReminderKind.OFF) Text("You asked for this", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        r.unsupported?.let { u ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.signalSoft).padding(12.dp).testTag("review_unsupported")) {
                Text("“${u.description}” isn't supported yet", style = MaterialTheme.typography.titleSmall, color = c.onSignalSoft)
                Text("This app can nudge once, every day at set times, or every N days. Nothing has been changed to a different schedule without you.", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.onSignalSoft)
                SecondaryButton(
                    "Use “${u.alternativeLabel}” instead",
                    onClick = { setReminder { it.copy(kind = u.alternativeKind, times = u.alternativeTimes, dayInterval = u.alternativeInterval, startDate = u.alternativeStartDate ?: it.startDate, apply = true, provenance = Provenance.EXPLICIT) } },
                    modifier = Modifier.padding(top = 8.dp).testTag("review_use_alternative"), minHeight = 44.dp,
                )
            }
        }
        // Times + cadence sentence
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("review_times")) {
            when (r.kind) {
                DraftReminderKind.OFF -> Text(if (r.unsupported == null) "No nudges." else "No nudges unless you choose a supported schedule.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                DraftReminderKind.ONCE -> {
                    Box(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(c.signalSoft).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        TimeText(r.onceLocal?.format(dayTimeFmt) ?: "Pick a date and time", size = 15, color = c.onSignalSoft, modifier = Modifier.testTag("review_once_at"))
                    }
                    Text("once", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.align(Alignment.CenterVertically))
                }
                DraftReminderKind.DAILY, DraftReminderKind.EVERY_N_DAYS -> {
                    r.times.forEachIndexed { i, t ->
                        Box(
                            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(c.signalSoft).clickable(role = Role.Button, onClickLabel = "Change $t") { editTime = i }.padding(horizontal = 14.dp).testTag("review_time_$i"),
                            contentAlignment = Alignment.Center,
                        ) { TimeText(t, size = 17, color = c.onSignalSoft) }
                    }
                    Text(
                        if (r.kind == DraftReminderKind.DAILY) "every day, until you mark it done" else "every ${r.dayInterval} days, until done",
                        style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
        // Interpretation callout: sits inside the block it explains.
        val interpretation = draft.assumptions.firstOrNull { it.contains("reminder", ignoreCase = true) || it.contains("time", ignoreCase = true) || it.contains("read ", ignoreCase = true) }
        if (interpretation != null && !draft.interpretationConfirmed && !draft.reminderEdited && r.kind != DraftReminderKind.OFF) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.routeSoft).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(AutopilotIcons.Info, contentDescription = null, tint = c.onRouteSoft, modifier = Modifier.size(18.dp))
                    Text(interpretation, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.onRouteSoft)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Looks right", onClick = { update { it.copy(interpretationConfirmed = true) } }, minHeight = 40.dp, modifier = Modifier.testTag("review_interp_ok"))
                    SecondaryButton("Change", onClick = { fineTune = true }, minHeight = 40.dp)
                }
            }
        }
        Text(
            if (preview.isEmpty()) "Nothing will ring for this task." else "First nudges: " + preview.joinToString(" · ") { Format.relative(it.plannedAt, now, zone) },
            style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("review_preview"),
        )
        LinkButton(if (fineTune) "Hide schedule options" else "Adjust follow-up", onClick = { fineTune = !fineTune })
        if (fineTune) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(DraftReminderKind.OFF to "Off", DraftReminderKind.ONCE to "Once", DraftReminderKind.DAILY to "Every day", DraftReminderKind.EVERY_N_DAYS to "Every N days").forEach { (k, label) ->
                    ChoiceChip(
                        label, selected = r.kind == k,
                        onClick = {
                            setReminder { cur ->
                                when (k) {
                                    DraftReminderKind.OFF -> cur.copy(kind = k, apply = false)
                                    DraftReminderKind.ONCE -> cur.copy(kind = k, onceAt = cur.onceAt ?: LocalDateTime.of(today.plusDays(1), LocalTime.of(9, 0)).toString(), apply = true)
                                    DraftReminderKind.DAILY -> cur.copy(kind = k, dayInterval = 1, times = cur.times.ifEmpty { listOf("09:00") }, apply = true)
                                    DraftReminderKind.EVERY_N_DAYS -> cur.copy(kind = k, dayInterval = cur.dayInterval.coerceAtLeast(2), times = cur.times.ifEmpty { listOf("09:00") }, apply = true)
                                }
                            }
                        },
                        modifier = Modifier.testTag("review_reminder_kind_${k.name.lowercase()}"),
                    )
                }
            }
            when (r.kind) {
                DraftReminderKind.ONCE -> LinkButton("Change date and time…", onClick = { pickOnce = true })
                DraftReminderKind.DAILY, DraftReminderKind.EVERY_N_DAYS -> {
                    if (r.kind == DraftReminderKind.EVERY_N_DAYS) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Every", style = MaterialTheme.typography.bodyMedium)
                        SecondaryButton("−", onClick = { setReminder { it.copy(dayInterval = (it.dayInterval - 1).coerceAtLeast(2)) } }, minHeight = 40.dp)
                        Text("${r.dayInterval} days", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("review_interval"))
                        SecondaryButton("+", onClick = { setReminder { it.copy(dayInterval = (it.dayInterval + 1).coerceAtMost(365)) } }, minHeight = 40.dp)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.times.forEachIndexed { i, t ->
                            ChoiceChip("Remove $t", selected = false, onClick = { setReminder { cur -> cur.copy(times = cur.times.filterIndexed { j, _ -> j != i }) } }, modifier = Modifier.testTag("review_time_remove_$i"))
                        }
                        ChoiceChip("Add time", selected = false, onClick = { addTime = true }, modifier = Modifier.testTag("review_time_add"))
                    }
                }
                DraftReminderKind.OFF -> Unit
            }
        }
        Text("Keeps nudging until you mark the task done. Dismissing a notification doesn't complete it.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        if (r.kind != DraftReminderKind.OFF) {
            ApplyRow(if (isEdit) "Replace the current reminders with this" else "Turn on these nudges", r.apply, "review_apply_reminders") { on -> update { it.copy(reminder = it.reminder.copy(apply = on)) } }
        }
    }

    editTime?.let { i ->
        val initial = r.localTimes.getOrNull(i) ?: LocalTime.of(9, 0)
        TimeSelectDialog(initial = runCatching { LocalTime.parse(r.times[i]) }.getOrDefault(initial), onDismiss = { editTime = null }) { t ->
            editTime = null
            setReminder { cur -> cur.copy(times = cur.times.mapIndexed { j, s -> if (j == i) t.format(hhmm) else s }.distinct().sorted()) }
        }
    }
    if (addTime) TimeSelectDialog(initial = LocalTime.of(12, 0), onDismiss = { addTime = false }) { t ->
        addTime = false
        setReminder { cur -> cur.copy(times = (cur.times + t.format(hhmm)).distinct().sorted().take(ProposalValidator.MAX_TIMES)) }
    }
    if (pickOnce) {
        val at = r.onceLocal ?: LocalDateTime.of(today.plusDays(1), LocalTime.of(9, 0))
        DateTimeSelectDialog(initialDate = at.toLocalDate(), initialTime = at.toLocalTime(), onDismiss = { pickOnce = false }) { d, t ->
            pickOnce = false
            setReminder { it.copy(onceAt = LocalDateTime.of(d, t).toString(), apply = true, provenance = Provenance.EXPLICIT) }
        }
    }
}

internal fun currentDeadline(d: TaskDetails, zone: ZoneId): String = when (d.task.deadlineType) {
    DeadlineType.NONE -> "No deadline"
    DeadlineType.DATE_ONLY -> d.task.deadlineLocalDate?.format(dayFmt) ?: "No deadline"
    DeadlineType.EXACT -> d.task.deadlineInstant?.atZone(zone)?.format(dayTimeFmt) ?: "No deadline"
}

@Composable
internal fun ReviewCard(title: String, tag: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    SurfaceCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), modifier = Modifier.testTag(tag)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp).semantics { heading() })
        content()
    }
}

@Composable
private fun DiffLine(label: String, value: String, modifier: Modifier = Modifier) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink2, modifier = modifier.padding(bottom = 4.dp))
}

@Composable
private fun ApplyRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(top = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        InkSwitch(checked = checked)
    }
}
