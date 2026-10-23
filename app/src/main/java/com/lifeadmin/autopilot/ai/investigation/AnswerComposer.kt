package com.lifeadmin.autopilot.ai.investigation

import java.util.Locale

/**
 * Finds internal residue in user-facing text (RES-087): requirement ids, raw claim markers,
 * status enum names, contract headings and key=value heading attributes.
 */
object LeakageScan {
    private val PATTERNS = listOf(
        Regex("""\br-[a-z]\d+\b""") to "requirement id",
        Regex("""\[\s*c\d+(?:\s*,\s*c\d+)*\s*]""") to "claim marker",
        Regex("""\b(?:NOT_ESTABLISHED|ESTABLISHED|INDICATIVE|CONTESTED|DEPENDS_ON_INPUT|ANSWERED_WITH_CONDITIONS|PARTIALLY_ANSWERED|NEEDS_INPUT|RESOLVED_CONFLICT|NEEDS_CHECK|YOUR_CALL|NOT_FOUND)\b""") to "status label",
        Regex("""#{1,4}\s*(?:TITLE|ANSWER|CLAIM|REQ|CAND|ATTR|PICK|STEPS?|COMPARE|RECOMMEND|CHANGED|CONFIRMED|PROPOSE|UNRESOLVED|CONDITION|NEEDS_INPUT|WORTH_KNOWING|CALLOUT|EXCLUDED|CONFLICT)\b""") to "contract heading",
        Regex("""\b(?:status|load|asof|volatile|needs_input|needs|fresh|about|type|claims|req|tag|kind)=\S+""") to "heading attribute",
        Regex("""\bsrc\s*=\s*s\d+""") to "source pointer",
    )

    /** Each finding names the kind of residue and the text that matched. */
    fun scan(text: String): List<String> = PATTERNS.flatMap { (rx, what) -> rx.findAll(text).map { "$what: ${it.value}" } }

    /** Removes every kind of residue, then tidies the punctuation it leaves behind. */
    fun clean(text: String): String {
        var t = EvidenceText.clean(text)
        for ((rx, _) in PATTERNS) t = rx.replace(t, "")
        t = t.replace(Regex("""\(\s*\)|\[\s*]"""), "")
        t = t.replace(Regex("""\s+([.,;:!?])"""), "$1")
        return t.replace(Regex("[ \t]{2,}"), " ").trim()
    }
}

/** What the parser produced for one turn, before assessment and composition. */
data class TurnOutput(
    val title: String? = null,
    val answer: List<AnswerSegment> = emptyList(),
    val claims: List<Claim> = emptyList(),
    val steps: List<AnswerSegment> = emptyList(),
    val compareRows: List<CompareRow> = emptyList(),
    val compareOptions: List<String> = emptyList(),
    val options: List<AnswerSegment> = emptyList(),
    val recommendation: Recommendation? = null,
    val worthKnowing: List<AnswerSegment> = emptyList(),
    val changes: List<ClaimChange> = emptyList(),
    val unresolved: List<Unresolved> = emptyList(),
    val proposals: List<ProposedChange> = emptyList(),
    val queries: List<String> = emptyList(),
    val injectionNoticed: Boolean = false,
    /** No contract headings at all: the whole text was kept as the answer. */
    val freeForm: Boolean = false,
) {
    val hasFindings: Boolean get() = answer.isNotEmpty() || claims.isNotEmpty() || steps.isNotEmpty() || compareRows.isNotEmpty() || recommendation != null
}

/**
 * Objective-led composition (RES-080–089). The answer leads; structure appears only when the
 * objective's ask calls for it; material caveats stay in the main answer; judgments never carry
 * citations; nothing internal leaks. Pure: the same inputs give the same answer.
 */
object AnswerComposer {
    private val NEGATIVE = listOf(
        Regex("""\bthere (?:is|are) no such\b""", RegexOption.IGNORE_CASE) to "no confirmed",
        Regex("""\bdoes not exist\b""", RegexOption.IGNORE_CASE) to "could not be found",
        Regex("""\bdoesn't exist\b""", RegexOption.IGNORE_CASE) to "couldn't be found",
        Regex("""\bis false\b""", RegexOption.IGNORE_CASE) to "could not be confirmed",
        Regex("""\bis not true\b""", RegexOption.IGNORE_CASE) to "could not be confirmed",
        Regex("""\bthere is no\b""", RegexOption.IGNORE_CASE) to "no source showed a",
        Regex("""\bthere are no\b""", RegexOption.IGNORE_CASE) to "no source showed any",
        Regex("""\bno such\b""", RegexOption.IGNORE_CASE) to "no confirmed",
    )

    fun compose(objective: Objective, out: TurnOutput, claims: List<Claim>, derivation: Derivation, fallbackTitle: String): Answer {
        val byId = claims.associateBy { it.id }
        val lead = out.answer.map { seg -> guardNegative(scrub(seg, byId), byId) }.filter { it.text.isNotBlank() }
        val caveats = caveats(derivation)
        val sections = ArrayList<AnswerSection>()
        // Structure follows the ask, never the subject (RES-080/083).
        if (objective.askKind == AskKind.PROCEDURE || (out.steps.isNotEmpty() && objective.satisfies.any { it.contains("step", true) })) {
            if (out.steps.isNotEmpty()) sections += AnswerSection(SectionKind.STEPS, out.steps.map { scrub(it, byId) })
        }
        if (objective.askKind == AskKind.COMPARE || objective.askKind == AskKind.RECOMMEND) {
            if (out.compareRows.isNotEmpty()) sections += AnswerSection(SectionKind.COMPARISON, rows = out.compareRows.map { r -> r.copy(cells = r.cells.mapValues { scrub(it.value, byId) }) }, options = out.compareOptions)
        }
        if (out.options.isNotEmpty() && (objective.askKind == AskKind.RECOMMEND || objective.askKind == AskKind.COMPARE || objective.askKind == AskKind.DECIDE)) {
            sections += AnswerSection(SectionKind.OPTIONS, out.options.map { scrub(it, byId) })
        }
        if (derivation.conditions.isNotEmpty()) sections += AnswerSection(SectionKind.CONDITIONS, derivation.conditions.map { AnswerSegment(it.text, listOf(it.id)) })
        if (out.worthKnowing.isNotEmpty()) sections += AnswerSection(SectionKind.MATERIAL, out.worthKnowing.map { scrub(it, byId) })
        val changed = out.changes.filter { it.kind != ChangeKind.CONFIRMED }
        if (changed.isNotEmpty()) sections += AnswerSection(SectionKind.CHANGES, changed.map { AnswerSegment(it.note, listOfNotNull(it.newClaimId ?: it.earlierClaimId)) })
        val confirmed = out.changes.filter { it.kind == ChangeKind.CONFIRMED }
        if (confirmed.isNotEmpty()) sections += AnswerSection(SectionKind.CONFIRMED, confirmed.map { AnswerSegment(it.note, listOf(it.earlierClaimId)) })
        if (derivation.notChecked.isNotEmpty()) sections += AnswerSection(SectionKind.NOT_CHECKED, derivation.notChecked.map { AnswerSegment(it.text, listOf(it.id)) })
        if (out.unresolved.isNotEmpty()) sections += AnswerSection(SectionKind.UNRESOLVED, out.unresolved.map { AnswerSegment("${it.topic} — ${it.why}") })
        val rec = out.recommendation?.let { r ->
            // A recommendation is given when asked for; on a neutral comparison it is a clearly separate, optional addition (RES-050).
            val cleaned = r.copy(text = LeakageScan.clean(r.text), reasons = r.reasons.map { scrub(it, byId) }, assumptions = r.assumptions.map(LeakageScan::clean), flipConditions = r.flipConditions.map(LeakageScan::clean))
            if (objective.askKind.wantsRecommendation || objective.askKind == AskKind.COMPARE || objective.askKind == AskKind.FORECAST) cleaned else null
        }
        val title = (out.title?.let(LeakageScan::clean)?.takeIf { it.isNotBlank() } ?: fallbackTitle).take(80)
        return Answer(title = title, stateLine = derivation.stateLine, lead = lead.ifEmpty { listOf(AnswerSegment(emptyLead(derivation))) }, caveats = caveats, sections = sections, recommendation = rec)
    }

    /** Markers stay only on current claims that can carry a citation (RES-085, RES-107); internal residue is removed from the text. */
    private fun scrub(seg: AnswerSegment, byId: Map<String, Claim>): AnswerSegment {
        val ids = seg.claimIds.filter { id -> byId[id]?.let { it.type.citable && !it.superseded } == true }
        return AnswerSegment(LeakageScan.clean(seg.text), ids)
    }

    /** "This investigation couldn't find support" is never shown as "this is false" (RES-063). */
    private fun guardNegative(seg: AnswerSegment, byId: Map<String, Claim>): AnswerSegment {
        val supported = seg.claimIds.any { byId[it]?.status == ClaimStatus.ESTABLISHED }
        if (supported) return seg
        var t = seg.text
        for ((rx, replacement) in NEGATIVE) t = rx.replace(t, replacement)
        return seg.copy(text = t)
    }

    /** Every load-bearing gap is named in the main answer (RES-086), whether or not the model mentioned it. */
    fun caveats(d: Derivation): List<String> = buildList {
        d.blocking.filter { it.checked }.forEach { c ->
            add(
                when (c.status) {
                    ClaimStatus.CONTESTED -> "Sources disagree: ${c.text.trimEnd('.')}."
                    ClaimStatus.INDICATIVE -> "Only indicated, not established: ${c.text.trimEnd('.')}."
                    else -> "Not established: ${c.text.trimEnd('.')}."
                },
            )
        }
        if (d.notChecked.isNotEmpty()) add("Not checked before it stopped: " + d.notChecked.joinToString("; ") { it.text.trimEnd('.') } + ".")
    }.distinct()

    private fun emptyLead(d: Derivation): String = when (d.state) {
        ResultState.NOT_ESTABLISHED -> "Nothing found could establish an answer."
        ResultState.NEEDS_INPUT -> "The answer depends on details only you have."
        else -> "No answer text came back; the findings below are what was established."
    }

    /** A title from the objective when the model gave none: never from a template. */
    fun fallbackTitle(objective: Objective): String = objective.text.ifBlank { objective.question }.trim().trimEnd('.', '?').replaceFirstChar { it.titlecase(Locale.ROOT) }.take(80)
}
