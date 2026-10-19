package com.lifeadmin.autopilot.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.SnoozePreset
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver
import com.lifeadmin.autopilot.domain.scheduling.PlannedReminder
import com.lifeadmin.autopilot.domain.scheduling.SnoozeCalculator
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.DateTimeSelectDialog
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.GroupDivider
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One snooze choice: label, resulting instant, and the clock text shown on the right. */
internal data class SnoozeOption(val label: String, val at: Instant, val icon: ImageVector)

/** Concrete choices (resulting clock times, not durations to compute); all strictly in the future. */
internal fun snoozeOptions(now: Instant, zone: ZoneId): List<SnoozeOption> {
    val local = now.atZone(zone)
    val today = local.toLocalDate()
    val evening = LocalTimeResolver.resolve(today, LocalTime.of(18, 0), zone)
    val nextMonday = LocalTimeResolver.resolve(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), LocalTime.of(9, 0), zone)
    return listOfNotNull(
        SnoozeOption("In 1 hour", SnoozeCalculator.target(SnoozePreset.ONE_HOUR, now, zone), AutopilotIcons.Clock),
        SnoozeOption("In 3 hours", SnoozeCalculator.target(SnoozePreset.THREE_HOURS, now, zone), AutopilotIcons.Clock),
        if (evening.isAfter(now.plusSeconds(15 * 60))) SnoozeOption("This evening", evening, AutopilotIcons.Moon) else null,
        SnoozeOption("Tomorrow morning", SnoozeCalculator.target(SnoozePreset.TOMORROW_MORNING, now, zone), AutopilotIcons.Sun),
        SnoozeOption("Next week", nextMonday, AutopilotIcons.Calendar),
    )
}

/**
 * Snooze sheet: each option shows the resulting time, the exact next reminder the engine would
 * plan is previewed before confirming, and "Remind me when the snooze ends" is kept.
 */
@Composable
fun SnoozeSheet(
    now: Instant,
    zone: ZoneId,
    preview: suspend (Instant, Boolean) -> PlannedReminder?,
    onDismiss: () -> Unit,
    onConfirm: (Instant, Boolean) -> Unit,
) {
    val c = AutopilotTheme.colors
    val options = remember(now, zone) { snoozeOptions(now, zone) }
    var until by remember { mutableStateOf(options.first { it.label == "Tomorrow morning" }.at) }
    var remind by remember { mutableStateOf(true) }
    var custom by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf<PlannedReminder?>(null) }
    var computed by remember { mutableStateOf(false) }
    LaunchedEffect(until, remind) {
        computed = false
        val result = runCatching { preview(until, remind) }.getOrNull()
        withContext(Dispatchers.Main.immediate) { next = result; computed = true }
    }
    AutopilotSheet(title = "Snooze until…", onDismiss = onDismiss) {
        Text("Quiet until then. After that, the normal follow-up resumes.", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, modifier = Modifier.padding(bottom = 6.dp))
        Column(Modifier.selectableGroup()) {
            options.forEachIndexed { i, o ->
                SnoozeRow(o.icon, o.label, Format.time(o.at, zone) + if (o.at.atZone(zone).toLocalDate() != now.atZone(zone).toLocalDate()) " · ${Format.date(o.at.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())}" else "", selected = until == o.at, divider = i > 0) { until = o.at }
            }
            val isCustom = options.none { it.at == until }
            SnoozeRow(AutopilotIcons.Calendar, if (isCustom) "Custom" else "Pick a date & time", if (isCustom) Format.relative(until, now, zone) else "", selected = isCustom, divider = true) { custom = true }
        }
        Row(
            Modifier.fillMaxWidth().toggleable(value = remind, onValueChange = { remind = it }, role = Role.Checkbox).heightIn(min = 48.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = remind, onCheckedChange = null)
            Text("Remind me when the snooze ends", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
        }
        val text = when {
            !computed -> "Calculating next reminder…"
            next == null -> "No reminder is scheduled after this snooze."
            else -> {
                val n = next!!
                val what = when (n.kind) {
                    OccurrenceKind.SNOOZE_END -> "Snooze-end reminder"
                    OccurrenceKind.CHECK_IN -> "Check-in reminder"
                    OccurrenceKind.POLICY -> "Next scheduled reminder"
                }
                "$what: ${Format.full(n.plannedAt, zone)}" + if (n.deferred) " (moved out of quiet hours)" else ""
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.signal, modifier = Modifier.padding(top = 4.dp).testTag("snooze_next"))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
            PrimaryButton("Snooze", onClick = { onConfirm(until, remind) }, enabled = until > now, modifier = Modifier.weight(1.4f).testTag("snooze_confirm"))
        }
    }
    if (custom) {
        DateTimeSelectDialog(now.atZone(zone).toLocalDate(), now.atZone(zone).toLocalTime().plusHours(2).withMinute(0), onDismiss = { custom = false }, onConfirm = { d, t ->
            LocalTimeResolver.resolve(d, t, zone).takeIf { it > now }?.let { until = it }
            custom = false
        })
    }
}

@Composable
private fun SnoozeRow(icon: ImageVector, label: String, time: String, selected: Boolean, divider: Boolean, onClick: () -> Unit) {
    val c = AutopilotTheme.colors
    if (divider) GroupDivider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) c.signal else c.ink2, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.ink, modifier = Modifier.weight(1f))
        TimeText(time, size = 14, color = if (selected) c.signal else c.ink2)
    }
}

/** Waiting sheet: who/what, then a check-in (chips from today's date, or a custom date and time). */
@Composable
fun WaitingSheet(task: Task, now: Instant, zone: ZoneId, onDismiss: () -> Unit, onSave: (String?, Instant?) -> Unit) {
    val c = AutopilotTheme.colors
    val today = now.atZone(zone).toLocalDate()
    val options = remember(today, zone) { waitingCheckInOptions(today, zone) }
    var reason by remember { mutableStateOf(task.waitingReason.orEmpty()) }
    var checkIn by remember { mutableStateOf(task.waitingCheckInAt?.takeIf { it > now } ?: options[1].second) }
    var custom by remember { mutableStateOf(false) }
    AutopilotSheet(title = "Waiting on someone", onDismiss = onDismiss) {
        Text("Nudges pause. You'll get one check-in, so it can't quietly stall.", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2)
        Text("Who or what are you waiting on?", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        OutlinedTextField(
            value = reason, onValueChange = { reason = it.take(300) },
            modifier = Modifier.fillMaxWidth().testTag("waiting_reason"),
            placeholder = { Text("e.g. the school office", color = c.ink3) }, singleLine = true, shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Text("Check in on", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (label, at) -> ChoiceChip(label, selected = checkIn == at, onClick = { checkIn = at }) }
            val isCustom = checkIn != null && options.none { it.second == checkIn }
            ChoiceChip(if (isCustom) "Custom: ${Format.relative(checkIn!!, now, zone)}" else "Pick date & time…", selected = isCustom, onClick = { custom = true })
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Start waiting", onClick = { onSave(reason.ifBlank { null }, checkIn) }, modifier = Modifier.fillMaxWidth().testTag("waiting_save"))
    }
    if (custom) {
        DateTimeSelectDialog(today.plusDays(1), LocalTime.of(9, 0), onDismiss = { custom = false }, onConfirm = { d, t ->
            checkIn = LocalTimeResolver.resolve(d, t, zone).takeIf { it > now } ?: checkIn
            custom = false
        })
    }
}

/** "More actions" sheet on a task: edit, move back to Inbox, archive/restore, cancel, delete. */
@Composable
fun MoreActionsSheet(
    title: String,
    archived: Boolean,
    terminal: Boolean,
    canMoveToInbox: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onMoveToInbox: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = AutopilotTheme.colors
    AutopilotSheet(title = null, onDismiss = onDismiss, closeLabel = "Close more actions") {
        Text(title, style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp), maxLines = 2)
        Column {
            MoreRow(AutopilotIcons.Pencil, "Edit title and notes", onClick = onEdit, testTag = "more_edit")
            if (canMoveToInbox) MoreRow(AutopilotIcons.Tray, "Move back to Inbox", onClick = onMoveToInbox, divider = true, testTag = "more_inbox")
            if (!archived) MoreRow(AutopilotIcons.Archive, "Archive", onClick = onArchive, divider = true, testTag = "more_archive")
            else MoreRow(AutopilotIcons.Archive, "Restore from archive", onClick = onUnarchive, divider = true, testTag = "more_unarchive")
            if (!terminal) MoreRow(AutopilotIcons.Close, "Cancel task", onClick = onCancel, divider = true, testTag = "more_cancel")
            MoreRow(AutopilotIcons.Trash, "Delete task", onClick = onDelete, divider = true, danger = true, testTag = "more_delete")
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, label: String, onClick: () -> Unit, divider: Boolean = false, danger: Boolean = false, testTag: String) {
    val c = AutopilotTheme.colors
    if (divider) GroupDivider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).testTag(testTag).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) c.danger else c.ink, modifier = Modifier.size(22.dp))
        Text(label, style = if (danger) MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp) else MaterialTheme.typography.bodyLarge, color = if (danger) c.danger else c.ink)
    }
}
