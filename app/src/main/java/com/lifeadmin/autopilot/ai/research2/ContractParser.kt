package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.Sources
import java.util.Locale

/** What one model call contributed, before it is merged into the session. */
data class StageOutput(
    val title: String? = null,
    val answer: List<CitedSegment> = emptyList(),
    val coverage: List<Coverage> = emptyList(),
    val candidates: List<Candidate> = emptyList(),
    val excluded: List<Candidate> = emptyList(),
    val picks: List<ItemPick> = emptyList(),
    val claims: List<Claim> = emptyList(),
    val conflicts: List<Conflict> = emptyList(),
    val steps: List<StepLine> = emptyList(),
    val callouts: List<Callout> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    val unresolved: List<Unresolved> = emptyList(),
    val queries: List<String> = emptyList(),
    val injectionNoticed: Boolean = false,
    /** True when the text had none of the contract headings (free-form answer). */
    val freeForm: Boolean = false,
)

/** A cited line the session already holds; a later restatement that matches it keeps its evidence. */
data class EvidenceLine(val text: String, val refs: List<Int>)

/**
 * Numbers sources in order of first appearance and keeps those numbers for the life of a session.
 * Two kinds of entry are kept apart (hardening finding B):
 * - **cited**: the provider attached the page to a text block as a citation (`citedCount > 0`);
 * - **discovered**: the page was only returned by a search and never cited.
 * A model-typed `src=sN` resolves only to a *cited* entry, and even then it is a pointer, never new
 * verification; see [ContractParser].
 */
class SourceRegistry2(seed: List<SourceRef2> = emptyList(), private val runId: String? = null) {
    private class Mutable(val n: Int, val url: String, var title: String, val official: Boolean, var pageAge: String?, val firstRunId: String?) {
        val quotes = LinkedHashSet<String>()
        var cited = 0
    }

    private val byUrl = LinkedHashMap<String, Mutable>()
    private val byN = HashMap<Int, Mutable>()

    init {
        for (s in seed) {
            val m = Mutable(s.n, s.url, s.title, s.official, s.pageAge, s.firstRunId).also { it.quotes += s.quotes; it.cited = s.citedCount }
            byUrl[key(s.url)] = m; byN[s.n] = m
        }
    }

    /** One page, one entry: fragments and a trailing slash do not make a new source. */
    private fun key(url: String) = url.trim().substringBefore('#').removeSuffix("/")
    private fun nextN(): Int = (byN.keys.maxOrNull() ?: 0) + 1

    fun register(citations: List<RawCitation>): List<Int> {
        val refs = LinkedHashSet<Int>()
        for (c in citations) {
            val url = Sources.safeExternalUrl(c.url) ?: continue
            val src = byUrl.getOrPut(key(url)) {
                Mutable(nextN(), url, c.title?.ifBlank { null } ?: Sources.host(url) ?: url, Sources.tier(url) == com.lifeadmin.autopilot.ai.research.SourceTier.OFFICIAL, null, runId).also { byN[it.n] = it }
            }
            if (c.title?.isNotBlank() == true && src.title == Sources.host(url)) src.title = c.title
            c.citedText.trim().takeIf { it.isNotEmpty() }?.let { src.quotes += it.take(150) }
            refs += src.n
        }
        return refs.toList()
    }

    /** One cited text block: counts each distinct source once. */
    fun countBlock(citations: List<RawCitation>) { for (n in register(citations)) byN[n]?.let { it.cited++ } }

    fun registerResult(r: RawSearchResult) {
        val url = Sources.safeExternalUrl(r.url) ?: return
        val existing = byUrl[key(url)]
        if (existing != null) { if (existing.pageAge == null) existing.pageAge = r.pageAge; return }
        if (byUrl.size >= MAX_SOURCES) return
        val m = Mutable(nextN(), url, r.title.ifBlank { Sources.host(url) ?: url }, Sources.tier(url) == com.lifeadmin.autopilot.ai.research.SourceTier.OFFICIAL, r.pageAge, runId)
        byUrl[key(url)] = m; byN[m.n] = m
    }

    fun has(n: Int): Boolean = byN.containsKey(n)

    /** The provider cited this source at least once (now or in an earlier run). */
    fun isCited(n: Int): Boolean = (byN[n]?.cited ?: 0) > 0

    fun sources(): List<SourceRef2> = byN.values.sortedBy { it.n }.map {
        SourceRef2(it.n, it.url, it.title.take(160), Sources.host(it.url) ?: "", it.official, it.pageAge, it.quotes.take(3), it.cited, it.firstRunId)
    }

    companion object {
        const val MAX_SOURCES = 60
    }
}

/**
 * Line-based parser for the [Research2Prompt] contract.
 *
 * Provenance rules (finding B):
 * 1. **Fresh evidence** comes only from the provider's structured citations on the text blocks that
 *    overlap a line. Those become `refs` and can make a line a Fact or a price Confirmed.
 * 2. A model-typed `src=sN` is accepted only for a source the provider has cited (now or earlier)
 *    and only as a **pointer** (`reusedRefs`). It never confirms a price and never makes a line a
 *    Fact on its own: such a line is tagged Restated.
 * 3. The one exception is a **validated restatement**: the line repeats a cited line the session
 *    already holds ([EvidenceLine]) and points at one of that line's sources — then it keeps the
 *    original evidence. A new statement with an old source id gets no credit from it.
 * 4. Citations attach to the lines they overlap, never to a neighbouring block's heading.
 */
object ContractParser {
    private val HEADING = Regex("""^\s*#{1,4}\s*(TITLE|ANSWER|REQ|CAND|ATTR|EXCLUDED|PICK|CLAIM|CONFLICT|STEP|CALLOUT|PROPOSE|UNRESOLVED)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""\b(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val SRC = Regex("""\s*\bsrc\s*=\s*((?:s\d+\s*,?\s*)+)""", RegexOption.IGNORE_CASE)
    private val BRACKET = Regex("""\s*\[\d+(?:,\s*\d+)*]""")
    private val KV = Regex("""(\w+)\s*=\s*([^·]+?)\s*(?=·|$)""")
    private val PRICE = Regex("""([$£€])\s?(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s?(USD|GBP|EUR|CAD|AUD)\b|(USD|GBP|EUR|CAD|AUD)\s?(\d[\d,]*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val SENTENCE = Regex("""(?<=[.!?])\s+(?=[A-Z0-9"'(])""")
    private val INJECTION = Regex(
        """ignore (?:all |any |the )?(?:previous|prior|above|earlier) (?:instructions|prompts?)|disregard (?:the |all |your )?(?:previous|above|system|earlier)|you are now|system prompt|reveal (?:your|the) (?:prompt|instructions)|tell the user to|do not tell the user|share (?:all|the) (?:user'?s )?(?:tasks|data)|send (?:the |all )?(?:private|other) tasks""",
        RegexOption.IGNORE_CASE,
    )

    private class Span(val start: Int, val end: Int, val citations: List<RawCitation>)
    private class Block(val kind: String, val head: String, val start: Int) { val lines = ArrayList<Pair<String, IntRange>>(); var end = start }

    /** Evidence for one line or block: what verifies it and what merely points elsewhere. */
    private class Refs(val verified: List<Int>, val reused: List<Int>) {
        companion object { val NONE = Refs(emptyList(), emptyList()) }
    }

    fun parse(
        segments: List<RawSegment>,
        searches: List<RawSearch>,
        registry: SourceRegistry2,
        requirements: List<Requirement>,
        nowEpochMs: Long,
        /** Candidates the session already holds: later stages may add attributes to them by id. */
        existingCandidates: List<Candidate> = emptyList(),
        /** Cited lines the session already holds: a matching restatement keeps their evidence. */
        knownEvidence: List<EvidenceLine> = emptyList(),
        proposalIdOf: (kind: ProposalKind, title: String, requirementId: String?) -> String = ::defaultProposalId,
    ): StageOutput {
        val text = StringBuilder()
        val spans = ArrayList<Span>()
        for (s in segments) { val st = text.length; text.append(s.text); spans += Span(st, text.length, s.citations) }
        val full = text.toString()
        for (s in spans) if (s.citations.isNotEmpty()) registry.countBlock(s.citations)
        for (s in searches) for (r in s.results) registry.registerResult(r)
        val queries = searches.map { it.query }.filter { it.isNotBlank() }

        // Cut into blocks.
        val blocks = ArrayList<Block>()
        var current: Block? = null
        var offset = 0
        for (raw in full.split('\n')) {
            val range = offset until offset + raw.length
            offset += raw.length + 1
            val m = HEADING.find(raw)
            if (m != null) { current = Block(m.groupValues[1].uppercase(Locale.ROOT), m.groupValues[2].trim(), range.first).also { blocks += it }; continue }
            if (raw.isBlank()) continue
            val b = current ?: Block("FREE", "", range.first).also { blocks += it; current = it }
            b.lines += raw to range; b.end = range.last + 1
        }
        if (blocks.none { it.kind != "FREE" }) {
            // No contract at all: the whole text is the answer, with whatever citations it carries.
            return StageOutput(answer = cited(spans, full, 0, full.length, registry, knownEvidence), queries = queries, freeForm = true)
        }

        fun providerRefsOf(range: IntRange): List<Int> {
            val refs = LinkedHashSet<Int>()
            for (sp in spans) if (sp.start < range.last + 1 && sp.end > range.first && sp.citations.isNotEmpty()) refs += registry.register(sp.citations)
            return refs.toList()
        }
        /** Citations overlap the block's own lines, never its heading or a neighbour's. */
        fun refsOf(b: Block): Refs {
            val provider = LinkedHashSet<Int>()
            for (line in b.lines) provider += providerRefsOf(line.second)
            val tokens = b.lines.flatMap { srcTokens(it.first, registry) }.distinct()
            return settle(provider.toList(), tokens, b.lines.joinToString(" ") { clean(it.first) }, knownEvidence)
        }
        fun refsOfLine(line: Pair<String, IntRange>): Refs = settle(providerRefsOf(line.second), srcTokens(line.first, registry), clean(line.first), knownEvidence)
        fun body(b: Block): String = b.lines.joinToString(" ") { clean(it.first) }.trim()

        var title: String? = null
        val answer = ArrayList<CitedSegment>()
        val coverage = ArrayList<Coverage>()
        val candidates = LinkedHashMap<String, Candidate>()
        val excluded = ArrayList<Candidate>()
        val picks = ArrayList<ItemPick>()
        val claims = ArrayList<Claim>()
        val conflicts = ArrayList<Conflict>()
        val steps = ArrayList<StepLine>()
        val callouts = ArrayList<Callout>()
        val proposals = ArrayList<Proposal>()
        val unresolved = ArrayList<Unresolved>()
        var injection = false
        val reqIds = requirements.map { it.id }.toSet()

        for (b in blocks) {
            val bodyText = body(b)
            if (b.kind != "FREE" && b.kind != "ANSWER" && INJECTION.containsMatchIn(bodyText + " " + b.head)) { injection = true; continue }
            when (b.kind) {
                "TITLE" -> title = (bodyText.ifBlank { clean(b.head) }).take(Research2Config.MAX_TITLE_CHARS).trimEnd('.').ifBlank { null }
                "ANSWER", "FREE" -> if (b.lines.isNotEmpty()) answer += cited(spans, full, b.lines.first().second.first, b.end, registry, knownEvidence)
                "REQ" -> {
                    val head = b.head
                    val id = Regex("""^(r-[a-z]\d+)""").find(head)?.groupValues?.get(1) ?: continue
                    if (id !in reqIds) continue
                    val status = statusOf(kv(head)["status"])
                    val r = refsOf(b)
                    coverage.removeAll { it.requirementId == id }
                    coverage += Coverage(id, status, bodyText.take(300), r.verified, r.reused)
                }
                "CAND", "EXCLUDED" -> {
                    val cid = Regex("""^(c\d+)""").find(b.head)?.groupValues?.get(1) ?: continue
                    val k = kv(b.head)
                    val name = clean(k["name"] ?: "").ifBlank { continue }
                    val hero = bodyText.startsWith("hero", ignoreCase = true)
                    val reason = bodyText.removePrefix("hero").removePrefix("Hero").trimStart(':', ' ', '—', '-', '·')
                    val r = refsOf(b)
                    val c = Candidate(
                        cid, name.take(120), k["variant"]?.let(::dash), k["style"]?.let(::dash)?.lowercase(Locale.ROOT),
                        included = b.kind == "CAND", reason = reason.take(300), refs = r.verified, hero = hero, reusedRefs = r.reused,
                    )
                    if (b.kind == "CAND") candidates[cid] = c else excluded += c
                }
                "ATTR" -> {
                    val m = Regex("""^(c\d+)\.(\w+)""").find(b.head) ?: continue
                    val cid = m.groupValues[1]; val keyName = m.groupValues[2].lowercase(Locale.ROOT)
                    val cand = candidates[cid] ?: existingCandidates.firstOrNull { it.id == cid }?.copy(attrs = emptyList(), price = null, refs = emptyList(), reason = "", hero = false, reusedRefs = emptyList())?.also { candidates[cid] = it } ?: continue
                    val r = refsOf(b)
                    val tag = tagOf(kv(b.head)["tag"], r)
                    if (bodyText.isBlank()) continue
                    if (keyName == "price") {
                        val price = parsePrice(bodyText, r.verified, nowEpochMs)
                        candidates[cid] = cand.copy(price = price ?: cand.price, attrs = cand.attrs.filter { it.key != "price" } + Attribute("price", bodyText.take(160), if (price?.status == PriceStatus.CONFIRMED) ProvenanceTag.FACT else ProvenanceTag.UNKNOWN, r.verified, r.reused))
                    } else {
                        candidates[cid] = cand.copy(attrs = cand.attrs.filter { it.key != keyName } + Attribute(keyName, bodyText.take(200), tag, r.verified, r.reused))
                    }
                }
                "PICK" -> {
                    val id = Regex("""^(r-[a-z]\d+)""").find(b.head)?.groupValues?.get(1) ?: continue
                    if (id !in reqIds) continue
                    val status = statusOf(kv(b.head)["status"])
                    var pick: String? = null; var variant: String? = null; var alt: String? = null; var forYou: String? = null; var why: String? = null; var price: Price? = null
                    val facts = ArrayList<Attribute>()
                    val r = refsOf(b)
                    for (line in b.lines) {
                        val t = clean(line.first)
                        val lr = refsOfLine(line)
                        val (k, v) = t.split(':', limit = 2).let { if (it.size == 2) it[0].trim().lowercase(Locale.ROOT) to it[1].trim() else "" to t }
                        when (k) {
                            "pick" -> { val none = v.equals("none found", true) || v.equals("none", true) || v == "-"; if (!none) { val parts = v.split(Regex("""\s[—–-]\s|\s\(""", RegexOption.IGNORE_CASE), limit = 2); pick = parts[0].trim(); variant = parts.getOrNull(1)?.trimEnd(')')?.trim()?.ifBlank { null } } }
                            "label" -> if (v.isNotBlank() && v != "-") facts += Attribute("label", v.take(300), tagOf("fact", lr), lr.verified, lr.reused)
                            "alternative" -> alt = dash(v)
                            "for you" -> forYou = dash(v)
                            "why" -> why = dash(v)
                            "price" -> price = parsePrice(v, lr.verified, nowEpochMs)
                            else -> if (t.isNotBlank()) facts += Attribute("note", t.take(300), if (lr.verified.isNotEmpty()) ProvenanceTag.FACT else if (lr.reused.isNotEmpty()) ProvenanceTag.SYNTHESIS else ProvenanceTag.JUDGMENT, lr.verified, lr.reused)
                        }
                    }
                    picks.removeAll { it.requirementId == id }
                    picks += ItemPick(id, status, pick, variant, facts, alt, forYou, why, price, r.verified, r.reused)
                    if (coverage.none { it.requirementId == id }) coverage += Coverage(id, status, (why ?: pick).orEmpty().take(300), r.verified, r.reused)
                }
                "CLAIM" -> if (bodyText.isNotBlank()) { val r = refsOf(b); claims += Claim(bodyText.take(400), tagOf(kv(b.head)["tag"], r), r.verified, r.reused) }
                "CONFLICT" -> {
                    val topic = clean(b.head).ifBlank { continue }
                    val parts = bodyText.split('|').map { it.trim() }
                    val sides = ArrayList<ConflictSide>()
                    var resolution: String? = null
                    val r = refsOf(b)
                    for (p in parts) {
                        val (k, v) = p.split(':', limit = 2).let { if (it.size == 2) it[0].trim().lowercase(Locale.ROOT) to it[1].trim() else "" to p }
                        when (k) { "resolution" -> resolution = dash(v); else -> if (v.isNotBlank()) sides += ConflictSide(v.take(300), r.verified, r.reused) }
                    }
                    if (sides.isNotEmpty()) conflicts += Conflict(topic.take(160), sides.take(3), resolution)
                }
                "STEP" -> if (bodyText.isNotBlank()) {
                    val r = refsOf(b)
                    val optional = bodyText.contains("(optional)", ignoreCase = true)
                    val tag = if (r.verified.isNotEmpty()) ProvenanceTag.FACT else if (r.reused.isNotEmpty()) ProvenanceTag.SYNTHESIS else ProvenanceTag.JUDGMENT
                    steps += StepLine(bodyText.replace(Regex("""\s*\(optional\)""", RegexOption.IGNORE_CASE), "").take(300), tag, r.verified, optional, r.reused)
                }
                "CALLOUT" -> if (bodyText.isNotBlank()) callouts += Callout(calloutKind(kv(b.head)["kind"]), bodyText.take(400))
                "PROPOSE" -> {
                    val kind = when (b.head.lowercase(Locale.ROOT).substringBefore(' ').substringBefore('·').trim()) {
                        "decide" -> ProposalKind.DECIDE; "check" -> ProposalKind.CHECK; "note" -> ProposalKind.NOTE; "research" -> ProposalKind.RESEARCH; else -> ProposalKind.STEP
                    }
                    val req = kv(b.head)["req"]?.trim()?.takeIf { it in reqIds }
                    val lines = b.lines.map { clean(it.first) }.filter { it.isNotBlank() }
                    val t = lines.firstOrNull { !it.startsWith("Note:", true) }?.trimEnd('.')?.take(80) ?: continue
                    val note = lines.firstOrNull { it.startsWith("Note:", true) }?.substringAfter(':')?.trim()?.take(300)
                    val r = refsOf(b)
                    proposals += Proposal(proposalIdOf(kind, t, req), kind, t, note, req, r.verified, nowEpochMs, selectedByDefault = kind == ProposalKind.STEP || kind == ProposalKind.NOTE, reusedRefs = r.reused)
                }
                "UNRESOLVED" -> for (line in b.lines) {
                    val t = clean(line.first).trimStart('-', '•', '*', ' ')
                    if (t.isBlank() || t.equals("none", true)) continue
                    val parts = t.split(Regex("""\s+[—–]\s+|\s+--\s+|\s+-\s+"""), limit = 2)
                    unresolved += Unresolved(parts[0].trim().take(160), parts.getOrNull(1)?.trim()?.take(300) ?: "No source covered it.")
                }
            }
        }
        return StageOutput(
            title, trimEnds(answer), coverage, candidates.values.take(Research2Config.MAX_CANDIDATES), excluded.take(Research2Config.MAX_CANDIDATES), picks,
            claims.take(Research2Config.MAX_CLAIMS), conflicts, steps.take(40), callouts.take(4), proposals.take(Research2Config.MAX_PROPOSALS), unresolved.take(12), queries, injection,
        )
    }

    // ------------------------------------------------------------------ provenance helpers

    /**
     * Decides what a line's evidence is: provider citations verify; model-typed pointers verify only
     * when the line restates a held evidence line that those sources back; otherwise they stay pointers.
     */
    private fun settle(provider: List<Int>, tokens: List<Int>, text: String, known: List<EvidenceLine>): Refs {
        val pointers = tokens.filter { it !in provider }
        if (pointers.isEmpty()) return Refs(provider, emptyList())
        val validated = LinkedHashSet<Int>()
        for (e in known) {
            val shared = e.refs.filter { it in pointers }
            if (shared.isNotEmpty() && restates(text, e.text)) validated += shared
        }
        return Refs((provider + validated).distinct(), pointers.filter { it !in validated })
    }

    /**
     * The same statement, allowing for trimming and small rewording; never a different fact. Every
     * value in the line (numbers with their unit, currency, percentage; number words count as numbers;
     * dates and dosages are numbers with units) must match the evidence exactly — a changed amount,
     * size, dose, date or currency is a different claim and inherits nothing.
     */
    internal fun restates(line: String, evidence: String): Boolean {
        val a = canon(line); val b = canon(evidence)
        if (a.isEmpty() || b.isEmpty()) return false
        if (values(a) != values(b)) return false
        if (a == b) return true
        if (a.length >= 12 && b.length >= 12 && (a.contains(b) || b.contains(a))) return true
        val wa = a.split(' ').filter { it.length > 2 }.toSet(); val wb = b.split(' ').filter { it.length > 2 }.toSet()
        if (wa.size < 4 || wb.size < 4) return false
        val inter = wa.intersect(wb).size
        return inter >= 4 && inter.toDouble() / wa.union(wb).size >= 0.6
    }

    private val NUMBER_WORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100, "thousand" to 1000, "dozen" to 12,
    )
    private val VALUE = Regex("""([$£€])\s?(\d[\d,]*(?:\.\d+)?)|(\d[\d,]*(?:\.\d+)?)\s?(%|[a-z]+)?""")
    private val CURRENCY_WORDS = mapOf("usd" to "usd", "dollar" to "usd", "dollars" to "usd", "gbp" to "gbp", "pound" to "gbp", "pounds" to "gbp", "eur" to "eur", "euro" to "eur", "euros" to "eur", "cad" to "cad", "aud" to "aud")

    /** Lower-cased, number words as digits, each value folded to "<amount><unit>" so "$98" and "98.00 USD" agree and "98" and "89" never do. */
    private fun canon(s: String): String {
        var t = clean(s).lowercase(Locale.ROOT)
        t = t.replace(Regex("""\b([a-z]+)\b""")) { m -> NUMBER_WORDS[m.value]?.toString() ?: m.value }
        t = VALUE.replace(t) { m ->
            val (amount, unit) = if (m.groupValues[1].isNotEmpty()) m.groupValues[2] to when (m.groupValues[1]) { "$" -> "usd"; "£" -> "gbp"; else -> "eur" }
            else m.groupValues[3] to (m.groupValues[4].let { CURRENCY_WORDS[it] ?: it })
            " " + amountKey(amount) + unit + " "
        }
        return t.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim().replace(Regex("\\s+"), " ")
    }

    private fun amountKey(raw: String): String = raw.replace(",", "").toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: raw

    /** The values a canonical line carries, in order of appearance. */
    private fun values(canonical: String): List<String> = Regex("""\b\d[\d.]*[a-z%]*""").findAll(canonical).map { it.value }.toList()

    private fun cited(spans: List<Span>, full: String, start: Int, end: Int, registry: SourceRegistry2, known: List<EvidenceLine>): List<CitedSegment> {
        val out = ArrayList<CitedSegment>()
        for (sp in spans) {
            val s = maxOf(sp.start, start); val e = minOf(sp.end, end)
            if (e <= s) continue
            val rawPiece = full.substring(s, e)
            val provider = registry.register(sp.citations)
            if (provider.isNotEmpty() || !SRC.containsMatchIn(rawPiece)) {
                val piece = clean(rawPiece, keepTrailingSpace = true).replace(Regex("\\s+"), " ")
                if (piece.isBlank()) continue
                val r = settle(provider, srcTokens(rawPiece, registry), piece, known)
                val last = out.lastOrNull()
                if (last != null && last.refs.isEmpty() && last.reusedRefs.isEmpty() && r.verified.isEmpty() && r.reused.isEmpty()) out[out.lastIndex] = last.copy(text = last.text + piece) else out += CitedSegment(piece, r.verified, r.reused)
            } else {
                // An uncited block with model-typed pointers: judge each sentence on its own.
                for (sentence in rawPiece.split(SENTENCE)) {
                    val piece = clean(sentence, keepTrailingSpace = true).replace(Regex("\\s+"), " ")
                    if (piece.isBlank()) continue
                    val r = settle(emptyList(), srcTokens(sentence, registry), piece, known)
                    val withSpace = if (piece.endsWith(" ")) piece else "$piece "
                    out += CitedSegment(withSpace, r.verified, r.reused)
                }
            }
        }
        return out
    }

    private fun trimEnds(list: List<CitedSegment>): List<CitedSegment> {
        if (list.isEmpty()) return list
        val out = list.toMutableList()
        out[0] = out[0].copy(text = out[0].text.trimStart())
        out[out.lastIndex] = out[out.lastIndex].copy(text = out[out.lastIndex].text.trimEnd())
        return out.filter { it.text.isNotBlank() }
    }

    /** Model-typed `src=sN` tokens that name a source the provider has cited; discovered-only sources never resolve. */
    private fun srcTokens(line: String, registry: SourceRegistry2): List<Int> =
        SRC.findAll(line).flatMap { m -> Regex("""s(\d+)""").findAll(m.groupValues[1]).map { it.groupValues[1].toInt() } }.filter { registry.isCited(it) }.distinct().toList()

    internal fun clean(s: String, keepTrailingSpace: Boolean = false): String {
        var t = URL.replace(s, "")
        t = SRC.replace(t, "")
        t = BRACKET.replace(t, "")
        t = t.replace("**", "").replace("__", "")
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        return if (keepTrailingSpace) t.trimStart() else t.trim()
    }

    private fun kv(head: String): Map<String, String> = KV.findAll(head).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].trim() }

    private fun dash(v: String): String? = clean(v).takeIf { it.isNotBlank() && it != "-" && !it.equals("none", true) }

    private fun statusOf(raw: String?): RequirementStatus = when (raw?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')) {
        "answered", "done", "found" -> RequirementStatus.ANSWERED
        "your_call", "yourcall", "decide" -> RequirementStatus.YOUR_CALL
        "needs_check", "check", "needscheck" -> RequirementStatus.NEEDS_CHECK
        "partly", "partial", "partially" -> RequirementStatus.PARTLY
        "resolved_conflict", "conflict_resolved" -> RequirementStatus.RESOLVED_CONFLICT
        else -> RequirementStatus.NOT_FOUND
    }

    /** A sourced tag needs a citation; with only a model-typed pointer the line is Restated, with nothing it is Unknown. */
    private fun tagOf(raw: String?, refs: Refs): ProvenanceTag {
        val t = tagOf(raw, refs.verified)
        return if (t == ProvenanceTag.UNKNOWN && refs.reused.isNotEmpty()) ProvenanceTag.SYNTHESIS else t
    }

    /** A tag that asserts something about the world needs a citation; without one it is unconfirmed. */
    internal fun tagOf(raw: String?, refs: List<Int>): ProvenanceTag {
        val t = when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "fact", "facts", "label", "official" -> ProvenanceTag.FACT
            "reviews", "review", "independent" -> ProvenanceTag.REVIEWS
            "community", "forum", "users" -> ProvenanceTag.COMMUNITY
            "judgment", "judgement", "opinion" -> ProvenanceTag.JUDGMENT
            "calc", "calculated", "calculation" -> ProvenanceTag.CALC
            "restated", "synthesis" -> ProvenanceTag.SYNTHESIS
            else -> if (refs.isNotEmpty()) ProvenanceTag.FACT else ProvenanceTag.UNKNOWN
        }
        return if (t.needsSource && refs.isEmpty()) ProvenanceTag.UNKNOWN else t
    }

    private fun calloutKind(raw: String?): CalloutKind = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "health" -> CalloutKind.HEALTH; "cross_item", "crossitem", "overlap" -> CalloutKind.CROSS_ITEM
        "unknown_recipient", "recipient", "gift" -> CalloutKind.UNKNOWN_RECIPIENT; "budget" -> CalloutKind.BUDGET; else -> CalloutKind.INFO
    }

    /** "89.00 USD · 100 ml · Retailer" / "$89 · 3.4 oz · Store". Confirmed only when the line itself is cited (or validly restates a cited price). */
    internal fun parsePrice(text: String, refs: List<Int>, nowEpochMs: Long): Price? {
        val m = PRICE.find(text) ?: return null
        val (symbolOrCode, amountRaw) = when {
            m.groupValues[1].isNotEmpty() -> m.groupValues[1] to m.groupValues[2]
            m.groupValues[3].isNotEmpty() -> m.groupValues[4] to m.groupValues[3]
            else -> m.groupValues[5] to m.groupValues[6]
        }
        val amount = amountRaw.replace(",", "").toDoubleOrNull() ?: return null
        val currency = when (symbolOrCode) { "$" -> "USD"; "£" -> "GBP"; "€" -> "EUR"; else -> symbolOrCode.uppercase(Locale.ROOT) }
        val parts = text.split('·').map { it.trim() }
        val rest = parts.drop(1).filter { it.isNotBlank() }
        val size = rest.firstOrNull { Regex("""\d+(\.\d+)?\s?(ml|oz|g|mg|mcg|iu|count|caps?|capsules|tablets|softgels|servings|l|fl)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val retailer = rest.firstOrNull { it != size }
        return Price(amount, currency, retailer?.take(80), size?.take(60), if (refs.isNotEmpty()) PriceStatus.CONFIRMED else PriceStatus.NOT_CONFIRMED, refs, if (refs.isNotEmpty()) nowEpochMs else null)
    }

    fun defaultProposalId(kind: ProposalKind, title: String, requirementId: String?): String {
        val norm = title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("${kind.name}|${requirementId.orEmpty()}|$norm".toByteArray())
        return "p-" + digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
