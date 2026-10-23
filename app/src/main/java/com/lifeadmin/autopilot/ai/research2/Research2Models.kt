package com.lifeadmin.autopilot.ai.research2

import kotlinx.serialization.Serializable

/*
 * M3A.2 domain: everything the research assistant reasons about, persists and shows. Pure Kotlin,
 * serializable, no Android or SDK types. Provenance is decided by the parser from provider citations;
 * these types only carry the result.
 */

/** The shape of the deliverable follows the question (design d1). */
@Serializable
enum class DeliverableTemplate(val letter: String, val label: String) {
    QUICK_ANSWER("A", "Quick answer"),
    SHORTLIST("B", "Shortlist & compare"),
    PICK_PER_ITEM("C", "Pick per item"),
    HOW_TO("D", "How-to steps"),
    CHECKLIST("E", "Checklist"),
}

@Serializable
enum class RequirementType(val label: String) {
    /** A thing to find: a product category, a fact, a place. */
    ITEM("Item"),

    /** A bound the answer must respect: budget, region, brand, exclusion. */
    CONSTRAINT("Constraint"),

    /** The output the person asked for: a list, one step per item, a comparison. */
    OUTPUT("Output wish"),

    /** Typed by the user on the preflight. */
    USER_ADDED("Added by you"),
}

@Serializable
enum class RequirementOrigin { EXTRACTED, USER }

/**
 * One thing the research must account for. [id] is assigned once in the preflight and never changes,
 * so coverage, proposals and continuation refer to the same requirement across runs.
 */
@Serializable
data class Requirement(
    val id: String,
    val type: RequirementType,
    val label: String,
    val origin: RequirementOrigin = RequirementOrigin.EXTRACTED,
    /** False when the local extractor was unsure; the preflight says so. */
    val confident: Boolean = true,
    /**
     * A preference the person allows exceptions to ("brand preference", "ideally under $150"). A
     * violation of a soft constraint is shown as an exception for the person to decide on; a violation
     * of a strict one keeps the research from being complete. See [CompletionPolicy].
     */
    val soft: Boolean = false,
    /**
     * A follow-up question typed on the Continue sheet. It is the primary objective of that
     * continuation and is tracked like any requirement, but never gets a generated proposal of its own.
     */
    val followUp: Boolean = false,
)

/**
 * Completion semantics (validated in code by [CompletionPolicy], never taken from the model alone):
 *
 * | status            | investigated | evidence supports an answer | satisfied for "Complete" |
 * |-------------------|--------------|-----------------------------|--------------------------|
 * | PENDING           | no           | —                           | no (a gap)               |
 * | NOT_FOUND         | yes          | nothing found               | no (a gap)               |
 * | PARTLY            | yes          | only part of it             | no (a gap)               |
 * | NEEDS_CHECK       | yes          | not enough to rely on       | no (a gap)               |
 * | YOUR_CALL         | yes          | options established         | yes — the person decides |
 * | ANSWERED          | yes          | yes, cited                  | yes                      |
 * | RESOLVED_CONFLICT | yes          | yes, conflict resolved      | yes                      |
 */
@Serializable
enum class RequirementStatus(val label: String) {
    PENDING("Not researched yet"),
    ANSWERED("Answered"),
    YOUR_CALL("Your call"),
    NEEDS_CHECK("Needs a check"),
    PARTLY("Partly"),
    NOT_FOUND("Not found"),
    RESOLVED_CONFLICT("Resolved conflict"),
    ;

    /** Satisfied for the completeness rule: answered, a resolved conflict, or a decision with its options on the table. */
    val covered: Boolean get() = this == ANSWERED || this == RESOLVED_CONFLICT || this == YOUR_CALL

    /** Still a research gap: another search could change it. */
    val open: Boolean get() = this == PENDING || this == NOT_FOUND || this == PARTLY || this == NEEDS_CHECK

    /** The research reported on it (even if it found nothing). */
    val investigated: Boolean get() = this != PENDING

    /** Shown as "needs you" on the coverage line. */
    val needsUser: Boolean get() = this == YOUR_CALL || this == NEEDS_CHECK
}

/**
 * [refs] are sources the provider cited on this requirement's line; [reusedRefs] are source ids the
 * model typed itself (`src=sN`) that point at sources cited earlier — a pointer, not verification.
 */
@Serializable
data class Coverage(val requirementId: String, val status: RequirementStatus, val summary: String = "", val refs: List<Int> = emptyList(), val reusedRefs: List<Int> = emptyList())

/** What a claim rests on. Decided by the parser: a sourced tag without a provider citation becomes UNKNOWN. */
@Serializable
enum class ProvenanceTag(val label: String, val short: String) {
    FACT("Fact from a cited page", "Fact"),
    REVIEWS("Independent reviews", "Reviews"),
    COMMUNITY("Community reports", "Community"),
    JUDGMENT("Claude's judgment", "Judgment"),
    CALC("Calculated", "Calc"),
    UNKNOWN("Unconfirmed", "Unknown"),

    /**
     * Written by the model with a `src=` pointer to a source the provider cited earlier, but this
     * particular sentence was not itself cited and does not match the evidence held. Restated, not verified.
     */
    SYNTHESIS("Restated from earlier evidence, not verified", "Restated"),
    ;

    /** Tags that assert something about the world and therefore need a citation. */
    val needsSource: Boolean get() = this == FACT || this == REVIEWS || this == COMMUNITY
}

@Serializable
data class Attribute(
    val key: String,
    val value: String,
    val tag: ProvenanceTag,
    val refs: List<Int> = emptyList(),
    val reusedRefs: List<Int> = emptyList(),
) {
    /** Backed by a provider citation (or a validated restatement of cited evidence). */
    val verified: Boolean get() = refs.isNotEmpty() && tag.needsSource
}

@Serializable
enum class PriceStatus(val label: String) {
    CONFIRMED("Confirmed"),
    CONFLICT_RESOLVED("Sources differed"),
    NOT_CONFIRMED("Not confirmed"),
}

/** A price is confirmed only when its line cites a page; the retrieval time is the run's own time. */
@Serializable
data class Price(
    val amount: Double,
    val currency: String,
    val retailer: String? = null,
    val size: String? = null,
    val status: PriceStatus,
    val refs: List<Int> = emptyList(),
    val retrievedAtEpochMs: Long? = null,
) {
    fun display(): String {
        val whole = amount == Math.floor(amount)
        val num = if (whole) String.format(java.util.Locale.US, "%,.0f", amount) else String.format(java.util.Locale.US, "%,.2f", amount)
        return when (currency.uppercase()) { "USD" -> "$$num"; "GBP" -> "£$num"; "EUR" -> "€$num"; else -> "$num ${currency.uppercase()}" }
    }
}

/** A distinct product/option (template B). Variants are separate candidates when they differ materially. */
@Serializable
data class Candidate(
    val id: String,
    val name: String,
    val variant: String? = null,
    /** Taste/style family for diversity ("fresh", "woody", "sweet"); free text from the model. */
    val style: String? = null,
    val included: Boolean = true,
    /** Why it is in (or out of) the shortlist. */
    val reason: String = "",
    val attrs: List<Attribute> = emptyList(),
    val price: Price? = null,
    val refs: List<Int> = emptyList(),
    /** True when the model named it as the pick for someone whose taste is unknown. */
    val hero: Boolean = false,
    val reusedRefs: List<Int> = emptyList(),
)

/** Template C: what was found for one requested item. */
@Serializable
data class ItemPick(
    val requirementId: String,
    val status: RequirementStatus,
    val pick: String? = null,
    val variant: String? = null,
    /** What the product label says (Fact when cited). */
    val labelFacts: List<Attribute> = emptyList(),
    val alternative: String? = null,
    /** Always a question for a pharmacist/clinician, never a dose. */
    val forYou: String? = null,
    val why: String? = null,
    val price: Price? = null,
    val refs: List<Int> = emptyList(),
    val reusedRefs: List<Int> = emptyList(),
)

@Serializable
data class Claim(val text: String, val tag: ProvenanceTag, val refs: List<Int> = emptyList(), val reusedRefs: List<Int> = emptyList())

@Serializable
data class ConflictSide(val says: String, val refs: List<Int> = emptyList(), val reusedRefs: List<Int> = emptyList())

@Serializable
data class Conflict(val topic: String, val sides: List<ConflictSide>, val resolution: String? = null)

@Serializable
data class StepLine(val text: String, val tag: ProvenanceTag = ProvenanceTag.JUDGMENT, val refs: List<Int> = emptyList(), val optional: Boolean = false, val reusedRefs: List<Int> = emptyList())

@Serializable
enum class CalloutKind { HEALTH, CROSS_ITEM, UNKNOWN_RECIPIENT, BUDGET, INFO }

@Serializable
data class Callout(val kind: CalloutKind, val text: String)

@Serializable
data class Unresolved(val topic: String, val why: String, val requirementId: String? = null)

/**
 * A run of answer text with the registry numbers of the sources the provider attached to it.
 * [reusedRefs] are model-typed pointers to earlier-cited sources (shown as restatements, never as verification).
 */
@Serializable
data class CitedSegment(val text: String, val refs: List<Int> = emptyList(), val reusedRefs: List<Int> = emptyList())

@Serializable
enum class ProposalKind(val label: String, val verb: String) {
    STEP("Add step", "Add"),
    DECIDE("Decide", "Decide"),
    CHECK("Check", "Check"),
    NOTE("Add note", "Note"),
    RESEARCH("Research more", "Research"),
}

/**
 * A proposed task change, separately reviewable and editable. Never applied without the user; never a
 * deadline, reminder, priority or status change (M3A rule kept).
 */
@Serializable
data class Proposal(
    val id: String,
    val kind: ProposalKind,
    val title: String,
    val note: String? = null,
    val requirementId: String? = null,
    val refs: List<Int> = emptyList(),
    /** When the evidence behind it was checked (the run's time). */
    val checkedAtEpochMs: Long,
    /** Pre-selected on the proposals screen (sourced steps yes; checks and research no). */
    val selectedByDefault: Boolean = true,
    /** Proposals the model wrote vs. ones the app added so every requested item is represented. */
    val generated: Boolean = false,
    val reusedRefs: List<Int> = emptyList(),
)

@Serializable
enum class SessionOutcome(val label: String, val success: Boolean) {
    COMPLETE("Complete", true),
    PARTIAL("Partially complete", false),
    INCOMPLETE_EVIDENCE("Incomplete · not enough evidence", false),
    INCOMPLETE_BUDGET("Incomplete · budget used up", false),
    STOPPED("Stopped by you", false),
    INTERRUPTED("Interrupted", false),
    FAILED("Failed", false),
}

@Serializable
enum class ResearchMode(val label: String) { QUICK("Quick"), THOROUGH("Thorough"), DEEP("Deep"), CUSTOM("Custom") }

@Serializable
enum class StageKind(val label: String) {
    GATHER("Searching and gathering evidence"),
    FILL_GAPS("Checking what the answer rests on"),
    CROSS_CHECK("Reconciling sources"),
    SYNTHESIZE("Writing the result"),
}

@Serializable
enum class StageResult { DONE, SKIPPED, FAILED, STOPPED, BUDGET }

/** One stage of one run, as it really happened. Only real orchestration events are recorded. */
@Serializable
data class StageRecord(
    val kind: StageKind,
    val result: StageResult,
    val searchesAllowed: Int,
    val searchesRun: Int,
    val queries: List<String> = emptyList(),
    val note: String? = null,
    val calls: Int = 0,
    /** One privacy-safe diagnostic per HTTP call of this stage (counts and kinds only). */
    val callLog: List<CallDiagnostic> = emptyList(),
)

/**
 * What one reply amounted to, decided in code after parsing. Shown in the run log and in the
 * failure screen so a real-device problem can be read off the record instead of guessed.
 */
@Serializable
enum class CallClass(val label: String) {
    /** Contract blocks parsed and merged. */
    STRUCTURED("usable blocks"),
    /** No contract headings at all; the text was kept as the answer. */
    FREE_FORM("free-form text, kept"),
    /** Headings present but none carried a usable id or body; the text was kept as free-form. */
    TEXT_UNPARSED("headings unusable, text kept"),
    /** Only an UNRESOLVED block: an honest "nothing verifiable", saved as such. */
    UNRESOLVED_ONLY("only unresolved topics"),
    /** Parsed fine but changed nothing the session already held. */
    NO_NEW_EVIDENCE("nothing new"),
    /** Searches ran (tool blocks) but no text was written and the turn ended. */
    TOOL_ONLY("searches, no text"),
    /** `pause_turn` arrived before any text was written. */
    PAUSED_BEFORE_TEXT("paused before any text"),
    /** `max_tokens` arrived before any text was written (thinking and tool calls used the output room). */
    TRUNCATED_BEFORE_TEXT("output limit before any text"),
    /** No content blocks at all. */
    EMPTY("empty reply"),
    REFUSED("declined"),
    /** Transport or API failure; see [CallDiagnostic.error]. */
    HTTP_FAILED("request failed"),
    ;

    /** The reply gave the app nothing to save. */
    val nothingUsable: Boolean get() = this == TOOL_ONLY || this == PAUSED_BEFORE_TEXT || this == TRUNCATED_BEFORE_TEXT || this == EMPTY
}

/**
 * One HTTP call, described without content: model, stop reason, block kinds and counts, parsed block
 * counts, token and search usage, duration, stage and classification. Never the prompt, the task,
 * the text, page bodies or the key.
 */
@Serializable
data class CallDiagnostic(
    val call: Int,
    val stage: StageKind,
    val model: String,
    /** end_turn · pause_turn · max_tokens · refusal · failed */
    val stop: String,
    val blocks: Map<String, Int> = emptyMap(),
    val textChars: Int = 0,
    val citedBlocks: Int = 0,
    /** Parsed contract blocks by kind (answer, req, cand, attr, pick, claim, step, unresolved, …). */
    val parsed: Map<String, Int> = emptyMap(),
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val maxTokens: Long,
    val webSearches: Int? = null,
    val searchesAllowed: Int,
    val durationMs: Long,
    val classification: CallClass,
    /** Technical line of a failed request (never contains the key). */
    val error: String? = null,
) {
    /** One shareable line. */
    fun line(): String = buildString {
        append("call $call · ${stage.name.lowercase()} · $model · stop=$stop")
        if (blocks.isNotEmpty()) append(" · blocks: " + blocks.entries.joinToString(", ") { "${it.key} ${it.value}" }) else if (error == null) append(" · blocks: none")
        append(" · text $textChars chars" + if (citedBlocks > 0) " ($citedBlocks cited)" else "")
        append(" · parsed: " + if (parsed.isEmpty()) "none" else parsed.entries.joinToString(", ") { "${it.key} ${it.value}" })
        append(" · tokens in ${inputTokens ?: "?"} out ${outputTokens ?: "?"} of max $maxTokens")
        append(" · searches ${webSearches ?: "?"} of $searchesAllowed")
        append(" · ${durationMs / 1000}.${(durationMs % 1000) / 100} s")
        append(" · ${classification.name}")
        error?.let { append(" · $it") }
    }

    companion object {
        /** A redacted summary of a run's calls, safe to paste into a bug report. */
        fun summary(runId: String, kind: RunKind, mode: ResearchMode, searchBudget: Int, calls: List<CallDiagnostic>): String = buildString {
            appendLine("${kind.name.lowercase()} run $runId · ${mode.label} · budget $searchBudget searches · ${calls.size} ${if (calls.size == 1) "call" else "calls"}")
            calls.forEach { appendLine(it.line()) }
            append("Contains counts and kinds only: no prompt, task text, page content or key.")
        }
    }
}

/**
 * The outcome of one continuation for a question or note typed on the Continue sheet. The original
 * answer is never replaced; a follow-up is answered next to it, or recorded as not found.
 */
@Serializable
data class FollowUp(
    val runId: String,
    val requirementId: String?,
    /** The person's words, verbatim. */
    val question: String,
    val status: RequirementStatus,
    val answer: List<CitedSegment> = emptyList(),
    /** What was found or why nothing could be verified, one or two sentences. */
    val summary: String = "",
    val atEpochMs: Long,
)

@Serializable
enum class RunKind { INITIAL, CONTINUATION }

@Serializable
data class RunUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val webSearches: Int = 0,
    val elapsedMs: Long = 0,
    /** False when one of the calls ended without usage (timeout, stop): totals are a floor. */
    val complete: Boolean = true,
)

@Serializable
data class RunRecord(
    val id: String,
    val kind: RunKind,
    val startedAtEpochMs: Long,
    val mode: ResearchMode,
    val model: String,
    val searchBudget: Int,
    val spendLimitCents: Int? = null,
    val stages: List<StageRecord> = emptyList(),
    val usage: RunUsage = RunUsage(),
    val outcome: SessionOutcome,
    /** Mono technical line for failures/stops. */
    val technical: String? = null,
)

/** Template-specific body; one of these is non-empty per session. */
@Serializable
data class ResultBody(
    val candidates: List<Candidate> = emptyList(),
    val excluded: List<Candidate> = emptyList(),
    /** Compare-table columns in display order (attribute keys). */
    val compareKeys: List<String> = emptyList(),
    val recommendation: List<CitedSegment> = emptyList(),
    val picks: List<ItemPick> = emptyList(),
    val steps: List<StepLine> = emptyList(),
)

@Serializable
data class SourceRef2(
    val n: Int,
    val url: String,
    val title: String,
    val domain: String,
    val official: Boolean,
    val pageAge: String? = null,
    val quotes: List<String> = emptyList(),
    val citedCount: Int = 0,
    /** Run id in which the source first appeared. */
    val firstRunId: String? = null,
)

/** Everything one legacy M3A.2 research session holds. Since Room v7 it lives byte-for-byte on a legacy investigation turn (`investigation_turns.payload`). */
@Serializable
data class SessionContent(
    val title: String,
    val request: String,
    val template: DeliverableTemplate,
    val requirements: List<Requirement>,
    val coverage: List<Coverage> = emptyList(),
    val answer: List<CitedSegment> = emptyList(),
    val callouts: List<Callout> = emptyList(),
    val body: ResultBody = ResultBody(),
    val claims: List<Claim> = emptyList(),
    val conflicts: List<Conflict> = emptyList(),
    val unresolved: List<Unresolved> = emptyList(),
    val sources: List<SourceRef2> = emptyList(),
    val queries: List<String> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    val runs: List<RunRecord> = emptyList(),
    /** Answers to follow-up questions from continuations, newest last; the original answer stays as it was. */
    val followUps: List<FollowUp> = emptyList(),
    val outcome: SessionOutcome,
    /** What was shared, in plain words (preflight scope). */
    val shared: List<String> = emptyList(),
    /** Text on a page tried to give instructions; it was dropped. */
    val injectionNoticed: Boolean = false,
    val contractVersion: Int = CONTRACT_VERSION,
) {
    val searchesTotal: Int get() = runs.sumOf { it.usage.webSearches }
    val latestRun: RunRecord? get() = runs.lastOrNull()
    fun coverageOf(id: String): Coverage = coverage.firstOrNull { it.requirementId == id } ?: Coverage(id, RequirementStatus.PENDING)
    fun source(n: Int): SourceRef2? = sources.firstOrNull { it.n == n }

    companion object {
        /** 2: statuses validated by [CompletionPolicy]; model-typed `src=` pointers kept apart from provider citations. */
        const val CONTRACT_VERSION = 2
    }
}

@Serializable
data class AppliedProposal(val proposalId: String, val atEpochMs: Long, val title: String, val stepId: String? = null)
