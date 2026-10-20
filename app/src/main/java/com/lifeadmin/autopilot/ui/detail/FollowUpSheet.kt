package com.lifeadmin.autopilot.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import com.lifeadmin.autopilot.domain.scheduling.ReminderProjection
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.DateSelectDialog
import com.lifeadmin.autopilot.ui.components.DateTimeSelectDialog
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.NoteLine
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.TimeSelectDialog
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.components.dashedBorder
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/** Which preset (if any) a policy currently matches, for the preset cards' selected state. */
internal fun matchPreset(p: ReminderPolicy, today: LocalDate): ReminderPreset? {
    if (!p.enabled) return null
    return ReminderPreset.entries.firstOrNull { preset ->
        val applied = ReminderPresets.applyTo(p, preset, today)
        applied.kind == p.kind && applied.dayInterval == p.dayInterval && applied.times.sorted() == p.times.sorted() && (p.startLocalDate == null || p.startLocalDate == today)
    }
}

/**
 * The Follow-up sheet: preset cards, "No nudges", fine-tune (Once / Daily / Every N days, time
 * chips, N stepper), the until-done and quiet-hours rules, and a live preview of the next three
 * nudges computed with the engine's own planner. "Advanced" keeps the existing start date,
 * quiet-hours override and time-zone choices. Nothing is written until [onSave].
 */
@Composable
fun FollowUpSheet(
    initial: ReminderPolicy,
    settings: AppSettings,
    now: Instant,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (ReminderPolicy, onError: (String) -> Unit) -> Unit,
    contextText: String = "",
    saveLabel: String = "Use this follow-up",
    offHint: String = "stays on your list, silently",
    taskStatus: TaskStatus? = null,
    closeLabel: String = "Close",
) {
    val c = AutopilotTheme.colors
    val today = now.atZone(zone).toLocalDate()
    var draft by remember {
        mutableStateOf(
            if (initial.enabled || initial.times.isNotEmpty() || initial.atInstant != null) initial
            else ReminderPresets.applyTo(initial, settings.defaultPreset, today).copy(enabled = true),
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var addingTime by remember { mutableStateOf(false) }
    var editingTime by remember { mutableStateOf<LocalTime?>(null) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingOnce by remember { mutableStateOf(false) }
    val matched = matchPreset(draft, today)
    val preview = remember(draft, settings, now, zone) { ReminderProjection.upcomingForNewTask(draft, settings, now, zone, 3) }

    AutopilotSheet(title = "Follow-up", onDismiss = onDismiss, closeLabel = closeLabel) {
        if (contextText.isNotBlank()) Text(contextText, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, modifier = Modifier.padding(bottom = 14.dp))

        // Presets: 2-column grid of selectable cards.
        val presets = listOf(
            ReminderPreset.PERSISTENT to "09:00 & 18:00 daily",
            ReminderPreset.DAILY to "09:00 every day",
            ReminderPreset.GENTLE to "Every 2 days, 10:00",
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { (preset, desc) ->
                        PresetCard(
                            name = ReminderPresets.shortLabel(preset), desc = desc, selected = matched == preset,
                            onClick = { draft = ReminderPresets.applyTo(draft, preset, today).copy(enabled = true); error = null },
                            modifier = Modifier.weight(1f).testTag("preset_${preset.name.lowercase()}"),
                        )
                    }
                    if (rowItems.size == 1) {
                        PresetCard(
                            name = "Once", desc = "One nudge, time of your choice", selected = draft.enabled && draft.kind == ReminderKind.ONCE,
                            onClick = {
                                draft = draft.copy(enabled = true, kind = ReminderKind.ONCE, atInstant = draft.atInstant ?: defaultOnce(now, zone))
                                error = null
                            },
                            modifier = Modifier.weight(1f).testTag("preset_once"),
                        )
                    }
                }
            }
            // No nudges.
            val offSelected = !draft.enabled
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (offSelected) c.paper else c.surface)
                    .border(2.dp, if (offSelected) c.ink else c.line, RoundedCornerShape(16.dp))
                    .selectable(selected = offSelected, role = Role.RadioButton, onClick = { draft = draft.copy(enabled = false); error = null })
                    .testTag("policy_enabled")
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(AutopilotIcons.BellOff, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
                Text(
                    buildString { append("No nudges"); append(" · "); append(offHint) },
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink,
                )
            }
        }

        if (draft.enabled) {
            // Fine-tune
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Fine-tune", style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.weight(1f))
                if (matched == null && draft.kind != ReminderKind.ONCE) {
                    Text("Custom", style = MaterialTheme.typography.labelSmall, color = c.ink, modifier = Modifier.clip(CircleShape).background(c.sunken).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            val kinds = listOf(ReminderKind.ONCE to "Once", ReminderKind.DAILY_TIMES to "Daily", ReminderKind.EVERY_N_DAYS to "Every N days")
            // Segments share the row's height and let a long label ("Every N days" at large text)
            // take two lines instead of being cut to "Every N d…".
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(24.dp)).background(c.sunken).padding(4.dp).height(IntrinsicSize.Min).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                kinds.forEach { (k, label) ->
                    val sel = draft.kind == k
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (sel) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(selected = sel, role = Role.RadioButton, onClick = {
                                draft = when (k) {
                                    ReminderKind.ONCE -> draft.copy(kind = k, atInstant = draft.atInstant ?: defaultOnce(now, zone))
                                    ReminderKind.DAILY_TIMES -> draft.copy(kind = k, dayInterval = 1, times = draft.times.ifEmpty { listOf(LocalTime.of(9, 0)) })
                                    ReminderKind.EVERY_N_DAYS -> draft.copy(kind = k, dayInterval = draft.dayInterval.coerceAtLeast(2), times = draft.times.ifEmpty { listOf(LocalTime.of(10, 0)) }, startLocalDate = draft.startLocalDate ?: today)
                                }
                                error = null
                            })
                            .testTag("policy_kind_${k.name.lowercase()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            when (draft.kind) {
                ReminderKind.ONCE -> {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeChip(draft.atInstant?.let { Format.relative(it, now, zone) } ?: "Pick date and time", onClick = { pickingOnce = true }, modifier = Modifier.weight(1f))
                    }
                }
                ReminderKind.DAILY_TIMES, ReminderKind.EVERY_N_DAYS -> {
                    if (draft.kind == ReminderKind.EVERY_N_DAYS) {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Every", style = MaterialTheme.typography.bodyMedium, color = c.ink)
                            StepperButton("−", "Fewer days") { draft = draft.copy(dayInterval = (draft.dayInterval - 1).coerceAtLeast(2)) }
                            TimeText(draft.dayInterval.toString(), size = 18, modifier = Modifier.semantics { contentDescription = "${draft.dayInterval} days" })
                            StepperButton("+", "More days") { draft = draft.copy(dayInterval = (draft.dayInterval + 1).coerceAtMost(365)) }
                            Text("days", style = MaterialTheme.typography.bodyMedium, color = c.ink)
                        }
                    }
                    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.times.sorted().forEach { time ->
                            Row(
                                Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.signalSoft).padding(start = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TimeText(
                                    time.format(hhmm), size = 17, color = c.onSignalSoft,
                                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Change ${time.format(hhmm)}") { editingTime = time },
                                )
                                RoundIconButton(AutopilotIcons.Close, contentDescription = "Remove ${time.format(hhmm)}", tint = c.onSignalSoft, onClick = {
                                    val left = draft.times - time
                                    draft = if (left.isEmpty()) draft.copy(enabled = false, times = emptyList()) else draft.copy(times = left)
                                }, modifier = Modifier.size(40.dp))
                            }
                        }
                        Row(
                            Modifier
                                .height(48.dp)
                                .dashedBorder(c.lineStrong, 14.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(role = Role.Button, onClick = { addingTime = true })
                                .testTag("policy_add_time")
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(AutopilotIcons.Plus, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
                            Text("Add time", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink)
                        }
                    }
                }
            }

            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteLine(
                    AutopilotIcons.Bell,
                    if (draft.kind == ReminderKind.ONCE) "Nudges once. Dismissing it doesn't complete the task — it stays on your list."
                    else "Keeps nudging until you mark it done. Dismissing a notification doesn't count as done.",
                )
                val qh = settings.quietHours
                val behavior = draft.quietHoursBehavior ?: settings.quietHoursBehavior
                if (qh.isEffective) NoteLine(
                    AutopilotIcons.Moon,
                    if (behavior == QuietHoursBehavior.SKIP) "Nudges that fall in quiet hours (${qh.start.format(hhmm)}–${qh.end.format(hhmm)}) are skipped."
                    else "Nothing rings in quiet hours, ${qh.start.format(hhmm)}–${qh.end.format(hhmm)}. Nudges wait until they end.",
                )
                when {
                    taskStatus == TaskStatus.WAITING -> NoteLine(AutopilotIcons.Hourglass, "This task is Waiting: only its check-in fires until you resume it.")
                    taskStatus == TaskStatus.PAUSED -> NoteLine(AutopilotIcons.Pause, "This task is Paused: nudges resume when you resume it.")
                    taskStatus?.isTerminal == true -> NoteLine(AutopilotIcons.Check, "This task is closed: nudges resume only if you reopen it.")
                }
            }

            // Preview of the next three nudges (same planner as the engine).
            Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(16.dp)).background(c.paper).padding(horizontal = 14.dp, vertical = 12.dp).testTag("policy_next")) {
                Text("Next nudges", style = MaterialTheme.typography.labelMedium, color = c.ink2)
                if (preview.isEmpty()) {
                    Text("No upcoming nudge for this schedule.", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 6.dp))
                } else {
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        preview.forEach { p -> TimeText(Format.relative(p.plannedAt, now, zone) + if (p.deferred) " (after quiet hours)" else "", size = 14) }
                    }
                }
            }

            // Advanced: the existing start date, quiet-hours override and time-zone choices.
            LinkButton(if (advanced) "Hide advanced" else "Advanced: start date, quiet hours, time zone", onClick = { advanced = !advanced }, modifier = Modifier.padding(top = 6.dp))
            if (advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (draft.kind != ReminderKind.ONCE) {
                        Text("Starting", style = MaterialTheme.typography.labelLarge, color = c.ink2)
                        TimeChip(Format.date(draft.startLocalDate ?: today, today), onClick = { pickingStart = true })
                    }
                    Text("During quiet hours", style = MaterialTheme.typography.labelLarge, color = c.ink2)
                    Column(Modifier.selectableGroup()) {
                        listOf(
                            null to "Use the app setting",
                            QuietHoursBehavior.DEFER_AND_COALESCE to "Wait until quiet hours end (combine into one)",
                            QuietHoursBehavior.SKIP to "Skip that nudge",
                        ).forEach { (b, label) -> RadioLine(label, draft.quietHoursBehavior == b) { draft = draft.copy(quietHoursBehavior = b) } }
                    }
                    Text("Time zone", style = MaterialTheme.typography.labelLarge, color = c.ink2)
                    Column(Modifier.selectableGroup()) {
                        RadioLine("Follow this phone's time zone", draft.zoneMode == ZoneMode.FOLLOW_DEVICE) { draft = draft.copy(zoneMode = ZoneMode.FOLLOW_DEVICE, zoneId = null) }
                        RadioLine("Keep fixed to ${draft.zoneId ?: zone.id}", draft.zoneMode == ZoneMode.FIXED_ZONE) { draft = draft.copy(zoneMode = ZoneMode.FIXED_ZONE, zoneId = draft.zoneId ?: zone.id) }
                    }
                }
            }
        }

        error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp).testTag("policy_error")) }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = if (!draft.enabled) "Turn nudges off" else saveLabel,
            onClick = { error = null; onSave(draft) { error = it } },
            modifier = Modifier.fillMaxWidth().testTag("policy_save"),
        )
    }

    if (addingTime) TimeSelectDialog(LocalTime.of(12, 0), title = "Add nudge time", onDismiss = { addingTime = false }) {
        draft = draft.copy(times = (draft.times + it).distinct().sorted()); addingTime = false
    }
    editingTime?.let { old ->
        TimeSelectDialog(old, title = "Change nudge time", onDismiss = { editingTime = null }) {
            draft = draft.copy(times = ((draft.times - old) + it).distinct().sorted()); editingTime = null
        }
    }
    if (pickingStart) DateSelectDialog(draft.startLocalDate ?: today, onDismiss = { pickingStart = false }) {
        draft = draft.copy(startLocalDate = it); pickingStart = false
    }
    if (pickingOnce) {
        val cur = draft.atInstant?.atZone(zone)
        DateTimeSelectDialog(cur?.toLocalDate() ?: today.plusDays(1), cur?.toLocalTime() ?: LocalTime.of(9, 0), onDismiss = { pickingOnce = false }) { d, t ->
            draft = draft.copy(atInstant = LocalTimeResolver.resolve(d, t, zone)); pickingOnce = false
        }
    }
}

private fun defaultOnce(now: Instant, zone: ZoneId): Instant {
    val local = now.atZone(zone)
    val evening = LocalTimeResolver.resolve(local.toLocalDate(), LocalTime.of(18, 0), zone)
    return if (evening.isAfter(now.plusSeconds(600))) evening else LocalTimeResolver.resolve(local.toLocalDate().plusDays(1), LocalTime.of(9, 0), zone)
}

@Composable
private fun PresetCard(name: String, desc: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    Box(
        modifier
            .heightIn(min = 78.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.paper else c.surface)
            .border(2.dp, if (selected) c.ink else c.line, RoundedCornerShape(18.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.ink)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
        Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(if (selected) c.inverse else androidx.compose.ui.graphics.Color.Transparent), contentAlignment = Alignment.Center) {
            if (selected) Icon(AutopilotIcons.Check, contentDescription = null, tint = c.onInverse, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun StepperButton(glyph: String, label: String, onClick: () -> Unit) {
    val c = AutopilotTheme.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(1.5.dp, c.lineStrong, CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp), color = c.ink) }
}

/** Outlined chip-like button showing a chosen date/time; tapping opens the picker. */
@Composable
internal fun TimeChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).border(1.5.dp, c.lineStrong, RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AutopilotIcons.Calendar, contentDescription = null, tint = c.ink2, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink)
    }
}

@Composable
internal fun RadioLine(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = AutopilotTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium, color = c.ink)
    }
}
