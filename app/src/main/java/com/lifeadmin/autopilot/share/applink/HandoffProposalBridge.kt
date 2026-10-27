package com.lifeadmin.autopilot.share.applink

import com.lifeadmin.autopilot.domain.proposal.MatchKey
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.TargetKind
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import java.security.MessageDigest

/**
 * Turns a validated [LinkHandoff] into the canonical [ProposalSet] for one saved task, so a ChatGPT
 * handoff goes through the one review and the one write path (Proposal Review → ProposalApplier).
 * Pure mapping, no AI and no second parser:
 * - each step → ADD_STEP, unless the task or the handoff already has it (same normalisation as
 *   planning). Steps are never added as done, and existing steps, done or not, are never touched;
 * - each decision → APPEND_NOTE "Decided: …"; each note → APPEND_NOTE as written. A line the notes
 *   already contain is skipped;
 * - the title only identifies the task and is never proposed as a rename.
 * Every item is plain (non-material) and recommended, because the user asked ChatGPT to send it;
 * nothing is pre-selected (OD-5).
 */
object HandoffProposalBridge {
    private const val SET_PREFIX = "hps-"
    private const val DECIDED = "Decided: "

    /**
     * The same changes for the same task always map to the same set, so tapping a link again reopens
     * its review (pending, applied or declined) instead of proposing it twice. The title and the
     * reference identify the task and are left out: the destination is [taskId].
     */
    fun setId(taskId: String, handoff: LinkHandoff): String {
        val canonical = buildString {
            append(taskId)
            handoff.decisions.forEach { append("\u0000d").append(it) }
            handoff.steps.forEach { append("\u0000s").append(it) }
            handoff.notes.forEach { append("\u0000n").append(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return SET_PREFIX + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun decisionLine(text: String): String = if (Regex("^(decided|decision)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) text else DECIDED + text

    fun build(handoff: LinkHandoff, taskId: String, snapshot: TargetSnapshot, nowMs: Long): ProposalSet {
        val id = setId(taskId, handoff)
        val items = ArrayList<ProposalItem>()
        fun nextId() = "$id-${items.size}"

        val stepKeys = snapshot.steps.map { key(it.title) }.toMutableSet()
        for (step in handoff.steps) {
            if (!stepKeys.add(key(step))) continue
            items += ProposalItem(nextId(), ItemKind.ADD_STEP, value = ItemValue.Step(step, ContentOrigin.USER), recommended = true)
        }
        val noteKeys = snapshot.notes.orEmpty().lines().map(::key).filter { it.isNotEmpty() }.toMutableSet()
        val lines = handoff.decisions.map(::decisionLine) + handoff.notes
        for (line in lines) {
            if (!noteKeys.add(key(line))) continue
            items += ProposalItem(nextId(), ItemKind.APPEND_NOTE, value = ItemValue.Text(line), recommended = true)
        }
        return ProposalSet(
            id = id, source = ProposalSource.HANDOFF, targetKind = TargetKind.TASK, targetId = taskId, madeAtMs = nowMs,
            baseOriginal = snapshot, base = snapshot, items = items, title = "From ChatGPT",
        )
    }

    private fun key(s: String) = MatchKey.of(s)
}
