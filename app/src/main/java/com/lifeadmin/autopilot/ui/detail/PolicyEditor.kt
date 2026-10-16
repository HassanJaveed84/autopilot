package com.lifeadmin.autopilot.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver
import com.lifeadmin.autopilot.domain.scheduling.PlannedReminder
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import com.lifeadmin.autopilot.ui.components.DateSelectDialog
import com.lifeadmin.autopilot.ui.components.DateTimeSelectDialog
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.TimeSelectDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun PolicyEditorDialog(
    initial: ReminderPolicy,
    taskStatus: TaskStatus,
    now: Instant,
    zone: ZoneId,
    preview: suspend (ReminderPolicy) -> PlannedReminder?,
    onDismiss: () -> Unit,
    onSave: (ReminderPolicy, (String) -> Unit) -> Unit,
) {
    val today = now.atZone(zone).toLocalDate()
    var draft by remember {
        mutableStateOf(
            if (initial.enabled || initial.times.isNotEmpty() || initial.atInstant != null) initial
            else ReminderPresets.applyTo(initial, ReminderPreset.PERSISTENT, today).copy(enabled = true),
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var addingTime by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingOnce by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf<PlannedReminder?>(null) }
    LaunchedEffect(draft) {
        val result = runCatching { preview(draft) }.getOrNull()
        withContext(Dispatchers.Main.immediate) { next = result }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Reminder schedule") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Discard changes") } },
                    actions = {
                        TextButton(onClick = { error = null; onSave(draft) { error = it } }, modifier = Modifier.testTag("policy_save")) { Text("Save") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Row(
                    Modifier.fillMaxWidth().toggleable(draft.enabled, role = Role.Switch, onValueChange = { draft = draft.copy(enabled = it) }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Reminders", style = MaterialTheme.typography.titleMedium)
                        Text(if (draft.enabled) "On" else "Off — task stays visible in the app", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = draft.enabled, onCheckedChange = null, modifier = Modifier.testTag("policy_enabled"))
                }
                if (draft.enabled) {
                    Label("Presets (editable)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReminderPreset.entries.forEach { p ->
                            AssistChip(onClick = { draft = ReminderPresets.applyTo(draft, p, today) }, label = { Text(ReminderPresets.label(p)) })
                        }
                    }
                    Label("Repeat")
                    val kinds = listOf(ReminderKind.ONCE to "Once", ReminderKind.DAILY_TIMES to "Daily", ReminderKind.EVERY_N_DAYS to "Every N days")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        kinds.forEachIndexed { i, (k, label) ->
                            SegmentedButton(
                                selected = draft.kind == k,
                                onClick = {
                                    draft = when (k) {
                                        ReminderKind.ONCE -> draft.copy(kind = k, atInstant = draft.atInstant ?: LocalTimeResolver.resolve(today.plusDays(1), LocalTime.of(9, 0), zone))
                                        ReminderKind.DAILY_TIMES -> draft.copy(kind = k, dayInterval = 1, times = draft.times.ifEmpty { listOf(LocalTime.of(9, 0)) })
                                        ReminderKind.EVERY_N_DAYS -> draft.copy(kind = k, dayInterval = draft.dayInterval.coerceAtLeast(2), times = draft.times.ifEmpty { listOf(LocalTime.of(10, 0)) }, startLocalDate = draft.startLocalDate ?: today)
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                            ) { Text(label) }
                        }
                    }
                    when (draft.kind) {
                        ReminderKind.ONCE -> {
                            Label("When")
                            OutlinedButton(onClick = { pickingOnce = true }) {
                                Text(draft.atInstant?.let { Format.full(it, zone) } ?: "Pick date and time")
                            }
                        }
                        ReminderKind.DAILY_TIMES, ReminderKind.EVERY_N_DAYS -> {
                            if (draft.kind == ReminderKind.EVERY_N_DAYS) {
                                Label("Every")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = { draft = draft.copy(dayInterval = (draft.dayInterval - 1).coerceAtLeast(2)) }) { Text("−") }
                                    Text("${draft.dayInterval} days", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                                    OutlinedButton(onClick = { draft = draft.copy(dayInterval = (draft.dayInterval + 1).coerceAtMost(365)) }) { Text("+") }
                                }
                            }
                            Label("Times of day")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                draft.times.sorted().forEach { time ->
                                    InputChip(
                                        selected = false,
                                        onClick = { draft = draft.copy(times = draft.times - time) },
                                        label = { Text(time.format(hhmm)) },
                                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove ${time.format(hhmm)}") },
                                    )
                                }
                                AssistChip(onClick = { addingTime = true }, label = { Text("Add time") }, leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) }, modifier = Modifier.testTag("policy_add_time"))
                            }
                            Label("Starting")
                            OutlinedButton(onClick = { pickingStart = true }) {
                                Text(Format.date(draft.startLocalDate ?: today, today))
                            }
                        }
                    }
                    Label("During quiet hours")
                    Column(Modifier.selectableGroup()) {
                        listOf(
                            null to "Use app setting",
                            QuietHoursBehavior.DEFER_AND_COALESCE to "Wait until quiet hours end (combine into one)",
                            QuietHoursBehavior.SKIP to "Skip that reminder",
                        ).forEach { (b, label) -> Choice(label, draft.quietHoursBehavior == b) { draft = draft.copy(quietHoursBehavior = b) } }
                    }
                    Label("Time zone")
                    Column(Modifier.selectableGroup()) {
                        Choice("Follow this phone's time zone", draft.zoneMode == ZoneMode.FOLLOW_DEVICE) { draft = draft.copy(zoneMode = ZoneMode.FOLLOW_DEVICE, zoneId = null) }
                        Choice("Keep fixed to ${draft.zoneId ?: zone.id}", draft.zoneMode == ZoneMode.FIXED_ZONE) { draft = draft.copy(zoneMode = ZoneMode.FIXED_ZONE, zoneId = draft.zoneId ?: zone.id) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                val nextText = when {
                    !draft.enabled -> "No reminders will be sent for this task."
                    taskStatus == TaskStatus.WAITING -> "This task is Waiting: only its check-in reminder fires until you resume it."
                    taskStatus == TaskStatus.PAUSED -> "This task is Paused: reminders resume when you set it back to Actionable."
                    taskStatus.isTerminal -> "This task is closed: reminders resume only if you reopen it."
                    next == null -> "No upcoming reminder for this schedule."
                    else -> {
                        val n = next!!
                        val label = if (n.kind == OccurrenceKind.SNOOZE_END) "Next reminder (snooze end)" else "Next reminder"
                        "$label: ${Format.full(n.plannedAt, zone)}" + if (n.deferred) " — moved out of quiet hours" else ""
                    }
                }
                Text(nextText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("policy_next"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("policy_error")) }
                Text(
                    "Reminders keep coming for this same task until you mark it done. Delivery is best-effort: Android may delay alarms to save battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
    if (addingTime) {
        TimeSelectDialog(LocalTime.of(9, 0), title = "Add reminder time", onDismiss = { addingTime = false }) {
            draft = draft.copy(times = (draft.times + it).distinct().sorted())
            addingTime = false
        }
    }
    if (pickingStart) {
        DateSelectDialog(draft.startLocalDate ?: today, onDismiss = { pickingStart = false }) {
            draft = draft.copy(startLocalDate = it)
            pickingStart = false
        }
    }
    if (pickingOnce) {
        val cur = draft.atInstant?.atZone(zone)
        DateTimeSelectDialog(cur?.toLocalDate() ?: today.plusDays(1), cur?.toLocalTime() ?: LocalTime.of(9, 0), onDismiss = { pickingOnce = false }) { d, t ->
            draft = draft.copy(atInstant = LocalTimeResolver.resolve(d, t, zone))
            pickingOnce = false
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() })
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}
