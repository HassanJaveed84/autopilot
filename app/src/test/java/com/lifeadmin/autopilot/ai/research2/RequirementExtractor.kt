package com.lifeadmin.autopilot.ai.research2

import java.util.Locale

/**
 * Deterministic, offline extraction of what a request asks for: items, constraints and the output
 * wish. It handles the patterns the benchmarks need (lists with "and"/commas, "brand preference",
 * budgets, regions, "as a gift", "each as a separate step") and says when it is unsure. The original
 * request is always kept verbatim; the user edits the list before anything is sent.
 */
object RequirementExtractor {
    data class Result(val template: DeliverableTemplate, val requirements: List<Requirement>, val confident: Boolean)

    private val HOW_TO = Regex("""\b(how (do|can|should|to)|what('s| is) the (process|procedure)|steps? (to|for)|instructions?|set up|renew|apply for|register|install|cancel)\b""", RegexOption.IGNORE_CASE)
    private val CHECKLIST = Regex("""\b(checklist|what (do i|should i|to) (bring|pack|prepare|need)|packing list|documents? (needed|required)|requirements? (for|to))\b""", RegexOption.IGNORE_CASE)
    private val SHORTLIST = Regex("""\b(best|recommend(ation)?s?|top \d+|which (one|is better)|compare|comparison|shortlist|options?|alternatives?|vs\.?|versus|gift)\b""", RegexOption.IGNORE_CASE)
    private val PER_ITEM = Regex("""\b(each|every|per|for each|separate(ly)?|one (for|per))\b""", RegexOption.IGNORE_CASE)
    private val NEED_LIST = Regex("""\b(?:need|needs|want|looking for|find|get|buy)(?:\s+(?:a|an|the|some))?\s+(.+?)(?:\.|;|\bproduce\b|\bmake\b|\badd\b|\bas\b|$)""", RegexOption.IGNORE_CASE)
    private val BRAND = Regex("""\b[Bb]rand(?:s)?\s*(?:preference|preferences|preferred|prefer|preferences:|:)?\s*(?:is|are|:)?\s*([A-Z][\w&'’.-]*(?:\s+[A-Z][\w&'’.-]*)*(?:\s*(?:,|and|or|/)\s*[A-Z][\w&'’.-]*(?:\s+[A-Z][\w&'’.-]*)*)*)""")
    private val BUDGET = Regex("""(?:up to|under|below|max(?:imum)?|budget(?: of)?|no more than|less than|around|about|≤|<=?)\s*([$£€])\s?(\d[\d,]*(?:\.\d+)?)|([$£€])\s?(\d[\d,]*(?:\.\d+)?)\s*(?:or less|max(?:imum)?|budget|limit|cap)""", RegexOption.IGNORE_CASE)
    private val REGION = Regex("""\b(?:in|within|for|from|ships? to|available in)\s+(the\s+)?(USA|US|U\.S\.|United States|UK|U\.K\.|United Kingdom|Canada|Australia|Ireland|Germany|France|Spain|Italy|Netherlands|New Zealand|India|Europe|EU)\b""", RegexOption.IGNORE_CASE)
    private val EXCLUDE = Regex("""\b(?:no|not|avoid|without|exclud(?:e|ing)|except)\s+([a-z][\w' -]{2,40}?)(?:[.,;]|$|\band\b)""", RegexOption.IGNORE_CASE)
    private val GIFT = Regex("""\b(as a gift|gift for|present for|for (my|a) (dad|mum|mom|father|mother|partner|husband|wife|boyfriend|girlfriend|friend|brother|sister|son|daughter))\b""", RegexOption.IGNORE_CASE)
    private val SEPARATE_STEP = Regex("""\b(each|every)\b.{0,40}\b(separate|own|its own|individual)\b.{0,20}\b(step|task|item|line)s?\b|\b(separate|individual) (steps?|tasks?)\b|\bone step (per|for each)\b|\badd(ed)? (them |each )?(as|to) (steps|my task)\b""", RegexOption.IGNORE_CASE)
    private val LIST_WORDS = Regex("""\b(produce|make|give me|write|create)\s+(a\s+)?(list|shortlist|comparison|table)\b""", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("""\s*(?:,|;|\band\b|\bplus\b|&)\s*""", RegexOption.IGNORE_CASE)
    private val STRICT_BRAND = Regex("""\b(only|must be|strictly|exclusively)\b""", RegexOption.IGNORE_CASE)
    private val SOFT_BUDGET = Regex("""\b(a bit over is (?:ok|fine)|can go over|flexible on (?:price|budget)|not a hard limit)\b""", RegexOption.IGNORE_CASE)
    private val STOP_ITEMS = setOf("a", "an", "the", "some", "it", "them", "this", "that", "me", "list", "recommendations", "recommendation", "options", "help")

    fun extract(request: String): Result {
        val text = request.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return Result(DeliverableTemplate.QUICK_ANSWER, emptyList(), confident = false)
        val items = ArrayList<String>()
        // label → soft (a preference the person allows exceptions to)
        val constraints = ArrayList<Pair<String, Boolean>>()
        val outputs = ArrayList<String>()
        var confident = true

        // Items: "need a general multivitamin, vitamin D, magnesium and glucosamine"
        NEED_LIST.find(text)?.groupValues?.get(1)?.let { raw ->
            // Constraint clauses ("under £1200", "in the USA", "no Apple") are not items.
            var phrase = raw
            for (rx in listOf(BUDGET, REGION, GIFT, EXCLUDE)) phrase = rx.replace(phrase, " ")
            phrase = phrase.replace(Regex("""\s*,\s*(?=,|$)"""), "").trim(' ', ',')
            val parts = phrase.split(SPLIT).map { cleanItem(it) }.filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in STOP_ITEMS }
            if (parts.size in 1..Research2Config.MAX_REQUIREMENTS) items += parts.distinct()
        }
        // Brand preference → constraint (case-sensitive so ordinary words aren't taken as brands).
        // A "preference" is soft by nature: a pick from another brand is shown as an exception to decide on.
        BRAND.find(text)?.groupValues?.get(1)?.trim()?.let { raw ->
            // Stop at the end of the sentence: "Thorne and Pure Encapsulations. Need a…" must not swallow "Need".
            val brands = raw.split(Regex("""\.\s+(?=[A-Z])"""))[0].trimEnd('.', ',')
            if (brands.isNotBlank()) constraints += "Brand preference: $brands" to !STRICT_BRAND.containsMatchIn(text)
        }
        BUDGET.find(text)?.let { m ->
            val sym = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val amount = m.groupValues[2].ifEmpty { m.groupValues[4] }.trim(',', '.')
            // "around $150" / "about £50" is a soft bound; "up to", "under", "max" are strict.
            val soft = Regex("""\b(around|about|roughly|approx(?:imately)?|ideally|preferably|or so)\b""", RegexOption.IGNORE_CASE).containsMatchIn(m.value) || SOFT_BUDGET.containsMatchIn(text)
            if (amount.isNotEmpty()) constraints += (if (soft) "Budget: around $sym$amount" else "Budget: up to $sym$amount") to soft
        }
        REGION.find(text)?.groupValues?.get(2)?.let { constraints += "Region: ${normalizeRegion(it)}" to false }
        if (GIFT.containsMatchIn(text)) constraints += "For a gift (recipient's taste unknown unless you add it)" to false
        for (m in EXCLUDE.findAll(text)) {
            val what = m.groupValues[1].trim()
            if (what.length in 3..40 && !what.startsWith("more than", true) && !what.startsWith("sure", true) && !Regex("""^(a|an|the|my|me|it|only)\b""", RegexOption.IGNORE_CASE).containsMatchIn(what)) constraints += "Exclude: $what" to false
        }

        // Template.
        val perItemAsk = SEPARATE_STEP.containsMatchIn(text) || (PER_ITEM.containsMatchIn(text) && items.size >= 2)
        val template = when {
            CHECKLIST.containsMatchIn(text) && !SHORTLIST.containsMatchIn(text) -> DeliverableTemplate.CHECKLIST
            items.size >= 2 && (perItemAsk || LIST_WORDS.containsMatchIn(text)) -> DeliverableTemplate.PICK_PER_ITEM
            items.size >= 2 -> DeliverableTemplate.PICK_PER_ITEM
            SHORTLIST.containsMatchIn(text) -> DeliverableTemplate.SHORTLIST
            HOW_TO.containsMatchIn(text) -> DeliverableTemplate.HOW_TO
            else -> DeliverableTemplate.QUICK_ANSWER
        }
        // Output wish.
        when {
            SEPARATE_STEP.containsMatchIn(text) -> outputs += "One step per item, added to the task"
            LIST_WORDS.containsMatchIn(text) -> outputs += "A list of picks"
            template == DeliverableTemplate.SHORTLIST -> outputs += "A shortlist with a recommendation"
            template == DeliverableTemplate.HOW_TO -> outputs += "Steps in order"
            template == DeliverableTemplate.CHECKLIST -> outputs += "A checklist"
        }
        if (items.isEmpty() && template != DeliverableTemplate.QUICK_ANSWER) {
            // A shortlist/how-to still has one implicit item: the subject of the question.
            val subject = subjectOf(text)
            if (subject != null) items += subject else confident = false
        }
        if (items.isEmpty() && template == DeliverableTemplate.QUICK_ANSWER) {
            items += text.take(Research2Config.MAX_REQUIREMENT_CHARS).trimEnd('?', '.')
            confident = text.length <= 140
        }
        val reqs = ArrayList<Requirement>()
        items.take(Research2Config.MAX_REQUIREMENTS).forEachIndexed { i, s -> reqs += Requirement("r-i$i", RequirementType.ITEM, s.take(Research2Config.MAX_REQUIREMENT_CHARS), confident = confident) }
        constraints.distinctBy { it.first }.forEachIndexed { i, (label, soft) -> reqs += Requirement("r-c$i", RequirementType.CONSTRAINT, label.take(Research2Config.MAX_REQUIREMENT_CHARS), soft = soft) }
        outputs.distinct().forEachIndexed { i, s -> reqs += Requirement("r-o$i", RequirementType.OUTPUT, s.take(Research2Config.MAX_REQUIREMENT_CHARS)) }
        return Result(template, reqs, confident)
    }

    /** Next free id for a requirement the user types; never reuses an id, even after deletions. */
    fun nextUserId(existing: List<Requirement>): String {
        val max = existing.mapNotNull { r -> Regex("""^r-u(\d+)$""").find(r.id)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: -1
        return "r-u${max + 1}"
    }

    private fun cleanItem(raw: String): String {
        var s = raw.trim().trimEnd('.', ',', ';', ':')
        s = s.replace(Regex("""^(?:recommendations?|suggestions?|options?|ideas?)\s+(?:for|on)\s+""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""^(?:a|an|the|some|my)\s+""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""^(?:the\s+)?best\s+""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\s+(?:please|too|as well)$""", RegexOption.IGNORE_CASE), "")
        if (s.length > 60) return "" // a clause, not an item
        return s
    }

    private fun subjectOf(text: String): String? {
        val m = Regex("""\b(?:best|recommend(?:ation)?s? for|top \d+|which|compare|how (?:do|can|should|to)|steps? (?:to|for))\s+(.+?)(?:[,.?;]|\bup to\b|\bunder\b|\bin\b|\bfor\b|$)""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val s = m.groupValues[1].trim().replace(Regex("""^(?:i|we)\s+""", RegexOption.IGNORE_CASE), "")
        return s.takeIf { it.length in 3..Research2Config.MAX_REQUIREMENT_CHARS }
    }

    private fun normalizeRegion(raw: String): String = when (raw.uppercase(Locale.ROOT).replace(".", "")) {
        "US", "USA", "UNITED STATES" -> "United States"
        "UK", "UNITED KINGDOM" -> "United Kingdom"
        else -> raw.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
