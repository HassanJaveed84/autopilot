package com.lifeadmin.autopilot.domain.proposal

/**
 * Case-, whitespace- and punctuation-insensitive key for "is this already on the task?" checks
 * (ChatGPT handoff steps and notes); the same normalisation the retired planning validator used.
 */
object MatchKey {
    fun of(s: String): String = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
