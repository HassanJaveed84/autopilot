package com.lifeadmin.autopilot.ai.research2

import java.util.Locale

/**
 * Merges stage outputs into the session, assesses coverage (code, not a prompt) and decides the
 * outcome. The completeness rule is design d3: Complete only when every Item and Output wish is
 * covered and the run ended on its own; running out of searches or money is never success.
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
                attrs = (old.attrs.filter { a -> c.attrs.none { it.key == a.key } } + c.attrs), price = c.price ?: old.price, refs = (old.refs + c.refs).distinct(), hero = c.hero || old.hero,
            )
        }
        out.excluded.forEach { cands.remove(it.id) }
        val excluded = (session.body.excluded.filter { e -> out.excluded.none { it.id == e.id } && out.candidates.none { it.id == e.id } } + out.excluded).distinctBy { it.id }
        val picks = session.body.picks.filter { p -> out.picks.none { it.requirementId == p.requirementId } } + out.picks
        val steps = if (out.steps.isNotEmpty()) out.steps else session.body.steps
        val answer = if (out.answer.isNotEmpty()) out.answer else session.answer
        val proposals = mergeProposals(session.proposals, out.proposals)
        return session.copy(
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

    /** Requirements still worth another call: Items and Output wishes not covered, plus Partly ones. */
    fun gaps(session: SessionContent): List<Requirement> = session.requirements.filter { r ->
        val st = session.coverageOf(r.id).status
        (r.type == RequirementType.ITEM || r.type == RequirementType.OUTPUT || r.type == RequirementType.USER_ADDED) && (st == RequirementStatus.NOT_FOUND || st == RequirementStatus.PENDING || st == RequirementStatus.PARTLY)
    }

    fun priceGaps(session: SessionContent): List<Candidate> = session.body.candidates.filter { it.included && (it.price == null || it.price.status == PriceStatus.NOT_CONFIRMED) }

    /** True when every Item/Output requirement is covered (not Pending/Not found). */
    fun covered(session: SessionContent): Boolean = session.requirements
        .filter { it.type == RequirementType.ITEM || it.type == RequirementType.OUTPUT || it.type == RequirementType.USER_ADDED }
        .all { session.coverageOf(it.id).status.covered }

    /** Why the run ended, as the orchestrator saw it. */
    enum class Ending { NORMAL, BUDGET_SEARCHES, BUDGET_SPEND, STOPPED, FAILED_AFTER_PARTIAL, FAILED, TRUNCATED, INTERRUPTED }

    fun outcome(session: SessionContent, ending: Ending): SessionOutcome {
        val covered = covered(session)
        return when (ending) {
            Ending.STOPPED -> SessionOutcome.STOPPED
            Ending.INTERRUPTED -> SessionOutcome.INTERRUPTED
            Ending.FAILED -> SessionOutcome.FAILED
            Ending.FAILED_AFTER_PARTIAL, Ending.TRUNCATED -> SessionOutcome.PARTIAL
            Ending.BUDGET_SEARCHES, Ending.BUDGET_SPEND -> if (covered) SessionOutcome.COMPLETE else SessionOutcome.INCOMPLETE_BUDGET
            Ending.NORMAL -> if (covered) SessionOutcome.COMPLETE else SessionOutcome.INCOMPLETE_EVIDENCE
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
        val you = st.count { it == RequirementStatus.YOUR_CALL || it == RequirementStatus.NEEDS_CHECK }
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
        // A model proposal without its own citations inherits the evidence of the requirement it serves.
        val out = session.proposals.map { p ->
            when {
                p.refs.isNotEmpty() -> p
                p.requirementId != null -> {
                    val pick = session.body.picks.firstOrNull { it.requirementId == p.requirementId }
                    p.copy(refs = ((pick?.refs ?: emptyList()) + (pick?.labelFacts?.flatMap { it.refs } ?: emptyList()) + session.coverageOf(p.requirementId).refs).distinct())
                }
                else -> {
                    // A shortlist proposal names a candidate: it rests on that candidate's cited evidence.
                    val cand = session.body.candidates.firstOrNull { it.name.length >= 3 && p.title.contains(it.name, ignoreCase = true) }
                    if (cand == null) p else p.copy(refs = (cand.refs + (cand.price?.refs ?: emptyList()) + cand.attrs.flatMap { it.refs }).distinct())
                }
            }
        }.toMutableList()
        val items = session.requirements.filter { it.type == RequirementType.ITEM || it.type == RequirementType.USER_ADDED }
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
