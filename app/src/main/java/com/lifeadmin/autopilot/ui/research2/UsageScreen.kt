package com.lifeadmin.autopilot.ui.research2

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.ResearchSessionStore
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.PricingCatalog
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.ai.usage.UsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageRecord
import com.lifeadmin.autopilot.ai.usage.UsageStatus
import com.lifeadmin.autopilot.ai.usage.UsageTotals
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.ui.components.AutopilotIcons
import com.lifeadmin.autopilot.ui.components.AutopilotSheet
import com.lifeadmin.autopilot.ui.components.BackHeader
import com.lifeadmin.autopilot.ui.components.ChoiceChip
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.SecondaryButton
import com.lifeadmin.autopilot.ui.components.SectionTitle
import com.lifeadmin.autopilot.ui.components.SurfaceCard
import com.lifeadmin.autopilot.ui.components.TimeText
import com.lifeadmin.autopilot.ui.research.ResearchStamp
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme
import com.lifeadmin.autopilot.ui.theme.TimeTextStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class RunGroup(val key: String, val sessionId: String?, val title: String, val records: List<UsageRecord>, val totals: UsageTotals, val startedAt: Instant, val feature: UsageFeature, val continuation: Boolean)

data class UsageUi(
    val loading: Boolean = true,
    val today: UsageTotals = UsageTotals.of(emptyList()),
    val month: UsageTotals = UsageTotals.of(emptyList()),
    val monthResearch: UsageTotals = UsageTotals.of(emptyList()),
    val monthPlanning: UsageTotals = UsageTotals.of(emptyList()),
    val monthSearchMicros: Long = 0,
    val monthLabel: String = "",
    val todayCounts: String = "",
    val groups: List<RunGroup> = emptyList(),
    val monthlyBudgetCents: Int = 0,
    val spendLimitCents: Int = 0,
    val sheet: RunGroup? = null,
    val focusSession: String? = null,
    val now: Instant = Instant.EPOCH,
)

/** AI usage & costs: today, month-to-date, by feature, per run with honest unknowns, spending preferences. */
class UsageViewModel(
    private val ledger: UsageLedger,
    store: ResearchSessionStore,
    private val settings: SettingsRepository,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    focusSession: String?,
) : ViewModel() {
    private val sheet = MutableStateFlow<RunGroup?>(null)
    private val titles = MutableStateFlow<Map<String, String>>(emptyMap())

    init { viewModelScope.launch { titles.value = store.getAll().associate { it.id to it.content.title } } }

    val state: StateFlow<UsageUi> = combine(ledger.observeAll(), settings.settings, sheet, titles) { all, s, sh, tt ->
        val zone = zoneProvider.current()
        val now = clock.now()
        val today = now.atZone(zone).toLocalDate()
        val month = YearMonth.from(now.atZone(zone))
        val todayRows = all.filter { it.startedAt.atZone(zone).toLocalDate() == today }
        val monthRows = all.filter { YearMonth.from(it.startedAt.atZone(zone)) == month }
        val research = monthRows.filter { it.feature != UsageFeature.PLANNING }
        val groups = all.groupBy { it.runId ?: it.id }.map { (k, rows) ->
            val first = rows.minBy { it.startedAt }
            RunGroup(k, first.sessionId, first.sessionId?.let { tt[it] } ?: if (first.feature == UsageFeature.PLANNING) "Task planning" else "Research", rows.sortedBy { it.startedAt }, UsageTotals.of(rows), first.startedAt, first.feature, first.feature == UsageFeature.RESEARCH_CONTINUATION)
        }.sortedByDescending { it.startedAt }
        UsageUi(
            loading = false, today = UsageTotals.of(todayRows), month = UsageTotals.of(monthRows), monthResearch = UsageTotals.of(research), monthPlanning = UsageTotals.of(monthRows.filter { it.feature == UsageFeature.PLANNING }),
            monthSearchMicros = research.sumOf { (it.usage?.webSearches ?: 0) * 10_000L },
            monthLabel = "${month.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} 1–${today.dayOfMonth}",
            todayCounts = "${todayRows.count { it.feature != UsageFeature.PLANNING }} research calls, ${todayRows.count { it.feature == UsageFeature.PLANNING }} plans",
            groups = groups.take(60), monthlyBudgetCents = s.aiMonthlyBudgetCents, spendLimitCents = s.researchSpendLimitCents, sheet = sh, focusSession = focusSession, now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageUi())

    fun open(g: RunGroup) { sheet.value = g }
    fun close() { sheet.value = null }
    fun setMonthlyBudget(cents: Int) = viewModelScope.launch { settings.update { it.copy(aiMonthlyBudgetCents = cents) } }
    fun setSpendLimit(cents: Int) = viewModelScope.launch { settings.update { it.copy(researchSpendLimitCents = cents) } }
    fun deleteOlderThanYear() = viewModelScope.launch { ledger.deleteOlderThan(clock.now().minus(java.time.Duration.ofDays(365))) }
    fun deleteAll() = viewModelScope.launch { ledger.deleteAll() }
}

@Composable
fun UsageScreen(viewModel: UsageViewModel, zone: ZoneId, onBack: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AutopilotTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(containerColor = c.paper, contentWindowInsets = WindowInsets(0), topBar = { BackHeader("AI usage", onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("usage_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile("This month", s.month, s.monthLabel, Modifier.weight(1f).testTag("usage_month"))
                    Tile("Today", s.today, s.todayCounts, Modifier.weight(1f).testTag("usage_today"))
                }
                Text("Estimates use the token and search counts Anthropic reported × the price list built into this version (${PricingCatalog.VERSION}). Your Anthropic Console is the authoritative bill.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                SectionTitle("By feature this month", Modifier.padding(top = 18.dp, bottom = 8.dp))
                SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("usage_features")) {
                    FeatureRow("Research", "${s.monthResearch.calls} calls · ${s.monthResearch.searches} searches", s.monthResearch)
                    HorizontalDivider(color = c.line)
                    FeatureRow("Task planning", "${s.monthPlanning.calls} requests", s.monthPlanning)
                }
                if (s.monthResearch.calls > 0) Text("In research, web searches were ~${UsdEstimate.format(s.monthSearchMicros / 1_000_000.0)} and tokens ~${UsdEstimate.format((s.monthResearch.estimate.micros - s.monthSearchMicros).coerceAtLeast(0) / 1_000_000.0)}.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                SectionTitle("Spending preferences", Modifier.padding(top = 18.dp, bottom = 8.dp))
                SurfaceCard(radius = 18.dp, modifier = Modifier.testTag("usage_prefs")) {
                    Text("Monthly budget (soft)", style = MaterialTheme.typography.titleSmall, color = c.ink)
                    Text(if (s.monthlyBudgetCents > 0) "~${UsdEstimate.format(s.month.estimate.usd)} of ${UsdEstimate.format(s.monthlyBudgetCents / 100.0)} · ${(s.month.estimate.usd / (s.monthlyBudgetCents / 100.0) * 100).toInt()}%. At 80% you'll get a note before research starts; at 100% research asks you first. Nothing is blocked." else "None. Set one to get a note at 80% and a confirmation at 100%; nothing is ever blocked.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("None", selected = s.monthlyBudgetCents == 0, onClick = { viewModel.setMonthlyBudget(0) }, modifier = Modifier.testTag("budget_0"))
                        Research2Config.MONTHLY_BUDGET_CHOICES_CENTS.forEach { cents -> ChoiceChip(UsdEstimate.format(cents / 100.0), selected = s.monthlyBudgetCents == cents, onClick = { viewModel.setMonthlyBudget(cents) }, modifier = Modifier.testTag("budget_$cents")) }
                    }
                    Text("Default stop limit per research", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(top = 14.dp))
                    Text(if (s.spendLimitCents > 0) "Research stops near ${UsdEstimate.format(s.spendLimitCents / 100.0)} and keeps what it found; one in-flight call can still land. Change it per run on the research screen." else "No limit by default: the estimate is shown before you start and every call is counted. Change it per run on the research screen.", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("No limit", selected = s.spendLimitCents == 0, onClick = { viewModel.setSpendLimit(0) }, modifier = Modifier.testTag("pref_limit_none"))
                        Research2Config.SPEND_LIMIT_CHOICES_CENTS.forEach { cents -> ChoiceChip(UsdEstimate.format(cents / 100.0), selected = s.spendLimitCents == cents, onClick = { viewModel.setSpendLimit(cents) }, modifier = Modifier.testTag("pref_limit_$cents")) }
                    }
                }
            }
            item { SectionTitle("Recent runs", Modifier.padding(top = 18.dp, bottom = 8.dp)) }
            if (s.groups.isEmpty()) item { Text("No AI calls recorded on this phone yet.", style = MaterialTheme.typography.bodyMedium, color = c.ink2) }
            else item {
                SurfaceCard(radius = 18.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("usage_runs")) {
                    s.groups.forEachIndexed { i, g -> UsageRunRow(g, s.now, zone, highlighted = g.sessionId != null && g.sessionId == s.focusSession, onClick = { viewModel.open(g) }, divider = i > 0) }
                }
            }
            item {
                SectionTitle("Keep or delete", Modifier.padding(top = 18.dp, bottom = 8.dp))
                SurfaceCard(radius = 18.dp) {
                    Text("Usage rows hold ids, counts and estimates only — never prompts, task text or your key. They are included in backups.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Delete older than a year", onClick = viewModel::deleteOlderThanYear, minHeight = 44.dp, modifier = Modifier.testTag("usage_delete_old"))
                        SecondaryButton("Delete all", onClick = { confirmDelete = true }, minHeight = 44.dp, modifier = Modifier.testTag("usage_delete_all"))
                    }
                }
            }
        }
    }
    s.sheet?.let { g -> BreakdownSheet(g, zone, onDismiss = viewModel::close) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, title = { Text("Delete usage history?") },
            text = { Text("Removes every usage row from this phone. Saved research and tasks are unaffected. Anthropic's Console still shows your real bill.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteAll() }, modifier = Modifier.testTag("usage_delete_confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Tile(label: String, totals: UsageTotals, sub: String, modifier: Modifier) {
    val c = AutopilotTheme.colors
    SurfaceCard(radius = 18.dp, modifier = modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.ink2)
        Text((if (totals.partial) "≥ " else "~") + UsdEstimate.format(totals.estimate.usd), style = TimeTextStyle.copy(fontSize = 24.sp, lineHeight = 30.sp), color = c.ink, modifier = Modifier.padding(top = 4.dp))
        Text(sub + if (totals.unknownCalls > 0) " · ${totals.unknownCalls} with unknown usage" else "", style = MaterialTheme.typography.labelSmall, color = c.ink2)
    }
}

@Composable
private fun FeatureRow(label: String, sub: String, totals: UsageTotals) {
    val c = AutopilotTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink); Text(sub, style = MaterialTheme.typography.bodySmall, color = c.ink2) }
        TimeText((if (totals.partial) "≥ " else "~") + UsdEstimate.format(totals.estimate.usd), size = 15)
    }
}

/** complete · cut short · stopped (tokens unknown) · failed (not billed). */
@Composable
fun UsageRunRow(g: RunGroup, now: Instant, zone: ZoneId, highlighted: Boolean, onClick: () -> Unit, divider: Boolean) {
    val c = AutopilotTheme.colors
    if (divider) HorizontalDivider(color = c.line)
    val worst = g.records.map { it.outcome }.let { o ->
        when {
            CallOutcome.IN_FLIGHT in o -> "in flight"
            CallOutcome.INTERRUPTED in o -> "interrupted · usage unknown"
            CallOutcome.FAILED_UNKNOWN in o -> "failed · usage unknown"
            CallOutcome.STOPPED in o -> "stopped · tokens unknown"
            CallOutcome.TRUNCATED in o -> "cut short"
            CallOutcome.FAILED_NOT_BILLED in o && o.all { it == CallOutcome.FAILED_NOT_BILLED } -> "failed · not billed"
            else -> "complete"
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp).testTag("usage_run_${g.key}").semantics { contentDescription = "${g.title}, $worst, estimated ${UsdEstimate.format(g.totals.estimate.usd)}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(g.title + if (g.continuation) " · continued" else "", style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            ResearchStamp("${Format.relative(g.startedAt, now, zone)} · ${AiConfig.label(g.records.first().model).removePrefix("Claude ")} · ${g.records.size} ${if (g.records.size == 1) "call" else "calls"} · ${g.totals.searches} searches")
            Text(worst, style = MaterialTheme.typography.labelSmall, color = if (worst == "complete") c.ink2 else c.onSignalSoft)
        }
        TimeText(if (g.totals.estimate.micros == 0L && g.records.all { it.outcome == CallOutcome.FAILED_NOT_BILLED }) "$0.00" else (if (g.totals.partial) "≥ " else "~") + UsdEstimate.format(g.totals.estimate.usd), size = 15, color = if (highlighted) c.route else c.ink)
    }
}

@Composable
private fun BreakdownSheet(g: RunGroup, zone: ZoneId, onDismiss: () -> Unit) {
    val c = AutopilotTheme.colors
    AutopilotSheet(title = g.title, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("usage_breakdown"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            g.records.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = c.line)
                val p = PricingCatalog.price(r.model)
                Text("${r.stage ?: r.feature.label} · ${AiConfig.label(r.model)} · ${r.outcome.label}" + (r.durationMs?.let { " · ${it / 1000} s" } ?: ""), style = MaterialTheme.typography.titleSmall, color = c.ink)
                val u = r.usage
                if (r.outcome == CallOutcome.FAILED_NOT_BILLED) Text("$0.00 · not billed — rejected before processing.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                else if (u == null) Text("Usage unknown — the response never arrived. Anthropic may still bill the request; the app shows no number rather than a wrong one.", style = MaterialTheme.typography.bodySmall, color = c.onSignalSoft)
                else {
                    Line("Input tokens", u.inputTokens, p?.input)
                    Line("Output tokens", u.outputTokens, p?.output)
                    if ((u.cacheWrite5mTokens ?: 0) > 0) Line("Cache write (5 min)", u.cacheWrite5mTokens, p?.cacheWrite5m)
                    if ((u.cacheWrite1hTokens ?: 0) > 0) Line("Cache write (1 h)", u.cacheWrite1hTokens, p?.cacheWrite1h)
                    if ((u.cacheReadTokens ?: 0) > 0) Line("Cache read", u.cacheReadTokens, p?.cacheRead)
                    Row(Modifier.fillMaxWidth()) { Text("Web searches", style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.weight(1f)); Text("${u.webSearches ?: "?"} × $0.01", style = MaterialTheme.typography.bodySmall, color = c.ink2); Text("  ${UsdEstimate.format((u.webSearches ?: 0) * 0.01)}", style = TimeTextStyle.copy(fontSize = 13.sp), color = c.ink) }
                    Text((if (r.usageStatus == UsageStatus.COMPLETE) "Estimated total ~" else "Estimated floor ≥ ") + UsdEstimate.format((r.estimatedMicros ?: 0) / 1_000_000.0) + if (r.usageStatus != UsageStatus.COMPLETE) " (some counts unknown)" else "", style = MaterialTheme.typography.bodyMedium, color = c.ink)
                }
            }
            Text("Price table ${g.records.first().priceVersion}. Anthropic's Console is the authoritative bill; the app never reads it.", style = MaterialTheme.typography.bodySmall, color = c.ink3)
        }
    }
}

@Composable
private fun Line(label: String, tokens: Long?, rate: Double?) {
    val c = AutopilotTheme.colors
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.ink2, modifier = Modifier.weight(1f))
        Text(tokens?.let { "$it × ${rate?.let { r -> "$$r/MTok" } ?: "?"}" } ?: "unknown", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        if (tokens != null && rate != null) Text("  ${UsdEstimate.format(tokens * rate / 1_000_000)}", style = TimeTextStyle.copy(fontSize = 13.sp), color = c.ink)
    }
}
