package com.lifeadmin.autopilot.ui.research

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.research.DetailField
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchFocus
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.ButtonPair
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.DraftPill
import com.lifeadmin.autopilot.ui.components.InkSwitch
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SectionTitle
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.SwitchRow
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/**
 * Research online (preflight): say what to find out, decide what to share, and authorize one paid
 * request. Nothing leaves the device until Start; the header pill says so.
 */
@Composable
fun ResearchPreflightScreen(
    viewModel: ResearchPreflightViewModel,
    onBack: () -> Unit,
    onStarted: (runId: String, question: String) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val blocker = s.gate?.blocker?.takeIf { it != ResearchError.NoResearchConsent }
    val gate = s.gate

    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AutopilotIcons.Close, contentDescription = "Cancel", onClick = onBack)
                Text("Research online", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
                DraftPill("Nothing sent yet", Modifier.testTag("preflight_pill"))
            }
        },
        bottomBar = {
            Surface(color = c.surface, contentColor = c.ink) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                    HorizontalDivider(color = c.line)
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        if (blocker != null) {
                            val setup = blocker == ResearchError.Disabled || blocker == ResearchError.NoApiKey || blocker == ResearchError.NoConsent
                            ButtonPair(
                                secondary = { m -> SecondaryButton("Cancel", onClick = onBack, modifier = m) },
                                primary = { m ->
                                    if (setup) PrimaryButton("Set up AI planning", onClick = onOpenAiSettings, modifier = m.testTag("preflight_setup"))
                                    else PrimaryButton("Start research", onClick = {}, enabled = false, modifier = m.testTag("preflight_start"))
                                },
                            )
                        } else {
                            ButtonPair(
                                secondary = { m -> SecondaryButton("Cancel", onClick = onBack, modifier = m.testTag("preflight_cancel")) },
                                primary = { m ->
                                    PrimaryButton(
                                        "Start · up to ${ResearchConfig.MAX_SEARCHES_PER_REQUEST} searches",
                                        onClick = { viewModel.start(onStarted) }, enabled = s.canStart && !s.loading, icon = AutopilotIcons.Research,
                                        modifier = m.testTag("preflight_start"),
                                    )
                                },
                            )
                        }
                        Text(
                            "Sends once when you tap. No automatic retries, nothing in the background.",
                            style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("preflight_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            if (blocker != null) {
                item {
                    ResearchBanner(
                        blocker.headline, blocker.message, warn = blocker != ResearchError.Offline, modifier = Modifier.padding(bottom = 12.dp),
                        icon = if (blocker == ResearchError.Offline) AutopilotIcons.WifiOff else AutopilotIcons.Lock, tag = "preflight_blocker",
                    )
                }
            }
            s.error?.let { e ->
                item { ResearchBanner(e.headline, e.message, warn = true, modifier = Modifier.padding(bottom = 12.dp), icon = AutopilotIcons.Warn, tag = "preflight_error") }
            }
            item {
                Text("For: ${s.taskTitle}", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(bottom = 10.dp).testTag("preflight_for"))
                Text("What should I find out?", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
                OutlinedTextField(
                    value = s.question,
                    onValueChange = viewModel::setQuestion,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("preflight_question"),
                    placeholder = { Text("e.g. When do advance tickets go on sale, and what do they cost?", color = c.ink3) },
                    minLines = 2, maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supportingText = {
                        val over = s.question.length > ResearchConfig.MAX_QUESTION_CHARS
                        Text(if (over) "${s.question.length} / ${ResearchConfig.MAX_QUESTION_CHARS} — too long" else "${s.question.length} / ${ResearchConfig.MAX_QUESTION_CHARS}", color = if (over) c.danger else c.ink3)
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ink, unfocusedBorderColor = c.lineStrong, cursorColor = c.ink, focusedTextColor = c.ink, unfocusedTextColor = c.ink, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface),
                )
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResearchFocus.entries.forEach { f -> ChoiceChip(f.label, selected = f in s.focus, onClick = { viewModel.toggleFocus(f) }, modifier = Modifier.testTag("focus_${f.name}")) }
                }
            }
            item {
                SectionTitle("Make it specific", Modifier.padding(top = 22.dp, bottom = 4.dp))
                Text("Optional. Typed details make searches sharper; nothing else about you is added.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(bottom = 8.dp))
                val oneColumn = LocalDensity.current.fontScale >= 1.3f
                val fields = DetailField.entries
                if (oneColumn) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { fields.forEach { f -> DetailFieldInput(f, s.details[f].orEmpty(), Modifier.fillMaxWidth()) { viewModel.setDetail(f, it) } } }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        fields.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { f -> DetailFieldInput(f, s.details[f].orEmpty(), Modifier.weight(1f)) { viewModel.setDetail(f, it) } }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (s.jurisdictionGap) {
                    ResearchBanner(
                        "Which country or region?", "This looks like it depends on where you live. Without it, you'll get general guidance only.",
                        modifier = Modifier.padding(top = 10.dp), tag = "preflight_gap",
                    )
                }
                Text("Type a town or station — never your home address. Your location isn’t read.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                SectionTitle("What Claude will see", Modifier.padding(top = 22.dp, bottom = 4.dp))
                Text("Only what’s switched on below, for this one request.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(bottom = 8.dp))
                SurfaceCard(radius = 20.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("preflight_scope")) {
                    SwitchRow("Your question", checked = true, onCheckedChange = {}, subtitle = "Always sent, with the details you typed", enabled = false)
                    SwitchRow("Task title", checked = s.scope.shareTitle, onCheckedChange = { viewModel.setScope(s.scope.copy(shareTitle = it)) }, subtitle = s.taskTitle, divider = true, switchTestTag = "scope_title")
                    SwitchRow(
                        "Notes", checked = s.scope.shareNotes && s.notesPreview != null, onCheckedChange = { viewModel.setScope(s.scope.copy(shareNotes = it)) },
                        subtitle = s.notesPreview?.lineSequence()?.first()?.take(80) ?: "No notes on this task", divider = true, enabled = s.notesPreview != null, switchTestTag = "scope_notes",
                    )
                    SwitchRow(
                        "Steps", checked = s.scope.shareSteps && s.stepsCount > 0, onCheckedChange = { viewModel.setScope(s.scope.copy(shareSteps = it)) },
                        subtitle = if (s.stepsCount == 0) "No steps yet" else "${s.stepsCount} step titles, so moves don't repeat them", divider = true, enabled = s.stepsCount > 0, switchTestTag = "scope_steps",
                    )
                    SwitchRow("Today’s date and language", checked = s.scope.shareLocale, onCheckedChange = { viewModel.setScope(s.scope.copy(shareLocale = it)) }, subtitle = "From this phone: ${s.localeLine}", divider = true, switchTestTag = "scope_locale")
                    Column(Modifier.fillMaxWidth().background(c.sunken).padding(16.dp)) {
                        Text("Never in the request", style = MaterialTheme.typography.labelLarge, color = c.ink)
                        Text(
                            "Your other tasks, Inbox, history, reminders, contacts or location. Your API key authenticates the request with Anthropic and is never part of the question or searches.",
                            style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            item {
                SectionTitle("Web search costs extra", Modifier.padding(top = 22.dp, bottom = 8.dp))
                SurfaceCard(radius = 20.dp, modifier = Modifier.testTag("preflight_cost")) {
                    Text(
                        "Up to ${ResearchConfig.MAX_SEARCHES_PER_REQUEST} web searches plus model tokens, billed by Anthropic to your API key. Searches are charged on top of tokens.",
                        style = MaterialTheme.typography.bodyMedium, color = c.ink2,
                    )
                    ResearchStamp(
                        "Research today ${gate?.requestsToday ?: 0} of ${ResearchConfig.MAX_REQUESTS_PER_DAY}" + (gate?.model?.let { " · model ${AiConfig.label(it)}" } ?: ""),
                        Modifier.padding(top = 10.dp).testTag("preflight_usage"),
                    )
                }
                Text(
                    "Research only reads the web. It never buys tickets, books, sends messages or fills in forms.",
                    style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    if (s.showExplainer) {
        ExplainerSheet(onAccept = { viewModel.acceptExplainer(onStarted) }, onDismiss = viewModel::dismissExplainer)
    }
}

@Composable
private fun DetailFieldInput(field: DetailField, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    val c = AutopilotTheme.colors
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier.testTag("detail_${field.name}"),
        label = { Text(field.label) }, placeholder = { Text(field.placeholder, color = c.ink3) }, singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ink, unfocusedBorderColor = c.lineStrong, cursorColor = c.ink, focusedTextColor = c.ink, unfocusedTextColor = c.ink, focusedLabelColor = c.ink2, unfocusedLabelColor = c.ink2, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface),
    )
}

