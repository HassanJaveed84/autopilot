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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.ModelNames
import com.lifeadmin.autopilot.ai.aifirst.AfAuthorization
import com.lifeadmin.autopilot.ai.aifirst.AfConfig
import com.lifeadmin.autopilot.ai.aifirst.AfSource
import com.lifeadmin.autopilot.ai.aifirst.AfStatus
import com.lifeadmin.autopilot.ai.aifirst.AfTurn
import com.lifeadmin.autopilot.ai.research.Sources
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.data.repository.AfThreadRepository
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.research.BriefSectionTitle
import com.lifeadmin.autopilot.ui.research.CitationChip
import com.lifeadmin.autopilot.ui.research.ResearchBanner
import com.lifeadmin.autopilot.ui.research.ResearchStamp
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.TimeTextStyle
import java.time.Instant
import java.time.ZoneId

/**
 * One saved research thread, read-only. Built-in research is retired: the answers are Claude's as
 * they were saved, with the provider's citations; sources, searches, cost and exactly what was sent
 * stay one tap away. Nothing here sends anything. Research from the earlier engine opens as it was.
 */
@Composable
fun InvestigationThreadScreen(
    viewModel: InvestigationThreadViewModel,
    zone: ZoneId,
    onBack: () -> Unit,
    onReview: (taskId: String, setId: String) -> Unit,
    onOpenTask: (taskId: String) -> Unit,
    onOpenLegacy: (investigationId: String) -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }
    val thread = s.thread
    val exists = thread != null || s.legacy != null

    Scaffold(
        containerColor = c.paper, contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(AutopilotIcons.Back, contentDescription = "Back", onClick = onBack)
                    Text(thread?.title ?: s.legacy?.title ?: "Saved research", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), color = c.ink, maxLines = 2, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }.testTag("thread_title"))
                }
                FlowRow(Modifier.padding(start = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.taskId != null && s.taskTitle != null) LinkButton("For: ${s.taskTitle}", onClick = { onOpenTask(s.taskId!!) }, modifier = Modifier.testTag("thread_task"))
                    else if (exists) Text("Not linked to a task", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 12.dp).testTag("thread_standalone"))
                    if (exists) LinkButton("Delete", onClick = { confirmDelete = true }, modifier = Modifier.testTag("thread_delete"))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("thread_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            if (exists) item { ResearchBanner("Saved research · read-only", "Built-in research is retired; this is kept as it was saved. To think the task through now, open it and tap Discuss in ChatGPT.", tag = "thread_readonly") }
            else if (!s.loading) item { Text("This research no longer exists.", style = MaterialTheme.typography.bodyLarge, color = c.ink2, modifier = Modifier.padding(top = 16.dp).testTag("thread_missing")) }
            s.legacy?.let { inv ->
                item { LegacyThread(inv, s, zone, onOpenLegacy = { onOpenLegacy(inv.id) }, onReview = { turnId -> viewModel.openLegacyReview(turnId, onReview) }) }
                return@LazyColumn
            }
            items(s.shownTurns, key = { "turn_" + it.id }) { t -> TurnCard(t, s, zone, viewModel, onSuggest = { viewModel.openSuggestions(t.id, onReview) }) }
        }
    }

    s.payloadFor?.let { id ->
        val a = thread?.turns?.firstOrNull { it.id == id }?.authorization
        if (a != null) PayloadSheet(a, onDismiss = viewModel::closePayload) else LaunchedEffect(id) { viewModel.closePayload() }
    }
    s.source?.let { (turnId, n) ->
        val t = thread?.turns?.firstOrNull { it.id == turnId }
        val src = t?.sources?.firstOrNull { it.n == n }
        if (t != null && src != null) SourceSheet(src, t, s, zone, onDismiss = viewModel::closeSource) else LaunchedEffect(turnId, n) { viewModel.closeSource() }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this research?") },
            text = { Text("Removes every turn of it from this phone. Steps and notes already added to tasks stay.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }, modifier = Modifier.testTag("thread_delete_confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

internal fun estimateText(a: AfAuthorization): String =
    if (a.estimateLowMicros != null && a.estimateHighMicros != null) UsdEstimate.formatRange(a.estimateLowMicros / 1e6, a.estimateHighMicros / 1e6) else "No price table for this model"

internal fun configLine(cfg: AfConfig): String =
    "${ModelNames.label(cfg.model).removePrefix("Claude ")} · up to ${cfg.searchBudget} ${if (cfg.searchBudget == 1) "search" else "searches"} · limit ${UsdEstimate.format(cfg.spendLimitCents / 100.0)}" +
        (cfg.effort?.let { " · effort $it" } ?: "")

private fun cost(micros: Long, floor: Boolean): String = (if (floor) "at least " else "~") + UsdEstimate.format(micros / 1e6)

/** Plain text for "Copy answer": answer, unverified links, numbered sources, stop note. */
internal fun copyText(t: AfTurn): String = buildString {
    appendLine(t.answer)
    if (t.unsuppliedLinks.isNotEmpty()) appendLine("\nUnverified links (written by Claude, not supplied by a search): " + t.unsuppliedLinks.joinToString(", "))
    if (t.sources.isNotEmpty()) {
        appendLine("\nSources")
        t.sources.forEach { s -> appendLine("[${s.n}] ${s.title ?: s.url} — ${s.url}" + (s.pageAge?.let { " (search result age: $it)" } ?: "")) }
    }
    t.note?.let { appendLine("\n$it") }
}.trimEnd()

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
        if (t.status == AfStatus.DECLINED || t.status.pending) { Text(t.note?.takeIf { t.status == AfStatus.DECLINED } ?: "Not researched. Nothing was sent.", style = MaterialTheme.typography.bodyMedium, color = c.ink3, modifier = Modifier.testTag("turn_declined_${t.index}")); return@Column }
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
            // Suggestions already made for this answer still open in the review; no new ones are made.
            s.suggestions[t.id]?.let { pendingCount ->
                if (pendingCount > 0) PrimaryButton("Review $pendingCount suggested ${if (pendingCount == 1) "change" else "changes"}", onClick = onSuggest, modifier = Modifier.fillMaxWidth().testTag("turn_review_${t.index}"))
                else LinkButton("See the suggestions from this answer", onClick = onSuggest, modifier = Modifier.testTag("turn_review_${t.index}"))
            }
            LinkButton("Copy answer", onClick = { clipboard.setText(AnnotatedString(copyText(t))) }, modifier = Modifier.testTag("turn_copy_${t.index}"))
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
        if (t.costIsFloor) Text("Some usage was never reported (a stop, failure or interruption), so the real cost may be higher.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 2.dp))
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
                "Open in browser",
                onClick = {
                    @Suppress("UseKtx") // core-ktx is not a dependency of this module
                    val uri = safe?.let { android.net.Uri.parse(it) } ?: return@PrimaryButton
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                },
                enabled = safe != null, icon = AutopilotIcons.OpenExternal, modifier = Modifier.fillMaxWidth().testTag("source_open"),
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
private fun PayloadSheet(a: AfAuthorization, onDismiss: () -> Unit) {
    val c = AutopilotTheme.colors
    AutopilotSheet(title = "What was sent", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("payload_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${configLine(a.config)}. The message below was sent exactly as shown, with the app's fixed research instructions (version ${a.promptVersion}) and the web search tool limited to ${a.config.searchBudget} uses.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
            SurfaceCard(radius = 12.dp, background = c.sunken, contentPadding = PaddingValues(12.dp)) {
                Text(a.payload, style = TimeTextStyle.copy(fontSize = 11.sp, lineHeight = 15.sp), color = c.ink, modifier = Modifier.testTag("payload_text"))
            }
            ResearchStamp("SHA-256 ${a.payloadSha256.take(16)}…")
        }
    }
}
