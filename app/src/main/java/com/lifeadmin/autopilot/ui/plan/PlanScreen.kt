package com.lifeadmin.autopilot.ui.plan

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.AiAvailability
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.PlanningError
import com.lifeadmin.autopilot.ai.PlanningMode
import com.lifeadmin.autopilot.ai.ProposalDraft
import com.lifeadmin.autopilot.ai.ProposalMapper
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.DraftPill
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.Motion
import com.lifeadmin.autopilot.ui.theme.rememberReducedMotion
import java.time.Instant
import java.time.ZoneId

/**
 * "Plan with AI" (new task) and "Move this forward" (existing task). Four honest states driven
 * by the real request lifecycle: input, drafting (with Cancel), review (nothing saved yet) and
 * saved. Errors keep the user's words and always offer "Save without AI".
 */
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    zone: ZoneId,
    now: () -> Instant,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onReminderEnabled: () -> Unit = {},
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val reduced = rememberReducedMotion()
    var confirmApply by remember { mutableStateOf(false) }
    val isEdit = s.mode == PlanningMode.FOLLOW_THROUGH
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAvailability() }

    LaunchedEffect(s.phase, s.savedTaskId) {
        if (s.phase == PlanPhase.SAVED) s.savedTaskId?.let { id ->
            if (s.savedWithReminders) onReminderEnabled()
            onOpenTask(id)
        }
    }

    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(
                    if (s.phase == PlanPhase.REVIEW) AutopilotIcons.Close else AutopilotIcons.Back,
                    contentDescription = if (s.phase == PlanPhase.REVIEW) "Discard draft" else if (s.phase == PlanPhase.WORKING) "Cancel" else "Back",
                    onClick = { when (s.phase) { PlanPhase.REVIEW -> viewModel.reject(); PlanPhase.WORKING -> viewModel.cancel(); else -> onBack() } },
                )
                Text(
                    when (s.phase) {
                        PlanPhase.REVIEW -> if (isEdit) "Review suggestions" else "Review plan"
                        PlanPhase.WORKING -> ""
                        else -> if (isEdit) "Move this forward" else "Plan with AI"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
                )
                if (s.phase == PlanPhase.REVIEW || s.phase == PlanPhase.WORKING) DraftPill(if (s.phase == PlanPhase.REVIEW) "Draft · not saved" else "Draft")
            }
        },
        bottomBar = {
            Surface(color = c.surface, contentColor = c.ink) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                    androidx.compose.material3.HorizontalDivider(color = c.line)
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        when (s.phase) {
                            PlanPhase.INPUT -> {
                                val ready = s.availability is AiAvailability.Ready
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (!isEdit) SecondaryButton("Save without AI", onClick = viewModel::saveWithoutAi, enabled = s.text.isNotBlank(), modifier = Modifier.weight(1f).testTag("plan_save_without_ai"))
                                    PrimaryButton(
                                        text = if (s.error?.retryable == true && s.error != PlanningError.Cancelled) "Retry" else if (isEdit) "Get suggestions" else "Create plan",
                                        onClick = viewModel::requestPlan, enabled = ready && (isEdit || s.text.isNotBlank()),
                                        icon = if (s.error?.retryable == true && s.error != PlanningError.Cancelled) AutopilotIcons.Refresh else AutopilotIcons.Route,
                                        modifier = Modifier.weight(1.2f).testTag("plan_request"),
                                    )
                                }
                            }
                            PlanPhase.WORKING -> {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                    Icon(AutopilotIcons.Lock, contentDescription = null, tint = c.ink2, modifier = Modifier.size(18.dp))
                                    Text(
                                        "Sent to Anthropic over an encrypted connection: this text, the date, time and time zone, your quiet hours and reminder defaults." +
                                            (if (isEdit) " For this task: its title, notes, steps, status, deadline and follow-up." else "") + " Nothing else.",
                                        style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.weight(1f),
                                    )
                                }
                                SecondaryButton("Cancel", onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("plan_cancel"))
                            }
                            PlanPhase.REVIEW -> {
                                s.saveError?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp).testTag("plan_save_error")) }
                                s.draft?.let { Text(saveSummary(it, isEdit), style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SecondaryButton("Discard", onClick = viewModel::reject, enabled = !s.saving, modifier = Modifier.weight(1f).testTag("plan_reject"))
                                    PrimaryButton(
                                        if (isEdit) "Apply changes…" else "Save task",
                                        onClick = { if (isEdit) confirmApply = true else viewModel.approve() },
                                        enabled = !s.saving && s.draft?.title?.isNotBlank() == true,
                                        icon = AutopilotIcons.Check,
                                        modifier = Modifier.weight(1.6f).testTag("plan_approve"),
                                    )
                                }
                                Text(
                                    "Nothing is saved until you tap ${if (isEdit) "Apply" else "Save task"}.",
                                    style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            PlanPhase.SAVED -> Text("Saved", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("plan_saved"))
                        }
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(targetState = s.phase, transitionSpec = Motion.crossfade(reduced), label = "planPhase", modifier = Modifier.padding(padding)) { phase ->
            LazyColumn(Modifier.fillMaxSize().testTag("plan_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (phase) {
                    PlanPhase.INPUT -> {
                        val availability = s.availability
                        if (availability != null && availability !is AiAvailability.Ready) item { SetupCard(availability, isEdit, onOpenAiSettings) }
                        s.error?.let { e -> if (e != PlanningError.Cancelled) item { ErrorHero(e) } }
                        item { InputCard(s, isEdit, enabled = true, onText = viewModel::setText) }
                        if (s.interrupted) item { MessageCard("The previous request was interrupted (the app was closed). Nothing was saved. Your text is still here — try again.", error = false, tag = "plan_interrupted") }
                        s.error?.let { e -> if (e == PlanningError.Cancelled) item { MessageCard(e.message, error = false, tag = "plan_error") } }
                        s.saveError?.let { item { MessageCard(it, error = true, tag = "plan_save_error_input") } }
                    }
                    PlanPhase.WORKING -> {
                        item { DraftingHero(isEdit, s.modelLabel) }
                        if (s.text.isNotBlank()) item { YourWordsCard(s.text) }
                        if (isEdit) s.current?.let { cur -> item { YourWordsCard(cur.task.title, caption = "This task") } }
                        item { DraftingStages(stage) }
                    }
                    PlanPhase.REVIEW -> {
                        val draft = s.draft
                        if (draft != null) {
                            val nowInstant = now()
                            reviewItems(draft, s.current, zone, ProposalMapper.today(nowInstant, zone), nowInstant, viewModel::updateDraft)
                            item { UsageNote(draft, s) }
                        }
                    }
                    PlanPhase.SAVED -> item { MessageCard("Saved.", error = false, tag = "plan_saved_card") }
                }
            }
        }
    }

    if (confirmApply) {
        val draft = s.draft
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text("Apply these changes?") },
            text = { Text(draft?.let { changeSummary(it) } ?: "", modifier = Modifier.testTag("plan_confirm_summary")) },
            confirmButton = { TextButton(onClick = { confirmApply = false; viewModel.approve() }, modifier = Modifier.testTag("plan_confirm_apply")) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("Back") } },
        )
    }
}

/** Plain-language list of the selected changes, for the confirmation dialog. */
fun changeSummary(d: ProposalDraft): String = buildList {
    if (d.applyTitle) add("• Rename to “${d.title.trim()}”")
    if (d.applyNotes) add("• Replace the notes")
    val steps = d.steps.count { it.include } + if (d.addNextStep && d.nextStep.isNotBlank()) 1 else 0
    if (steps > 0) add("• Add $steps checklist step(s)")
    if (d.deadline.apply) add("• Set the deadline to ${d.deadline.date}${d.deadline.time?.let { " $it" } ?: ""}")
    if (d.reminder.apply) add("• Replace the reminders")
    if (d.waiting.apply) add("• Mark as Waiting")
    if (d.applyPriority && d.priority != null) add("• Set priority to ${d.priority.lowercase()}")
}.ifEmpty { listOf("Nothing is selected yet.") }.joinToString("\n")

/** Footer summary: "Saves 1 task · 3 steps · nudges 09:00 & 18:00 daily". */
fun saveSummary(d: ProposalDraft, isEdit: Boolean): String {
    if (isEdit) {
        val n = listOf(d.applyTitle, d.applyNotes, d.deadline.apply, d.reminder.apply, d.waiting.apply, d.applyPriority && d.priority != null).count { it } +
            (d.steps.count { it.include } + if (d.addNextStep && d.nextStep.isNotBlank()) 1 else 0).coerceAtMost(1)
        return if (n == 0) "Nothing selected yet" else "Applies $n change${if (n == 1) "" else "s"} to this task"
    }
    val steps = d.steps.count { it.include } + if (d.addNextStep && d.nextStep.isNotBlank()) 1 else 0
    val nudges = if (!d.reminder.apply) "no nudges" else when (d.reminder.kind) {
        com.lifeadmin.autopilot.ai.DraftReminderKind.OFF -> "no nudges"
        com.lifeadmin.autopilot.ai.DraftReminderKind.ONCE -> "one nudge"
        com.lifeadmin.autopilot.ai.DraftReminderKind.DAILY -> "nudges ${d.reminder.times.joinToString(" & ")} daily"
        com.lifeadmin.autopilot.ai.DraftReminderKind.EVERY_N_DAYS -> "nudges every ${d.reminder.dayInterval} days"
    }
    return "Saves 1 task · $steps step${if (steps == 1) "" else "s"} · $nudges" + if (d.deadline.apply) " · deadline" else ""
}

@Composable
private fun InputCard(s: PlanUiState, isEdit: Boolean, enabled: Boolean, onText: (String) -> Unit) {
    val c = AutopilotTheme.colors
    Column {
        if (isEdit) {
            s.current?.let { Text(it.task.title, style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.padding(bottom = 8.dp)) }
            Text("Claude suggests a next step, missing steps, or a better date for this task. You choose what to apply — nothing changes until you do.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        }
        OutlinedTextField(
            value = s.text,
            onValueChange = onText,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("plan_text"),
            label = { Text(if (isEdit) "Anything to add? (optional)" else "Describe what you need to do") },
            placeholder = { Text(if (isEdit) "e.g. I keep putting this off because…" else "e.g. Book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done") },
            minLines = 3, maxLines = 10, shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            supportingText = { Text("${s.text.length}/${AiConfig.MAX_INPUT_CHARS} · Only this text, the date/time and your reminder settings are sent to Anthropic.") },
        )
    }
}

@Composable
private fun SetupCard(a: AiAvailability, isEdit: Boolean, onOpenAiSettings: () -> Unit) {
    val c = AutopilotTheme.colors
    val text = when (a) {
        AiAvailability.Off -> "AI planning is optional and currently off. It can turn a sentence into a reviewed task with steps and nudges, using your own Anthropic API key." +
            if (isEdit) "" else " You can still save this right now without AI."
        is AiAvailability.NeedsSetup -> buildString {
            append("AI planning needs setting up: ")
            append(listOfNotNull("review the privacy note".takeIf { a.needsConsent }, "add your Anthropic API key".takeIf { a.needsKey }).joinToString(" and "))
            append(".")
        }
        is AiAvailability.Ready -> ""
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.routeSoft).padding(16.dp).testTag("plan_setup")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(AutopilotIcons.Route, contentDescription = null, tint = c.onRouteSoft, modifier = Modifier.size(22.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onRouteSoft)
        }
        PrimaryButton("Set up AI planning", onClick = onOpenAiSettings, modifier = Modifier.padding(top = 12.dp).testTag("plan_open_ai_settings"), minHeight = 48.dp)
    }
}

@Composable
private fun MessageCard(text: String, error: Boolean, tag: String) {
    val c = AutopilotTheme.colors
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = if (error) c.onDangerSoft else c.ink2,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (error) c.dangerSoft else c.sunken).padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
    )
}

@Composable
private fun DraftingHero(isEdit: Boolean, modelLabel: String) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 14.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("plan_working")) {
        Text(if (isEdit) "Looking for a next step…" else "Drafting a plan…", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, lineHeight = 34.sp), color = c.ink)
        Text(
            "$modelLabel is turning ${if (isEdit) "this task" else "your words"} into ${if (isEdit) "suggestions" else "steps and nudges"}. You review everything before it's saved.",
            style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ErrorHero(e: PlanningError) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 14.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("plan_error")) {
        IconCircle(if (e == PlanningError.Offline) AutopilotIcons.WifiOff else AutopilotIcons.Warn, tint = c.onSignalSoft, background = c.signalSoft, size = 56.dp)
        Text(
            when (e) {
                PlanningError.Offline -> "Couldn't reach Anthropic"
                PlanningError.NoApiKey -> "No API key yet"
                PlanningError.DailyLimit -> "Daily limit reached"
                else -> "That didn't work"
            },
            style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.padding(top = 16.dp),
        )
        Text(e.message, style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Nothing was saved or lost — your words are still here" + (if (e.retryable) ", and you can save them without AI." else "."),
            style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun YourWordsCard(text: String, caption: String = "Your words") {
    val c = AutopilotTheme.colors
    SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Text(caption, style = MaterialTheme.typography.labelMedium, color = c.ink3)
        Text("“$text”", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp), color = c.ink, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Three stages driven by the real request lifecycle (sent → waiting → checking), never by timers. */
@Composable
private fun DraftingStages(stage: Int) {
    val c = AutopilotTheme.colors
    val labels = listOf("Sending your note", "Claude is drafting", "Checking dates, times and quiet hours")
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        labels.forEachIndexed { i, label ->
            val done = i < stage
            val active = i == stage
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(if (done) c.done else if (active) c.routeSoft else Color.Transparent)
                        .border(2.dp, if (done) c.done else if (active) c.route else c.lineStrong, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(AutopilotIcons.Check, contentDescription = null, tint = c.onDone, modifier = Modifier.size(16.dp))
                    else if (active) Box(Modifier.size(10.dp).clip(CircleShape).background(c.route))
                }
                Text(label, style = MaterialTheme.typography.bodyLarge, color = if (done || active) c.ink else c.ink3, modifier = Modifier.semantics { contentDescription = "$label, ${if (done) "done" else if (active) "in progress" else "pending"}" })
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun UsageNote(draft: ProposalDraft, s: PlanUiState) {
    val c = AutopilotTheme.colors
    Text(
        "Drafted by ${AiConfig.label(draft.model)}" + (s.usage?.let { " · ${it.inputTokens} in / ${it.outputTokens} out tokens" } ?: "") +
            ". AI can be wrong — check dates and times before saving.",
        style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}
