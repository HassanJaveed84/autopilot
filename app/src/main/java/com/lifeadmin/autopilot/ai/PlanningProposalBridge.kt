package com.lifeadmin.autopilot.ai

import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.TargetKind
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot

/**
 * Turns a validated follow-through [ProposalDraft] (planning on a saved task) into the canonical
 * [ProposalSet] reviewed and applied by the one path (INT-042, PLAN-070–077):
 * - steps and the suggested next step are plain additions (recommended);
 * - title / notes rewrites, reminders, status and every date are material and never recommended;
 * - a date proposed for a task that already has a deadline becomes a **planning target** with its
 *   basis, never a deadline change (OD-6, PLAN-041, INT-053);
 * - priority is never proposed (D38; PLAN-070).
 * Nothing is pre-selected; selection happens in review (OD-5).
 */
object PlanningProposalBridge {
    fun build(draft: ProposalDraft, details: TaskDetails, snapshot: TargetSnapshot, nowMs: Long): ProposalSet {
        val task = details.task
        val items = ArrayList<ProposalItem>()
        var n = 0
        fun id() = "pl-${draft.proposalId}-${n++}"

        val title = draft.title.trim()
        if (draft.applyTitle && title.isNotEmpty() && title != task.title) {
            items += ProposalItem(id(), ItemKind.SET_TITLE, value = ItemValue.Text(title), why = "A clearer title", material = true)
        }
        val notes = draft.notes.trim()
        if (draft.applyNotes && notes.isNotEmpty() && notes != task.notes.orEmpty()) {
            items += ProposalItem(id(), ItemKind.SET_NOTES, value = ItemValue.Text(notes), why = "Revised notes", material = true)
        }
        val existing = details.steps.map { ProposalValidator.normalizeKeyPublic(it.title) }.toSet()
        for (step in draft.steps) {
            val text = step.text.trim()
            if (text.isEmpty() || ProposalValidator.normalizeKeyPublic(text) in existing) continue
            items += ProposalItem(id(), ItemKind.ADD_STEP, value = ItemValue.Step(text, if (step.edited) ContentOrigin.USER else ContentOrigin.PLANNING), recommended = true)
        }
        val next = draft.nextStep.trim()
        if (next.isNotEmpty() && !ProposalValidator.nextStepListed(draft) && ProposalValidator.normalizeKeyPublic(next) !in existing) {
            items += ProposalItem(id(), ItemKind.ADD_STEP, value = ItemValue.Step(next, ContentOrigin.PLANNING, afterStepId = details.steps.sortedBy { it.sortOrder }.lastOrNull { it.isDone }?.id), why = "Suggested next step", recommended = true)
        }
        draft.deadline.date?.let { date ->
            val basis = when (draft.deadline.provenance) {
                Provenance.EXPLICIT -> "from your words: “${draft.deadline.sourcePhrase}”"
                Provenance.INFERRED -> "read from “${draft.deadline.sourcePhrase}”"
                else -> "suggested by the planner"
            }
            if (task.hasDeadline) {
                items += ProposalItem(id(), ItemKind.SET_TARGET, value = ItemValue.Date(date, basis = basis), why = "Your deadline stays as it is; this is a planning target", material = true)
            } else {
                items += ProposalItem(id(), ItemKind.SET_DEADLINE, value = ItemValue.Date(date, draft.deadline.time, basis = basis), material = true)
            }
        }
        val r = draft.reminder
        val reminderValue: ItemValue.Reminder? = when {
            r.kind != DraftReminderKind.OFF -> ItemValue.Reminder(kindName(r.kind), r.times, r.dayInterval, r.startDate, r.onceAt, basis = r.requestedPhrase.ifBlank { "suggested by the planner" })
            r.unsupported != null -> r.unsupported.let { u -> ItemValue.Reminder(kindName(u.alternativeKind), u.alternativeTimes, u.alternativeInterval, u.alternativeStartDate, null, basis = "closest supported schedule to: ${u.description}") }
            else -> null
        }
        reminderValue?.takeIf { it.kind != "OFF" }?.let { v ->
            items += ProposalItem(id(), ItemKind.SET_REMINDER, value = v, why = if (r.provenance == Provenance.SUGGESTED) "The planner suggests a reminder" else "You asked to be reminded", material = true)
        }
        if (draft.waiting.apply) {
            items += ProposalItem(id(), ItemKind.SET_STATUS, value = ItemValue.Status("WAITING", draft.waiting.waitingFor.trim().ifBlank { null }, draft.waiting.checkIn), why = "Waiting on someone else", material = true)
        }
        return ProposalSet(
            id = "ps-${draft.proposalId}", source = ProposalSource.PLANNING, targetKind = TargetKind.TASK, targetId = task.id, madeAtMs = nowMs, model = draft.model,
            baseOriginal = snapshot, base = snapshot, items = items, title = "Plan next step",
            advice = draft.advice.trim(), assumptions = draft.assumptions, questions = draft.questions,
        )
    }

    private fun kindName(k: DraftReminderKind): String = when (k) {
        DraftReminderKind.OFF -> "OFF"
        DraftReminderKind.ONCE -> "ONCE"
        DraftReminderKind.DAILY -> "DAILY_TIMES"
        DraftReminderKind.EVERY_N_DAYS -> "EVERY_N_DAYS"
    }
}
