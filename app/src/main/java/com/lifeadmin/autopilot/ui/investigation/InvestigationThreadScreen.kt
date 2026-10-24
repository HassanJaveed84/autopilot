package com.lifeadmin.autopilot.ui.investigation

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.aifirst.AfAuthorization
import com.lifeadmin.autopilot.ai.aifirst.AfConfig
import com.lifeadmin.autopilot.ai.aifirst.AfRunState
import com.lifeadmin.autopilot.ai.aifirst.AfSource
import com.lifeadmin.autopilot.ai.aifirst.AfStatus
import com.lifeadmin.autopilot.ai.aifirst.AfTurn
import com.lifeadmin.autopilot.ai.aifirst.EffortSupport
import com.lifeadmin.autopilot.ai.aifirst.ResearchOnlineService
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.Sources
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.usage.PricingCatalog
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.data.repository.AfThreadRepository
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
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.TimeTextStyle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * Research Online: one thread of questions and answers. The answer is Claude's, shown as written with
 * the provider's citations; sources, searches, cost and exactly what was sent are one tap away. Opening
 * this screen sends nothing. Legacy research (the earlier engine) opens read-only.
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
    var confirmBudget by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val running = s.running
    LaunchedEffect(s.finished?.turnId) { if (s.finished != null) viewModel.dismissFinished() }
    val thread = s.thread
    val pending = s.pending
    val exists = thread != null || s.legacy != null

    Scaffold(
        containerColor = c.paper, contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(AutopilotIcons.Back, contentDescription = "Back", onClick = onBack)
                    Text(thread?.title ?: s.legacy?.title ?: "Research online", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), color = c.ink, maxLines = 2, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }.testTag("thread_title"))
                    if (running != null) DraftPill("Researching") else if (!exists || pending != null) DraftPill("Nothing searched yet", Modifier.testTag("thread_pill"))
                }
                FlowRow(Modifier.padding(start = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.taskId != null && s.taskTitle != null) {
                        LinkButton("For: ${s.taskTitle}", onClick = { onOpenTask(s.taskId!!) }, modifier = Modifier.testTag("thread_task"))
                        if (exists && running == null) LinkButton("Unlink", onClick = viewModel::unlink, modifier = Modifier.testTag("thread_unlink"))
                    } else {
                        Text("Not linked to a task", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 12.dp).testTag("thread_standalone"))
                        if (exists && running == null) LinkButton("Link to a task…", onClick = viewModel::openLink, modifier = Modifier.testTag("thread_link"))
                    }
                    if (exists && running == null) LinkButton("Delete", onClick = { confirmDelete = true }, modifier = Modifier.testTag("thread_delete"))
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
                                SecondaryButton("Stop", onClick = viewModel::openStop, icon = AutopilotIcons.Stop, enabled = !running.stopRequested, modifier = Modifier.fillMaxWidth().testTag("research_stop"))
                                Text("Leaving this screen keeps it running. Closing the app interrupts it; what arrived is kept and nothing is re-sent.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                        pending?.status == AfStatus.READY && s.authorization != null -> {
                            HorizontalDivider(color = c.line)
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                val a = s.authorization!!
                                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                    Text(estimateText(a), style = TimeTextStyle.copy(fontSize = 18.sp), color = c.ink, modifier = Modifier.testTag("auth_estimate"))
                                    Text("estimate · limit ${UsdEstimate.format(a.config.spendLimitCents / 100.0)}", style = MaterialTheme.typography.labelSmall, color = c.ink2)
                                }
                                ButtonPair(
                                    secondary = { m -> SecondaryButton("Not now", onClick = viewModel::decline, modifier = m.testTag("auth_decline")) },
                                    primary = { m -> PrimaryButton("Start research", onClick = { if (s.overMonthlyBudget) confirmBudget = true else viewModel.start() }, enabled = s.canStart, icon = AutopilotIcons.Research, modifier = m.testTag("auth_start")) },
                                )
                                Text("Billed by Anthropic to your API key. Starts only when you tap; no retries, nothing in the background.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
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
                    if (s.online == false) ResearchBanner("You're offline", "Everything saved here is readable. Asking and researching need a connection.", icon = AutopilotIcons.WifiOff, tag = "thread_offline")
                    if (s.busyElsewhere) ResearchBanner("Another research is running", "One research runs at a time. This one can start when it finishes.", tag = "thread_busy")
                }
            }
            s.legacy?.let { inv ->
                item { LegacyThread(inv, s, zone, onOpenLegacy = { onOpenLegacy(inv.id) }, onReview = { turnId -> viewModel.openLegacyReview(turnId, onReview) }) }
                return@LazyColumn
            }
            if (!exists && !s.loading) item { Composer(s, viewModel, followUp = false) }
            items(s.shownTurns, key = { "turn_" + it.id }) { t -> TurnCard(t, s, zone, viewModel, onSuggest = { viewModel.openSuggest(t.id, onReview) }) }
            if (running != null) item { RunningCard(running, now) }
            if (pending != null && running == null) item { PendingTurn(pending, s, viewModel) }
            if (thread != null && pending == null && running == null) item { Composer(s, viewModel, followUp = true) }
        }
    }

    if (s.stopSheet && running != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeStop,
            title = { Text("Stop this research?") },
            text = { Text("Whatever has arrived is kept. Searches that already ran may be billed; a call in progress may still be billed in full.") },
            confirmButton = { TextButton(onClick = viewModel::stop, modifier = Modifier.testTag("stop_confirm")) { Text("Stop and keep what arrived") } },
            dismissButton = { TextButton(onClick = viewModel::closeStop) { Text("Keep researching") } },
        )
    }
    s.payloadFor?.let { id ->
        val a = if (id == ThreadUi.PENDING) s.authorization else thread?.turns?.firstOrNull { it.id == id }?.authorization
        if (a != null) PayloadSheet(a, historical = id != ThreadUi.PENDING, onDismiss = viewModel::closePayload) else LaunchedEffect(id) { viewModel.closePayload() }
    }
    s.source?.let { (turnId, n) ->
        val t = thread?.turns?.firstOrNull { it.id == turnId }
        val src = t?.sources?.firstOrNull { it.n == n }
        if (t != null && src != null) SourceSheet(src, t, s, zone, onDismiss = viewModel::closeSource) else LaunchedEffect(turnId, n) { viewModel.closeSource() }
    }
    s.suggestFor?.let { (_, payload) ->
        AutopilotSheet(title = "Suggest changes to the task", onDismiss = viewModel::closeSuggest, footer = {
            PrimaryButton("Ask Claude for suggestions", onClick = { viewModel.confirmSuggest(onReview) }, modifier = Modifier.fillMaxWidth().testTag("suggest_confirm"))
        }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("suggest_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One small call to Claude Haiku, without web search, usually well under \$0.01 and recorded in AI usage. It sends exactly this:", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                SurfaceCard(radius = 12.dp, background = c.sunken, contentPadding = PaddingValues(12.dp)) { Text(payload, style = TimeTextStyle.copy(fontSize = 11.sp, lineHeight = 15.sp), color = c.ink, modifier = Modifier.testTag("suggest_payload")) }
                Text("Suggestions open in the review. Nothing on your task changes unless you apply it there.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
            }
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

internal fun estimateText(a: AfAuthorization): String =
    if (a.estimateLowMicros != null && a.estimateHighMicros != null) UsdEstimate.formatRange(a.estimateLowMicros / 1e6, a.estimateHighMicros / 1e6) else "No price table for this model"

internal fun configLine(cfg: AfConfig): String =
    "${AiConfig.label(cfg.model).removePrefix("Claude ")} · up to ${cfg.searchBudget} ${if (cfg.searchBudget == 1) "search" else "searches"} · limit ${UsdEstimate.format(cfg.spendLimitCents / 100.0)}" +
        (cfg.effort?.let { " · effort $it" } ?: "")

private fun cost(micros: Long, floor: Boolean): String = (if (floor) "at least " else "~") + UsdEstimate.format(micros / 1e6)

// ------------------------------------------------------------------ asking

@Composable
private fun Composer(s: ThreadUi, vm: InvestigationThreadViewModel, followUp: Boolean) {
    val c = AutopilotTheme.colors
    val over = s.question.length > Research2Config.MAX_REQUEST_CHARS
    Column(Modifier.padding(top = if (followUp) 20.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (followUp) BriefSectionTitle("Ask a follow-up") else Text("What should I find out?", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = s.question, onValueChange = vm::setQuestion, modifier = Modifier.fillMaxWidth().testTag(if (followUp) "thread_followup" else "thread_question"),
            placeholder = { Text(if (followUp) "e.g. What if I don't want iron?" else "e.g. What documents do I need to renew my passport?", color = c.ink3) }, minLines = if (followUp) 1 else 2, maxLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            supportingText = { Text(if (over) "${s.question.length} / ${Research2Config.MAX_REQUEST_CHARS} — too long" else "${s.question.length} / ${Research2Config.MAX_REQUEST_CHARS}", color = if (over) c.danger else c.ink3) },
        )
        val label = if (s.asking) "Checking the question…" else "Continue"
        val enabled = s.question.isNotBlank() && !s.asking && !over
        if (followUp) SecondaryButton(label, onClick = vm::ask, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("thread_followup_prepare"))
        else PrimaryButton(label, onClick = vm::ask, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("thread_prepare"))
        Text(
            (if (s.aiReady) "Continue sends only your question" + (if (followUp) " and this thread's earlier questions" else "") + " to Claude Haiku to check whether anything must be clarified first: no web search, usually well under \$0.01, recorded in AI usage. Then you'll see"
            else "Next you'll see") + " exactly what research would send and what it may cost. Nothing is searched until you start." +
                (if (followUp) " The follow-up carries this thread's earlier questions, answers and source lists, and nothing from other research." else ""),
            style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("composer_note"),
        )
    }
}

// ------------------------------------------------------------------ clarification + authorization

@Composable
private fun PendingTurn(t: AfTurn, s: ThreadUi, vm: InvestigationThreadViewModel) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 16.dp).testTag("thread_pending"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (t.index > 0) ResearchStamp("Follow-up · turn ${t.index + 1}")
        SurfaceCard(radius = 20.dp, modifier = Modifier.testTag("question_card")) {
            Text("YOUR QUESTION", style = MaterialTheme.typography.labelSmall, color = c.ink2)
            Text(t.question, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 6.dp).testTag("pending_question"))
            val cl = t.clarification
            when {
                cl == null -> Unit
                t.status == AfStatus.CLARIFYING && cl.question != null -> Clarify(cl.question, cl.options, vm::answerClarification, vm::skipClarification)
                cl.question != null && cl.answer != null -> Text("You clarified: ${cl.question} — ${cl.answer}", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 6.dp).testTag("clarified"))
                cl.question != null && cl.skipped -> Text("Claude asked “${cl.question}”; you chose to research it as asked.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                cl.checked -> Text("Checked first: nothing needs clarifying.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp).testTag("clarify_clear"))
                else -> Text(cl.note ?: "The question wasn't checked first.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp).testTag("clarify_unchecked"))
            }
            if (cl != null && (cl.checked || cl.costMicros > 0)) Text("Checking the question: ${cost(cl.costMicros, cl.costIsFloor)} (Claude Haiku, no web search).", style = MaterialTheme.typography.labelSmall, color = c.ink3, modifier = Modifier.padding(top = 4.dp).testTag("clarify_cost"))
        }
        if (t.status == AfStatus.READY) AuthorizationCard(s, vm)
    }
}

@Composable
private fun Clarify(question: String, options: List<String>, onAnswer: (String) -> Unit, onSkip: () -> Unit) {
    val c = AutopilotTheme.colors
    var answer by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(top = 10.dp).testTag("clarify"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("clarify_question"))
        Text("Nothing has been searched. Answering this keeps the research from paying for the wrong reading.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        if (options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, opt -> ChoiceChip(opt, selected = false, onClick = { onAnswer(opt) }, modifier = Modifier.testTag("clarify_option_$i")) }
        }
        OutlinedTextField(answer, { answer = it }, modifier = Modifier.fillMaxWidth().testTag("clarify_answer"), placeholder = { Text("Or type your answer", color = c.ink3) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = fieldColors())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Use this answer", onClick = { onAnswer(answer) }, enabled = answer.isNotBlank(), minHeight = 44.dp, modifier = Modifier.testTag("clarify_submit"))
            LinkButton("Research as asked", onClick = onSkip, modifier = Modifier.testTag("clarify_skip"))
        }
    }
}

@Composable
private fun AuthorizationCard(s: ThreadUi, vm: InvestigationThreadViewModel) {
    val c = AutopilotTheme.colors
    val cfg = s.config ?: return
    val a = s.authorization
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("auth_card")) {
        SectionTitle("How it researches", Modifier.padding(top = 8.dp))
        ResearchStamp(configLine(cfg), Modifier.testTag("auth_config"))
        LinkButton(if (s.choices.adjustOpen) "Hide adjustments" else "Adjust model, effort, searches and limit", onClick = vm::toggleAdjust, modifier = Modifier.testTag("auth_adjust"))
        if (s.choices.adjustOpen) SurfaceCard(radius = 18.dp, modifier = Modifier.testTag("adjust_panel")) { AdjustPanel(cfg, vm) }
        if (a != null) {
            Text(
                "Estimate if all ${a.config.searchBudget} searches are used and the answer runs long: ${estimateText(a)}. Most answers cost less. It is not a guaranteed maximum: pages with long text cost more.",
                style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("auth_estimate_note"),
            )
            Text(
                "Before every call the app checks the spending so far plus a high estimate of that call against your limit, and stops instead of sending one that could pass it. A call already in progress can't be cut short, so it can still cost more than estimated.",
                style = MaterialTheme.typography.bodySmall, color = c.ink2,
            )
            if (s.limitTooLow) ResearchBanner("Your limit is below one research call", "Raise the limit or allow fewer searches; otherwise nothing would be sent.", warn = true, icon = AutopilotIcons.Warn, tag = "auth_limit_low")
        }

        SectionTitle("What Claude will see", Modifier.padding(top = 8.dp))
        SurfaceCard(radius = 20.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("auth_context")) {
            SwitchRow("Your question" + if (a?.clarified != null) " and clarification" else "", checked = true, onCheckedChange = {}, subtitle = "Always sent", enabled = false)
            if (a?.includesHistory == true) SwitchRow("This thread's earlier answers", checked = true, onCheckedChange = {}, subtitle = "${a.historyTurnIds.size} earlier ${if (a.historyTurnIds.size == 1) "turn" else "turns"}: questions, answers and source lists", enabled = false, divider = true)
            s.context.forEach { item ->
                SwitchRow(item.label, checked = item.included, onCheckedChange = { on ->
                    val sc = s.choices.scope
                    vm.setScope(when (item.id) { "title" -> sc.copy(shareTitle = on); "notes" -> sc.copy(shareNotes = on); "steps" -> sc.copy(shareSteps = on); else -> sc.copy(shareLocale = on) })
                }, subtitle = item.value.take(90), divider = true, switchTestTag = "ctx_${item.id}")
            }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Never sent", style = MaterialTheme.typography.labelLarge, color = c.ink)
                Text("Your other tasks, other research, drafts, Inbox, reminders, location, contacts or your API key.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
            }
        }
        LinkButton("See exactly what's sent", onClick = { vm.openPayload(ThreadUi.PENDING) }, enabled = a != null, modifier = Modifier.testTag("auth_payload"))
        Text("Research only reads the web. It never buys, books, signs up or messages anyone. Your task changes only when you apply suggestions in the review.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
    }
}

@Composable
private fun AdjustPanel(cfg: AfConfig, vm: InvestigationThreadViewModel) {
    val c = AutopilotTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Model", style = MaterialTheme.typography.titleSmall, color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ResearchOnlineService.MODELS.forEach { m -> ChoiceChip(AiConfig.label(m).removePrefix("Claude "), selected = cfg.model == m, onClick = { vm.setModel(m) }, count = PricingCatalog.relativeCost(m), modifier = Modifier.testTag("adjust_model_$m")) }
        }
        Text("Reasoning effort", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(top = 6.dp))
        if (!EffortSupport.supports(cfg.model)) Text("Not configurable for ${AiConfig.label(cfg.model)} as far as this app knows; the provider's default applies.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("effort_unsupported"))
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Model default", selected = cfg.effort == null, onClick = { vm.setEffort(null) }, modifier = Modifier.testTag("effort_default"))
            EffortSupport.LEVELS.forEach { e -> ChoiceChip(EffortSupport.label(e), selected = cfg.effort == e, onClick = { vm.setEffort(e) }, modifier = Modifier.testTag("effort_$e")) }
        }
        Text("Search budget", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(top = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundIconButton(AutopilotIcons.Minus, contentDescription = "Fewer searches", onClick = { vm.setSearches(cfg.searchBudget - 1) }, modifier = Modifier.testTag("adjust_fewer"))
            Text("up to ${cfg.searchBudget}", style = TimeTextStyle.copy(fontSize = 16.sp), color = c.ink, modifier = Modifier.testTag("adjust_searches"))
            RoundIconButton(AutopilotIcons.Plus, contentDescription = "More searches", onClick = { vm.setSearches(cfg.searchBudget + 1) }, modifier = Modifier.testTag("adjust_more"))
        }
        Text("Claude decides how many to use; unused searches aren't charged. The server enforces the budget.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        Text("Spending limit", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ResearchOnlineService.LIMIT_CHOICES_CENTS.forEach { cents -> ChoiceChip(UsdEstimate.format(cents / 100.0), selected = cfg.spendLimitCents == cents, onClick = { vm.setLimit(cents) }, modifier = Modifier.testTag("limit_$cents")) }
        }
    }
}

// ------------------------------------------------------------------ running

@Composable
private fun RunningCard(run: AfRunState.Running, now: () -> Instant) {
    val c = AutopilotTheme.colors
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(run.turnId) { while (true) { elapsedMs = (now().toEpochMilli() - run.startedAtMs).coerceAtLeast(0); delay(1_000) } }
    val p = run.progress
    Column(Modifier.padding(top = 16.dp).testTag("research_progress"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (run.stopRequested) "Stopping…" else "Researching…", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() })
        ResearchStamp(configLine(run.config))
        SurfaceCard(radius = 20.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Counter("Searches", "${p.searches}/${run.config.searchBudget}")
                Counter("Calls", "${p.calls + if (p.callInFlight) 1 else 0}")
                Counter("Time", "%d:%02d".format(elapsedMs / 60_000, (elapsedMs / 1_000) % 60))
            }
            Text(
                if (p.callInFlight) "Claude is searching and writing. Counts arrive when the call returns" + (p.callTimeoutSeconds?.let { " (it gives up after ${it / 60} min)" } ?: "") + "."
                else if (p.calls > 0) "Continuing where the last call paused." else "Sending…",
                style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("progress_waiting"),
            )
            Text("Measured so far: ${cost(p.spentMicros, p.spentIsFloor)} for finished calls." + if (p.hasPartial) " Their text is already saved." else "", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp).testTag("progress_spent"))
        }
        Text("Web pages are read as information only; instructions on pages are never followed.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
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

// ------------------------------------------------------------------ one turn

@Composable
private fun TurnCard(t: AfTurn, s: ThreadUi, zone: ZoneId, vm: InvestigationThreadViewModel, onSuggest: () -> Unit) {
    val c = AutopilotTheme.colors
    var showDetails by rememberSaveable(t.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.padding(top = 18.dp).testTag("turn_${t.index}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (t.index > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(bottom = 8.dp))
        ResearchStamp((if (t.authorization?.includesHistory == true) "Follow-up" else "Asked") + " · " + Format.relative(Instant.ofEpochMilli(t.createdAtMs), s.now, zone))
        Text("“${t.question}”", style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.testTag("turn_question_${t.index}"))
        t.authorization?.clarified?.let { Text("Clarified: ${it.answer}", style = MaterialTheme.typography.bodySmall, color = c.ink2) }
        if (t.status == AfStatus.DECLINED) { Text(t.note ?: "Not started. Nothing was sent for research.", style = MaterialTheme.typography.bodyMedium, color = c.ink3, modifier = Modifier.testTag("turn_declined_${t.index}")); return@Column }
        endingBanner(t)?.let { (title, body) -> ResearchBanner(title, body, warn = true, icon = if (t.status == AfStatus.STOPPED) AutopilotIcons.Stop else AutopilotIcons.Warn, tag = "turn_ending_${t.index}") }
        if (t.hasAnswer) {
            SurfaceCard(radius = 18.dp, modifier = Modifier.testTag("turn_answer_${t.index}")) {
                AnswerText(t.segments, onSource = { n -> vm.openSource(t.id, n) })
            }
            val anyUncited = t.segments.any { it.sources.isEmpty() && it.text.isNotBlank() }
            if (anyUncited && t.sources.isNotEmpty()) Text("Text in grey has no source citation attached: it is Claude's own wording, inference or judgment, not checked against a page.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.testTag("turn_uncited_${t.index}"))
            if (t.sources.isEmpty()) Text("No source citation is attached to this answer. Treat it as Claude's own account, not as researched fact.", style = MaterialTheme.typography.bodySmall, color = c.onSignalSoft, modifier = Modifier.testTag("turn_nocitations_${t.index}"))
            if (t.unsuppliedLinks.isNotEmpty()) ResearchBanner("Links Claude wrote itself", "No search supplied these, so they are not sources and haven't been checked: " + t.unsuppliedLinks.joinToString(", "), warn = true, icon = AutopilotIcons.Warn, tag = "turn_unsupplied_${t.index}")
            if (t.sources.isNotEmpty()) {
                BriefSectionTitle("Sources", count = t.sources.size)
                SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("turn_sources_${t.index}")) {
                    t.sources.forEachIndexed { i, src -> SourceRow(src, onClick = { vm.openSource(t.id, src.n) }, divider = i > 0) }
                }
                Text("Sources are the pages Anthropic's search returned and attached to the answer. Dates are the search index's, not a live check.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
            }
            if (s.taskId != null && t.ran) {
                val pendingCount = s.suggestions[t.id]
                when {
                    pendingCount == null -> SecondaryButton(if (s.suggesting) "Asking for suggestions…" else "Suggest changes to the task…", onClick = onSuggest, enabled = !s.suggesting && s.running == null && s.aiReady, minHeight = 48.dp, modifier = Modifier.fillMaxWidth().testTag("turn_suggest_${t.index}"))
                    pendingCount > 0 -> PrimaryButton("Review $pendingCount suggested ${if (pendingCount == 1) "change" else "changes"}", onClick = onSuggest, modifier = Modifier.fillMaxWidth().testTag("turn_review_${t.index}"))
                    else -> LinkButton("See the suggestions from this answer", onClick = onSuggest, modifier = Modifier.testTag("turn_review_${t.index}"))
                }
            }
            LinkButton("Copy answer", onClick = { clipboard.setText(AnnotatedString(com.lifeadmin.autopilot.ai.aifirst.AfRender.text(t))) }, modifier = Modifier.testTag("turn_copy_${t.index}"))
        }
        LinkButton(if (showDetails) "Hide how this was researched" else "How this was researched", onClick = { showDetails = !showDetails }, modifier = Modifier.testTag("turn_log_toggle_${t.index}"))
        if (showDetails) RunLog(t, onPayload = { vm.openPayload(t.id) })
    }
}

private fun endingBanner(t: AfTurn): Pair<String, String>? = when (t.status) {
    AfStatus.COMPLETE -> null
    AfStatus.STOPPED -> "Stopped" to (t.note ?: "Stopped by you.")
    AfStatus.INTERRUPTED -> "Interrupted" to (t.note ?: "The app closed while this was running.")
    AfStatus.FAILED -> "Didn't finish" to (t.note ?: "The research failed.")
    else -> AfThreadRepository.label(t.status) to (t.note ?: "")
}

@Composable
private fun RunLog(t: AfTurn, onPayload: () -> Unit) {
    val c = AutopilotTheme.colors
    val a = t.authorization
    SurfaceCard(radius = 18.dp, modifier = Modifier.testTag("turn_log_${t.index}")) {
        if (a != null) {
            Text("Approved: " + configLine(a.config), style = MaterialTheme.typography.bodySmall, color = c.ink)
            Text("Estimated beforehand: ${estimateText(a)}", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 2.dp))
        }
        Text(
            "Measured: ${t.calls} ${if (t.calls == 1) "call" else "calls"} · ${t.webSearches} ${if (t.webSearches == 1) "search" else "searches"} · ${cost(t.costMicros, t.costIsFloor)}" +
                (t.clarification?.takeIf { it.costMicros > 0 || it.checked }?.let { " · plus ${cost(it.costMicros, it.costIsFloor)} to check the question" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.padding(top = 4.dp).testTag("turn_used_${t.index}"),
        )
        if (t.costIsFloor) Text("Some usage was never reported (a stop, failure or interruption), so the real cost may be higher. AI usage shows each call.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 2.dp))
        if (t.searches.isNotEmpty()) {
            Text("SEARCHES CLAUDE RAN", style = MaterialTheme.typography.labelSmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
            t.searches.forEach { q -> Text("“${q.query.ifBlank { "(query not reported)" }}” · " + (q.error?.let { "failed: $it" } ?: "${q.results} results"), style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.padding(top = 2.dp)) }
        }
        if (a != null) {
            Text("Approved " + Instant.ofEpochMilli(a.authorizedAtMs).toString().take(16).replace('T', ' ') + " UTC · fingerprint ${a.payloadSha256.take(12)}", style = TimeTextStyle.copy(fontSize = 11.sp), color = c.ink3, modifier = Modifier.padding(top = 6.dp))
            LinkButton("What was sent", onClick = onPayload, modifier = Modifier.testTag("turn_payload_${t.index}"))
        }
    }
}

@Composable
private fun SourceRow(source: AfSource, onClick: () -> Unit, divider: Boolean) {
    val c = AutopilotTheme.colors
    if (divider) HorizontalDivider(color = c.line)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp).testTag("source_${source.n}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CitationChip(source.n, onClick = onClick, standalone = true, title = source.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(source.title ?: (Sources.host(source.url) ?: source.url), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((Sources.host(source.url) ?: source.url) + (source.pageAge?.let { " · indexed $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        androidx.compose.material3.Icon(AutopilotIcons.ChevronRight, contentDescription = null, tint = c.ink3)
    }
}

// ------------------------------------------------------------------ sheets

@Composable
private fun SourceSheet(source: AfSource, t: AfTurn, s: ThreadUi, zone: ZoneId, onDismiss: () -> Unit) {
    val c = AutopilotTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val safe = Sources.safeExternalUrl(source.url)
    AutopilotSheet(
        title = null, onDismiss = onDismiss, titleLeading = { CitationChip(source.n, onClick = {}, standalone = true, title = source.title) },
        footer = {
            PrimaryButton(
                if (s.online == false) "Open in browser (offline)" else "Open in browser",
                onClick = {
                    @Suppress("UseKtx") // core-ktx is not a dependency of this module
                    val uri = safe?.let { android.net.Uri.parse(it) } ?: return@PrimaryButton
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                },
                enabled = safe != null && s.online != false, icon = AutopilotIcons.OpenExternal, modifier = Modifier.fillMaxWidth().testTag("source_open"),
            )
            Text("Opens outside the app. Check the address before you sign in or pay anywhere.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("source_sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(source.title ?: (Sources.host(source.url) ?: source.url), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
            Text((Sources.host(source.url) ?: source.url), style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            if (source.quotes.isNotEmpty()) {
                Text("TEXT THE CITATION POINTS TO", style = MaterialTheme.typography.labelSmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                source.quotes.forEach { q -> Text("“$q”", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(start = 12.dp).testTag("source_quote")) }
                Text("Anthropic's search supplies these excerpts (about 150 characters each). The answer can say more than they show; open the page to check.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
            }
            ResearchStamp("Researched ${Format.relative(Instant.ofEpochMilli(t.finishedAtMs ?: t.createdAtMs), s.now, zone)}" + (source.pageAge?.let { " · search index age: $it" } ?: ""))
            Text("The search reads an index, not the live page: prices, stock and hours may have changed since.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
            Text(source.url, style = TimeTextStyle.copy(fontSize = 12.sp, lineHeight = 16.sp), color = c.ink2, modifier = Modifier.testTag("source_url"))
            LinkButton("Copy link", onClick = { clipboard.setText(AnnotatedString(source.url)) }, modifier = Modifier.testTag("source_copy"))
            if (safe == null) Text("This address isn't a normal web link, so the app won't open it.", style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
    }
}

@Composable
private fun PayloadSheet(a: AfAuthorization, historical: Boolean, onDismiss: () -> Unit) {
    val c = AutopilotTheme.colors
    AutopilotSheet(title = if (historical) "What was sent" else "Exactly what would be sent", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("payload_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${configLine(a.config)}. The message below is sent exactly as shown, with the app's fixed research instructions (version ${a.promptVersion}) and the web search tool limited to ${a.config.searchBudget} uses. Your API key only authenticates it.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
            SurfaceCard(radius = 12.dp, background = c.sunken, contentPadding = PaddingValues(12.dp)) {
                Text(a.payload, style = TimeTextStyle.copy(fontSize = 11.sp, lineHeight = 15.sp), color = c.ink, modifier = Modifier.testTag("payload_text"))
            }
            ResearchStamp("SHA-256 ${a.payloadSha256.take(16)}…")
        }
    }
}

@Composable
private fun LinkSheet(s: ThreadUi, vm: InvestigationThreadViewModel) {
    val c = AutopilotTheme.colors
    AutopilotSheet(title = "Link to a task", onDismiss = vm::closeLink) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("link_sheet")) {
            Text("Linking lets you ask for task suggestions from answers. Nothing about the task is sent until you start a research turn with it switched on.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(bottom = 8.dp))
            if (s.tasks.isEmpty()) Text("No open tasks.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            s.tasks.take(40).forEachIndexed { i, t -> GroupRow(t.task.title, onClick = { vm.link(t.task.id, t.task.title) }, divider = i > 0, minHeight = 52.dp, modifier = Modifier.testTag("link_task_${t.task.id}")) }
        }
    }
}
