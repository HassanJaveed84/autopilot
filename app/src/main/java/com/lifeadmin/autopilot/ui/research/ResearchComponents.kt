package com.lifeadmin.autopilot.ui.research

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.ai.research.Finding
import com.lifeadmin.autopilot.ai.research.MoveKind
import com.lifeadmin.autopilot.ai.research.NextMove
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.Segment
import com.lifeadmin.autopilot.ai.research.SourceRef
import com.lifeadmin.autopilot.ai.research.SourceTier
import com.lifeadmin.autopilot.ai.research.Sources
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.IconCircle
import com.lifeadmin.autopilot.ui.components.LinkButton
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.TimeTextStyle

/*
 * The M3 vocabulary of edges, spelled out in words as well: solid = backed by a source, dotted =
 * unresolved or skipped, dashed indigo = Claude's proposal. No new colors.
 */

/** Mono uppercase "dossier stamp" line: RESEARCHED 20 OCT · 2 SEARCHES · 3 SOURCES. */
@Composable
fun ResearchStamp(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val c = AutopilotTheme.colors
    Text(
        text.uppercase(),
        style = TimeTextStyle.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.24.sp, fontWeight = FontWeight.Medium),
        color = if (color == Color.Unspecified) c.ink2 else color,
        modifier = modifier,
    )
}

/** Dotted edge for unresolved or skipped things. */
fun Modifier.dottedBorder(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    val stroke = Stroke(width = width.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
    drawRoundRect(color = color, cornerRadius = CornerRadius(radius.toPx()), style = stroke)
}

/** Numbered citation chip. Inline chips are small; the Sources list gives the same link a 64dp row. */
@Composable
fun CitationChip(n: Int, onClick: () -> Unit, modifier: Modifier = Modifier, title: String? = null, standalone: Boolean = false) {
    val c = AutopilotTheme.colors
    val size = if (standalone) 32.dp else 22.dp
    Box(
        modifier
            .defaultMinSize(minWidth = if (standalone) 48.dp else size, minHeight = if (standalone) 48.dp else size)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Source $n" + (title?.let { ", $it" } ?: "") },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.defaultMinSize(minWidth = size, minHeight = size).clip(RoundedCornerShape(if (standalone) 8.dp else 6.dp)).background(c.sunken).border(1.dp, c.lineStrong, RoundedCornerShape(if (standalone) 8.dp else 6.dp)).padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(n.toString(), style = TimeTextStyle.copy(fontSize = if (standalone) 14.sp else 12.sp, lineHeight = 14.sp), color = c.ink, maxLines = 1, softWrap = false)
        }
    }
}

/** Answer text with its citation chips inline; placeholders are in sp so chips scale with text. */
@Composable
fun CitedText(segments: List<Segment>, sources: List<SourceRef>, onCite: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    val refs = segments.flatMap { it.refs }.distinct()
    val text: AnnotatedString = buildAnnotatedString {
        segments.forEach { seg ->
            append(seg.text)
            seg.refs.forEach { n -> append(" "); appendInlineContent("cite-$n", "[source $n]") }
        }
    }
    val inline = refs.associate { n ->
        "cite-$n" to InlineTextContent(Placeholder(26.sp, 20.sp, PlaceholderVerticalAlign.TextCenter)) {
            CitationChip(n, onClick = { onCite(n) }, title = sources.firstOrNull { it.n == n }?.title)
        }
    }
    Text(text, inlineContent = inline, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp), color = c.ink, modifier = modifier)
}

/** Evidence meter: bars for Official (3) or Web page (1, no claim), with the words next to it. */
@Composable
fun EvidenceBadge(tier: SourceTier, modifier: Modifier = Modifier, label: Boolean = true) {
    val c = AutopilotTheme.colors
    val bars = if (tier == SourceTier.OFFICIAL) 3 else 1
    val text = if (tier == SourceTier.OFFICIAL) "Official" else "Web page"
    Row(modifier.semantics { contentDescription = "Source type: $text" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 1..3) Box(Modifier.width(4.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i <= bars) c.ink else c.line))
        }
        if (label) Text(text, style = MaterialTheme.typography.labelSmall, color = c.ink2)
    }
}

/** Kind label + edge + words for each provenance type. */
@Composable
fun FindingCard(finding: Finding, sources: List<SourceRef>, onCite: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val (label, edge, description) = when (finding) {
        is Finding.Sourced -> Triple("Sourced", Modifier.border(1.dp, c.lineStrong, shape), "Sourced, " + (if (finding.tier == SourceTier.OFFICIAL) "official" else "web page") + ". ")
        is Finding.Disagree -> Triple("Sources disagree", Modifier.border(1.dp, c.lineStrong, shape), "Sources disagree: ")
        is Finding.Unconfirmed -> Triple("Couldn’t confirm", Modifier.dottedBorder(c.lineStrong, 18.dp), "Couldn’t confirm: ")
        is Finding.Suggestion -> Triple("Claude’s suggestion", Modifier.border(1.dp, c.route, shape).let { it }, "Claude’s suggestion, not from a source: ")
    }
    val dashed = finding is Finding.Suggestion
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface)
            .let { if (dashed) it.drawBehind { drawRoundRect(c.route, cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))) } else it.then(edge) }
            .padding(14.dp)
            .semantics(mergeDescendants = true) { contentDescription = description + findingText(finding) }
            .testTag("finding_${label.lowercase().replace(Regex("[^a-z]+"), "_")}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (dashed) c.route else c.ink2, modifier = Modifier.weight(1f))
            if (finding is Finding.Sourced) EvidenceBadge(finding.tier)
        }
        when (finding) {
            is Finding.Sourced -> Row(verticalAlignment = Alignment.Top) {
                Text(finding.text, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
                finding.refs.forEach { n -> CitationChip(n, onClick = { onCite(n) }, modifier = Modifier.padding(start = 4.dp), title = sources.firstOrNull { it.n == n }?.title) }
            }
            is Finding.Disagree -> {
                Text(finding.topic, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                finding.sides.forEach { side ->
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        CitationChip(side.ref, onClick = { onCite(side.ref) }, title = sources.firstOrNull { it.n == side.ref }?.title)
                        Column(Modifier.weight(1f)) {
                            Text(side.says, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                            sources.firstOrNull { it.n == side.ref }?.let { Text(it.domain + " · " + (if (it.tier == SourceTier.OFFICIAL) "official" else "web page"), style = MaterialTheme.typography.labelSmall, color = c.ink3) }
                        }
                    }
                }
                Text(finding.advice, style = MaterialTheme.typography.bodySmall, color = c.ink2)
            }
            is Finding.Unconfirmed -> {
                Text(finding.topic, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Text(finding.note, style = MaterialTheme.typography.bodySmall, color = c.ink2)
            }
            is Finding.Suggestion -> {
                Text(finding.text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Text("Not from a source. Check it before you rely on it.", style = MaterialTheme.typography.bodySmall, color = c.route)
            }
        }
    }
}

private fun findingText(f: Finding): String = when (f) {
    is Finding.Sourced -> f.text + f.refs.joinToString("") { " Source $it." }
    is Finding.Disagree -> f.topic + ". " + f.sides.joinToString(" ") { "Source ${it.ref} says ${it.says}." }
    is Finding.Unconfirmed -> f.topic + ". " + f.note
    is Finding.Suggestion -> f.text
}

/** Selectable next-move row with kind tag, text, basis line and source numbers. */
@Composable
fun MoveRow(
    move: NextMove,
    sources: List<SourceRef>,
    checked: Boolean,
    onToggle: ((Boolean) -> Unit)?,
    onCite: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** "Already in your steps" etc.; the row is shown dotted and cannot be selected. */
    skippedReason: String? = null,
    applied: Boolean = false,
) {
    val c = AutopilotTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val sourced = move.refs.isNotEmpty()
    val selectable = onToggle != null && skippedReason == null && !applied
    val kind = when (move.kind) { MoveKind.STEP -> "Add step"; MoveKind.CHECK -> "Check"; MoveKind.NOTE -> "Add note" }
    val state = when {
        applied -> "Already applied"
        skippedReason != null -> "$skippedReason, will be skipped"
        checked -> "Selected"
        else -> "Not selected"
    }
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (checked && selectable) c.sunken else c.surface)
            .let {
                when {
                    skippedReason != null || applied -> it.dottedBorder(c.lineStrong, 18.dp)
                    sourced -> it.border(1.dp, if (checked) c.lineStrong else c.line, shape)
                    else -> it.drawBehind { drawRoundRect(c.route, cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))) }
                }
            }
            .let { if (selectable) it.toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle!!) else it }
            .semantics { stateDescription = state }
            .padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)
            .testTag("move_${move.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onToggle != null) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (checked && selectable) c.inverse else Color.Transparent)
                    .border(2.dp, if (!selectable) c.line else if (checked) c.inverse else c.lineStrong, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) { if (checked && selectable) Icon(AutopilotIcons.Check, contentDescription = null, tint = c.onInverse, modifier = Modifier.size(14.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (sourced || move.kind == MoveKind.CHECK) c.ink2 else c.route)
            Text(move.text, style = MaterialTheme.typography.bodyLarge, color = if (selectable || onToggle == null) c.ink else c.ink2)
            Text(
                if (applied) "Already added to your task" else skippedReason ?: move.basis,
                style = MaterialTheme.typography.bodySmall, color = if (!sourced && skippedReason == null && !applied) c.route else c.ink2,
            )
        }
        if (move.refs.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { move.refs.forEach { n -> CitationChip(n, onClick = { onCite(n) }, title = sources.firstOrNull { it.n == n }?.title) } }
        }
    }
}

/** Legend row for the four provenance types (words, not just lines). */
@Composable
fun ProvenanceLegend(modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendItem("Sourced") { Box(Modifier.size(14.dp).border(1.5.dp, c.lineStrong, RoundedCornerShape(4.dp))) }
        LegendItem("Sources disagree") { Box(Modifier.size(14.dp).border(1.5.dp, c.lineStrong, RoundedCornerShape(4.dp))) { Box(Modifier.width(7.dp).height(14.dp).background(c.lineStrong)) } }
        LegendItem("Couldn’t confirm") { Box(Modifier.size(14.dp).dottedBorder(c.lineStrong, 4.dp)) }
        LegendItem("Claude’s suggestion") { Box(Modifier.size(14.dp).drawBehind { drawRoundRect(c.route, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))) }) }
    }
}

@Composable
private fun LegendItem(text: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatch()
        Text(text, style = MaterialTheme.typography.labelSmall, color = AutopilotTheme.colors.ink2)
    }
}

/** Soft banner (stale, offline, unverified, truncated, injection). */
@Composable
fun ResearchBanner(title: String, body: String?, modifier: Modifier = Modifier, warn: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector = AutopilotIcons.Info, action: (@Composable () -> Unit)? = null, tag: String = "research_banner") {
    val c = AutopilotTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (warn) c.signalSoft else c.sunken).padding(14.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = if (warn) c.onSignalSoft else c.ink2, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (warn) c.onSignalSoft else c.ink)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = if (warn) c.onSignalSoft else c.ink2)
            action?.invoke()
        }
    }
}

/** Source list row: number, title, domain, type, how often cited. 64dp target. */
@Composable
fun SourceRow(source: SourceRef, onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = false) {
    val c = AutopilotTheme.colors
    if (divider) androidx.compose.material3.HorizontalDivider(color = c.line)
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp).testTag("source_${source.n}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CitationChip(source.n, onClick = onClick, standalone = true, title = source.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(source.title, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(source.domain, style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                EvidenceBadge(source.tier)
            }
            Text(
                if (source.citedCount == 0) "Read while searching · not cited" else "Cited ${source.citedCount} ${if (source.citedCount == 1) "time" else "times"}",
                style = MaterialTheme.typography.labelSmall, color = c.ink3,
            )
        }
        Icon(AutopilotIcons.ChevronRight, contentDescription = null, tint = c.ink3)
    }
}

/** Inspect a citation before trusting or opening it. The link opens in the browser, never a WebView. */
@Composable
fun SourceSheet(source: SourceRef, usedFor: List<String>, researchedLine: String, onDismiss: () -> Unit, online: Boolean?) {
    val c = AutopilotTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val safe = Sources.safeExternalUrl(source.url)
    AutopilotSheet(
        title = null, onDismiss = onDismiss,
        titleLeading = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CitationChip(source.n, onClick = {}, standalone = true, title = source.title)
                EvidenceBadge(source.tier)
            }
        },
        footer = {
            PrimaryButton(
                if (online == false) "Open in browser (offline)" else "Open in browser",
                onClick = {
                    val uri = safe?.let { androidx.core.net.toUri(it) } ?: return@PrimaryButton
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                },
                enabled = safe != null && online != false, icon = AutopilotIcons.OpenExternal,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Open in browser, opens in your browser" }.testTag("source_open"),
            )
            Text(
                "Opens outside the app in your browser. Check the address before you sign in or pay anywhere.",
                style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("source_sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(source.title, style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(source.domain, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            Text(
                if (source.tier == SourceTier.OFFICIAL) "Official: a government domain, or the site of the operator or service you named."
                else "Web page: the app can't confirm who runs this site. Treat it as a lead, not an authority.",
                style = MaterialTheme.typography.bodySmall, color = c.ink2,
            )
            if (source.quotes.isNotEmpty()) {
                Text("QUOTED FROM THE PAGE", style = MaterialTheme.typography.labelSmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                source.quotes.forEach { q -> Text("“$q”", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(start = 12.dp)) }
            }
            if (usedFor.isNotEmpty()) {
                Text("USED IN THIS BRIEF FOR", style = MaterialTheme.typography.labelSmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                usedFor.take(4).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = c.ink) }
            }
            ResearchStamp(buildString { append(researchedLine); source.pageAge?.let { append(" · page dated $it") } }, Modifier.padding(top = 6.dp))
            Text(source.url, style = TimeTextStyle.copy(fontSize = 12.sp, lineHeight = 16.sp), color = c.ink2, modifier = Modifier.testTag("source_url"))
            LinkButton("Copy link", onClick = { clipboard.setText(AnnotatedString(source.url)) }, modifier = Modifier.testTag("source_copy"))
            if (safe == null) Text("This address isn't a normal web link, so the app won't open it.", style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
    }
}

/** One-time terms for web research, separate from AI planning consent. */
@Composable
fun ExplainerSheet(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val c = AutopilotTheme.colors
    AutopilotSheet(
        title = "Before your first web research", onDismiss = onDismiss, closeLabel = "Not now",
        footer = {
            PrimaryButton("I understand, start", onClick = onAccept, modifier = Modifier.fillMaxWidth().testTag("explainer_accept"))
            SecondaryButton("Not now", onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("explainer_dismiss"), minHeight = 48.dp)
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("explainer_sheet"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Your AI planning consent doesn’t cover this, so here’s what’s different. You’ll only see this once.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            ExplainerPoint(AutopilotIcons.Play, "You start every request.", "Nothing runs in the background, on a schedule or from a reminder.")
            ExplainerPoint(AutopilotIcons.Key, "It costs more than planning.", "Anthropic bills each web search on top of tokens. The app caps it at ${ResearchConfig.MAX_SEARCHES_PER_REQUEST} searches per request and ${ResearchConfig.MAX_REQUESTS_PER_DAY} requests a day.")
            ExplainerPoint(AutopilotIcons.Warn, "Web pages can be wrong or out of date.", "Every claim shows its source; anything without one is labelled as Claude’s suggestion.")
            ExplainerPoint(AutopilotIcons.Shield, "It only reads.", "No bookings, purchases, messages or forms — and your task changes only when you apply.")
            Text(
                "Your API key is sent to Anthropic to authenticate each request, like planning. It is never placed in the question, search queries, sources, backups or logs.",
                style = MaterialTheme.typography.bodySmall, color = c.ink2,
            )
        }
    }
}

@Composable
private fun ExplainerPoint(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    val c = AutopilotTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        IconCircle(icon, tint = c.ink, background = c.sunken, size = 36.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text(body, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2)
        }
    }
}

/**
 * Result/error state: icon, headline, explanation, the kept question, and the mono technical line
 * that always says whether anything may have been billed.
 */
@Composable
fun ResearchResultState(
    error: ResearchError,
    question: String?,
    modifier: Modifier = Modifier,
    primary: (@Composable () -> Unit)? = null,
    secondary: (@Composable () -> Unit)? = null,
    fixes: List<String> = emptyList(),
) {
    val c = AutopilotTheme.colors
    Column(modifier.fillMaxWidth().testTag("research_result"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconCircle(
            when (error) { ResearchError.Offline -> AutopilotIcons.WifiOff; is ResearchError.Stopped -> AutopilotIcons.Stop; ResearchError.Disabled, ResearchError.NoApiKey, ResearchError.NoConsent, ResearchError.NoResearchConsent -> AutopilotIcons.Lock; else -> AutopilotIcons.Warn },
            tint = c.ink, background = c.sunken, size = 48.dp,
        )
        Text(error.headline, style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() }.testTag("result_headline"))
        Text(error.message, style = MaterialTheme.typography.bodyLarge, color = c.ink2)
        if (fixes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fixes.forEachIndexed { i, f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(c.sunken), contentAlignment = Alignment.Center) { Text("${i + 1}", style = TimeTextStyle.copy(fontSize = 12.sp), color = c.ink) }
                        Text(f, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
            }
        }
        if (!question.isNullOrBlank()) {
            SurfaceCard(radius = 16.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Text("YOUR QUESTION — KEPT", style = MaterialTheme.typography.labelSmall, color = c.ink2)
                Text("“$question”", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 4.dp).testTag("result_question"))
            }
        }
        ResearchStamp(error.technical, Modifier.testTag("result_technical"))
        if (primary != null || secondary != null) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                primary?.invoke()
                secondary?.invoke()
            }
        }
    }
}

/** Section heading inside the brief; announced as a heading. */
@Composable
fun BriefSectionTitle(text: String, modifier: Modifier = Modifier, count: Int? = null) {
    val c = AutopilotTheme.colors
    Row(modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
        if (count != null) ResearchStamp(count.toString())
    }
}

@Composable
fun ColumnScope.ResearchNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AutopilotTheme.colors.ink2, modifier = Modifier.padding(top = 8.dp))
}

