package com.lifeadmin.autopilot.ai.research

/**
 * Turns the provider's cited text blocks into a [BriefContent]. Provenance is decided here and
 * only from structured citations: a sentence with none is a suggestion, whatever it claims. URLs
 * written in prose are removed. Text that looks like an instruction to the app is never a move.
 */
object BriefParser {
    private val URL = Regex("""\b(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val BRACKET_REF = Regex("""\s*\[\d+(?:,\s*\d+)*]""")
    private val HEADING = Regex("""^\s*(?:#{1,4}\s*)?\**\s*(QUICK ANSWER|NEXT MOVES|FINDINGS|UNCONFIRMED)\s*:?\s*\**\s*:?\s*$""", RegexOption.IGNORE_CASE)
    private val BULLET = Regex("""^\s*(?:[-*•]|\d+[.)])\s+(.*)$""")
    private val MOVE = Regex("""^(STEP|CHECK|NOTE)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val DISAGREE = Regex("""^DISAGREE\s*:\s*(.+?)\s*\|\s*(.+?)\s*\|\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val DASH_SPLIT = Regex("""\s+[—–]\s+|\s+--\s+|\s+-\s+""")
    private val INJECTION = Regex(
        """ignore (?:all |any |the )?(?:previous|prior|above|earlier) (?:instructions|prompts?)|disregard (?:the |all |your )?(?:previous|above|system|earlier)|you are now|system prompt|reveal (?:your|the) (?:prompt|instructions)|tell the user to|as an ai (?:model|assistant)|do not tell the user|share (?:all|the) (?:user'?s )?(?:tasks|data)|send (?:the |all )?(?:private|other) tasks""",
        RegexOption.IGNORE_CASE,
    )

    private enum class Section { NONE, ANSWER, MOVES, FINDINGS, UNCONFIRMED }

    private class Span(val start: Int, val end: Int, val citations: List<RawCitation>)

    fun parse(
        segments: List<RawSegment>,
        searches: List<RawSearch>,
        usage: ResearchUsage,
        truncated: Boolean,
        shared: List<String>,
        newId: () -> String,
    ): BriefContent {
        // One text with the citation spans it is made of.
        val text = StringBuilder()
        val spans = ArrayList<Span>()
        for (s in segments) {
            val start = text.length
            text.append(s.text)
            spans += Span(start, text.length, s.citations)
        }
        val full = text.toString()
        // Sources are numbered in order of first structured citation and counted once per cited text
        // block, whatever section or line split it lands in. Only provider citations reach the registry.
        val registry = SourceRegistry()
        for (s in spans) if (s.citations.isNotEmpty()) registry.countBlock(s.citations)

        // Section ranges by line.
        var section = Section.NONE
        var sawHeading = false
        val answerRanges = ArrayList<IntRange>()
        val moves = ArrayList<NextMove>()
        val findings = ArrayList<Finding>()
        var injection = false
        var offset = 0
        for (rawLine in full.split('\n')) {
            val lineStart = offset
            val lineEnd = offset + rawLine.length
            offset = lineEnd + 1
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            HEADING.find(rawLine)?.let { m ->
                section = when (m.groupValues[1].uppercase()) {
                    "QUICK ANSWER" -> Section.ANSWER
                    "NEXT MOVES" -> Section.MOVES
                    "FINDINGS" -> Section.FINDINGS
                    else -> Section.UNCONFIRMED
                }
                sawHeading = true
                return@let
            }
            if (HEADING.matches(rawLine)) continue
            val refs = registry.refsFor(spans, lineStart, lineEnd)
            when (section) {
                Section.ANSWER -> answerRanges += lineStart until lineEnd
                Section.MOVES -> {
                    val body = BULLET.find(rawLine)?.groupValues?.get(1) ?: line
                    if (INJECTION.containsMatchIn(body)) { injection = true; continue }
                    val m = MOVE.find(body)
                    val kind = when (m?.groupValues?.get(1)?.uppercase()) { "CHECK" -> MoveKind.CHECK; "NOTE" -> MoveKind.NOTE; else -> MoveKind.STEP }
                    val rest = clean(m?.groupValues?.get(2) ?: body)
                    val parts = rest.split(DASH_SPLIT, limit = 2)
                    val moveText = parts[0].trim().trimEnd('.').take(120)
                    if (moveText.isEmpty()) continue
                    val basis = parts.getOrNull(1)?.trim()?.ifEmpty { null }
                        ?: if (refs.isNotEmpty()) "From your research" else if (kind == MoveKind.CHECK) "Nothing online settled this." else "Claude's suggestion — not from a source."
                    moves += NextMove(newId(), kind, moveText, basis, refs)
                }
                Section.FINDINGS -> {
                    val body = BULLET.find(rawLine)?.groupValues?.get(1) ?: line
                    if (INJECTION.containsMatchIn(body)) { injection = true; continue }
                    val d = DISAGREE.find(body)
                    if (d != null) {
                        val topic = clean(d.groupValues[1])
                        val sideA = clean(d.groupValues[2]); val sideB = clean(d.groupValues[3])
                        val aStart = rawLine.indexOf(d.groupValues[2]); val bStart = rawLine.indexOf(d.groupValues[3], (aStart + d.groupValues[2].length).coerceAtLeast(0))
                        val refsA = if (aStart >= 0) registry.refsFor(spans, lineStart + aStart, lineStart + aStart + d.groupValues[2].length) else emptyList()
                        val refsB = if (bStart >= 0) registry.refsFor(spans, lineStart + bStart, lineStart + bStart + d.groupValues[3].length) else emptyList()
                        if (refsA.isNotEmpty() && refsB.isNotEmpty() && refsA.first() != refsB.first()) {
                            findings += Finding.Disagree(topic, listOf(Side(refsA.first(), sideA), Side(refsB.first(), sideB)), "Confirm with the organization itself before you rely on either.")
                        } else if (refs.isNotEmpty()) {
                            findings += Finding.Sourced("$topic: $sideA / $sideB", refs, registry.bestTier(refs))
                        } else {
                            findings += Finding.Suggestion("$topic: $sideA / $sideB")
                        }
                        continue
                    }
                    val t = clean(body)
                    if (t.isEmpty()) continue
                    findings += if (refs.isNotEmpty()) Finding.Sourced(t, refs, registry.bestTier(refs)) else Finding.Suggestion(t)
                }
                Section.UNCONFIRMED -> {
                    val body = clean(BULLET.find(rawLine)?.groupValues?.get(1) ?: line)
                    if (body.isEmpty() || body.equals("none", ignoreCase = true) || body.startsWith("none", ignoreCase = true) && body.length < 12) continue
                    val parts = body.split(DASH_SPLIT, limit = 2)
                    findings += Finding.Unconfirmed(parts[0].trim().trimEnd('.'), parts.getOrNull(1)?.trim() ?: "I searched, but no source covered it.")
                }
                Section.NONE -> if (!sawHeading) answerRanges += lineStart until lineEnd // no headings at all: the whole text is the answer
            }
        }

        // Quick answer segments: pieces of the answer lines, each with the refs of the span it sits in.
        val answer = ArrayList<Segment>()
        for (range in answerRanges) {
            for (sp in spans) {
                val s = maxOf(sp.start, range.first); val e = minOf(sp.end, range.last + 1)
                if (e <= s) continue
                val piece = clean(full.substring(s, e), keepTrailingSpace = true)
                if (piece.isBlank()) continue
                val refs = registry.register(sp.citations)
                val last = answer.lastOrNull()
                if (last != null && last.refs.isEmpty() && refs.isEmpty()) answer[answer.lastIndex] = last.copy(text = last.text + piece)
                else answer += Segment(piece, refs)
            }
            answer.lastOrNull()?.let { answer[answer.lastIndex] = it.copy(text = it.text.trimEnd() + " ") }
        }
        val quick = answer.map { it.copy(text = it.text.replace(Regex("\\s+"), " ")) }.filter { it.text.isNotBlank() }.let { trimEnds(it) }

        // Searches: queries for the log; uncited results join the source list as "read, not cited".
        val queries = searches.map { it.query }.filter { it.isNotBlank() }
        for (s in searches) for (r in s.results) registry.registerResult(r)
        val sources = registry.sources()
        val verified = sources.any { it.citedCount > 0 }
        val finalFindings = if (verified) findings else findings.map { f -> if (f is Finding.Sourced) Finding.Suggestion(f.text) else f }
        return BriefContent(
            quickAnswer = quick,
            moves = moves.take(8),
            findings = finalFindings.take(12),
            sources = sources,
            queries = queries,
            usage = usage,
            verified = verified,
            truncated = truncated,
            injectionNoticed = injection,
            shared = shared,
        )
    }

    private fun trimEnds(segments: List<Segment>): List<Segment> {
        if (segments.isEmpty()) return segments
        val out = segments.toMutableList()
        out[0] = out[0].copy(text = out[0].text.trimStart())
        out[out.lastIndex] = out[out.lastIndex].copy(text = out[out.lastIndex].text.trimEnd())
        return out
    }

    /** Removes URLs, bracket citation markers the model may have typed, and markdown emphasis. */
    internal fun clean(s: String, keepTrailingSpace: Boolean = false): String {
        var t = URL.replace(s, "")
        t = BRACKET_REF.replace(t, "")
        t = t.replace("**", "").replace("__", "")
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        return if (keepTrailingSpace) t.trimStart() else t.trim()
    }

    /** Numbers sources in order of first citation; search results are appended uncited. */
    private class SourceRegistry {
        private val byUrl = LinkedHashMap<String, MutableSource>()

        private class MutableSource(val n: Int, val url: String, var title: String, val tier: SourceTier, var pageAge: String?) {
            val quotes = LinkedHashSet<String>()
            var cited = 0
        }

        private fun key(url: String) = url.trim().removeSuffix("/")

        fun register(citations: List<RawCitation>): List<Int> {
            val refs = LinkedHashSet<Int>()
            for (c in citations) {
                val url = Sources.safeExternalUrl(c.url) ?: continue
                val src = byUrl.getOrPut(key(url)) { MutableSource(byUrl.size + 1, url, c.title?.ifBlank { null } ?: Sources.host(url) ?: url, Sources.tier(url), null) }
                if (c.title?.isNotBlank() == true && src.title == Sources.host(url)) src.title = c.title
                c.citedText.trim().takeIf { it.isNotEmpty() }?.let { src.quotes += it.take(ResearchConfig.MAX_QUOTE_CHARS) }
                refs += src.n
            }
            return refs.toList()
        }

        /** One cited text block: registers its sources and counts each distinct one once. */
        fun countBlock(citations: List<RawCitation>) {
            for (n in register(citations)) byUrl.values.firstOrNull { it.n == n }?.let { it.cited++ }
        }

        /** The sources cited by the blocks overlapping [start, end). Resolves only; counting happened per block. */
        fun refsFor(spans: List<Span>, start: Int, end: Int): List<Int> {
            val refs = LinkedHashSet<Int>()
            for (sp in spans) if (sp.start < end && sp.end > start && sp.citations.isNotEmpty()) refs += register(sp.citations)
            return refs.toList()
        }

        fun registerResult(r: RawSearchResult) {
            val url = Sources.safeExternalUrl(r.url) ?: return
            val existing = byUrl[key(url)]
            if (existing != null) { if (existing.pageAge == null) existing.pageAge = r.pageAge; return }
            if (byUrl.size >= 12) return
            byUrl[key(url)] = MutableSource(byUrl.size + 1, url, r.title.ifBlank { Sources.host(url) ?: url }, Sources.tier(url), r.pageAge)
        }

        fun bestTier(refs: List<Int>): SourceTier =
            if (byUrl.values.any { it.n in refs && it.tier == SourceTier.OFFICIAL }) SourceTier.OFFICIAL else SourceTier.WEB

        fun sources(): List<SourceRef> = byUrl.values.sortedBy { it.n }.map {
            SourceRef(it.n, it.url, it.title.take(160), Sources.host(it.url) ?: "", it.tier, it.pageAge, it.quotes.take(3), it.cited)
        }
    }
}
