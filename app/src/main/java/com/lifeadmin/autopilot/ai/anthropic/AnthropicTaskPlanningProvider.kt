package com.lifeadmin.autopilot.ai.anthropic

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.PlanSchema
import com.lifeadmin.autopilot.ai.PlanningError
import com.lifeadmin.autopilot.ai.PlanningPrompt
import com.lifeadmin.autopilot.ai.PlanningRequest
import com.lifeadmin.autopilot.ai.PlanningResult
import com.lifeadmin.autopilot.ai.PlanningUsage
import com.lifeadmin.autopilot.ai.RawPlan
import com.lifeadmin.autopilot.ai.TaskPlanningProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Duration

/**
 * Claude Messages API adapter using the official Anthropic Java SDK (HTTPS via OkHttp).
 *
 * - Structured output: `output_config.format` = `json_schema` with [PlanSchema]; the JSON is
 *   still decoded leniently and validated by the app ([com.lifeadmin.autopilot.ai.ProposalValidator]).
 * - `stop_reason` is checked before content: `refusal` and `max_tokens` never reach parsing.
 * - No SDK retries ([AiConfig.SDK_MAX_RETRIES]); the user retries explicitly.
 * - The API key is passed per call from the encrypted store and only lives in the client
 *   instance; it is never logged (SDK logging is off unless ANTHROPIC_LOG is set, which an
 *   Android app process does not have).
 */
class AnthropicTaskPlanningProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val timeout: Duration = AiConfig.REQUEST_TIMEOUT,
) : TaskPlanningProvider {
    override val id: String = "anthropic"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Volatile private var cached: Pair<Int, AnthropicClientAsync>? = null

    /** One client per key (connection pools are reused); replaced when the key changes. */
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

    /** Drops the cached client (e.g. after the key is deleted). */
    fun reset() = synchronized(this) {
        cached?.second?.let { runCatching { it.close() } }
        cached = null
    }

    fun buildParams(request: PlanningRequest, model: String): MessageCreateParams {
        val schema = JsonOutputFormat.Schema.builder()
            .putAllAdditionalProperties(PlanSchema.schema.mapValues { (_, v) -> JsonValue.from(v) })
            .build()
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(AiConfig.MAX_OUTPUT_TOKENS)
            .system(PlanningPrompt.SYSTEM)
            .addUserMessage(PlanningPrompt.userMessage(request))
            .outputConfig(
                OutputConfig.builder()
                    // Explicit: Haiku 5.5 / Opus 5.5 default to medium, Sonnet 5.5 to high.
                    .effort(OutputConfig.Effort.MEDIUM)
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
        if (model in SERVER_FALLBACK_MODELS) {
            // Server-side refusal fallback ("default" routing by refusal category).
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    override suspend fun plan(request: PlanningRequest, apiKey: String, model: String): PlanningResult = try {
        val message = withTimeout(timeout.toMillis() + TIMEOUT_SLACK_MS) {
            client(apiKey).messages().create(buildParams(request, model)).await()
        }
        interpret(message, model)
    } catch (e: TimeoutCancellationException) {
        PlanningResult.Failure(PlanningError.Timeout)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PlanningResult.Failure(mapError(e))
    }

    override suspend fun testConnection(apiKey: String, model: String): PlanningError? = try {
        // GET /v1/models/{id}: authenticates the key and confirms the model, costs no tokens.
        withTimeout(timeout.toMillis() + TIMEOUT_SLACK_MS) { client(apiKey).models().retrieve(model).await() }
        null
    } catch (e: TimeoutCancellationException) {
        PlanningError.Timeout
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        mapError(e)
    }

    internal fun interpret(message: Message, model: String): PlanningResult {
        val stop = message.stopReason().orElse(null)
        when (stop) {
            StopReason.REFUSAL -> return PlanningResult.Failure(
                PlanningError.Refused(message.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse(null)),
            )
            StopReason.MAX_TOKENS, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> return PlanningResult.Failure(PlanningError.Truncated)
            else -> Unit
        }
        val text = message.content().flatMap { block -> block.text().map { listOf(it.text()) }.orElse(emptyList()) }.joinToString("").trim()
        if (text.isEmpty()) return PlanningResult.Failure(PlanningError.Malformed("empty answer"))
        val plan = decode(text) ?: return PlanningResult.Failure(PlanningError.Malformed("not the expected JSON"))
        val usage = runCatching {
            message.usage().let {
                PlanningUsage(
                    it.inputTokens(), it.outputTokens(), model,
                    cacheWriteTokens = it.cacheCreationInputTokens().orElse(0L), cacheReadTokens = it.cacheReadInputTokens().orElse(0L),
                    messageId = runCatching { message.id() }.getOrNull(),
                )
            }
        }.getOrNull()
        return PlanningResult.Success(plan, usage)
    }

    internal fun decode(text: String): RawPlan? {
        val body = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            json.decodeFromString(RawPlan.serializer(), body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    internal fun mapError(e: Throwable): PlanningError = AnthropicErrors.map(e)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private const val TIMEOUT_SLACK_MS = 5_000L

        /** Models that support the server-side `fallbacks: "default"` refusal routing. */
        val SERVER_FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-sonnet-5-5")
    }
}
