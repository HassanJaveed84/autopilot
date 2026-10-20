package com.lifeadmin.autopilot.ui.research2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.research2.Callout
import com.lifeadmin.autopilot.ai.research2.CalloutKind
import com.lifeadmin.autopilot.ai.research2.Candidate
import com.lifeadmin.autopilot.ai.research2.CitedSegment
import com.lifeadmin.autopilot.ai.research2.Coverage
import com.lifeadmin.autopilot.ai.research2.ItemPick
import com.lifeadmin.autopilot.ai.research2.Price
import com.lifeadmin.autopilot.ai.research2.PriceStatus
import com.lifeadmin.autopilot.ai.research2.ProvenanceTag
import com.lifeadmin.autopilot.ai.research2.Requirement
import com.lifeadmin.autopilot.ai.research2.RequirementStatus
import com.lifeadmin.autopilot.ai.research2.RequirementType
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.ResearchMode
import com.lifeadmin.autopilot.ai.research2.ResearchModes
import com.lifeadmin.autopilot.ai.research2.RunConfig
import com.lifeadmin.autopilot.ai.research2.SessionOutcome
import com.lifeadmin.autopilot.ai.research2.SourceRef2
import com.lifeadmin.autopilot.ai.research2.StepLine
import com.lifeadmin.autopilot.ai.usage.PricingCatalog
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.PrimaryButton
import com.lifeadmin.autopilot.ui.components.RoundIconButton
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.research.CitationChip
import com.lifeadmin.autopilot.ui.research.ResearchStamp
import com.lifeadmin.autopilot.ui.research.dottedBorder
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.TimeTextStyle
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * M3A.2 components. Provenance styles follow M3: solid = sourced, dotted = unresolved, dashed indigo
 * = Claude's reasoning. Every state is also spelled out in words, never only in colour.
 */

fun Modifier.dashedRoute(color: Color, radius: Dp2 = 18.dp): Modifier = drawBehind {
    drawRoundRect(color, cornerRadius = CornerRadius(radius.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))))
}
private typealias Dp2 = androidx.compose.ui.unit.Dp

// ------------------------------------------------------------------ requirements & coverage

/** Preflight row: type chip, label, remove. The id never changes. */
@Composable
fun RequirementRow(req: Requirement, onRemove: (() -> Unit)?, modifier: Modifier = Modifier, divider: Boolean = false) {
    val c = AutopilotTheme.colors
    if (divider) HorizontalDivider(color = c.line)
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${req.type.label}: ${req.label}" + if (!req.confident) ", unsure" else "" }
            .testTag("req_${req.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TypeChip(req.type)
        Column(Modifier.weight(1f)) {
            Text(req.label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (!req.confident) Text("Unsure — edit or remove if this isn't what you meant", style = MaterialTheme.typography.labelSmall, color = c.route)
        }
        if (onRemove != null) RoundIconButton(AutopilotIcons.Close, contentDescription = "Remove ${req.label}", onClick = onRemove, modifier = Modifier.testTag("req_remove_${req.id}"))
    }
}

@Composable
private fun TypeChip(type: RequirementType) {
    val c = AutopilotTheme.colors
    val (text, bg, fg) = when (type) {
        RequirementType.ITEM -> Triple("Item", c.inverse, c.onInverse)
        RequirementType.CONSTRAINT -> Triple("Constraint", c.sunken, c.ink)
        RequirementType.OUTPUT -> Triple("Output", c.routeSoft, c.onRouteSoft)
        RequirementType.USER_ADDED -> Triple("Yours", c.sunken, c.ink)
    }
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.4.sp), color = fg, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 3.dp))
}

/** Status glyph: filled check / half circle / dotted ? / dotted − / split. Words come from [RequirementStatus.label]. */
@Composable
fun StatusGlyph(status: RequirementStatus, modifier: Modifier = Modifier, size: Dp2 = 20.dp) {
    val c = AutopilotTheme.colors
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when (status) {
            RequirementStatus.ANSWERED -> Box(Modifier.size(size).clip(CircleShape).background(c.done), contentAlignment = Alignment.Center) { Icon(AutopilotIcons.Check, null, tint = c.onDone, modifier = Modifier.size(size * 0.65f)) }
            RequirementStatus.YOUR_CALL, RequirementStatus.PARTLY -> Box(Modifier.size(size).clip(CircleShape).border(2.dp, c.signal, CircleShape)) { Box(Modifier.width(size / 2).height(size).background(c.signal)) }
            RequirementStatus.NEEDS_CHECK -> Box(Modifier.size(size).dottedBorder(c.ink2, size / 2), contentAlignment = Alignment.Center) { Text("?", style = TimeTextStyle.copy(fontSize = 12.sp), color = c.ink2) }
            RequirementStatus.NOT_FOUND, RequirementStatus.PENDING -> Box(Modifier.size(size).dottedBorder(c.ink3, size / 2), contentAlignment = Alignment.Center) { Icon(AutopilotIcons.Minus, null, tint = c.ink3, modifier = Modifier.size(size * 0.6f)) }
            RequirementStatus.RESOLVED_CONFLICT -> Box(Modifier.size(size).clip(CircleShape).background(c.done), contentAlignment = Alignment.Center) { Box(Modifier.width(2.dp).height(size).background(c.onDone)) }
        }
    }
}

/** "Vitamin D, your call: Northfield D3, choose a strength". Tap jumps to the item. */
@Composable
fun CoverageRow(req: Requirement, cov: Coverage, onClick: (() -> Unit)?, modifier: Modifier = Modifier, divider: Boolean = false) {
    val c = AutopilotTheme.colors
    if (divider) HorizontalDivider(color = c.line)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${req.label}, ${cov.status.label.lowercase()}" + (cov.summary.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") }
            .testTag("cov_${req.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusGlyph(cov.status)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(req.label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (cov.summary.isNotBlank()) Text(cov.summary, style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(cov.status.label, style = MaterialTheme.typography.labelSmall, color = when (cov.status) { RequirementStatus.ANSWERED, RequirementStatus.RESOLVED_CONFLICT -> c.onDoneSoft; RequirementStatus.NOT_FOUND, RequirementStatus.PENDING -> c.ink3; else -> c.onSignalSoft })
    }
}

// ------------------------------------------------------------------ provenance, prices, text

@Composable
fun ProvenanceTagChip(tag: ProvenanceTag, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    val shape = RoundedCornerShape(6.dp)
    val m = when (tag) {
        ProvenanceTag.FACT -> Modifier.border(1.5.dp, c.ink, shape)
        ProvenanceTag.REVIEWS, ProvenanceTag.COMMUNITY -> Modifier.border(1.dp, c.lineStrong, shape)
        ProvenanceTag.JUDGMENT -> Modifier.dashedRoute(c.route, 6.dp)
        ProvenanceTag.CALC -> Modifier.background(c.sunken, shape)
        ProvenanceTag.UNKNOWN -> Modifier.dottedBorder(c.lineStrong, 6.dp)
    }
    Text(
        tag.short, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.4.sp), color = if (tag == ProvenanceTag.JUDGMENT) c.route else c.ink2,
        modifier = modifier.clip(shape).then(m).padding(horizontal = 6.dp, vertical = 2.dp).semantics { contentDescription = tag.label },
    )
}

/** Never a price without its status. */
@Composable
fun PriceLine(price: Price?, now: Instant, zone: ZoneId, onCite: (Int) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = AutopilotTheme.colors
    if (price == null) { Text("Price not confirmed", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = modifier.dottedBorder(c.lineStrong, 8.dp).padding(horizontal = 8.dp, vertical = 3.dp)); return }
    val stale = price.retrievedAtEpochMs?.let { Duration.between(Instant.ofEpochMilli(it), now) > Research2Config.PRICE_STALE_AFTER } ?: false
    val status = when {
        price.status == PriceStatus.NOT_CONFIRMED -> "not confirmed"
        stale -> "checked ${Instant.ofEpochMilli(price.retrievedAtEpochMs!!).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))} · may be stale"
        price.status == PriceStatus.CONFLICT_RESOLVED -> "sources differed · brand price kept"
        else -> "confirmed on ${price.retailer ?: "the cited page"}" + (price.retrievedAtEpochMs?.let { " · checked ${timeOf(it, now, zone)}" } ?: "")
    }
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "${price.display()} ${price.size?.let { "for $it, " } ?: ""}$status" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(price.display(), style = TimeTextStyle.copy(fontSize = if (compact) 14.sp else 16.sp), color = if (price.status == PriceStatus.NOT_CONFIRMED) c.ink2 else c.ink,
            modifier = if (price.status == PriceStatus.NOT_CONFIRMED) Modifier.dottedBorder(c.lineStrong, 6.dp).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier)
        Column(Modifier.weight(1f, fill = false)) {
            price.size?.let { Text(it + (price.retailer?.let { r -> " · $r" } ?: ""), style = MaterialTheme.typography.labelSmall, color = c.ink2) }
            Text(status, style = MaterialTheme.typography.labelSmall, color = if (price.status == PriceStatus.NOT_CONFIRMED || stale) c.ink3 else c.onDoneSoft)
        }
        price.refs.firstOrNull()?.let { CitationChip(it, onClick = { onCite(it) }) }
    }
}

private fun timeOf(epochMs: Long, now: Instant, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    return if (at.toLocalDate() == now.atZone(zone).toLocalDate()) "today ${at.format(DateTimeFormatter.ofPattern("HH:mm"))}" else at.toLocalDate().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
}

/** Answer text with inline numbered citations (M3 component, M3A.2 segments). */
@Composable
fun CitedText2(segments: List<CitedSegment>, sources: List<SourceRef2>, onCite: (Int) -> Unit, modifier: Modifier = Modifier) {
    com.lifeadmin.autopilot.ui.research.CitedText(
        segments.map { com.lifeadmin.autopilot.ai.research.Segment(it.text, it.refs) },
        sources.map { com.lifeadmin.autopilot.ai.research.SourceRef(it.n, it.url, it.title, it.domain, if (it.official) com.lifeadmin.autopilot.ai.research.SourceTier.OFFICIAL else com.lifeadmin.autopilot.ai.research.SourceTier.WEB, it.pageAge, it.quotes, it.citedCount) },
        onCite, modifier,
    )
}

fun SourceRef2.legacy(): com.lifeadmin.autopilot.ai.research.SourceRef = com.lifeadmin.autopilot.ai.research.SourceRef(
    n, url, title, domain, if (official) com.lifeadmin.autopilot.ai.research.SourceTier.OFFICIAL else com.lifeadmin.autopilot.ai.research.SourceTier.WEB, pageAge, quotes, citedCount,
)

// ------------------------------------------------------------------ callouts & banners

@Composable
fun CalloutCard(callout: Callout, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    val (title, icon) = when (callout.kind) {
        CalloutKind.HEALTH -> "Product facts, not medical advice" to AutopilotIcons.Shield
        CalloutKind.CROSS_ITEM -> "Across your items · Claude's reasoning" to AutopilotIcons.Route
        CalloutKind.UNKNOWN_RECIPIENT -> "If you don't know their taste" to AutopilotIcons.Info
        CalloutKind.BUDGET -> "Budget" to AutopilotIcons.Info
        CalloutKind.INFO -> "Worth knowing" to AutopilotIcons.Info
    }
    val insight = callout.kind == CalloutKind.CROSS_ITEM || callout.kind == CalloutKind.UNKNOWN_RECIPIENT
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (insight) c.surface else c.sunken).let { if (insight) it.dashedRoute(c.route, 16.dp) else it }.padding(14.dp).testTag("callout_${callout.kind.name.lowercase()}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = if (insight) c.route else c.ink2, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (insight) c.route else c.ink)
            Text(callout.text, style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
    }
}

/** Status banner (only when true): names the outcome and offers one action. */
@Composable
fun OutcomeBanner(outcome: SessionOutcome, affected: List<String>, technical: String?, onContinue: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (outcome == SessionOutcome.COMPLETE) return
    val c = AutopilotTheme.colors
    val body = when (outcome) {
        SessionOutcome.PARTIAL -> "The research didn't finish cleanly (${technical ?: "cut short"}). What arrived is shown; parts may be missing."
        SessionOutcome.INCOMPLETE_EVIDENCE -> "Searched, but not enough reliable evidence for: ${affected.joinToString(", ").ifEmpty { "some requirements" }}."
        SessionOutcome.INCOMPLETE_BUDGET -> "The search or spending budget ran out before: ${affected.joinToString(", ").ifEmpty { "everything was covered" }}. Using the budget up is not the same as finishing."
        SessionOutcome.STOPPED -> "You stopped it. What was found so far is kept" + (if (affected.isNotEmpty()) "; still open: ${affected.joinToString(", ")}" else "") + "."
        SessionOutcome.INTERRUPTED -> "The app closed while researching. Nothing was re-sent."
        SessionOutcome.FAILED -> "The research failed (${technical ?: "error"})."
        SessionOutcome.COMPLETE -> ""
    }
    com.lifeadmin.autopilot.ui.research.ResearchBanner(
        outcome.label, body, warn = true, icon = if (outcome == SessionOutcome.STOPPED) AutopilotIcons.Stop else AutopilotIcons.Warn, modifier = modifier, tag = "outcome_banner",
        action = onContinue?.let { { com.lifeadmin.autopilot.ui.components.LinkButton("Continue researching", onClick = it, modifier = Modifier.padding(top = 4.dp).testTag("banner_continue")) } },
    )
}

// ------------------------------------------------------------------ modes & estimate

/** Radio card: name, badge, description, meta (model · searches · time · estimate). */
@Composable
fun ModeCard(preset: ResearchModes.Preset, selected: Boolean, badge: String?, modelOverride: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    val model = modelOverride?.takeIf { it.isNotBlank() } ?: preset.model
    val est = com.lifeadmin.autopilot.ai.usage.CostCalculator.estimateRun(model, preset.searches, preset.outputTokens)
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(if (selected) c.sunken else c.surface).border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.line, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(14.dp).testTag("mode_${preset.mode.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = selected, onClick = null)
                Text(preset.mode.label, style = MaterialTheme.typography.titleMedium, color = c.ink)
            }
            if (badge != null) Text(badge, style = MaterialTheme.typography.labelSmall, color = c.onRouteSoft, modifier = Modifier.padding(top = 10.dp).clip(CircleShape).background(c.routeSoft).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Text(preset.blurb, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(start = 32.dp))
        ResearchStamp("${AiConfig.label(model)} · up to ${preset.searches} searches · ${preset.timeHint}" + (est?.let { " · ${UsdEstimate.formatRange(it.lowUsd, it.highUsd)}" } ?: ""), Modifier.padding(start = 32.dp))
    }
}

/** "~$0.27–0.51 estimate" with the authorized budget and this month's spend, always labelled estimate. */
@Composable
fun EstimateLine(config: RunConfig, monthSoFar: UsdEstimate?, monthlyBudgetCents: Int, modifier: Modifier = Modifier, onOpenUsage: (() -> Unit)? = null) {
    val c = AutopilotTheme.colors
    val est = config.estimate()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Wrapping row: at large text the month link drops to its own line instead of being squeezed.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(est?.let { UsdEstimate.formatRange(it.lowUsd, it.highUsd) } ?: "No price table for this model", style = TimeTextStyle.copy(fontSize = 18.sp), color = c.ink, modifier = Modifier.testTag("estimate_value"))
            Text("estimate", style = MaterialTheme.typography.labelSmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
        }
        if (monthSoFar != null) {
            val month = "This month ~${UsdEstimate.format(monthSoFar.usd)}" + (if (monthlyBudgetCents > 0) " of ${UsdEstimate.format(monthlyBudgetCents / 100.0)}" else "")
            if (onOpenUsage != null) com.lifeadmin.autopilot.ui.components.LinkButton(month, onClick = onOpenUsage, modifier = Modifier.testTag("estimate_month"))
            else Text(month, style = MaterialTheme.typography.labelSmall, color = c.ink2)
        }
        val limit = config.spendLimitCents
        if (limit != null && est != null && est.highUsd > limit / 100.0) {
            Text("May reach your ${UsdEstimate.format(limit / 100.0)} limit — it would stop early and keep what it found.", style = MaterialTheme.typography.bodySmall, color = c.onSignalSoft, modifier = Modifier.testTag("estimate_limit_warn"))
        }
    }
}

/** Adjust: model radio with relative token cost, search stepper 1–30, spending limit chips. No limit is the default. */
@Composable
fun AdjustPanel(
    model: String, onModel: (String) -> Unit,
    searches: Int, onSearches: (Int) -> Unit,
    limitCents: Int?, onLimit: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AutopilotTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Model", style = MaterialTheme.typography.titleSmall, color = c.ink)
        Column(Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp))) {
            AiConfig.MODELS.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = c.line)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected = model == m.id, role = Role.RadioButton, onClick = { onModel(m.id) }).padding(horizontal = 10.dp, vertical = 6.dp).testTag("adjust_model_${m.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = model == m.id, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text(m.label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                        Text(m.description.substringBefore(" (default)").substringBefore(", ~"), style = MaterialTheme.typography.bodySmall, color = c.ink2)
                    }
                    Text(PricingCatalog.relativeCost(m.id) ?: "?", style = TimeTextStyle.copy(fontSize = 13.sp), color = c.ink2)
                }
            }
        }
        Text("Search budget", style = MaterialTheme.typography.titleSmall, color = c.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundIconButton(AutopilotIcons.Minus, contentDescription = "Fewer searches", onClick = { onSearches((searches - 1).coerceAtLeast(Research2Config.MIN_SEARCHES)) }, modifier = Modifier.testTag("adjust_fewer"))
            Text("$searches searches max", style = TimeTextStyle.copy(fontSize = 16.sp), color = c.ink, modifier = Modifier.testTag("adjust_searches").semantics { stateDescription = "$searches" })
            RoundIconButton(AutopilotIcons.Plus, contentDescription = "More searches", onClick = { onSearches((searches + 1).coerceAtMost(Research2Config.MAX_SEARCHES)) }, modifier = Modifier.testTag("adjust_more"))
        }
        Text("Unused searches aren't charged. More searches cover more candidates and cross-checks.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        Text("Stop if this research would cost more than", style = MaterialTheme.typography.titleSmall, color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("No limit", selected = limitCents == null, onClick = { onLimit(null) }, modifier = Modifier.testTag("limit_none"))
            Research2Config.SPEND_LIMIT_CHOICES_CENTS.forEach { cents -> ChoiceChip(UsdEstimate.format(cents / 100.0), selected = limitCents == cents, onClick = { onLimit(cents) }, modifier = Modifier.testTag("limit_$cents")) }
        }
        Text(if (limitCents == null) "No limit: the estimate above is what to expect; every search and call is still counted." else "If it gets close, research stops and keeps what it found. One search already in flight can still land. You can continue after.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
    }
}

// ------------------------------------------------------------------ template B: candidates & compare

@Composable
fun CandidateCard(cand: Candidate, sources: List<SourceRef2>, now: Instant, zone: ZoneId, onCite: (Int) -> Unit, onDetails: () -> Unit, modifier: Modifier = Modifier, rank: Int? = null) {
    val c = AutopilotTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface).border(if (cand.hero) 2.dp else 1.dp, if (cand.hero) c.ink else c.line, shape).padding(14.dp).testTag("cand_${cand.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cand.style?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.ink2, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.sunken).padding(horizontal = 6.dp, vertical = 2.dp)) }
            if (cand.hero) Text("TOP PICK", style = MaterialTheme.typography.labelSmall, color = c.onInverse, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.inverse).padding(horizontal = 6.dp, vertical = 2.dp))
            Spacer(Modifier.weight(1f))
            if (rank != null) ResearchStamp("#$rank")
        }
        Text(cand.name, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(cand.variant ?: "Variant not confirmed", style = MaterialTheme.typography.bodySmall, color = if (cand.variant == null) c.ink3 else c.ink2)
        PriceLine(cand.price, now, zone, onCite)
        if (cand.reason.isNotBlank()) Text(cand.reason, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        val lasts = cand.attrs.firstOrNull { it.key == "lasts" }
        val gift = cand.attrs.firstOrNull { it.key == "gift" || it.key == "versatility" }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lasts?.let { AttrInline("Lasts", it.value, it.tag) }
            gift?.let { AttrInline(if (it.key == "gift") "As a gift" else "Versatility", it.value, it.tag) }
        }
        com.lifeadmin.autopilot.ui.components.LinkButton("Compare details", onClick = onDetails, modifier = Modifier.testTag("cand_details_${cand.id}"))
    }
}

@Composable
private fun AttrInline(label: String, value: String, tag: ProvenanceTag) {
    val c = AutopilotTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label: ", style = MaterialTheme.typography.labelSmall, color = c.ink2)
        Text(value, style = MaterialTheme.typography.bodySmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        ProvenanceTagChip(tag)
    }
}

/** Sticky first column, scrolls sideways inside a labelled region; a provenance tag under each row label. */
@Composable
fun CompareTable(candidates: List<Candidate>, keys: List<String>, sources: List<SourceRef2>, onCite: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    if (candidates.isEmpty() || keys.isEmpty()) return
    val scroll = rememberScrollState()
    val labelOf = mapOf("price" to "Price", "size" to "Size", "strength" to "Strength", "lasts" to "Lasts", "versatility" to "Versatility", "gift" to "As a gift", "value" to "Value", "notes" to "Notes", "ingredients" to "Ingredients", "form" to "Form", "serving" to "Serving", "third_party_testing" to "3rd-party tested")
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).semantics { contentDescription = "Comparison table, scrolls sideways" }.testTag("compare_table")) {
        Column(Modifier.width(IntrinsicSize.Max).background(c.sunken)) {
            Cell("", header = true)
            keys.forEach { k ->
                val tag = candidates.firstNotNullOfOrNull { cd -> cd.attrs.firstOrNull { it.key == k }?.tag } ?: ProvenanceTag.UNKNOWN
                Column(Modifier.widthIn(min = 96.dp).heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(labelOf[k] ?: k.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = c.ink)
                    ProvenanceTagChip(tag)
                }
            }
        }
        Row(Modifier.horizontalScroll(scroll)) {
            candidates.forEach { cd ->
                Column(Modifier.widthIn(min = 150.dp, max = 220.dp)) {
                    Cell(cd.name + (cd.variant?.let { "\n$it" } ?: ""), header = true)
                    keys.forEach { k ->
                        val a = cd.attrs.firstOrNull { it.key == k }
                        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(a?.value ?: "—", style = MaterialTheme.typography.bodySmall, color = if (a == null || a.tag == ProvenanceTag.UNKNOWN) c.ink3 else c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            a?.refs?.firstOrNull()?.let { CitationChip(it, onClick = { onCite(it) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, header: Boolean) {
    val c = AutopilotTheme.colors
    Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.BottomStart) {
        Text(text, style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ template C: item cards

/** Collapsible per-item card: header (item · status · pick); body with 4 labelled blocks. */
@Composable
fun ItemCard(req: Requirement, pick: ItemPick?, cov: Coverage, sources: List<SourceRef2>, now: Instant, zone: ZoneId, onCite: (Int) -> Unit, modifier: Modifier = Modifier, initiallyExpanded: Boolean = false) {
    val c = AutopilotTheme.colors
    var expanded by rememberSaveable(req.id) { mutableStateOf(initiallyExpanded) }
    val shape = RoundedCornerShape(18.dp)
    val status = pick?.status ?: cov.status
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface).let { if (status == RequirementStatus.NOT_FOUND) it.dottedBorder(c.lineStrong, 18.dp) else it.border(1.dp, c.line, shape) }.testTag("item_${req.id}"),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { expanded = !expanded }.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }.padding(14.dp).testTag("item_header_${req.id}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusGlyph(status, size = 22.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${req.label} · ${status.label}", style = MaterialTheme.typography.labelSmall, color = c.ink2)
                Text(pick?.pick ?: if (status == RequirementStatus.NOT_FOUND) "Nothing reliable found" else "No pick yet", style = MaterialTheme.typography.titleMedium, color = if (pick?.pick != null) c.ink else c.ink2, modifier = Modifier.semantics { heading() })
                pick?.variant?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink2) }
            }
            Icon(if (expanded) AutopilotIcons.ChevronDown else AutopilotIcons.ChevronRight, contentDescription = null, tint = c.ink3)
        }
        if (expanded) {
            HorizontalDivider(color = c.line)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pick?.labelFacts?.isNotEmpty() == true) Block("From the label", solid = true) {
                    pick.labelFacts.forEach { a ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(a.value, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
                            ProvenanceTagChip(a.tag)
                            a.refs.firstOrNull()?.let { CitationChip(it, onClick = { onCite(it) }) }
                        }
                    }
                }
                pick?.price?.let { PriceLine(it, now, zone, onCite) }
                pick?.alternative?.let { Block("Alternative") { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink) } }
                pick?.forYou?.let { Block("For you or your clinician", dotted = true) { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink) } }
                pick?.why?.let { Block("Why this pick · Claude's reasoning", dashed = true) { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink) } }
                if (pick == null || (pick.pick == null)) Text(cov.summary.ifBlank { "No source covered this item." }, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            }
        }
    }
}

@Composable
private fun Block(title: String, solid: Boolean = false, dotted: Boolean = false, dashed: Boolean = false, content: @Composable () -> Unit) {
    val c = AutopilotTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .let { when { solid -> it.border(1.dp, c.lineStrong, RoundedCornerShape(12.dp)); dotted -> it.dottedBorder(c.lineStrong, 12.dp); dashed -> it.dashedRoute(c.route, 12.dp); else -> it.background(c.sunken) } }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (dashed) c.route else c.ink2)
        content()
    }
}

// ------------------------------------------------------------------ templates D/E: steps

@Composable
fun StepList(steps: List<StepLine>, sources: List<SourceRef2>, onCite: (Int) -> Unit, modifier: Modifier = Modifier, numbered: Boolean = true, startIndex: Int = 0) {
    val c = AutopilotTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { offset, s ->
            val i = startIndex + offset
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).let { if (s.refs.isEmpty()) it.dottedBorder(c.lineStrong, 14.dp) else it.border(1.dp, c.line, RoundedCornerShape(14.dp)) }.padding(12.dp).testTag("step_$i"),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(c.sunken), contentAlignment = Alignment.Center) { Text(if (numbered) "${i + 1}" else "•", style = TimeTextStyle.copy(fontSize = 12.sp), color = c.ink) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (s.optional) Text("Optional", style = MaterialTheme.typography.labelSmall, color = c.ink2)
                        Text(if (s.refs.isEmpty()) "Not confirmed by a page" else "Sourced", style = MaterialTheme.typography.labelSmall, color = if (s.refs.isEmpty()) c.ink3 else c.ink2)
                    }
                }
                s.refs.forEach { n -> CitationChip(n, onClick = { onCite(n) }, title = sources.firstOrNull { it.n == n }?.title) }
            }
        }
    }
}

// ------------------------------------------------------------------ continue & stop sheets

@Composable
fun ContinueCard(openLabels: List<String>, estimate: UsdEstimate?, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    SurfaceCard(radius = 18.dp, modifier = modifier.testTag("continue_card")) {
        Text("Still open", style = MaterialTheme.typography.titleSmall, color = c.ink)
        Text(if (openLabels.isEmpty()) "Everything you asked for is covered. More searches could still add candidates or confirm prices." else openLabels.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("Continue researching…", onClick = onContinue, minHeight = 44.dp, modifier = Modifier.testTag("continue_button"))
            estimate?.let { ResearchStamp("${UsdEstimate.formatRange(it.lowUsd, it.highUsd)} est.", Modifier.padding(top = 12.dp)) }
        }
        Text("Picks up from this result — nothing found so far is searched again. Starts only when you confirm.", style = MaterialTheme.typography.bodySmall, color = c.ink3, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun StopSheet(searchesRun: Int, spentFloor: UsdEstimate, canKeep: Boolean, onKeep: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    val c = AutopilotTheme.colors
    AutopilotSheet(title = "Stop researching?", onDismiss = onDismiss, closeLabel = "Keep researching", footer = {
        PrimaryButton(if (canKeep) "Stop and keep what's found" else "Stop (nothing to keep yet)", onClick = onKeep, modifier = Modifier.fillMaxWidth().testTag("stop_keep"))
        SecondaryButton("Stop and discard", onClick = onDiscard, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("stop_discard"), minHeight = 48.dp)
        SecondaryButton("Keep researching", onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("stop_cancel"), minHeight = 48.dp)
    }) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("stop_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$searchesRun ${if (searchesRun == 1) "search has" else "searches have"} run so far (≥ ${UsdEstimate.format(spentFloor.usd)} for searches and finished calls); tokens for the call in flight are billed too, exact count unknown. Stopping aborts the request now.",
                style = MaterialTheme.typography.bodyMedium, color = c.ink2,
            )
            Text(if (canKeep) "Keeping saves a result marked Stopped, with what was gathered. Discarding saves nothing; the usage is still recorded." else "Nothing usable has arrived yet, so there is nothing to keep.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
    }
}

/** Mono stamp: THOROUGH · SONNET 5.5 · 7 SEARCHES · 1:20. */
fun stampLine(mode: ResearchMode, model: String, searches: Int, elapsedMs: Long): String =
    "${mode.label} · ${AiConfig.label(model).removePrefix("Claude ")} · $searches ${if (searches == 1) "search" else "searches"} · ${elapsedMs / 60_000}:${"%02d".format((elapsedMs / 1000) % 60)}"
