package com.lifeadmin.autopilot.ai.research2

import java.util.Locale

/**
 * Merges stage outputs into the session, assesses coverage (code, not a prompt) and decides the
 * outcome. The completeness rule is design d3 as hardened by [CompletionPolicy]: Complete only when
 * every requirement — items, constraints and output wishes — is covered after validation and the run
 * ended on its own; running out of searches or money is never success.
 */
object SessionAssembler {
    fun merge(session: SessionContent, out: StageOutput, sources: List<SourceRef2>): SessionContent {
        val coverage = session.coverage.associateBy { it.requirementId }.toMutableMap()
        out.coverage.forEach { coverage[it.requirementId] = it }
        val cands = LinkedHashMap<String, Candidate>()
        session.body.candidates.forEach { cands[it.id] = it }
        out.candidates.forEach { c ->
            val old = cands[c.id]
            cands[c.id] = if (old == null) c else old.copy(
                name = c.name.ifBlank { old.name }, variant = c.variant ?: old.variant, style = c.style ?: old.style, reason = c.reason.ifBlank { old.reason },
                attrs = mergeAttrs(old.attrs, c.attrs), price = betterPrice(old.price, c.price), refs = (old.refs + c.refs).distinct(), hero = c.hero || old.hero,
                reusedRefs = (old.reusedRefs + c.reusedRefs).distinct(),
            )
        }
        out.excluded.forEach { cands.remove(it.id) }
        val excluded = (session.body.excluded.filter { e -> out.excluded.none { it.id == e.id } && out.candidates.none { it.id == e.id } } + out.excluded).distinctBy { it.id }
        val picks = session.body.picks.filter { p -> out.picks.none { it.requirementId == p.requirementId } } + out.picks
        val steps = if (out.steps.isNotEmpty()) out.steps else session.body.steps
        val answer = if (out.answer.isNotEmpty()) out.answer else session.answer
        val proposals = mergeProposals(session.proposals, out.proposals)
        val merged = session.copy(
            title = out.title?.takeIf { it.isNotBlank() } ?: session.title,
            answer = answer,
            coverage = session.requirements.map { r -> coverage[r.id] ?: Coverage(r.id, RequirementStatus.PENDING) },
            callouts = (session.callouts + out.callouts).distinctBy { it.kind to it.text.lowercase(Locale.ROOT) }.take(4),
            body = session.body.copy(candidates = cands.values.toList(), excluded = excluded, picks = picks, steps = steps, compareKeys = compareKeys(cands.values.toList())),
            claims = (session.claims + out.claims).distinctBy { it.text.lowercase(Locale.ROOT) }.take(Research2Config.MAX_CLAIMS),
            conflicts = (session.conflicts.filter { c -> out.conflicts.none { it.topic.equals(c.topic, true) } } + out.conflicts).take(8),
            unresolved = (session.unresolved + out.unresolved).distinctBy { it.topic.lowercase(Locale.ROOT) }.take(12),
            sources = sources,
            queries = (session.queries + out.queries).distinct(),
            proposals = proposals,
            injectionNoticed = session.injectionNoticed || out.injectionNoticed,
        )
        // Statuses are validated against the structured result every time, never taken from the model alone.
        return CompletionPolicy.validate(merged)
    }

    /** A later, unverified value never replaces a cited one for the same attribute. */
    private fun mergeAttrs(old: List<Attribute>, new: List<Attribute>): List<Attribute> {
        val kept = old.filter { o -> new.none { it.key == o.key } || (o.verified && new.first { it.key == o.key }.let { n -> !n.verified }) }
        val keptKeys = kept.map { it.key }.toSet()
        return kept + new.filter { it.key !in keptKeys }
    }

    /** A later, unconfirmed price never replaces a confirmed one. */
    private fun betterPrice(old: Price?, new: Price?): Price? = when {
        new == null -> old
        old == null -> new
        new.status == PriceStatus.CONFIRMED -> new
        old.status == PriceStatus.CONFIRMED || old.status == PriceStatus.CONFLICT_RESOLVED -> old
        else -> new
    }

    /** Every cited line the session holds, for validating model-typed `src=` restatements in later stages. */
    fun evidenceLines(s: SessionContent): List<EvidenceLine> = buildList {
        s.answer.forEach { if (it.refs.isNotEmpty()) add(EvidenceLine(it.text, it.refs)) }
        s.coverage.forEach { if (it.refs.isNotEmpty() && it.summary.isNotBlank()) add(EvidenceLine(it.summary, it.refs)) }
        s.claims.forEach { if (it.refs.isNotEmpty()) add(EvidenceLine(it.text, it.refs)) }
        s.body.candidates.forEach { c ->
            c.attrs.forEach { if (it.refs.isNotEmpty()) add(EvidenceLine(it.value, it.refs)) }
            if (c.refs.isNotEmpty() && c.reason.isNotBlank()) add(EvidenceLine(c.reason, c.refs))
        }
        s.body.picks.forEach { p -> p.labelFacts.forEach { if (it.refs.isNotEmpty()) add(EvidenceLine(it.value, it.refs)) } }
        s.body.steps.forEach { if (it.refs.isNotEmpty()) add(EvidenceLine(it.text, it.refs)) }
        s.conflicts.forEach { c -> c.sides.forEach { if (it.refs.isNotEmpty()) add(EvidenceLine(it.says, it.refs)) } }
    }

    /** Later stages refine; applied proposals are never replaced or dropped. */
    private fun mergeProposals(old: List<Proposal>, new: List<Proposal>): List<Proposal> {
        if (new.isEmpty()) return old
        val keep = old.filter { o ->
            new.none { n -> n.id == o.id || (n.requirementId != null && n.requirementId == o.requirementId && (n.kind == o.kind || o.generated)) }
        }
        return (keep + new).take(Research2Config.MAX_PROPOSALS)
    }

    private fun compareKeys(c: List<Candidate>): List<String> {
        val order = listOf("price", "size", "strength", "lasts", "versatility", "gift", "value", "notes", "ingredients", "form", "serving", "third_party_testing")
        val present = c.flatMap { it.attrs.map { a -> a.key } }.toSet()
        return order.filter { it in present } + (present - order.toSet()).sorted()
    }

    /** Requirements still worth another call: anything (item, constraint or output wish) whose status is open. */
    fun gaps(session: SessionContent): List<Requirement> = CompletionPolicy.gaps(session)

    /** True when a merge changed what the session holds as findings (not sources, queries or bookkeeping). */
    fun findingsDiffer(a: SessionContent, b: SessionContent): Boolean =
        a.answer != b.answer || a.coverage != b.coverage || a.body != b.body || a.claims != b.claims || a.conflicts != b.conflicts ||
            a.unresolved != b.unresolved || a.callouts != b.callouts || a.proposals != b.proposals || a.title != b.title

    fun priceGaps(session: SessionContent): List<Candidate> = session.body.candidates.filter { it.included && (it.price == null || it.price.status == PriceStatus.NOT_CONFIRMED) }

    /** True when every requirement, constraints included, is covered after validation. */
    fun covered(session: SessionContent): Boolean = CompletionPolicy.covered(session)

    /** Why the run ended, as the orchestrator saw it. */
    enum class Ending { NORMAL, BUDGET_SEARCHES, BUDGET_SPEND, STOPPED, FAILED_AFTER_PARTIAL, FAILED, TRUNCATED, INTERRUPTED }

    /**
     * Complete only when every requirement is covered. A normal ending with something Not found is
     * "not enough evidence"; with everything investigated but some of it Partly / Needs a check it is
     * "Partially complete". A budget stop with open requirements is never Complete.
     */
    fun outcome(session: SessionContent, ending: Ending): SessionOutcome {
        val covered = covered(session)
        val missing = session.requirements.any { session.coverageOf(it.id).status.let { st -> st == RequirementStatus.NOT_FOUND || st == RequirementStatus.PENDING } }
        return when (ending) {
            Ending.STOPPED -> SessionOutcome.STOPPED
            Ending.INTERRUPTED -> SessionOutcome.INTERRUPTED
            Ending.FAILED -> SessionOutcome.FAILED
            Ending.FAILED_AFTER_PARTIAL, Ending.TRUNCATED -> SessionOutcome.PARTIAL
            Ending.BUDGET_SEARCHES, Ending.BUDGET_SPEND -> if (covered) SessionOutcome.COMPLETE else SessionOutcome.INCOMPLETE_BUDGET
            Ending.NORMAL -> when { covered -> SessionOutcome.COMPLETE; missing -> SessionOutcome.INCOMPLETE_EVIDENCE; else -> SessionOutcome.PARTIAL }
        }
    }

    /** Pending requirements after a finished run were never reported: they count as Not found (d3). */
    fun settlePending(session: SessionContent): SessionContent = session.copy(
        coverage = session.requirements.map { r -> session.coverageOf(r.id).let { if (it.status == RequirementStatus.PENDING) it.copy(status = RequirementStatus.NOT_FOUND, summary = it.summary.ifBlank { "The research did not report on this." }) else it } },
    )

    /** "3 of 4 ready · 1 needs you" (d2). */
    fun coverageLine(session: SessionContent): String {
        val items = session.requirements.filter { it.type == RequirementType.ITEM || it.type == RequirementType.USER_ADDED }
        if (items.isEmpty()) return ""
        val st = items.map { session.coverageOf(it.id).status }
        val ready = st.count { it == RequirementStatus.ANSWERED || it == RequirementStatus.RESOLVED_CONFLICT }
        val you = st.count { it.needsUser }
        val missing = st.count { it == RequirementStatus.NOT_FOUND || it == RequirementStatus.PENDING }
        val partly = st.count { it == RequirementStatus.PARTLY }
        return buildList {
            add("$ready of ${items.size} ready")
            if (you > 0) add("$you need${if (you == 1) "s" else ""} you")
            if (partly > 0) add("$partly partly")
            if (missing > 0) add("$missing not found")
        }.joinToString(" · ")
    }
}

/**
 * Makes sure every requested item is represented by a reviewable proposal (benchmark A: four
 * categories → four proposals) and that proposals carry evidence refs and the check time. Model
 * proposals are kept; the app only fills what is missing, and marks those as generated.
 */
object ProposalBuilder {
    fun complete(session: SessionContent, nowEpochMs: Long): List<Proposal> {
        // A model proposal without its own citations inherits evidence only from the product it actually
        // names (the requirement's pick or a shortlist candidate); a proposal for something else stays uncited
        // and is not pre-selected.
        val out = session.proposals.map { p ->
            when {
                p.refs.isNotEmpty() -> p
                else -> {
                    val pick = p.requirementId?.let { id -> session.body.picks.firstOrNull { it.requirementId == id } }
                    val names = p.title + " " + p.note.orEmpty()
                    val pickRefs = if (pick?.pick != null && pick.pick.length >= 3 && names.contains(pick.pick, ignoreCase = true))
                        (pick.refs + pick.labelFacts.flatMap { it.refs } + (pick.price?.refs ?: emptyList())).distinct() else emptyList()
                    val cand = session.body.candidates.firstOrNull { it.name.length >= 3 && names.contains(it.name, ignoreCase = true) }
                    val candRefs = if (cand == null) emptyList() else (cand.refs + (cand.price?.refs ?: emptyList()) + cand.attrs.flatMap { it.refs }).distinct()
                    val refs = (pickRefs + candRefs).distinct()
                    if (refs.isNotEmpty()) p.copy(refs = refs) else p.copy(selectedByDefault = p.selectedByDefault && p.kind != ProposalKind.STEP)
                }
            }
        }.toMutableList()
        // A follow-up question is answered in its own card; it never gets a generated proposal.
        val items = session.requirements.filter { (it.type == RequirementType.ITEM || it.type == RequirementType.USER_ADDED) && !it.followUp }
        val wantsStepPerItem = session.template == DeliverableTemplate.PICK_PER_ITEM ||
            session.requirements.any { it.type == RequirementType.OUTPUT && it.label.contains("per item", true) }
        if (wantsStepPerItem && items.size >= 1) {
            for (r in items) {
                if (out.any { it.requirementId == r.id && (it.kind == ProposalKind.STEP || it.kind == ProposalKind.CHECK || it.kind == ProposalKind.RESEARCH) }) continue
                val pick = session.body.picks.firstOrNull { it.requirementId == r.id }
                val cov = session.coverageOf(r.id)
                val p = when {
                    pick?.pick != null && cov.status != RequirementStatus.NOT_FOUND -> {
                        val title = "Buy ${r.label}: ${pick.pick}${pick.variant?.let { " ($it)" } ?: ""}".take(80)
                        val note = buildString {
                            pick.labelFacts.firstOrNull()?.let { append(it.value.take(120)) }
                            pick.price?.let { pr -> if (isNotEmpty()) append(" · "); append(pr.display()); pr.retailer?.let { append(" at $it") }; pr.size?.let { append(", $it") }; if (pr.status != PriceStatus.CONFIRMED) append(" (not confirmed)") }
                            pick.forYou?.let { if (isNotEmpty()) append(" · "); append(it.take(140)) }
                        }.ifBlank { null }
                        Proposal(ContractParser.defaultProposalId(ProposalKind.STEP, title, r.id), ProposalKind.STEP, title, note, r.id, (pick.refs + pick.labelFacts.flatMap { it.refs }).distinct(), nowEpochMs, selectedByDefault = pick.refs.isNotEmpty() || pick.labelFacts.any { it.refs.isNotEmpty() }, generated = true)
                    }
                    cov.status == RequirementStatus.YOUR_CALL || cov.status == RequirementStatus.NEEDS_CHECK -> {
                        val title = "Decide on ${r.label}".take(80)
                        Proposal(ContractParser.defaultProposalId(ProposalKind.DECIDE, title, r.id), ProposalKind.DECIDE, title, cov.summary.ifBlank { null }, r.id, cov.refs, nowEpochMs, selectedByDefault = false, generated = true)
                    }
                    else -> {
                        val title = "Research ${r.label} further".take(80)
                        Proposal(ContractParser.defaultProposalId(ProposalKind.RESEARCH, title, r.id), ProposalKind.RESEARCH, title, cov.summary.ifBlank { "Nothing found yet for this item." }, r.id, emptyList(), nowEpochMs, selectedByDefault = false, generated = true)
                    }
                }
                out += p
            }
        }
        if (session.template == DeliverableTemplate.SHORTLIST && out.none { it.kind == ProposalKind.DECIDE }) {
            val top = session.body.candidates.filter { it.included }
            if (top.isNotEmpty()) {
                val title = "Choose from the shortlist (${top.size} options)".take(80)
                val note = top.take(4).joinToString("; ") { c -> c.name + (c.variant?.let { " $it" } ?: "") + (c.price?.let { " · " + it.display() + (it.status.takeIf { s -> s != PriceStatus.CONFIRMED }?.let { " (not confirmed)" } ?: "") } ?: "") }
                out += Proposal(ContractParser.defaultProposalId(ProposalKind.DECIDE, title, null), ProposalKind.DECIDE, title, note, null, top.flatMap { it.refs }.distinct(), nowEpochMs, selectedByDefault = false, generated = true)
            }
        }
        if ((session.template == DeliverableTemplate.HOW_TO || session.template == DeliverableTemplate.CHECKLIST) && out.none { it.kind == ProposalKind.STEP }) {
            session.body.steps.filterNot { it.optional }.take(Research2Config.MAX_PROPOSALS).forEach { s ->
                val title = s.text.trimEnd('.').take(80)
                out += Proposal(ContractParser.defaultProposalId(ProposalKind.STEP, title, null), ProposalKind.STEP, title, null, null, s.refs, nowEpochMs, selectedByDefault = s.refs.isNotEmpty(), generated = true)
            }
        }
        return out.distinctBy { it.id }.take(Research2Config.MAX_PROPOSALS)
    }
}
