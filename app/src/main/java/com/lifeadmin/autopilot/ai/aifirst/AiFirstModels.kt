package com.lifeadmin.autopilot.ai.aifirst

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Research Online (AI-first), retired: the data a saved research thread keeps, decoded for its
 * read-only screens. What the person approved, what the provider returned and what the app measured. Nothing here is a model's description of itself.
 * Stored as JSON in the existing `investigations` / `investigation_turns` tables (turn kind
 * [AfJson.TURN_KIND]); older M4 turns in the same tables stay readable through the legacy path.
 */

/** What one turn may spend. The search budget goes to the server as `max_uses`; the spending limit is enforced by the app before every call. */
@Serializable
data class AfConfig(
    val model: String,
    val searchBudget: Int,
    val spendLimitCents: Int,
    val maxOutputTokens: Long = DEFAULT_MAX_OUTPUT_TOKENS,
    /** Provider reasoning effort; null = provider default. */
    val effort: String? = null,
) {
    companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 16_000L
    }
}

/** One context line the person left switched on, verbatim. */
@Serializable
data class AfContextLine(val id: String, val label: String, val value: String)

/** The person's answer to the clarification question, sent with the question. */
@Serializable
data class AfClarified(val question: String, val answer: String)

/**
 * The record the person approves. [payload] is the exact user message the research call sends; the
 * engine sends it byte-for-byte and refuses to run when it no longer matches what these fields and
 * the named earlier turns produce. Building one sends nothing.
 */
@Serializable
data class AfAuthorization(
    val question: String,
    val clarified: AfClarified? = null,
    val context: List<AfContextLine> = emptyList(),
    val config: AfConfig,
    /** Earlier turns of this same thread whose question, answer and source list travel with the question. */
    val historyTurnIds: List<String> = emptyList(),
    val promptVersion: Int,
    val payload: String,
    val payloadSha256: String,
    /** Estimate if every authorized search is used and the answer runs long; not a guaranteed maximum. */
    val estimateLowMicros: Long? = null,
    val estimateHighMicros: Long? = null,
    val authorizedAtMs: Long,
) {
    val includesHistory: Boolean get() = historyTurnIds.isNotEmpty()
}

/** The pre-search clarification check (a small no-search call under general AI consent), as it happened. */
@Serializable
data class AfClarification(
    /** True when the check call ran and returned an answer. */
    val checked: Boolean,
    /** The question Claude asked; null when the question was clear (or could not be checked). */
    val question: String? = null,
    val options: List<String> = emptyList(),
    /** The person's answer; null until answered. */
    val answer: String? = null,
    /** The person chose to research the question as asked. */
    val skipped: Boolean = false,
    /** Why the check did not run or did not finish, in plain words. */
    val note: String? = null,
    val model: String? = null,
    val costMicros: Long = 0,
    val costIsFloor: Boolean = false,
) {
    val waitingForAnswer: Boolean get() = question != null && answer == null && !skipped
}

/** A page the provider cited, with the provider's own cited text. Numbered per turn in order of first use. */
@Serializable
data class AfSource(val n: Int, val url: String, val title: String?, val quotes: List<String>, val pageAge: String? = null)

@Serializable
data class AfSearch(val query: String, val results: Int, val error: String? = null)

/** A run of answer text as the provider returned it, with the numbers of the sources it cites (empty = no citation attached). */
@Serializable
data class AfSegment(val text: String, val sources: List<Int> = emptyList())

@Serializable
enum class AfStatus {
    /** Waiting for the person's answer to the clarification question. Nothing paid can start. */
    CLARIFYING,
    /** The authorization card is showing; nothing has been sent for research. */
    READY,
    DECLINED,
    RUNNING,
    COMPLETE,
    TRUNCATED,
    SEARCH_BUDGET_REACHED,
    SPEND_LIMIT_REACHED,
    REFUSED,
    FAILED,
    STOPPED,
    INTERRUPTED,
    ;

    val pending: Boolean get() = this == CLARIFYING || this == READY
    val ran: Boolean get() = this != CLARIFYING && this != READY && this != DECLINED && this != RUNNING
}

@Serializable
data class AfTurn(
    val id: String,
    val index: Int,
    /** The person's words, verbatim. */
    val question: String,
    val createdAtMs: Long,
    val status: AfStatus,
    val clarification: AfClarification? = null,
    /** Set when the person started the research; never before. */
    val authorization: AfAuthorization? = null,
    /** The answer as provider segments, for display with citations. */
    val segments: List<AfSegment> = emptyList(),
    /** The same answer as plain text with `[n]` after every cited passage (used for follow-ups and copying). */
    val answer: String = "",
    val sources: List<AfSource> = emptyList(),
    val searches: List<AfSearch> = emptyList(),
    /** Links Claude wrote itself that no search result supplied: shown as unverified, never as citations. */
    val unsuppliedLinks: List<String> = emptyList(),
    /** Plain-language stop or failure reason; null when it simply finished. */
    val note: String? = null,
    val calls: Int = 0,
    val webSearches: Int = 0,
    val costMicros: Long = 0,
    /** Some usage was not reported (a failure, stop or interruption), so [costMicros] is a floor. */
    val costIsFloor: Boolean = false,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
) {
    val hasAnswer: Boolean get() = answer.isNotBlank()
    /** The research call ran (or was refused before sending) and the turn is final. */
    val ran: Boolean get() = status.ran
}

/** Thread-level data beside the turns. [engine] marks an AI-first thread; its absence means a legacy (M4 or M3A.2) thread. */
@Serializable
data class AfThreadMeta(
    val title: String,
    val firstQuestion: String,
    val associations: List<AfAssociation> = emptyList(),
    val engine: String = AfJson.ENGINE,
)

@Serializable
data class AfAssociation(val atMs: Long, val taskId: String?, val note: String)

data class AfThread(
    val id: String,
    val taskId: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val meta: AfThreadMeta,
    val turns: List<AfTurn>,
) {
    val title: String get() = meta.title
    val latest: AfTurn? get() = turns.maxByOrNull { it.index }
    val running: Boolean get() = turns.any { it.status == AfStatus.RUNNING }
    /** Finished turns with an answer: what a follow-up may carry as history. */
    val answered: List<AfTurn> get() = turns.sortedBy { it.index }.filter { it.ran && it.hasAnswer }
}

object AfJson {
    const val TURN_KIND = "AI_FIRST"
    const val ENGINE = "ai-first"
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeTurn(t: AfTurn): String = json.encodeToString(AfTurn.serializer(), t)
    fun decodeTurn(raw: String): AfTurn? = runCatching { json.decodeFromString(AfTurn.serializer(), raw) }.getOrNull()
    fun encodeMeta(m: AfThreadMeta): String = json.encodeToString(AfThreadMeta.serializer(), m)
    /** Null for a legacy thread's meta (no `engine` field) as well as for unreadable JSON. */
    fun decodeMeta(raw: String): AfThreadMeta? = runCatching {
        if (!json.parseToJsonElement(raw).let { it is kotlinx.serialization.json.JsonObject && it.containsKey("engine") }) return null
        json.decodeFromString(AfThreadMeta.serializer(), raw)
    }.getOrNull()
}
