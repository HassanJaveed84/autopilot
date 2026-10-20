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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.lifeadmin.autopilot.domain.model.SnoozePreset
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver
import com.lifeadmin.autopilot.domain.scheduling.SnoozeCalculator
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.DateTimeSelectDialog
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.GroupDivider
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.SwitchRow
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
 * Snooze sheet: concrete times, one tap each. "Remind me when the snooze ends" is a switch at
 * the bottom so a single row tap still applies the snooze (the board's one-tap rows).
 */
@Composable
fun SnoozeSheet(
    now: Instant,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onConfirm: (Instant, Boolean) -> Unit,
) {
    val c = AutopilotTheme.colors
    val options = remember(now, zone) { snoozeOptions(now, zone) }
    var remind by remember { mutableStateOf(true) }
    var custom by remember { mutableStateOf(false) }
    val today = now.atZone(zone).toLocalDate()
    AutopilotSheet(title = "Snooze until…", onDismiss = onDismiss) {
        Text("Quiet until then. After that, the normal follow-up resumes.", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, modifier = Modifier.padding(bottom = 6.dp))
        Column {
            options.forEachIndexed { i, o ->
                val local = o.at.atZone(zone)
                val clock = if (local.toLocalDate() == today) Format.time(o.at, zone) else local.format(snoozeDayFmt)
                SnoozeRow(o.icon, o.label, clock, divider = i > 0, testTag = "snooze_" + o.label.lowercase().replace(' ', '_')) { onConfirm(o.at, remind) }
            }
            SnoozeRow(AutopilotIcons.Calendar, "Pick a date & time", "", divider = true, testTag = "snooze_custom") { custom = true }
        }
        SwitchRow(
            title = "Remind me when the snooze ends",
            subtitle = "One nudge when it's over; the normal follow-up resumes after.",
            checked = remind, onCheckedChange = { remind = it },
            modifier = Modifier.padding(top = 6.dp), switchTestTag = "snooze_remind",
        )
    }
    if (custom) {
        DateTimeSelectDialog(today, now.atZone(zone).toLocalTime().plusHours(2).withMinute(0), onDismiss = { custom = false }, onConfirm = { d, t ->
            custom = false
            LocalTimeResolver.resolve(d, t, zone).takeIf { it > now }?.let { onConfirm(it, remind) }
        })
    }
}

private val snoozeDayFmt: DateTimeFormatter get() = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())

@Composable
private fun SnoozeRow(icon: ImageVector, label: String, time: String, divider: Boolean, testTag: String, onClick: () -> Unit) {
    val c = AutopilotTheme.colors
    if (divider) GroupDivider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClickLabel = "Snooze until $label", onClick = onClick).padding(horizontal = 6.dp).testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.ink2, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.ink, modifier = Modifier.weight(1f))
        if (time.isNotEmpty()) TimeText(time, size = 14, color = c.ink2)
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
    AutopilotSheet(
        title = "Waiting on someone", onDismiss = onDismiss,
        footer = { PrimaryButton("Start waiting", onClick = { onSave(reason.ifBlank { null }, checkIn) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("waiting_save")) },
    ) {
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
        Spacer(Modifier.height(8.dp))
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
