package com.lifeadmin.autopilot.ai.research2

import java.util.Locale

/**
 * Conservative, deterministic completion policy (hardening finding A). A `status=answered` written
 * by the model is a claim to check, not a result: every requirement's status is validated here
 * against the structured result before coverage is shown or an outcome is decided.
 *
 * - Items: Answered / Resolved conflict / Your call need cited evidence; otherwise they become
 *   Needs a check. A pick block that found nothing is Not found.
 * - Constraints take part: budgets, brand preferences and exclusions are checked against the
 *   candidates and picks themselves. A broken strict constraint is Partly; a broken soft one
 *   (a preference, or an exception the person allowed) is Your call with the exceptions named.
 *   Constraints the app cannot check keep the model's status, except that Answered needs some cited
 *   evidence in the session.
 * - Output wishes are checked for real: a step per item, a list of picks, a shortlist with a
 *   recommendation, ordered steps.
 *
 * Partly, Needs a check, Not found and Not researched are never "covered" (see [RequirementStatus]).
 */
object CompletionPolicy {
    private val AMOUNT = Regex("""([$£€])\s?(\d[\d,]*(?:\.\d+)?)|(\d[\d,]*(?:\.\d+)?)\s?(USD|GBP|EUR|CAD|AUD|dollars|pounds|euros)\b""", RegexOption.IGNORE_CASE)
    private val SOFT = Regex("""\b(ideally|preferably|prefer(?:red|ence)?|if possible|roughly|around|about|approx(?:imately|\.)?|or so|flexible|exceptions? (?:ok|okay|allowed|fine)|not (?:a )?(?:hard|strict)|can go over|soft)\b""", RegexOption.IGNORE_CASE)
    private val STRICT = Regex("""\b(only|must|strict(?:ly)?|hard limit|no exceptions|never)\b""", RegexOption.IGNORE_CASE)
    private val BRAND_SPLIT = Regex("""\s*(?:,|;|\band\b|\bor\b|/|&)\s*""", RegexOption.IGNORE_CASE)

    /** Validates every requirement's status against the structured result; adds a budget callout when a pick is over budget. */
    fun validate(s: SessionContent): SessionContent {
        val evidenceAnywhere = hasAnyEvidence(s)
        var callouts = s.callouts
        val coverage = s.requirements.map { r ->
            val cov = s.coverageOf(r.id)
            when (r.type) {
                RequirementType.ITEM, RequirementType.USER_ADDED -> validateItem(s, r, cov)
                RequirementType.OUTPUT -> validateOutput(s, r, cov, evidenceAnywhere)
                RequirementType.CONSTRAINT -> {
                    val (c, callout) = validateConstraint(s, r, cov, evidenceAnywhere)
                    if (callout != null && callouts.none { it.kind == CalloutKind.BUDGET && it.text == callout.text }) callouts = (callouts + callout).take(4)
                    c
                }
            }
        }
        return s.copy(coverage = coverage, callouts = callouts)
    }

    /** A constraint the person allows exceptions to: flagged by the extractor, or worded that way. */
    fun isSoft(r: Requirement): Boolean {
        if (STRICT.containsMatchIn(r.label)) return false
        return r.soft || SOFT.containsMatchIn(r.label)
    }

    /** Every requirement — items, constraints and output wishes alike — must be covered. */
    fun covered(s: SessionContent): Boolean = s.requirements.all { s.coverageOf(it.id).status.covered }

    /** Requirements another search could still change (all types). */
    fun gaps(s: SessionContent): List<Requirement> = s.requirements.filter { s.coverageOf(it.id).status.open }

    /** Something in the session rests on a provider citation. */
    fun hasAnyEvidence(s: SessionContent): Boolean = s.sources.any { it.citedCount > 0 } || cited(s).isNotEmpty()

    // ------------------------------------------------------------------ items

    private fun validateItem(s: SessionContent, r: Requirement, cov: Coverage): Coverage {
        val pick = s.body.picks.firstOrNull { it.requirementId == r.id }
        if (pick != null && pick.pick == null && cov.status.covered) {
            return cov.copy(status = RequirementStatus.NOT_FOUND, summary = cov.summary.ifBlank { "Nothing was picked for this item." })
        }
        return when (cov.status) {
            RequirementStatus.ANSWERED, RequirementStatus.RESOLVED_CONFLICT, RequirementStatus.YOUR_CALL ->
                if (itemEvidence(s, r, cov, pick)) cov
                else cov.copy(status = RequirementStatus.NEEDS_CHECK, summary = unverified(cov, if (cov.status == RequirementStatus.YOUR_CALL) "Reported as your call, but no cited page backs the options." else "Reported as answered, but no cited page backs it."))
            else -> cov
        }
    }

    private fun itemEvidence(s: SessionContent, r: Requirement, cov: Coverage, pick: ItemPick?): Boolean {
        if (cov.refs.isNotEmpty()) return true
        if (pick != null && (pick.refs.isNotEmpty() || pick.labelFacts.any { it.refs.isNotEmpty() } || pick.price?.refs?.isNotEmpty() == true)) return true
        return when (s.template) {
            DeliverableTemplate.SHORTLIST -> s.body.candidates.any { it.included && candidateEvidence(it) }
            DeliverableTemplate.HOW_TO, DeliverableTemplate.CHECKLIST -> s.body.steps.any { it.refs.isNotEmpty() }
            DeliverableTemplate.QUICK_ANSWER -> s.answer.any { it.refs.isNotEmpty() } || s.claims.any { it.refs.isNotEmpty() }
            DeliverableTemplate.PICK_PER_ITEM -> false
        }
    }

    private fun candidateEvidence(c: Candidate): Boolean = c.refs.isNotEmpty() || c.price?.refs?.isNotEmpty() == true || c.attrs.any { it.refs.isNotEmpty() }

    // ------------------------------------------------------------------ output wishes

    private fun validateOutput(s: SessionContent, r: Requirement, cov: Coverage, evidenceAnywhere: Boolean): Coverage {
        val label = r.label.lowercase(Locale.ROOT)
        val items = s.requirements.filter { it.type == RequirementType.ITEM || it.type == RequirementType.USER_ADDED }
        return when {
            label.contains("step per item") || label.contains("one step per") || label.contains("separate step") -> {
                val provided = items.count { item ->
                    s.proposals.any { it.requirementId == item.id && it.kind == ProposalKind.STEP } ||
                        s.body.picks.any { it.requirementId == item.id && it.pick != null }
                }
                verdict(cov, provided, items.size, "Steps for ${provided} of ${items.size} items", "A step for every item", "No step could be proposed yet")
            }
            label.contains("list of picks") -> {
                val provided = items.count { item -> s.body.picks.any { it.requirementId == item.id && it.pick != null } }
                if (items.isEmpty()) ifEvidence(cov, evidenceAnywhere) else verdict(cov, provided, items.size, "Picks for ${provided} of ${items.size} items", "A pick for every item", "No picks yet")
            }
            label.contains("shortlist") -> {
                val n = s.body.candidates.count { it.included }
                when {
                    n >= 2 && s.answer.isNotEmpty() -> cov.copy(status = RequirementStatus.ANSWERED, summary = cov.summary.ifBlank { "$n options with a recommendation" })
                    n >= 1 -> cov.copy(status = RequirementStatus.PARTLY, summary = if (s.answer.isEmpty()) "$n option${if (n == 1) "" else "s"} found, no recommendation yet" else "Only one option found so far")
                    else -> cov.copy(status = RequirementStatus.NOT_FOUND, summary = cov.summary.ifBlank { "No shortlist yet" })
                }
            }
            label.contains("steps in order") || label.contains("checklist") -> {
                val n = s.body.steps.size
                if (n > 0) cov.copy(status = RequirementStatus.ANSWERED, summary = cov.summary.ifBlank { "$n steps" }) else cov.copy(status = RequirementStatus.NOT_FOUND, summary = cov.summary.ifBlank { "No steps yet" })
            }
            else -> ifEvidence(cov, evidenceAnywhere)
        }
    }

    private fun verdict(cov: Coverage, provided: Int, total: Int, partlyText: String, okText: String, noneText: String): Coverage = when {
        total == 0 -> cov
        provided >= total -> cov.copy(status = RequirementStatus.ANSWERED, summary = okText)
        provided > 0 -> cov.copy(status = RequirementStatus.PARTLY, summary = partlyText)
        else -> cov.copy(status = RequirementStatus.NOT_FOUND, summary = noneText)
    }

    // ------------------------------------------------------------------ constraints

    private data class Named(val label: String, val name: String, val price: Price?, val haystack: String)

    private fun validateConstraint(s: SessionContent, r: Requirement, cov: Coverage, evidenceAnywhere: Boolean): Pair<Coverage, Callout?> {
        val label = r.label.trim()
        val lower = label.lowercase(Locale.ROOT)
        val soft = isSoft(r)
        val recommended = recommended(s)
        return when {
            lower.startsWith("budget") || (lower.contains("up to") || lower.contains("under") || lower.contains("max")) && AMOUNT.containsMatchIn(label) -> budget(label, soft, recommended, cov)
            lower.startsWith("brand") -> brand(label, soft, recommended, cov) to null
            lower.startsWith("exclude") || lower.startsWith("no ") || lower.startsWith("avoid") || lower.startsWith("without") -> exclude(label, soft, recommended, cov) to null
            else -> ifEvidence(cov, evidenceAnywhere) to null
        }
    }

    /** What the research actually recommends: included shortlist candidates and per-item picks. */
    private fun recommended(s: SessionContent): List<Named> = buildList {
        s.body.candidates.filter { it.included }.forEach { add(Named(it.name, it.name + (it.variant?.let { v -> " ($v)" } ?: ""), it.price, listOf(it.name, it.variant.orEmpty(), it.reason).joinToString(" "))) }
        s.body.picks.filter { it.pick != null }.forEach { p ->
            val item = s.requirements.firstOrNull { it.id == p.requirementId }?.label ?: p.requirementId
            add(Named(item, p.pick!!, p.price, listOf(p.pick, p.variant.orEmpty(), p.labelFacts.joinToString(" ") { it.value }).joinToString(" ")))
        }
    }

    private fun budget(label: String, soft: Boolean, picks: List<Named>, cov: Coverage): Pair<Coverage, Callout?> {
        val m = AMOUNT.find(label) ?: return ifEvidence(cov, true) to null
        val (symbol, amountRaw) = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] to m.groupValues[2] else currencyOf(m.groupValues[4]) to m.groupValues[3]
        val limit = amountRaw.replace(",", "").toDoubleOrNull() ?: return ifEvidence(cov, true) to null
        val currency = when (symbol) { "$" -> "USD"; "£" -> "GBP"; "€" -> "EUR"; else -> symbol.uppercase(Locale.ROOT) }
        val limitText = Price(limit, currency, status = PriceStatus.CONFIRMED).display()
        if (picks.isEmpty()) return capAnswered(cov, "Nothing to check against the $limitText limit yet") to null
        val over = picks.filter { it.price != null && it.price.currency.equals(currency, true) && it.price.amount > limit + 1e-9 }
        val unpriced = picks.filter { it.price == null || !it.price.currency.equals(currency, true) }
        val overText = over.joinToString(", ") { "${it.name} (${it.price!!.display()})" }
        return when {
            over.isNotEmpty() && !soft -> cov.copy(status = RequirementStatus.PARTLY, summary = "Over the $limitText limit: $overText".take(300)) to Callout(CalloutKind.BUDGET, "Over budget: $overText is above the $limitText limit you set.".take(400))
            over.isNotEmpty() -> cov.copy(status = RequirementStatus.YOUR_CALL, summary = "Over the $limitText limit (allowed as an exception): $overText".take(300)) to Callout(CalloutKind.BUDGET, "Exception: $overText is above $limitText; you allowed going over.".take(400))
            unpriced.isNotEmpty() -> cov.copy(status = RequirementStatus.NEEDS_CHECK, summary = "No ${currency} price seen for ${unpriced.joinToString(", ") { it.name }}, so the $limitText limit can't be confirmed".take(300)) to null
            else -> cov.copy(status = RequirementStatus.ANSWERED, summary = "All ${picks.size} priced picks are at or under $limitText".take(300)) to null
        }
    }

    private fun brand(label: String, soft: Boolean, picks: List<Named>, cov: Coverage): Coverage {
        val raw = if (label.contains(':')) label.substringAfter(':') else label.replaceFirst(Regex("""(?i)^brands?\s*(?:preferences?|preferred|prefer)?\s*(?:is|are)?"""), "")
        val brands = raw.replaceFirst(Regex("""(?i)^\s*(?:only|just|strictly)\s+"""), "").split(BRAND_SPLIT).map { it.trim().trim('.', ':') }.filter { it.length >= 2 }
        if (brands.isEmpty()) return cov
        if (picks.isEmpty()) return capAnswered(cov, "Nothing to check against the preferred brands yet")
        val off = picks.filter { p -> brands.none { b -> p.haystack.contains(b, ignoreCase = true) } }
        val offText = off.joinToString(", ") { "${it.label} (${it.name})" }
        return when {
            off.isEmpty() -> cov.copy(status = RequirementStatus.ANSWERED, summary = "Every pick is from ${brands.joinToString(" or ")}".take(300))
            soft -> cov.copy(status = RequirementStatus.YOUR_CALL, summary = "Not from a preferred brand: $offText".take(300))
            else -> cov.copy(status = RequirementStatus.PARTLY, summary = "Not from ${brands.joinToString(" or ")}: $offText".take(300))
        }
    }

    private fun exclude(label: String, soft: Boolean, picks: List<Named>, cov: Coverage): Coverage {
        val what = label.substringAfter(':', label).replace(Regex("""^(?:no|avoid|without|exclude)\s+""", RegexOption.IGNORE_CASE), "").trim().trim('.')
        if (what.length < 2) return cov
        if (picks.isEmpty()) return capAnswered(cov, "Nothing to check against the exclusion yet")
        // "testers" matches "tester" and vice versa.
        val stem = Regex.escape(what.removeSuffix("es").removeSuffix("s"))
        val hit = picks.filter { Regex("""\b${stem}(?:e?s)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.haystack) }
        return when {
            hit.isEmpty() -> cov.copy(status = RequirementStatus.ANSWERED, summary = "No pick mentions '$what'".take(300))
            soft -> cov.copy(status = RequirementStatus.YOUR_CALL, summary = "Mentions '$what' (allowed as an exception): ${hit.joinToString(", ") { it.name }}".take(300))
            else -> cov.copy(status = RequirementStatus.PARTLY, summary = "Still mentions '$what': ${hit.joinToString(", ") { it.name }}".take(300))
        }
    }

    // ------------------------------------------------------------------ helpers

    /** An Answered the app cannot check itself still needs some cited evidence in the session. */
    private fun ifEvidence(cov: Coverage, evidenceAnywhere: Boolean): Coverage =
        if ((cov.status == RequirementStatus.ANSWERED || cov.status == RequirementStatus.RESOLVED_CONFLICT || cov.status == RequirementStatus.YOUR_CALL) && !evidenceAnywhere)
            cov.copy(status = RequirementStatus.NEEDS_CHECK, summary = unverified(cov, "Reported as settled, but nothing in this research cites a page."))
        else cov

    private fun capAnswered(cov: Coverage, why: String): Coverage =
        if (cov.status.covered) cov.copy(status = RequirementStatus.NEEDS_CHECK, summary = why) else cov

    private fun unverified(cov: Coverage, lead: String): String = (lead + cov.summary.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()).take(300)

    private fun currencyOf(word: String): String = when (word.lowercase(Locale.ROOT)) { "dollars" -> "$"; "pounds" -> "£"; "euros" -> "€"; else -> word }

    private fun cited(s: SessionContent): List<Int> = buildList {
        s.answer.forEach { addAll(it.refs) }
        s.coverage.forEach { addAll(it.refs) }
        s.claims.forEach { addAll(it.refs) }
        s.body.candidates.forEach { c -> addAll(c.refs); c.attrs.forEach { addAll(it.refs) }; c.price?.let { addAll(it.refs) } }
        s.body.picks.forEach { p -> addAll(p.refs); p.labelFacts.forEach { addAll(it.refs) } }
        s.body.steps.forEach { addAll(it.refs) }
    }
}
