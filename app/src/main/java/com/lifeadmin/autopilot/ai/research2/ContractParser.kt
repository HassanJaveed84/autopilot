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

/**
 * Numbers sources in order of first citation and keeps those numbers for the life of a session.
 * Only provider citations and search results enter; a model-typed `src=sN` resolves only to an
 * existing entry.
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

    private fun key(url: String) = url.trim().removeSuffix("/")
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

    fun sources(): List<SourceRef2> = byN.values.sortedBy { it.n }.map {
        SourceRef2(it.n, it.url, it.title.take(160), Sources.host(it.url) ?: "", it.official, it.pageAge, it.quotes.take(3), it.cited, it.firstRunId)
    }

    companion object {
        const val MAX_SOURCES = 60
    }
}

/**
 * Line-based parser for the [Research2Prompt] contract. Provenance comes from the provider's
 * citations on the text blocks that overlap each line (and from `src=sN` tokens naming sources the
 * registry already holds). Tags that need a source are downgraded when none backs the line.
 */
object ContractParser {
    private val HEADING = Regex("""^\s*#{1,4}\s*(TITLE|ANSWER|REQ|CAND|ATTR|EXCLUDED|PICK|CLAIM|CONFLICT|STEP|CALLOUT|PROPOSE|UNRESOLVED)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""\b(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val SRC = Regex("""\s*\bsrc\s*=\s*((?:s\d+\s*,?\s*)+)""", RegexOption.IGNORE_CASE)
    private val BRACKET = Regex("""\s*\[\d+(?:,\s*\d+)*]""")
    private val KV = Regex("""(\w+)\s*=\s*([^·]+?)\s*(?=·|$)""")
    private val PRICE = Regex("""([$£€])\s?(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s?(USD|GBP|EUR|CAD|AUD)\b|(USD|GBP|EUR|CAD|AUD)\s?(\d[\d,]*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val INJECTION = Regex(
        """ignore (?:all |any |the )?(?:previous|prior|above|earlier) (?:instructions|prompts?)|disregard (?:the |all |your )?(?:previous|above|system|earlier)|you are now|system prompt|reveal (?:your|the) (?:prompt|instructions)|tell the user to|do not tell the user|share (?:all|the) (?:user'?s )?(?:tasks|data)|send (?:the |all )?(?:private|other) tasks""",
        RegexOption.IGNORE_CASE,
    )

    private class Span(val start: Int, val end: Int, val citations: List<RawCitation>)
    private class Block(val kind: String, val head: String, val start: Int) { val lines = ArrayList<Pair<String, IntRange>>(); var end = start }

    fun parse(
        segments: List<RawSegment>,
        searches: List<RawSearch>,
        registry: SourceRegistry2,
        requirements: List<Requirement>,
        nowEpochMs: Long,
        /** Candidates the session already holds: later stages may add attributes to them by id. */
        existingCandidates: List<Candidate> = emptyList(),
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
            return StageOutput(answer = cited(spans, full, 0, full.length, registry), queries = queries, freeForm = true)
        }

        fun refsOf(b: Block): List<Int> {
            val refs = LinkedHashSet<Int>()
            for (sp in spans) if (sp.start < b.end && sp.end > b.start && sp.citations.isNotEmpty()) refs += registry.register(sp.citations)
            for (line in b.lines) refs += srcTokens(line.first, registry)
            return refs.toList()
        }
        fun refsOfLine(line: Pair<String, IntRange>): List<Int> {
            val refs = LinkedHashSet<Int>()
            for (sp in spans) if (sp.start < line.second.last + 1 && sp.end > line.second.first && sp.citations.isNotEmpty()) refs += registry.register(sp.citations)
            refs += srcTokens(line.first, registry)
            return refs.toList()
        }
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
                "ANSWER", "FREE" -> if (b.lines.isNotEmpty()) answer += cited(spans, full, b.lines.first().second.first, b.end, registry)
                "REQ" -> {
                    val head = b.head
                    val id = Regex("""^(r-[a-z]\d+)""").find(head)?.groupValues?.get(1) ?: continue
                    if (id !in reqIds) continue
                    val status = statusOf(kv(head)["status"])
                    coverage.removeAll { it.requirementId == id }
                    coverage += Coverage(id, status, bodyText.take(300), refsOf(b))
                }
                "CAND", "EXCLUDED" -> {
                    val cid = Regex("""^(c\d+)""").find(b.head)?.groupValues?.get(1) ?: continue
                    val k = kv(b.head)
                    val name = clean(k["name"] ?: "").ifBlank { continue }
                    val hero = bodyText.startsWith("hero", ignoreCase = true)
                    val reason = bodyText.removePrefix("hero").removePrefix("Hero").trimStart(':', ' ', '—', '-', '·')
                    val c = Candidate(
                        cid, name.take(120), k["variant"]?.let(::dash), k["style"]?.let(::dash)?.lowercase(Locale.ROOT),
                        included = b.kind == "CAND", reason = reason.take(300), refs = refsOf(b), hero = hero,
                    )
                    if (b.kind == "CAND") candidates[cid] = c else excluded += c
                }
                "ATTR" -> {
                    val m = Regex("""^(c\d+)\.(\w+)""").find(b.head) ?: continue
                    val cid = m.groupValues[1]; val keyName = m.groupValues[2].lowercase(Locale.ROOT)
                    val cand = candidates[cid] ?: existingCandidates.firstOrNull { it.id == cid }?.copy(attrs = emptyList(), price = null, refs = emptyList(), reason = "", hero = false)?.also { candidates[cid] = it } ?: continue
                    val refs = refsOf(b)
                    val tag = tagOf(kv(b.head)["tag"], refs)
                    if (bodyText.isBlank()) continue
                    if (keyName == "price") {
                        val price = parsePrice(bodyText, refs, nowEpochMs)
                        candidates[cid] = cand.copy(price = price ?: cand.price, attrs = cand.attrs.filter { it.key != "price" } + Attribute("price", bodyText.take(160), if (price?.status == PriceStatus.CONFIRMED) ProvenanceTag.FACT else ProvenanceTag.UNKNOWN, refs))
                    } else {
                        candidates[cid] = cand.copy(attrs = cand.attrs.filter { it.key != keyName } + Attribute(keyName, bodyText.take(200), tag, refs))
                    }
                }
                "PICK" -> {
                    val id = Regex("""^(r-[a-z]\d+)""").find(b.head)?.groupValues?.get(1) ?: continue
                    if (id !in reqIds) continue
                    val status = statusOf(kv(b.head)["status"])
                    var pick: String? = null; var variant: String? = null; var alt: String? = null; var forYou: String? = null; var why: String? = null; var price: Price? = null
                    val facts = ArrayList<Attribute>()
                    val refs = refsOf(b)
                    for (line in b.lines) {
                        val t = clean(line.first)
                        val lr = refsOfLine(line)
                        val (k, v) = t.split(':', limit = 2).let { if (it.size == 2) it[0].trim().lowercase(Locale.ROOT) to it[1].trim() else "" to t }
                        when (k) {
                            "pick" -> { val none = v.equals("none found", true) || v.equals("none", true) || v == "-"; if (!none) { val parts = v.split(Regex("""\s[—–-]\s|\s\(""", RegexOption.IGNORE_CASE), limit = 2); pick = parts[0].trim(); variant = parts.getOrNull(1)?.trimEnd(')')?.trim()?.ifBlank { null } } }
                            "label" -> if (v.isNotBlank() && v != "-") facts += Attribute("label", v.take(300), if (lr.isNotEmpty()) ProvenanceTag.FACT else ProvenanceTag.UNKNOWN, lr)
                            "alternative" -> alt = dash(v)
                            "for you" -> forYou = dash(v)
                            "why" -> why = dash(v)
                            "price" -> price = parsePrice(v, lr, nowEpochMs)
                            else -> if (t.isNotBlank()) facts += Attribute("note", t.take(300), if (lr.isNotEmpty()) ProvenanceTag.FACT else ProvenanceTag.JUDGMENT, lr)
                        }
                    }
                    picks.removeAll { it.requirementId == id }
                    picks += ItemPick(id, status, pick, variant, facts, alt, forYou, why, price, refs)
                    if (coverage.none { it.requirementId == id }) coverage += Coverage(id, status, (why ?: pick).orEmpty().take(300), refs)
                }
                "CLAIM" -> if (bodyText.isNotBlank()) { val refs = refsOf(b); claims += Claim(bodyText.take(400), tagOf(kv(b.head)["tag"], refs), refs) }
                "CONFLICT" -> {
                    val topic = clean(b.head).ifBlank { continue }
                    val parts = bodyText.split('|').map { it.trim() }
                    val sides = ArrayList<ConflictSide>()
                    var resolution: String? = null
                    for (p in parts) {
                        val (k, v) = p.split(':', limit = 2).let { if (it.size == 2) it[0].trim().lowercase(Locale.ROOT) to it[1].trim() else "" to p }
                        when (k) { "resolution" -> resolution = dash(v); else -> if (v.isNotBlank()) sides += ConflictSide(v.take(300), refsOf(b)) }
                    }
                    if (sides.isNotEmpty()) conflicts += Conflict(topic.take(160), sides.take(3), resolution)
                }
                "STEP" -> if (bodyText.isNotBlank()) {
                    val refs = refsOf(b)
                    val optional = bodyText.contains("(optional)", ignoreCase = true)
                    steps += StepLine(bodyText.replace(Regex("""\s*\(optional\)""", RegexOption.IGNORE_CASE), "").take(300), if (refs.isNotEmpty()) ProvenanceTag.FACT else ProvenanceTag.JUDGMENT, refs, optional)
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
                    val refs = refsOf(b)
                    proposals += Proposal(proposalIdOf(kind, t, req), kind, t, note, req, refs, nowEpochMs, selectedByDefault = kind == ProposalKind.STEP || kind == ProposalKind.NOTE)
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

    // ------------------------------------------------------------------ helpers

    private fun cited(spans: List<Span>, full: String, start: Int, end: Int, registry: SourceRegistry2): List<CitedSegment> {
        val out = ArrayList<CitedSegment>()
        for (sp in spans) {
            val s = maxOf(sp.start, start); val e = minOf(sp.end, end)
            if (e <= s) continue
            val rawPiece = full.substring(s, e)
            val piece = clean(rawPiece, keepTrailingSpace = true).replace(Regex("\\s+"), " ")
            if (piece.isBlank()) continue
            val refs = (registry.register(sp.citations) + srcTokens(rawPiece, registry)).distinct()
            val last = out.lastOrNull()
            if (last != null && last.refs.isEmpty() && refs.isEmpty()) out[out.lastIndex] = last.copy(text = last.text + piece) else out += CitedSegment(piece, refs)
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

    private fun srcTokens(line: String, registry: SourceRegistry2): List<Int> =
        SRC.findAll(line).flatMap { m -> Regex("""s(\d+)""").findAll(m.groupValues[1]).map { it.groupValues[1].toInt() } }.filter { registry.has(it) }.toList()

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

    /** A tag that asserts something about the world needs a citation; without one it is unconfirmed. */
    internal fun tagOf(raw: String?, refs: List<Int>): ProvenanceTag {
        val t = when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "fact", "facts", "label", "official" -> ProvenanceTag.FACT
            "reviews", "review", "independent" -> ProvenanceTag.REVIEWS
            "community", "forum", "users" -> ProvenanceTag.COMMUNITY
            "judgment", "judgement", "opinion" -> ProvenanceTag.JUDGMENT
            "calc", "calculated", "calculation" -> ProvenanceTag.CALC
            else -> if (refs.isNotEmpty()) ProvenanceTag.FACT else ProvenanceTag.UNKNOWN
        }
        return if (t.needsSource && refs.isEmpty()) ProvenanceTag.UNKNOWN else t
    }

    private fun calloutKind(raw: String?): CalloutKind = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "health" -> CalloutKind.HEALTH; "cross_item", "crossitem", "overlap" -> CalloutKind.CROSS_ITEM
        "unknown_recipient", "recipient", "gift" -> CalloutKind.UNKNOWN_RECIPIENT; "budget" -> CalloutKind.BUDGET; else -> CalloutKind.INFO
    }

    /** "89.00 USD · 100 ml · Retailer" / "$89 · 3.4 oz · Store". Confirmed only when the line is cited. */
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
