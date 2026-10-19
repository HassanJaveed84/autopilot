package com.lifeadmin.autopilot.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.AiAvailability
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.domain.model.DeadlineSpec
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import com.lifeadmin.autopilot.ui.ai.AI_BILLING_TEXT
import com.lifeadmin.autopilot.ui.ai.AI_PRIVACY_TEXT
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.ButtonPair
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.DateSelectDialog
import com.lifeadmin.autopilot.ui.components.DateTimeSelectDialog
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.InkSwitch
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.TaskPresentation
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.detail.FollowUpSheet
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.LocalTime

/**
 * Capture: one field, one follow-up row with a switch, one primary button whose label follows
 * the switch ("Save & nudge" / "Save to Inbox"), "Plan with AI" beside it. Notes, priority and
 * deadline sit behind one disclosure. Everything typed stays in the ViewModel until saved.
 */
@Composable
fun CaptureSheet(
    viewModel: CaptureViewModel,
    onDismiss: () -> Unit,
    onSaved: (taskId: String, withReminder: Boolean, firstNudge: String?) -> Unit,
    onPlanWithAi: (text: String) -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val now = viewModel.now()
    val zone = viewModel.zone()
    var followUp by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickDateTime by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAvailability() }

    if (s.aiSetup) {
        AiSetupSheet(
            state = s,
            onDismiss = { viewModel.closeAiSetup() },
            onNotNow = { viewModel.closeAiSetup() },
            onTurnOn = { key -> viewModel.turnOnAndPlan(key, onPlanWithAi) },
            onOpenAiSettings = onOpenAiSettings,
        )
        return
    }

    val d = s.draft
    val policy = s.effectivePolicy(now, zone)
    val today = now.atZone(zone).toLocalDate()

    AutopilotSheet(title = "Capture", onDismiss = onDismiss, scrollable = true) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Text("What do you need to follow through on?", style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = d.text,
            onValueChange = viewModel::setText,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("capture_title"),
            placeholder = { Text("e.g. Renew car insurance before 3 Nov, remind me Friday", color = c.ink3) },
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
            TimeText("${d.text.length} / ${AiConfig.MAX_INPUT_CHARS}", size = 13, color = c.ink3)
        }

        // Follow-up row: tap the text to change the schedule, the switch to turn nudges on/off.
        val remTitle = if (d.nudge) "Nudge me " + TaskPresentation.scheduleShort(policy, zone, now) else "No nudges"
        val remSub = if (d.nudge) {
            (if (policy.kind == ReminderKind.ONCE) "Once · " else "Until it's done · ") + (if (d.policy == null) "your default" else "custom") + " · tap to change"
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
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                    val dl = d.deadline
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

        ButtonPair(
            modifier = Modifier.padding(top = 10.dp), primaryWeight = 1.1f,
            secondary = { m ->
                SecondaryButton(
                    "Plan with AI", onClick = { viewModel.planWithAi(onPlanWithAi) }, enabled = !d.isEmpty && !s.saving,
                    icon = AutopilotIcons.Route, route = true, modifier = m.testTag("capture_plan_ai"),
                )
            },
            primary = { m ->
                PrimaryButton(
                    if (d.nudge) "Save & nudge" else "Save to Inbox", onClick = { viewModel.save(onSaved) }, enabled = !d.isEmpty && !s.saving,
                    modifier = m.testTag("capture_save"),
                )
            },
        )
        Text(
            when (s.availability) {
                is AiAvailability.Ready -> "Plan with AI sends only this text, the date and time, and your reminder settings to Anthropic. You review the plan before it's saved."
                else -> "AI planning is off — everything works without it. Tap Plan with AI if you'd like to set it up."
            },
            style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp),
        )
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

private fun deadlineLabel(d: DeadlineSpec, now: java.time.Instant, zone: java.time.ZoneId): String = when (d.type) {
    DeadlineType.DATE_ONLY -> d.date?.let { Format.date(it, now.atZone(zone).toLocalDate()) } ?: "Date"
    DeadlineType.EXACT -> d.instant?.let { Format.relative(it, now, zone) } ?: "Date & time"
    DeadlineType.NONE -> "None"
}

/**
 * "Turn on AI planning?" in place of the capture sheet: the note is kept, the key can be pasted,
 * what is sent is listed, and "Turn on & plan" records consent and continues. Saving the task
 * never depends on this sheet.
 */
@Composable
private fun AiSetupSheet(
    state: CaptureUiState,
    onDismiss: () -> Unit,
    onNotNow: () -> Unit,
    onTurnOn: (key: String) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val c = AutopilotTheme.colors
    val clipboard = LocalClipboardManager.current
    var key by remember { mutableStateOf("") }
    var showFull by remember { mutableStateOf(false) }
    val peek = state.draft.text.let { if (it.length > 64) it.take(62) + "…" else it }
    val needsKey = !state.hasKey
    AutopilotSheet(
        title = null, onDismiss = onDismiss, closeLabel = "Back to capture",
        titleLeading = { IconCircle(AutopilotIcons.Route, tint = c.route, background = c.routeSoft, size = 44.dp) },
    ) {
        Text("Turn on AI planning?", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp).semantics { contentDescription = "Turn on AI planning?" })
        Text(
            "Claude can turn what you typed into a draft: a clear title, steps and nudges. You edit and approve it before anything is saved. It uses your own Anthropic API key.",
            style = MaterialTheme.typography.bodyMedium, color = c.ink2,
        )
        Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(16.dp)).background(c.sunken).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(buildString { append("Your note is kept. "); append("“"); append(peek); append("”") }, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2)
        }
        Text("Anthropic API key", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        if (needsKey) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it.take(400) },
                    modifier = Modifier.weight(1f).testTag("capture_ai_key"),
                    placeholder = { Text("sk-ant-…", color = c.ink3) }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = state.aiSetupError != null,
                )
                SecondaryButton("Paste", onClick = { clipboard.getText()?.text?.let { key = it.trim().take(400) } }, icon = AutopilotIcons.Clip, minHeight = 52.dp)
            }
            Text("Create one at console.anthropic.com. Stored only on this phone, encrypted.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
        } else {
            Text("Key saved on this device" + (state.keyHint?.let { " (ends …$it)" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = c.ink)
        }
        state.aiSetupError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger, modifier = Modifier.padding(top = 6.dp).testTag("capture_ai_error")) }
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Sends only this text, the date and time, and your reminder settings",
                "Never your other tasks, history or notifications",
                "Only when you tap — nothing in the background",
            ).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(AutopilotIcons.Check, contentDescription = null, tint = c.done, modifier = Modifier.size(18.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink)
                }
            }
        }
        LinkButton(if (showFull) "Hide the full privacy and billing note" else "Read the full privacy and billing note", onClick = { showFull = !showFull }, modifier = Modifier.padding(top = 4.dp))
        if (showFull) {
            Text(AI_PRIVACY_TEXT, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("capture_ai_privacy"))
            Spacer(Modifier.height(8.dp))
            Text(AI_BILLING_TEXT, style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
        LinkButton("Open AI planning settings", onClick = onOpenAiSettings, modifier = Modifier.testTag("capture_ai_settings"))
        ButtonPair(
            modifier = Modifier.padding(top = 10.dp), primaryWeight = 1.3f,
            secondary = { m -> SecondaryButton("Not now", onClick = onNotNow, modifier = m.testTag("capture_ai_not_now")) },
            primary = { m ->
                PrimaryButton(
                    if (state.aiSetupBusy) "Turning on…" else "Turn on & plan",
                    onClick = { onTurnOn(key) },
                    enabled = !state.aiSetupBusy && (!needsKey || key.isNotBlank()),
                    modifier = m.testTag("capture_ai_turn_on"),
                )
            },
        )
    }
}
