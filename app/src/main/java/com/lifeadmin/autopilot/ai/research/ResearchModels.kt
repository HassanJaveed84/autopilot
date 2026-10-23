package com.lifeadmin.autopilot.ai.research

import com.lifeadmin.autopilot.ai.AiAvailability

import com.lifeadmin.autopilot.ai.PlanningError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * Limits for consented web research (M3). Every request is started by the user; these caps
 * bound what one tap can cost on the user's own Anthropic key. Counted separately from M2
 * planning ([com.lifeadmin.autopilot.ai.AiConfig.MAX_REQUESTS_PER_DAY]).
 */
object ResearchConfig {
    /** Hard per-request cap handed to the provider as the tool's `max_uses`. */
    const val MAX_SEARCHES_PER_REQUEST = 3

    /** Research requests per local day (a follow-up counts as one). */
    const val MAX_REQUESTS_PER_DAY = 10

    const val MAX_QUESTION_CHARS = 600
    const val MAX_DETAIL_CHARS = 120
    const val MAX_OUTPUT_TOKENS = 4_000L
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)

    /** After `pause_turn` the request is continued at most this many times, within the same search budget. */
    const val MAX_CONTINUATIONS = 1

    /** Briefs kept per task; the oldest beyond this are pruned when a new one is saved. */
    const val MAX_BRIEFS_PER_TASK = 20

    /** Serialized brief content larger than this is trimmed (findings/sources) before saving. */
    const val MAX_PAYLOAD_BYTES = 64_000

    /** A brief older than this shows the "may be out of date" banner. */
    val STALE_AFTER: Duration = Duration.ofHours(72)

    /** Bump when the first-use explainer's terms change so it is shown again. */
    const val EXPLAINER_VERSION = 1

    /** Quoted text shown in the source sheet is cut to this many characters. */
    const val MAX_QUOTE_CHARS = 150

    /** Anthropic server tool identifiers (documented web search tool, version 2025-03-05). */
    const val TOOL_TYPE = "web_search_20250305"
    const val TOOL_NAME = "web_search"
}

enum class ResearchFocus(val label: String) {
    WHAT_TO_DO("What I need to do"),
    COSTS("Costs"),
    DEADLINES("Deadlines"),
    WHERE_HOW("Where & how"),
}

/** "Make it specific" fields. Typed text only; the app never reads a location. */
enum class DetailField(val label: String, val placeholder: String) {
    FROM("From", "Town or station"),
    TO("To", "Town or station"),
    DATE("Date", "e.g. Fri 20 Nov"),
    OPERATOR("Operator or service", "e.g. Northline Rail"),
    JURISDICTION("Country or region", "e.g. United Kingdom"),
}

/** What the user chose to share for one request. The question always goes. */
data class ResearchScope(
    val shareTitle: Boolean = true,
    val shareNotes: Boolean = true,
    val shareSteps: Boolean = false,
    val shareLocale: Boolean = true,
)

/**
 * Exactly what leaves the device for one request, already reduced to the user's selection.
 * Nothing else about the user or their other tasks is available to the provider.
 */
data class ResearchRequest(
    val taskId: String,
    val question: String,
    val focus: Set<ResearchFocus>,
    val details: Map<DetailField, String>,
    /** Null when the user switched the title off for this request. */
    val title: String?,
    val notes: String?,
    val steps: List<String>?,
    /** "Tue 20 Oct 2026 · English (US)" when the user shares date and language, else null. */
    val localeLine: String?,
    val maxSearches: Int = ResearchConfig.MAX_SEARCHES_PER_REQUEST,
) {
    /** Plain-language list of what is sent, for the preflight and the saved brief. */
    fun sharedSummary(): List<String> = buildList {
        if (title != null) add("Task title")
        add("Your question" + if (details.values.any { it.isNotBlank() }) " and details" else "")
        if (notes != null) add("Notes")
        if (steps != null) add("Steps (${steps.size})")
        if (localeLine != null) add("Date and language")
    }
}

// ------------------------------------------------------------------ provider output (raw)

/** A citation the provider attached to a text block: the only thing that may become a source. */
data class RawCitation(val url: String, val title: String?, val citedText: String)

/** One text block of the answer with the structured citations that support it. */
data class RawSegment(val text: String, val citations: List<RawCitation>)

data class RawSearchResult(val url: String, val title: String, val pageAge: String?)

/** One web search the server ran: its query and what it returned (or the tool's error code). */
data class RawSearch(val query: String, val results: List<RawSearchResult>, val errorCode: String? = null)

@Serializable
data class ResearchUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val webSearches: Int,
    val model: String,
    val elapsedMs: Long,
    val continuedOnce: Boolean = false,
)

sealed interface ResearchResponse {
    data class Success(
        val segments: List<RawSegment>,
        val searches: List<RawSearch>,
        val usage: ResearchUsage,
        /** `max_tokens` or context window reached: the brief may be incomplete. */
        val truncated: Boolean,
    ) : ResearchResponse

    data class Failure(val error: ResearchError, val searchesRan: Int) : ResearchResponse
}

/** Coarse, honest stages. Without streaming only Sent, Searching(0), Continuing and Writing occur. */
sealed interface ResearchProgress {
    data object Sent : ResearchProgress
    data class Searching(val count: Int) : ResearchProgress
    data object Continuing : ResearchProgress
    data object Writing : ResearchProgress
}

/** A provider (Anthropic today). Implementations must be cancellable and never retry on their own. */
interface ResearchProvider {
    val id: String
    suspend fun research(request: ResearchRequest, apiKey: String, model: String, onProgress: (ResearchProgress) -> Unit = {}): ResearchResponse
}

/** Every way research can fail. [technical] is the mono footer line; never contains the key. */
sealed class ResearchError(val headline: String, val message: String, val retryable: Boolean, val technical: String) {
    data object Disabled : ResearchError("AI suggestions and research are off", "Turn on AI planning in Settings to research tasks online. Saved research still opens.", false, "AI OFF")
    data object NoApiKey : ResearchError("No API key", "Add your Anthropic API key in Settings → Plan with AI.", false, "NO KEY")
    data object NoConsent : ResearchError("Review the AI privacy note first", "Settings → Plan with AI has the note to accept before the first request.", false, "NO CONSENT")
    data object NoResearchConsent : ResearchError("Before your first web research", "Read the one-time note about web research before starting.", false, "EXPLAINER NOT ACCEPTED")
    data object Offline : ResearchError("You're offline", "Your question is kept — start when you're back online. Saved briefs still open.", true, "OFFLINE · NOTHING SENT")
    data object DailyLimit : ResearchError("Today's research limit is reached", "You've used ${ResearchConfig.MAX_REQUESTS_PER_DAY} of ${ResearchConfig.MAX_REQUESTS_PER_DAY} research requests. It resets at midnight. Saved briefs still open, and planning still works.", false, "DAILY CAP ${ResearchConfig.MAX_REQUESTS_PER_DAY} · NOTHING SENT")
    data object LimitBelowFirstCall : ResearchError("Your spending limit is below the first call", "Nothing was sent: the first search alone would pass the limit you set. Raise the limit or lower the searches, then start again.", false, "SPEND LIMIT < FIRST CALL · NOTHING SENT")
    data object Busy : ResearchError("Research is already running", "Wait for the current research to finish or stop it first.", false, "ONE REQUEST AT A TIME")
    data object EmptyQuestion : ResearchError("What should I find out?", "Type a question first.", false, "EMPTY QUESTION")
    data object QuestionTooLong : ResearchError("That question is too long", "Keep it under ${ResearchConfig.MAX_QUESTION_CHARS} characters.", false, "QUESTION > ${ResearchConfig.MAX_QUESTION_CHARS}")
    data object WebSearchNotEnabled : ResearchError("Web search isn't enabled for this key", "Your key works for planning, but Anthropic said web search is turned off for your organization. Nothing was searched or charged for search.", false, "ANTHROPIC: WEB SEARCH NOT ENABLED (400)")
    data object OutOfCredit : ResearchError("Your Anthropic account needs credit", "Anthropic declined the request because the account's credit balance is too low. Nothing was billed.", false, "ANTHROPIC: CREDIT BALANCE (400)")
    data object Unauthorized : ResearchError("Your API key was rejected", "Anthropic rejected the key (401). Check or replace it in Settings → Plan with AI.", false, "UNAUTHORIZED (401)")
    data object Forbidden : ResearchError("This key can't do that", "The key isn't allowed to use this model or feature, or billing needs attention (402/403).", false, "FORBIDDEN (402/403)")
    data class RateLimited(val retryAfterSeconds: Long?) : ResearchError("Anthropic is busy right now", "Your account hit a rate limit. Nothing was saved, and the app won't retry on its own" + (retryAfterSeconds?.let { " — try again in ${it.coerceAtLeast(1)} s." } ?: " — try again in a minute."), true, "RATE LIMITED (429) · NOT RETRIED AUTOMATICALLY")
    data class ServerError(val status: Int) : ResearchError("Research didn't finish", "Anthropic had a problem ($status). Searches that ran may be billed. Nothing was saved.", true, "SERVER ERROR ($status)")
    data class BadRequest(val status: Int) : ResearchError("The request was rejected", "Anthropic rejected the request ($status). If this persists, try another model in Settings → Plan with AI.", false, "REJECTED ($status)")
    /**
     * The app stopped waiting at the limit configured for THIS call ([seconds]); the legacy M3A flow
     * uses [ResearchConfig.REQUEST_TIMEOUT], the M3A.2 flow a per-call limit from its mode and budget.
     */
    data class Timeout(val seconds: Int = ResearchConfig.REQUEST_TIMEOUT.seconds.toInt()) : ResearchError(
        "Research didn't finish",
        "Anthropic hadn't replied after ${describe(seconds)}, so the app stopped waiting. Anthropic may still have run searches and may bill them. Nothing from this call was saved. Trying again sends a new request and may be billed again.",
        true, "CLIENT TIMEOUT ($seconds S)",
    )

    /** Anthropic reported a timeout on its side (HTTP 504). */
    data object ServerTimeout : ResearchError("Research didn't finish", "Anthropic timed out while working on the request (504). Searches that ran may be billed. Nothing from this call was saved. Trying again sends a new request and may be billed again.", true, "SERVER TIMEOUT (504)")
    data object Network : ResearchError("Connection to Anthropic was lost", "The connection dropped before a reply arrived. Anthropic may still have run searches and may bill them. Nothing from this call was saved. Check your connection; trying again sends a new request.", true, "CONNECTION LOST")
    data class Refused(val category: String?) : ResearchError("Claude couldn't research this", "Claude declined this request" + (category?.let { " ($it)" } ?: "") + ". Nothing was suggested for your task.", false, "STOP REASON: REFUSAL")
    data class Malformed(val detail: String) : ResearchError("The answer couldn't be used", "The response had no usable text ($detail). Nothing was saved.", true, "MALFORMED RESPONSE")

    /**
     * The request succeeded but the reply gave the app nothing to save, and the record says what came
     * back instead (M3A.2 continuation bug): the output limit reached before any text, a paused turn
     * with no searches left, searches without an answer, or an empty reply. A failed continuation never
     * touches the research the person already has.
     */
    data class NoFindings(val why: com.lifeadmin.autopilot.ai.research2.CallClass, val detail: String, val continuation: Boolean) : ResearchError(
        when (why) {
            com.lifeadmin.autopilot.ai.research2.CallClass.TRUNCATED_BEFORE_TEXT -> "The reply hit its output limit before any answer"
            com.lifeadmin.autopilot.ai.research2.CallClass.PAUSED_BEFORE_TEXT -> "Research paused before an answer was written"
            com.lifeadmin.autopilot.ai.research2.CallClass.TOOL_ONLY -> "Searches ran, but no answer came back"
            com.lifeadmin.autopilot.ai.research2.CallClass.EMPTY -> "Claude sent back an empty reply"
            else -> "The answer couldn't be used"
        },
        detail.trimEnd('.') + ". " + if (continuation) "No new findings were saved. Your previous research is unchanged." else "Nothing was saved.",
        true,
        "NO FINDINGS · ${why.name}",
    )
    data class SearchTool(val code: String) : ResearchError("Web search failed", "Anthropic's search tool reported “$code”. Nothing was saved.", true, "SEARCH TOOL ERROR: ${code.uppercase()}")
    data class Stopped(val searchesRan: Int) : ResearchError("Research stopped", "Nothing was saved and your task didn't change. " + if (searchesRan > 0) "$searchesRan ${if (searchesRan == 1) "search" else "searches"} had already run and may be billed." else "Any searches that had already run may be billed.", true, "STOPPED BY YOU · NO BRIEF SAVED")
    data object Interrupted : ResearchError("Research was interrupted", "The app was closed while researching. Nothing was saved; your question is kept.", true, "INTERRUPTED · NOT RE-SENT")
    data class Unexpected(val kind: String) : ResearchError("Something went wrong", "Something unexpected happened ($kind). Nothing was saved.", true, "UNEXPECTED: $kind")

    companion object {
        /** "90 s" / "5 min" / "5 min 30 s" for timeout copy. */
        fun describe(seconds: Int): String = if (seconds < 120) "$seconds s" else "${seconds / 60} min" + (if (seconds % 60 != 0) " ${seconds % 60} s" else "")

        /** Shared failures reuse the planning adapter's classification. */
        fun fromPlanning(e: PlanningError): ResearchError = when (e) {
            PlanningError.Disabled -> Disabled
            PlanningError.NoConsent -> NoConsent
            PlanningError.NoApiKey -> NoApiKey
            PlanningError.Offline -> Offline
            PlanningError.Unauthorized -> Unauthorized
            PlanningError.Forbidden -> Forbidden
            is PlanningError.RateLimited -> RateLimited(e.retryAfter?.seconds)
            is PlanningError.ServerError -> if (e.status == 504) ServerTimeout else ServerError(e.status)
            is PlanningError.BadRequest -> BadRequest(e.status)
            PlanningError.Timeout -> Timeout()
            PlanningError.Network -> Network
            is PlanningError.Refused -> Refused(e.category)
            PlanningError.Truncated -> Malformed("cut off")
            is PlanningError.Malformed -> Malformed(e.detail)
            PlanningError.Cancelled -> Stopped(0)
            is PlanningError.Unexpected -> Unexpected(e.kind)
            PlanningError.EmptyInput -> EmptyQuestion
            PlanningError.InputTooLong -> QuestionTooLong
            PlanningError.DailyLimit -> DailyLimit
        }
    }
}

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

/** Everything the authorization surface needs to decide whether research can start, computed without any request. */
data class ResearchGate(
    val availability: AiAvailability,
    val explainerAccepted: Boolean,
    val requestsToday: Int,
    /** null = unknown; the request is then attempted. */
    val online: Boolean?,
    /** Daily cap this gate enforces (M3A requests or M3A.2 sessions). */
    val cap: Int = ResearchConfig.MAX_REQUESTS_PER_DAY,
) {
    val model: String? get() = (availability as? AiAvailability.Ready)?.model
    val remaining: Int get() = (cap - requestsToday).coerceAtLeast(0)

    /** The first reason research cannot start right now, or null. */
    val blocker: ResearchError?
        get() = when (val a = availability) {
            AiAvailability.Off -> ResearchError.Disabled
            is AiAvailability.NeedsSetup -> if (a.needsConsent) ResearchError.NoConsent else ResearchError.NoApiKey
            is AiAvailability.Ready -> when {
                !explainerAccepted -> ResearchError.NoResearchConsent
                remaining == 0 -> ResearchError.DailyLimit
                online == false -> ResearchError.Offline
                else -> null
            }
        }
}
