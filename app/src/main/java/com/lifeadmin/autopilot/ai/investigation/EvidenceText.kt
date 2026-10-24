package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.SourceTier
import com.lifeadmin.autopilot.ai.research.Sources
import com.lifeadmin.autopilot.ai.research2.SourceRef2
import java.util.Locale

/**
 * Numbers sources in order of first appearance and keeps those numbers for the life of an
 * investigation. Two kinds of entry are kept apart:
 * - **cited**: the provider attached the page to a text block as a citation (`citedCount > 0`);
 * - **discovered**: the page was only returned by a search and never cited.
 * A model-typed `src=sN` resolves only to a *cited* entry, and even then as a pointer, never as
 * verification (RES-044/101).
 */
class SourceRegistry(seed: List<SourceRef2> = emptyList(), private val turnId: String? = null) {
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

    private fun key(url: String) = url.trim().substringBefore('#').removeSuffix("/")
    private fun nextN(): Int = (byN.keys.maxOrNull() ?: 0) + 1

    fun register(citations: List<RawCitation>): List<Int> {
        val refs = LinkedHashSet<Int>()
        for (c in citations) {
            val url = Sources.safeExternalUrl(c.url) ?: continue
            val src = byUrl.getOrPut(key(url)) {
                Mutable(nextN(), url, c.title?.ifBlank { null } ?: Sources.host(url) ?: url, Sources.tier(url) == SourceTier.OFFICIAL, null, turnId).also { byN[it.n] = it }
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
        val m = Mutable(nextN(), url, r.title.ifBlank { Sources.host(url) ?: url }, Sources.tier(url) == SourceTier.OFFICIAL, r.pageAge, turnId)
        byUrl[key(url)] = m; byN[m.n] = m
    }

    fun has(n: Int): Boolean = byN.containsKey(n)
    fun isCited(n: Int): Boolean = (byN[n]?.cited ?: 0) > 0
    fun originKey(n: Int): String = byN[n]?.let { Sources.host(it.url) } ?: "s$n"

    /** The page title the provider gave for a source (or its host when it gave none). */
    fun title(n: Int): String? = byN[n]?.title

    fun url(n: Int): String? = byN[n]?.url

    /** A government host by domain; only a fallback when the reply did not say what kind of source it is. */
    fun official(n: Int): Boolean = byN[n]?.official == true

    fun sources(): List<SourceRef2> = byN.values.sortedBy { it.n }.map {
        SourceRef2(it.n, it.url, it.title.take(160), Sources.host(it.url) ?: "", it.official, it.pageAge, it.quotes.take(3), it.cited, it.firstRunId)
    }

    companion object {
        const val MAX_SOURCES = 60
    }
}

/** A cited line the investigation already holds; a later restatement that matches it keeps its evidence. */
data class EvidenceLine(val text: String, val refs: List<Int>)

/** Evidence for one line: what verifies it and what merely points elsewhere. */
data class LineRefs(val verified: List<Int>, val reused: List<Int>) {
    companion object { val NONE = LineRefs(emptyList(), emptyList()) }
}

/**
 * Text and citation helpers shared by the parsers (moved from the M3A.2 contract parser; the
 * provenance rules are unchanged): provider citations verify; model-typed `src=` tokens are
 * pointers unless the line restates a held evidence line that those sources back.
 */
object EvidenceText {
    val URL = Regex("""\b(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    val SRC = Regex("""\s*\bsrc\s*=\s*((?:s\d+\s*,?\s*)+)""", RegexOption.IGNORE_CASE)
    val BRACKET = Regex("""\s*\[\d+(?:,\s*\d+)*]""")
    /** A sentence end: the punctuation, any closing quote or bracket, then the markers that close the sentence. */
    private val SENTENCE_END = Regex("""[.!?]["'”’)]*((?:\s*\[\s*c\d+(?:\s*,\s*c\d+)*\s*])*)(?=\s+["'“‘(]?[A-Z0-9])""")
    /** Words whose trailing period is not a sentence end ("Dr. Smith", "Main St. Station", "the U.S. rule"). */
    private val ABBREVIATIONS = setOf(
        "st", "ave", "rd", "blvd", "ln", "dr", "mr", "mrs", "ms", "prof", "no", "nos", "inc", "ltd", "co", "corp", "jr", "sr", "vs", "etc", "mt", "ft", "approx", "dept",
        "gov", "sen", "rep", "fig", "vol", "ch", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "e.g", "i.e", "u.s", "u.k", "d.c", "a.m", "p.m",
    )

    /**
     * Splits one line of model text into sentences, each with the claim markers it carries itself. A marker written
     * after the full stop ("…20002. [c1] The station…") belongs to the sentence it follows.
     */
    fun sentences(raw: String): List<AttributedSentence> {
        val out = ArrayList<AttributedSentence>()
        fun add(piece: String) {
            val text = clean(piece).replace(Regex("\\s+"), " ").replace(Regex("""\s+([.,;:!?])"""), "$1").trim()
            if (text.isNotEmpty()) out += AttributedSentence(text, markers(piece))
        }
        var start = 0
        for (m in SENTENCE_END.findAll(raw)) {
            val word = Regex("""([\p{L}.]+)$""").find(raw.substring(start, m.range.first))?.value?.lowercase(Locale.ROOT)?.trimEnd('.')
            if (word != null && (word in ABBREVIATIONS || word.length == 1)) continue
            add(raw.substring(start, m.range.last + 1))
            start = m.range.last + 1
        }
        add(raw.substring(start))
        return out
    }

    /** Claim markers in prose: [c1], [c1, c2], [c1][c2]. */
    val MARKER = Regex("""\[\s*(c\d+(?:\s*,\s*c\d+)*)\s*]""")
    val INJECTION = Regex(
        """ignore (?:all |any |the )?(?:previous|prior|above|earlier) (?:instructions|prompts?)|disregard (?:the |all |your )?(?:previous|above|system|earlier)|you are now|system prompt|reveal (?:your|the) (?:prompt|instructions)|tell the user to|do not tell the user|share (?:all|the) (?:user'?s )?(?:tasks|data)|send (?:the |all )?(?:private|other) tasks""",
        RegexOption.IGNORE_CASE,
    )

    class Span(val start: Int, val end: Int, val citations: List<RawCitation>)

    /** Concatenates segments and keeps the citation spans. */
    fun spans(segments: List<RawSegment>): Pair<String, List<Span>> {
        val text = StringBuilder()
        val spans = ArrayList<Span>()
        for (s in segments) { val st = text.length; text.append(s.text); spans += Span(st, text.length, s.citations) }
        return text.toString() to spans
    }

    fun providerRefsOf(spans: List<Span>, range: IntRange, registry: SourceRegistry): List<Int> {
        val refs = LinkedHashSet<Int>()
        for (sp in spans) if (sp.start < range.last + 1 && sp.end > range.first && sp.citations.isNotEmpty()) refs += registry.register(sp.citations)
        return refs.toList()
    }

    fun citationsOf(spans: List<Span>, range: IntRange): List<RawCitation> {
        val out = ArrayList<RawCitation>()
        for (sp in spans) if (sp.start < range.last + 1 && sp.end > range.first && sp.citations.isNotEmpty()) out += sp.citations
        return out
    }

    fun settle(provider: List<Int>, tokens: List<Int>, text: String, known: List<EvidenceLine>): LineRefs {
        val pointers = tokens.filter { it !in provider }
        if (pointers.isEmpty()) return LineRefs(provider, emptyList())
        val validated = LinkedHashSet<Int>()
        for (e in known) {
            val shared = e.refs.filter { it in pointers }
            if (shared.isNotEmpty() && restates(text, e.text)) validated += shared
        }
        return LineRefs((provider + validated).distinct(), pointers.filter { it !in validated })
    }

    /** Two claim texts state the same thing: equal once normalized, or a restatement with every value unchanged. */
    fun sameStatement(a: String, b: String): Boolean = canon(a).let { it.isNotEmpty() && it == canon(b) } || restates(a, b)

    /** The same statement, allowing trimming and small rewording; every value must match exactly. */
    fun restates(line: String, evidence: String): Boolean {
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
    private fun values(canonical: String): List<String> = Regex("""\b\d[\d.]*[a-z%]*""").findAll(canonical).map { it.value }.toList()

    /** Model-typed `src=sN` tokens that name a source the provider has cited; discovered-only sources never resolve. */
    fun srcTokens(line: String, registry: SourceRegistry): List<Int> =
        SRC.findAll(line).flatMap { m -> Regex("""s(\d+)""").findAll(m.groupValues[1]).map { it.groupValues[1].toInt() } }.filter { registry.isCited(it) }.distinct().toList()

    /** Claim ids named by [c…] markers in a line, in order. */
    fun markers(line: String): List<String> = MARKER.findAll(line).flatMap { m -> m.groupValues[1].split(',').map { it.trim() } }.filter { it.isNotEmpty() }.distinct().toList()

    /** Strips URLs, src tokens, bracket citations, claim markers and bold markup. */
    fun clean(s: String, keepTrailingSpace: Boolean = false): String {
        var t = URL.replace(s, "")
        t = SRC.replace(t, "")
        t = MARKER.replace(t, "")
        t = BRACKET.replace(t, "")
        t = t.replace("**", "").replace("__", "")
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        return if (keepTrailingSpace) t.trimStart() else t.trim()
    }

    /** `key=value · key=value` heading attributes. */
    fun kv(head: String): Map<String, String> = Regex("""(\w+)\s*=\s*([^·]+?)\s*(?=·|$)""").findAll(head).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].trim() }

    fun yes(v: String?): Boolean = v?.trim()?.lowercase(Locale.ROOT) in setOf("yes", "true", "y", "1")
}

/**
 * What an evidence row shows. The page's own cited words are the only text presented as a quotation; the
 * reply's line is the model's summary and is labelled as such, so a paraphrase never reads as the page's words.
 */
object EvidenceDisplay {
    /** The verbatim cited text, or null when the provider gave none for this line. */
    fun quoted(e: EvidenceRef): String? = e.quote?.trim()?.takeIf { it.isNotEmpty() }?.take(240)

    /**
     * The model's line, labelled; omitted when it only repeats the quoted words. Contract syntax never shows: a line
     * whose labels could not be read gets no summary, and any label left in a line is removed.
     */
    fun summary(e: EvidenceRef): String? {
        if (e.labelsUnreadable) return "Claude's summary is not shown: its labels could not be read, so this source is not counted."
        val line = LeakageScan.clean(EvidenceAttrs.withoutLabels(e.excerpt)).trim().takeIf { it.isNotEmpty() } ?: return null
        val quote = quoted(e) ?: return "Claude's summary (not the page's words): ${line.take(240)}"
        return if (norm(quote).contains(norm(line))) null else "Claude's summary: ${line.take(240)}"
    }

    /** The note under an evidence row: why it does not count, what it is about, or what its date rests on. */
    fun note(e: EvidenceRef): String? = when {
        e.pointerOnly -> "Points to a source cited elsewhere; it doesn't verify this."
        e.labelsUnreadable -> "Its labels could not be read, so it is kept here for inspection but not counted."
        e.scopeNote.isNotBlank() -> e.scopeNote
        e.asOf != null && e.asOfBasis == DateBasis.QUOTED -> "As of ${e.asOf}"
        e.asOf != null -> "Dated ${e.asOf} by Claude; the page's cited text does not show this date"
        else -> null
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}

/**
 * Whether a text shows a date (RES-046). A date counts as shown when the text has it in ISO form, or its year and,
 * at month or day precision, the month (by name, three-letter abbreviation or number) and the day. A year alone
 * shows only a year-precision date. This is how a date a reply gives is checked against the page's cited words.
 */
object DateSupport {
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val DATE = Regex("""^(\d{4})(?:-(\d{2}))?(?:-(\d{2}))?$""")

    fun shown(date: String?, text: String?): Boolean = date != null && shownPrecision(date, text) == date.trim()

    /**
     * The most precise part of [date] the text shows: the whole date, its `YYYY-MM`, its `YYYY`, or null. A page that
     * says "October 2026" supports a claimed 2026-10-23 only to the month.
     */
    fun shownPrecision(date: String, text: String?): String? {
        val d = date.trim()
        val m = DATE.matchEntire(d) ?: return null
        val t = text?.lowercase(Locale.ROOT) ?: return null
        if (t.contains(d)) return d
        val y = m.groupValues[1]
        if (!Regex("""\b$y\b""").containsMatchIn(t)) return null
        val month = m.groupValues[2].toIntOrNull() ?: return y
        if (month !in 1..12) return y
        val name = MONTHS[month - 1]
        val monthShown = Regex("""\b(?:$name|${name.take(3)})\b""").containsMatchIn(t) ||
            Regex("""\b0?$month[/.-]$y\b|\b$y[/.-]0?$month\b|\b\d{1,2}[/.-]0?$month[/.-]$y\b|\b0?$month[/.-]\d{1,2}[/.-]$y\b""").containsMatchIn(t)
        if (!monthShown) return y
        val day = m.groupValues[3].toIntOrNull() ?: return "$y-${m.groupValues[2]}"
        return if (Regex("""\b0?$day(?:st|nd|rd|th)?\b""").containsMatchIn(t)) d else "$y-${m.groupValues[2]}"
    }
}
