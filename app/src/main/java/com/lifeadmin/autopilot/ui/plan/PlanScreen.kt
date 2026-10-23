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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.lifeadmin.autopilot.ui.components.ButtonPair
import com.lifeadmin.autopilot.ui.components.DraftPill
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.Motion
import com.lifeadmin.autopilot.ui.theme.rememberReducedMotion
import java.time.Instant
import java.time.ZoneId

/**
 * "Plan with AI" (new task) and "Move this forward" (existing task). Four honest states driven
 * by the real request lifecycle: input, drafting (the X cancels), review (nothing saved yet) and
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
    /** FOLLOW_THROUGH: the proposals were saved as a set; open the canonical Proposal Review for it. */
    onOpenReview: (setId: String) -> Unit = {},
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val reduced = rememberReducedMotion()
    val isEdit = s.mode == PlanningMode.FOLLOW_THROUGH
    val shownError = s.error?.takeIf { it != PlanningError.Cancelled }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAvailability() }

    LaunchedEffect(s.phase, s.savedTaskId) {
        if (s.phase == PlanPhase.SAVED) s.savedTaskId?.let { id ->
            if (s.savedWithReminders) onReminderEnabled()
            onOpenTask(id)
        }
    }
    LaunchedEffect(s.reviewSetId) { s.reviewSetId?.let(onOpenReview) }

    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(
                    AutopilotIcons.Close,
                    contentDescription = when (s.phase) { PlanPhase.REVIEW -> "Discard draft"; PlanPhase.WORKING -> "Cancel"; else -> "Close" },
                    onClick = { when (s.phase) { PlanPhase.REVIEW -> viewModel.reject(); PlanPhase.WORKING -> viewModel.cancel(); else -> onBack() } },
                )
                val title = when (s.phase) {
                    PlanPhase.REVIEW -> if (isEdit) "Move this forward" else "Review plan"
                    PlanPhase.INPUT -> if (shownError != null) "" else if (isEdit) "Move this forward" else "Plan with AI"
                    else -> ""
                }
                if (title.isNotEmpty()) {
                    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
                } else {
                    Spacer(Modifier.weight(1f))
                }
                when {
                    s.phase == PlanPhase.REVIEW && !isEdit -> DraftPill("Draft · not saved")
                    s.phase == PlanPhase.WORKING || (s.phase == PlanPhase.INPUT && shownError != null) -> DraftPill("Draft")
                }
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
                                val retry = shownError?.retryable == true
                                val request: @Composable (Modifier) -> Unit = { m ->
                                    PrimaryButton(
                                        text = if (retry) "Try again" else if (isEdit) "Get suggestions" else "Create plan",
                                        onClick = viewModel::requestPlan, enabled = ready && (isEdit || s.text.isNotBlank()),
                                        icon = if (retry) AutopilotIcons.Refresh else AutopilotIcons.Route,
                                        modifier = m.testTag("plan_request"),
                                    )
                                }
                                if (isEdit) request(Modifier.fillMaxWidth())
                                else ButtonPair(
                                    secondary = { m -> SecondaryButton("Save without AI", onClick = viewModel::saveWithoutAi, enabled = s.text.isNotBlank(), modifier = m.testTag("plan_save_without_ai")) },
                                    primary = request,
                                )
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
                            }
                            PlanPhase.REVIEW -> {
                                s.saveError?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp).testTag("plan_save_error")) }
                                val draft = s.draft
                                if (isEdit) {
                                    val n = draft?.let { changeCount(it, s.current, zone) } ?: 0
                                    ButtonPair(
                                        primaryWeight = 1.4f,
                                        secondary = { m -> SecondaryButton("Not now", onClick = { viewModel.reject(); onBack() }, enabled = !s.saving, modifier = m.testTag("plan_reject")) },
                                        primary = { m ->
                                            PrimaryButton(
                                                if (n == 0) "Apply changes" else "Apply $n change${if (n == 1) "" else "s"}",
                                                onClick = viewModel::approve,
                                                enabled = !s.saving && n > 0,
                                                modifier = m.testTag("plan_approve"),
                                            )
                                        },
                                    )
                                    Text(
                                        "Sent: this task's title, notes, steps, status, deadline and follow-up. Nothing else.",
                                        style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp),
                                    )
                                } else {
                                    draft?.let { Text(saveSummary(it), style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                                    ButtonPair(
                                        primaryWeight = 1.6f,
                                        secondary = { m -> SecondaryButton("Discard", onClick = viewModel::reject, enabled = !s.saving, modifier = m.testTag("plan_reject")) },
                                        primary = { m ->
                                            PrimaryButton(
                                                "Save task",
                                                onClick = viewModel::approve,
                                                enabled = !s.saving && draft?.title?.isNotBlank() == true,
                                                icon = AutopilotIcons.Check,
                                                modifier = m.testTag("plan_approve"),
                                            )
                                        },
                                    )
                                    Text("Nothing is saved until you tap Save task.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                                }
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
                        if (shownError != null) {
                            // The words are kept and shown read-only, as on the design board; editing is one tap away.
                            item { ErrorHero(shownError) }
                            if (s.text.isNotBlank()) item { YourWordsCard(s.text) }
                            item { LinkButton("Edit your words", onClick = viewModel::dismissError, modifier = Modifier.padding(horizontal = 4.dp).testTag("plan_edit_words")) }
                        } else {
                            item { InputCard(s, isEdit, onText = viewModel::setText) }
                        }
                        if (s.interrupted) item { MessageCard("The previous request was interrupted (the app was closed). Nothing was saved. Your text is still here — try again.", error = false, tag = "plan_interrupted") }
                        s.error?.let { e -> if (e == PlanningError.Cancelled) item { MessageCard(e.message, error = false, tag = "plan_error") } }
                        s.saveError?.let { item { MessageCard(it, error = true, tag = "plan_save_error_input") } }
                    }
                    PlanPhase.WORKING -> {
                        item { DraftingHero(isEdit) }
                        if (s.text.isNotBlank()) item { YourWordsCard(s.text) }
                        if (isEdit) s.current?.let { cur -> item { YourWordsCard(cur.task.title, caption = "This task") } }
                        item { DraftingStages(stage, isEdit) }
                    }
                    PlanPhase.REVIEW -> {
                        val draft = s.draft
                        if (draft != null) {
                            val nowInstant = now()
                            if (isEdit) followThroughItems(draft, s.current, zone, nowInstant, viewModel::updateDraft)
                            else reviewItems(draft, s.current, zone, ProposalMapper.today(nowInstant, zone), nowInstant, viewModel::updateDraft)
                            item { UsageNote(draft, s) }
                        }
                    }
                    PlanPhase.SAVED -> item { MessageCard("Saved.", error = false, tag = "plan_saved_card") }
                }
            }
        }
    }
}

/** Footer summary for a new task: "Saves 1 task · 3 steps · nudges 09:00 & 18:00 daily". */
fun saveSummary(d: ProposalDraft): String {
    val steps = d.steps.count { it.include } + if (d.addNextStep && d.nextStep.isNotBlank()) 1 else 0
    val nudges = if (!d.reminder.apply) "no nudges" else when (d.reminder.kind) {
        com.lifeadmin.autopilot.ai.DraftReminderKind.OFF -> "no nudges"
        com.lifeadmin.autopilot.ai.DraftReminderKind.ONCE -> "one nudge"
        com.lifeadmin.autopilot.ai.DraftReminderKind.DAILY -> "nudges ${d.reminder.times.joinToString(" & ")} daily"
        com.lifeadmin.autopilot.ai.DraftReminderKind.EVERY_N_DAYS -> "nudges every ${d.reminder.dayInterval} days"
    }
    return "Saves 1 task · $steps step${if (steps == 1) "" else "s"} · $nudges" + if (d.deadline.apply) " · deadline" else ""
}

/** The Capture field, not a Material form field: label above, sunken box, helper row below. */
@Composable
private fun InputCard(s: PlanUiState, isEdit: Boolean, onText: (String) -> Unit) {
    val c = AutopilotTheme.colors
    Column {
        if (isEdit) {
            s.current?.let { Text(it.task.title, style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.padding(bottom = 8.dp)) }
            Text("Claude suggests a next step, missing steps, or a better date for this task. You choose what to apply — nothing changes until you do.", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(bottom = 14.dp))
        }
        Text(if (isEdit) "Anything to add? (optional)" else "Describe what you need to do", style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = s.text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth().testTag("plan_text"),
            placeholder = { Text(if (isEdit) "e.g. I keep putting this off because…" else "e.g. Book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done", color = c.ink3) },
            minLines = 4, maxLines = 10,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 25.sp),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.sunken, unfocusedContainerColor = c.sunken,
                focusedBorderColor = c.lineStrong, unfocusedBorderColor = c.sunken, cursorColor = c.ink,
                focusedTextColor = c.ink, unfocusedTextColor = c.ink,
            ),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Text("Only this text, the date and time, and your reminder settings are sent to Anthropic.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.weight(1f).padding(end = 10.dp))
            TimeText("${s.text.length} / ${AiConfig.MAX_INPUT_CHARS}", size = 13, color = c.ink3)
        }
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
private fun DraftingHero(isEdit: Boolean) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 14.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("plan_working")) {
        Text(if (isEdit) "Looking for a next step…" else "Drafting a plan…", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, lineHeight = 34.sp), color = c.ink)
        Text(
            if (isEdit) "Claude is reading this task and looking for the next move. You review everything before anything changes."
            else "Claude is turning your words into steps and nudges. You review everything before it's saved.",
            style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Headline plus one explanation; the user's words follow as a read-only card. */
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
        val kept = "Nothing was saved or lost — your words are still here" + (if (e.retryable) ", and you can save them without AI." else ".")
        Text(
            if (e == PlanningError.Offline) "Your phone seems to be offline. $kept" else "${e.message.trimEnd('.')}. $kept",
            style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.padding(top = 8.dp).testTag("plan_error_message"),
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
private fun DraftingStages(stage: Int, isEdit: Boolean) {
    val c = AutopilotTheme.colors
    val labels = if (isEdit) listOf("Reading this task", "Looking for the next move", "Checking dates and quiet hours")
    else listOf("Reading your note", "Breaking it into steps", "Fitting nudges around your quiet hours")
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
