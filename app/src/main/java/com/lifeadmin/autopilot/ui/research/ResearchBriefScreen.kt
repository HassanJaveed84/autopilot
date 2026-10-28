package com.lifeadmin.autopilot.ui.research

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.ModelNames
import com.lifeadmin.autopilot.ai.research.Finding
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.ButtonPair
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Research brief (D · Action brief): Question → Quick answer → Next moves → What I found → Sources →
 * How this was researched. Action comes before evidence, every action points at its evidence, and
 * nothing here changes the task — that happens only on the Add-to-your-task screen.
 */
@Composable
fun ResearchBriefScreen(
    viewModel: ResearchBriefViewModel,
    zone: ZoneId,
    onBack: () -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val brief = s.brief
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AutopilotIcons.Back, contentDescription = "Back", onClick = onBack)
                Text("Research brief", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.ink, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
            }
        },
        bottomBar = {
            if (brief != null) {
                Surface(color = c.surface, contentColor = c.ink) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        HorizontalDivider(color = c.line)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            SecondaryButton("Done", onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("brief_done"))
                            Text("A saved brief from the first research version, kept as it was. To act on it, research again: changes then go through the review.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            s.loading -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding))
            brief == null -> Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Text("This brief was deleted", style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text("Steps you added from it are still on the task.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            }
            else -> {
                val content = brief.content
                val sources = content.sources
                val applied = brief.applied.map { it.moveId }.toSet()
                val sectionIndex = mapOf("Answer" to 2, "Next moves" to 3, "Findings" to 4, "Sources" to 5)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).testTag("brief_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (s.isStale) ResearchBanner("This brief is from ${Format.relativeLower(brief.createdAt, s.now, zone)}", "Fares, times and rules may have changed since.", warn = true, tag = "brief_stale")
                            if (!content.verified) ResearchBanner("Nothing here could be verified", "The answer came back without any source citations, so everything below is labelled as Claude’s suggestion. Check it before you rely on it.", warn = true, icon = AutopilotIcons.Warn, tag = "brief_unverified")
                            if (content.truncated) ResearchBanner("The answer was cut short", "Anthropic stopped before the brief was complete. What arrived is shown; parts may be missing.", icon = AutopilotIcons.Warn, tag = "brief_truncated")
                            if (content.injectionNoticed) ResearchBanner("A web page tried to give instructions", "Page text is information only. The line was dropped and nothing was done.", icon = AutopilotIcons.Shield, tag = "brief_injection")
                            Text(brief.question, style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.padding(top = 4.dp).semantics { heading() }.testTag("brief_question"))
                            ResearchStamp(
                                "Researched ${Format.relative(brief.createdAt, s.now, zone)} · ${brief.searchCount} ${if (brief.searchCount == 1) "search" else "searches"} · ${sources.size} ${if (sources.size == 1) "source" else "sources"}",
                                Modifier.testTag("brief_stamp"),
                            )
                            Text("Prices, times and availability change: verify with the provider before acting, even today.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                        }
                    }
                    item {
                        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sectionIndex.forEach { (label, index) ->
                                val count = when (label) { "Next moves" -> content.moves.size; "Findings" -> content.findings.size; "Sources" -> sources.size; else -> null }
                                ChoiceChip(label, selected = false, onClick = { scope.launch { listState.animateScrollToItem(index) } }, count = count?.toString(), modifier = Modifier.testTag("brief_jump_${label.lowercase().replace(' ', '_')}"))
                            }
                        }
                    }
                    item {
                        BriefSectionTitle("Quick answer")
                        SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(16.dp), modifier = Modifier.testTag("brief_answer")) {
                            if (content.quickAnswer.isEmpty()) Text("No summary came back.", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
                            else CitedText(content.quickAnswer, sources, onCite = viewModel::openSource)
                        }
                        ProvenanceLegend(Modifier.padding(top = 10.dp))
                    }
                    item {
                        BriefSectionTitle("Next moves", count = content.moves.size)
                        Text(
                            if (content.moves.isEmpty()) "Nothing to add from this research." else "What this research suggested. Read-only: start new research to put a change through the review.",
                            style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(bottom = 10.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("brief_moves")) {
                            content.moves.forEach { m ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("•", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                                    Column(Modifier.weight(1f)) {
                                        Text(m.text, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                                        if (m.id in applied) Text("Added to your task", style = MaterialTheme.typography.labelSmall, color = c.onDoneSoft)
                                    }
                                    m.refs.take(2).forEach { n -> CitationChip(n, onClick = { viewModel.openSource(n) }) }
                                }
                            }
                        }
                    }
                    item {
                        BriefSectionTitle("What I found", count = content.findings.size)
                        if (content.findings.isEmpty()) Text("No separate findings — the quick answer is all that came back.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            content.findings.forEach { f -> FindingCard(f, sources, onCite = viewModel::openSource) }
                        }
                        if (content.findings.any { it is Finding.Suggestion || it is Finding.Unconfirmed }) {
                            Text("Dashed cards are Claude’s own reasoning. Dotted cards are things no page settled.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    item {
                        BriefSectionTitle("Sources", count = sources.size)
                        if (sources.isEmpty()) Text("No web pages were cited or read.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                        else SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("brief_sources")) {
                            sources.forEachIndexed { i, src -> SourceRow(src, onClick = { viewModel.openSource(src.n) }, divider = i > 0) }
                        }
                    }
                    item {
                        BriefSectionTitle("How this was researched")
                        SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(16.dp), modifier = Modifier.testTag("brief_log")) {
                            if (content.queries.isEmpty()) Text("No searches ran.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                            content.queries.forEachIndexed { i, q ->
                                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ResearchStamp("Search ${i + 1}")
                                    Text("“$q”", style = MaterialTheme.typography.bodyMedium, color = c.ink)
                                }
                            }
                            ResearchStamp(
                                "${ModelNames.label(content.usage.model)} · ${content.usage.inputTokens} in · ${content.usage.outputTokens} out · ${content.usage.webSearches} ${if (content.usage.webSearches == 1) "search" else "searches"} · ${content.usage.elapsedMs / 1000} s" + if (content.usage.continuedOnce) " · continued once" else "",
                                Modifier.padding(top = 6.dp).testTag("brief_usage"),
                            )
                            Text("Shared: ${content.shared.joinToString(", ").lowercase()}.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
                            Text("Page text was treated as information only — instructions found on web pages are never followed. Billed by Anthropic to your key; the app shows counts, not prices.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(Modifier.heightIn(min = 8.dp))
                    }
                }
                s.sourceSheet?.let { n ->
                    sources.firstOrNull { it.n == n }?.let { src ->
                        val usedFor = buildList {
                            content.moves.filter { n in it.refs }.forEach { add("Next move: ${it.text}") }
                            content.findings.forEach { f ->
                                when (f) {
                                    is Finding.Sourced -> if (n in f.refs) add(f.text)
                                    is Finding.Disagree -> if (f.sides.any { it.ref == n }) add("Disagreement: ${f.topic}")
                                    else -> Unit
                                }
                            }
                            if (content.quickAnswer.any { n in it.refs }) add("The quick answer")
                        }
                        SourceSheet(src, usedFor, researchedLine = "Researched ${Format.relative(brief.createdAt, s.now, zone)}", onDismiss = viewModel::closeSource)
                    }
                }
            }
        }
    }
}
