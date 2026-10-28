package com.lifeadmin.autopilot.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.domain.model.DeadlineSpec
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.DateSelectDialog
import com.lifeadmin.autopilot.ui.components.DateTimeSelectDialog
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.InkSwitch
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.TaskPresentation
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.detail.FollowUpSheet
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.LocalTime

/**
 * Capture: one field, one follow-up row with a switch, one primary button whose label follows
 * the switch ("Save & nudge" / "Save to Inbox"). Notes, priority and deadline sit behind one
 * disclosure. Everything typed stays in the ViewModel until saved.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CaptureSheet(
    viewModel: CaptureViewModel,
    onDismiss: () -> Unit,
    onSaved: (taskId: String, withReminder: Boolean, firstNudge: String?) -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val now = viewModel.now()
    val zone = viewModel.zone()
    var followUp by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickDateTime by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val d = s.draft
    val policy = s.effectivePolicy(now, zone)
    val today = now.atZone(zone).toLocalDate()

    val detailsRequester = remember { BringIntoViewRequester() }
    AutopilotSheet(
        title = "Capture", onDismiss = onDismiss, scrollable = true,
        footer = {
            // Pinned under the content and above the keyboard: always reachable.
            PrimaryButton(
                if (d.nudge) "Save & nudge" else "Save to Inbox", onClick = { viewModel.save(onSaved) }, enabled = !d.isEmpty && !s.saving,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("capture_save"),
            )
        },
    ) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        // Opening the disclosure scrolls it into view, so the new fields never sit behind the keyboard.
        LaunchedEffect(d.detailsOpen) { if (d.detailsOpen) runCatching { detailsRequester.bringIntoView() } }
        Text("What do you need to follow through on?", style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = d.text,
            onValueChange = viewModel::setText,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("capture_title"),
            // Only phrases the offline reader understands (CaptureTiming), so the example is honest.
            placeholder = { Text("e.g. Turn off the fan before bed, or send the report by Monday 5pm", color = c.ink3) },
            minLines = 3, maxLines = 6,
            isError = s.error != null,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 25.sp),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.sunken, unfocusedContainerColor = c.sunken,
                focusedBorderColor = c.lineStrong, unfocusedBorderColor = c.sunken, cursorColor = c.ink,
                focusedTextColor = c.ink, unfocusedTextColor = c.ink,
            ),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
            Text(s.error ?: "Saved on this phone", style = MaterialTheme.typography.bodySmall, color = if (s.error != null) c.danger else c.ink3, modifier = Modifier.weight(1f))
            TimeText("${d.text.length} / ${CaptureViewModel.MAX_INPUT_CHARS}", size = 13, color = c.ink3)
        }

        // What the words say about timing (read offline). It prefills the follow-up row and the
        // deadline below; the text itself stays exactly as typed.
        d.activeTiming?.let { reading -> TimingSection(reading, s, policy, now, zone, viewModel) }

        // Follow-up row: tap the text to change the schedule, the switch to turn nudges on/off.
        val remTitle = if (d.nudge) "Nudge me " + TaskPresentation.scheduleShort(policy, zone, now) else "No nudges"
        val remSub = if (d.nudge) {
            (if (policy.kind == ReminderKind.ONCE) "Once · " else if (policy.isRoutine) "Every day, a routine · " else "Until it's done · ") +
                when (s.followUpSource()) {
                    FollowUpSource.WORDS -> "from your words"
                    FollowUpSource.CUSTOM -> "custom"
                    FollowUpSource.DEFAULT -> "your default"
                } + " · tap to change"
        } else "It goes to your Inbox to sort later"
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(20.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(20.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .clickable(role = Role.Button, onClickLabel = "Change follow-up") { followUp = true }
                    .testTag("capture_follow_up")
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconCircle(if (d.nudge) AutopilotIcons.Bell else AutopilotIcons.Tray, tint = if (d.nudge) c.onSignalSoft else c.ink2, background = if (d.nudge) c.signalSoft else c.sunken)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(remTitle, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink)
                    Text(remSub, style = MaterialTheme.typography.bodySmall, color = c.ink2)
                }
            }
            Row(
                Modifier
                    .toggleable(value = d.nudge, role = Role.Switch, onValueChange = viewModel::setNudge)
                    .semantics { contentDescription = "Nudge me about this" }
                    .testTag("capture_nudge_switch")
                    .height(56.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { InkSwitch(checked = d.nudge) }
        }

        // The per-task quiet-hours exception: only offered when a nudge would fall in quiet hours,
        // never turned on by itself, and explained before saving.
        if (s.quietHoursMatter(now, zone)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(20.dp))
                    .toggleable(value = d.ringInQuietHours, role = Role.Switch, onValueChange = viewModel::setRingInQuietHours)
                    .testTag("capture_ring_quiet")
                    .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconCircle(AutopilotIcons.Moon, tint = c.ink2, background = c.sunken)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Ring during quiet hours", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink)
                    Text(CaptureTimingText.quietHours(s.settings, d.ringInQuietHours), style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("capture_ring_quiet_text"))
                }
                InkSwitch(checked = d.ringInQuietHours)
            }
        }

        // Disclosure: notes, priority, deadline.
        Row(
            Modifier
                .padding(top = 6.dp)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = viewModel::toggleDetails)
                .semantics { contentDescription = if (d.detailsOpen) "Hide notes, priority, deadline" else "Show notes, priority, deadline" }
                .testTag("capture_details")
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (d.detailsOpen) AutopilotIcons.Minus else AutopilotIcons.Plus, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
            Text("Notes, priority, deadline", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = c.ink)
        }
        if (d.detailsOpen) {
            Column(Modifier.bringIntoViewRequester(detailsRequester).padding(horizontal = 4.dp, vertical = 4.dp).testTag("capture_details_section"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Priority", style = MaterialTheme.typography.labelMedium, color = c.ink2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Priority.entries.forEach { p ->
                            ChoiceChip(p.name.lowercase().replaceFirstChar { it.uppercase() }, selected = d.priority == p, onClick = { viewModel.setPriority(p) }, modifier = Modifier.testTag("capture_priority_${p.name.lowercase()}"))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Deadline", style = MaterialTheme.typography.labelMedium, color = c.ink2)
                    val dl = s.effectiveDeadline()
                    val tomorrow = today.plusDays(1)
                    val nextWeek = today.plusWeeks(1)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("None", selected = dl == null, onClick = { viewModel.setDeadline(null) })
                        ChoiceChip("Tomorrow", selected = dl?.type == DeadlineType.DATE_ONLY && dl.date == tomorrow, onClick = { viewModel.setDeadline(DeadlineSpec(DeadlineType.DATE_ONLY, date = tomorrow)) })
                        ChoiceChip(Format.date(nextWeek, today), selected = dl?.type == DeadlineType.DATE_ONLY && dl.date == nextWeek, onClick = { viewModel.setDeadline(DeadlineSpec(DeadlineType.DATE_ONLY, date = nextWeek)) })
                        val customDate = dl != null && !(dl.type == DeadlineType.DATE_ONLY && (dl.date == tomorrow || dl.date == nextWeek))
                        ChoiceChip(
                            if (customDate) deadlineLabel(dl!!, now, zone) else "Pick a date…",
                            selected = customDate, onClick = { pickDate = true },
                        )
                        ChoiceChip("Date & time…", selected = false, onClick = { pickDateTime = true })
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Notes", style = MaterialTheme.typography.labelMedium, color = c.ink2)
                    OutlinedTextField(
                        value = d.notes, onValueChange = viewModel::setNotes,
                        modifier = Modifier.fillMaxWidth().testTag("capture_notes"),
                        placeholder = { Text("Anything future-you should know", color = c.ink3) },
                        minLines = 1, maxLines = 6, shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
            }
        }
    }

    if (followUp) {
        FollowUpSheet(
            initial = policy,
            settings = s.settings,
            now = now, zone = zone,
            onDismiss = { followUp = false },
            onSave = { p, _ -> viewModel.setPolicy(if (p.enabled) p else null); viewModel.setNudge(p.enabled); followUp = false },
            contextText = "For what you're capturing now.",
            saveLabel = "Use for this capture",
            offHint = "goes to Inbox",
            closeLabel = "Back to capture",
        )
    }
    if (pickDate) DateSelectDialog(d.deadline?.date ?: d.deadline?.instant?.atZone(zone)?.toLocalDate() ?: today.plusDays(1), onDismiss = { pickDate = false }) { date ->
        pickDate = false; viewModel.setDeadline(DeadlineSpec(DeadlineType.DATE_ONLY, date = date))
    }
    if (pickDateTime) DateTimeSelectDialog(d.deadline?.date ?: today.plusDays(1), d.deadline?.instant?.atZone(zone)?.toLocalTime() ?: LocalTime.of(17, 0), onDismiss = { pickDateTime = false }) { date, time ->
        pickDateTime = false; viewModel.setDeadline(DeadlineSpec(DeadlineType.EXACT, instant = LocalTimeResolver.resolve(date, time, zone)))
    }
}

private fun deadlineLabel(d: DeadlineSpec, now: java.time.Instant, zone: java.time.ZoneId): String = Format.deadlineSpec(d, now, zone)

/**
 * "Understood from your words": the nudge and/or deadline read from the text, each with the words
 * it came from, notes about timing that was deliberately not used, and Ignore. For a one-off
 * bedtime nudge, "Every night instead" turns it into a confirmed routine.
 */
@Composable
private fun TimingSection(
    reading: com.lifeadmin.autopilot.domain.capture.CaptureTiming.Reading,
    s: CaptureUiState,
    policy: com.lifeadmin.autopilot.domain.model.ReminderPolicy,
    now: java.time.Instant,
    zone: java.time.ZoneId,
    viewModel: CaptureViewModel,
) {
    val c = AutopilotTheme.colors
    val d = s.draft
    val nudge = s.inferredNudge(zone)
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(c.signalSoft).padding(horizontal = 12.dp, vertical = 10.dp).testTag("capture_timing"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Understood from your words", style = MaterialTheme.typography.labelMedium, color = c.onSignalSoft)
        if (nudge != null && d.nudge && d.policy == null) {
            TimingRow(AutopilotIcons.Bell, CaptureTimingText.nudge(nudge, policy, s.settings, now, zone), CaptureTimingText.source(nudge.phrase), "capture_timing_nudge")
        } else if (nudge != null && d.policy != null) {
            Text("You chose a different follow-up below.", style = MaterialTheme.typography.bodySmall, color = c.onSignalSoft)
        }
        reading.deadline?.let { dl ->
            if (!d.deadlineTouched) TimingRow(AutopilotIcons.Calendar, CaptureTimingText.deadline(dl, now, zone), CaptureTimingText.source(dl.phrase), "capture_timing_deadline")
        }
        reading.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSignalSoft) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val once = reading.nudge as? com.lifeadmin.autopilot.domain.capture.CaptureTiming.Nudge.Once
            if (once != null && once.bedtime && d.policy == null && d.nudge) {
                LinkButton(if (d.makeNightly) "Just once" else "Every night instead", onClick = { viewModel.setMakeNightly(!d.makeNightly) }, modifier = Modifier.testTag("capture_timing_nightly"))
            }
            if (reading.nudge != null || reading.deadline != null) LinkButton("Ignore", onClick = viewModel::ignoreTiming, modifier = Modifier.testTag("capture_timing_ignore"))
        }
    }
}

@Composable
private fun TimingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, source: String, tag: String) {
    val c = AutopilotTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = null, tint = c.onSignalSoft, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = c.ink)
            Text(source, style = MaterialTheme.typography.bodySmall, color = c.onSignalSoft)
        }
    }
}
