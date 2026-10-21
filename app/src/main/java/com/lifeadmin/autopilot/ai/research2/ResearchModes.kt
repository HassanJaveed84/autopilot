package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.ai.usage.CostCalculator
import com.lifeadmin.autopilot.ai.usage.UsdEstimate

/** Limits and defaults for M3A.2. Presets are starting points; every field is editable before Start. */
object Research2Config {
    const val MIN_SEARCHES = 1
    const val MAX_SEARCHES = 30
    const val MAX_REQUEST_CHARS = 1_200
    const val MAX_REQUIREMENTS = 16
    const val MAX_REQUIREMENT_CHARS = 120
    const val MAX_TITLE_CHARS = 60

    /** Sessions per local day across tasks; a continuation counts as one. A cheap guard, not a product ceiling. */
    const val MAX_SESSIONS_PER_DAY = 25

    /** Sessions kept per task; the oldest beyond this are pruned on save. */
    const val MAX_SESSIONS_PER_TASK = 30
    const val MAX_PAYLOAD_BYTES = 200_000
    const val MAX_CANDIDATES = 12
    const val MAX_CLAIMS = 24
    const val MAX_PROPOSALS = 12

    /** `pause_turn` is continued at most this many times per stage, within the stage's remaining searches. */
    const val MAX_PAUSE_CONTINUATIONS = 1

    /**
     * Searches one HTTP call may run. A stage with a larger allowance is split into bounded calls
     * (each a separate billed request, each merged and saved before the next), so a slow or dropped
     * call costs at most one batch of evidence instead of the whole stage. Follow-up calls within a
     * stage happen only while requirements are still open and the model keeps searching.
     */
    const val MAX_SEARCHES_PER_CALL = 6

    /** Upper bound on calls per stage (batches + one `pause_turn` continuation each). */
    const val MAX_CALLS_PER_STAGE = 5

    /**
     * Per-call HTTP timeout policy (P0 real-device fix). Non-streaming Messages calls must finish well
     * inside Anthropic's 10-minute request limit, and a research call's wall time grows with the
     * searches it may run and the tokens it may write. The limit is applied per request at the SDK
     * layer, never only as a coroutine timer.
     */
    val MIN_CALL_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(120)
    val MAX_CALL_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(540)

    /** 60 s base + 15 s per allowed search + ~40 output tokens/s, clamped to [MIN_CALL_TIMEOUT, MAX_CALL_TIMEOUT]. */
    fun callTimeout(maxSearches: Int, outputTokens: Long): java.time.Duration {
        val seconds = 60L + 15L * maxSearches.coerceAtLeast(0) + outputTokens.coerceAtLeast(0) / 40L
        return java.time.Duration.ofSeconds(seconds.coerceIn(MIN_CALL_TIMEOUT.seconds, MAX_CALL_TIMEOUT.seconds))
    }

    /** A price older than this is shown as "may be stale". */
    val PRICE_STALE_AFTER: java.time.Duration = java.time.Duration.ofHours(24)

    /** Spend-limit chips (cents). The default is no limit: quality first, with an honest estimate instead. */
    val SPEND_LIMIT_CHOICES_CENTS = listOf(25, 50, 100, 200)
    val MONTHLY_BUDGET_CHOICES_CENTS = listOf(200, 500, 1_000, 2_000)

    /** Anthropic server tool identifiers (documented web search tool, version 2025-03-05). */
    const val TOOL_TYPE = "web_search_20250305"
    const val TOOL_NAME = "web_search"
}

/** Everything one run is authorized to spend and how it should go about it. */
data class RunConfig(
    val mode: ResearchMode,
    val model: String,
    val searchBudget: Int,
    val outputTokens: Long,
    val stages: List<StageKind>,
    /** Soft dollar limit chosen by the user; null = no limit. */
    val spendLimitCents: Int? = null,
) {
    fun estimate(): UsdEstimate? = CostCalculator.estimateRun(model, searchBudget, outputTokens)
    val stampWord: String get() = mode.label.uppercase()
}

object ResearchModes {
    const val QUICK_MODEL = "claude-haiku-5-5"
    const val THOROUGH_MODEL = "claude-sonnet-5-5"
    const val DEEP_MODEL = "claude-opus-5-5"

    data class Preset(val mode: ResearchMode, val model: String, val searches: Int, val outputTokens: Long, val stages: List<StageKind>, val timeHint: String, val blurb: String)

    val QUICK = Preset(ResearchMode.QUICK, QUICK_MODEL, 3, 1_500, listOf(StageKind.GATHER), "about 30 s", "One pass, a few searches. Good for a fact or a quick answer.")
    val THOROUGH = Preset(ResearchMode.THOROUGH, THOROUGH_MODEL, 10, 6_000, listOf(StageKind.GATHER, StageKind.FILL_GAPS), "1–2 min", "Gathers evidence, then goes back for anything still missing.")
    val DEEP = Preset(ResearchMode.DEEP, DEEP_MODEL, 25, 10_000, listOf(StageKind.GATHER, StageKind.FILL_GAPS, StageKind.CROSS_CHECK, StageKind.SYNTHESIZE), "3–6 min", "Multi-stage: gather, fill gaps, cross-check prices and conflicts, then write it up.")

    val PRESETS = listOf(QUICK, THOROUGH, DEEP)

    fun preset(mode: ResearchMode): Preset = PRESETS.firstOrNull { it.mode == mode } ?: THOROUGH

    /** Quick answers start Quick; everything else Thorough. Deep is never pre-selected (d5). */
    fun defaultFor(template: DeliverableTemplate): ResearchMode = if (template == DeliverableTemplate.QUICK_ANSWER) ResearchMode.QUICK else ResearchMode.THOROUGH

    fun config(mode: ResearchMode, modelOverride: String? = null, searches: Int? = null, spendLimitCents: Int? = null): RunConfig {
        val p = preset(mode)
        val model = modelOverride?.takeIf { it.isNotBlank() } ?: p.model
        val s = (searches ?: p.searches).coerceIn(Research2Config.MIN_SEARCHES, Research2Config.MAX_SEARCHES)
        val custom = model != p.model || s != p.searches
        return RunConfig(if (custom && mode != ResearchMode.CUSTOM) ResearchMode.CUSTOM else mode, model, s, p.outputTokens, stagesFor(p.stages, s), spendLimitCents)
    }

    /** With very few searches a second stage cannot help; keep the stage list honest. */
    private fun stagesFor(stages: List<StageKind>, searches: Int): List<StageKind> = when {
        searches <= 2 -> listOf(StageKind.GATHER)
        searches <= 5 -> stages.filter { it == StageKind.GATHER || it == StageKind.FILL_GAPS }
        else -> stages
    }

    /** Search allowance per stage: GATHER gets the lion's share, later stages the remainder. */
    fun stageShare(kind: StageKind, remaining: Int, total: Int, stageCount: Int = 2): Int = when (kind) {
        StageKind.GATHER -> if (stageCount <= 1) remaining else maxOf(1, (total * 0.6).toInt().coerceAtMost(remaining))
        StageKind.FILL_GAPS -> maxOf(1, (total * 0.3).toInt()).coerceAtMost(remaining)
        StageKind.CROSS_CHECK -> remaining.coerceAtLeast(0)
        StageKind.SYNTHESIZE -> 0
    }
}
