package com.lifeadmin.autopilot.ai

import java.time.Duration

/**
 * Central, documented limits for optional AI planning. Every request is user-triggered; these
 * caps bound accidental spend on the user's own Anthropic API key.
 */
object AiConfig {
    /**
     * Cost-conscious default (the M2 brief asks for one). Claude Haiku 5.5 supports structured
     * outputs and costs $0.10 / $0.50 per million input/output tokens; a typical planning
     * request is ~2k input + ~1-2k output tokens, i.e. well under a cent.
     */
    const val DEFAULT_MODEL = "claude-haiku-5-5"

    /** Selectable in AI settings. Ids are exact API strings (no date suffixes). */
    val MODELS: List<ModelOption> = listOf(
        ModelOption(DEFAULT_MODEL, "Claude Haiku 5.5", "Fastest and cheapest (default)"),
        ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5", "More careful planning, ~20× the cost of Haiku"),
        ModelOption("claude-opus-5-5", "Claude Opus 5.5", "Most capable, ~40× the cost of Haiku"),
    )

    /** Hard ceiling per response (includes any thinking tokens). */
    const val MAX_OUTPUT_TOKENS = 6_000L

    /** Longest capture text we send; longer input is rejected locally, never truncated silently. */
    const val MAX_INPUT_CHARS = 2_000

    /** Requests per local day across all AI features. */
    const val MAX_REQUESTS_PER_DAY = 60

    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)

    /** Explicit user-triggered retries only: the SDK must not silently re-send paid requests. */
    const val SDK_MAX_RETRIES = 0

    fun modelOrDefault(raw: String): String = raw.trim().ifEmpty { DEFAULT_MODEL }

    fun label(model: String): String = MODELS.firstOrNull { it.id == model }?.label ?: model
}

data class ModelOption(val id: String, val label: String, val description: String)
