package com.lifeadmin.autopilot.ui.investigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeadmin.autopilot.ai.investigation.AnswerSegment
import com.lifeadmin.autopilot.ai.investigation.Investigation
import com.lifeadmin.autopilot.ai.investigation.SectionKind
import com.lifeadmin.autopilot.ai.investigation.Turn
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.ai.research.Sources
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.research.BriefSectionTitle
import com.lifeadmin.autopilot.ui.research.ResearchBanner
import com.lifeadmin.autopilot.ui.research.ResearchStamp
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import java.time.Instant
import java.time.ZoneId

/**
 * Research saved by the retired M4 engine, shown read-only exactly as it was stored: its composed
 * answer text, caveats and sections, the sources it cited and what it cost. Nothing is re-derived or
 * re-labelled with the new engine's model, and nothing here can be sent again.
 */
@Composable
internal fun LegacyThread(inv: Investigation, s: ThreadUi, zone: ZoneId, onOpenLegacy: () -> Unit, onReview: (turnId: String) -> Unit) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 8.dp).testTag("legacy_thread"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ResearchBanner(
            "Saved with the earlier research engine",
            "Shown as it was saved, read-only. Status lines are that engine's own verdicts. To ask more, start a new research.",
            icon = AutopilotIcons.Info, tag = "legacy_banner",
        )
        inv.turns.sortedBy { it.index }.forEach { t -> LegacyTurn(t, inv, s, zone, onOpenLegacy, onReview) }
    }
}

@Composable
private fun LegacyTurn(t: Turn, inv: Investigation, s: ThreadUi, zone: ZoneId, onOpenLegacy: () -> Unit, onReview: (String) -> Unit) {
    val c = AutopilotTheme.colors
    Column(Modifier.padding(top = 12.dp).testTag("legacy_turn_${t.index}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (t.index > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(bottom = 8.dp))
        ResearchStamp("Asked · " + Format.relative(Instant.ofEpochMilli(t.createdAtMs), s.now, zone))
        Text("“${t.objective.question}”", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
        t.legacy?.let { l ->
            Text("Saved with the M3A.2 research format.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            if (l.sessionContentJson.isNotBlank() && inv.taskId != null) LinkButton("Open the saved result", onClick = onOpenLegacy, modifier = Modifier.testTag("legacy_open_${t.index}"))
            return@Column
        }
        when (t.state) {
            TurnState.PREPARING, TurnState.CLARIFYING, TurnState.AUTHORIZING, TurnState.DECLINED -> { Text("Not started. Nothing was sent.", style = MaterialTheme.typography.bodyMedium, color = c.ink3); return@Column }
            TurnState.STOPPED, TurnState.FAILED, TurnState.INTERRUPTED, TurnState.RUNNING ->
                ResearchBanner(if (t.state == TurnState.STOPPED) "Stopped" else if (t.state == TurnState.FAILED) "Didn't finish" else "Interrupted", t.endingNote, warn = true, icon = AutopilotIcons.Warn, tag = "legacy_ending_${t.index}")
            TurnState.DONE -> Unit
        }
        val r = t.result ?: return@Column
        val a = r.answer
        SurfaceCard(radius = 18.dp, modifier = Modifier.testTag("legacy_answer_${t.index}")) {
            Text(a.title, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
            if (a.stateLine.isNotBlank()) Text("Recorded status: ${a.stateLine}", style = MaterialTheme.typography.labelLarge, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
            Text(text(a.lead), style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 8.dp))
            a.caveats.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 4.dp)) }
        }
        a.sections.forEach { sec ->
            BriefSectionTitle(sec.kind.title)
            if (sec.kind == SectionKind.COMPARISON) sec.rows.forEach { row ->
                Text(row.criterion, style = MaterialTheme.typography.titleSmall, color = c.ink)
                row.cells.forEach { (opt, cell) -> Text("$opt: ${cell.text}", style = MaterialTheme.typography.bodyMedium, color = c.ink) }
            } else sec.items.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium, color = c.ink) }
        }
        a.recommendation?.let { rec ->
            SurfaceCard(radius = 18.dp, background = c.sunken) {
                Text("RECOMMENDATION · JUDGMENT", style = MaterialTheme.typography.labelSmall, color = c.ink2)
                Text(rec.text, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 4.dp))
                rec.assumptions.forEach { Text("Assumes: $it", style = MaterialTheme.typography.bodySmall, color = c.ink2) }
                rec.flipConditions.forEach { Text("Changes if: $it", style = MaterialTheme.typography.bodySmall, color = c.ink2) }
            }
        }
        val cited = r.sources.filter { it.citedCount > 0 }
        if (cited.isNotEmpty()) {
            BriefSectionTitle("Sources", count = cited.size)
            SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(14.dp)) {
                cited.forEach { src ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("[${src.n}]", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                        Column(Modifier.weight(1f)) {
                            Text(src.title, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                            Text(Sources.host(src.url) ?: src.url, style = MaterialTheme.typography.bodySmall, color = c.ink2)
                        }
                    }
                }
            }
        }
        Text(
            "Used: ${t.usage.webSearches} ${if (t.usage.webSearches == 1) "search" else "searches"} · " + (if (t.usage.floor) "at least " else "~") + UsdEstimate.format(t.usage.spentMicros / 1e6),
            style = MaterialTheme.typography.bodySmall, color = c.ink2,
        )
        if (inv.taskId != null && r.proposals.isNotEmpty()) LinkButton("See the task changes this proposed", onClick = { onReview(t.id) }, modifier = Modifier.testTag("legacy_review_${t.index}"))
    }
}

private fun text(segments: List<AnswerSegment>): String = segments.joinToString(" ") { it.text.trim() }.trim()
