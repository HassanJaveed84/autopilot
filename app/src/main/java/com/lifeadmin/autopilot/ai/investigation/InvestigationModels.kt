package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.context.FindingDisposition
import com.lifeadmin.autopilot.ai.context.TurnConfig
import com.lifeadmin.autopilot.ai.research2.CallDiagnostic
import com.lifeadmin.autopilot.ai.research2.SourceRef2
import com.lifeadmin.autopilot.ai.research2.StageRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Research Online domain (M4): the objective a person actually has, the claims an answer rests on,
 * the evidence attached to each claim, the state derived from those claims, and the thread of turns
 * that holds all of it. Pure Kotlin, serializable, provider-neutral. Nothing here is keyed by topic
 * or template (RES-010/011): the objective decides what is investigated and what is shown.
 */

// ------------------------------------------------------------------ objective

/** What kind of answer the question asks for. Read from the request's own words, never from its subject. */
@Serializable
enum class AskKind(val label: String) {
    /** A fact, figure, address, date, rule in force. */
    FACT("a fact"),
    /** Steps, documents, how to do something. */
    PROCEDURE("how to do it"),
    /** Two or more named options, even-handed, no winner asked for (RES-050). */
    COMPARE("a comparison"),
    /** A choice between options, or "which should I". */
    RECOMMEND("a recommendation"),
    /** Why or how something works; evidence of benefit. */
    EXPLAIN("an explanation"),
    /** Something that has not happened yet (RES-L). */
    FORECAST("a forecast"),
    /** A yes/no judgment about one option ("is it worth it?"). */
    DECIDE("a judgment"),
    ;

    /** Recommendation asked for: the answer may take a side (RES-050). */
    val wantsRecommendation: Boolean get() = this == RECOMMEND || this == DECIDE
}

@Serializable
data class Constraint(val text: String, val soft: Boolean = false)

/** A detail the objective lacks. [blocking]: the objective cannot even be described without it (RES-025 first case). */
@Serializable
data class Unknown(val text: String, val blocking: Boolean)

/** Asked before any authorization surface exists (RES-025, R-J, R-N(b)). */
@Serializable
data class Clarification(val question: String, val options: List<String> = emptyList())

@Serializable
enum class ObjectiveSource { LOCAL, PROVIDER, USER_EDITED }

/** RES-010: what is being learned or decided, what satisfies it, what bounds it, what is missing. */
@Serializable
data class Objective(
    /** The person's words, verbatim. */
    val question: String,
    /** What is being learned or decided, in one sentence. */
    val text: String,
    val askKind: AskKind,
    val satisfies: List<String> = emptyList(),
    val constraints: List<Constraint> = emptyList(),
    val evidenceNeeds: List<String> = emptyList(),
    val unknowns: List<Unknown> = emptyList(),
    val exclusions: List<String> = emptyList(),
    /** Health, legal, financial, safety: analysis without claiming individual suitability (RES-053). */
    val consequential: Boolean = false,
    val recommendedDepth: ResearchDepth = ResearchDepth.STANDARD,
    val depthReason: String = "",
    /** False while the subject or the objective itself is unclear; then [clarification] is asked first. */
    val established: Boolean = true,
    val clarification: Clarification? = null,
    val preparedBy: ObjectiveSource = ObjectiveSource.LOCAL,
    val preparedWithModel: String? = null,
) {
    /** A missing detail that does not block authorization but is disclosed on it (RES-025 second case). */
    val disclosedGaps: List<Unknown> get() = unknowns.filter { !it.blocking }
}

// ------------------------------------------------------------------ claims and evidence

@Serializable
enum class ClaimType(val label: String) {
    FACT("fact"),
    CALCULATION("calculation"),
    INFERENCE("inference"),
    /** Reasoning over premises; never itself established by sources (RES-041). */
    JUDGMENT("judgment"),
    /** Inherits the status of what it rests on. */
    RECOMMENDATION("recommendation"),
    ;

    /** Only these may carry a citation marker (RES-085). */
    val citable: Boolean get() = this == FACT || this == CALCULATION || this == INFERENCE
    val derived: Boolean get() = this == JUDGMENT || this == RECOMMENDATION
}

/** RES-042. "Established" means adequate for the claim's scope and as-of date, not certainty. */
@Serializable
enum class ClaimStatus {
    ESTABLISHED,
    INDICATIVE,
    CONTESTED,
    NOT_ESTABLISHED,
    DEPENDS_ON_INPUT,
    ;

    /** Good enough to rest a current answer or a task correction on (INT-052). */
    val supports: Boolean get() = this == ESTABLISHED
}

/** One piece of evidence attached to one claim, with enough context to avoid misreading (RES-091). */
@Serializable
data class EvidenceRef(
    /** Registry number of the source. */
    val sourceN: Int,
    val excerpt: String,
    /** What the evidence is about when that differs from the claim's scope (variant, jurisdiction, date). */
    val scopeNote: String = "",
    val retrievedAtMs: Long,
    /** Publisher host: repetitions of one origin count once (RES-045). */
    val originKey: String,
    /** The model pointed at a source the provider cited elsewhere; a pointer, never verification. */
    val pointerOnly: Boolean = false,
)

@Serializable
data class Claim(
    val id: String,
    val text: String,
    val type: ClaimType,
    /** Variant, jurisdiction, population, seller, period the claim is about (RES-044). */
    val scope: String = "",
    /** When the underlying fact was true per the evidence (ISO date, year-month, or free text). */
    val asOf: String? = null,
    val loadBearing: Boolean,
    /** Changes in days (prices, schedules, availability): never reused on a continuation; checked fresh. */
    val volatile: Boolean = false,
    /** Only the person can resolve it (their requirement, situation or decision): a condition, not a gap (RES-062). */
    val dependsOnUser: Boolean = false,
    val support: List<EvidenceRef> = emptyList(),
    val against: List<EvidenceRef> = emptyList(),
    /** Claim ids a judgment or recommendation rests on. */
    val premises: List<String> = emptyList(),
    val status: ClaimStatus = ClaimStatus.NOT_ESTABLISHED,
    val statusReason: String = "",
    /** Turn that produced this claim. */
    val turnId: String,
    /** Set when the claim was carried into this turn from an earlier one (RES-101). */
    val inheritedFromTurnId: String? = null,
    /** Reused as found; not re-checked in this turn. */
    val reused: Boolean = false,
    /** A later turn replaced it; it may be cited as historical only (RES-107). */
    val superseded: Boolean = false,
    val supersededByClaimId: String? = null,
    /** False when the run stopped or failed before this claim was investigated (OD-11, RES-151). */
    val checked: Boolean = true,
) {
    val independentOrigins: Int get() = support.filter { !it.pointerOnly }.map { it.originKey }.distinct().size
}

// ------------------------------------------------------------------ derived state

/** RES-060. Derived from the load-bearing claims, never declared by the model. */
@Serializable
enum class ResultState(val label: String) {
    ANSWERED("Answered"),
    ANSWERED_WITH_CONDITIONS("Answered, with conditions"),
    PARTIALLY_ANSWERED("Partly answered"),
    NOT_ESTABLISHED("Not established"),
    NEEDS_INPUT("Needs your input"),
}

@Serializable
enum class TurnEnding(val label: String) {
    COMPLETE("Finished"),
    BUDGET_SEARCHES("Search budget reached"),
    BUDGET_SPEND("Spending limit reached"),
    STOPPED("Stopped by you"),
    FAILED("The provider failed"),
    INTERRUPTED("Interrupted"),
    ;

    val complete: Boolean get() = this == COMPLETE
}

// ------------------------------------------------------------------ answer composition

/** A run of answer text and the claims whose markers it carried; markers resolve to evidence in one tap (RES-084/088). */
@Serializable
data class AnswerSegment(val text: String, val claimIds: List<String> = emptyList())

@Serializable
enum class SectionKind(val title: String) {
    REASONS("Why"),
    CONDITIONS("Depends on you"),
    COMPARISON("Side by side"),
    OPTIONS("Options"),
    STEPS("Steps"),
    CHANGES("Changed since the earlier answer"),
    CONFIRMED("Still the case"),
    NOT_CHECKED("Not checked before it stopped"),
    UNRESOLVED("What I couldn't establish"),
    MATERIAL("Worth knowing"),
}

@Serializable
data class CompareRow(val criterion: String, val cells: Map<String, AnswerSegment>)

@Serializable
data class AnswerSection(val kind: SectionKind, val items: List<AnswerSegment> = emptyList(), val rows: List<CompareRow> = emptyList(), val options: List<String> = emptyList())

/** Labelled as judgment; premises carry the citations, the recommendation itself never does (RES-051/085). */
@Serializable
data class Recommendation(
    val text: String,
    val reasons: List<AnswerSegment> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val flipConditions: List<String> = emptyList(),
    val claimId: String? = null,
    /** On a neutral comparison the pick is a clearly separate, optional addition (RES-050). */
    val optional: Boolean = false,
)

@Serializable
data class Answer(
    val title: String,
    /** One plain-language line: the state and its most important limitation (RES-081). */
    val stateLine: String,
    val lead: List<AnswerSegment>,
    /** Qualifying caveats that belong in the main answer, never only behind an expander (RES-086). */
    val caveats: List<String> = emptyList(),
    val sections: List<AnswerSection> = emptyList(),
    val recommendation: Recommendation? = null,
)

// ------------------------------------------------------------------ changes, proposals, result

@Serializable
enum class ChangeKind { CHANGED, CONFLICT, CONFIRMED }

/** A continuation's finding about an earlier claim (RES-105–107, INT-071). */
@Serializable
data class ClaimChange(val earlierClaimId: String, val newClaimId: String?, val kind: ChangeKind, val note: String)

@Serializable
data class Unresolved(val topic: String, val why: String)

@Serializable
enum class ProposedKind { STEP, NOTE, VERIFY, CORRECT_STEP }

/** A task change the result proposes; it must rest on claims (INT-041) and only becomes a change through the review. */
@Serializable
data class ProposedChange(
    val id: String,
    val kind: ProposedKind,
    val text: String,
    val note: String? = null,
    val claimIds: List<String>,
    /** The existing step a correction targets, by its wording. */
    val targetStepText: String? = null,
    /** The corrected step was a planning assumption the finding supersedes (INT-052). */
    val supersedesAssumption: Boolean = false,
)

/** Immutable once the turn ends (RES-092). */
@Serializable
data class TurnResult(
    val answer: Answer,
    val state: ResultState,
    val ending: TurnEnding,
    val claims: List<Claim>,
    val sources: List<SourceRef2>,
    val queries: List<String> = emptyList(),
    val changes: List<ClaimChange> = emptyList(),
    val unresolved: List<Unresolved> = emptyList(),
    val proposals: List<ProposedChange> = emptyList(),
    /** Proposals withheld because a premise was not fully checked (OD-8). */
    val heldBackProposals: Int = 0,
    val injectionNoticed: Boolean = false,
    val stages: List<StageRecord> = emptyList(),
    val diagnostics: List<CallDiagnostic> = emptyList(),
    /** Technical failure line, never the prompt or page text. */
    val failure: String? = null,
    val finishedAtMs: Long,
) {
    fun claim(id: String): Claim? = claims.firstOrNull { it.id == id }
    val loadBearing: List<Claim> get() = claims.filter { it.loadBearing && !it.superseded }
    fun source(n: Int): SourceRef2? = sources.firstOrNull { it.n == n }
}

// ------------------------------------------------------------------ thread

@Serializable
enum class InvestigationOrigin { TASK, STANDALONE, DRAFT }

@Serializable
enum class TurnKind { INITIAL, CONTINUATION, RESUME }

@Serializable
enum class TurnState {
    /** Objective being established; no authorization exists. */
    PREPARING,
    CLARIFYING,
    AUTHORIZING,
    DECLINED,
    RUNNING,
    DONE,
    STOPPED,
    FAILED,
    INTERRUPTED,
    ;

    val finished: Boolean get() = this == DONE || this == STOPPED || this == FAILED || this == INTERRUPTED
    val active: Boolean get() = this == RUNNING
}

/** One context item on the authorization card as it was decided (RES-161, OD-4). */
@Serializable
data class ContextRecord(val id: String, val label: String, val value: String, val included: Boolean, val requestOnly: Boolean = false)

@Serializable
data class InheritedRecord(val claimId: String, val text: String, val disposition: FindingDisposition, val reason: String = "")

/** Written before any provider call; the run refuses to start without it (RES-021). */
@Serializable
data class Authorization(
    val approvedAtMs: Long,
    val objectiveText: String,
    val constraints: List<String> = emptyList(),
    val context: List<ContextRecord> = emptyList(),
    val inherited: List<InheritedRecord> = emptyList(),
    val freshChecks: List<String> = emptyList(),
    val config: TurnConfig,
    val presetName: String,
    /** The exact bytes sent and their fingerprint; shown as "What was sent". */
    val payloadJson: String,
    val payloadSha256: String,
    val payloadLines: List<String> = emptyList(),
    val estimateLowMicros: Long? = null,
    val estimateHighMicros: Long? = null,
    val warnings: List<String> = emptyList(),
    /** Disclosed gaps the person may still fill before the first search (RES-025). */
    val disclosedGaps: List<String> = emptyList(),
)

/** Usage of one turn as recorded; unknown portions stay unknown (RES-143/154). */
@Serializable
data class TurnUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val webSearches: Int = 0,
    val elapsedMs: Long = 0,
    val spentMicros: Long = 0,
    /** Some call ended without usage: totals are a floor. */
    val floor: Boolean = false,
    /** A call was in flight when the process died: its usage is unknown. */
    val inFlightUnknown: Boolean = false,
    /** Model actually used per stage (RES-123). */
    val modelsUsed: List<String> = emptyList(),
    val effortUsed: String? = null,
)

/** Everything the legacy M3A.2 session held, kept byte-for-byte for display (RES-092). */
@Serializable
data class LegacyTurn(val sessionContentJson: String, val appliedJson: String = "[]", val runId: String, val outcome: String)

@Serializable
data class Turn(
    val id: String,
    val index: Int,
    val kind: TurnKind,
    val state: TurnState,
    val createdAtMs: Long,
    val objective: Objective,
    val authorization: Authorization? = null,
    val result: TurnResult? = null,
    val usage: TurnUsage = TurnUsage(),
    /** Claim ids the person excluded for this turn; carried into every later turn (OD-4). */
    val excludedClaimIds: List<String> = emptyList(),
    /** The stopped or interrupted turn this one continues (RES-034/153). */
    val resumeOf: String? = null,
    /** Plain-words note on how the turn ended (stop, failure, interruption). */
    val endingNote: String? = null,
    /** Which stage was running when the turn ended; for the resume surface. */
    val stageAtEnd: String? = null,
    val legacy: LegacyTurn? = null,
    /** The detail supplied under this authorization before the first search (RES-025). */
    val suppliedDetails: List<String> = emptyList(),
) {
    val finished: Boolean get() = state.finished
}

@Serializable
data class AssociationEvent(val atMs: Long, val taskId: String?, val note: String)

/** Investigation-level data stored beside the turns. */
@Serializable
data class InvestigationMeta(
    val title: String,
    val firstQuestion: String,
    val associations: List<AssociationEvent> = emptyList(),
    /** Claim ids excluded in any turn: never silently reinserted (OD-4). */
    val excludedClaimIds: List<String> = emptyList(),
    val legacy: Boolean = false,
)

data class Investigation(
    val id: String,
    val origin: InvestigationOrigin,
    val taskId: String?,
    val draftId: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val meta: InvestigationMeta,
    val turns: List<Turn>,
) {
    val title: String get() = meta.title
    val latestTurn: Turn? get() = turns.maxByOrNull { it.index }
    val latestResult: TurnResult? get() = turns.sortedByDescending { it.index }.firstNotNullOfOrNull { it.result }
    val running: Boolean get() = turns.any { it.state == TurnState.RUNNING }
    val linkedToTask: Boolean get() = taskId != null

    /** The claims a continuation can inherit: the latest non-superseded version of every claim. */
    fun currentClaims(): List<Claim> {
        val all = turns.sortedBy { it.index }.flatMap { t -> t.result?.claims.orEmpty() }
        // The latest version of each id wins; an id superseded in any turn stays superseded.
        val latest = LinkedHashMap<String, Claim>()
        for (c in all) latest[c.id] = c
        val superseded = all.filter { it.superseded }.map { it.id }.toSet()
        return latest.values.filter { it.id !in superseded && !it.superseded }
    }
}

object InvestigationJson {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    fun encodeTurn(t: Turn): String = json.encodeToString(Turn.serializer(), t)
    fun decodeTurn(raw: String): Turn? = runCatching { json.decodeFromString(Turn.serializer(), raw) }.getOrNull()
    fun encodeMeta(m: InvestigationMeta): String = json.encodeToString(InvestigationMeta.serializer(), m)
    fun decodeMeta(raw: String): InvestigationMeta? = runCatching { json.decodeFromString(InvestigationMeta.serializer(), raw) }.getOrNull()
    fun encodeObjective(o: Objective): String = json.encodeToString(Objective.serializer(), o)
    fun decodeObjective(raw: String): Objective? = runCatching { json.decodeFromString(Objective.serializer(), raw) }.getOrNull()
}
