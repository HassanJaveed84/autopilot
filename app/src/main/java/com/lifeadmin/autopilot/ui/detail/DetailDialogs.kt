package com.lifeadmin.autopilot.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.SnoozePreset
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver
import com.lifeadmin.autopilot.domain.scheduling.PlannedReminder
import com.lifeadmin.autopilot.domain.scheduling.SnoozeCalculator
import com.lifeadmin.autopilot.ui.components.DateSelectDialog
import com.lifeadmin.autopilot.ui.components.DateTimeSelectDialog
import com.lifeadmin.autopilot.ui.components.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Composable
fun EditDetailsDialog(task: Task, onDismiss: () -> Unit, onSave: (String, String?, Priority) -> Unit) {
    var title by remember { mutableStateOf(task.title) }
    var notes by remember { mutableStateOf(task.notes.orEmpty()) }
    var priority by remember { mutableStateOf(task.priority) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit task") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(300) }, label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth().testTag("edit_title"),
                    isError = title.isBlank(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it.take(10_000) }, label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3, maxLines = 8,
                )
                Text("Priority", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Priority.entries.forEachIndexed { i, p ->
                        SegmentedButton(selected = priority == p, onClick = { priority = p }, shape = SegmentedButtonDefaults.itemShape(i, Priority.entries.size)) {
                            Text(p.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onSave(title, notes.ifBlank { null }, priority) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun DeadlineDialog(task: Task, now: Instant, zone: ZoneId, onDismiss: () -> Unit, onSave: (DeadlineType, LocalDate?, Instant?) -> Unit) {
    var type by remember { mutableStateOf(task.deadlineType) }
    var date by remember { mutableStateOf(task.deadlineLocalDate ?: task.deadlineInstant?.atZone(zone)?.toLocalDate() ?: now.atZone(zone).toLocalDate().plusDays(1)) }
    var time by remember { mutableStateOf(task.deadlineInstant?.atZone(zone)?.toLocalTime() ?: LocalTime.of(17, 0)) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deadline") },
        text = {
            Column(Modifier.selectableGroup()) {
                RadioRow("No deadline", type == DeadlineType.NONE, { type = DeadlineType.NONE })
                RadioRow("Date only", type == DeadlineType.DATE_ONLY, { type = DeadlineType.DATE_ONLY })
                RadioRow("Date and time", type == DeadlineType.EXACT, { type = DeadlineType.EXACT })
                if (type != DeadlineType.NONE) {
                    val label = if (type == DeadlineType.DATE_ONLY) Format.date(date, now.atZone(zone).toLocalDate())
                    else Format.full(LocalTimeResolver.resolve(date, time, zone), zone)
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.padding(top = 8.dp)) { Text(label) }
                    Text(
                        "A deadline never creates reminders by itself — set reminders separately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(type, if (type == DeadlineType.DATE_ONLY) date else null, if (type == DeadlineType.EXACT) LocalTimeResolver.resolve(date, time, zone) else null)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        if (type == DeadlineType.DATE_ONLY) {
            DateSelectDialog(date, onDismiss = { picking = false }, onConfirm = { date = it; picking = false })
        } else {
            DateTimeSelectDialog(date, time, onDismiss = { picking = false }, onConfirm = { d, t -> date = d; time = t; picking = false })
        }
    }
}

@Composable
fun WaitingDialog(task: Task, now: Instant, zone: ZoneId, onDismiss: () -> Unit, onSave: (String?, Instant?) -> Unit) {
    val today = now.atZone(zone).toLocalDate()
    val nineAm = LocalTime.of(9, 0)
    val options: List<Pair<String, Instant?>> = listOf(
        "No check-in reminder" to null,
        "Tomorrow 09:00" to LocalTimeResolver.resolve(today.plusDays(1), nineAm, zone),
        "In 3 days, 09:00" to LocalTimeResolver.resolve(today.plusDays(3), nineAm, zone),
        "Next week (${today.with(TemporalAdjusters.next(DayOfWeek.TUESDAY)).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}) 09:00" to
            LocalTimeResolver.resolve(today.with(TemporalAdjusters.next(DayOfWeek.TUESDAY)), nineAm, zone),
    )
    var reason by remember { mutableStateOf(task.waitingReason.orEmpty()) }
    var checkIn by remember { mutableStateOf(task.waitingCheckInAt?.takeIf { it > now } ?: options[1].second) }
    var custom by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Waiting on something") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it.take(300) }, label = { Text("Waiting for… (optional)") },
                    modifier = Modifier.fillMaxWidth().testTag("waiting_reason"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Text(
                    "Regular reminders pause while waiting. Choose when to check in:",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                Column(Modifier.selectableGroup()) {
                    options.forEach { (label, at) -> RadioRow(label, checkIn == at, { checkIn = at }) }
                    val isCustom = checkIn != null && options.none { it.second == checkIn }
                    RadioRow(if (isCustom) "Custom: ${Format.relative(checkIn!!, now, zone)}" else "Pick date and time…", isCustom, { custom = true })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(reason.ifBlank { null }, checkIn) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (custom) {
        DateTimeSelectDialog(today.plusDays(1), nineAm, onDismiss = { custom = false }, onConfirm = { d, t ->
            checkIn = LocalTimeResolver.resolve(d, t, zone).takeIf { it > now } ?: checkIn
            custom = false
        })
    }
}

/** Snooze dialog: shows the exact next eligible reminder before the user confirms. */
@Composable
fun SnoozeDialog(
    now: Instant,
    zone: ZoneId,
    preview: suspend (Instant, Boolean) -> PlannedReminder?,
    onDismiss: () -> Unit,
    onConfirm: (Instant, Boolean) -> Unit,
) {
    val presets = listOf(
        "1 hour" to SnoozeCalculator.target(SnoozePreset.ONE_HOUR, now, zone),
        "3 hours" to SnoozeCalculator.target(SnoozePreset.THREE_HOURS, now, zone),
        "Tomorrow morning" to SnoozeCalculator.target(SnoozePreset.TOMORROW_MORNING, now, zone),
    )
    var until by remember { mutableStateOf(presets.last().second) }
    var remind by remember { mutableStateOf(true) }
    var custom by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf<PlannedReminder?>(null) }
    var computed by remember { mutableStateOf(false) }
    LaunchedEffect(until, remind) {
        computed = false
        val result = runCatching { preview(until, remind) }.getOrNull()
        // Publish UI state on the main thread regardless of where the preview resumed.
        withContext(Dispatchers.Main.immediate) {
            next = result
            computed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snooze") },
        text = {
            Column {
                Column(Modifier.selectableGroup()) {
                    presets.forEach { (label, at) -> RadioRow("$label · ${Format.relative(at, now, zone)}", until == at, { until = at }) }
                    val isCustom = presets.none { it.second == until }
                    RadioRow(if (isCustom) "Custom · ${Format.relative(until, now, zone)}" else "Pick date and time…", isCustom, { custom = true })
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(value = remind, onValueChange = { remind = it }, role = Role.Checkbox).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = remind, onCheckedChange = null)
                    Text("Remind me when the snooze ends", modifier = Modifier.padding(start = 12.dp))
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
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("snooze_next"))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(until, remind) }, enabled = until > now) { Text("Snooze") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (custom) {
        DateTimeSelectDialog(now.atZone(zone).toLocalDate(), now.atZone(zone).toLocalTime().plusHours(2).withMinute(0), onDismiss = { custom = false }, onConfirm = { d, t ->
            LocalTimeResolver.resolve(d, t, zone).takeIf { it > now }?.let { until = it }
            custom = false
        })
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SpacedRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
