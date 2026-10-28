package com.lifeadmin.autopilot.ai.research

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration

/** Limits for saved M3A research briefs, which stay readable now that built-in research is retired. */
object ResearchConfig {
    /** Briefs kept per task; the oldest beyond this are pruned when one is imported. */
    const val MAX_BRIEFS_PER_TASK = 20

    /** Serialized brief content larger than this is trimmed (findings/sources) before saving. */
    const val MAX_PAYLOAD_BYTES = 64_000

    /** A brief older than this shows the "may be out of date" banner. */
    val STALE_AFTER: Duration = Duration.ofHours(72)

    const val MAX_QUESTION_CHARS = 600

    /** Quoted text shown in the source sheet is cut to this many characters. */
    const val MAX_QUOTE_CHARS = 150
}

// ------------------------------------------------------------------ saved brief

@Serializable
data class ResearchUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val webSearches: Int,
    val model: String,
    val elapsedMs: Long,
    val continuedOnce: Boolean = false,
)

// ------------------------------------------------------------------ the brief (persisted)

@Serializable
enum class SourceTier {
    /** Government domain, or the domain of the operator/service the user named. Never guessed. */
    OFFICIAL,

    /** Any other page. We never claim "secondary" or "community" without evidence. */
    WEB,
}

@Serializable
data class SourceRef(
    val n: Int,
    val url: String,
    val title: String,
    val domain: String,
    val tier: SourceTier,
    /** Page age as reported by the search tool ("September 2026"), when available. */
    val pageAge: String? = null,
    /** The provider's cited text, trimmed, for the source sheet. */
    val quotes: List<String> = emptyList(),
    /** How many sentences in the brief cite this source (0 = read while searching, not cited). */
    val citedCount: Int = 0,
)

/** A run of answer text with the sources (by number) the provider attached to it. */
@Serializable
data class Segment(val text: String, val refs: List<Int> = emptyList())

@Serializable
data class Side(val ref: Int, val says: String)

/** What a finding is made of decides how it is drawn and worded; see the design's provenance types. */
@Serializable
sealed class Finding {
    @Serializable @SerialName("sourced")
    data class Sourced(val text: String, val refs: List<Int>, val tier: SourceTier) : Finding()

    @Serializable @SerialName("disagree")
    data class Disagree(val topic: String, val sides: List<Side>, val advice: String) : Finding()

    @Serializable @SerialName("unconfirmed")
    data class Unconfirmed(val topic: String, val note: String) : Finding()

    /** Model reasoning without a source. Never carries a citation; never the basis for a deadline. */
    @Serializable @SerialName("suggestion")
    data class Suggestion(val text: String) : Finding()
}

@Serializable
enum class MoveKind { STEP, NOTE, CHECK }

@Serializable
data class NextMove(
    val id: String,
    val kind: MoveKind,
    val text: String,
    val basis: String,
    val refs: List<Int> = emptyList(),
)

@Serializable
data class BriefContent(
    val quickAnswer: List<Segment>,
    val moves: List<NextMove>,
    val findings: List<Finding>,
    val sources: List<SourceRef>,
    /** The queries the server ran, in order. */
    val queries: List<String>,
    val usage: ResearchUsage,
    /** False when the response carried no structured citation at all: everything is a suggestion. */
    val verified: Boolean,
    val truncated: Boolean,
    /** Text on a page tried to give instructions; it was treated as data and dropped from moves. */
    val injectionNoticed: Boolean,
    /** What was shared, in plain words, as shown on the preflight. */
    val shared: List<String>,
)

@Serializable
data class AppliedMove(val moveId: String, val atEpochMs: Long, val stepTitle: String? = null, val stepId: String? = null)
