package com.lifeadmin.autopilot.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.AiAvailability
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.PlanningError
import com.lifeadmin.autopilot.ai.PlanningMode
import com.lifeadmin.autopilot.ai.ProposalMapper
import com.lifeadmin.autopilot.ai.ProposalDraft
import java.time.Instant
import java.time.ZoneId

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
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Move this forward" else "Plan with AI") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    when (s.phase) {
                        PlanPhase.INPUT -> {
                            val ready = s.availability is AiAvailability.Ready
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!isEdit) OutlinedButton(
                                    onClick = { viewModel.saveWithoutAi(onOpenTask) }, enabled = s.text.isNotBlank(),
                                    modifier = Modifier.weight(1f).testTag("plan_save_without_ai"),
                                ) { Text("Save without AI") }
                                Button(
                                    onClick = viewModel::requestPlan, enabled = ready && (isEdit || s.text.isNotBlank()),
                                    modifier = Modifier.weight(1f).testTag("plan_request"),
                                ) { Text(if (s.error?.retryable == true && s.error != PlanningError.Cancelled) "Retry" else if (isEdit) "Get suggestion" else "Create plan") }
                            }
                        }
                        PlanPhase.WORKING -> OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth().testTag("plan_cancel")) { Text("Cancel") }
                        PlanPhase.REVIEW -> {
                            s.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp).testTag("plan_save_error")) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = viewModel::reject, enabled = !s.saving, modifier = Modifier.weight(1f).testTag("plan_reject")) { Text("Discard") }
                                Button(
                                    onClick = { if (isEdit) confirmApply = true else viewModel.approve() },
                                    enabled = !s.saving && s.draft?.title?.isNotBlank() == true,
                                    modifier = Modifier.weight(1f).testTag("plan_approve"),
                                ) { Text(if (isEdit) "Apply changes…" else "Create task") }
                            }
                            Text(
                                "Nothing is saved until you tap ${if (isEdit) "Apply" else "Create task"}.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        PlanPhase.SAVED -> Text("Saved", modifier = Modifier.testTag("plan_saved"))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("plan_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            when (s.phase) {
                PlanPhase.INPUT, PlanPhase.WORKING -> {
                    val availability = s.availability
                    if (availability != null && availability !is AiAvailability.Ready) item { SetupCard(availability, isEdit, onOpenAiSettings) }
                    item { InputCard(s, isEdit, enabled = s.phase == PlanPhase.INPUT, onText = viewModel::setText) }
                    if (s.phase == PlanPhase.WORKING) item {
                        Column(Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("plan_working")) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Asking ${s.modelLabel}… This usually takes a few seconds.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    if (s.interrupted) item { MessageCard("The previous request was interrupted (the app was closed). Nothing was saved. Your text is still here — try again.", error = false, tag = "plan_interrupted") }
                    s.error?.let { e -> item { MessageCard(e.message, error = e != PlanningError.Cancelled, tag = "plan_error") } }
                    s.saveError?.let { item { MessageCard(it, error = true, tag = "plan_save_error_input") } }
                }
                PlanPhase.REVIEW -> {
                    val draft = s.draft
                    if (draft != null) {
                        val nowInstant = now()
                        reviewItems(draft, s.current, zone, ProposalMapper.today(nowInstant, zone), viewModel::updateDraft)
                        item { UsageNote(draft, s) }
                    }
                }
                PlanPhase.SAVED -> item { MessageCard("Saved.", error = false, tag = "plan_saved_card") }
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

@Composable
private fun InputCard(s: PlanUiState, isEdit: Boolean, enabled: Boolean, onText: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (isEdit) {
            s.current?.let { Text(it.task.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            Text("Claude suggests a next step, missing steps, or a better date for this task. You review every change before it's applied.", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = s.text,
            onValueChange = onText,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("plan_text"),
            label = { Text(if (isEdit) "Anything to add? (optional)" else "Describe what you need to do") },
            placeholder = { Text(if (isEdit) "e.g. I keep putting this off because…" else "e.g. Book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done") },
            minLines = 3, maxLines = 10,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            supportingText = { Text("${s.text.length}/${AiConfig.MAX_INPUT_CHARS} · Only this text, the date/time and your reminder settings are sent to Anthropic.") },
        )
    }
}

@Composable
private fun SetupCard(a: AiAvailability, isEdit: Boolean, onOpenAiSettings: () -> Unit) {
    val text = when (a) {
        AiAvailability.Off -> "AI planning is optional and currently off. It can turn a sentence into a reviewed task with steps and reminders, using your own Anthropic API key." +
            if (isEdit) "" else " You can still save this right now without AI."
        is AiAvailability.NeedsSetup -> buildString {
            append("AI planning needs setting up: ")
            append(listOfNotNull("review the privacy note".takeIf { a.needsConsent }, "add your Anthropic API key".takeIf { a.needsKey }).joinToString(" and "))
            append(".")
        }
        is AiAvailability.Ready -> ""
    }
    Card(
        Modifier.fillMaxWidth().padding(12.dp).testTag("plan_setup"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Button(onClick = onOpenAiSettings, modifier = Modifier.padding(top = 8.dp).testTag("plan_open_ai_settings")) { Text("Set up AI planning") }
        }
    }
}

@Composable
private fun MessageCard(text: String, error: Boolean, tag: String) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
        colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp), color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsageNote(draft: ProposalDraft, s: PlanUiState) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Text(
            "Drafted by ${AiConfig.label(draft.model)}" + (s.usage?.let { " · ${it.inputTokens} in / ${it.outputTokens} out tokens" } ?: "") +
                ". AI can be wrong — check dates and times before saving.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
