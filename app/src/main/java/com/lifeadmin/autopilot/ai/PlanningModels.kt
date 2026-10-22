package com.lifeadmin.autopilot.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * Provider-neutral planning boundary. Nothing in this file (or in the task/reminder modules)
 * depends on Anthropic types; [com.lifeadmin.autopilot.ai.anthropic] is the only adapter that
 * does, so an on-device or proxy-based provider can be swapped in later.
 */

enum class PlanningMode {
    /** Turn free text into a new task proposal. */
    CAPTURE,

    /** Suggest how to move one existing task forward (user-triggered on that task only). */
    FOLLOW_THROUGH,
}

/**
 * Everything the model is told about the user's situation. Deliberately minimal: current local
 * time and zone, the user's quiet hours and default reminder times — never other tasks,
 * contacts, history, notifications or diagnostics.
 */
data class PlanningContext(
    val nowLocal: LocalDateTime,
    val zoneId: String,
    val utcOffset: String,
    val quietHours: Pair<LocalTime, LocalTime>?,
    val defaultReminderTimes: List<LocalTime>,
)

/** The one task a FOLLOW_THROUGH request is about; sent only when the user asks on that task. */
data class TaskSnapshot(
    val title: String,
    val notes: String?,
    val status: String,
    val steps: List<Pair<String, Boolean>>,
    val deadline: String?,
    val reminders: String,
    val ageDays: Long,
    val snoozesLast14Days: Int,
    val waitingFor: String?,
)

data class PlanningRequest(
    val mode: PlanningMode,
    val text: String,
    val context: PlanningContext,
    val task: TaskSnapshot? = null,
)

/**
 * Raw model output, mirroring [PlanSchema]. Every field has a default so partial or slightly
 * different JSON still decodes; [ProposalValidator] decides what is usable.
 */
@Serializable
data class RawPlan(
    val title: String = "",
    val notes: String = "",
    val steps: List<String> = emptyList(),
    @SerialName("next_step") val nextStep: String = "",
    val deadline: RawDeadline = RawDeadline(),
    val reminder: RawReminder = RawReminder(),
    val waiting: RawWaiting = RawWaiting(),
    val priority: RawPriority = RawPriority(),
    val assumptions: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
    @SerialName("ignored_instructions") val ignoredInstructions: List<String> = emptyList(),
    val advice: String = "",
)

@Serializable
data class RawDeadline(
    /** explicit | inferred | unknown */
    val provenance: String = "unknown",
    /** YYYY-MM-DD or "" */
    val date: String = "",
    /** HH:MM (24h) or "" */
    val time: String = "",
    @SerialName("source_phrase") val sourcePhrase: String = "",
)

@Serializable
data class RawReminder(
    /** none | once | daily_times | every_n_days | weekly_days | unsupported */
    val kind: String = "none",
    /** explicit | suggested */
    val provenance: String = "suggested",
    val times: List<String> = emptyList(),
    @SerialName("start_date") val startDate: String = "",
    @SerialName("day_interval") val dayInterval: Int = 1,
    val weekdays: List<String> = emptyList(),
    @SerialName("once_date") val onceDate: String = "",
    @SerialName("once_time") val onceTime: String = "",
    /** For "in 2 hours": minutes from now; 0 when an absolute date/time is given. */
    @SerialName("once_in_minutes") val onceInMinutes: Int = 0,
    @SerialName("requested_phrase") val requestedPhrase: String = "",
)

@Serializable
data class RawWaiting(
    @SerialName("is_waiting") val isWaiting: Boolean = false,
    @SerialName("waiting_for") val waitingFor: String = "",
    @SerialName("check_in_date") val checkInDate: String = "",
    @SerialName("check_in_time") val checkInTime: String = "",
)

@Serializable
data class RawPriority(
    /** none | low | normal | high */
    val value: String = "none",
    val reason: String = "",
)

/** Usage reported by the provider, for the request-cost note shown to the user. */
data class PlanningUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val model: String,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    /** Provider message id, for de-duplicating usage records. */
    val messageId: String? = null,
)

sealed interface PlanningResult {
    data class Success(val plan: RawPlan, val usage: PlanningUsage?) : PlanningResult
    data class Failure(val error: PlanningError) : PlanningResult
}

/** Every way a planning request can fail, each with a plain-language message. */
sealed class PlanningError(val message: String, val retryable: Boolean) {
    data object Disabled : PlanningError("AI planning is turned off. You can turn it on in Settings → AI planning.", false)
    data object NoConsent : PlanningError("Please review the privacy note in Settings → AI planning before the first request.", false)
    data object NoApiKey : PlanningError("No Anthropic API key is set up. Add one in Settings → AI planning.", false)
    data object EmptyInput : PlanningError("Type what you need to do first.", false)
    data object InputTooLong : PlanningError("That's too long for AI planning (max ${AiConfig.MAX_INPUT_CHARS} characters). Shorten it or save it manually.", false)
    data object DailyLimit : PlanningError("Daily AI request limit reached (${AiConfig.MAX_REQUESTS_PER_DAY}). Manual capture still works; the limit resets tomorrow.", false)
    data object Offline : PlanningError("No internet connection. Your text is kept — retry when you're online, or save it without AI.", true)
    data object Unauthorized : PlanningError("Anthropic rejected the API key (401). Check or replace the key in Settings → AI planning.", false)
    data object Forbidden : PlanningError("This API key isn't allowed to use the selected model, or billing needs attention (402/403).", false)
    data class RateLimited(val retryAfter: Duration?) : PlanningError(
        "Anthropic is rate-limiting this key (429)." + (retryAfter?.let { " Try again in ${it.seconds.coerceAtLeast(1)} s." } ?: " Try again shortly."), true,
    )
    data class ServerError(val status: Int) : PlanningError("Anthropic had a problem ($status). Try again in a moment.", true)
    data class BadRequest(val status: Int) : PlanningError("The request was rejected ($status). If this persists, try another model in Settings → AI planning.", false)
    data object Timeout : PlanningError("The request took too long and was stopped. Try again.", true)
    data object Network : PlanningError("Couldn't reach Anthropic. Check your connection and try again.", true)
    data class Refused(val category: String?) : PlanningError("Claude declined to plan this request. You can still save it manually.", false)
    data object Truncated : PlanningError("The answer was cut off before it was complete. Try again, or shorten the text.", true)
    data class Malformed(val detail: String) : PlanningError("The AI answer couldn't be used ($detail). Nothing was saved. Try again.", true)
    data object Cancelled : PlanningError("Cancelled. Nothing was sent to your tasks.", true)
    data class Unexpected(val kind: String) : PlanningError("Something went wrong ($kind). Nothing was saved.", true)
}

/** A provider (Anthropic today; on-device or proxy later). Implementations must be cancellable. */
interface TaskPlanningProvider {
    val id: String
    suspend fun plan(request: PlanningRequest, apiKey: String, model: String): PlanningResult

    /** Cheap authenticated call to verify the key and model; returns null on success. */
    suspend fun testConnection(apiKey: String, model: String): PlanningError?
}
