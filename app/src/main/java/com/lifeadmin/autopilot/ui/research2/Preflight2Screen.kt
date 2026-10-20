package com.lifeadmin.autopilot.ui.research2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research2.DeliverableTemplate
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.ResearchMode
import com.lifeadmin.autopilot.ai.research2.ResearchModes
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.ButtonPair
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.DraftPill
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SectionTitle
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.SwitchRow
import com.lifeadmin.autopilot.ui.research.ExplainerSheet
import com.lifeadmin.autopilot.ui.research.ResearchBanner
import com.lifeadmin.autopilot.ui.research.ResearchStamp
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/**
 * Research online (M3A.2 preflight): the request, what you'll get (template + editable requirements
 * extracted on the phone), how deep (mode cards + Adjust), what Claude will see, the estimate. Nothing
 * leaves the device until Start.
 */
@Composable
fun Preflight2Screen(
    viewModel: Preflight2ViewModel,
    onBack: () -> Unit,
    onStarted: (runId: String, sessionId: String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val blocker = s.gate?.blocker?.takeIf { it != ResearchError.NoResearchConsent }
    var confirmBudget by remember { mutableStateOf(false) }
    val config = s.config

    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            val stacked = LocalDensity.current.fontScale >= 1.3f
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(AutopilotIcons.Close, contentDescription = "Cancel", onClick = onBack)
                    Text("Research online", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
                    if (!stacked) DraftPill("Nothing sent yet", Modifier.testTag("preflight_pill"))
                }
                if (stacked) DraftPill("Nothing sent yet", Modifier.padding(start = 12.dp, bottom = 6.dp).testTag("preflight_pill"))
            }
        },
        bottomBar = {
            Surface(color = c.surface, contentColor = c.ink) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                    HorizontalDivider(color = c.line)
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        if (blocker == null) EstimateLine(config, s.monthSoFar, s.monthlyBudgetCents, Modifier.padding(bottom = 10.dp).testTag("preflight_estimate"), onOpenUsage = onOpenUsage)
                        if (blocker != null) {
                            val setup = blocker == ResearchError.Disabled || blocker == ResearchError.NoApiKey || blocker == ResearchError.NoConsent
                            ButtonPair(
                                secondary = { m -> SecondaryButton("Cancel", onClick = onBack, modifier = m.testTag("preflight_cancel")) },
                                primary = { m ->
                                    if (setup) PrimaryButton("Set up AI planning", onClick = onOpenAiSettings, modifier = m.testTag("preflight_setup"))
                                    else PrimaryButton("Start research", onClick = {}, enabled = false, modifier = m.testTag("preflight_start"))
                                },
                            )
                        } else {
                            ButtonPair(
                                secondary = { m -> SecondaryButton("Cancel", onClick = onBack, modifier = m.testTag("preflight_cancel")) },
                                primary = { m ->
                                    // Half-width at 1.0 (next to Cancel) and full-width but large at >= 1.3: the search
                                    // count lives in the Adjust panel and the estimate line, so the button names the mode.
                                    val compact = LocalDensity.current.fontScale >= 1.3f
                                    PrimaryButton(
                                        if (compact) "Start research" else "Start · ${config.mode.label}",
                                        onClick = { if (viewModel.needsBudgetConfirmation()) confirmBudget = true else viewModel.start(onStarted) },
                                        enabled = s.canStart && !s.loading, icon = AutopilotIcons.Research, modifier = m.testTag("preflight_start"),
                                    )
                                },
                            )
                        }
                        Text("Billed by Anthropic to your API key. Starts only when you tap; no retries, nothing in the background.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("preflight_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            if (blocker != null) item {
                ResearchBanner(blocker.headline, blocker.message, warn = blocker != ResearchError.Offline, modifier = Modifier.padding(bottom = 12.dp), icon = if (blocker == ResearchError.Offline) AutopilotIcons.WifiOff else AutopilotIcons.Lock, tag = "preflight_blocker")
            }
            s.error?.let { e -> item { ResearchBanner(e.headline, e.message, warn = true, modifier = Modifier.padding(bottom = 12.dp), icon = AutopilotIcons.Warn, tag = "preflight_error") } }
            s.monthWarning?.let { w -> item { ResearchBanner("Monthly AI budget", w, modifier = Modifier.padding(bottom = 12.dp), tag = "preflight_month", action = { LinkButton("See AI usage", onClick = onOpenUsage, modifier = Modifier.padding(top = 4.dp)) }) } }
            item {
                Text("For: ${s.taskTitle}", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(bottom = 10.dp).testTag("preflight_for"))
                Text("What should I find out?", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
                OutlinedTextField(
                    value = s.request, onValueChange = viewModel::setRequest,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("preflight_question"),
                    placeholder = { Text("e.g. Best men's fragrance up to \$150 in the US, as a gift", color = c.ink3) },
                    minLines = 2, maxLines = 8, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supportingText = { val over = s.request.length > Research2Config.MAX_REQUEST_CHARS; Text(if (over) "${s.request.length} / ${Research2Config.MAX_REQUEST_CHARS} — too long" else "${s.request.length} / ${Research2Config.MAX_REQUEST_CHARS}", color = if (over) c.danger else c.ink3) },
                    shape = RoundedCornerShape(16.dp), colors = fieldColors(),
                )
            }
            item {
                SectionTitle("What you'll get", Modifier.padding(top = 18.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("preflight_templates")) {
                    DeliverableTemplate.entries.forEach { t -> ChoiceChip(t.label, selected = s.template == t, onClick = { viewModel.setTemplate(t) }, modifier = Modifier.testTag("template_${t.name}")) }
                }
            }
            item {
                SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.padding(top = 10.dp).testTag("preflight_requirements")) {
                    if (s.requirements.isEmpty()) Text("Type your request above; the requirements appear here.", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(14.dp))
                    s.requirements.forEachIndexed { i, r -> RequirementRow(r, onRemove = { viewModel.removeRequirement(r.id) }, divider = i > 0) }
                    HorizontalDivider(color = c.line)
                    OutlinedTextField(
                        value = s.newRequirement, onValueChange = viewModel::setNewRequirement, singleLine = true,
                        placeholder = { Text("Add a requirement, e.g. vegan capsules", color = c.ink3) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences), keyboardActions = KeyboardActions(onDone = { viewModel.addRequirement() }),
                        trailingIcon = { if (s.newRequirement.isNotBlank()) RoundIconButton(AutopilotIcons.Plus, contentDescription = "Add requirement", onClick = viewModel::addRequirement, modifier = Modifier.testTag("req_add")) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).testTag("req_new"), shape = RoundedCornerShape(12.dp), colors = fieldColors(),
                    )
                }
                Text(
                    if (s.extractorUnsure) "Found on your phone from your words — the app wasn't sure, so check the list. Nothing sent." else "Found on your phone from your words — nothing sent. Each one is checked off in the results.",
                    style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp),
                )
            }
            item { SectionTitle("How deep", Modifier.padding(top = 18.dp, bottom = 8.dp)) }
            items(ResearchModes.PRESETS, key = { "mode_" + it.mode.name }) { p ->
                val badge = if (p.mode == ResearchMode.THOROUGH && (s.template == DeliverableTemplate.SHORTLIST || s.template == DeliverableTemplate.PICK_PER_ITEM)) "Best for buying decisions" else null
                ModeCard(p, selected = s.mode == p.mode, badge = badge, modelOverride = Preflight2Ui.settingsModelFor(p.mode, s.settings), onClick = { viewModel.setMode(p.mode) }, modifier = Modifier.padding(bottom = 8.dp))
            }
            item {
                val adjustLabel = buildString {
                    append(if (s.adjustOpen) "Hide adjustments" else "Adjust")
                    if (!s.adjustOpen) append(" · ${AiConfig.label(config.model).removePrefix("Claude ")} · ${config.searchBudget} searches · " + (config.spendLimitCents?.let { "stop near ${UsdEstimate.format(it / 100.0)}" } ?: "no limit"))
                }
                LinkButton(adjustLabel, onClick = viewModel::toggleAdjust, modifier = Modifier.testTag("preflight_adjust"))
                if (config.mode == ResearchMode.CUSTOM) ResearchStamp("Custom · ${AiConfig.label(config.model)} · ${config.searchBudget} searches", Modifier.padding(top = 6.dp).testTag("preflight_custom"))
            }
            if (s.adjustOpen) item {
                SurfaceCard(radius = 18.dp, modifier = Modifier.padding(top = 4.dp).testTag("adjust_panel")) {
                    AdjustPanel(model = config.model, onModel = viewModel::setModel, searches = config.searchBudget, onSearches = viewModel::setSearches, limitCents = config.spendLimitCents, onLimit = viewModel::setLimit)
                }
            }
            item {
                SectionTitle("What Claude will see", Modifier.padding(top = 18.dp, bottom = 4.dp))
                SurfaceCard(radius = 20.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("preflight_scope")) {
                    SwitchRow("Your request and requirements", checked = true, onCheckedChange = {}, subtitle = "Always sent, exactly as listed above", enabled = false)
                    SwitchRow("Task title", checked = s.scope.shareTitle, onCheckedChange = { viewModel.setScope(s.scope.copy(shareTitle = it)) }, subtitle = s.taskTitle, divider = true, switchTestTag = "scope_title")
                    SwitchRow("Notes", checked = s.scope.shareNotes && s.notesPreview != null, onCheckedChange = { viewModel.setScope(s.scope.copy(shareNotes = it)) }, subtitle = s.notesPreview?.lineSequence()?.first()?.take(80) ?: "No notes on this task", divider = true, enabled = s.notesPreview != null, switchTestTag = "scope_notes")
                    SwitchRow("Steps", checked = s.scope.shareSteps && s.stepsCount > 0, onCheckedChange = { viewModel.setScope(s.scope.copy(shareSteps = it)) }, subtitle = if (s.stepsCount == 0) "No steps yet" else "${s.stepsCount} step titles, so proposals don't repeat them", divider = true, enabled = s.stepsCount > 0, switchTestTag = "scope_steps")
                    SwitchRow("Today's date and language", checked = s.scope.shareLocale, onCheckedChange = { viewModel.setScope(s.scope.copy(shareLocale = it)) }, subtitle = "From this phone: ${s.localeLine}", divider = true, switchTestTag = "scope_locale")
                    Column(Modifier.fillMaxWidth().background(c.sunken).padding(16.dp)) {
                        Text("Never sent", style = MaterialTheme.typography.labelLarge, color = c.ink)
                        Text("Your other tasks, Inbox, history, location, contacts or your API key (it only authenticates the request).", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            item {
                Text("Research only reads the web. It never buys, books, signs up or messages anyone. Your task changes only when you apply proposals.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 12.dp))
                ResearchStamp("Research today ${s.gate?.requestsToday ?: 0} of ${Research2Config.MAX_SESSIONS_PER_DAY}", Modifier.padding(top = 10.dp).testTag("preflight_usage"))
            }
        }
    }

    if (s.showExplainer) ExplainerSheet(onAccept = { viewModel.acceptExplainer(onStarted) }, onDismiss = viewModel::dismissExplainer)
    if (confirmBudget) {
        AlertDialog(
            onDismissRequest = { confirmBudget = false },
            title = { Text("Past your monthly budget") },
            text = { Text("This month's AI spend is already at or above the budget you set. Start anyway? The estimate for this research is ${config.estimate()?.let { UsdEstimate.formatRange(it.lowUsd, it.highUsd) } ?: "unknown"}.") },
            confirmButton = { TextButton(onClick = { confirmBudget = false; viewModel.confirmOverBudget(); viewModel.start(onStarted) }, modifier = Modifier.testTag("budget_confirm")) { Text("Start anyway") } },
            dismissButton = { TextButton(onClick = { confirmBudget = false }) { Text("Not now") } },
        )
    }
}

@Composable
internal fun fieldColors() = AutopilotTheme.colors.let { c ->
    OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ink, unfocusedBorderColor = c.lineStrong, cursorColor = c.ink, focusedTextColor = c.ink, unfocusedTextColor = c.ink, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, focusedLabelColor = c.ink2, unfocusedLabelColor = c.ink2)
}
