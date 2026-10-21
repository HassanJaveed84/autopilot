package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage

/** One Messages API call as the orchestrator wants it; the adapter turns it into SDK params. */
data class ModelCall(
    val model: String,
    val system: String,
    val user: String,
    val maxTokens: Long,
    /** 0 = no web_search tool on this call. */
    val maxSearches: Int,
    /** Mark the system prompt for 5-minute prompt caching (only when another call will reuse it soon). */
    val cacheSystem: Boolean = false,
    /** The paused assistant turn to continue after `pause_turn`; opaque to everything but the adapter. */
    val prior: Any? = null,
    /**
     * How long the adapter waits for THIS call at the HTTP layer (applied per request, so a shared
     * client's default never shortens it). Null = the adapter's default.
     */
    val timeout: java.time.Duration? = null,
)

enum class CallStop { END, PAUSE_TURN, MAX_TOKENS, REFUSAL }

sealed interface CallResult {
    data class Done(
        val segments: List<RawSegment>,
        val searches: List<RawSearch>,
        val usage: MeasuredUsage,
        val stop: CallStop,
        val elapsedMs: Long,
        val providerMessageId: String?,
        /** Pass back as [ModelCall.prior] to continue a `pause_turn`. */
        val paused: Any? = null,
        val refusalCategory: String? = null,
    ) : CallResult

    /** Transport or API failure. [usage] is whatever the provider reported before failing (usually nothing). */
    data class Failed(val error: ResearchError, val searchesSeen: Int, val usage: MeasuredUsage?, val elapsedMs: Long) : CallResult
}

/** Lower-level provider seam for multi-stage research. Implementations never retry on their own. */
interface ResearchCallProvider {
    val id: String
    suspend fun call(call: ModelCall, apiKey: String): CallResult
}
