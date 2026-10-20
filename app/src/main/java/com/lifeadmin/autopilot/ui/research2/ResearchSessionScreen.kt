package com.lifeadmin.autopilot.ui.research2

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.research2.DeliverableTemplate
import com.lifeadmin.autopilot.ai.research2.RequirementStatus
import com.lifeadmin.autopilot.ai.research2.RequirementType
import com.lifeadmin.autopilot.ai.research2.ResearchMode
import com.lifeadmin.autopilot.ai.research2.ResearchModes
import com.lifeadmin.autopilot.ai.research2.SessionAssembler
import com.lifeadmin.autopilot.ai.research2.SessionContent
import com.lifeadmin.autopilot.ai.research2.SessionOutcome
import com.lifeadmin.autopilot.ai.research2.StageResult
import com.lifeadmin.autopilot.ai.usage.CostCalculator
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.ButtonPair
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.research.BriefSectionTitle
import com.lifeadmin.autopilot.ui.research.CitationChip
import com.lifeadmin.autopilot.ui.research.ResearchBanner
import com.lifeadmin.autopilot.ui.research.ResearchStamp
import com.lifeadmin.autopilot.ui.research.SourceRow
import com.lifeadmin.autopilot.ui.research.SourceSheet
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Sum of the session's runs priced with the catalog; partial when any run's usage was incomplete. */
fun sessionEstimate(content: SessionContent): Pair<UsdEstimate, Boolean> {
    var total = UsdEstimate.ZERO
    var partial = false
    for (r in content.runs) {
        val u = r.usage
        val priced = CostCalculator.price(r.model, MeasuredUsage(u.inputTokens, u.outputTokens, u.cacheWriteTokens, 0, u.cacheReadTokens, u.webSearches))
        priced.estimate?.let { total += it }
        if (!u.complete || priced.estimate == null) partial = true
    }
    return total to partial
}

/**
 * Research result (M3A.2). Fixed order across templates: banners → title/stamp/cost → request →
 * answer (A, D) → coverage → callouts → template body → continue → sources → how researched → footer.
 * Nothing here changes the task; that happens only on the proposals screen.
 */
@Composable
fun ResearchSessionScreen(
    viewModel: Session2ViewModel,
    zone: ZoneId,
    onBack: () -> Unit,
    onReview: () -> Unit,
    onContinueStarted: (runId: String) -> Unit,
    onOpenUsage: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val session = s.session
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var requestOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = c.paper, contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AutopilotIcons.Back, contentDescription = "Back", onClick = onBack)
                Text("Research", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
                session?.let { ss ->
                    val (est, partial) = sessionEstimate(ss.content)
                    LinkButton((if (partial) "≥ " else "~") + UsdEstimate.format(est.usd), onClick = onOpenUsage, modifier = Modifier.testTag("cost_chip"))
                }
            }
        },
        bottomBar = {
            if (session != null) {
                Surface(color = c.surface, contentColor = c.ink) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        HorizontalDivider(color = c.line)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            val terminal = s.details?.task?.status?.isTerminal == true
                            val applied = session.applied.map { it.proposalId }.toSet()
                            val n = session.content.proposals.count { it.id !in applied }
                            ButtonPair(
                                secondary = { m -> SecondaryButton("Done", onClick = onBack, modifier = m.testTag("brief_done")) },
                                primary = { m -> PrimaryButton(if (n == 0) "Nothing to add" else "Review $n ${if (n == 1) "change" else "changes"}", onClick = onReview, enabled = n > 0 && !terminal && s.details != null && !s.running, modifier = m.testTag("brief_review")) },
                            )
                            Text(if (terminal) "This task is done. Reopen it to add proposals." else if (s.running) "Continuing research — proposals update when it finishes." else "Saved to this task's research. Your task hasn't changed.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            s.loading -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding))
            session == null -> Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Text("This research was deleted", style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text("Steps you added from it are still on the task.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            }
            else -> {
                val content = session.content
                val sources = content.sources
                val items = content.requirements.filter { it.type == RequirementType.ITEM || it.type == RequirementType.USER_ADDED }
                val open = SessionAssembler.gaps(content)
                val affected = open.map { it.label }
                val cite = viewModel::openSource
                val latest = content.latestRun
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).testTag("brief_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutcomeBanner(content.outcome, affected, latest?.technical, onContinue = if (!s.running) viewModel::openContinue else null)
                            if (s.running) ResearchBanner("Continuing research now", "New findings merge into this result when the run finishes.", icon = AutopilotIcons.Research, tag = "brief_running", action = { LinkButton("Watch progress", onClick = { s.runId?.let(onContinueStarted) }, modifier = Modifier.padding(top = 4.dp)) })
                            if (s.isStale) ResearchBanner("Researched ${Format.relativeLower(session.updatedAt, s.now, zone)}", "Prices, stock and rules may have changed since.", warn = true, tag = "brief_stale")
                            if (s.online == false) ResearchBanner("You're offline", "This saved result is fully readable; links open once you're back online.", icon = AutopilotIcons.WifiOff, tag = "brief_offline")
                            if (sources.none { it.citedCount > 0 }) ResearchBanner("Nothing here could be verified", "The answer came back without any source citations, so nothing below counts as a fact and no steps are pre-selected.", warn = true, icon = AutopilotIcons.Warn, tag = "brief_unverified")
                            if (content.injectionNoticed) ResearchBanner("A web page tried to give instructions", "Page text is information only. The line was dropped and nothing was done.", icon = AutopilotIcons.Shield, tag = "brief_injection")
                            Text(content.title, style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.padding(top = 4.dp).semantics { heading() }.testTag("brief_title"))
                            latest?.let { ResearchStamp(stampLine(it.mode, it.model, content.searchesTotal, content.runs.sumOf { r -> r.usage.elapsedMs }) + (if (content.runs.size > 1) " · ${content.runs.size} runs" else ""), Modifier.testTag("brief_stamp")) }
                            LinkButton(if (requestOpen) "Hide your request" else "Your request", onClick = { requestOpen = !requestOpen }, modifier = Modifier.testTag("brief_request_toggle"))
                            if (requestOpen) Text("“${content.request}”", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.testTag("brief_request"))
                        }
                    }
                    if (content.answer.isNotEmpty() && (content.template == DeliverableTemplate.QUICK_ANSWER || content.template == DeliverableTemplate.HOW_TO || content.template == DeliverableTemplate.SHORTLIST)) item {
                        BriefSectionTitle(if (content.template == DeliverableTemplate.SHORTLIST) "Recommendation" else "Answer")
                        SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(16.dp), modifier = Modifier.testTag("brief_answer")) { CitedText2(content.answer, sources, cite) }
                    }
                    item {
                        BriefSectionTitle("Your request, point by point")
                        Text(SessionAssembler.coverageLine(content).ifBlank { "${content.requirements.count { content.coverageOf(it.id).status.covered }} of ${content.requirements.size} covered" }, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(bottom = 8.dp).testTag("brief_coverage_line"))
                        SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("brief_coverage")) {
                            content.requirements.forEachIndexed { i, r -> CoverageRow(r, content.coverageOf(r.id), onClick = { scope.launch { listState.animateScrollToItem(4) } }, divider = i > 0) }
                        }
                    }
                    item {
                        if (content.callouts.isNotEmpty()) Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content.callouts.take(2).forEach { CalloutCard(it) } }
                    }
                    when (content.template) {
                        DeliverableTemplate.PICK_PER_ITEM -> {
                            item { BriefSectionTitle("Picks", count = items.size) }
                            itemsIndexed(items, key = { _, r -> "item_" + r.id }) { i, r ->
                                ItemCard(r, content.body.picks.firstOrNull { it.requirementId == r.id }, content.coverageOf(r.id), sources, s.now, zone, cite, modifier = Modifier.padding(bottom = 10.dp), initiallyExpanded = i == 0)
                            }
                        }
                        DeliverableTemplate.SHORTLIST -> shortlistItems(s, viewModel, zone)
                        DeliverableTemplate.HOW_TO, DeliverableTemplate.CHECKLIST -> {
                            item {
                                BriefSectionTitle(if (content.template == DeliverableTemplate.HOW_TO) "Steps" else "Checklist", count = content.body.steps.size)
                                if (content.body.steps.isEmpty()) Text("No steps came back.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                            }
                            itemsIndexed(content.body.steps) { i, st -> StepList(listOf(st), sources, cite, numbered = content.template == DeliverableTemplate.HOW_TO, startIndex = i, modifier = Modifier.padding(bottom = 8.dp)) }
                        }
                        DeliverableTemplate.QUICK_ANSWER -> Unit
                    }
                    if (content.claims.isNotEmpty()) {
                        item { BriefSectionTitle("What I found", count = content.claims.size) }
                        items(content.claims) { cl ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProvenanceTagChip(cl.tag, Modifier.padding(top = 3.dp))
                                Text(cl.text, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
                                cl.refs.take(2).forEach { n -> CitationChip(n, onClick = { cite(n) }, title = sources.firstOrNull { it.n == n }?.title) }
                            }
                        }
                    }
                    if (content.conflicts.isNotEmpty()) {
                        item { BriefSectionTitle("Where sources disagreed", count = content.conflicts.size) }
                        items(content.conflicts) { cf ->
                            SurfaceCard(radius = 14.dp, contentPadding = PaddingValues(12.dp), modifier = Modifier.padding(bottom = 8.dp).testTag("conflict")) {
                                Text(cf.topic, style = MaterialTheme.typography.titleSmall, color = c.ink)
                                cf.sides.forEach { side -> Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) { side.refs.firstOrNull()?.let { CitationChip(it, onClick = { cite(it) }) }; Text(side.says, style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.weight(1f)) } }
                                Text("Resolution: ${cf.resolution ?: "unresolved — check before you rely on either"}", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    if (content.unresolved.isNotEmpty()) {
                        item { BriefSectionTitle("Still unresolved", count = content.unresolved.size) }
                        items(content.unresolved) { u -> Text("• ${u.topic} — ${u.why}", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(bottom = 6.dp)) }
                    }
                    item {
                        if (!s.running && (content.outcome != SessionOutcome.COMPLETE || open.isNotEmpty() || content.unresolved.isNotEmpty())) {
                            Spacer(Modifier.heightIn(min = 16.dp))
                            ContinueCard(open.map { it.label } + content.unresolved.map { it.topic }.take(3), s.continueConfig.estimate(), onContinue = viewModel::openContinue)
                        }
                    }
                    item {
                        BriefSectionTitle("Sources", count = sources.size)
                        if (sources.isEmpty()) Text("No web pages were cited or read.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                    }
                    val shown = if (s.showAllSources) sources else sources.take(5)
                    itemsIndexed(shown, key = { _, src -> "src_" + src.n }) { i, src ->
                        SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(bottom = 6.dp).testTag(if (i == 0) "brief_sources" else "brief_source_card_$i")) {
                            SourceRow(src.legacy(), onClick = { cite(src.n) })
                        }
                    }
                    if (sources.size > 5) item { LinkButton(if (s.showAllSources) "Show fewer" else "Show all ${sources.size}", onClick = viewModel::toggleAllSources, modifier = Modifier.testTag("brief_sources_all")) }
                    item {
                        LinkButton(if (s.showLog) "Hide how this was researched" else "How this was researched", onClick = viewModel::toggleLog, modifier = Modifier.padding(top = 18.dp).testTag("brief_log_toggle"))
                        if (s.showLog) SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(16.dp), modifier = Modifier.testTag("brief_log")) {
                            content.runs.forEachIndexed { ri, run ->
                                if (ri > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 8.dp))
                                ResearchStamp("${if (run.kind == com.lifeadmin.autopilot.ai.research2.RunKind.CONTINUATION) "Continuation" else "Run"} ${ri + 1} · ${run.mode.label} · ${AiConfig.label(run.model)} · ${run.outcome.label}")
                                run.stages.forEach { st ->
                                    Text("${st.kind.label}: ${st.result.name.lowercase()}" + (if (st.result == StageResult.DONE) " · ${st.searchesRun} of ${st.searchesAllowed} searches" + (if (st.calls > 1) " · ${st.calls} calls" else "") else "") + (st.note?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                                    st.queries.forEach { q -> Text("   “$q”", style = MaterialTheme.typography.bodySmall, color = c.ink) }
                                }
                                Text("${run.usage.inputTokens} in · ${run.usage.outputTokens} out" + (if (run.usage.cacheReadTokens > 0) " · ${run.usage.cacheReadTokens} cached" else "") + " · ${run.usage.webSearches} searches · ${run.usage.elapsedMs / 1000} s" + (if (!run.usage.complete) " · usage incomplete" else ""), style = MaterialTheme.typography.labelSmall, color = c.ink3, modifier = Modifier.padding(top = 4.dp))
                            }
                            Text("Shared: ${content.shared.joinToString(", ").lowercase()}.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
                            LinkButton("See cost breakdown", onClick = onOpenUsage, modifier = Modifier.padding(top = 4.dp).testTag("brief_cost_link"))
                        }
                    }
                }
                s.sourceSheet?.let { n ->
                    sources.firstOrNull { it.n == n }?.let { src ->
                        val usedFor = buildList {
                            content.body.candidates.filter { n in it.refs || it.attrs.any { a -> n in a.refs } }.forEach { add("Candidate: ${it.name}") }
                            content.body.picks.filter { n in it.refs || it.labelFacts.any { a -> n in a.refs } }.forEach { p -> add("Pick for ${content.requirements.firstOrNull { it.id == p.requirementId }?.label ?: p.requirementId}") }
                            content.claims.filter { n in it.refs }.forEach { add(it.text) }
                            if (content.answer.any { n in it.refs }) add("The answer")
                        }
                        SourceSheet(src.legacy(), usedFor, researchedLine = "Researched ${Format.relative(session.updatedAt, s.now, zone)}", onDismiss = viewModel::closeSource, online = s.online)
                    }
                }
                s.candidateSheet?.let { id -> content.body.candidates.firstOrNull { it.id == id }?.let { cd -> CandidateSheet(cd, content, s, zone, viewModel) } }
                if (s.continueSheet) ContinueSheet(s, viewModel, onContinueStarted)
            }
        }
    }
}

private fun LazyListScope.shortlistItems(s: Session2Ui, viewModel: Session2ViewModel, zone: ZoneId) {
    val content = s.session!!.content
    val all = content.body.candidates.filter { it.included }
    val styles = all.mapNotNull { it.style }.distinct()
    // Local, free re-rank: the chosen style first, then the model's order. No call is made.
    val ordered = if (s.styleFilter == null) all.sortedByDescending { it.hero } else all.sortedWith(compareByDescending<com.lifeadmin.autopilot.ai.research2.Candidate> { it.style == s.styleFilter }.thenByDescending { it.hero })
    val hero = ordered.firstOrNull()
    if (hero != null) item {
        BriefSectionTitle(if (s.styleFilter == null) (if (hero.hero) "Safest pick if you don't know their taste" else "Top of the list") else "Top pick for “${s.styleFilter}”")
        CandidateCard(hero, content.sources, s.now, zone, viewModel::openSource, onDetails = { viewModel.openCandidate(hero.id) }, rank = 1)
    }
    if (styles.size >= 2) item {
        val c = AutopilotTheme.colors
        Text("Know their taste? Re-rank without new searches", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(top = 14.dp))
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Unknown taste", selected = s.styleFilter == null, onClick = { viewModel.setStyle(null) }, modifier = Modifier.testTag("style_none"))
            styles.forEach { st -> ChoiceChip(st.replaceFirstChar { ch -> ch.uppercase() }, selected = s.styleFilter == st, onClick = { viewModel.setStyle(st) }, modifier = Modifier.testTag("style_$st")) }
        }
        Text("Reorders what was already found on this phone. Free — no call is made.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 4.dp))
    }
    item {
        BriefSectionTitle("Shortlist", count = all.size)
        if (all.isEmpty()) Text("No candidates came back.", style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink2)
    }
    itemsIndexed(ordered.drop(if (hero != null) 1 else 0), key = { _, cd -> "cand_" + cd.id }) { i, cd ->
        CandidateCard(cd, content.sources, s.now, zone, viewModel::openSource, onDetails = { viewModel.openCandidate(cd.id) }, modifier = Modifier.padding(bottom = 10.dp), rank = i + 2)
    }
    if (all.isNotEmpty() && content.body.compareKeys.isNotEmpty()) item {
        val c = AutopilotTheme.colors
        BriefSectionTitle("Compare")
        FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(com.lifeadmin.autopilot.ai.research2.ProvenanceTag.FACT, com.lifeadmin.autopilot.ai.research2.ProvenanceTag.REVIEWS, com.lifeadmin.autopilot.ai.research2.ProvenanceTag.JUDGMENT, com.lifeadmin.autopilot.ai.research2.ProvenanceTag.CALC).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { ProvenanceTagChip(t); Text(t.label, style = MaterialTheme.typography.labelSmall, color = c.ink2) }
            }
        }
        CompareTable(ordered, content.body.compareKeys, content.sources, viewModel::openSource)
    }
    if (content.body.excluded.isNotEmpty()) {
        item { BriefSectionTitle("Left out", count = content.body.excluded.size) }
        items(content.body.excluded, key = { "ex_" + it.id }) { ex ->
            Text("• ${ex.name}${ex.variant?.let { " ($it)" } ?: ""} — ${ex.reason.ifBlank { "excluded" }}", style = MaterialTheme.typography.bodyMedium, color = AutopilotTheme.colors.ink2, modifier = Modifier.padding(bottom = 4.dp).testTag("excluded_${ex.id}"))
        }
    }
}

@Composable
private fun CandidateSheet(cd: com.lifeadmin.autopilot.ai.research2.Candidate, content: SessionContent, s: Session2Ui, zone: ZoneId, viewModel: Session2ViewModel) {
    val c = AutopilotTheme.colors
    AutopilotSheet(title = cd.name, onDismiss = viewModel::closeCandidate) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("candidate_sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(cd.variant ?: "Variant not confirmed", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            PriceLine(cd.price, s.now, zone, { viewModel.closeCandidate(); viewModel.openSource(it) })
            if (cd.reason.isNotBlank()) Text(cd.reason, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            cd.attrs.filter { it.key != "price" }.forEach { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(a.key.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = c.ink2)
                        Text(a.value, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                    ProvenanceTagChip(a.tag)
                    a.refs.firstOrNull()?.let { n -> CitationChip(n, onClick = { viewModel.closeCandidate(); viewModel.openSource(n) }, title = content.sources.firstOrNull { it.n == n }?.title) }
                }
            }
            Text("Facts come from cited pages; Judgment lines are Claude's view and say so.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
        }
    }
}

@Composable
private fun ContinueSheet(s: Session2Ui, viewModel: Session2ViewModel, onStarted: (String) -> Unit) {
    val c = AutopilotTheme.colors
    val content = s.session!!.content
    val cfg = s.continueConfig
    val est = cfg.estimate()
    val (soFar, partial) = sessionEstimate(content)
    AutopilotSheet(
        title = "Continue researching", onDismiss = viewModel::closeContinue,
        footer = {
            PrimaryButton("Continue · ${cfg.mode.label} · ${cfg.searchBudget} searches", onClick = { viewModel.continueResearch(onStarted) }, enabled = s.continueFocus.isNotEmpty() || s.continueNote.isNotBlank() || true, modifier = Modifier.fillMaxWidth().testTag("continue_go"))
            Text((est?.let { "${UsdEstimate.formatRange(it.lowUsd, it.highUsd)} estimate" } ?: "No price table") + " · this result so far ${if (partial) "≥ " else "~"}${UsdEstimate.format(soFar.usd)}. Billed to your key when you tap.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp).testTag("continue_estimate"))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("continue_sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Picks up from this result — the sources already found keep their numbers and nothing is searched again.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            s.continueError?.let { ResearchBanner(it.headline, it.message, warn = true, icon = AutopilotIcons.Warn, tag = "continue_error") }
            Text("Focus on", style = MaterialTheme.typography.titleSmall, color = c.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content.requirements.filter { it.type != RequirementType.OUTPUT }.forEach { r ->
                    val st = content.coverageOf(r.id).status
                    ChoiceChip(r.label + if (st == RequirementStatus.NOT_FOUND) " · not found" else "", selected = r.id in s.continueFocus, onClick = { viewModel.toggleFocus(r.id) }, modifier = Modifier.testTag("focus_${r.id}"))
                }
            }
            OutlinedTextField(value = s.continueNote, onValueChange = viewModel::setContinueNote, placeholder = { Text("Anything to add? e.g. try the brand site directly", color = c.ink3) }, modifier = Modifier.fillMaxWidth().testTag("continue_note"), shape = RoundedCornerShape(14.dp), colors = fieldColors(), maxLines = 3)
            Text("How deep", style = MaterialTheme.typography.titleSmall, color = c.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ResearchModes.PRESETS.forEach { p -> ChoiceChip("${p.mode.label} · ${p.searches}", selected = s.continueMode == p.mode, onClick = { viewModel.setContinueMode(p.mode) }, modifier = Modifier.testTag("continue_mode_${p.mode.name.lowercase()}")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(AutopilotIcons.Minus, contentDescription = "Fewer searches", onClick = { viewModel.setContinueSearches(cfg.searchBudget - 1) })
                Text("${cfg.searchBudget} searches max · ${AiConfig.label(cfg.model)}", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.testTag("continue_searches"))
                RoundIconButton(AutopilotIcons.Plus, contentDescription = "More searches", onClick = { viewModel.setContinueSearches(cfg.searchBudget + 1) })
            }
            if (cfg.spendLimitCents != null) Text("Stops near ${UsdEstimate.format(cfg.spendLimitCents / 100.0)} (your last limit). Change it on the research screen.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
            if (cfg.mode == ResearchMode.DEEP) Text("Deep adds a cross-check and a final write-up call.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
        }
    }
}
