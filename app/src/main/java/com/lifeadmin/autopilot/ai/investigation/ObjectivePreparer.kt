package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawSegment
import java.util.Locale

/**
 * Establishes the objective (RES-010, 013, 020, 025) before any authorization surface exists.
 *
 * Two paths, one result type. [local] is free and offline: the person's words become the objective,
 * the ask is read from the request's own words (never from its subject), and the ambiguity it can see
 * is a question with no stated subject ("is it worth it?") or a place-dependent ask that names no place
 * ("address of Union Station"). It cannot tell a unique landmark from a common name, so the second asks
 * which place; the optional provider pass can settle it instead. [fromProvider] parses the one
 * no-search preparation call made under general AI consent. Neither path consults a topic list to
 * shape the answer; a consequential-domain hint only adds the RES-053 caution.
 */
object ObjectivePreparer {
    private val COMPARE = Regex("""\b(compare|comparison|versus|vs\.?|difference between|differences between|or)\b""", RegexOption.IGNORE_CASE)
    private val COMPARE_STRONG = Regex("""\b(compare|comparison|versus|vs\.?|difference between|differences between)\b""", RegexOption.IGNORE_CASE)
    private val RECOMMEND = Regex("""\b(which (one |option )?(should|would)|should (i|we)|recommend|recommendation|best (way|option|choice|pick)|what('s| is) the best|best\b)""", RegexOption.IGNORE_CASE)
    private val DECIDE = Regex("""\b(worth it|worth (the|buying|paying|switching|upgrading)|is it worth|good idea|make sense|or not\?)""", RegexOption.IGNORE_CASE)
    private val PROCEDURE = Regex("""\b(how (do|can|should|to)|what('s| is) the (process|procedure)|steps? (to|for)|what (documents?|forms?|papers?) (do i|are|will i) need|requirements? (for|to)|how long does)\b""", RegexOption.IGNORE_CASE)
    private val EXPLAIN = Regex("""\b(why|explain|how does|what does .* do|evidence (for|of|that)|benefits? of|does .* (work|help))\b""", RegexOption.IGNORE_CASE)
    private val FORECAST = Regex("""\b(will .* (rise|fall|drop|go up|go down|increase|decrease|change|happen)|forecast|next (year|month|quarter)|in 20[3-9]\d|likely to)\b""", RegexOption.IGNORE_CASE)
    private val CONSEQUENTIAL = Regex("""\b(medication|medicine|drug|dose|supplement|vitamin|blood|symptom|diagnos|treatment|surgery|therapy|doctor|health|legal|lawsuit|visa|immigration|tax|mortgage|loan|insurance|invest|pension|retirement|safety|electrical|gas line)\w*""", RegexOption.IGNORE_CASE)
    private val BARE_REFERENT = Regex("""^\s*(is|are|was|were|does|do|did|should|would|can|could|will)\s+(it|this|that|they|these|those|he|she|one)\b""", RegexOption.IGNORE_CASE)
    private val STOP = setOf(
        "the", "a", "an", "is", "are", "it", "this", "that", "they", "these", "those", "to", "of", "for", "in", "on", "at", "and", "or", "what", "which", "how", "do", "does", "did", "i", "we", "my", "our",
        "should", "would", "can", "could", "will", "worth", "best", "me", "you", "with", "from", "about", "there", "any", "some", "be", "was", "were", "have", "has", "get", "need", "want",
    )
    private val BUDGET = Regex("""(?:up to|under|below|max(?:imum)?|budget(?: of)?|no more than|less than|around|about|≤|<=?)\s*([$£€]\s?\d[\d,]*(?:\.\d+)?)|([$£€]\s?\d[\d,]*(?:\.\d+)?)\s*(?:or less|max(?:imum)?|budget|limit|cap)""", RegexOption.IGNORE_CASE)
    private val PLACE = Regex("""\b(?:in|within|for|from|ships? to|available in)\s+(the\s+)?(USA|US|U\.S\.|United States|UK|U\.K\.|United Kingdom|Canada|Australia|Ireland|Germany|France|Spain|Italy|Netherlands|New Zealand|India|Europe|EU|[A-Z][a-z]+(?:,\s*[A-Z]{2})?)\b""")
    /**
     * Asks whose answer depends on which place is meant: where something is, how to reach it, when it opens, how to
     * call it, the nearest one. Read from the request's words, never from its subject.
     */
    private val PLACE_ASK = Regex(
        """\b(?:address(?:es)?|where (?:is|are)|where's|located|location of|directions?|how (?:do|can|would|should) (?:i|we|you) get to|opening (?:hours|times)|hours of|open(?:s|ing)? (?:on|at|until|today|tonight|tomorrow|now)|what time does .+ (?:open|close)|clos(?:e|es|ing) (?:on|at|today|tonight|tomorrow|time)|phone number|telephone number|contact number|nearest|closest|near me)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Words after "in"/"near" that name a time or a language, not a place. */
    private val NOT_PLACES = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "english", "spanish", "french", "german", "italian", "portuguese", "chinese", "japanese", "arabic",
    )
    /** Ways a question names the place itself: "in Boston", ", Washington, DC", a postcode, a street address, "the Lisbon passport office". */
    private val PLACE_NAMED = listOf(
        Regex("""\b(?:in|near|outside|around|at)\s+(?:the\s+)?([A-Z][\p{L}'’.-]+)"""),
        Regex(""",\s*([A-Z][\p{L}'’.-]+)"""),
        Regex("""\b(\d{5})(?:-\d{4})?\b"""),
        Regex("""\b([A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2})\b"""),
        Regex("""\b(\d+[A-Za-z]?\s+(?:[A-Z][\p{L}'’-]+\s+){1,3}(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Way|Square|Sq|Place|Pl|Court|Ct|Terrace|Highway|Hwy))\b"""),
        // A capitalised place word qualifying a common facility noun: "Lisbon passport office", "Denver public library".
        Regex("""\b([A-Z][a-z]+)\s+(?:[a-z]+\s+){0,2}(?:office|station|airport|library|hospital|museum|branch|clinic|embassy|consulate|court|school|university|centre|center|stadium|terminal|post office|town hall|city hall)\b"""),
    )

    private val TIME = Regex("""\b(by|before|until|on|this|next)\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday|week|month|weekend|[A-Z][a-z]+ \d{1,2}(?:st|nd|rd|th)?|\d{1,2}(?:st|nd|rd|th)? [A-Z][a-z]+)\b""", RegexOption.IGNORE_CASE)

    /** The free, offline objective: correct for clear questions, honest about the one kind of ambiguity it can detect. */
    fun local(question: String): Objective {
        val q = question.trim().replace(Regex("\\s+"), " ")
        val ask = askKind(q)
        val subjectWords = q.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}$£€]+")).filter { it.length > 2 && it !in STOP }
        val noSubject = subjectWords.isEmpty() || (BARE_REFERENT.containsMatchIn(q) && subjectWords.size <= 1)
        val constraints = buildList {
            BUDGET.find(q)?.let { m -> add(Constraint(m.value.trim(), soft = Regex("""\b(around|about|roughly|ideally)\b""", RegexOption.IGNORE_CASE).containsMatchIn(m.value))) }
            PLACE.find(q)?.let { add(Constraint(it.value.trim())) }
            TIME.find(q)?.let { add(Constraint(it.value.trim())) }
        }
        val consequential = CONSEQUENTIAL.containsMatchIn(q)
        val depth = recommendedDepth(ask, consequential)
        // Document F R-N(b): the place changes the answer and the question does not say which; no consented location is known.
        val placeUnknown = !noSubject && PLACE_ASK.containsMatchIn(q) && !namesPlace(q)
        val clarification = when {
            noSubject -> Clarification("What are you weighing up, and against what? Name the thing and what would make it worth it for you.")
            placeUnknown -> Clarification("Which one do you mean? Name the city or area, so the answer is about the right place.")
            else -> null
        }
        return Objective(
            question = q,
            text = if (noSubject) "" else objectiveText(q, ask),
            askKind = ask,
            satisfies = if (noSubject) emptyList() else listOf(satisfiesLine(ask)),
            constraints = constraints,
            evidenceNeeds = emptyList(),
            unknowns = if (placeUnknown) listOf(Unknown("Which place is meant: the city or area", blocking = true)) else emptyList(),
            consequential = consequential,
            recommendedDepth = depth.first, depthReason = depth.second,
            established = !noSubject && !placeUnknown,
            clarification = clarification,
            preparedBy = ObjectiveSource.LOCAL,
        )
    }

    /** True when the question itself names where: a place after "in"/"near", a trailing ", Place", a postcode, a street address or a place-qualified facility. */
    private fun namesPlace(q: String): Boolean = PLACE_NAMED.any { rx -> rx.findAll(q).any { it.groupValues[1].lowercase(Locale.ROOT).trimEnd('.') !in NOT_PLACES } }

    /** The provider's preparation blocks → an [Objective]; falls back to [local] for anything it left out. Never trusts it with the user's words. */
    fun fromProvider(question: String, segments: List<RawSegment>, model: String): Objective {
        val base = local(question)
        val text = segments.joinToString("") { it.text }
        if (text.isBlank()) return base
        val blocks = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        var current: String? = null
        val rx = Regex("""^\s*#{1,4}\s*(OBJECTIVE|ASK|SATISFIES|CONSTRAINT|NEEDS|UNKNOWN|EXCLUDE|CONSEQUENTIAL|DEPTH|CLARIFY)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
        val constraints = ArrayList<Constraint>()
        val unknowns = ArrayList<Unknown>()
        var clarify: Pair<String, MutableList<String>>? = null
        for (line in text.split('\n')) {
            val m = rx.find(line)
            if (m != null) {
                val kind = m.groupValues[1].uppercase(Locale.ROOT)
                val head = m.groupValues[2].trim()
                current = kind
                when (kind) {
                    "CONSTRAINT" -> { constraints += Constraint("", soft = EvidenceText.yes(EvidenceText.kv(head)["soft"])); }
                    "UNKNOWN" -> unknowns += Unknown("", blocking = EvidenceText.yes(EvidenceText.kv(head)["blocking"]))
                    "CLARIFY" -> clarify = "" to mutableListOf()
                    else -> blocks[kind] = head to mutableListOf()
                }
                continue
            }
            val t = EvidenceText.clean(line).trimStart('-', '•', '*', ' ')
            if (t.isBlank()) continue
            when (current) {
                "CONSTRAINT" -> constraints.lastOrNull()?.let { c -> if (c.text.isEmpty()) constraints[constraints.lastIndex] = c.copy(text = t.take(160)) }
                "UNKNOWN" -> unknowns.lastOrNull()?.let { u -> if (u.text.isEmpty()) unknowns[unknowns.lastIndex] = u.copy(text = t.take(200)) }
                "CLARIFY" -> clarify?.let { (q, opts) -> if (q.isEmpty()) clarify = t.take(200) to opts else opts += t.take(80) }
                null -> {}
                else -> blocks[current]?.second?.add(t.take(300))
            }
        }
        if (EvidenceText.INJECTION.containsMatchIn(text)) return base
        val objectiveText = blocks["OBJECTIVE"]?.second?.firstOrNull()?.take(300)
        val ask = blocks["ASK"]?.first?.let { EvidenceText.kv(it)["kind"] }?.let { k -> AskKind.entries.firstOrNull { it.name.equals(k.trim(), true) } } ?: base.askKind
        val depth = blocks["DEPTH"]?.first?.let { EvidenceText.kv(it)["level"] }?.let { ResearchDepth.of(it) }?.takeIf { it != ResearchDepth.CUSTOM }
        val consequential = blocks["CONSEQUENTIAL"]?.first?.let { h -> h.lowercase(Locale.ROOT).contains("yes") } ?: base.consequential
        val clar = clarify?.takeIf { it.first.isNotBlank() }?.let { Clarification(it.first, it.second.take(6)) }
        val blocked = clar != null || unknowns.any { it.blocking && it.text.isNotBlank() }
        return Objective(
            question = base.question,
            text = objectiveText?.takeIf { !blocked } ?: if (blocked) "" else base.text,
            askKind = ask,
            satisfies = blocks["SATISFIES"]?.second?.take(6).orEmpty().ifEmpty { base.satisfies },
            constraints = constraints.filter { it.text.isNotBlank() }.ifEmpty { base.constraints },
            evidenceNeeds = blocks["NEEDS"]?.second?.take(8).orEmpty(),
            unknowns = unknowns.filter { it.text.isNotBlank() }.take(6),
            exclusions = blocks["EXCLUDE"]?.second?.take(6).orEmpty(),
            consequential = consequential,
            recommendedDepth = depth ?: base.recommendedDepth,
            depthReason = blocks["DEPTH"]?.second?.firstOrNull()?.take(160) ?: base.depthReason,
            established = !blocked,
            clarification = clar ?: if (blocked) Clarification(unknowns.first { it.blocking }.text) else null,
            preparedBy = ObjectiveSource.PROVIDER,
            preparedWithModel = model,
        )
    }

    /**
     * A follow-up inside an investigation is read against the earlier objective (RES-100, RES-103): a
     * constraint it changes replaces the earlier one of the same kind ("under $100" replaces "under
     * $150"), the ones it leaves alone carry over, and a follow-up with no subject of its own ("what
     * about under $100?") keeps the earlier subject and ask instead of asking the person what they mean.
     */
    fun followUp(question: String, earlier: Objective): Objective {
        val re = local(question)
        val q = re.question
        val merged = earlier.constraints.filter { c -> re.constraints.none { constraintKind(it.text) == constraintKind(c.text) } } + re.constraints
        val fewWords = q.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}$£€]+")).count { it.length > 2 && it !in STOP } <= 3
        val askSignal = re.askKind != AskKind.FACT
        val ask = if (askSignal || !fewWords) re.askKind else earlier.askKind
        val core = q.trimEnd('?', '.', ' ')
        return if (re.established && !fewWords) {
            re.copy(constraints = merged, askKind = ask, satisfies = if (ask == re.askKind) re.satisfies else listOf(satisfiesLine(ask)), consequential = re.consequential || earlier.consequential, recommendedDepth = recommendedDepth(ask, re.consequential || earlier.consequential).first)
        } else {
            Objective(
                question = q,
                text = (earlier.text.ifBlank { earlier.question } + " — now: " + core).take(400),
                askKind = ask,
                satisfies = earlier.satisfies.ifEmpty { listOf(satisfiesLine(ask)) },
                constraints = merged,
                exclusions = earlier.exclusions,
                consequential = earlier.consequential || re.consequential,
                recommendedDepth = earlier.recommendedDepth, depthReason = earlier.depthReason,
                established = true,
                preparedBy = ObjectiveSource.LOCAL,
            )
        }
    }

    /** Budget / place / time / other, so a follow-up's constraint can replace the one it changes. */
    internal fun constraintKind(text: String): String = when {
        BUDGET.containsMatchIn(text) || Regex("""[$£€]\s?\d""").containsMatchIn(text) -> "budget"
        PLACE.containsMatchIn(text) -> "place"
        TIME.containsMatchIn(text) -> "time"
        else -> "other:" + text.lowercase(Locale.ROOT)
    }

    /** The person's own edit of the objective text; the ask and constraints are re-read from the new wording where it changed them. */
    fun edited(current: Objective, newText: String): Objective {
        val t = newText.trim()
        if (t.isBlank() || t == current.text) return current
        return current.copy(text = t.take(400), established = true, clarification = null, preparedBy = ObjectiveSource.USER_EDITED)
    }

    /** A clarification answered: the chosen option or typed answer becomes part of the question and the objective is prepared again locally. */
    fun clarified(current: Objective, answer: String): Objective {
        val a = answer.trim()
        if (a.isBlank()) return current
        val q = current.question.trimEnd('?', '.', ' ') + " — " + a
        val re = local(q)
        return re.copy(question = q, text = re.text.ifBlank { a }, unknowns = re.unknowns.filterNot { it.blocking }, established = true, clarification = null, preparedBy = ObjectiveSource.USER_EDITED, consequential = re.consequential || current.consequential)
    }

    fun askKind(q: String): AskKind = when {
        DECIDE.containsMatchIn(q) -> AskKind.DECIDE
        COMPARE_STRONG.containsMatchIn(q) && !RECOMMEND.containsMatchIn(q) -> AskKind.COMPARE
        PROCEDURE.containsMatchIn(q) -> AskKind.PROCEDURE
        RECOMMEND.containsMatchIn(q) -> AskKind.RECOMMEND
        FORECAST.containsMatchIn(q) -> AskKind.FORECAST
        EXPLAIN.containsMatchIn(q) -> AskKind.EXPLAIN
        COMPARE.containsMatchIn(q) && Regex("""\b[A-Z][\w-]+\s+or\s+[A-Z][\w-]+""").containsMatchIn(q) -> AskKind.COMPARE
        else -> AskKind.FACT
    }

    private fun objectiveText(q: String, ask: AskKind): String {
        val core = q.trimEnd('?', '.', ' ')
        return when (ask) {
            AskKind.FACT -> "Find out: $core"
            AskKind.PROCEDURE -> "Work out how to: ${core.replaceFirstChar { it.lowercase(Locale.ROOT) }}"
            AskKind.COMPARE -> "Compare, even-handedly: ${core.replaceFirstChar { it.lowercase(Locale.ROOT) }}"
            AskKind.RECOMMEND -> "Recommend, with reasons: ${core.replaceFirstChar { it.lowercase(Locale.ROOT) }}"
            AskKind.EXPLAIN -> "Explain what the evidence shows: ${core.replaceFirstChar { it.lowercase(Locale.ROOT) }}"
            AskKind.FORECAST -> "Assess the outlook: ${core.replaceFirstChar { it.lowercase(Locale.ROOT) }}"
            AskKind.DECIDE -> "Judge whether: ${core.replaceFirstChar { it.lowercase(Locale.ROOT) }}"
        }.take(300)
    }

    private fun satisfiesLine(ask: AskKind): String = when (ask) {
        AskKind.FACT -> "The fact itself, from an authoritative source, with its date"
        AskKind.PROCEDURE -> "The steps or documents, from the authority that sets them, with what depends on circumstances"
        AskKind.COMPARE -> "Each option on the criteria the question implies, from primary sources, no winner declared"
        AskKind.RECOMMEND -> "A choice with its reasons, assumptions and what would change it"
        AskKind.EXPLAIN -> "What the evidence shows, how strong it is and for whom"
        AskKind.FORECAST -> "The current state and informed views, with the uncertainty stated"
        AskKind.DECIDE -> "A yes/no judgment with its reasons and what would flip it"
    }

    /** Lightest depth likely to be trustworthy (RES-131): from the ask and its consequence, never from the subject. */
    fun recommendedDepth(ask: AskKind, consequential: Boolean): Pair<ResearchDepth, String> = when {
        ask == AskKind.FACT && !consequential -> ResearchDepth.QUICK to "A single fact usually needs one authoritative source."
        ask == AskKind.FACT -> ResearchDepth.STANDARD to "A consequential fact is worth a second source."
        ask == AskKind.PROCEDURE || ask == AskKind.EXPLAIN || ask == AskKind.FORECAST -> ResearchDepth.STANDARD to "Needs the authoritative source and a check of what depends on circumstances."
        consequential -> ResearchDepth.THOROUGH to "A consequential choice needs evidence on each option and cross-checking."
        else -> ResearchDepth.STANDARD to "A comparison or choice needs evidence on each option."
    }
}
