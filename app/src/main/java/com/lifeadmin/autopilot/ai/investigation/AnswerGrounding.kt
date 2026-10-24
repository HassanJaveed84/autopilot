package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.Sources
import java.text.Normalizer
import java.util.Locale

/** Something a sentence states that the cited text does not carry (N4). [span] is the sentence's own words; empty for the whole sentence. */
data class GroundingIssue(val kind: Kind, val span: String = "") {
    enum class Kind {
        /** A checkable detail (a number with its unit, a name, a place, a date) that no cited text shows. */
        DETAIL,
        /** A certainty or universality word ("prevents", "always", "for every") the cited text does not use. */
        CERTAINTY,
        /** Research evidence that is hedged ("may", "limited") behind a sentence that is not. */
        TENTATIVE,
        /** Words the model-assisted wording check found go further than the cited text. */
        OVERSTATED,
    }
}

/**
 * N4, Document F §4 evidence grounding: a sentence is checked against the verbatim cited text of the claims it names,
 * never against the claims' own wording or the presence of a marker. The checks are deliberately narrow and lexical:
 * a checkable detail must appear in the cited text, a certainty word must be the source's own, and hedged research
 * evidence must not be stated flatly. They can show that something is *not* supported; they never prove that a
 * paraphrase is entailed. The model-assisted wording check ([WordingCheck]) covers meaning for consequential answers.
 * Pure and configuration-free.
 */
object Grounding {
    /** What the cited evidence of a sentence or claim says, normalized for matching. */
    class Corpus(private val texts: List<String>, private val quotes: List<String>, val research: Boolean, val uncheckable: Boolean = false) {
        val tokens: List<String> = texts.flatMap { tokens(it) + SEP }
        val isEmpty: Boolean get() = uncheckable || tokens.none { it != SEP }
        fun has(seq: List<String>): Boolean = seq.isNotEmpty() && tokens.windowed(seq.size).any { it == seq }
        private val words: Set<String> by lazy { tokens.toSet() - SEP }
        fun hasWord(t: String): Boolean = t in words || (t.length >= 4 && words.any { u -> u.length >= 4 && (u.startsWith(t) || t.startsWith(u)) })
        /** Only the pages' own words can be hedged; titles and addresses are not read for this ("may" in a URL is a month). */
        val hedged: Boolean get() = quotes.any { LIMITING.containsMatchIn(it) }
        operator fun plus(o: Corpus) = Corpus(texts + o.texts, quotes + o.quotes, research || o.research, uncheckable || o.uncheckable)

        companion object {
            val EMPTY = Corpus(emptyList(), emptyList(), false)
            /** A named claim whose evidence holds no verbatim text (a record from before quotes were kept): nothing can be checked. */
            val UNCHECKABLE = Corpus(emptyList(), emptyList(), false, uncheckable = true)
        }
    }

    /** A checkable detail in a sentence and where it is. */
    data class Atom(val span: String, val start: Int, val end: Int, val tokens: List<String>, val name: Boolean = false) {
        /**
         * A number must appear with its unit, in order. A name's words must appear, in any order ("Acme D3" on acme.example),
         * a word also matching a longer or shorter form of itself ("Postgres", "PostgreSQL"); a name of three or more words
         * may miss one ("Dior Sauvage EDT" on a "Sauvage EDT review").
         */
        fun shownIn(c: Corpus): Boolean = c.has(tokens) || (name && tokens.count { !c.hasWord(it) } <= (if (tokens.size >= 3) 1 else 0))
    }

    /** Evidence that counts for grounding: verified, readable, in scope, with the page's own words. */
    fun usable(e: EvidenceRef): Boolean = !e.pointerOnly && !e.labelsUnreadable && e.scopeNote.isBlank()

    /** The page text, title and site name of [evidence]: never the reply's own summary or labels. */
    fun corpusOf(evidence: List<EvidenceRef>, extra: List<String> = emptyList()): Corpus {
        val rows = evidence.filter(::usable)
        if (rows.none { it.quote != null }) return if (rows.isEmpty()) Corpus.EMPTY else Corpus.UNCHECKABLE
        val texts = rows.flatMap { listOfNotNull(it.quote, it.pageTitle, it.pageUrl?.let(::urlWords), Sources.siteName(it.originKey).takeIf { s -> s.length >= 3 }) } + extra
        return Corpus(texts, rows.mapNotNull { it.quote }, rows.any { it.kind == EvidenceKind.STUDY || it.kind == EvidenceKind.REVIEW })
    }

    /**
     * The corpus a sentence naming [claimIds] is checked against. A judgment or recommendation is grounded by its
     * premises; a calculation or inference may also use its own stated result, which is labelled as such. An empty
     * corpus (no verbatim text held, for example a record stored before quotes were kept) means "cannot check".
     */
    fun corpusFor(claimIds: List<String>, byId: Map<String, Claim>, depth: Int = 0): Corpus {
        var out = Corpus.EMPTY
        for (c in claimIds.mapNotNull { byId[it] }.filter { !it.superseded }) {
            out += when {
                c.type.derived -> if (depth < 3) corpusFor(c.premises, byId, depth + 1) else Corpus.EMPTY
                c.type == ClaimType.FACT -> corpusOf(c.support)
                else -> corpusOf(c.support, listOf(c.text))
            }
        }
        return out
    }

    /** The words of a page address's host and path, without the scheme or query. */
    private fun urlWords(url: String): String = url.substringAfter("://").substringBefore('?').substringBefore('#').replace(Regex("[/_.+-]+"), " ")

    /** The verbatim cited text behind [claimIds] (premises for a judgment), for the model-assisted check. */
    fun quotesFor(claimIds: List<String>, byId: Map<String, Claim>, depth: Int = 0): List<String> =
        claimIds.mapNotNull { byId[it] }.filter { !it.superseded }.flatMap { c ->
            if (c.type.derived) (if (depth < 3) quotesFor(c.premises, byId, depth + 1) else emptyList()) else c.support.filter(::usable).mapNotNull { it.quote }
        }.distinct().take(4)

    /** The details in a fact claim's own text that its verified cited text does not show; empty when it cannot be checked. */
    fun missingFromClaim(c: Claim, usable: List<EvidenceRef>): List<String> {
        if (c.type != ClaimType.FACT) return emptyList()
        val corpus = corpusOf(usable)
        if (corpus.isEmpty) return emptyList()
        return atoms(c.text).filterNot { it.shownIn(corpus) }.map { it.span }.distinct()
    }

    /** Every issue in one sentence; empty when the corpus is empty (nothing to check against). */
    fun issues(sentence: String, corpus: Corpus): List<Pair<GroundingIssue, IntRange?>> {
        if (corpus.isEmpty) return emptyList()
        val text = withoutMarkers(sentence)
        val out = ArrayList<Pair<GroundingIssue, IntRange?>>()
        for (a in atoms(text)) if (!a.shownIn(corpus)) out += GroundingIssue(GroundingIssue.Kind.DETAIL, a.span) to (a.start until a.end)
        for (m in CERTAINTY.findAll(text)) if (!corpus.has(tokens(m.value))) out += GroundingIssue(GroundingIssue.Kind.CERTAINTY, m.value) to m.range
        if (corpus.research && corpus.hedged && !StatusQualifier.HEDGED.containsMatchIn(text) && !LIMITING.containsMatchIn(text)) out += GroundingIssue(GroundingIssue.Kind.TENTATIVE) to null
        return out
    }

    /** What to do with one sentence: its text with unsupported separable clauses removed, and the issues that remain. */
    class Review(val text: String, val remaining: List<GroundingIssue>, val removedAny: Boolean)

    /**
     * Removes each separable clause (", where …", ", which …", ", also …", a parenthesis or a dash aside) that holds an
     * unsupported detail; the main clause and any contrastive clause (", but …", ", unless …") stay, because dropping
     * a stated exception could mislead. Issues outside the removed clauses remain.
     */
    fun review(sentence: String, issues: List<Pair<GroundingIssue, IntRange?>>): Review {
        val text = withoutMarkers(sentence)
        if (issues.isEmpty()) return Review(text, emptyList(), false)
        val drop = clauses(text).filter { cl -> cl.removable && issues.any { (_, r) -> r != null && r.first >= cl.range.first && r.last <= cl.range.last } }
        val remaining = issues.filter { (_, r) -> r == null || drop.none { r.first >= it.range.first && r.last <= it.range.last } }.map { it.first }
        if (drop.isEmpty()) return Review(text, remaining, false)
        val sb = StringBuilder()
        var at = 0
        for (cut in drop.map { it.cut }.sortedBy { it.first }) {
            if (cut.first < at) { at = maxOf(at, cut.last + 1); continue }
            sb.append(text, at, cut.first); at = cut.last + 1
        }
        sb.append(text.substring(at))
        val tidy = sb.toString().replace(Regex("""\s+([.,;:!?])"""), "$1").replace(Regex("""[,;]\s*([.!?])$"""), "$1").replace(Regex("\\s{2,}"), " ").trim()
        return Review(tidy, remaining, true)
    }

    /** The plain-words note for issues that remain in a sentence that is kept: "the cited sources do not show: 18:00". */
    fun qualify(text: String, remaining: List<GroundingIssue>): String {
        if (remaining.isEmpty()) return text
        val details = remaining.filter { it.kind == GroundingIssue.Kind.DETAIL }.map { it.span }
        val words = remaining.filter { it.kind == GroundingIssue.Kind.CERTAINTY || it.kind == GroundingIssue.Kind.OVERSTATED }.map { "“${it.span.trim()}”" }
        val wholeOverstated = remaining.any { it.kind == GroundingIssue.Kind.OVERSTATED && it.span.isBlank() }
        val tentative = remaining.any { it.kind == GroundingIssue.Kind.TENTATIVE }
        // The whole sentence, not a part of it, goes beyond the cited text: say so up front.
        if (wholeOverstated) return "Not shown by the cited sources: $text"
        val notes = buildList {
            val shown = (details + words.filter { it != "“”" }).distinct()
            if (shown.isNotEmpty()) add("the cited sources do not show: " + shown.joinToString(", "))
            if (tentative) add("the cited evidence is more tentative than this")
        }
        val body = text.trimEnd()
        val end = body.lastOrNull()?.takeIf { it == '.' || it == '!' || it == '?' }
        val stem = if (end != null) body.dropLast(1) else body
        return "$stem (${notes.joinToString("; ")})${end ?: ""}"
    }

    // ------------------------------------------------------------------ details

    /**
     * Checkable details: numbers (with a unit that follows them), and names: runs of capitalized words, a word with
     * an inner capital or digit, joined by "of", "the", "and", "de" or "&". A lone capitalized word at the start of a
     * sentence and a lone short acronym ("ZIP", "DC") are left out: they cannot be told apart from ordinary words.
     */
    fun atoms(text: String): List<Atom> {
        val words = WORD.findAll(text).toList()
        val out = ArrayList<Atom>()
        var i = 0
        fun initial(ix: Int): Boolean {
            if (ix == 0) return true
            val gap = text.substring(words[ix - 1].range.last + 1, words[ix].range.first)
            return Regex("""[.!?:]\s*["“‘(]?\s*$""").containsMatchIn(gap)
        }
        fun cap(w: String) = w.first().isUpperCase() || w.drop(1).any { it.isUpperCase() }
        fun joined(a: Int, b: Int) = text.substring(words[a].range.last + 1, words[b].range.first).let { it.isNotEmpty() && it.isBlank() }
        while (i < words.size) {
            val w = words[i].value
            if (w.any { it.isDigit() } && !cap(w)) {
                // A number, with the unit word or percent sign that directly follows it.
                val start = words[i].range.first
                var end = words[i].range.last + 1
                if (end < text.length && text[end] == '%') end++
                else if (i + 1 < words.size && joined(i, i + 1) && norm1(words[i + 1].value) in UNITS) { i++; end = words[i].range.last + 1 }
                val span = text.substring(start, end)
                out += Atom(span, start, end, tokens(span))
                i++; continue
            }
            if (!cap(w) || w.lowercase(Locale.ROOT) in NOT_NAMES) { i++; continue }
            var j = i
            while (j + 1 < words.size) {
                val next = words[j + 1].value
                if (joined(j, j + 1) && cap(next) && next.lowercase(Locale.ROOT) !in NOT_NAMES) { j++; continue }
                if (j + 2 < words.size && joined(j, j + 1) && joined(j + 1, j + 2) && next.lowercase(Locale.ROOT) in CONNECTORS && cap(words[j + 2].value)) { j += 2; continue }
                break
            }
            var first = i
            while (first < j && words[first].value.lowercase(Locale.ROOT) in LEADING) first++
            val single = first == j
            val w0 = words[first].value
            val skip = single && ((initial(first) && !w0.drop(1).any { it.isUpperCase() || it.isDigit() }) || (w0.length <= 4 && w0.all { it.isUpperCase() }))
            if (!skip) {
                val span = text.substring(words[first].range.first, words[j].range.last + 1)
                out += Atom(span, words[first].range.first, words[j].range.last + 1, tokens(span), name = true)
            }
            i = j + 1
        }
        return out.filter { it.tokens.isNotEmpty() }
    }

    private fun withoutMarkers(s: String) = EvidenceText.MARKER.replace(s, "").replace(Regex("""\s+([.,;:!?])"""), "$1").replace(Regex("\\s{2,}"), " ").trim()

    // ------------------------------------------------------------------ clauses

    private class Clause(val range: IntRange, val cut: IntRange, val removable: Boolean)

    /**
     * The main clause, each clause after a comma, semicolon or dash, and each parenthesis. [Clause.cut] is what removing
     * the clause takes out, its separator included; the main clause is never removable.
     */
    private fun clauses(text: String): List<Clause> {
        val bodyEnd = text.trimEnd().let { t -> if (t.isNotEmpty() && t.last() in ".!?") t.length - 1 else t.length }
        val body = text.substring(0, bodyEnd)
        val parens = Regex("""\s*\([^()]*\)""").findAll(body).map { it.range }.toList()
        val seps = Regex(""",\s+|;\s+|\s+[—–]\s+""").findAll(body).filter { m -> parens.none { m.range.first in it } }.toList()
        val out = ArrayList<Clause>()
        var start = 0
        var cut = -1
        fun close(endExclusive: Int) {
            if (endExclusive <= start) return
            val first = Regex("""\p{L}+""").find(body.substring(start, endExclusive))?.value?.lowercase(Locale.ROOT)
            out += Clause(start until endExclusive, if (cut >= 0) cut until endExclusive else IntRange.EMPTY, cut >= 0 && first in REMOVABLE)
        }
        for (m in seps) { close(m.range.first); cut = m.range.first; start = m.range.last + 1 }
        close(bodyEnd)
        parens.forEach { out += Clause(it, it, true) }
        return out
    }

    /** Where [span] (words a checker quoted from the sentence) sits in the sentence, ignoring case and spacing. */
    fun locate(sentence: String, span: String): IntRange? {
        val text = withoutMarkers(sentence)
        val want = span.trim().trimEnd('.', ',', ';').replace(Regex("\\s+"), " ")
        if (want.isEmpty()) return null
        val at = text.replace(Regex("\\s"), " ").indexOf(want, ignoreCase = true)
        return if (at < 0) null else at until at + want.length
    }

    // ------------------------------------------------------------------ normalization

    private const val SEP = "\u0000"
    private val WORD = Regex("""[\p{L}\p{N}]+(?:['’.:,/\-][\p{L}\p{N}]+)*""")

    /** Lower-case word tokens with abbreviations, units, ISO dates and number words made comparable. */
    fun tokens(text: String): List<String> {
        var t = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        t = t.replace(Regex("""\[([^\]]*)]\([^)]*\)?"""), "$1").replace(EvidenceText.URL, " ")
        t = t.replace(Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")) { m -> monthName(m.groupValues[2])?.let { "$it ${m.groupValues[3].toInt()} ${m.groupValues[1]}" } ?: m.value }
        t = t.replace(Regex("""\b(\d{4})-(\d{2})\b(?!-)""")) { m -> monthName(m.groupValues[2])?.let { "$it ${m.groupValues[1]}" } ?: m.value }
        t = t.replace(Regex("""(?<![\p{L}\p{N}])((?:\p{L}\.){2,})""")) { it.value.replace(".", "") }
        t = t.replace(Regex("""(?<=\d),(?=\d{3}\b)"""), "")
        t = t.replace("%", " percent ").replace(Regex("""(?<=\d)(?=[a-zA-Zµ])"""), " ")
        return Regex("""\d+(?:[.:]\d+)*|[\p{L}]+(?:['’][\p{L}]+)?""").findAll(t.lowercase(Locale.ROOT)).map { norm1(it.value) }.toList()
    }

    private fun norm1(w: String): String {
        // "09:00" and "9:00" are one time; leading zeros carry no meaning in a match.
        if (w.first().isDigit()) {
            if (':' in w) return w.split(':').joinToString(":") { it.trimStart('0').ifEmpty { "0" } }
            val whole = w.substringBefore('.').trimStart('0').ifEmpty { "0" }
            val fraction = if ('.' in w) w.substringAfter('.').trimEnd('0') else ""
            return if (fraction.isEmpty()) whole else "$whole.$fraction"
        }
        var x = w.lowercase(Locale.ROOT).replace('’', '\'').removeSuffix("'s")
        x = MAP[x] ?: x
        if (x.length > 3 && x.endsWith("s") && !x.endsWith("ss") && !x.endsWith("us") && !x.endsWith("is") && x.any { it.isLetter() }) x = x.dropLast(1)
        return MAP[x] ?: x
    }

    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private fun monthName(mm: String): String? = mm.toIntOrNull()?.takeIf { it in 1..12 }?.let { MONTHS[it - 1] }

    private val MAP: Map<String, String> = buildMap {
        putAll(mapOf("ave" to "avenue", "av" to "avenue", "st" to "street", "rd" to "road", "blvd" to "boulevard", "sq" to "square", "hwy" to "highway", "pl" to "place", "ln" to "lane", "dr" to "drive"))
        putAll(mapOf("northeast" to "ne", "northwest" to "nw", "southeast" to "se", "southwest" to "sw"))
        putAll(mapOf("jan" to "january", "feb" to "february", "mar" to "march", "apr" to "april", "jun" to "june", "jul" to "july", "aug" to "august", "sep" to "september", "sept" to "september", "oct" to "october", "nov" to "november", "dec" to "december"))
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve").forEachIndexed { i, w -> put(w, i.toString()) }
        putAll(mapOf("milligram" to "mg", "milligrams" to "mg", "microgram" to "mcg", "micrograms" to "mcg", "µg" to "mcg", "ug" to "mcg", "gram" to "g", "grams" to "g", "kilogram" to "kg", "kilograms" to "kg"))
        putAll(mapOf("milliliter" to "ml", "milliliters" to "ml", "millilitre" to "ml", "millilitres" to "ml", "liter" to "l", "litre" to "l", "hrs" to "hour", "hr" to "hour", "hours" to "hour", "mins" to "minute", "min" to "minute", "minutes" to "minute"))
    }

    private val UNITS = setOf("mg", "mcg", "g", "kg", "iu", "ml", "l", "percent", "hour", "minute", "day", "week", "month", "year", "mm", "cm", "m", "km", "mile", "inch", "ft", "foot", "feet", "lb", "oz", "gb", "mb", "tb", "kb", "ghz", "mhz", "w", "kw", "kwh", "v", "mah", "dollar", "euro", "cent", "point", "time", "dose", "tablet", "capsule", "serving", "softgel")

    /** Lower-cased words that are capitalized for grammar, not because they name something. */
    private val NOT_NAMES = setOf("i", "i'm", "i've", "i'd", "i'll", "ok")
    private val LEADING = setOf("the", "a", "an", "this", "that", "these", "those", "its", "it", "on", "in", "at", "for", "by", "from", "to", "and", "or", "but", "if", "when", "each", "every", "some", "any", "our", "your", "their", "his", "her", "my", "most", "many", "several", "all", "both", "few", "such", "other", "another", "no", "more", "less", "much", "one", "only", "also")
    private val CONNECTORS = setOf("of", "the", "and", "de", "du", "la", "le", "del", "der", "van", "von", "&", "for", "on", "upon")

    /** Words that begin an aside the sentence reads correctly without. Contrastive clauses (but, unless, except, however) are not among them. */
    private val REMOVABLE = setOf("where", "which", "who", "whose", "whom", "near", "next", "close", "including", "such", "also", "located", "situated", "known", "called", "and", "plus", "with", "at", "in", "on", "along", "opposite", "beside", "between", "facing", "about", "around", "approximately", "roughly", "built", "founded", "opened", "established")

    /** Certainty and universality the sentence asserts; the cited text must use the same words. */
    private val CERTAINTY = Regex(
        """\b(?:always|never|everyone|everybody|in all cases|in every case|without exception|for all|for every|for any|at any scale|regardless of|no matter|guarantee[sd]?|guaranteed|proven|proves|definitely|certainly|cures?|cured|prevents?|prevented|eliminates?|risk-free|no risk|no side effects|completely safe|totally safe)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Hedges and limits in research evidence that a flat sentence would drop. */
    private val LIMITING = Regex(
        """\b(?:may|might|could|possibly|modest(?:ly)?|small|slight(?:ly)?|limited|insufficient|inconclusive|mixed|uncertain|unclear|weak|low[- ](?:quality|certainty)|preliminary|associated with|observational|not significant(?:ly)?|no significant|more research|further research|suggests?)\b""",
    )
}

/**
 * N3, Document F §4 presentation ("nothing included because found"): unrequested material is shown only when it bears
 * on the objective's answer. The model must say how an item bears on it (`bears=`) and which answer claim it qualifies
 * (`on=`); the engine then keeps a risk in acting on the answer always, and an exception, limitation or prerequisite
 * only when it is tied to the answer and actually restricts it. Background, context and nearby facts are omitted, not
 * disclaimed. No topic or category is consulted; only the objective's answer and the item's own words.
 */
object Relevance {
    enum class Bearing { RISK, EXCEPTION, LIMITATION, PREREQUISITE;
        companion object { fun of(s: String?): Bearing? = s?.trim()?.lowercase(Locale.ROOT)?.let { v -> entries.firstOrNull { it.name.lowercase(Locale.ROOT) == v } } }
    }

    /** Words that make a statement restrict or condition another. */
    val RESTRICTS = Regex(
        """\b(?:except|exception|unless|only|not|no|nor|but|however|although|though|closed|until|before|after|if|varies|vary|depends|depending|required|requires?|must|needs?|needed|limited|limit|excluding|excludes?|cannot|can't|doesn't|don't|isn't|aren't|won't|wasn't|no longer|instead|differs?|different|separate|restricted|subject to|at least|at most|within|appointment|advance|risk|risks|warning|danger|dangerous|unsafe|avoid|interacts?|interactions?|side effects?|overdose|toxic\w*|contraindicat\w*)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** An unrequested item is kept when it is a risk, or an exception, limitation or prerequisite of what the answer states. */
    fun keepMaterial(item: AnswerSegment, answerClaims: Set<String>, byId: Map<String, Claim>): Boolean {
        val bearing = Bearing.of(item.bearing) ?: return false
        if (bearing == Bearing.RISK) return true
        val tied = item.relatesTo.any { it in answerClaims } || item.claimIds.any { byId[it]?.loadBearing == true }
        return tied && RESTRICTS.containsMatchIn(item.text)
    }

    /**
     * For a narrow fact, a main-answer sentence that states only claims the reply itself marked as not load-bearing,
     * and neither restricts nor warns, is background found along the way: it is left out.
     */
    fun background(sentence: AttributedSentence, byId: Map<String, Claim>, objective: Objective): Boolean {
        if (objective.askKind != AskKind.FACT) return false
        val claims = sentence.claimIds.mapNotNull { byId[it] }
        if (claims.isEmpty() || claims.any { it.loadBearing || it.dependsOnUser || IMPLICIT.containsMatchIn(it.id) }) return false
        return !RESTRICTS.containsMatchIn(sentence.text)
    }

    /** Claims the parser made for cited sentences that had no marker: their weight was never declared. */
    private val IMPLICIT = Regex("""-x\d+$""")
}
