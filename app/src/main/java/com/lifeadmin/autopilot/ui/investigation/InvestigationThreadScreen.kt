package com.lifeadmin.autopilot.ui.investigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.context.FindingDisposition
import com.lifeadmin.autopilot.ai.investigation.Answer
import com.lifeadmin.autopilot.ai.investigation.AnswerSegment
import com.lifeadmin.autopilot.ai.investigation.Authorization
import com.lifeadmin.autopilot.ai.investigation.Claim
import com.lifeadmin.autopilot.ai.investigation.ClaimAssessor
import com.lifeadmin.autopilot.ai.investigation.ClaimStatus
import com.lifeadmin.autopilot.ai.investigation.EffortSupport
import com.lifeadmin.autopilot.ai.investigation.EvidenceDisplay
import com.lifeadmin.autopilot.ai.investigation.EvidenceRef
import com.lifeadmin.autopilot.ai.investigation.ResearchDepth
import com.lifeadmin.autopilot.ai.investigation.ResearchPresets
import com.lifeadmin.autopilot.ai.investigation.ResearchSummary
import com.lifeadmin.autopilot.ai.investigation.SectionKind
import com.lifeadmin.autopilot.ai.investigation.Turn
import com.lifeadmin.autopilot.ai.investigation.TurnKind
import com.lifeadmin.autopilot.ai.investigation.TurnOutcome
import com.lifeadmin.autopilot.ai.investigation.TurnEventKind
import com.lifeadmin.autopilot.ai.investigation.TurnResult
import com.lifeadmin.autopilot.ai.investigation.TurnRunState
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.SourceRef2
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.ButtonPair
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.DraftPill
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.GroupRow
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SectionTitle
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.SwitchRow
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.research.BriefSectionTitle
import com.lifeadmin.autopilot.ui.research.CitationChip
import com.lifeadmin.autopilot.ui.research.ExplainerSheet
import com.lifeadmin.autopilot.ui.research.ResearchBanner
import com.lifeadmin.autopilot.ui.research.ResearchStamp
import com.lifeadmin.autopilot.ui.research.SourceSheet
import com.lifeadmin.autopilot.ui.research2.AdjustPanel
import com.lifeadmin.autopilot.ui.research2.StopSheet
import com.lifeadmin.autopilot.ui.research2.legacy
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.TimeTextStyle
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The Investigation Thread (M4, handoff §6–§8): one objective-led investigation, turn by turn. The
 * answer leads each turn; caveats sit in it; evidence is one tap away; follow-ups, resume and
 * association live here. Opening this screen sends nothing.
 */
@Composable
fun InvestigationThreadScreen(
    viewModel: InvestigationThreadViewModel,
    zone: ZoneId,
    now: () -> Instant,
    onBack: () -> Unit,
    onReview: (taskId: String, setId: String) -> Unit,
    onOpenTask: (taskId: String) -> Unit,
    onOpenLegacy: (investigationId: String) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var payloadFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmBudget by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val running = s.running
    val finished = s.finished
    LaunchedEffect(finished?.turnId) { if (finished != null) viewModel.dismissFinished() }
    val inv = s.investigation
    val pending = s.pending

    Scaffold(
        containerColor = c.paper, contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(AutopilotIcons.Back, contentDescription = "Back", onClick = onBack)
                    Text(inv?.title ?: "Research online", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), color = c.ink, maxLines = 2, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }.testTag("thread_title"))
                    if (running != null) DraftPill("Researching") else if (inv == null || pending != null) DraftPill("Nothing sent yet", Modifier.testTag("thread_pill"))
                }
                // Association is explicit and visible (RES-130 · N).
                FlowRow(Modifier.padding(start = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.taskId != null && s.taskTitle != null) {
                        LinkButton("For: ${s.taskTitle}", onClick = { onOpenTask(s.taskId!!) }, modifier = Modifier.testTag("thread_task"))
                        if (inv != null && running == null) LinkButton("Unlink", onClick = viewModel::unlink, modifier = Modifier.testTag("thread_unlink"))
                    } else {
                        Text(if (inv != null) "Not linked to a task" else "Not linked to a task yet", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 12.dp).testTag("thread_standalone"))
                        if (inv != null && running == null) LinkButton("Link to a task…", onClick = viewModel::openLink, modifier = Modifier.testTag("thread_link"))
                    }
                    if (inv != null && running == null) LinkButton("Delete", onClick = { confirmDelete = true }, modifier = Modifier.testTag("thread_delete"))
                }
            }
        },
        bottomBar = {
            Surface(color = c.surface, contentColor = c.ink) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                    when {
                        running != null -> {
                            HorizontalDivider(color = c.line)
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                SecondaryButton("Stop…", onClick = viewModel::openStop, icon = AutopilotIcons.Stop, modifier = Modifier.fillMaxWidth().testTag("research_stop"))
                                Text("Searches that already ran are billed. Leaving this screen keeps it running; closing the app interrupts it and nothing is re-sent.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                        pending?.state == TurnState.AUTHORIZING && s.authorization != null -> {
                            HorizontalDivider(color = c.line)
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                val a = s.authorization!!
                                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                    Text(if (a.estimateLowMicros != null && a.estimateHighMicros != null) UsdEstimate.formatRange(a.estimateLowMicros / 1e6, a.estimateHighMicros / 1e6) else "No price table for this model", style = TimeTextStyle.copy(fontSize = 18.sp), color = c.ink, modifier = Modifier.testTag("auth_estimate"))
                                    Text("estimate", style = MaterialTheme.typography.labelSmall, color = c.ink2)
                                }
                                ButtonPair(
                                    secondary = { m -> SecondaryButton("Not now", onClick = viewModel::decline, modifier = m.testTag("auth_decline")) },
                                    primary = { m -> PrimaryButton("Start research", onClick = { if (s.overMonthlyBudget) confirmBudget = true else viewModel.start() }, enabled = s.canStart, icon = AutopilotIcons.Research, modifier = m.testTag("auth_start")) },
                                )
                                // At large text the footer stays short so the card above keeps room to scroll (RES-021 wording kept).
                                val large = LocalDensity.current.fontScale >= 1.3f
                                Text(if (large) "Billed to your API key. Starts only when you tap." else "Billed by Anthropic to your API key. Starts only when you tap; no retries, nothing in the background.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("thread_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    s.error?.let { e ->
                        val setup = e == ResearchError.Disabled || e == ResearchError.NoApiKey || e == ResearchError.NoConsent || e == ResearchError.Unauthorized
                        ResearchBanner(e.headline, e.message, warn = true, icon = AutopilotIcons.Warn, tag = "thread_error", action = if (setup) ({ LinkButton("Open AI settings", onClick = onOpenAiSettings, modifier = Modifier.padding(top = 4.dp)) }) else null)
                    }
                    s.message?.let { m -> ResearchBanner(m, null, tag = "thread_message", action = { LinkButton("OK", onClick = viewModel::messageShown) }) }
                    if (s.online == false) ResearchBanner("You're offline", "Everything saved here is readable. Starting research needs a connection.", icon = AutopilotIcons.WifiOff, tag = "thread_offline")
                    if (s.busyElsewhere) ResearchBanner("Another research is running", "One research runs at a time. This one can start when it finishes.", tag = "thread_busy")
                }
            }
            if (inv == null) item { NewQuestion(s, viewModel) }
            items(s.completedTurns, key = { "turn_" + it.id }) { t ->
                TurnCard(
                    t, s, zone, isLatest = t.index == inv?.turns?.maxOfOrNull { it.index }, viewModel = viewModel,
                    onReview = { viewModel.openReview(t.id, onReview) }, onPayload = { payloadFor = t.id }, onOpenLegacy = { inv?.id?.let(onOpenLegacy) },
                )
            }
            if (running != null) item { RunningCard(running, now) }
            if (pending != null && running == null) item { PendingTurn(pending, s, viewModel, onPayload = { payloadFor = "pending" }) }
            if (inv != null && pending == null && running == null) item { FollowUpComposer(s, viewModel) }
        }
    }

    if (s.stopSheet && running != null) {
        StopSheet(
            searchesRun = running.searchesRun, spentFloor = UsdEstimate(running.spentMicros + running.searchesRun * 10_000L),
            canKeep = running.checkpointed, onKeep = { viewModel.stop(keep = true) }, onDiscard = { viewModel.stop(keep = false) }, onDismiss = viewModel::closeStop,
        )
    }
    payloadFor?.let { id ->
        val a = if (id == "pending") s.authorization else inv?.turns?.firstOrNull { it.id == id }?.authorization
        if (a != null) PayloadSheet(a, historical = id != "pending", onDismiss = { payloadFor = null }) else payloadFor = null
    }
    s.evidenceClaim?.let { (turnId, claimId) ->
        val t = inv?.turns?.firstOrNull { it.id == turnId }
        val claim = t?.result?.claim(claimId)
        if (t != null && claim != null) EvidenceSheet(claim, t.result!!, onSource = { n -> viewModel.closeEvidence(); viewModel.openSource(turnId, n) }, onDismiss = viewModel::closeEvidence)
    }
    s.sourceSheet?.let { (turnId, n) ->
        val t = inv?.turns?.firstOrNull { it.id == turnId }
        val src = t?.result?.sources?.firstOrNull { it.n == n }
        if (t != null && src != null) {
            val usedFor = t.result!!.claims.filter { cl -> cl.support.any { it.sourceN == n } || cl.against.any { it.sourceN == n } }.map { it.text }
            SourceSheet(src.legacy(), usedFor, researchedLine = "Read ${Format.relative(Instant.ofEpochMilli(t.result.finishedAtMs), s.now, zone)}", onDismiss = viewModel::closeSource, online = s.online)
        }
    }
    if (s.linkSheet) LinkSheet(s, viewModel)
    if (s.showExplainer) ExplainerSheet(onAccept = viewModel::acceptExplainer, onDismiss = viewModel::dismissExplainer)
    if (confirmBudget) {
        AlertDialog(
            onDismissRequest = { confirmBudget = false },
            title = { Text("Past your monthly budget") },
            text = { Text("This month's AI spend is already at or above the budget you set. Start anyway?") },
            confirmButton = { TextButton(onClick = { confirmBudget = false; viewModel.start() }, modifier = Modifier.testTag("budget_confirm")) { Text("Start anyway") } },
            dismissButton = { TextButton(onClick = { confirmBudget = false }) { Text("Not now") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this research?") },
            text = { Text("Removes every turn of it from this phone. Steps and notes already added to tasks stay, and the usage record of what it cost is kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }, modifier = Modifier.testTag("thread_delete_confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

// ------------------------------------------------------------------ the question

@Composable
private fun NewQuestion(s: ThreadUi, vm: InvestigationThreadViewModel) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What should I find out?", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = s.question, onValueChange = vm::setQuestion, modifier = Modifier.fillMaxWidth().testTag("thread_question"),
            placeholder = { Text("e.g. What documents do I need to renew my passport?", color = c.ink3) }, minLines = 2, maxLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            supportingText = { val over = s.question.length > Research2Config.MAX_REQUEST_CHARS; Text(if (over) "${s.question.length} / ${Research2Config.MAX_REQUEST_CHARS} — too long" else "${s.question.length} / ${Research2Config.MAX_REQUEST_CHARS}", color = if (over) c.danger else c.ink3) },
        )
        if (s.aiReady) ProviderPrepRow(s.useProvider, vm::setUseProvider)
        PrimaryButton("Continue", onClick = { vm.prepare() }, enabled = s.question.isNotBlank() && !s.preparing && s.question.length <= Research2Config.MAX_REQUEST_CHARS, modifier = Modifier.fillMaxWidth().testTag("thread_prepare"))
        Text(
            if (s.useProvider && s.aiReady) "Next you'll see the objective and exactly what would be sent. Only your question goes to Claude now, in one small call recorded in AI usage. No web search until you start."
            else "Next you'll see the objective, the depth and exactly what would be sent. Nothing leaves the phone until you start.",
            style = MaterialTheme.typography.bodySmall, color = c.ink2,
        )
    }
}

@Composable
private fun ProviderPrepRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = AutopilotTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) { onChange(!checked) }.heightIn(min = 48.dp).testTag("thread_use_provider"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text("Let Claude read the question first (small call, no search)", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun FollowUpComposer(s: ThreadUi, vm: InvestigationThreadViewModel) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BriefSectionTitle("Ask a follow-up")
        OutlinedTextField(
            value = s.question, onValueChange = vm::setQuestion, modifier = Modifier.fillMaxWidth().testTag("thread_followup"),
            placeholder = { Text("e.g. What about under \$100?", color = c.ink3) }, minLines = 1, maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), shape = RoundedCornerShape(16.dp), colors = fieldColors(),
        )
        if (s.aiReady) ProviderPrepRow(s.useProvider, vm::setUseProvider)
        SecondaryButton("Continue", onClick = { vm.prepare() }, enabled = s.question.isNotBlank() && !s.preparing, modifier = Modifier.fillMaxWidth().testTag("thread_followup_prepare"))
        Text("A follow-up continues this investigation: earlier findings it needs are reused as found, quick-changing ones are checked fresh, and you see exactly what's sent before anything starts.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
    }
}

// ------------------------------------------------------------------ objective + authorization

@Composable
private fun PendingTurn(t: Turn, s: ThreadUi, vm: InvestigationThreadViewModel, onPayload: () -> Unit) {
    val c = AutopilotTheme.colors
    val o = t.objective
    var editing by rememberSaveable(t.id) { mutableStateOf(false) }
    var draft by rememberSaveable(t.id) { mutableStateOf(o.text) }
    Column(Modifier.padding(top = 16.dp).testTag("thread_pending"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (t.kind != TurnKind.INITIAL) ResearchStamp(if (t.kind == TurnKind.RESUME) "Resuming · new authorization" else "Follow-up · turn ${t.index + 1}")
        SurfaceCard(radius = 20.dp, modifier = Modifier.testTag("objective_card")) {
            Text("OBJECTIVE", style = MaterialTheme.typography.labelSmall, color = c.ink2)
            if (t.state == TurnState.CLARIFYING) {
                Text("“${o.question}”", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 6.dp))
                Clarify(o.clarification?.question ?: "Say a little more about what you want to find out.", o.clarification?.options.orEmpty(), vm::clarify)
                return@SurfaceCard
            }
            if (editing) {
                OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("objective_edit_field"), minLines = 2, shape = RoundedCornerShape(12.dp), colors = fieldColors())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SecondaryButton("Save", onClick = { vm.editObjective(draft); editing = false }, minHeight = 44.dp, modifier = Modifier.testTag("objective_save"))
                    LinkButton("Cancel", onClick = { draft = o.text; editing = false })
                }
            } else {
                Text(o.text, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 6.dp).testTag("objective_text"))
                Text("You asked: “${o.question}” · you want ${o.askKind.label}", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                LinkButton("Edit objective", onClick = { draft = o.text; editing = true }, modifier = Modifier.testTag("objective_edit"))
            }
            if (o.constraints.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                o.constraints.forEach { k -> Text(k.text, style = MaterialTheme.typography.labelMedium, color = c.ink, modifier = Modifier.clip(CircleShape).background(c.sunken).padding(horizontal = 10.dp, vertical = 4.dp)) }
            }
            if (o.consequential) Text("A consequential question: the answer describes the evidence, not what is right for you personally.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
            val gaps = o.disclosedGaps
            if (gaps.isNotEmpty() || t.suppliedDetails.isNotEmpty()) DetailsField(gaps.map { it.text }, t.suppliedDetails, vm::supplyDetails)
        }
        if (t.state != TurnState.AUTHORIZING) return@Column
        AuthorizationCard(t, s, vm, onPayload)
    }
}

@Composable
private fun Clarify(question: String, options: List<String>, onAnswer: (String) -> Unit) {
    val c = AutopilotTheme.colors
    var answer by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(top = 10.dp).testTag("clarify"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("clarify_question"))
        Text("Nothing has been searched. Answer this so the research looks for the right thing.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        if (options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, opt -> ChoiceChip(opt, selected = false, onClick = { onAnswer(opt) }, modifier = Modifier.testTag("clarify_option_$i")) }
        }
        OutlinedTextField(answer, { answer = it }, modifier = Modifier.fillMaxWidth().testTag("clarify_answer"), placeholder = { Text("Or type your answer", color = c.ink3) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = fieldColors())
        SecondaryButton("Use this answer", onClick = { onAnswer(answer) }, enabled = answer.isNotBlank(), minHeight = 44.dp, modifier = Modifier.testTag("clarify_submit"))
    }
}

@Composable
private fun DetailsField(gaps: List<String>, supplied: List<String>, onSave: (String) -> Unit) {
    val c = AutopilotTheme.colors
    var text by rememberSaveable { mutableStateOf(supplied.joinToString("\n")) }
    Column(Modifier.padding(top = 10.dp).testTag("auth_details"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (gaps.isNotEmpty()) Text("Not known: " + gaps.joinToString("; ") + ". Add it if you can; otherwise the answer says what depends on it.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().testTag("auth_details_field"), placeholder = { Text("Details only you know (sent with this request)", color = c.ink3) }, minLines = 1, maxLines = 4, shape = RoundedCornerShape(12.dp), colors = fieldColors())
        SecondaryButton("Add to the request", onClick = { onSave(text) }, minHeight = 44.dp, modifier = Modifier.testTag("auth_details_save"))
    }
}

@Composable
private fun AuthorizationCard(t: Turn, s: ThreadUi, vm: InvestigationThreadViewModel, onPayload: () -> Unit) {
    val c = AutopilotTheme.colors
    val cfg = s.config ?: return
    val a = s.authorization
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("auth_card")) {
        SectionTitle("How deep", Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("auth_depths")) {
            ResearchPresets.PRESETS.forEach { p -> ChoiceChip(p.depth.label, selected = cfg.depth == p.depth, onClick = { vm.setDepth(p.depth) }, modifier = Modifier.testTag("depth_${p.depth.name.lowercase()}"), count = if (t.objective.recommendedDepth == p.depth) "suggested" else null) }
        }
        val preset = ResearchPresets.PRESETS.firstOrNull { it.depth == cfg.depth }
        Text((preset?.blurb ?: "Your own settings.") + (t.objective.depthReason.takeIf { it.isNotBlank() && t.objective.recommendedDepth == cfg.depth }?.let { " $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.ink2)
        ResearchStamp(configLine(cfg.presetName, cfg.model, cfg.searchBudget, cfg.spendLimitCents, cfg.effort), Modifier.testTag("auth_config"))
        LinkButton(if (s.choices.adjustOpen) "Hide adjustments" else "Adjust model, searches, limit and effort", onClick = vm::toggleAdjust, modifier = Modifier.testTag("auth_adjust"))
        if (s.choices.adjustOpen) SurfaceCard(radius = 18.dp, modifier = Modifier.testTag("adjust_panel")) {
            AdjustPanel(model = cfg.model, onModel = vm::setModel, searches = cfg.searchBudget, onSearches = vm::setSearches, limitCents = cfg.spendLimitCents, onLimit = vm::setLimit)
            Text("Reasoning effort", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(top = 14.dp))
            val reason = EffortSupport.reason(cfg.model)
            if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp).testTag("effort_unsupported"))
            else FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Model default", selected = cfg.effort == null, onClick = { vm.setEffort(null) }, modifier = Modifier.testTag("effort_default"))
                EffortSupport.LEVELS.forEach { e -> ChoiceChip(EffortSupport.label(e), selected = cfg.effort == e, onClick = { vm.setEffort(e) }, modifier = Modifier.testTag("effort_$e")) }
            }
        }
        a?.warnings?.forEach { w -> ResearchBanner(w, null, warn = true, icon = AutopilotIcons.Warn, tag = "auth_warning") }

        SectionTitle("What Claude will see", Modifier.padding(top = 8.dp))
        SurfaceCard(radius = 20.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("auth_context")) {
            SwitchRow("Your objective and constraints", checked = true, onCheckedChange = {}, subtitle = "Always sent, as shown above", enabled = false)
            s.context.forEach { item ->
                SwitchRow(item.label, checked = item.included, onCheckedChange = { on ->
                    val sc = s.choices.scope
                    vm.setScope(when (item.id) { "title" -> sc.copy(shareTitle = on); "notes" -> sc.copy(shareNotes = on); "steps" -> sc.copy(shareSteps = on); else -> sc.copy(shareLocale = on) })
                }, subtitle = item.value.take(90), divider = true, switchTestTag = "ctx_${item.id}")
            }
            Column(Modifier.fillMaxWidth().background(c.sunken).padding(16.dp)) {
                Text("Never sent", style = MaterialTheme.typography.labelLarge, color = c.ink)
                Text("Your other tasks, other research, drafts, Inbox, history, location, contacts or your API key.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (s.inheritance.isNotEmpty()) {
            SectionTitle("Earlier findings", Modifier.padding(top = 8.dp))
            SurfaceCard(radius = 20.dp, contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.testTag("auth_inherited")) {
                s.inheritance.forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(color = c.line)
                    val canToggle = f.disposition == FindingDisposition.SENT || f.disposition == FindingDisposition.EXCLUDED
                    Row(
                        Modifier.fillMaxWidth().let { if (canToggle) it.clickable(role = Role.Checkbox) { vm.toggleExclude(f.id) } else it }.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("inherit_${f.id}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (canToggle) Checkbox(checked = f.disposition == FindingDisposition.SENT, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(f.text, style = MaterialTheme.typography.bodyMedium, color = if (f.disposition == FindingDisposition.EXCLUDED) c.ink3 else c.ink, maxLines = 3)
                            Text(dispositionLabel(f.disposition) + " · " + f.reason, style = MaterialTheme.typography.bodySmall, color = c.ink2)
                        }
                    }
                }
            }
            Text("Unticked findings are not sent now or in later turns of this research.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
        if (s.authorization?.wordingCheck == true) {
            Text("Includes one more call, without searching, that checks the answer's wording against the pages it cites. It is in the estimate.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("auth_wording_check"))
        }
        LinkButton("See exactly what's sent", onClick = onPayload, modifier = Modifier.testTag("auth_payload"))
        Text("Research only reads the web. It never buys, books, signs up or messages anyone. Your task changes only when you apply proposals.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
    }
}

private fun dispositionLabel(d: FindingDisposition): String = when (d) {
    FindingDisposition.SENT -> "Reused as found"
    FindingDisposition.EXCLUDED -> "Excluded by you"
    FindingDisposition.NOT_SENT -> "Not sent"
    FindingDisposition.REPLACED -> "Replaced by what you added"
    FindingDisposition.FRESH -> "Checked fresh"
}

private fun configLine(preset: String, model: String, searches: Int, limitCents: Int?, effort: String?): String =
    "$preset · ${AiConfig.label(model).removePrefix("Claude ")} · up to $searches ${if (searches == 1) "search" else "searches"} · " +
        (limitCents?.let { "stop near ${UsdEstimate.format(it / 100.0)}" } ?: "no limit") + (effort?.let { " · effort ${EffortSupport.label(it).lowercase()}" } ?: "")

// ------------------------------------------------------------------ running

@Composable
private fun RunningCard(run: TurnRunState.Running, now: () -> Instant) {
    val c = AutopilotTheme.colors
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(run.turnId) { while (true) { elapsedMs = Duration.between(run.startedAt, now()).toMillis().coerceAtLeast(0); delay(1_000) } }
    Column(Modifier.padding(top = 16.dp).testTag("research_progress"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (run.continuation) "Continuing…" else "Researching…", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() })
        ResearchStamp(configLine(run.config.presetName, run.config.model, run.config.searchBudget, run.config.spendLimitCents, run.config.effort))
        SurfaceCard(radius = 20.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Counter("Searches", "${run.searchesRun}/${run.config.searchBudget}")
                Counter("Sources", if (run.sourcesSeen > 0) "${run.sourcesSeen}" else "—")
                Counter("Supported", "${run.establishedClaims}/${run.claimsHeld}")
                Counter("Time", "%d:%02d".format(elapsedMs / 60_000, (elapsedMs / 1_000) % 60))
            }
            run.callStartedAtEpochMs?.let { started ->
                val callElapsed = (elapsedMs - (started - run.startedAt.toEpochMilli())).coerceAtLeast(0)
                val limit = run.callTimeoutSeconds ?: 0
                Text("Waiting for Anthropic's reply to call ${run.callsSent} · ${"%d:%02d".format(callElapsed / 60_000, (callElapsed / 1_000) % 60)} of up to ${"%d:%02d".format(limit / 60, limit % 60)}. Counts arrive when a call returns.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 10.dp).testTag("progress_waiting"))
            }
            Text("So far ${if (run.spentIsFloor) "≥ " else "~"}${UsdEstimate.format((run.spentMicros) / 1e6)} estimated for finished calls." + if (run.checkpointed) " Findings from finished calls are already saved." else "", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp).testTag("progress_spent"))
        }
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = run.events.lastOrNull()?.text ?: "" }.testTag("progress_stages"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            run.config.stages.forEachIndexed { i, st ->
                val done = i < run.stageIndex || run.events.any { it.kind == TurnEventKind.STAGE_DONE && it.text.startsWith(st.label) }
                val skipped = run.events.any { (it.kind == TurnEventKind.STAGE_SKIPPED || it.kind == TurnEventKind.BUDGET) && it.text.startsWith(st.label) }
                val active = st == run.stage && !done && !skipped
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (done || active) c.ink else c.line))
                    Text(st.label, style = MaterialTheme.typography.bodyLarge, color = if (done || active) c.ink else c.ink3, modifier = Modifier.weight(1f))
                    when { skipped -> TimeText("skipped", color = c.ink3, size = 12); active -> TimeText("working", color = c.ink2, size = 12); done -> Icon(AutopilotIcons.Check, contentDescription = "done", tint = c.ink2, modifier = Modifier.size(16.dp)) }
                }
            }
        }
        SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(14.dp), modifier = Modifier.testTag("progress_events")) {
            Text("WHAT HAS HAPPENED", style = MaterialTheme.typography.labelSmall, color = c.ink2)
            if (run.events.isEmpty()) Text("Preparing the first request…", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
            run.events.takeLast(8).forEach { e -> Text("• ${e.text}", style = MaterialTheme.typography.bodyMedium, color = if (e.kind == TurnEventKind.FAILED || e.kind == TurnEventKind.BUDGET) c.onSignalSoft else c.ink, modifier = Modifier.padding(top = 6.dp)) }
        }
        Text("Stage names describe the app's own plan; counts come from Anthropic's responses. Web pages are read as information only; instructions on pages are never followed.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
    }
}

@Composable
private fun Counter(label: String, value: String) {
    val c = AutopilotTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TimeText(value, size = 18)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = c.ink2)
    }
}

// ------------------------------------------------------------------ one turn's result

@Composable
private fun TurnCard(t: Turn, s: ThreadUi, zone: ZoneId, isLatest: Boolean, viewModel: InvestigationThreadViewModel, onReview: () -> Unit, onPayload: () -> Unit, onOpenLegacy: () -> Unit) {
    val c = AutopilotTheme.colors
    var showEvidence by rememberSaveable(t.id) { mutableStateOf(false) }
    var showLog by rememberSaveable(t.id) { mutableStateOf(false) }
    Column(Modifier.padding(top = 18.dp).testTag("turn_${t.index}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (t.index > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(bottom = 8.dp))
        ResearchStamp(
            (when (t.kind) { TurnKind.INITIAL -> "Asked"; TurnKind.CONTINUATION -> "Follow-up"; TurnKind.RESUME -> "Resumed" }) + " · " + Format.relative(Instant.ofEpochMilli(t.createdAtMs), s.now, zone),
        )
        Text("“${t.objective.question}”", style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.testTag("turn_question_${t.index}"))
        t.legacy?.let {
            ResearchBanner("Saved with the earlier research format", "Kept exactly as it was. Open it to read the result and its sources." + (it.outcome.takeIf { o -> o.isNotBlank() }?.let { "" } ?: ""), icon = AutopilotIcons.Info, tag = "turn_legacy_${t.index}", action = if (it.sessionContentJson.isNotBlank() && s.taskId != null) ({ LinkButton("Open the saved result", onClick = onOpenLegacy, modifier = Modifier.padding(top = 4.dp).testTag("turn_legacy_open_${t.index}")) }) else null)
            return@Column
        }
        if (t.state == TurnState.DECLINED) { Text("Not started. Nothing was sent.", style = MaterialTheme.typography.bodyMedium, color = c.ink3, modifier = Modifier.testTag("turn_declined_${t.index}")); return@Column }
        val r = t.result
        endingBanner(t, r)?.let { (title, body) -> ResearchBanner(title, body, warn = t.state != TurnState.DONE, icon = if (t.state == TurnState.STOPPED) AutopilotIcons.Stop else AutopilotIcons.Warn, tag = "turn_ending_${t.index}") }
        if (r != null && r.claims.isNotEmpty() || r?.answer?.lead?.isNotEmpty() == true) {
            r!!
            AnswerBlock(t, r.answer, r, onClaim = { viewModel.openEvidence(t.id, it) })
            if (r.injectionNoticed) ResearchBanner("A web page tried to give instructions", "Page text is information only. It was dropped and nothing was done.", icon = AutopilotIcons.Shield, tag = "turn_injection_${t.index}")
            // Proposals: only when justified; "nothing to change" is a valid result (RES-113).
            val pendingCount = s.pendingProposals[t.id] ?: 0
            if (s.taskId != null) {
                when {
                    r.proposals.isNotEmpty() && pendingCount > 0 -> PrimaryButton("Review $pendingCount ${if (pendingCount == 1) "change" else "changes"} to the task", onClick = onReview, enabled = s.running == null, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("turn_review_${t.index}"))
                    r.proposals.isNotEmpty() -> LinkButton("See the changes from this turn", onClick = onReview, modifier = Modifier.testTag("turn_review_${t.index}"))
                    else -> Text("No change to your task is suggested from this.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("turn_no_proposal_${t.index}"))
                }
                if (r.heldBackProposals > 0) Text("${r.heldBackProposals} possible ${if (r.heldBackProposals == 1) "change was" else "changes were"} held back: what ${if (r.heldBackProposals == 1) "it rests" else "they rest"} on wasn't established.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("turn_held_back_${t.index}"))
            }
            LinkButton(if (showEvidence) "Hide the evidence" else "Evidence · ${r.claims.count { !it.superseded }} findings, ${r.sources.count { it.citedCount > 0 }} sources", onClick = { showEvidence = !showEvidence }, modifier = Modifier.testTag("turn_evidence_toggle_${t.index}"))
            if (showEvidence) EvidenceList(t, r, onClaim = { viewModel.openEvidence(t.id, it) }, onSource = { viewModel.openSource(t.id, it) })
        }
        // Recovery surface for a turn that ended early (RES-150–154): what is kept, what is open, what is unknown.
        if ((t.state == TurnState.INTERRUPTED || t.state == TurnState.STOPPED || t.state == TurnState.FAILED) && isLatest && s.pending == null && s.running == null) {
            SurfaceCard(radius = 18.dp, modifier = Modifier.testTag("turn_recovery_${t.index}")) {
                val kept = r?.claims?.count { it.checked && !it.superseded } ?: 0
                val open = r?.claims?.count { !it.checked } ?: 0
                Text("Kept: $kept ${if (kept == 1) "finding" else "findings"}" + if (open > 0) " · $open not checked yet" else "", style = MaterialTheme.typography.titleSmall, color = c.ink)
                if (t.usage.inFlightUnknown) Text("A call was in flight when it stopped; what it cost is unknown and is shown as such in AI usage.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                Text("Nothing restarts on its own. Continuing is a new turn with its own approval; findings kept here are reused.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                SecondaryButton("Continue from here…", onClick = { viewModel.prepare(resumeOf = t.id) }, minHeight = 44.dp, modifier = Modifier.padding(top = 8.dp).testTag("turn_resume_${t.index}"))
            }
        }
        LinkButton(if (showLog) "Hide how this was researched" else "How this was researched", onClick = { showLog = !showLog }, modifier = Modifier.testTag("turn_log_toggle_${t.index}"))
        if (showLog) RunLog(t, onPayload)
    }
}

private fun endingBanner(t: Turn, r: TurnResult?): Pair<String, String>? = when (t.state) {
    TurnState.STOPPED -> "Stopped" to (t.endingNote ?: "Stopped by you.") + if (r == null) " Nothing from it was kept; its usage is recorded." else ""
    TurnState.FAILED -> "Didn't finish" to (t.endingNote ?: "The research failed.") + (r?.failure?.let { " · $it" } ?: "")
    TurnState.INTERRUPTED -> "Interrupted" to (t.endingNote ?: "The app closed while this was running.")
    else -> null
}

@Composable
private fun AnswerBlock(t: Turn, a: Answer, r: TurnResult, onClaim: (String) -> Unit) {
    val c = AutopilotTheme.colors
    Text(a.title, style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() }.testTag("turn_title_${t.index}"))
    Text(a.stateLine, style = MaterialTheme.typography.labelLarge, color = when (r.state) { com.lifeadmin.autopilot.ai.investigation.ResultState.ANSWERED -> c.onDoneSoft; else -> c.onSignalSoft }, modifier = Modifier.testTag("turn_state_${t.index}"))
    SurfaceCard(radius = 18.dp, modifier = Modifier.testTag("turn_answer_${t.index}")) {
        a.lead.forEach { seg -> Segment(seg, r, onClaim) }
        if (a.caveats.isNotEmpty()) Column(Modifier.padding(top = 10.dp).testTag("turn_caveats_${t.index}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            a.caveats.forEach { cv -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(AutopilotIcons.Warn, contentDescription = null, tint = c.onSignalSoft, modifier = Modifier.size(16.dp).padding(top = 2.dp)); Text(cv, style = MaterialTheme.typography.bodyMedium, color = c.ink) } }
        }
    }
    a.sections.forEach { sec ->
        Column(Modifier.testTag("turn_section_${sec.kind.name.lowercase()}_${t.index}")) {
            BriefSectionTitle(sec.kind.title)
            if (sec.kind == SectionKind.COMPARISON) CompareRows(sec, r, onClaim)
            else sec.items.forEachIndexed { i, it ->
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (sec.kind == SectionKind.STEPS) "${i + 1}." else "•", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                    Column(Modifier.weight(1f)) { Segment(it, r, onClaim) }
                }
            }
        }
    }
    a.recommendation?.let { rec ->
        SurfaceCard(radius = 18.dp, background = c.sunken, modifier = Modifier.padding(top = 8.dp).testTag("turn_recommendation_${t.index}")) {
            Text(if (rec.optional) "IF YOU WANT A PICK · JUDGMENT" else "RECOMMENDATION · JUDGMENT", style = MaterialTheme.typography.labelSmall, color = c.ink2)
            Text(rec.text, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 4.dp))
            rec.reasons.forEach { Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text("Because", style = MaterialTheme.typography.labelMedium, color = c.ink2); Column(Modifier.weight(1f)) { Segment(it, r, onClaim) } } }
            rec.assumptions.forEach { Text("Assumes: $it", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp)) }
            rec.flipConditions.forEach { Text("Changes if: $it", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** Text then one chip per source behind each citable claim; a chip opens the claim's evidence. */
@Composable
private fun Segment(seg: AnswerSegment, r: TurnResult, onClaim: (String) -> Unit) {
    val c = AutopilotTheme.colors
    val chips = seg.claimIds.mapNotNull { r.claim(it) }.flatMap { cl -> cl.support.filter { !it.pointerOnly }.map { it.sourceN }.distinct().map { n -> cl.id to n } }.distinctBy { it.second }
    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(seg.text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
        chips.forEach { (id, n) -> CitationChip(n, onClick = { onClaim(id) }, title = r.sources.firstOrNull { it.n == n }?.title) }
    }
}

@Composable
private fun CompareRows(sec: com.lifeadmin.autopilot.ai.investigation.AnswerSection, r: TurnResult, onClaim: (String) -> Unit) {
    val c = AutopilotTheme.colors
    // Stacked rows rather than a wide table: readable at 320 dp and 200 % text.
    SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp)) {
        sec.rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = c.line)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.criterion, style = MaterialTheme.typography.titleSmall, color = c.ink)
                (sec.options.ifEmpty { row.cells.keys.toList() }).forEach { opt ->
                    val cell = row.cells[opt]
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(opt, style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.padding(top = 2.dp))
                        Column(Modifier.weight(1f)) { if (cell != null) Segment(cell, r, onClaim) else Text("—", color = c.ink3) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceList(t: Turn, r: TurnResult, onClaim: (String) -> Unit, onSource: (Int) -> Unit) {
    val c = AutopilotTheme.colors
    val ordered = r.claims.sortedWith(compareBy<Claim>({ it.superseded }, { !it.loadBearing }))
    SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("turn_evidence_${t.index}")) {
        ordered.forEachIndexed { i, cl ->
            if (i > 0) HorizontalDivider(color = c.line)
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onClaim(cl.id) }.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp).testTag("claim_${cl.id}"), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                StatusBadge(cl.status, cl.superseded)
                Column(Modifier.weight(1f)) {
                    Text(cl.text, style = MaterialTheme.typography.bodyMedium, color = if (cl.superseded) c.ink3 else c.ink)
                    Text(listOfNotNull(cl.type.label.replaceFirstChar { it.uppercase() }, cl.scope.takeIf { it.isNotBlank() }, cl.asOf?.let { "as of $it" }, if (cl.reused) "reused, not re-checked" else null, if (!cl.checked) "not checked" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.ink2)
                }
            }
        }
    }
    val cited = r.sources.filter { it.citedCount > 0 }
    if (cited.isNotEmpty()) {
        BriefSectionTitle("Sources", count = cited.size)
        SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("turn_sources_${t.index}")) {
            cited.forEachIndexed { i, src -> com.lifeadmin.autopilot.ui.research.SourceRow(src.legacy(), onClick = { onSource(src.n) }, divider = i > 0) }
        }
    }
}

@Composable
private fun StatusBadge(s: ClaimStatus, superseded: Boolean) {
    val c = AutopilotTheme.colors
    val text = if (superseded) "Replaced" else ClaimAssessor.badge(s)
    val good = s == ClaimStatus.ESTABLISHED && !superseded
    Text(text, style = MaterialTheme.typography.labelSmall, color = if (good) c.onDoneSoft else if (superseded) c.ink3 else c.onSignalSoft, modifier = Modifier.padding(top = 2.dp).clip(CircleShape).background(if (good) c.doneSoft else c.sunken).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun RunLog(t: Turn, onPayload: () -> Unit) {
    val c = AutopilotTheme.colors
    val a = t.authorization
    SurfaceCard(radius = 18.dp, modifier = Modifier.testTag("turn_log_${t.index}")) {
        if (a != null) {
            Text("Requested: " + configLine(a.presetName, a.config.model, a.config.searchBudget, a.config.spendLimitCents, a.config.effort), style = MaterialTheme.typography.bodySmall, color = c.ink)
            Text("Used: " + (t.usage.modelsUsed.joinToString(", ") { AiConfig.label(it) }.ifBlank { "nothing yet" }) + (t.usage.effortUsed?.let { " · effort $it" } ?: "") + " · ${t.usage.webSearches} ${if (t.usage.webSearches == 1) "search" else "searches"} · ${t.usage.inputTokens} in / ${t.usage.outputTokens} out" + " · " + (if (t.usage.floor) "≥ " else "~") + UsdEstimate.format(t.usage.spentMicros / 1e6), style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.padding(top = 4.dp).testTag("turn_used_${t.index}"))
            Text("Approved " + java.time.Instant.ofEpochMilli(a.approvedAtMs).toString().take(16).replace('T', ' ') + " UTC · fingerprint ${a.payloadSha256.take(12)}", style = TimeTextStyle.copy(fontSize = 11.sp), color = c.ink3, modifier = Modifier.padding(top = 4.dp))
            LinkButton("What was sent", onClick = onPayload, modifier = Modifier.testTag("turn_payload_${t.index}"))
        }
        // The engine's own account of the budget and the stop, never a reply's remark about its allowance.
        t.result?.let { r -> if (a != null) Text(ResearchSummary.line(r, a.config.searchBudget), style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.padding(top = 4.dp).testTag("turn_stop_${t.index}")) }
        t.result?.stages?.forEach { st ->
            Text("${st.kind.label}: ${st.result.name.lowercase()}" + (if (st.searchesAllowed > 0) " · ${st.searchesRun} ${if (st.searchesRun == 1) "search" else "searches"} (this stage's share: ${st.searchesAllowed})" else "") + (st.note?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
            st.queries.forEach { q -> Text("   “$q”", style = MaterialTheme.typography.bodySmall, color = c.ink) }
            st.callLog.forEach { d -> Text("   " + d.line(), style = TimeTextStyle.copy(fontSize = 10.sp, lineHeight = 14.sp), color = c.ink3) }
        }
    }
}

// ------------------------------------------------------------------ sheets

@Composable
private fun PayloadSheet(a: Authorization, historical: Boolean, onDismiss: () -> Unit) {
    val c = AutopilotTheme.colors
    AutopilotSheet(title = if (historical) "What was sent" else "Exactly what would be sent", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("payload_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            a.payloadLines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = c.ink) }
            Text("The request below is these exact bytes, plus the app's fixed research instructions. Your API key only authenticates it.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
            SurfaceCard(radius = 12.dp, background = c.sunken, contentPadding = PaddingValues(12.dp)) {
                Text(a.payloadJson, style = TimeTextStyle.copy(fontSize = 11.sp, lineHeight = 15.sp), color = c.ink, modifier = Modifier.testTag("payload_json"))
            }
            ResearchStamp("SHA-256 ${a.payloadSha256.take(16)}…")
        }
    }
}

@Composable
private fun EvidenceSheet(claim: Claim, r: TurnResult, onSource: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = AutopilotTheme.colors
    fun title(n: Int): String = r.sources.firstOrNull { it.n == n }?.title ?: "Source $n"
    AutopilotSheet(title = null, onDismiss = onDismiss, titleLeading = { StatusBadge(claim.status, claim.superseded) }) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("evidence_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(claim.text, style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(claim.statusReason.ifBlank { ClaimAssessor.label(claim.status) }.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.testTag("evidence_reason"))
            Text(listOfNotNull(claim.type.label.replaceFirstChar { it.uppercase() }, claim.scope.takeIf { it.isNotBlank() }?.let { "About: $it" }, claim.asOf?.let { "As of $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.ink2)
            if (claim.superseded) Text("Replaced by a later finding; kept here as history.", style = MaterialTheme.typography.bodySmall, color = c.onSignalSoft)
            if (claim.reused) Text("Reused from an earlier turn as found; not re-checked in this one.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
            if (claim.type.derived) {
                Text("A ${claim.type.label}: it rests on other findings and is never cited itself.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                claim.premises.mapNotNull { r.claim(it) }.forEach { p -> Text("• ${p.text} — ${ClaimAssessor.label(p.status)}", style = MaterialTheme.typography.bodyMedium, color = c.ink) }
            }
            if (claim.support.isNotEmpty()) {
                Text("SUPPORTING", style = MaterialTheme.typography.labelSmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                claim.support.forEach { e -> EvidenceRow(e, title(e.sourceN), EvidenceDisplay.note(e), onSource) }
            }
            if (claim.against.isNotEmpty()) {
                Text("POINTING THE OTHER WAY", style = MaterialTheme.typography.labelSmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                claim.against.forEach { e -> EvidenceRow(e, title(e.sourceN), EvidenceDisplay.note(e), onSource) }
            }
            if (claim.support.isEmpty() && claim.against.isEmpty() && !claim.type.derived) Text(if (claim.dependsOnUser) "This depends on your situation or choice; no source can settle it." else "No source was found for this. That doesn't make it false.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        }
    }
}

@Composable
private fun EvidenceRow(e: EvidenceRef, source: String, note: String?, onSource: (Int) -> Unit) {
    val c = AutopilotTheme.colors
    val n = e.sourceN
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSource(n) }.heightIn(min = 48.dp).padding(vertical = 4.dp).testTag("evidence_source_$n"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CitationChip(n, onClick = { onSource(n) }, title = source)
        Column(Modifier.weight(1f)) {
            // Only the page's own cited words go in quotation marks; the reply's line is labelled as a summary.
            EvidenceDisplay.quoted(e)?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.testTag("evidence_quote_$n")) }
            EvidenceDisplay.summary(e)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (e.quote == null) c.ink else c.ink2, modifier = Modifier.testTag("evidence_summary_$n")) }
            Text(source, style = MaterialTheme.typography.bodySmall, color = c.ink2)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSignalSoft) }
        }
    }
}

@Composable
private fun LinkSheet(s: ThreadUi, vm: InvestigationThreadViewModel) {
    val c = AutopilotTheme.colors
    AutopilotSheet(title = "Link to a task", onDismiss = vm::closeLink) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("link_sheet")) {
            Text("Linking lets later turns propose changes to that task. Nothing about the task is sent until you start a turn and leave it switched on.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(bottom = 8.dp))
            if (s.tasks.isEmpty()) Text("No open tasks.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            s.tasks.take(40).forEachIndexed { i, t -> GroupRow(t.task.title, onClick = { vm.link(t.task.id, t.task.title) }, divider = i > 0, minHeight = 52.dp, modifier = Modifier.testTag("link_task_${t.task.id}")) }
        }
    }
}
