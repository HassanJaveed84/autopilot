package com.lifeadmin.autopilot.ui.research2

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research2.DeepRunState
import com.lifeadmin.autopilot.ai.research2.RunEventKind
import com.lifeadmin.autopilot.ai.research2.RunResult
import com.lifeadmin.autopilot.ai.research2.StageKind
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.DraftPill
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.research.ResearchResultState
import com.lifeadmin.autopilot.ui.research.ResearchStamp
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * Investigating: stages and counters come only from real orchestration events (a call sent, a stage
 * finished with its queries, a stage skipped and why, a budget stop). No percentages, no invented
 * steps. Stop opens the keep / discard / keep-going sheet.
 */
@Composable
fun InvestigationScreen(
    viewModel: InvestigationViewModel,
    now: () -> Instant,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    onTryAgain: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val run = ui.run
    val c = AutopilotTheme.colors
    val reduced = rememberReducedMotion()
    val mine = viewModel.matches(run)
    val running = (run as? DeepRunState.Running)?.takeIf { mine }
    val finished = (run as? DeepRunState.Finished)?.takeIf { mine }

    LaunchedEffect(finished) {
        val f = finished ?: return@LaunchedEffect
        if (f.result is RunResult.Saved) { viewModel.dismiss(); onOpenSession(f.result.sessionId) }
    }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running?.runId) {
        val startedAt = running?.startedAt ?: return@LaunchedEffect
        while (true) { elapsedMs = Duration.between(startedAt, now()).toMillis().coerceAtLeast(0); delay(1_000) }
    }

    Scaffold(
        containerColor = c.paper, contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AutopilotIcons.Back, contentDescription = "Back to task", onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (running != null) DraftPill(if (running.continuation) "Continuing" else "Researching")
            }
        },
        bottomBar = {
            if (running != null) {
                Surface(color = c.surface, contentColor = c.ink) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        HorizontalDivider(color = c.line)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            SecondaryButton("Stop…", onClick = viewModel::openStop, icon = AutopilotIcons.Stop, modifier = Modifier.fillMaxWidth().testTag("research_stop"))
                            Text("Searches that already ran are billed. Leaving this screen keeps the request running; closing the app ends it and nothing is re-sent.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp).testTag("research_progress")) {
            when {
                running != null -> RunningContent(running, elapsedMs, reduced)
                finished != null && finished.result is RunResult.Failed -> {
                    val error = finished.result.error
                    ResearchResultState(
                        error, null,
                        primary = {
                            when (error) {
                                ResearchError.Disabled, ResearchError.NoApiKey, ResearchError.NoConsent, ResearchError.Unauthorized, ResearchError.WebSearchNotEnabled, ResearchError.OutOfCredit, ResearchError.Forbidden ->
                                    PrimaryButton("Open AI settings", onClick = onOpenAiSettings, modifier = Modifier.fillMaxWidth().testTag("result_settings"))
                                else -> PrimaryButton(if (error.retryable) "Try again" else "Edit request", onClick = { viewModel.dismiss(); onTryAgain() }, modifier = Modifier.fillMaxWidth().testTag("result_retry"))
                            }
                        },
                        secondary = { SecondaryButton("Back to task", onClick = { viewModel.dismiss(); onBack() }, modifier = Modifier.fillMaxWidth().testTag("result_back"), minHeight = 48.dp) },
                        fixes = when (error) {
                            ResearchError.WebSearchNotEnabled -> listOf("Open the Anthropic Console for your organization", "Enable the web search tool for your key", "Come back and start the research again")
                            ResearchError.OutOfCredit -> listOf("Add credit in the Anthropic Console", "Start the research again — your request is kept")
                            is ResearchError.RateLimited -> listOf("Wait a moment", "Tap Try again — nothing was retried for you")
                            else -> emptyList()
                        },
                    )
                }
                finished != null && finished.result is RunResult.Discarded -> ResearchResultState(ResearchError.Stopped(0), null, primary = { PrimaryButton("Back to task", onClick = { viewModel.dismiss(); onBack() }, modifier = Modifier.fillMaxWidth().testTag("result_back")) })
                finished != null -> Box(Modifier.fillMaxWidth().height(200.dp)) // handing over to the result
                else -> ResearchResultState(
                    ResearchError.Interrupted, null,
                    primary = { PrimaryButton("Research again", onClick = onTryAgain, modifier = Modifier.fillMaxWidth().testTag("result_retry")) },
                    secondary = { SecondaryButton("Back to task", onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("result_back"), minHeight = 48.dp) },
                )
            }
        }
    }

    if (ui.stopSheet && running != null) {
        StopSheet(
            searchesRun = running.searchesRun, spentFloor = UsdEstimate(running.spentMicros + running.searchesRun * 10_000L),
            canKeep = running.sourcesSeen > 0 || running.events.any { it.kind == RunEventKind.STAGE_DONE },
            onKeep = { viewModel.stop(keep = true) }, onDiscard = { viewModel.stop(keep = false) }, onDismiss = viewModel::closeStop,
        )
    }
}

@Composable
private fun RunningContent(run: DeepRunState.Running, elapsedMs: Long, reduced: Boolean) {
    val c = AutopilotTheme.colors
    val stages = run.config.stages
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (run.continuation) "Continuing…" else "Researching…", style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(run.title, style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.testTag("progress_title"))
        ResearchStamp(stampLine(run.config.mode, run.config.model, run.searchesRun, elapsedMs), Modifier.testTag("progress_stamp"))
        SurfaceCard(radius = 20.dp, contentPadding = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Counter("Searches", "${run.searchesRun}/${run.config.searchBudget}")
                Counter("Sources", if (run.sourcesSeen > 0) "${run.sourcesSeen}" else "—")
                Counter("Time", "%d:%02d".format(elapsedMs / 60_000, (elapsedMs / 1_000) % 60))
            }
            Text(
                "So far: ${if (run.spentIsFloor) "≥ " else "~"}${UsdEstimate.format((run.spentMicros + run.searchesRun * 10_000L) / 1_000_000.0)} estimated for finished calls and searches; the call in flight is counted when it returns.",
                style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 10.dp).testTag("progress_spent"),
            )
        }
        val current = run.stage
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = run.events.lastOrNull()?.text ?: "" }.testTag("progress_stages"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stages.forEachIndexed { i, st ->
                val done = i < run.stageIndex || run.events.any { it.kind == RunEventKind.STAGE_DONE && it.text.startsWith(st.label) }
                val skipped = run.events.any { (it.kind == RunEventKind.STAGE_SKIPPED || it.kind == RunEventKind.BUDGET) && it.text.startsWith(st.label) }
                val active = st == current && !done && !skipped
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (done || active) c.ink else c.line))
                    Text(st.label, style = MaterialTheme.typography.bodyLarge, color = if (done || active) c.ink else c.ink3, modifier = Modifier.weight(1f))
                    when {
                        skipped -> TimeText("skipped", color = c.ink3, size = 12)
                        active -> TimeText("working", color = c.ink2, size = 12)
                        done -> Icon(AutopilotIcons.Check, contentDescription = "done", tint = c.ink2, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (!reduced) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).clip(CircleShape), color = c.ink, trackColor = c.sunken)
        }
        SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(14.dp), modifier = Modifier.testTag("progress_events")) {
            Text("WHAT HAS HAPPENED", style = MaterialTheme.typography.labelSmall, color = c.ink2)
            if (run.events.isEmpty()) Text("Preparing the first request…", style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
            run.events.takeLast(8).forEach { e ->
                Text("• ${e.text}", style = MaterialTheme.typography.bodyMedium, color = if (e.kind == RunEventKind.FAILED || e.kind == RunEventKind.BUDGET) c.onSignalSoft else c.ink, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Text("Stage names describe the app's own plan; counts come from Anthropic's responses. Web pages are read as information only — instructions on pages are never followed.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
        if (StageKind.SYNTHESIZE in stages) Text("Deep mode ends with a write-up call that uses only the sources already found.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
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
