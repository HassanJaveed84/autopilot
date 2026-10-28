package com.lifeadmin.autopilot.share.applink

import com.lifeadmin.autopilot.domain.proposal.MatchKey
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.StepDetails
import com.lifeadmin.autopilot.domain.model.StepLink
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
 *   planning). Steps are never added as done. A v2 step carries its description and links in the
 *   same item, so it is reviewed and applied as one unit;
 * - a v2 step whose name is already on the task, with details → ADD_STEP_DETAILS holding only the
 *   paragraphs and links that aren't there yet (nothing new → no item). Its wording, done state and
 *   existing details are never touched. Matching uses the task's step ids once found: exactly one
 *   step with that name → that step; more than one → the user picks one in review, never a guess;
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
            handoff.steps.forEachIndexed { i, step ->
                append("\u0000s").append(step)
                // Only rich steps add to the key, so a v1 link and its plain v2 twin reopen the same set.
                handoff.stepDetails[i]?.let { d ->
                    d.description.forEach { append("\u0000sd").append(it) }
                    d.links.forEach { append("\u0000sl").append(it.label.orEmpty()).append(' ').append(it.url) }
                }
            }
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

        // One group per distinct step name, in first-mention order; a name repeated in the link merges its details.
        class Group(val title: String, val paragraphs: MutableList<String> = mutableListOf(), val links: MutableList<StepLink> = mutableListOf())
        val groups = LinkedHashMap<String, Group>()
        handoff.steps.forEachIndexed { i, step ->
            val g = groups.getOrPut(key(step)) { Group(step) }
            val d = handoff.details(i)
            g.paragraphs += StepDetails.newParagraphs(g.paragraphs.joinToString("\n"), d.description, ::key)
            g.links += StepDetails.newLinks(g.links, d.links)
        }
        for ((k, g) in groups) {
            val matches = snapshot.steps.filter { key(it.title) == k }
            when {
                matches.isEmpty() -> items += ProposalItem(
                    nextId(), ItemKind.ADD_STEP,
                    value = ItemValue.Step(g.title, ContentOrigin.USER, description = g.paragraphs.joinToString("\n").ifEmpty { null }, links = g.links.toList()),
                    recommended = true,
                )
                // Already on the task and nothing to add: as in v1, nothing is proposed.
                g.paragraphs.isEmpty() && g.links.isEmpty() -> Unit
                matches.size == 1 -> {
                    val m = matches.single()
                    val paragraphs = StepDetails.newParagraphs(m.description, g.paragraphs, ::key)
                    val links = StepDetails.newLinks(m.links, g.links)
                    if (paragraphs.isNotEmpty() || links.isNotEmpty()) {
                        items += ProposalItem(
                            nextId(), ItemKind.ADD_STEP_DETAILS,
                            value = ItemValue.StepDetails(stepId = m.id, stepTitle = g.title, description = paragraphs.joinToString("\n").ifEmpty { null }, links = links),
                            recommended = true,
                        )
                    }
                }
                // Two or more steps share the name: the user chooses which one gets the details.
                else -> items += ProposalItem(
                    nextId(), ItemKind.ADD_STEP_DETAILS,
                    value = ItemValue.StepDetails(stepId = null, choices = matches.map { it.id }, stepTitle = g.title, description = g.paragraphs.joinToString("\n").ifEmpty { null }, links = g.links.toList()),
                    recommended = true,
                )
            }
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
