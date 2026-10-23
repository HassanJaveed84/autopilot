package com.lifeadmin.autopilot.domain.proposal

import com.lifeadmin.autopilot.domain.model.ContentOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLAN-073 (conflict-based reconciliation), PLAN-074 (completed work protected), OD-5 (nothing
 * selected by default; "Select recommended" picks only recommended, non-material, non-conflicting
 * items) and OD-6 (user-stated deadlines are never altered). Pure fixtures, no database.
 */
class ProposalReconcilerTest {
    private val base = TargetSnapshot(
        rev = 3, title = "Renew passport", notes = "Expires in March", status = "ACTIONABLE",
        steps = listOf(
            StepSnapshot("s1", 0, "Find old passport", false),
            StepSnapshot("s2", 1, "Take photo", false),
            StepSnapshot("s3", 2, "Fill in form", true, "USER"),
        ),
        reminder = ReminderSnapshot(true, "DAILY_TIMES", listOf("09:00"), 1, null, null, 1_000),
        takenAtMs = 1_000,
    )

    private fun set(vararg items: ProposalItem) = ProposalSet(
        id = "set1", source = ProposalSource.PLANNING, targetKind = TargetKind.TASK, targetId = "t1", madeAtMs = 1_000,
        baseOriginal = base, base = base, items = items.toList(),
    )

    private val addStep = ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment"), recommended = true)
    private val addAfterS2 = ProposalItem("b", ItemKind.ADD_STEP, value = ItemValue.Step("Print photo", afterStepId = "s2"), recommended = true)
    private val editS2 = ProposalItem("c", ItemKind.EDIT_STEP, anchorStepId = "s2", value = ItemValue.Step("Take a passport photo (35×45 mm)"), recommended = true)
    private val reminder = ProposalItem("d", ItemKind.SET_REMINDER, value = ItemValue.Reminder("DAILY_TIMES", listOf("18:00")), recommended = true, material = true)

    @Test fun tickingAnUnrelatedStepLeavesEveryOtherItemApplicable() {
        // PLAN-073 fixture: tick a step after the snapshot → the item touching that step is flagged; the rest apply.
        val current = base.copy(rev = 4, steps = base.steps.map { if (it.id == "s2") it.copy(isDone = true) else it })
        val checks = ProposalReconciler.reconcile(set(addStep, addAfterS2, editS2, reminder), current).associateBy { it.itemId }
        assertEquals(CheckOutcome.OK, checks.getValue("a").outcome)
        assertEquals("anchor ticked is only a note", CheckOutcome.NOTE, checks.getValue("b").outcome)
        assertEquals("editing the ticked step is a conflict", CheckOutcome.CONFLICT, checks.getValue("c").outcome)
        assertTrue(checks.getValue("c").reason!!.contains("ticked"))
        assertEquals(CheckOutcome.OK, checks.getValue("d").outcome)
    }

    @Test fun snoozingNeverInvalidatesAnything() {
        // PLAN-073 fixture: snooze after the snapshot → nothing is invalidated (a reminder item gets a note at most).
        val current = base.copy(rev = 4, snoozedUntilMs = 5_000)
        val checks = ProposalReconciler.reconcile(set(addStep, addAfterS2, editS2, reminder), current)
        assertTrue(checks.none { it.blocksApply })
        assertEquals(CheckOutcome.NOTE, checks.first { it.itemId == "d" }.outcome)
    }

    @Test fun deletedOrRenamedStepsConflictAndDoneStepsAreProtected() {
        val renamed = base.copy(steps = base.steps.map { if (it.id == "s2") it.copy(title = "Photo booth at the mall") else it })
        assertEquals(CheckOutcome.CONFLICT, ProposalReconciler.check(editS2, base, renamed).outcome)
        val deleted = base.copy(steps = base.steps.filter { it.id != "s2" })
        assertEquals(CheckOutcome.CONFLICT, ProposalReconciler.check(editS2, base, deleted).outcome)
        // PLAN-074: a proposal that would touch a done step never applies without an explicit per-change choice.
        val editDone = ProposalItem("e", ItemKind.EDIT_STEP, anchorStepId = "s3", value = ItemValue.Step("Fill in the online form"))
        val check = ProposalReconciler.check(editDone, base, base)
        assertEquals(CheckOutcome.CONFLICT, check.outcome)
        assertEquals("This step is done", check.reason)
        assertTrue("done steps are material", ProposalReconciler.isMaterial(editDone, base))
    }

    @Test fun userStatedDeadlinesAreNeverChanged() {
        val withDeadline = base.copy(deadlineType = "DATE_ONLY", deadlineDate = "2026-11-01")
        val item = ProposalItem("f", ItemKind.SET_DEADLINE, value = ItemValue.Date("2026-10-20", basis = "processing time"), material = true)
        val check = ProposalReconciler.check(item, withDeadline, withDeadline)
        assertEquals("OD-6: blocked, not merely a conflict", CheckOutcome.BLOCKED, check.outcome)
        assertEquals(CheckOutcome.OK, ProposalReconciler.check(item, base, base).outcome)
        // A planning target is the allowed alternative and lives in its own field.
        val target = ProposalItem("g", ItemKind.SET_TARGET, value = ItemValue.Date("2026-10-20", basis = "3-week processing time"), material = true)
        assertEquals(CheckOutcome.OK, ProposalReconciler.check(target, withDeadline, withDeadline).outcome)
    }

    @Test fun completedTasksBlockEverythingAndFinishingIsNeverProposed() {
        val done = base.copy(status = "DONE")
        assertEquals(CheckOutcome.BLOCKED, ProposalReconciler.check(addStep, base, done).outcome)
        val finish = ProposalItem("h", ItemKind.SET_STATUS, value = ItemValue.Status("DONE"))
        assertEquals(CheckOutcome.BLOCKED, ProposalReconciler.check(finish, base, base).outcome)
        val wait = ProposalItem("i", ItemKind.SET_STATUS, value = ItemValue.Status("WAITING", reason = "the consulate"))
        assertEquals(CheckOutcome.OK, ProposalReconciler.check(wait, base, base).outcome)
        // Status moved since the snapshot but the transition is still possible: a conflict the user decides.
        assertEquals(CheckOutcome.CONFLICT, ProposalReconciler.check(wait, base, base.copy(status = "INBOX")).outcome)
        // Status moved to one the proposed transition can't start from: blocked outright.
        assertEquals(CheckOutcome.BLOCKED, ProposalReconciler.check(wait, base, base.copy(status = "PAUSED")).outcome)
    }

    @Test fun duplicateStepWordingIsAConflictNotASilentSkip() {
        val dup = ProposalItem("j", ItemKind.ADD_STEP, value = ItemValue.Step("take photo"))
        val check = ProposalReconciler.check(dup, base, base)
        assertEquals(CheckOutcome.CONFLICT, check.outcome)
        assertTrue(check.reason!!.contains("already"))
    }

    @Test fun selectRecommendedSkipsMaterialAndConflictingItems() {
        val current = base.copy(steps = base.steps.map { if (it.id == "s2") it.copy(isDone = true) else it })
        val s = set(addStep, addAfterS2, editS2, reminder)
        val checks = ProposalReconciler.reconcile(s, current)
        // OD-5: nothing is pre-selected anywhere; "Select recommended" is the one-tap path and it never picks material or conflicting items.
        assertEquals(setOf("a", "b"), ProposalReconciler.recommendedSelection(s, checks))
        assertTrue(s.items.none { it.status != ItemStatus.PENDING })
    }

    @Test fun materialityFollowsPlan072() {
        assertTrue(ProposalReconciler.isMaterial(reminder, base))
        assertTrue(ProposalReconciler.isMaterial(ProposalItem("k", ItemKind.SET_TITLE, value = ItemValue.Text("x")), base))
        assertTrue(!ProposalReconciler.isMaterial(addStep, base))
        // Editing an AI-origin step is not the user's own wording.
        val aiStep = base.copy(steps = listOf(StepSnapshot("s9", 0, "Suggested step", false, ContentOrigin.PLANNING.name)))
        assertTrue(!ProposalReconciler.isMaterial(ProposalItem("l", ItemKind.EDIT_STEP, anchorStepId = "s9", value = ItemValue.Step("Better wording")), aiStep))
    }

    @Test fun proposalJsonRoundTripsEveryValueKind() {
        val s = set(
            addStep, editS2, reminder,
            ProposalItem("m", ItemKind.SET_TARGET, value = ItemValue.Date("2026-10-20", basis = "verified processing time"), material = true,
                src = ItemSrc(listOf(Premise("3 weeks processing", listOf("F1"))), listOf(FindingRef("F1", "turn1", "f-1", "Processing takes 3 weeks", "established", "2026-10-20", listOf("s1"))), "established", "2026-10-20")),
            ProposalItem("n", ItemKind.SET_STATUS, value = ItemValue.Status("WAITING", "consulate", "2026-10-25T09:00")),
            ProposalItem("o", ItemKind.REMOVE_STEP, anchorStepId = "s1", value = ItemValue.None),
        )
        val back = ProposalJson.decodeSet(ProposalJson.encodeSet(s))
        assertEquals(s, back)
    }
}
