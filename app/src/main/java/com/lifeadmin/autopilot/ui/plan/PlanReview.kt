package com.lifeadmin.autopilot.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
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
import com.lifeadmin.autopilot.domain.scheduling.PolicyDescriber
import com.lifeadmin.autopilot.ui.components.DateSelectDialog
import com.lifeadmin.autopilot.ui.components.DateTimeSelectDialog
import com.lifeadmin.autopilot.ui.components.TimeSelectDialog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
private val dayTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())
private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/** All review sections, as LazyColumn items. Edits go through [update]; nothing is saved here. */
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.reviewItems(
    draft: ProposalDraft,
    current: TaskDetails?,
    zone: ZoneId,
    today: LocalDate,
    update: ((ProposalDraft) -> ProposalDraft) -> Unit,
) {
    val isEdit = draft.mode == PlanningMode.FOLLOW_THROUGH
    item { OriginalTextCard(draft.originalText, isEdit) }
    if (draft.advice.isNotBlank()) item {
        ReviewCard("Suggestion", tag = "review_advice") { Text(draft.advice, style = MaterialTheme.typography.bodyLarge) }
    }
    if (draft.notices.isNotEmpty()) item {
        Column(Modifier.padding(horizontal = 12.dp).testTag("review_notices")) {
            draft.notices.forEach { n ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (n.level == NoticeLevel.WARNING) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        n.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp),
                        color = if (n.level == NoticeLevel.WARNING) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // Title and notes
    item {
        ReviewCard(if (isEdit) "Title" else "Task", tag = "review_task") {
            if (isEdit && current != null) {
                if (draft.title.trim() == current.task.title) Text("Keeps the current title: ${current.task.title}", style = MaterialTheme.typography.bodyMedium)
                else {
                    DiffLine("Now", current.task.title)
                    ApplyRow("Use the suggested title", draft.applyTitle, "review_apply_title") { on -> update { it.copy(applyTitle = on) } }
                    if (draft.applyTitle) TitleField(draft, update)
                }
            } else {
                TitleField(draft, update)
                OutlinedTextField(
                    value = draft.notes, onValueChange = { v -> update { it.copy(notes = v.take(ProposalValidator.MAX_NOTES)) } },
                    label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("review_notes"), minLines = 1, maxLines = 6,
                )
            }
        }
    }
    if (isEdit && current != null && draft.notes.isNotBlank() && draft.notes.trim() != current.task.notes.orEmpty()) item {
        ReviewCard("Notes", tag = "review_notes_diff") {
            DiffLine("Now", current.task.notes ?: "(none)")
            ApplyRow("Replace notes with the suggestion", draft.applyNotes, "review_apply_notes") { on -> update { it.copy(applyNotes = on) } }
            if (draft.applyNotes) OutlinedTextField(
                value = draft.notes, onValueChange = { v -> update { it.copy(notes = v.take(ProposalValidator.MAX_NOTES)) } },
                label = { Text("New notes") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 6,
            )
        }
    }

    // Checklist
    item { StepsCard(draft, isEdit, update) }

    // Deadline
    item { DeadlineCard(draft, current, isEdit, zone, today, update) }

    // Reminders
    item { ReminderCard(draft, current, isEdit, zone, today, update) }

    // Waiting
    if (draft.waiting.apply || draft.waiting.waitingFor.isNotBlank()) item {
        ReviewCard("Waiting on someone", tag = "review_waiting") {
            Text(
                "This sounds event-triggered" + (draft.waiting.waitingFor.takeIf { it.isNotBlank() }?.let { ": waiting for $it" } ?: "") +
                    ". A Waiting task doesn't send its regular reminders" + (draft.waiting.checkInLocal?.let { "; you'll be reminded to check in ${it.format(dayTimeFmt)}." } ?: "; no check-in date is set, so add one if you want a nudge."),
                style = MaterialTheme.typography.bodyMedium,
            )
            ApplyRow("Mark as Waiting", draft.waiting.apply, "review_apply_waiting") { on -> update { it.copy(waiting = it.waiting.copy(apply = on)) } }
        }
    }

    // Priority
    if (draft.priority != null) item {
        ReviewCard("Priority", tag = "review_priority") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProvenanceChip("Suggested")
                Text(
                    draft.priority.lowercase().replaceFirstChar { it.uppercase() } + (draft.priorityReason.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp),
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
private fun TitleField(draft: ProposalDraft, update: ((ProposalDraft) -> ProposalDraft) -> Unit) {
    OutlinedTextField(
        value = draft.title, onValueChange = { v -> update { it.copy(title = v.take(ProposalValidator.MAX_TITLE)) } },
        label = { Text("Title") }, modifier = Modifier.fillMaxWidth().testTag("review_title"), singleLine = false, maxLines = 3,
        isError = draft.title.isBlank(),
    )
}

@Composable
private fun OriginalTextCard(text: String, isEdit: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (text.isBlank()) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("review_original"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(if (isEdit) "Your request" else "Your words", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                "“$text”", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = if (expanded) Int.MAX_VALUE else 4, modifier = Modifier.padding(top = 4.dp).testTag("review_original_text"),
            )
            if (text.length > 160) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show all") }
        }
    }
}

@Composable
private fun StepsCard(draft: ProposalDraft, isEdit: Boolean, update: ((ProposalDraft) -> ProposalDraft) -> Unit) {
    var newStep by rememberSaveable { mutableStateOf("") }
    val included = draft.steps.count { it.include }
    ReviewCard(if (isEdit) "Steps to add · $included" else "Checklist · $included", tag = "review_steps") {
        if (draft.steps.isEmpty()) Text(if (isEdit) "No new steps suggested." else "No steps — this is a single action.", style = MaterialTheme.typography.bodyMedium)
        draft.steps.forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = step.include, onCheckedChange = { on -> update { d -> d.copy(steps = d.steps.mapIndexed { j, s -> if (j == i) s.copy(include = on) else s }) } },
                    modifier = Modifier.testTag("review_step_check_$i").semantics { contentDescription = "Include step ${i + 1}" },
                )
                OutlinedTextField(
                    value = step.text,
                    onValueChange = { v -> update { d -> d.copy(steps = d.steps.mapIndexed { j, s -> if (j == i) s.copy(text = v.take(ProposalValidator.MAX_STEP)) else s }) } },
                    modifier = Modifier.weight(1f).testTag("review_step_$i"), singleLine = false, maxLines = 3,
                )
                IconButton(onClick = { update { d -> d.copy(steps = d.steps.filterIndexed { j, _ -> j != i }) } }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove step ${i + 1}")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedTextField(
                value = newStep, onValueChange = { newStep = it.take(ProposalValidator.MAX_STEP) }, label = { Text("Add a step") },
                modifier = Modifier.weight(1f).testTag("review_step_new"), singleLine = true,
            )
            IconButton(
                onClick = { if (newStep.isNotBlank()) { val t = newStep.trim(); update { d -> d.copy(steps = d.steps + DraftStep(t)) }; newStep = "" } },
                enabled = newStep.isNotBlank(),
            ) { Icon(Icons.Filled.Add, contentDescription = "Add step") }
        }
        if (draft.nextStep.isNotBlank()) {
            Text("Next step", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Text(draft.nextStep, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("review_next_step"))
            if (!ProposalValidator.nextStepListed(draft)) {
                ApplyRow("Add it as the first step", draft.addNextStep, "review_add_next_step") { on -> update { it.copy(addNextStep = on) } }
            }
        }
    }
}

@Composable
private fun DeadlineCard(draft: ProposalDraft, current: TaskDetails?, isEdit: Boolean, zone: ZoneId, today: LocalDate, update: ((ProposalDraft) -> ProposalDraft) -> Unit) {
    val d = draft.deadline
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    ReviewCard("Deadline", tag = "review_deadline") {
        if (isEdit && current != null) DiffLine("Now", currentDeadline(current, zone))
        val date = d.localDate
        if (date == null) {
            Text(
                if (d.sourcePhrase.isNotBlank()) "No firm date for “${d.sourcePhrase}”." else "No deadline in your words.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("review_deadline_none"),
            )
            TextButton(onClick = { pickDate = true }) { Text("Set a deadline…") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProvenanceChip(provenanceLabel(d.provenance), testTag = "review_deadline_provenance")
                Text(
                    date.format(dayFmt) + (d.localTime?.let { " at ${it.format(hhmm)}" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp).testTag("review_deadline_value"),
                )
            }
            if (d.sourcePhrase.isNotBlank()) Text("From “${d.sourcePhrase}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (d.provenance != Provenance.EXPLICIT) {
                Text("This date is only a suggestion — it's not set unless you turn it on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pickDate = true }) { Text("Change date") }
                TextButton(onClick = { pickTime = true }) { Text(if (d.time == null) "Add time" else "Change time") }
                if (d.time != null) TextButton(onClick = { update { it.copy(deadline = it.deadline.copy(time = null)) } }) { Text("Date only") }
            }
            ApplyRow(if (isEdit) "Change the deadline to this" else "Set this deadline", d.apply, "review_apply_deadline") { on -> update { it.copy(deadline = it.deadline.copy(apply = on)) } }
        }
    }
    if (pickDate) DateSelectDialog(initial = d.localDate ?: today, onDismiss = { pickDate = false }) { picked ->
        pickDate = false
        // A date the user picked themselves is explicit by definition.
        update { it.copy(deadline = it.deadline.copy(date = picked.toString(), provenance = Provenance.EXPLICIT, apply = true)) }
    }
    if (pickTime) TimeSelectDialog(initial = d.localTime ?: LocalTime.of(17, 0), onDismiss = { pickTime = false }) { t ->
        pickTime = false
        update { it.copy(deadline = it.deadline.copy(time = t.format(hhmm))) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderCard(draft: ProposalDraft, current: TaskDetails?, isEdit: Boolean, zone: ZoneId, today: LocalDate, update: ((ProposalDraft) -> ProposalDraft) -> Unit) {
    val r = draft.reminder
    var editTime by remember { mutableStateOf<Int?>(null) }
    var addTime by remember { mutableStateOf(false) }
    var pickOnce by remember { mutableStateOf(false) }
    fun setReminder(transform: (DraftReminder) -> DraftReminder) = update { it.copy(reminder = transform(it.reminder)) }

    ReviewCard("Reminders", tag = "review_reminders") {
        if (isEdit && current != null) DiffLine("Now", PolicyDescriber.describe(current.policy, zone))
        r.unsupported?.let { u ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("review_unsupported")) {
                Column(Modifier.padding(12.dp)) {
                    Text("“${u.description}” isn't supported yet", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "This app can remind you once, every day at set times, or every N days. Nothing has been changed to a different schedule without you.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    OutlinedButton(
                        onClick = { setReminder { it.copy(kind = u.alternativeKind, times = u.alternativeTimes, dayInterval = u.alternativeInterval, apply = true, provenance = Provenance.EXPLICIT) } },
                        modifier = Modifier.padding(top = 6.dp).testTag("review_use_alternative"),
                    ) { Text("Use “${u.alternativeLabel}” instead") }
                }
            }
        }
        Text("Schedule", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(DraftReminderKind.OFF to "Off", DraftReminderKind.ONCE to "Once", DraftReminderKind.DAILY to "Every day", DraftReminderKind.EVERY_N_DAYS to "Every N days").forEach { (k, label) ->
                FilterChip(
                    selected = r.kind == k,
                    onClick = {
                        setReminder { cur ->
                            when (k) {
                                DraftReminderKind.OFF -> cur.copy(kind = k, apply = false)
                                DraftReminderKind.ONCE -> cur.copy(kind = k, onceAt = cur.onceAt ?: LocalDateTime.of(today.plusDays(1), LocalTime.of(9, 0)).toString())
                                DraftReminderKind.DAILY -> cur.copy(kind = k, dayInterval = 1, times = cur.times.ifEmpty { listOf("09:00") })
                                DraftReminderKind.EVERY_N_DAYS -> cur.copy(kind = k, dayInterval = cur.dayInterval.coerceAtLeast(2), times = cur.times.ifEmpty { listOf("09:00") })
                            }
                        }
                    },
                    label = { Text(label) },
                    modifier = Modifier.testTag("review_reminder_kind_${k.name.lowercase()}"),
                )
            }
        }
        when (r.kind) {
            DraftReminderKind.OFF -> Text(
                if (r.unsupported == null) "No reminders." else "No reminders unless you choose a supported schedule.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
            )
            DraftReminderKind.ONCE -> {
                val at = r.onceLocal
                Text(at?.format(dayTimeFmt) ?: "Pick a date and time", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp).testTag("review_once_at"))
                TextButton(onClick = { pickOnce = true }) { Text("Change…") }
            }
            DraftReminderKind.DAILY, DraftReminderKind.EVERY_N_DAYS -> {
                if (r.kind == DraftReminderKind.EVERY_N_DAYS) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every", modifier = Modifier.padding(end = 6.dp))
                    OutlinedButton(onClick = { setReminder { it.copy(dayInterval = (it.dayInterval - 1).coerceAtLeast(2)) } }) { Text("−") }
                    Text("${r.dayInterval} days", modifier = Modifier.padding(horizontal = 8.dp).testTag("review_interval"))
                    OutlinedButton(onClick = { setReminder { it.copy(dayInterval = (it.dayInterval + 1).coerceAtMost(365)) } }) { Text("+") }
                }
                Text("Times", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("review_times")) {
                    r.times.forEachIndexed { i, t ->
                        InputChip(
                            selected = true, onClick = { editTime = i }, label = { Text(t) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close, contentDescription = "Remove $t",
                                    modifier = Modifier.toggleable(value = false, role = Role.Button, onValueChange = { setReminder { c -> c.copy(times = c.times.filterIndexed { j, _ -> j != i }) } }),
                                )
                            },
                            modifier = Modifier.testTag("review_time_$i"),
                        )
                    }
                    AssistChip(onClick = { addTime = true }, label = { Text("Add time") }, leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) }, modifier = Modifier.testTag("review_time_add"))
                }
                Text(
                    "Keeps reminding until you mark the task Done. Dismissing a notification doesn't complete it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (r.kind != DraftReminderKind.OFF) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                ProvenanceChip(if (r.provenance == Provenance.EXPLICIT) "You asked for this" else "Suggested", testTag = "review_reminder_provenance")
                if (r.requestedPhrase.isNotBlank()) Text("“${r.requestedPhrase}”", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }
            ApplyRow(if (isEdit) "Replace the current reminders with this" else "Turn on these reminders", r.apply, "review_apply_reminders") { on -> setReminder { it.copy(apply = on) } }
        }
    }

    editTime?.let { i ->
        val initial = r.localTimes.getOrNull(i) ?: LocalTime.of(9, 0)
        TimeSelectDialog(initial = runCatching { LocalTime.parse(r.times[i]) }.getOrDefault(initial), onDismiss = { editTime = null }) { t ->
            editTime = null
            setReminder { c -> c.copy(times = c.times.mapIndexed { j, s -> if (j == i) t.format(hhmm) else s }.distinct().sorted()) }
        }
    }
    if (addTime) TimeSelectDialog(initial = LocalTime.of(12, 0), onDismiss = { addTime = false }) { t ->
        addTime = false
        setReminder { c -> c.copy(times = (c.times + t.format(hhmm)).distinct().sorted().take(ProposalValidator.MAX_TIMES)) }
    }
    if (pickOnce) {
        val at = r.onceLocal ?: LocalDateTime.of(today.plusDays(1), LocalTime.of(9, 0))
        DateTimeSelectDialog(initialDate = at.toLocalDate(), initialTime = at.toLocalTime(), onDismiss = { pickOnce = false }) { d, t ->
            pickOnce = false
            setReminder { it.copy(onceAt = LocalDateTime.of(d, t).toString(), apply = true, provenance = Provenance.EXPLICIT) }
        }
    }
}

private fun currentDeadline(d: TaskDetails, zone: ZoneId): String = when (d.task.deadlineType) {
    DeadlineType.NONE -> "No deadline"
    DeadlineType.DATE_ONLY -> d.task.deadlineLocalDate?.format(dayFmt) ?: "No deadline"
    DeadlineType.EXACT -> d.task.deadlineInstant?.atZone(zone)?.format(dayTimeFmt) ?: "No deadline"
}

private fun provenanceLabel(p: Provenance): String = when (p) {
    Provenance.EXPLICIT -> "From your words"
    Provenance.INFERRED -> "Suggested date"
    Provenance.SUGGESTED -> "Suggested"
    Provenance.UNKNOWN -> "Unknown"
}

@Composable
private fun ReviewCard(title: String, tag: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp).semantics { heading() })
            content()
        }
    }
}

@Composable
private fun DiffLine(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun ProvenanceChip(text: String, testTag: String? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun ApplyRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(top = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
