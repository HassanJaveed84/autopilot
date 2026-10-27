package com.lifeadmin.autopilot.ai

/** Display names for the Claude models that saved research (from the retired built-in AI) records. */
object ModelNames {
    private val LABELS = mapOf(
        "claude-haiku-5-5" to "Claude Haiku 5.5",
        "claude-sonnet-5-5" to "Claude Sonnet 5.5",
        "claude-opus-5-5" to "Claude Opus 5.5",
    )

    /** The friendly name of a known model id, otherwise the id as stored. */
    fun label(model: String): String = LABELS[model.trim()] ?: model
}
