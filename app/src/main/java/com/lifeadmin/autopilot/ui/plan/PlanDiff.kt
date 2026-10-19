package com.lifeadmin.autopilot.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.ai.DraftReminder
import com.lifeadmin.autopilot.ai.DraftReminderKind
import com.lifeadmin.autopilot.ai.ProposalDraft
import com.lifeadmin.autopilot.ai.ProposalValidator
import com.lifeadmin.autopilot.ai.Provenance
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.domain.scheduling.PolicyDescriber
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.KindTag
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.Instant
import java.time.ZoneId

/** One proposed change on an existing task, shown as a checkable card. */
private data class DiffCardSpec(
    val kind: String,
    val title: String,
    val description: String?,
    val selected: Boolean,
    val tag: String,
    val onToggle: (Boolean) -> Unit,
)

/**
 * "Move this forward" review: every suggestion is a card with an uppercase kind label; selected
 * cards are route-tinted. A tip that changes nothing stays a plain tip. Toggling only edits the
 * draft; the task changes when the user taps Apply.
 */
fun LazyListScope.followThroughItems(
    draft: ProposalDraft,
    current: TaskDetails?,
    zone: ZoneId,
    now: Instant,
    update: ((ProposalDraft) -> ProposalDraft) -> Unit,
) {
    item { Intro(current?.task?.title ?: draft.title) }
    if (draft.notices.isNotEmpty()) item { Notices(draft) }

    val cards = buildList {
        if (current != null && draft.title.trim() != current.task.title) add(
            DiffCardSpec("Rename", draft.title.trim(), "Now: ${current.task.title}", draft.applyTitle, "review_apply_title") { on -> update { it.copy(applyTitle = on) } },
        )
        if (current != null && draft.notes.isNotBlank() && draft.notes.trim() != current.task.notes.orEmpty()) add(
            DiffCardSpec("Update notes", draft.notes.trim(), current.task.notes?.takeIf { it.isNotBlank() }?.let { "Now: $it" } ?: "No notes yet", draft.applyNotes, "review_apply_notes") { on -> update { it.copy(applyNotes = on) } },
        )
        val next = draft.nextStep.trim()
        draft.steps.forEachIndexed { i, step ->
            val first = next.isNotEmpty() && step.text.trim().equals(next, ignoreCase = true)
            add(
                DiffCardSpec(if (first) "Add step · First" else "Add step", step.text, null, step.include, "review_step_check_$i") { on ->
                    update { d -> d.copy(steps = d.steps.mapIndexed { j, s -> if (j == i) s.copy(include = on) else s }) }
                },
            )
        }
        if (next.isNotEmpty() && !ProposalValidator.nextStepListed(draft)) add(
            DiffCardSpec("Add step · First", next, "Suggested as the next move", draft.addNextStep, "review_add_next_step") { on -> update { it.copy(addNextStep = on) } },
        )
        val dl = draft.deadline
        if (dl.localDate != null) add(
            DiffCardSpec(
                "Set deadline",
                (current?.let { currentDeadline(it, zone) } ?: "None") + " → " + dl.localDate!!.format(dayFmt) + (dl.localTime?.let { " at ${it.format(hhmm)}" } ?: ""),
                when {
                    dl.provenance == Provenance.EXPLICIT && dl.sourcePhrase.isNotBlank() -> "From your words: “${dl.sourcePhrase}”"
                    dl.sourcePhrase.isNotBlank() -> dl.sourcePhrase
                    else -> "Suggested date"
                },
                dl.apply, "review_apply_deadline",
            ) { on -> update { it.copy(deadline = it.deadline.copy(apply = on)) } },
        )
        val r = draft.reminder
        if (r.kind != DraftReminderKind.OFF) add(
            DiffCardSpec(
                "Change follow-up",
                (current?.let { PolicyDescriber.describe(it.policy, zone) } ?: "No nudges") + " → " + describe(r),
                r.requestedPhrase.takeIf { it.isNotBlank() }?.let { "You said “$it”" } ?: "Keeps nudging until you mark the task done",
                r.apply, "review_apply_reminders",
            ) { on -> update { it.copy(reminder = it.reminder.copy(apply = on)) } },
        )
        val w = draft.waiting
        if (w.apply || w.waitingFor.isNotBlank()) add(
            DiffCardSpec(
                "Mark as waiting",
                w.waitingFor.takeIf { it.isNotBlank() }?.let { "Waiting on $it" } ?: "Waiting",
                w.checkInLocal?.let { "Check in ${it.format(dayTimeFmt)}" } ?: "No check-in date yet — add one so it can't quietly stall",
                w.apply, "review_apply_waiting",
            ) { on -> update { it.copy(waiting = it.waiting.copy(apply = on)) } },
        )
        if (draft.priority != null) add(
            DiffCardSpec(
                "Set priority", draft.priority.lowercase().replaceFirstChar { it.uppercase() },
                draft.priorityReason.takeIf { it.isNotBlank() }, draft.applyPriority, "review_apply_priority",
            ) { on -> update { it.copy(applyPriority = on) } },
        )
    }

    if (cards.isEmpty()) item { NothingToChange() }
    cards.forEach { spec -> item(key = spec.tag) { DiffCard(spec) } }
    if (draft.advice.isNotBlank()) item { TipCard(draft.advice) }

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

private fun describe(r: DraftReminder): String = when (r.kind) {
    DraftReminderKind.OFF -> "no nudges"
    DraftReminderKind.ONCE -> "once, " + (r.onceLocal?.format(dayTimeFmt) ?: "time to pick")
    DraftReminderKind.DAILY -> r.times.joinToString(" & ") + " daily"
    DraftReminderKind.EVERY_N_DAYS -> "every ${r.dayInterval} days at " + r.times.joinToString(" & ")
}

@Composable
private fun Intro(taskTitle: String) {
    val c = AutopilotTheme.colors
    Text(
        buildString { append("Suggestions for "); append(taskTitle); append(". Choose what to apply — nothing changes until you do.") },
        style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(horizontal = 2.dp).testTag("review_intro"),
    )
}

@Composable
private fun NothingToChange() {
    val c = AutopilotTheme.colors
    SurfaceCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), modifier = Modifier.testTag("review_nothing")) {
        Text("Nothing to change", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text("Claude didn't find a step, date or follow-up worth adding. The task stays exactly as it is.", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DiffCard(spec: DiffCardSpec) {
    val c = AutopilotTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (spec.selected) c.routeSoft else c.surface)
            .border(1.5.dp, if (spec.selected) c.route else c.line, shape)
            .toggleable(value = spec.selected, role = Role.Checkbox, onValueChange = spec.onToggle)
            .semantics { contentDescription = "${spec.kind}: ${spec.title}" + (spec.description?.let { ". $it" } ?: "") }
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .testTag(spec.tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(22.dp).clip(RoundedCornerShape(6.dp))
                .background(if (spec.selected) c.route else Color.Transparent)
                .border(2.dp, if (spec.selected) c.route else c.lineStrong, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { if (spec.selected) Icon(AutopilotIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            KindTag(spec.kind, color = if (spec.selected) c.route else c.ink2)
            Text(spec.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp), color = c.ink)
            spec.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink2) }
        }
    }
}
