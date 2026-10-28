package com.lifeadmin.autopilot.domain.proposal

import com.lifeadmin.autopilot.domain.model.StepDetails
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.TaskStateMachine

enum class CheckOutcome {
    /** No relevant change since the set's base. */
    OK,

    /** A related change that does not conflict (a snooze; a ticked anchor). Shown as "Still applies". */
    NOTE,

    /** The user must choose: apply anyway or keep theirs (which declines the item). */
    CONFLICT,

    /** Cannot be applied at all: a user-stated deadline, completed work, an impossible transition. */
    BLOCKED,
}

data class ItemCheck(val itemId: String, val outcome: CheckOutcome, val reason: String? = null) {
    val blocksApply: Boolean get() = outcome == CheckOutcome.CONFLICT || outcome == CheckOutcome.BLOCKED
}

/**
 * Checks every pending item of a set against the current target (PLAN-073, handoff §5 "Review
 * reconciliation"). Pure and deterministic: the same inputs always produce the same checks.
 * Compatible changes stay applicable; conflicting ones are flagged, never dropped or overwritten;
 * unrelated activity (a snooze, another step ticked) never invalidates a compatible change.
 */
object ProposalReconciler {
    fun reconcile(set: ProposalSet, current: TargetSnapshot): List<ItemCheck> = set.pending.map { check(it, set.base, current) }

    fun check(item: ProposalItem, base: TargetSnapshot, current: TargetSnapshot): ItemCheck {
        val status = TaskStatus.entries.firstOrNull { it.name == current.status }
        if (status?.isTerminal == true) return blocked(item, "The task is ${if (status == TaskStatus.DONE) "done" else "cancelled"}; reopen it first")
        return when (item.kind) {
            ItemKind.ADD_STEP -> addStep(item, base, current)
            ItemKind.EDIT_STEP, ItemKind.REMOVE_STEP -> anchoredStep(item, base, current)
            ItemKind.ADD_STEP_DETAILS -> stepDetails(item, current)
            ItemKind.APPEND_NOTE -> if (base.notes != current.notes) note(item, "Notes changed since; this is added after them") else ok(item)
            ItemKind.SET_NOTES -> if (base.notes != current.notes) conflict(item, "You changed the notes since this was proposed") else ok(item)
            ItemKind.SET_TITLE -> if (base.title != current.title) conflict(item, "You changed the title since this was proposed") else ok(item)
            ItemKind.SET_DEADLINE -> when {
                current.hasDeadline -> blocked(item, "Your deadline is kept. A proposed date can only become a planning target")
                base.hasDeadline -> conflict(item, "You removed your deadline since this was proposed")
                else -> ok(item)
            }
            ItemKind.SET_TARGET -> if (base.targetDate != current.targetDate || base.targetBasis != current.targetBasis) conflict(item, "The planning target changed since this was proposed") else ok(item)
            ItemKind.SET_EVENT_DATE -> if (base.eventDate != current.eventDate) conflict(item, "The event date changed since this was proposed") else ok(item)
            ItemKind.SET_REMINDER -> when {
                reminderChanged(base, current) -> conflict(item, "You changed the reminders since this was proposed")
                base.snoozedUntilMs != current.snoozedUntilMs -> note(item, "The task was snoozed since; the reminder still applies")
                else -> ok(item)
            }
            ItemKind.SET_STATUS -> statusItem(item, base, current, status)
        }
    }

    private fun addStep(item: ProposalItem, base: TargetSnapshot, current: TargetSnapshot): ItemCheck {
        val text = (item.value as? ItemValue.Step)?.text?.trim().orEmpty()
        if (current.steps.any { it.title.equals(text, ignoreCase = true) }) return conflict(item, "A step with this wording is already on the task")
        val anchorId = (item.value as? ItemValue.Step)?.afterStepId ?: return ok(item)
        val now = current.step(anchorId) ?: return note(item, "The step it followed is gone; it goes at the end")
        return if (now.isDone && base.step(anchorId)?.isDone == false) note(item, "The step it follows was ticked; this still applies") else ok(item)
    }

    private fun anchoredStep(item: ProposalItem, base: TargetSnapshot, current: TargetSnapshot): ItemCheck {
        val id = item.anchorStepId ?: return blocked(item, "No step to change")
        val was = base.step(id)
        val now = current.step(id) ?: return conflict(item, "You deleted this step")
        // Completed work is protected (PLAN-074): changing a done step needs an explicit per-change choice.
        if (now.isDone) return conflict(item, if (was?.isDone == true) "This step is done" else "You ticked this step since")
        if (was != null && was.title != now.title) return conflict(item, "You renamed this step since")
        return ok(item)
    }

    /**
     * Details for an existing step: the destination must be chosen (never guessed), still exist and
     * still take what's new. Completed work is protected (PLAN-074): a ticked step needs an explicit
     * "apply anyway", and stays ticked. A rename since doesn't matter: the step is found by its id.
     */
    private fun stepDetails(item: ProposalItem, current: TargetSnapshot): ItemCheck {
        val v = item.value as? ItemValue.StepDetails ?: return blocked(item, "Nothing to add")
        val id = v.stepId ?: return conflict(item, CHOOSE_STEP)
        val now = current.step(id) ?: return blocked(item, "That step no longer exists")
        val paragraphs = StepDetails.newParagraphs(now.description, StepDetails.paragraphs(v.description), MatchKey::of)
        val links = StepDetails.newLinks(now.links, v.links)
        if (paragraphs.isEmpty() && links.isEmpty()) return note(item, "Everything here is already on the step")
        if (now.links.size + links.size > StepDetails.MAX_LINKS) return blocked(item, "The step would have more than ${StepDetails.MAX_LINKS} links")
        val merged = StepDetails.appendDescription(now.description, paragraphs).orEmpty()
        if (merged.codePointCount(0, merged.length) > StepDetails.MAX_DESCRIPTION) return blocked(item, "The step's description would be too long")
        if (now.isDone) return conflict(item, "This step is done; it stays done")
        return ok(item)
    }

    /** The reason shown while an ambiguous match waits for the user to pick the step. */
    const val CHOOSE_STEP = "More than one step has this name. Choose the one these details belong to"

    private fun statusItem(item: ProposalItem, base: TargetSnapshot, current: TargetSnapshot, status: TaskStatus?): ItemCheck {
        val to = (item.value as? ItemValue.Status)?.status?.let { s -> TaskStatus.entries.firstOrNull { it.name == s } } ?: return blocked(item, "Unknown status")
        if (status == null) return blocked(item, "Unknown status")
        if (to.isTerminal) return blocked(item, "Finishing or cancelling a task is yours to do")
        if (status != to && !TaskStateMachine.canTransition(status, to)) return blocked(item, "A ${status.name.lowercase()} task can't go to ${to.name.lowercase()}")
        if (base.status != current.status) return conflict(item, "You changed the status since this was proposed")
        return ok(item)
    }

    private fun reminderChanged(base: TargetSnapshot, current: TargetSnapshot): Boolean {
        val b = base.reminder
        val c = current.reminder
        if (b == null && c == null) return false
        if (b == null || c == null) return (b ?: c)!!.enabled
        return b.enabled != c.enabled || b.kind != c.kind || b.times != c.times || b.dayInterval != c.dayInterval || b.startDate != c.startDate || b.onceAtMs != c.onceAtMs
    }

    private fun ok(i: ProposalItem) = ItemCheck(i.id, CheckOutcome.OK)
    private fun note(i: ProposalItem, why: String) = ItemCheck(i.id, CheckOutcome.NOTE, why)
    private fun conflict(i: ProposalItem, why: String) = ItemCheck(i.id, CheckOutcome.CONFLICT, why)
    private fun blocked(i: ProposalItem, why: String) = ItemCheck(i.id, CheckOutcome.BLOCKED, why)

    /** Which pending items "Select recommended" picks: recommended, not material, not conflicting (handoff §7 rule 2). */
    fun recommendedSelection(set: ProposalSet, checks: List<ItemCheck>): Set<String> {
        val byId = checks.associateBy { it.itemId }
        return set.pending.filter { it.recommended && !it.material && byId[it.id]?.outcome.let { o -> o == CheckOutcome.OK || o == CheckOutcome.NOTE } }.map { it.id }.toSet()
    }

    /** Whether a change is material (PLAN-072): timing, reminders, status, done steps, or the user's own wording. */
    fun isMaterial(item: ProposalItem, target: TargetSnapshot): Boolean = when (item.kind) {
        ItemKind.SET_DEADLINE, ItemKind.SET_TARGET, ItemKind.SET_EVENT_DATE, ItemKind.SET_REMINDER, ItemKind.SET_STATUS -> true
        ItemKind.SET_TITLE, ItemKind.SET_NOTES -> true
        ItemKind.EDIT_STEP, ItemKind.REMOVE_STEP -> target.step(item.anchorStepId)?.let { it.isDone || it.origin == "USER" } ?: true
        // Adds to a step without changing its wording, done state or anything already on it.
        ItemKind.ADD_STEP, ItemKind.APPEND_NOTE, ItemKind.ADD_STEP_DETAILS -> false
    }
}
