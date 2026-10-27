package com.lifeadmin.autopilot.ai.usage

import kotlin.math.roundToLong

/**
 * Versioned price table, copied from platform.claude.com/docs/en/about-claude/pricing on 2026-10-20.
 * Built-in AI is retired; the table only prices the usage that saved research recorded, for its
 * read-only screens. Prices are USD per million tokens; web search is USD per 1,000 searches.
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

    private fun perMTok(tokens: Long?, usdPerMTok: Double): Double = (tokens ?: 0L) * usdPerMTok
}
