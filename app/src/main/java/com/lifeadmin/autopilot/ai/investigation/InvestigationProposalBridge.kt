package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.proposal.FindingRef
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemSrc
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.Premise
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.SetStatus
import com.lifeadmin.autopilot.domain.proposal.TargetKind
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import java.time.Instant
import java.time.ZoneOffset

/**
 * Turns one turn's proposed changes into the canonical [ProposalSet] (RES-110–113, INT-042): one set
 * per turn, reviewed and applied by the one Proposal Review and the one write path. Pure mapping;
 * nothing here writes a task. Every item carries claim-level provenance: the claims it rests on, their
 * status and as-of, and the sources behind them (handoff §8).
 */
object InvestigationProposalBridge {
    fun setId(investigationId: String, turnId: String) = "ips-$investigationId-$turnId"

    fun build(inv: Investigation, turn: Turn, snapshot: TargetSnapshot, nowMs: Long): ProposalSet {
        val result = turn.result ?: error("a turn without a result proposes nothing")
        val claims = result.claims.associateBy { it.id }
        val sources = result.sources.associateBy { it.n }
        val asOf = Instant.ofEpochMilli(result.finishedAtMs).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val items = result.proposals.map { p ->
            val basis = p.claimIds.mapNotNull { claims[it] }
            val weakest = basis.minByOrNull { rank(it.status) }
            val established = weakest?.status == ClaimStatus.ESTABLISHED
            val statusLabel = weakest?.let { ClaimAssessor.label(it.status) } ?: "not established"
            val anchor = p.targetStepText?.let { t -> snapshot.steps.firstOrNull { it.title.equals(t, ignoreCase = true) } }
            val (kind, value, recommended, material) = when (p.kind) {
                ProposedKind.NOTE -> Quad(ItemKind.APPEND_NOTE, ItemValue.Text(listOfNotNull(p.text, p.note).joinToString(" — ").take(600)), established, false)
                ProposedKind.STEP -> Quad(ItemKind.ADD_STEP, ItemValue.Step(p.text.take(300), ContentOrigin.RESEARCH), established, false)
                ProposedKind.VERIFY -> Quad(ItemKind.ADD_STEP, ItemValue.Step(p.text.take(300), ContentOrigin.UNVERIFIED, verify = true), false, false)
                // Correcting the person's own step is a material change: its own accept, never bulk-selected (PLAN-072).
                ProposedKind.CORRECT_STEP -> if (anchor != null) Quad(ItemKind.EDIT_STEP, ItemValue.Step(p.text.take(300), ContentOrigin.RESEARCH), false, true) else Quad(ItemKind.ADD_STEP, ItemValue.Step(p.text.take(300), ContentOrigin.RESEARCH), established, false)
            }
            ProposalItem(
                id = p.id, kind = kind, anchorStepId = anchor?.id, value = value,
                why = (p.note ?: basis.firstOrNull()?.text ?: "").take(400),
                recommended = recommended, material = material, status = ItemStatus.PENDING,
                src = ItemSrc(
                    premises = basis.map { c -> Premise(c.text.take(300), c.support.filter { !it.pointerOnly }.map { "s${it.sourceN}" }.distinct()) },
                    findings = basis.map { c ->
                        FindingRef(
                            ref = c.id, turnId = c.inheritedFromTurnId ?: c.turnId, findingId = c.id, text = c.text.take(300), status = ClaimAssessor.label(c.status), asOf = c.asOf ?: asOf,
                            keys = c.support.filter { !it.pointerOnly }.mapNotNull { sources[it.sourceN]?.url }.distinct(), reused = c.reused,
                        )
                    },
                    status = statusLabel, asOf = asOf,
                ),
            )
        }
        return ProposalSet(
            id = setId(inv.id, turn.id), source = ProposalSource.RESEARCH, targetKind = TargetKind.TASK, targetId = inv.taskId ?: error("a standalone thread proposes nothing"),
            investigationId = inv.id, turnId = turn.id, madeAtMs = nowMs, model = turn.usage.modelsUsed.firstOrNull().orEmpty(),
            status = if (items.isNotEmpty()) SetStatus.OPEN else SetStatus.CLOSED,
            baseOriginal = snapshot, base = snapshot, items = items,
            title = "From research: ${result.answer.title}".take(120),
            advice = result.answer.stateLine,
        )
    }

    private data class Quad(val kind: ItemKind, val value: ItemValue, val recommended: Boolean, val material: Boolean)

    private fun rank(s: ClaimStatus): Int = when (s) {
        ClaimStatus.NOT_ESTABLISHED -> 0; ClaimStatus.CONTESTED -> 1; ClaimStatus.INDICATIVE -> 2; ClaimStatus.DEPENDS_ON_INPUT -> 3; ClaimStatus.ESTABLISHED -> 4
    }
}

/**
 * Finds or creates the proposal set for a turn. Idempotent: the same turn always maps to the same
 * set, so reopening the review never duplicates items. When a later turn supersedes a claim an
 * earlier set rested on, that set's pending items are marked obsolete rather than silently changed
 * (RES-107, INT-071).
 */
class InvestigationProposalSets(
    private val store: InvestigationRepository,
    private val sets: ProposalSetRepository,
    private val applier: ProposalApplier,
    private val clock: AppClock,
) {
    suspend fun ensure(investigationId: String, turnId: String): ProposalSet? {
        val inv = store.get(investigationId) ?: return null
        val turn = inv.turns.firstOrNull { it.id == turnId } ?: return null
        if (inv.taskId == null || turn.result == null || turn.result.proposals.isEmpty()) return null
        sets.get(InvestigationProposalBridge.setId(inv.id, turn.id))?.let { return it }
        val snapshot = applier.snapshot(inv.taskId) ?: return null
        val set = InvestigationProposalBridge.build(inv, turn, snapshot, clock.now().toEpochMilli())
        sets.save(set)
        return set
    }

    /** Pending count for the thread screen: from the set when it exists, else from the turn's proposals. */
    suspend fun pendingCount(inv: Investigation, turn: Turn): Int {
        if (inv.taskId == null) return 0
        val existing = sets.get(InvestigationProposalBridge.setId(inv.id, turn.id))
        return existing?.pending?.size ?: turn.result?.proposals?.size ?: 0
    }

    /** After a new turn: earlier open sets whose premises were superseded become obsolete, with the reason recorded. */
    suspend fun obsoleteSuperseded(inv: Investigation, newTurn: Turn) {
        val superseded = newTurn.result?.claims?.filter { it.superseded }?.map { it.id }?.toSet().orEmpty()
        if (superseded.isEmpty()) return
        for (earlier in inv.turns.filter { it.index < newTurn.index }) {
            val set = sets.get(InvestigationProposalBridge.setId(inv.id, earlier.id)) ?: continue
            if (set.status != SetStatus.OPEN) continue
            val rests = set.pending.any { item -> item.src?.findings?.any { f -> f.findingId in superseded } == true }
            if (rests) sets.markObsolete(set.id, "A later turn of this research replaced a finding these changes rested on")
        }
    }
}
