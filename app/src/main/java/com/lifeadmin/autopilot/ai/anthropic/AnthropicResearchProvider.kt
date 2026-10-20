package com.lifeadmin.autopilot.ai.anthropic

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.WebSearchTool20250305
import com.fasterxml.jackson.core.type.TypeReference
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.PlanningError
import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchProgress
import com.lifeadmin.autopilot.ai.research.ResearchPrompt
import com.lifeadmin.autopilot.ai.research.ResearchProvider
import com.lifeadmin.autopilot.ai.research.ResearchRequest
import com.lifeadmin.autopilot.ai.research.ResearchResponse
import com.lifeadmin.autopilot.ai.research.ResearchUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import java.time.Duration

/**
 * Claude Messages API adapter for consented web research, using the official SDK's server-side
 * `web_search_20250305` tool.
 *
 * - `max_uses` is set on every request to the user's per-request budget; the server enforces it.
 * - No `user_location`: the app never reads or sends a location.
 * - `pause_turn` is continued at most [ResearchConfig.MAX_CONTINUATIONS] times with the budget that
 *   remains; the search cap therefore holds across the whole run.
 * - Sources come only from the response's structured citations and search results. Text is
 *   returned as cited segments for [com.lifeadmin.autopilot.ai.research.BriefParser].
 * - No SDK retries; the user retries explicitly. The key lives only in the client instance.
 */
class AnthropicResearchProvider(
    private val baseUrl: String = AnthropicTaskPlanningProvider.DEFAULT_BASE_URL,
    private val timeout: Duration = ResearchConfig.REQUEST_TIMEOUT,
    private val nanoTime: () -> Long = System::nanoTime,
) : ResearchProvider {
    override val id: String = "anthropic-web-search"

    @Volatile private var cached: Pair<Int, AnthropicClientAsync>? = null

    private fun client(apiKey: String): AnthropicClientAsync {
        val fingerprint = apiKey.hashCode()
        cached?.let { (fp, c) -> if (fp == fingerprint) return c }
        synchronized(this) {
            cached?.let { (fp, c) -> if (fp == fingerprint) return c }
            cached?.second?.let { runCatching { it.close() } }
            val c = AnthropicOkHttpClientAsync.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .maxRetries(AiConfig.SDK_MAX_RETRIES)
                .timeout(timeout)
                .build()
            cached = fingerprint to c
            return c
        }
    }

    /** Shared client for the M3A.2 call adapter (same key handling and connection pool). */
    internal fun clientFor(apiKey: String): AnthropicClientAsync = client(apiKey)

    fun reset() = synchronized(this) {
        cached?.second?.let { runCatching { it.close() } }
        cached = null
    }

    /** [prior] is the paused assistant turn to continue; [maxUses] the searches still allowed. */
    fun buildParams(request: ResearchRequest, model: String, maxUses: Int, prior: Message? = null): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ResearchConfig.MAX_OUTPUT_TOKENS)
            .system(ResearchPrompt.SYSTEM)
            .addUserMessage(ResearchPrompt.userMessage(request))
            .addTool(WebSearchTool20250305.builder().maxUses(maxUses.coerceAtLeast(1).toLong()).build())
        prior?.let { builder.addMessage(it) }
        return builder.build()
    }

    override suspend fun research(
        request: ResearchRequest,
        apiKey: String,
        model: String,
        onProgress: (ResearchProgress) -> Unit,
    ): ResearchResponse {
        val started = nanoTime()
        val absorbed = Absorbed()
        try {
            onProgress(ResearchProgress.Sent)
            var message = call(apiKey, buildParams(request, model, request.maxSearches))
            absorbed.add(message)
            var continued = false
            var continuations = 0
            while (message.stopReason().orElse(null) == StopReason.PAUSE_TURN) {
                val remaining = request.maxSearches - absorbed.searchCount
                if (continuations >= ResearchConfig.MAX_CONTINUATIONS || remaining <= 0) break
                onProgress(ResearchProgress.Searching(absorbed.searchCount))
                onProgress(ResearchProgress.Continuing)
                message = call(apiKey, buildParams(request, model, remaining, prior = message))
                absorbed.add(message)
                continued = true
                continuations++
            }
            onProgress(ResearchProgress.Writing)
            return interpret(message, absorbed, model, elapsedMs(started), continued)
        } catch (e: TimeoutCancellationException) {
            return ResearchResponse.Failure(ResearchError.Timeout, absorbed.searchCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ResearchResponse.Failure(mapError(e), absorbed.searchCount)
        }
    }

    private suspend fun call(apiKey: String, params: MessageCreateParams): Message =
        withTimeout(timeout.toMillis() + TIMEOUT_SLACK_MS) { client(apiKey).messages().create(params).await() }

    private fun elapsedMs(startedNanos: Long): Long = ((nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    /** Content, searches and usage gathered across the first response and any continuation. */
    internal class Absorbed {
        val segments = ArrayList<RawSegment>()
        val searches = ArrayList<RawSearch>()
        var inputTokens = 0L
        var outputTokens = 0L
        var reportedSearches = 0L
        private val pendingQueries = LinkedHashMap<String, String>()

        val searchCount: Int get() = maxOf(reportedSearches.toInt(), searches.size)

        fun add(message: Message) {
            // Each response carries only its own blocks; a continuation's blocks are appended in order.
            for (block in message.content()) {
                block.serverToolUse().orElse(null)?.let { use ->
                    if (use.name().toString() == ResearchConfig.TOOL_NAME) pendingQueries[use.id()] = queryOf(use._input())
                    return@let
                }
                block.webSearchToolResult().orElse(null)?.let { result ->
                    val query = pendingQueries.remove(result.toolUseId()) ?: ""
                    val content = result.content()
                    val error = content.error().orElse(null)
                    if (error != null) {
                        searches += RawSearch(query, emptyList(), error.errorCode().toString())
                    } else {
                        val results = content.resultBlocks().orElse(emptyList()).map { r ->
                            RawSearchResult(r.url(), r.title(), r.pageAge().orElse(null))
                        }
                        searches += RawSearch(query, results)
                    }
                    return@let
                }
                block.text().orElse(null)?.let { text ->
                    val citations = text.citations().orElse(emptyList()).mapNotNull { c ->
                        c.webSearchResultLocation().orElse(null)?.let { loc ->
                            RawCitation(loc.url(), loc.title().orElse(null), loc.citedText())
                        }
                    }
                    segments += RawSegment(text.text(), citations)
                }
            }
            // Searches the server started but never answered still count as run.
            pendingQueries.values.forEach { searches += RawSearch(it, emptyList(), "no_result") }
            pendingQueries.clear()
            runCatching {
                val u = message.usage()
                inputTokens += u.inputTokens()
                outputTokens += u.outputTokens()
                reportedSearches += u.serverToolUse().orElse(null)?.webSearchRequests() ?: 0L
            }
        }

        private fun queryOf(input: com.anthropic.core.JsonValue): String = runCatching {
            input.convert(object : TypeReference<Map<String, Any?>>() {})?.get("query")?.toString()
        }.getOrNull()?.trim().orEmpty()
    }

    internal fun interpret(message: Message, absorbed: Absorbed, model: String, elapsedMs: Long, continued: Boolean): ResearchResponse {
        val stop = message.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) {
            val category = message.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse(null)
            return ResearchResponse.Failure(ResearchError.Refused(category), absorbed.searchCount)
        }
        val truncated = stop == StopReason.MAX_TOKENS || stop == StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED || stop == StopReason.PAUSE_TURN
        val textLength = absorbed.segments.sumOf { it.text.trim().length }
        if (textLength == 0) {
            // A failed search tool with no answer is the tool's failure; otherwise the answer is unusable.
            val toolError = absorbed.searches.firstNotNullOfOrNull { it.errorCode }
            return if (toolError != null && toolError != "no_result") {
                ResearchResponse.Failure(ResearchError.SearchTool(toolError), absorbed.searchCount)
            } else {
                ResearchResponse.Failure(ResearchError.Malformed(if (truncated) "cut off before any answer" else "empty answer"), absorbed.searchCount)
            }
        }
        val usage = ResearchUsage(absorbed.inputTokens, absorbed.outputTokens, absorbed.searchCount, model, elapsedMs, continued)
        return ResearchResponse.Success(absorbed.segments.toList(), absorbed.searches.toList(), usage, truncated)
    }

    internal fun mapError(e: Throwable): ResearchError {
        val planning = AnthropicErrors.map(e)
        if (planning is PlanningError.BadRequest || planning == PlanningError.Forbidden) {
            val body = AnthropicErrors.bodyText(e)
            if (body.contains("credit balance") || body.contains("insufficient credit") || body.contains("purchase credits")) return ResearchError.OutOfCredit
            if ((body.contains("web search") || body.contains("web_search")) &&
                (body.contains("not enabled") || body.contains("disabled") || body.contains("not available") || body.contains("not permitted") || body.contains("not allowed"))
            ) return ResearchError.WebSearchNotEnabled
        }
        return ResearchError.fromPlanning(planning)
    }

    companion object {
        private const val TIMEOUT_SLACK_MS = 5_000L
    }
}

// ------------------------------------------------------------------ M3A.2 structured call

/**
 * Generic single Messages call for the multi-stage orchestrator. Same client, caps and error
 * mapping as [AnthropicResearchProvider.research]; `pause_turn` is handed back to the caller so every
 * HTTP call is accounted for separately. System prompt caching is 5-minute ephemeral when asked.
 */
class AnthropicResearchCalls(
    private val base: AnthropicResearchProvider,
    private val timeout: Duration = com.lifeadmin.autopilot.ai.research2.Research2Config.CALL_TIMEOUT,
    private val nanoTime: () -> Long = System::nanoTime,
) : com.lifeadmin.autopilot.ai.research2.ResearchCallProvider {
    override val id: String = "anthropic-web-search-v2"

    fun buildParams(call: com.lifeadmin.autopilot.ai.research2.ModelCall): MessageCreateParams {
        val builder = MessageCreateParams.builder().model(call.model).maxTokens(call.maxTokens)
        if (call.cacheSystem) {
            builder.systemOfTextBlockParams(
                listOf(
                    com.anthropic.models.messages.TextBlockParam.builder().text(call.system)
                        .cacheControl(com.anthropic.models.messages.CacheControlEphemeral.builder().ttl(com.anthropic.models.messages.CacheControlEphemeral.Ttl.TTL_5M).build())
                        .build(),
                ),
            )
        } else {
            builder.system(call.system)
        }
        builder.addUserMessage(call.user)
        if (call.maxSearches > 0) builder.addTool(WebSearchTool20250305.builder().maxUses(call.maxSearches.toLong()).build())
        (call.prior as? Message)?.let { builder.addMessage(it) }
        return builder.build()
    }

    override suspend fun call(call: com.lifeadmin.autopilot.ai.research2.ModelCall, apiKey: String): com.lifeadmin.autopilot.ai.research2.CallResult {
        val started = nanoTime()
        fun elapsed() = ((nanoTime() - started) / 1_000_000L).coerceAtLeast(0)
        val absorbed = AnthropicResearchProvider.Absorbed()
        try {
            val message = withTimeout(timeout.toMillis() + 5_000L) { base.clientFor(apiKey).messages().create(buildParams(call)).await() }
            absorbed.add(message)
            val u = runCatching { message.usage() }.getOrNull()
            val usage = com.lifeadmin.autopilot.ai.usage.MeasuredUsage(
                inputTokens = u?.inputTokens(),
                outputTokens = u?.outputTokens(),
                cacheWrite5mTokens = u?.cacheCreation()?.orElse(null)?.ephemeral5mInputTokens() ?: u?.cacheCreationInputTokens()?.orElse(null) ?: 0L,
                cacheWrite1hTokens = u?.cacheCreation()?.orElse(null)?.ephemeral1hInputTokens() ?: 0L,
                cacheReadTokens = u?.cacheReadInputTokens()?.orElse(null) ?: 0L,
                // Measured searches: the server's own count when present, else the result blocks seen; never max_uses.
                webSearches = u?.serverToolUse()?.orElse(null)?.webSearchRequests()?.toInt() ?: absorbed.searches.count { it.errorCode == null },
            )
            val stopReason = message.stopReason().orElse(null)
            val stop = when (stopReason) {
                StopReason.PAUSE_TURN -> com.lifeadmin.autopilot.ai.research2.CallStop.PAUSE_TURN
                StopReason.MAX_TOKENS, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> com.lifeadmin.autopilot.ai.research2.CallStop.MAX_TOKENS
                StopReason.REFUSAL -> com.lifeadmin.autopilot.ai.research2.CallStop.REFUSAL
                else -> com.lifeadmin.autopilot.ai.research2.CallStop.END
            }
            val category = if (stop == com.lifeadmin.autopilot.ai.research2.CallStop.REFUSAL) message.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse(null) else null
            return com.lifeadmin.autopilot.ai.research2.CallResult.Done(
                absorbed.segments.toList(), absorbed.searches.toList(), usage, stop, elapsed(), runCatching { message.id() }.getOrNull(),
                paused = if (stop == com.lifeadmin.autopilot.ai.research2.CallStop.PAUSE_TURN) message else null, refusalCategory = category,
            )
        } catch (e: TimeoutCancellationException) {
            return com.lifeadmin.autopilot.ai.research2.CallResult.Failed(ResearchError.Timeout, absorbed.searchCount, null, elapsed())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return com.lifeadmin.autopilot.ai.research2.CallResult.Failed(base.mapError(e), absorbed.searchCount, null, elapsed())
        }
    }
}
