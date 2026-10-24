package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.context.TurnConfig
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.ResearchMode
import com.lifeadmin.autopilot.ai.research2.StageKind
import com.lifeadmin.autopilot.ai.usage.CostCalculator
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.domain.model.AppSettings
import kotlinx.serialization.Serializable

/** Research depth (R48): Quick, Standard, Thorough, Deep, plus Custom. A ceiling on breadth, never a target (RES-030). */
@Serializable
enum class ResearchDepth(val label: String) {
    QUICK("Quick"),
    STANDARD("Standard"),
    THOROUGH("Thorough"),
    DEEP("Deep"),
    CUSTOM("Custom"),
    ;

    companion object {
        fun of(name: String?): ResearchDepth? = entries.firstOrNull { it.name == name?.trim()?.uppercase() }

        /** R48 migration rule: a saved legacy mode keeps its meaning exactly; nothing maps onto Standard. */
        fun fromLegacy(mode: ResearchMode): ResearchDepth = when (mode) {
            ResearchMode.QUICK -> QUICK
            ResearchMode.THOROUGH -> THOROUGH
            ResearchMode.DEEP -> DEEP
            ResearchMode.CUSTOM -> CUSTOM
        }
    }
}

data class DepthPreset(val depth: ResearchDepth, val model: String, val searches: Int, val outputTokens: Long, val stages: List<StageKind>, val blurb: String)

/** Everything one turn is allowed to spend and how it goes about it; every dimension independent (RES-130). */
data class ResearchRunConfig(
    val depth: ResearchDepth,
    /** Name shown and recorded: the preset, or "Custom" when a dimension was changed by hand. */
    val presetName: String,
    val model: String,
    val searchBudget: Int,
    /** True while the budget follows the depth's suggestion (OD-3). */
    val budgetFollowsDepth: Boolean,
    val outputTokens: Long,
    val stages: List<StageKind>,
    val spendLimitCents: Int? = null,
    /** Provider reasoning effort, only when the model supports it; null = provider default. */
    val effort: String? = null,
    val auto: Boolean = false,
) {
    fun estimate(): UsdEstimate? = CostCalculator.estimateRun(model, searchBudget, outputTokens)
    fun turnConfig(): TurnConfig = TurnConfig(model = model, effort = effort, depth = depth.name.lowercase(), searchBudget = searchBudget, spendLimitCents = spendLimitCents, auto = auto)
}

object ResearchPresets {
    const val QUICK_MODEL = "claude-haiku-5-5"
    const val STANDARD_MODEL = "claude-sonnet-5-5"
    const val THOROUGH_MODEL = "claude-sonnet-5-5"
    const val DEEP_MODEL = "claude-opus-5-5"

    /** Quick, Thorough and Deep keep their M3A.2 definitions exactly; Standard is the new, middle level. */
    val QUICK = DepthPreset(ResearchDepth.QUICK, QUICK_MODEL, 3, 6_000L, listOf(StageKind.GATHER), "One pass, a few searches. For a fact or a simple question.")
    val STANDARD = DepthPreset(ResearchDepth.STANDARD, STANDARD_MODEL, 6, 8_000L, listOf(StageKind.GATHER, StageKind.FILL_GAPS), "Gathers evidence, then checks what the answer rests on.")
    val THOROUGH = DepthPreset(ResearchDepth.THOROUGH, THOROUGH_MODEL, 10, 8_000L, listOf(StageKind.GATHER, StageKind.FILL_GAPS), "More searches and a second pass on anything still open.")
    val DEEP = DepthPreset(ResearchDepth.DEEP, DEEP_MODEL, 25, 12_000L, listOf(StageKind.GATHER, StageKind.FILL_GAPS, StageKind.CROSS_CHECK, StageKind.SYNTHESIZE), "Gather, check, reconcile disagreements, then write it up.")

    val PRESETS = listOf(QUICK, STANDARD, THOROUGH, DEEP)

    fun preset(depth: ResearchDepth): DepthPreset = PRESETS.firstOrNull { it.depth == depth } ?: STANDARD

    /** The saved model override for a depth; blank = the preset's model. Standard has its own, new setting. */
    fun savedModel(depth: ResearchDepth, s: AppSettings): String? = when (depth) {
        ResearchDepth.QUICK -> s.researchQuickModel
        ResearchDepth.STANDARD -> s.researchStandardModel
        ResearchDepth.THOROUGH -> s.researchThoroughModel
        ResearchDepth.DEEP -> s.researchDeepModel
        ResearchDepth.CUSTOM -> null
    }?.takeIf { it.isNotBlank() }

    /**
     * Resolves one run's configuration. [modelOverride], [searches], [limitCents] and [effort] are the
     * per-run choices; absent, the saved defaults apply; absent those, the preset. A changed model or
     * budget turns the preset name into Custom; the depth (stages) stays what was chosen.
     */
    fun resolve(
        depth: ResearchDepth,
        settings: AppSettings,
        modelOverride: String? = null,
        searches: Int? = null,
        limitCents: Int? = null,
        effort: String? = null,
        auto: Boolean = false,
    ): ResearchRunConfig {
        val p = preset(if (depth == ResearchDepth.CUSTOM) ResearchDepth.STANDARD else depth)
        val model = modelOverride?.takeIf { it.isNotBlank() } ?: savedModel(depth, settings) ?: p.model
        val savedBudget = settings.researchDefaultSearches.takeIf { it > 0 }
        val budget = (searches ?: savedBudget ?: p.searches).coerceIn(Research2Config.MIN_SEARCHES, Research2Config.MAX_SEARCHES)
        val follows = searches == null && savedBudget == null
        val limit = (limitCents ?: settings.researchSpendLimitCents.takeIf { it > 0 })?.takeIf { it > 0 }
        val eff = (effort ?: settings.researchEffort.takeIf { it.isNotBlank() })?.takeIf { EffortSupport.supports(model) && it in EffortSupport.LEVELS }
        val custom = depth == ResearchDepth.CUSTOM || model != p.model || budget != p.searches
        return ResearchRunConfig(
            depth = if (depth == ResearchDepth.CUSTOM) ResearchDepth.CUSTOM else depth, presetName = if (custom) ResearchDepth.CUSTOM.label else depth.label,
            model = model, searchBudget = budget, budgetFollowsDepth = follows, outputTokens = p.outputTokens, stages = stagesFor(p.stages, budget),
            spendLimitCents = limit, effort = eff, auto = auto,
        )
    }

    /** With very few searches a second stage cannot help; keep the stage list honest. */
    fun stagesFor(stages: List<StageKind>, searches: Int): List<StageKind> = when {
        searches <= 2 -> listOf(StageKind.GATHER)
        searches <= 5 -> stages.filter { it == StageKind.GATHER || it == StageKind.FILL_GAPS }
        else -> stages
    }

    /** Search allowance per stage: GATHER gets the lion's share, later stages the remainder. */
    fun stageShare(kind: StageKind, remaining: Int, total: Int, stageCount: Int): Int = when (kind) {
        StageKind.GATHER -> if (stageCount <= 1) remaining else maxOf(1, (total * 0.6).toInt().coerceAtMost(remaining))
        StageKind.FILL_GAPS -> maxOf(1, (total * 0.3).toInt()).coerceAtMost(remaining)
        StageKind.CROSS_CHECK -> remaining.coerceAtLeast(0)
        StageKind.SYNTHESIZE, StageKind.CHECK_WORDING -> 0
    }

    /** RES-035: the warning informs; it never blocks. */
    fun insufficientLevelWarning(depth: ResearchDepth, objective: Objective): String? {
        if (depth != ResearchDepth.QUICK) return null
        val needsMore = objective.askKind == AskKind.COMPARE || objective.askKind == AskKind.RECOMMEND || objective.askKind == AskKind.DECIDE || objective.consequential ||
            objective.recommendedDepth == ResearchDepth.THOROUGH || objective.recommendedDepth == ResearchDepth.DEEP
        return if (needsMore) "Quick may not be enough here. It won't cross-check sources against each other, so the answer may rest on thin evidence. You can still run it." else null
    }
}

/**
 * Reasoning effort (RES-135). Sent as the provider's `output_config.effort` only for models the
 * app lists as supporting it. The list is the app's reading of the provider's documentation, not a
 * live capability check; an unsupported model gets the control disabled with that reason.
 */
object EffortSupport {
    val LEVELS = listOf("low", "medium", "high")
    private val SUPPORTED = setOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1")

    fun supports(model: String): Boolean = model.trim() in SUPPORTED

    fun reason(model: String): String? = if (supports(model)) null else "Reasoning effort isn't configurable for ${com.lifeadmin.autopilot.ai.AiConfig.label(model)} as far as this app knows; the provider's default applies."

    fun label(effort: String?): String = when (effort) { null, "" -> "Provider default"; else -> effort.replaceFirstChar { it.uppercase() } }
}
