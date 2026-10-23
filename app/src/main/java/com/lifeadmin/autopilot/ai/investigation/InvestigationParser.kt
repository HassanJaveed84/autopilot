package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSegment
import java.util.Locale

/**
 * Line-based parser for [InvestigationPrompt]'s contract. Provenance rules are the M3A.2 ones,
 * unchanged: evidence comes only from the provider's structured citations on the lines they
 * overlap; a model-typed `src=sN` is a pointer; a restatement of a held cited line keeps that
 * evidence. Statuses are never read from the model: [ClaimAssessor] derives them afterwards.
 */
object InvestigationParser {
    private val HEADING = Regex("""^\s*#{1,4}\s*(TITLE|ANSWER|CLAIM|CONDITION|NEEDS_INPUT|STEPS?|COMPARE|RECOMMEND|WORTH_KNOWING|CHANGED|CONFIRMED|PROPOSE|UNRESOLVED)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val CLAIM_ID = Regex("""\b(c\d+)\b""")
    private val CHANGE = Regex("""\b(c\d+)\b\s*(?:->|→|=>|to)\s*\b(c\d+)\b""", RegexOption.IGNORE_CASE)
    private val SENTENCE = Regex("""(?<=[.!?])\s+(?=[A-Z0-9"'(])""")
    private val LIST_PREFIX = Regex("""^\s*(?:\d+[.)]|[-•*])\s*""")

    private class Block(val kind: String, val head: String, val start: Int) { val lines = ArrayList<Pair<String, IntRange>>(); var end = start }

    /**
     * @param turnId the turn these claims belong to
     * @param knownClaims claims inherited into this turn; a CLAIM block with one of their ids re-checks it
     * @param knownEvidence cited lines already held, for validating restatements
     */
    fun parse(
        segments: List<RawSegment>,
        searches: List<RawSearch>,
        registry: SourceRegistry,
        turnId: String,
        nowMs: Long,
        knownClaims: List<Claim> = emptyList(),
        knownEvidence: List<EvidenceLine> = emptyList(),
        proposalIdOf: (kind: ProposedKind, text: String) -> String = ::defaultProposalId,
    ): TurnOutput {
        val (full, spans) = EvidenceText.spans(segments)
        for (s in spans) if (s.citations.isNotEmpty()) registry.countBlock(s.citations)
        for (s in searches) for (r in s.results) registry.registerResult(r)
        val queries = searches.map { it.query }.filter { it.isNotBlank() }

        val blocks = ArrayList<Block>()
        var current: Block? = null
        var offset = 0
        for (raw in full.split('\n')) {
            val range = offset until offset + raw.length
            offset += raw.length + 1
            val m = HEADING.find(raw)
            if (m != null) { current = Block(m.groupValues[1].uppercase(Locale.ROOT).let { if (it == "STEP") "STEPS" else it }, m.groupValues[2].trim(), range.first).also { blocks += it }; continue }
            if (raw.isBlank()) continue
            val b = current ?: Block("FREE", "", range.first).also { blocks += it; current = it }
            b.lines += raw to range; b.end = range.last + 1
        }

        fun refsOfLine(line: Pair<String, IntRange>): LineRefs = EvidenceText.settle(EvidenceText.providerRefsOf(spans, line.second, registry), EvidenceText.srcTokens(line.first, registry), EvidenceText.clean(line.first), knownEvidence)
        fun evidence(line: Pair<String, IntRange>, excerpt: String): List<EvidenceRef> {
            val r = refsOfLine(line)
            return r.verified.map { n -> EvidenceRef(n, excerpt.take(300), retrievedAtMs = nowMs, originKey = registry.originKey(n)) } +
                r.reused.map { n -> EvidenceRef(n, excerpt.take(300), retrievedAtMs = nowMs, originKey = registry.originKey(n), pointerOnly = true) }
        }
        fun segment(text: String, range: IntRange, extraIds: List<String> = emptyList()): AnswerSegment =
            AnswerSegment(EvidenceText.clean(text, keepTrailingSpace = true).replace(Regex("\\s+"), " ").trim(), (EvidenceText.markers(text) + extraIds).distinct())

        if (blocks.none { it.kind != "FREE" }) return freeForm(full, spans, registry, turnId, nowMs, queries)

        var title: String? = null
        val answer = ArrayList<AnswerSegment>()
        val claims = LinkedHashMap<String, Claim>()
        val implicit = ArrayList<Claim>()
        val steps = ArrayList<AnswerSegment>()
        val rows = ArrayList<CompareRow>()
        val options = LinkedHashSet<String>()
        var recommendation: Recommendation? = null
        val worth = ArrayList<AnswerSegment>()
        val changes = ArrayList<ClaimChange>()
        val unresolved = ArrayList<Unresolved>()
        val proposals = ArrayList<ProposedChange>()
        var injection = false
        var implicitN = 0
        val known = knownClaims.associateBy { it.id }
        // Provider citations on an ANSWER sentence verify the claims that sentence is marked with.
        val answerEvidence = LinkedHashMap<String, ArrayList<EvidenceRef>>()

        for (b in blocks) {
            val body = b.lines.joinToString(" ") { EvidenceText.clean(it.first) }.trim()
            if (b.kind != "ANSWER" && b.kind != "FREE" && EvidenceText.INJECTION.containsMatchIn(body + " " + b.head)) { injection = true; continue }
            when (b.kind) {
                "TITLE" -> title = body.ifBlank { EvidenceText.clean(b.head) }.take(80).trimEnd('.').ifBlank { null }
                "ANSWER", "FREE" -> for (line in b.lines) {
                    // A cited sentence without a marker still keeps its evidence as an implicit, non-load-bearing claim.
                    val cites = EvidenceText.providerRefsOf(spans, line.second, registry)
                    val ids = EvidenceText.markers(line.first)
                    val extra = if (cites.isNotEmpty() && ids.isEmpty()) {
                        val id = "${turnId}-x${++implicitN}"
                        implicit += Claim(id, EvidenceText.clean(line.first).take(300), ClaimType.FACT, loadBearing = false, support = evidence(line, EvidenceText.clean(line.first)), turnId = turnId)
                        listOf(id)
                    } else emptyList()
                    if (cites.isNotEmpty() && ids.isNotEmpty()) {
                        // Each cited span verifies the marker that closes it (the first marker at or after the span's start), not every marker on the line.
                        val markerAt = EvidenceText.MARKER.findAll(line.first).flatMap { m -> m.groupValues[1].split(',').map { it.trim() to line.second.first + m.range.first } }.toList()
                        for (sp in spans) {
                            if (sp.citations.isEmpty() || sp.start >= line.second.last + 1 || sp.end <= line.second.first) continue
                            val target = markerAt.firstOrNull { it.second >= sp.start }?.first ?: markerAt.last().first
                            val excerpt = EvidenceText.clean(full.substring(maxOf(sp.start, line.second.first), minOf(sp.end, line.second.last + 1))).take(300)
                            answerEvidence.getOrPut(target) { ArrayList() } += registry.register(sp.citations).map { n -> EvidenceRef(n, excerpt, retrievedAtMs = nowMs, originKey = registry.originKey(n)) }
                        }
                    }
                    segment(line.first, line.second, extra).takeIf { it.text.isNotBlank() }?.let { answer += it }
                }
                "CLAIM" -> {
                    val id = CLAIM_ID.find(b.head)?.groupValues?.get(1) ?: continue
                    val kv = EvidenceText.kv(b.head)
                    val type = when (kv["type"]?.lowercase(Locale.ROOT)) {
                        "calculation", "calc" -> ClaimType.CALCULATION; "inference" -> ClaimType.INFERENCE; "judgment", "judgement" -> ClaimType.JUDGMENT; "recommendation" -> ClaimType.RECOMMENDATION; else -> ClaimType.FACT
                    }
                    var text = ""
                    val support = ArrayList<EvidenceRef>()
                    val against = ArrayList<EvidenceRef>()
                    val premises = ArrayList<String>()
                    for (line in b.lines) {
                        val t = EvidenceText.clean(line.first)
                        val lower = t.lowercase(Locale.ROOT)
                        when {
                            lower.startsWith("evidence:") -> support += evidence(line, t.substringAfter(':').trim())
                            lower.startsWith("against:") -> against += evidence(line, t.substringAfter(':').trim())
                            lower.startsWith("premises:") -> premises += CLAIM_ID.findAll(t).map { it.groupValues[1] }
                            text.isEmpty() -> { text = t.take(400); support += evidence(line, t) }
                            else -> text = (text + " " + t).take(400)
                        }
                    }
                    if (text.isBlank()) continue
                    val prior = known[id]
                    claims[id] = Claim(
                        id = id, text = text, type = type, scope = kv["scope"]?.takeIf { it != "-" }.orEmpty().take(120),
                        asOf = kv["asof"]?.takeIf { it != "-" && it.isNotBlank() }, loadBearing = EvidenceText.yes(kv["load"]), volatile = EvidenceText.yes(kv["volatile"]),
                        dependsOnUser = EvidenceText.yes(kv["needs_input"]), support = support.distinctBy { it.sourceN to it.pointerOnly }, against = against.distinctBy { it.sourceN },
                        premises = premises.distinct(), turnId = turnId, inheritedFromTurnId = prior?.inheritedFromTurnId ?: prior?.turnId?.takeIf { it != turnId },
                    )
                }
                "CONDITION", "NEEDS_INPUT" -> for (line in b.lines) {
                    val t = EvidenceText.clean(line.first).let { LIST_PREFIX.replace(it, "") }
                    if (t.isBlank()) continue
                    val id = "${turnId}-${if (b.kind == "CONDITION") "cond" else "need"}${claims.size + implicit.size + 1}"
                    claims[id] = Claim(id, t.take(300), ClaimType.INFERENCE, loadBearing = true, dependsOnUser = true, support = evidence(line, t), premises = EvidenceText.markers(line.first), turnId = turnId)
                }
                "STEPS" -> for (line in b.lines) {
                    val t = LIST_PREFIX.replace(line.first, "")
                    segment(t, line.second).takeIf { it.text.isNotBlank() }?.let { steps += it }
                }
                "COMPARE" -> {
                    val criterion = EvidenceText.kv(b.head)["criterion"] ?: EvidenceText.clean(b.head).ifBlank { continue }
                    val cells = LinkedHashMap<String, AnswerSegment>()
                    for (line in b.lines) {
                        for (part in line.first.split('|')) {
                            val (name, finding) = part.split(':', limit = 2).let { if (it.size == 2) it[0].trim() to it[1] else continue }
                            if (name.isBlank()) continue
                            options += name
                            cells[name] = segment(finding, line.second)
                        }
                    }
                    if (cells.isNotEmpty()) rows += CompareRow(criterion.take(80), cells)
                }
                "RECOMMEND" -> {
                    var text = ""; val reasons = ArrayList<AnswerSegment>(); val assumes = ArrayList<String>(); val flips = ArrayList<String>()
                    for (line in b.lines) {
                        val t = EvidenceText.clean(line.first)
                        val lower = t.lowercase(Locale.ROOT)
                        when {
                            lower.startsWith("because:") -> reasons += segment(line.first.substringAfter(':'), line.second)
                            lower.startsWith("assumes:") -> assumes += t.substringAfter(':').trim()
                            lower.startsWith("changes if:") -> flips += t.substringAfter(':').trim()
                            text.isEmpty() -> text = t
                            else -> text = "$text $t"
                        }
                    }
                    if (text.isNotBlank()) recommendation = Recommendation(text.take(400), reasons, assumes, flips, optional = EvidenceText.yes(EvidenceText.kv(b.head)["optional"]))
                }
                "WORTH_KNOWING" -> for (line in b.lines) segment(LIST_PREFIX.replace(line.first, ""), line.second).takeIf { it.text.isNotBlank() }?.let { worth += it }
                "CHANGED" -> {
                    val m = CHANGE.find(b.head) ?: continue
                    changes += ClaimChange(m.groupValues[1], m.groupValues[2], ChangeKind.CHANGED, body.ifBlank { "Changed since the earlier finding" }.take(300))
                }
                "CONFIRMED" -> {
                    val id = CLAIM_ID.find(b.head)?.groupValues?.get(1) ?: continue
                    changes += ClaimChange(id, null, ChangeKind.CONFIRMED, body.ifBlank { "Still the case" }.take(300))
                }
                "PROPOSE" -> {
                    val kv = EvidenceText.kv(b.head)
                    val kind = when (b.head.lowercase(Locale.ROOT).substringBefore(' ').substringBefore('·').trim()) {
                        "note" -> ProposedKind.NOTE; "verify", "check" -> ProposedKind.VERIFY; "correct", "correction" -> ProposedKind.CORRECT_STEP; else -> ProposedKind.STEP
                    }
                    val claimIds = kv["claims"]?.let { CLAIM_ID.findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
                    val lines = b.lines.map { EvidenceText.clean(it.first) }.filter { it.isNotBlank() }
                    val t = lines.firstOrNull { !it.startsWith("Note:", true) }?.trimEnd('.')?.take(80) ?: continue
                    val note = lines.firstOrNull { it.startsWith("Note:", true) }?.substringAfter(':')?.trim()?.take(300)
                    val step = kv["step"]?.takeIf { it != "-" && it.isNotBlank() }?.take(200)
                    proposals += ProposedChange(proposalIdOf(kind, t), kind, t, note, claimIds, step, supersedesAssumption = kind == ProposedKind.CORRECT_STEP)
                }
                "UNRESOLVED" -> for (line in b.lines) {
                    val t = EvidenceText.clean(line.first).trimStart('-', '•', '*', ' ')
                    if (t.isBlank() || t.equals("none", true)) continue
                    val parts = t.split(Regex("""\s+[—–]\s+|\s+--\s+|\s+-\s+"""), limit = 2)
                    unresolved += Unresolved(parts[0].trim().take(160), parts.getOrNull(1)?.trim()?.take(300) ?: "No source covered it.")
                }
            }
        }
        // Verified evidence from the marked ANSWER sentence joins the claim; a pointer to the same source gives way to it. Judgments stay uncited.
        for ((id, refs) in answerEvidence) {
            val c = claims[id] ?: continue
            if (!c.type.citable) continue
            val verifiedN = (c.support.filter { !it.pointerOnly } + refs).map { it.sourceN }.toSet()
            val merged = (c.support.filter { !it.pointerOnly } + refs).distinctBy { it.sourceN } + c.support.filter { it.pointerOnly && it.sourceN !in verifiedN }
            claims[id] = c.copy(support = merged)
        }
        return TurnOutput(
            title = title, answer = answer, claims = claims.values.toList() + implicit, steps = steps.take(40), compareRows = rows.take(12), compareOptions = options.toList(),
            recommendation = recommendation, worthKnowing = worth.take(6), changes = changes, unresolved = unresolved.take(12), proposals = proposals.take(12), queries = queries, injectionNoticed = injection,
        )
    }

    /** No contract at all: the whole text is the answer; each cited sentence becomes an implicit claim so its evidence is kept. */
    private fun freeForm(full: String, spans: List<EvidenceText.Span>, registry: SourceRegistry, turnId: String, nowMs: Long, queries: List<String>): TurnOutput {
        val answer = ArrayList<AnswerSegment>()
        val claims = ArrayList<Claim>()
        var n = 0
        for (sp in spans) {
            val piece = full.substring(sp.start, sp.end)
            if (piece.isBlank()) continue
            val refs = registry.register(sp.citations)
            if (refs.isEmpty()) {
                answer += AnswerSegment(EvidenceText.clean(piece, keepTrailingSpace = true).replace(Regex("\\s+"), " "))
                continue
            }
            val id = "${turnId}-x${++n}"
            val text = EvidenceText.clean(piece).replace(Regex("\\s+"), " ")
            claims += Claim(id, text.take(300), ClaimType.FACT, loadBearing = true, support = refs.map { EvidenceRef(it, text.take(300), retrievedAtMs = nowMs, originKey = registry.originKey(it)) }, turnId = turnId)
            answer += AnswerSegment(text, listOf(id))
        }
        return TurnOutput(answer = answer.filter { it.text.isNotBlank() }, claims = claims, queries = queries, freeForm = true)
    }

    fun defaultProposalId(kind: ProposedKind, text: String): String {
        val norm = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("${kind.name}|$norm".toByteArray())
        return "p-" + digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /** Parsed block counts by kind, for the call diagnostic. */
    fun counts(out: TurnOutput): Map<String, Int> = buildMap {
        if (out.title != null) put("title", 1)
        if (out.answer.isNotEmpty()) put("answer", out.answer.size)
        if (out.claims.isNotEmpty()) put("claim", out.claims.size)
        if (out.steps.isNotEmpty()) put("steps", out.steps.size)
        if (out.compareRows.isNotEmpty()) put("compare", out.compareRows.size)
        if (out.recommendation != null) put("recommend", 1)
        if (out.changes.isNotEmpty()) put("changed", out.changes.size)
        if (out.proposals.isNotEmpty()) put("propose", out.proposals.size)
        if (out.unresolved.isNotEmpty()) put("unresolved", out.unresolved.size)
    }

    /** Every cited line the turn holds, for validating model-typed restatements in later calls. */
    fun evidenceLines(claims: List<Claim>): List<EvidenceLine> = claims.flatMap { c -> (c.support + c.against).filter { !it.pointerOnly }.map { EvidenceLine(it.excerpt, listOf(it.sourceN)) } }

    /** Highest claim number in use, so a later call continues the numbering. */
    fun nextClaimNumber(claims: List<Claim>): Int = (claims.mapNotNull { Regex("""^c(\d+)$""").find(it.id)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
}
