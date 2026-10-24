package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawCitation
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
        fun evidence(line: Pair<String, IntRange>, excerpt: String, attrs: EvidenceAttrs = EvidenceAttrs()): List<EvidenceRef> {
            val r = refsOfLine(line)
            val quotes = quotesOf(EvidenceText.citationsOf(spans, line.second), registry)
            fun ref(n: Int, pointer: Boolean) = EvidenceRef(
                n, excerpt.take(300), scopeNote = attrs.scopeNote, asOf = attrs.asOf,
                kind = attrs.kind ?: EvidenceKind.PRIMARY.takeIf { registry.official(n) }, retrievedAtMs = nowMs, originKey = registry.originKey(n), pointerOnly = pointer,
                quote = if (pointer) null else quotes[n], origin = attrs.origin,
                asOfBasis = attrs.asOf?.let { if (!pointer && !attrs.asOfQualified && DateSupport.shown(it, quotes[n])) DateBasis.QUOTED else DateBasis.STATED },
                asOfNote = attrs.asOfNote, labelsUnreadable = attrs.unreadable, pageTitle = if (pointer) null else registry.title(n), pageUrl = if (pointer) null else registry.url(n),
            )
            return r.verified.map { ref(it, false) } + r.reused.map { ref(it, true) }
        }
        // A cited line with no marker of its own is attributed to its implicit claim.
        fun segment(text: String, range: IntRange, extraIds: List<String> = emptyList()): AnswerSegment =
            AnswerSegment(
                EvidenceText.clean(text, keepTrailingSpace = true).replace(Regex("\\s+"), " ").trim(), (EvidenceText.markers(text) + extraIds).distinct(),
                sentences = EvidenceText.sentences(text).map { if (it.claimIds.isEmpty() && extraIds.isNotEmpty()) it.copy(claimIds = extraIds) else it },
            )

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
                            val quotes = quotesOf(sp.citations, registry)
                            answerEvidence.getOrPut(target) { ArrayList() } += registry.register(sp.citations).map { n -> EvidenceRef(n, excerpt, retrievedAtMs = nowMs, originKey = registry.originKey(n), quote = quotes[n], pageTitle = registry.title(n), pageUrl = registry.url(n)) }
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
                    val claimScope = kv["scope"]?.takeIf { it != "-" }.orEmpty()
                    for (line in b.lines) {
                        val t = EvidenceText.clean(line.first)
                        val lower = t.lowercase(Locale.ROOT)
                        when {
                            lower.startsWith("evidence:") -> EvidenceAttrs.split(t.substringAfter(':'), claimScope).let { (a, rest) -> support += evidence(line, rest, a) }
                            lower.startsWith("against:") -> EvidenceAttrs.split(t.substringAfter(':'), claimScope).let { (a, rest) -> against += evidence(line, rest, a) }
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
                        freshForDays = EvidenceAttrs.days(kv["fresh"]), needs = EvidenceAttrs.standard(kv["needs"]),
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
                "WORTH_KNOWING" -> {
                    // How the item bears on the answer and what it qualifies, from the block heading (N3).
                    val kv = EvidenceText.kv(b.head)
                    val on = kv["on"]?.let { CLAIM_ID.findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
                    for (line in b.lines) segment(LIST_PREFIX.replace(line.first, ""), line.second).takeIf { it.text.isNotBlank() }?.let { worth += it.copy(bearing = kv["bears"]?.takeIf { v -> v != "-" }, relatesTo = on) }
                }
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
            val quotes = quotesOf(sp.citations, registry)
            if (refs.isEmpty()) {
                answer += AnswerSegment(EvidenceText.clean(piece, keepTrailingSpace = true).replace(Regex("\\s+"), " "))
                continue
            }
            val id = "${turnId}-x${++n}"
            val text = EvidenceText.clean(piece).replace(Regex("\\s+"), " ")
            claims += Claim(id, text.take(300), ClaimType.FACT, loadBearing = true, support = refs.map { EvidenceRef(it, text.take(300), retrievedAtMs = nowMs, originKey = registry.originKey(it), quote = quotes[it]) }, turnId = turnId)
            answer += AnswerSegment(text, listOf(id))
        }
        return TurnOutput(answer = answer.filter { it.text.isNotBlank() }, claims = claims, queries = queries, freeForm = true)
    }

    /** The provider's cited text per source, verbatim: what the page itself says, kept apart from the reply's own words. */
    private fun quotesOf(citations: List<RawCitation>, registry: SourceRegistry): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (c in citations) {
            val text = c.citedText.trim().takeIf { it.isNotEmpty() } ?: continue
            val n = registry.register(listOf(c)).firstOrNull() ?: continue
            out[n] = out[n]?.let { if (text in it) it else "$it … $text" }?.take(300) ?: text.take(300)
        }
        return out
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

/**
 * What a reply says about one EVIDENCE or AGAINST line, written as a leading `(kind=… · origin=… · asof=… · about=…)`.
 * These can only narrow what the evidence supports; the assessor still decides every status.
 *
 * @property unreadable a label block was present but could not be read safely; nothing in it is trusted and the
 *   evidence is not counted as support (see [EvidenceRef.labelsUnreadable]).
 * @property asOfQualified `asof=` held more than a bare date, so the date is the reply's own reading, never the page's.
 */
data class EvidenceAttrs(
    val scopeNote: String = "",
    val asOf: String? = null,
    val kind: EvidenceKind? = null,
    val origin: String? = null,
    val asOfNote: String? = null,
    val asOfQualified: Boolean = false,
    val unreadable: Boolean = false,
) {
    companion object {
        private val KNOWN = setOf("kind", "origin", "asof", "about")
        /** A label anywhere in the text: the contract's keys, plus `scope=`, which a reply may write for `about=`. */
        private val LABEL = Regex("""\b(?:kind|origin|asof|about|scope)\s*=""", RegexOption.IGNORE_CASE)
        private val PART = Regex("""^([A-Za-z_]+)\s*=\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
        private val BARE_DATE = Regex("""^(\d{4}(?:-\d{2}(?:-\d{2})?)?)(?![\d-])\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

        /**
         * Splits the label block from the excerpt. The block is the leading parenthesised group, matched with
         * balanced parentheses, so a value may itself contain "(…)" or commas. Fields are separated by `·` or `;`
         * outside any parentheses. A block that is unclosed, holds a part that is not `key=value`, an unknown or
         * conflicting key, or labels written outside the block is unreadable: none of its labels are trusted.
         * A leading parenthesis with no labels in it ("(Updated 2025) …") is ordinary text. An `about` that
         * restates the claim's own scope is no mismatch.
         */
        fun split(raw: String, claimScope: String): Pair<EvidenceAttrs, String> {
            val text = raw.trim()
            if (!text.startsWith("(")) return if (LABEL.containsMatchIn(text)) unreadable(text) else EvidenceAttrs() to text
            val close = closingParen(text)
            if (close < 0) return if (LABEL.containsMatchIn(text)) unreadable(text) else EvidenceAttrs() to text
            val inner = text.substring(1, close)
            val rest = text.substring(close + 1).trim()
            if (!LABEL.containsMatchIn(inner)) return if (LABEL.containsMatchIn(rest)) unreadable(text) else EvidenceAttrs() to text
            if (LABEL.containsMatchIn(rest)) return unreadable(text)
            val kv = LinkedHashMap<String, String>()
            for (part in topLevelParts(inner)) {
                val m = PART.find(part) ?: return unreadable(text)
                val key = m.groupValues[1].lowercase(Locale.ROOT).let { if (it == "scope") "about" else it }
                val value = m.groupValues[2].trim()
                if (key !in KNOWN) return unreadable(text)
                if (kv[key] != null && kv[key] != value) return unreadable(text)
                kv[key] = value
            }
            val about = kv["about"]?.takeIf { it != "-" && it.isNotBlank() }?.take(120)
            val note = if (about == null || norm(about) == norm(claimScope)) "" else "About $about"
            val kind = when (kv["kind"]?.lowercase(Locale.ROOT)) {
                "primary", "official", "issuer" -> EvidenceKind.PRIMARY
                "review", "meta-analysis", "guideline" -> EvidenceKind.REVIEW
                "study", "test", "measurement" -> EvidenceKind.STUDY
                "secondary", "news", "blog", "forum", "aggregator" -> EvidenceKind.SECONDARY
                else -> null
            }
            val origin = kv["origin"]?.takeIf { it != "-" && it.isNotBlank() }?.take(80)
            val asofRaw = kv["asof"]?.takeIf { it != "-" && it.isNotBlank() }?.take(80)
            val dated = asofRaw?.let { BARE_DATE.find(it) }
            val qualified = dated != null && dated.groupValues[2].isNotBlank()
            return EvidenceAttrs(
                note, dated?.groupValues?.get(1), kind, origin,
                asOfNote = asofRaw?.takeIf { dated == null || qualified }, asOfQualified = qualified,
            ) to rest
        }

        /** Conservative result for a block that cannot be read: no label is trusted; the excerpt keeps only text outside label groups. */
        private fun unreadable(text: String) = EvidenceAttrs(unreadable = true) to withoutLabels(text)

        /** Index of the parenthesis that closes the one at 0, or -1 when it is never closed. */
        private fun closingParen(t: String): Int {
            var depth = 0
            for ((i, ch) in t.withIndex()) {
                if (ch == '(') depth++ else if (ch == ')') { depth--; if (depth == 0) return i }
            }
            return -1
        }

        /** Splits on `·` and `;` that are outside parentheses and brackets; empty parts (a trailing "· )") are dropped. */
        private fun topLevelParts(inner: String): List<String> {
            val parts = ArrayList<String>()
            var depth = 0
            val cur = StringBuilder()
            for (ch in inner) {
                when {
                    ch == '(' || ch == '[' -> { depth++; cur.append(ch) }
                    (ch == ')' || ch == ']') && depth > 0 -> { depth--; cur.append(ch) }
                    (ch == '·' || ch == ';') && depth == 0 -> { parts += cur.toString(); cur.clear() }
                    else -> cur.append(ch)
                }
            }
            parts += cur.toString()
            return parts.map { it.trim() }.filter { it.isNotEmpty() }
        }

        /** Removes every parenthesised group that holds a label (to the end when unclosed), then any stray `key=value`. */
        internal fun withoutLabels(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '(') {
                    val close = closingParen(text.substring(i))
                    val group = if (close < 0) text.substring(i) else text.substring(i, i + close + 1)
                    if (LABEL.containsMatchIn(group)) { i += group.length; continue }
                }
                out.append(ch); i++
            }
            return Regex("""\b(?:kind|origin|asof|about|scope)\s*=[^·;]*""", RegexOption.IGNORE_CASE).replace(out.toString(), "")
                .replace(Regex("""\s*[·;]\s*"""), " ").replace(Regex("\\s+"), " ").trim()
        }

        /** `7d`, `3w`, `6m`, `1y` (or a bare number of days); anything else is no window. */
        fun days(v: String?): Int? {
            val m = Regex("""^\s*(\d{1,4})\s*([dwmy]?)\s*$""", RegexOption.IGNORE_CASE).find(v ?: return null) ?: return null
            val n = m.groupValues[1].toInt()
            return when (m.groupValues[2].lowercase(Locale.ROOT)) { "w" -> n * 7; "m" -> n * 30; "y" -> n * 365; else -> n }.takeIf { it > 0 }
        }

        fun standard(v: String?): EvidenceStandard? = when (v?.trim()?.lowercase(Locale.ROOT)) {
            "direct" -> EvidenceStandard.DIRECT
            "corroborated", "corroboration", "corroborate" -> EvidenceStandard.CORROBORATED
            "review" -> EvidenceStandard.REVIEW
            else -> null
        }

        private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
