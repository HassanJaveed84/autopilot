package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.core.AppClock
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
 * Turns an M3A.2 session's proposals into the canonical [ProposalSet] (one per session run), so
 * research changes go through the one review and the one write path (INT-042, handoff §1).
 * Pure mapping: nothing here writes a task. Legacy sessions whose moves were applied through the
 * retired M3A.2 screen keep that history: those items appear as already applied.
 */
object ResearchProposalBridge {
    fun setId(sessionId: String, runId: String) = "rps-$sessionId-$runId"

    fun build(session: ResearchSession, snapshot: TargetSnapshot, nowMs: Long): ProposalSet {
        val content = session.content
        val run = content.latestRun
        val runId = run?.id ?: "run"
        val asOf = Instant.ofEpochMilli(run?.startedAtEpochMs ?: nowMs).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val legacyApplied = session.applied.associateBy { it.proposalId }
        val items = content.proposals.map { p ->
            val (kind, value, recommended) = when (p.kind) {
                ProposalKind.NOTE -> Triple(ItemKind.APPEND_NOTE, ItemValue.Text(noteLine(p)), p.refs.isNotEmpty())
                ProposalKind.STEP -> Triple(ItemKind.ADD_STEP, ItemValue.Step(p.title.take(300), ContentOrigin.RESEARCH), p.refs.isNotEmpty())
                ProposalKind.CHECK -> Triple(ItemKind.ADD_STEP, ItemValue.Step(prefixed("Check", p.title), ContentOrigin.UNVERIFIED, verify = true), false)
                ProposalKind.DECIDE -> Triple(ItemKind.ADD_STEP, ItemValue.Step(prefixed("Decide", p.title), ContentOrigin.RESEARCH), false)
                ProposalKind.RESEARCH -> Triple(ItemKind.ADD_STEP, ItemValue.Step(prefixed("Research", p.title), ContentOrigin.UNVERIFIED, verify = true), false)
            }
            val legacy = legacyApplied[p.id]
            ProposalItem(
                id = p.id, kind = kind, value = value, why = p.note.orEmpty(), recommended = recommended, material = false,
                status = if (legacy != null) ItemStatus.APPLIED else ItemStatus.PENDING,
                statusReason = if (legacy != null) "Applied earlier" else null,
                src = src(p, content, runId, asOf),
            )
        }
        return ProposalSet(
            id = setId(session.id, runId), source = ProposalSource.RESEARCH, targetKind = TargetKind.TASK, targetId = session.taskId,
            investigationId = session.id, turnId = runId, madeAtMs = nowMs, model = run?.model.orEmpty(),
            status = if (items.any { it.status == ItemStatus.PENDING }) SetStatus.OPEN else SetStatus.CLOSED,
            baseOriginal = snapshot, base = snapshot, items = items,
            title = "From research: ${content.title}".take(120),
        )
    }

    /** Claim → findings → evidence keys (handoff §8). The "why" text is judgment and is never a finding. */
    private fun src(p: Proposal, c: SessionContent, runId: String, asOf: String): ItemSrc? {
        val refs = (p.refs + p.reusedRefs).distinct()
        if (refs.isEmpty() && p.requirementId == null) return null
        val cov = p.requirementId?.let { c.coverageOf(it) }
        val status = when (cov?.status) {
            RequirementStatus.ANSWERED, RequirementStatus.RESOLVED_CONFLICT -> "established"
            RequirementStatus.YOUR_CALL -> "established · your decision"
            RequirementStatus.NEEDS_CHECK, RequirementStatus.PARTLY -> "indicative"
            RequirementStatus.NOT_FOUND, RequirementStatus.PENDING -> "not established"
            null -> if (refs.isNotEmpty()) "cited" else "not established"
        }
        val findings = refs.mapNotNull { n ->
            val s = c.source(n) ?: return@mapNotNull null
            FindingRef(
                ref = "s$n", turnId = s.firstRunId ?: runId, findingId = p.requirementId ?: p.id,
                text = (cov?.summary?.takeIf { it.isNotBlank() } ?: s.title).take(300), status = status, asOf = asOf,
                keys = listOf(s.url), reused = n in p.reusedRefs || (s.firstRunId != null && s.firstRunId != runId),
            )
        }
        return ItemSrc(premises = listOf(Premise(p.title.take(300), refs.map { "s$it" })), findings = findings, status = status, asOf = asOf)
    }

    private fun noteLine(p: Proposal): String = listOfNotNull(p.title.trim().takeIf { it.isNotEmpty() }, p.note?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" — ").take(600)

    private fun prefixed(verb: String, title: String): String {
        val t = title.trim()
        val starts = when (verb) {
            "Check" -> Regex("^(check|confirm|verify)\\b", RegexOption.IGNORE_CASE)
            "Decide" -> Regex("^(decide|choose|pick)\\b", RegexOption.IGNORE_CASE)
            else -> Regex("^(research|look|find|compare)\\b", RegexOption.IGNORE_CASE)
        }
        return (if (starts.containsMatchIn(t)) t else "$verb: $t").take(300)
    }
}

/**
 * Finds or creates the proposal set for a session's latest run. Idempotent: the same session and
 * run always map to the same set, so reopening the review never duplicates items.
 */
class ResearchProposalSets(
    private val sessions: ResearchSessionStore,
    private val sets: ProposalSetRepository,
    private val applier: ProposalApplier,
    private val clock: AppClock,
) {
    suspend fun ensure(sessionId: String): ProposalSet? {
        val session = sessions.get(sessionId) ?: return null
        val runId = session.content.latestRun?.id ?: "run"
        sets.get(ResearchProposalBridge.setId(sessionId, runId))?.let { return it }
        val snapshot = applier.snapshot(session.taskId) ?: return null
        val set = ResearchProposalBridge.build(session, snapshot, clock.now().toEpochMilli())
        sets.save(set)
        return set
    }

    /** Pending count for the session screen: from the set when it exists, else from the raw proposals. */
    fun pendingCount(session: ResearchSession, existing: ProposalSet?): Int {
        if (existing != null) return existing.pending.size
        val applied = session.applied.map { it.proposalId }.toSet()
        return session.content.proposals.count { it.id !in applied }
    }
}
