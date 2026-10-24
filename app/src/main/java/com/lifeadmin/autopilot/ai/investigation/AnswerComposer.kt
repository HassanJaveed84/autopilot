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
        Regex("""\b(?:status|load|asof|volatile|needs_input|needs|fresh|about|origin|type|claims|req|tag|kind)=\S+""") to "heading attribute",
        Regex("""\bsrc\s*=\s*s\d+""") to "source pointer",
        // A held source's registry number ("held as s1"), with the "as"/"source" that introduces it, so "held as s1, but" reads "held, but".
        Regex("""\s?\b(?:as\s+|source\s+)?(?<![=\w])s\d{1,3}\b""") to "source number",
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

/**
 * A call after the first in a turn can cite only what its own searches returned, while the answer speaks for
 * everything the turn holds. Sentences in which such a call describes its own search ("I did not retrieve the
 * issuer's page in this search") are statements about the call, and can be false about the investigation, so they
 * are left out of the answer. Real gaps still reach the person: every load-bearing claim that is not established
 * becomes a caveat from the evidence, and unresolved items are kept.
 */
object CallNarration {
    private val CALL = Regex("""\b(?:this|that|the (?:latest|last|current|second|follow-up)) (?:search|searches|call|pass|stage|round|lookup)\b|\bthese (?:search )?results\b""", RegexOption.IGNORE_CASE)
    private val HELD = listOf(Regex("""\bsrc\s*=\s*s\d+|\bs\d{1,3}\b"""), Regex("""\balready (?:held|cited|have)\b""", RegexOption.IGNORE_CASE))
    private val SENTENCE = Regex("""(?<=[.!?])\s+(?=[A-Z0-9"'(“])""")

    fun narrates(text: String): Boolean = CALL.containsMatchIn(text)

    /** A remark about a call's search allowance or budget: the engine, not the reply, knows the budget and why it stopped. */
    private val BUDGET_TALK = Regex(
        """\b(?:search(?:es)?\s+(?:allowance|budget|limit|cap)|(?:allowance|budget|limit)\s+(?:was|is|of)\s+(?:only\s+)?(?:one|two|three|four|five|\d+)\s+search(?:es)?|(?:only|just)\s+(?:one|two|three|four|five|\d+)\s+search(?:es)?\s+(?:was|were|is|are)\s+(?:allowed|available|permitted)|searches\s+(?:allowed|available)\s+(?:on|for|in)\s+this|(?:one|two|three|\d+)-search\s+(?:limit|allowance|budget)|limited\s+to\s+(?:one|two|three|four|five|\d+)\s+search(?:es)?|(?:could|can|was able to)\s+only\s+(?:run|do|make|use|perform)\s+(?:one|two|three|four|five|\d+)\s+search(?:es)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CLAUSE = Regex(""",\s+(?:and|but|so)\s+|;\s+""")

    /** Removes the clauses that talk about search allowance or budget; returns "" when nothing else is left. */
    fun withoutBudgetTalk(text: String): String {
        if (!BUDGET_TALK.containsMatchIn(text)) return text
        val sentences = text.split(SENTENCE).mapNotNull { sentence ->
            if (!BUDGET_TALK.containsMatchIn(sentence)) return@mapNotNull sentence
            val end = sentence.trimEnd().lastOrNull()?.takeIf { it == '.' || it == '!' || it == '?' }
            val body = sentence.trimEnd().trimEnd('.', '!', '?')
            // Clauses with the separator that follows each, so what is kept reads as written.
            val clauses = ArrayList<Pair<String, String>>()
            var at = 0
            for (m in CLAUSE.findAll(body)) { clauses += body.substring(at, m.range.first) to m.value; at = m.range.last + 1 }
            clauses += body.substring(at) to ""
            val kept = clauses.filterNot { BUDGET_TALK.containsMatchIn(it.first) }
            if (kept.isEmpty()) null else kept.dropLast(1).joinToString("") { it.first + it.second } + kept.last().first + (end ?: "")
        }
        return sentences.joinToString(" ").trim()
    }

    /** The same for answer text: sentences keep their claim markers. */
    fun withoutBudgetTalk(segments: List<AnswerSegment>): List<AnswerSegment> = segments.mapNotNull { seg ->
        if (seg.sentences.isNotEmpty()) {
            val kept = seg.sentences.mapNotNull { s -> withoutBudgetTalk(s.text).takeIf { it.isNotBlank() }?.let { s.copy(text = it) } }
            return@mapNotNull if (kept.isEmpty()) null else seg.copy(text = kept.joinToString(" ") { it.text }, sentences = kept)
        }
        withoutBudgetTalk(seg.text).takeIf { it.isNotBlank() }?.let { seg.copy(text = it) }
    }

    /** True for a note about re-citing a page the investigation already holds. */
    fun aboutHeldSource(text: String): Boolean = narrates(text) && HELD.any { it.containsMatchIn(text) }

    /** Drops the narrating sentences; a segment left empty is dropped. Claim markers stay on what remains. */
    fun strip(segments: List<AnswerSegment>): List<AnswerSegment> = segments.mapNotNull { seg ->
        if (seg.sentences.isNotEmpty()) {
            val kept = seg.sentences.filterNot { narrates(it.text) }
            return@mapNotNull if (kept.isEmpty()) null else seg.copy(text = kept.joinToString(" ") { it.text }, sentences = kept)
        }
        val kept = seg.text.split(SENTENCE).filterNot(::narrates)
        if (kept.isEmpty()) null else seg.copy(text = kept.joinToString(" ").trim())
    }
}

/**
 * RES-062/086, Document F §4 status honesty: no sentence in the answer reads as an established fact when the
 * claims it states are not established. Each sentence is judged by its own claim markers, so one weak claim
 * does not taint the rest of its paragraph:
 * - every claim it states is weak → the sentence is kept, prefixed with the weakest status in plain words;
 * - some are weak → the sentence is kept, with the weak claims named after it;
 * - the sentence already says it is uncertain ("may", "reportedly", "not confirmed") → left as written;
 * - no markers, or only established or user-dependent claims → left as written.
 * Nothing is dropped, so a partly supported answer stays an answer, and caveats for load-bearing gaps still follow.
 */
object StatusQualifier {
    private val HEDGED = Regex(
        """\b(?:may|might|possibly|perhaps|reportedly|apparently|appears?|seems?|suggests?|unconfirmed|unverified|unclear|uncertain|disputed|conflicting|disagree\w*|not (?:yet )?(?:established|confirmed|verified)|no source (?:confirms|establishes|verifies|shows)|could not be (?:confirmed|verified|established)|only indicated|some sources|one source|could(?: not|n't)? be found|not found|found no|could(?: not|n't) find|no confirmed|no source showed)\b|\bno\b[^.!?]{0,80}\b(?:found|identified|located)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** @param absence rewrites "does not exist" style wording for a sentence none of whose claims is established (RES-063). */
    fun qualify(seg: AnswerSegment, byId: Map<String, Claim>, absence: ((String) -> String)? = null): String =
        if (seg.sentences.isEmpty()) qualify(seg.text, seg.claimIds, byId, absence)
        else seg.sentences.joinToString(" ") { qualify(it.text, it.claimIds, byId, absence) }

    fun qualify(raw: String, claimIds: List<String>, byId: Map<String, Claim>, absence: ((String) -> String)? = null): String {
        val claims = claimIds.mapNotNull { byId[it] }
        val text = if (absence != null && claims.none { it.status == ClaimStatus.ESTABLISHED }) absence(raw) else raw
        val weak = claims.mapNotNull { c -> weakness(c)?.let { c to it } }
        if (weak.isEmpty() || HEDGED.containsMatchIn(text)) return text
        if (weak.size == claims.size) return "${weak.minByOrNull { rank(it.first) }!!.second}: $text"
        val notes = weak.joinToString("; ") { (c, label) -> "${label.replaceFirstChar { it.lowercase(Locale.ROOT) }}: ${c.text.trim().trimEnd('.')}" }
        val body = text.trimEnd()
        val end = body.lastOrNull()?.takeIf { it == '.' || it == '!' || it == '?' }
        return if (end != null) "${body.dropLast(1)} ($notes)$end" else "$body ($notes)"
    }

    /** The plain-language label for a claim that is not established and current, or null when it is. */
    private fun weakness(c: Claim): String? = when {
        c.superseded -> "No longer current"
        c.status == ClaimStatus.ESTABLISHED || c.status == ClaimStatus.DEPENDS_ON_INPUT -> null
        !c.checked -> "Not checked"
        c.type.derived -> "Rests on findings that are not established"
        c.status == ClaimStatus.INDICATIVE -> "Only indicated, not established"
        c.status == ClaimStatus.CONTESTED -> "Sources disagree"
        else -> "Not established"
    }

    private fun rank(c: Claim): Int = when {
        c.superseded -> 0
        !c.checked || c.status == ClaimStatus.NOT_ESTABLISHED -> 1
        c.status == ClaimStatus.CONTESTED -> 2
        else -> 3
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
        val lead = out.answer.map { seg -> scrub(seg, byId, absence = true) }.filter { it.text.isNotBlank() }
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
        if (out.unresolved.isNotEmpty()) sections += AnswerSection(SectionKind.UNRESOLVED, out.unresolved.map { AnswerSegment(LeakageScan.clean("${it.topic} — ${it.why}")) })
        val rec = out.recommendation?.let { r ->
            // A recommendation is given when asked for; on a neutral comparison it is a clearly separate, optional addition (RES-050).
            val cleaned = r.copy(text = LeakageScan.clean(r.text), reasons = r.reasons.map { scrub(it, byId) }, assumptions = r.assumptions.map(LeakageScan::clean), flipConditions = r.flipConditions.map(LeakageScan::clean))
            if (objective.askKind.wantsRecommendation || objective.askKind == AskKind.COMPARE || objective.askKind == AskKind.FORECAST) cleaned else null
        }
        val title = (out.title?.let(LeakageScan::clean)?.takeIf { it.isNotBlank() } ?: fallbackTitle).take(80)
        return Answer(title = title, stateLine = derivation.stateLine, lead = lead.ifEmpty { listOf(AnswerSegment(emptyLead(derivation))) }, caveats = caveats, sections = sections, recommendation = rec)
    }

    /** Markers stay only on current claims that can carry a citation (RES-085, RES-107); internal residue is removed from the text. */
    private fun scrub(seg: AnswerSegment, byId: Map<String, Claim>, absence: Boolean = false): AnswerSegment {
        val ids = seg.claimIds.filter { id -> byId[id]?.let { it.type.citable && !it.superseded } == true }
        return AnswerSegment(LeakageScan.clean(StatusQualifier.qualify(seg, byId, if (absence) ::absenceWording else null)), ids)
    }

    /** "This investigation couldn't find support" is never shown as "this is false" (RES-063); applied per sentence. */
    private fun absenceWording(text: String): String {
        var t = text
        for ((rx, replacement) in NEGATIVE) t = rx.replace(t, replacement)
        return t
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
