package com.lifeadmin.autopilot.ai.usage

import kotlin.math.roundToLong

/**
 * Versioned price table, copied from platform.claude.com/docs/en/about-claude/pricing on 2026-10-20.
 * Prices are USD per million tokens; web search is USD per 1,000 searches. Every estimate the app
 * shows names the version it used, so a stale table is visible rather than silently wrong.
 */
data class ModelPrice(
    val model: String,
    val input: Double,
    val cacheWrite5m: Double,
    val cacheWrite1h: Double,
    val cacheRead: Double,
    val output: Double,
    /** Prompts above this many input tokens use [longContext] (Haiku 5.5 has a tier break at 100k). */
    val longContextThreshold: Long? = null,
    val longContext: ModelPrice? = null,
)

object PricingCatalog {
    const val VERSION = "2026-10"
    const val SOURCE = "platform.claude.com/docs/en/about-claude/pricing (2026-10-20)"

    /** $10 per 1,000 searches; errored searches are not billed. */
    const val WEB_SEARCH_USD = 0.01

    private val haikuLong = ModelPrice("claude-haiku-5-5", 0.50, 0.625, 1.00, 0.05, 2.50)
    val MODELS: List<ModelPrice> = listOf(
        ModelPrice("claude-haiku-5-5", 0.10, 0.125, 0.20, 0.01, 0.50, longContextThreshold = 100_000, longContext = haikuLong),
        ModelPrice("claude-sonnet-5-5", 2.00, 2.50, 4.00, 0.10, 10.00),
        ModelPrice("claude-opus-5-5", 4.00, 5.00, 8.00, 0.20, 20.00),
        ModelPrice("claude-fable-5-1", 10.00, 12.50, 20.00, 0.25, 50.00),
    )

    fun price(model: String): ModelPrice? = MODELS.firstOrNull { it.model == model.trim() }

    /** "~20×" style relation to the cheapest model, for the Adjust sheet. */
    fun relativeCost(model: String): String? {
        val p = price(model) ?: return null
        val base = MODELS.minOf { it.input }
        val ratio = p.input / base
        return if (ratio < 1.5) "1×" else "~${ratio.roundToLong()}×"
    }
}

/** Token and search counts of one API call, each nullable when the provider never reported it. */
data class MeasuredUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheWrite5mTokens: Long? = 0,
    val cacheWrite1hTokens: Long? = 0,
    val cacheReadTokens: Long? = 0,
    val webSearches: Int?,
) {
    val anyUnknown: Boolean get() = inputTokens == null || outputTokens == null || webSearches == null
    val allUnknown: Boolean get() = inputTokens == null && outputTokens == null && webSearches == null
}

/** USD in micro-dollars (1 USD = 1,000,000) so sums never lose cents. */
data class UsdEstimate(val micros: Long, val lowMicros: Long = micros, val highMicros: Long = micros, val priceVersion: String = PricingCatalog.VERSION) {
    val usd: Double get() = micros / 1_000_000.0
    val lowUsd: Double get() = lowMicros / 1_000_000.0
    val highUsd: Double get() = highMicros / 1_000_000.0
    operator fun plus(o: UsdEstimate) = UsdEstimate(micros + o.micros, lowMicros + o.lowMicros, highMicros + o.highMicros, priceVersion)

    companion object {
        val ZERO = UsdEstimate(0)
        fun format(usd: Double): String = when {
            usd <= 0.0 -> "$0.00"
            usd < 0.01 -> "<$0.01"
            else -> String.format(java.util.Locale.US, "$%.2f", usd)
        }
        fun formatRange(low: Double, high: Double): String =
            if (format(low) == format(high)) "~${format(low)}" else "~${format(low)}–${format(high).removePrefix("$").let { "$$it" }}"
    }
}

/**
 * Pure cost arithmetic. Unknown counts contribute nothing but are reported through [Priced.partial]
 * so the UI prefixes "≥" instead of pretending the number is final.
 */
object CostCalculator {
    data class Priced(val estimate: UsdEstimate?, val partial: Boolean, val priceVersion: String = PricingCatalog.VERSION)

    fun price(model: String, usage: MeasuredUsage): Priced {
        val p = PricingCatalog.price(model) ?: return Priced(null, partial = usage.anyUnknown)
        val tier = if (p.longContextThreshold != null && p.longContext != null && (usage.inputTokens ?: 0) > p.longContextThreshold) p.longContext else p
        var micros = 0.0
        micros += perMTok(usage.inputTokens, tier.input)
        micros += perMTok(usage.outputTokens, tier.output)
        micros += perMTok(usage.cacheWrite5mTokens, tier.cacheWrite5m)
        micros += perMTok(usage.cacheWrite1hTokens, tier.cacheWrite1h)
        micros += perMTok(usage.cacheReadTokens, tier.cacheRead)
        micros += (usage.webSearches ?: 0) * PricingCatalog.WEB_SEARCH_USD * 1_000_000
        return Priced(UsdEstimate(micros.roundToLong()), partial = usage.anyUnknown)
    }

    /**
     * Pre-run estimate (design d5): searches at list price, ~6k base + ~11k input tokens per search result
     * set, the mode's output budget; shown as a 0.7×–1.3× range because the token figure is a calibration guess.
     */
    fun estimateRun(model: String, maxSearches: Int, outputTokens: Long, baseInputTokens: Long = 6_000, inputPerSearch: Long = 11_000): UsdEstimate? {
        val p = PricingCatalog.price(model) ?: return null
        val input = baseInputTokens + maxSearches * inputPerSearch
        val tier = if (p.longContextThreshold != null && p.longContext != null && input > p.longContextThreshold) p.longContext else p
        val mid = perMTok(input, tier.input) + perMTok(outputTokens, tier.output) + maxSearches * PricingCatalog.WEB_SEARCH_USD * 1_000_000
        return UsdEstimate(mid.roundToLong(), (mid * 0.7).roundToLong(), (mid * 1.3).roundToLong())
    }

    private fun perMTok(tokens: Long?, usdPerMTok: Double): Double = (tokens ?: 0L) * usdPerMTok
}
