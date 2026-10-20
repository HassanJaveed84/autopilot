package com.lifeadmin.autopilot.ui.research

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
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchOutcome
import com.lifeadmin.autopilot.ai.research.ResearchProgress
import com.lifeadmin.autopilot.ai.research.ResearchRunState
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.DraftPill
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * Researching: honest stages driven by the real request (Sent → Searching → Writing; no streaming,
 * so Searching is indeterminate and no counts are invented), a Stop that aborts the HTTP call, and
 * the result states when the run fails. A finished run hands over to the brief.
 */
@Composable
fun ResearchProgressScreen(
    viewModel: ResearchRunViewModel,
    now: () -> Instant,
    onBack: () -> Unit,
    onOpenBrief: (briefId: String) -> Unit,
    onTryAgain: (question: String) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val run by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    val reduced = rememberReducedMotion()
    val mine = viewModel.matches(run)
    val running = run as? ResearchRunState.Running
    val finished = (run as? ResearchRunState.Finished)?.takeIf { mine }

    LaunchedEffect(finished) {
        val f = finished ?: return@LaunchedEffect
        if (f.outcome is ResearchOutcome.Saved) {
            viewModel.dismiss()
            onOpenBrief(f.outcome.briefId)
        }
    }

    // Elapsed time counts from the run's own start, so returning to this screen keeps the clock honest.
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running?.runId) {
        val startedAt = running?.startedAt ?: return@LaunchedEffect
        while (true) {
            elapsedMs = Duration.between(startedAt, now()).toMillis().coerceAtLeast(0)
            delay(1_000)
        }
    }

    Scaffold(
        containerColor = c.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AutopilotIcons.Back, contentDescription = "Back to task", onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (running != null && mine) DraftPill("Researching")
            }
        },
        bottomBar = {
            if (running != null && mine) {
                Surface(color = c.surface, contentColor = c.ink) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        HorizontalDivider(color = c.line)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            SecondaryButton("Stop research", onClick = viewModel::stop, icon = AutopilotIcons.Stop, modifier = Modifier.fillMaxWidth().testTag("research_stop"))
                            Text(
                                "Searches that already ran may be billed. Nothing changes in your task. Leaving the app does not stop a request already sent.",
                                style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp).testTag("research_progress")) {
            when {
                running != null && mine -> RunningContent(running, elapsedMs, reduced)
                finished != null && finished.outcome is ResearchOutcome.Failed -> {
                    val error = finished.outcome.error
                    ResearchResultState(
                        error, finished.question,
                        primary = {
                            when (error) {
                                ResearchError.Disabled, ResearchError.NoApiKey, ResearchError.NoConsent, ResearchError.Unauthorized, ResearchError.WebSearchNotEnabled, ResearchError.OutOfCredit, ResearchError.Forbidden ->
                                    PrimaryButton("Open AI settings", onClick = onOpenAiSettings, modifier = Modifier.fillMaxWidth().testTag("result_settings"))
                                else -> PrimaryButton(if (error.retryable) "Try again" else "Edit question", onClick = { viewModel.dismiss(); onTryAgain(finished.question) }, modifier = Modifier.fillMaxWidth().testTag("result_retry"))
                            }
                        },
                        secondary = { SecondaryButton("Back to task", onClick = { viewModel.dismiss(); onBack() }, modifier = Modifier.fillMaxWidth().testTag("result_back"), minHeight = 48.dp) },
                        fixes = fixesFor(error),
                    )
                }
                finished != null -> Box(Modifier.fillMaxWidth().height(200.dp)) // handing over to the brief
                else -> ResearchResultState(
                    ResearchError.Interrupted, viewModel.question,
                    primary = { PrimaryButton("Research again", onClick = { onTryAgain(viewModel.question) }, modifier = Modifier.fillMaxWidth().testTag("result_retry")) },
                    secondary = { SecondaryButton("Back to task", onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("result_back"), minHeight = 48.dp) },
                )
            }
        }
    }
}

private fun fixesFor(e: ResearchError): List<String> = when (e) {
    ResearchError.WebSearchNotEnabled -> listOf("Open the Anthropic Console for your organization", "Enable the web search tool for your key", "Come back and start the research again")
    ResearchError.OutOfCredit -> listOf("Add credit in the Anthropic Console", "Start the research again — your question is kept")
    is ResearchError.RateLimited -> listOf("Wait a moment", "Tap Try again — nothing was retried for you")
    else -> emptyList()
}

@Composable
private fun RunningContent(run: ResearchRunState.Running, elapsedMs: Long, reduced: Boolean) {
    val c = AutopilotTheme.colors
    val stage = when (run.progress) { ResearchProgress.Sent -> 0; is ResearchProgress.Searching, ResearchProgress.Continuing -> 1; ResearchProgress.Writing -> 2 }
    val stageText = when (run.progress) {
        ResearchProgress.Sent -> "Sent to Anthropic"
        is ResearchProgress.Searching -> "Searching the web · ${run.progress.count} of up to ${ResearchConfig.MAX_SEARCHES_PER_REQUEST} searches"
        ResearchProgress.Continuing -> "Continuing · more searches within the same budget"
        ResearchProgress.Writing -> "Writing the brief"
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Researching…", style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.semantics { heading() })
        Text("“${run.question}”", style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.testTag("progress_question"))
        SurfaceCard(radius = 20.dp, contentPadding = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Counter("Searches", if (run.searchesSeen > 0) "${run.searchesSeen}/${ResearchConfig.MAX_SEARCHES_PER_REQUEST}" else "—/${ResearchConfig.MAX_SEARCHES_PER_REQUEST}")
                Counter("Sources", "—")
                Counter("Time", "%d:%02d".format(elapsedMs / 60_000, (elapsedMs / 1_000) % 60))
            }
            Text("Counts fill in when the answer arrives; the app doesn't guess them.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 10.dp))
        }
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = stageText }.testTag("progress_stages"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Sent", "Searching the web", "Writing the brief").forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (i <= stage) c.ink else c.line))
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = if (i <= stage) c.ink else c.ink3, modifier = Modifier.weight(1f))
                    if (i == stage) TimeText(if (i == 2) "writing" else "working", color = c.ink2, size = 12)
                    else if (i < stage) androidx.compose.material3.Icon(AutopilotIcons.Check, contentDescription = "done", tint = c.ink2, modifier = Modifier.size(16.dp))
                }
            }
            if (!reduced) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).clip(CircleShape), color = c.ink, trackColor = c.sunken)
            Text(stageText, style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.testTag("progress_stage_text"))
        }
        Text(
            "Up to ${ResearchConfig.MAX_SEARCHES_PER_REQUEST} searches on this request. Web pages are read as information only — instructions on pages are never followed.",
            style = MaterialTheme.typography.bodySmall, color = c.ink3,
        )
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
