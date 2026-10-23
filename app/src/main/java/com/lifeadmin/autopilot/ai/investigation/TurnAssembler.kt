package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research2.CallDiagnostic
import com.lifeadmin.autopilot.ai.research2.SourceRef2
import com.lifeadmin.autopilot.ai.research2.StageRecord

/**
 * The working state of one turn between calls: claims (inherited and new), the answer parts the model
 * wrote, changes, proposals, queries. Pure merging; the service only feeds it parser output and asks
 * for a result. The same inputs always give the same result (RES-068).
 */
class TurnWork(
    val turnId: String,
    inherited: List<Claim>,
    val taskLinked: Boolean,
    /** Origin turn of every claim id this investigation has held, so a fresh re-check keeps its lineage (RES-101). */
    private val lineage: Map<String, String> = emptyMap(),
    /** Claim ids the person excluded: never reinserted, whatever a reply says (OD-4). */
    private val excluded: Set<String> = emptySet(),
) {
    val claims = LinkedHashMap<String, Claim>().apply { inherited.forEach { put(it.id, it.copy(reused = true, inheritedFromTurnId = it.inheritedFromTurnId ?: it.turnId.takeIf { t -> t != turnId })) } }
    var title: String? = null
    var answer: List<AnswerSegment> = emptyList()
    var steps: List<AnswerSegment> = emptyList()
    var compareRows: List<CompareRow> = emptyList()
    var compareOptions: List<String> = emptyList()
    var options: List<AnswerSegment> = emptyList()
    var recommendation: Recommendation? = null
    var worthKnowing: List<AnswerSegment> = emptyList()
    val changes = ArrayList<ClaimChange>()
    val unresolved = ArrayList<Unresolved>()
    val proposals = LinkedHashMap<String, ProposedChange>()
    val queries = ArrayList<String>()
    var injectionNoticed = false
    /** Something usable was merged from at least one call. */
    var merged = false
    /** The verify pass ran (or was not planned); otherwise open claims count as not checked when the turn ends early. */
    var verifyRan = false

    fun current(): List<Claim> = claims.values.toList()
    fun openClaims(): List<Claim> = current().filter { it.loadBearing && !it.superseded && !it.dependsOnUser && it.status != ClaimStatus.ESTABLISHED }
    fun contested(): List<Claim> = current().filter { it.status == ClaimStatus.CONTESTED && !it.superseded }

    /** Merges one call's output; returns true when it changed anything. */
    fun merge(out: TurnOutput, nowMs: Long): Boolean {
        var changed = false
        if (out.injectionNoticed) injectionNoticed = true
        for (c in out.claims) {
            if (c.id in excluded) continue
            val prior = claims[c.id]
            // A re-check that brings no new verified source keeps the earlier opposing evidence it did not restate, so a
            // conflict is never lost by silence; one that brings fresh evidence stands as written (RES-106), and the
            // resolution of an earlier disagreement is recorded as a change the person can see.
            val fresh = prior != null && c.support.any { e -> !e.pointerOnly && prior.support.none { p -> p.sourceN == e.sourceN } }
            val against = if (prior != null && !fresh) (c.against + prior.against).distinctBy { it.sourceN } else c.against
            if (prior != null && fresh && prior.against.isNotEmpty() && c.against.isEmpty() && changes.none { it.earlierClaimId == c.id && it.kind == ChangeKind.CONFIRMED }) {
                changes += ClaimChange(c.id, null, ChangeKind.CONFIRMED, "Re-checked with new evidence; the earlier disagreement about this is resolved.")
            }
            val merged = c.copy(
                reused = false, checked = true,
                inheritedFromTurnId = prior?.inheritedFromTurnId ?: prior?.turnId?.takeIf { it != turnId } ?: c.inheritedFromTurnId ?: lineage[c.id]?.takeIf { it != turnId },
                against = against,
            )
            if (prior == null || prior != merged) changed = true
            claims[c.id] = merged
        }
        for (ch in out.changes) {
            if (changes.none { it.earlierClaimId == ch.earlierClaimId && it.kind == ch.kind }) { changes += ch; changed = true }
            if (ch.kind == ChangeKind.CHANGED && ch.newClaimId != null) {
                claims[ch.earlierClaimId]?.let { old -> if (!old.superseded) claims[ch.earlierClaimId] = old.copy(superseded = true, supersededByClaimId = ch.newClaimId, statusReason = "Replaced by ${ch.newClaimId}") }
            }
        }
        if (out.title != null && out.title != title) { title = out.title; changed = true }
        if (out.answer.isNotEmpty()) { answer = out.answer; changed = true }
        if (out.steps.isNotEmpty()) { steps = out.steps; changed = true }
        if (out.compareRows.isNotEmpty()) { compareRows = out.compareRows; compareOptions = out.compareOptions; changed = true }
        if (out.options.isNotEmpty()) { options = out.options; changed = true }
        if (out.recommendation != null) { recommendation = out.recommendation; changed = true }
        if (out.worthKnowing.isNotEmpty()) { worthKnowing = out.worthKnowing; changed = true }
        for (u in out.unresolved) if (unresolved.none { it.topic.equals(u.topic, true) }) { unresolved += u; changed = true }
        for (p in out.proposals) if (!proposals.containsKey(p.id)) { proposals[p.id] = p; changed = true }
        for (q in out.queries) if (q !in queries) queries += q
        if (changed) merged = true
        // Statuses follow the evidence after every call, never the model's words.
        val assessed = ClaimAssessor.assess(current(), nowMs)
        assessed.forEach { claims[it.id] = it }
        return changed
    }

    /** The final (or provisional) record: statuses from evidence, state derived, answer composed, proposals gated. */
    fun result(objective: Objective, ending: TurnEnding, sources: List<SourceRef2>, stages: List<StageRecord>, diagnostics: List<CallDiagnostic>, failure: String?, nowMs: Long): TurnResult {
        var list = current()
        if (ending != TurnEnding.COMPLETE && !verifyRan) {
            // The run never reached the pass that would have checked these: say so instead of calling them unsupported.
            list = list.map { if (it.loadBearing && !it.superseded && !it.dependsOnUser && it.status != ClaimStatus.ESTABLISHED && it.support.none { e -> !e.pointerOnly } && !it.reused) it.copy(checked = false) else it }
        }
        val assessed = ClaimAssessor.assess(list, nowMs)
        val derivation = ResultDeriver.derive(assessed, ending)
        val out = TurnOutput(title, answer, assessed, steps, compareRows, compareOptions, options, recommendation, worthKnowing, changes.toList(), unresolved.toList(), proposals.values.toList(), queries.toList(), injectionNoticed)
        val composed = AnswerComposer.compose(objective, out, assessed, derivation, AnswerComposer.fallbackTitle(objective))
        val (kept, heldBack) = gateProposals(proposals.values.toList(), assessed)
        return TurnResult(
            answer = composed, state = derivation.state, ending = ending, claims = assessed, sources = sources, queries = queries.toList(),
            changes = changes.toList(), unresolved = unresolved.toList(), proposals = if (taskLinked) kept else emptyList(), heldBackProposals = if (taskLinked) heldBack else 0,
            injectionNoticed = injectionNoticed, stages = stages, diagnostics = diagnostics, failure = failure, finishedAtMs = nowMs,
        )
    }

    /**
     * RES-110–113, OD-8: a proposal resting on established evidence stands as proposed; one resting on
     * indicative, contested or user-dependent claims becomes a labelled verification need; one resting
     * on nothing established, or on a claim never checked, is held back and counted.
     */
    private fun gateProposals(all: List<ProposedChange>, claims: List<Claim>): Pair<List<ProposedChange>, Int> {
        val byId = claims.associateBy { it.id }
        val kept = ArrayList<ProposedChange>()
        var held = 0
        for (p in all) {
            val basis = p.claimIds.mapNotNull { byId[it] }
            if (basis.isEmpty() || basis.any { !it.checked || it.status == ClaimStatus.NOT_ESTABLISHED }) { held++; continue }
            val weakest = basis.minByOrNull { statusRank(it.status) }!!
            kept += when (weakest.status) {
                ClaimStatus.ESTABLISHED -> p
                else -> if (p.kind == ProposedKind.VERIFY || p.kind == ProposedKind.NOTE) p else p.copy(kind = ProposedKind.VERIFY, text = ("Check: " + p.text).take(80), note = (p.note?.let { "$it · " } ?: "") + "Rests on something ${ClaimAssessor.label(weakest.status)}", supersedesAssumption = false)
            }
        }
        return kept to held
    }

    private fun statusRank(s: ClaimStatus): Int = when (s) {
        ClaimStatus.NOT_ESTABLISHED -> 0; ClaimStatus.CONTESTED -> 1; ClaimStatus.INDICATIVE -> 2; ClaimStatus.DEPENDS_ON_INPUT -> 3; ClaimStatus.ESTABLISHED -> 4
    }
}
