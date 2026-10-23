package com.lifeadmin.autopilot.ai

import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStep
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Follow-through drafts become canonical proposal sets: PLAN-070 (no priority), OD-5 (nothing selected), OD-6 / PLAN-041 (targets, not deadline changes), PLAN-072 (material). */
class PlanningProposalBridgeTest {
    private val now = Instant.parse("2026-10-23T09:00:00Z")

    private fun details(task: Task, steps: List<String> = listOf("Find the contract")) =
        TaskDetails(task, steps.mapIndexed { i, t -> TaskStep("s$i", task.id, i, t) }, null, null, emptyList(), emptyList())

    private fun draft(vararg steps: String, next: String = "", deadline: DraftDeadline = DraftDeadline(), reminder: DraftReminder = DraftReminder(), waiting: DraftWaiting = DraftWaiting(), title: String = "Cancel the gym membership", applyTitle: Boolean = false, priority: String? = "HIGH") =
        ProposalDraft(
            proposalId = "p1", mode = PlanningMode.FOLLOW_THROUGH, originalText = "What first?", title = title, applyTitle = applyTitle,
            steps = steps.map { DraftStep(it) }, nextStep = next, deadline = deadline, reminder = reminder, waiting = waiting,
            priority = priority, applyPriority = true, advice = "Check the notice period first.", questions = listOf("Is there a notice period?"), model = "claude-haiku-5-5", targetTaskId = "t1",
        )

    @Test fun stepsAndNextStepAreRecommendedAdditionsAndNothingIsSelectedOrPrioritised() {
        val task = Task("t1", "Cancel the gym membership", createdAt = now, updatedAt = now)
        val d = details(task)
        val set = PlanningProposalBridge.build(draft("Find the contract", "Email the gym", next = "Find the cancellation clause"), d, TargetSnapshot.of(task, d.steps, null, 0), 1_000)
        assertEquals(ProposalSource.PLANNING, set.source)
        assertEquals("t1", set.targetId)
        assertEquals("existing step is not proposed again", listOf("Email the gym", "Find the cancellation clause"), set.items.map { (it.value as ItemValue.Step).text })
        assertTrue(set.items.all { it.kind == ItemKind.ADD_STEP && it.recommended && !it.material && it.status == ItemStatus.PENDING })
        assertEquals("Suggested next step", set.items.last().why)
        assertEquals(ContentOrigin.PLANNING, (set.items.first().value as ItemValue.Step).origin)
        assertTrue("priority is never proposed (D38)", set.items.none { it.kind.name.contains("PRIORITY") })
        assertEquals("Check the notice period first.", set.advice)
        assertEquals(listOf("Is there a notice period?"), set.questions)
    }

    @Test fun aDateBecomesAPlanningTargetWhenTheTaskAlreadyHasADeadline() {
        val withDeadline = Task("t1", "Renew passport", createdAt = now, updatedAt = now, deadlineType = DeadlineType.DATE_ONLY, deadlineLocalDate = LocalDate.of(2026, 11, 20))
        val d = details(withDeadline)
        val proposed = DraftDeadline(provenance = Provenance.INFERRED, date = "2026-11-05", sourcePhrase = "allow three weeks")
        val set = PlanningProposalBridge.build(draft(deadline = proposed), d, TargetSnapshot.of(withDeadline, d.steps, null, 0), 1_000)
        val target = set.items.single { it.kind == ItemKind.SET_TARGET }
        assertTrue(target.material)
        assertFalse(target.recommended)
        assertEquals("2026-11-05", (target.value as ItemValue.Date).date)
        assertTrue((target.value as ItemValue.Date).basis.contains("allow three weeks"))
        assertTrue("never a deadline change", set.items.none { it.kind == ItemKind.SET_DEADLINE })

        val noDeadline = withDeadline.copy(deadlineType = DeadlineType.NONE, deadlineLocalDate = null)
        val d2 = details(noDeadline)
        val set2 = PlanningProposalBridge.build(draft(deadline = proposed.copy(provenance = Provenance.EXPLICIT, sourcePhrase = "by the 5th")), d2, TargetSnapshot.of(noDeadline, d2.steps, null, 0), 1_000)
        val deadline = set2.items.single { it.kind == ItemKind.SET_DEADLINE }
        assertTrue(deadline.material)
        assertTrue((deadline.value as ItemValue.Date).basis.contains("by the 5th"))
    }

    @Test fun remindersStatusAndTitleAreMaterialAndCarryTheirBasis() {
        val task = Task("t1", "Cancel the gym membership", createdAt = now, updatedAt = now)
        val d = details(task)
        val set = PlanningProposalBridge.build(
            draft(
                reminder = DraftReminder(kind = DraftReminderKind.DAILY, times = listOf("09:00"), provenance = Provenance.EXPLICIT, requestedPhrase = "remind me every morning"),
                waiting = DraftWaiting(apply = true, waitingFor = "the gym's reply", checkIn = "2026-10-30T09:00"),
                title = "Cancel the gym membership before we move", applyTitle = true,
            ),
            d, TargetSnapshot.of(task, d.steps, null, 0), 1_000,
        )
        val reminder = set.items.single { it.kind == ItemKind.SET_REMINDER }
        assertTrue(reminder.material)
        assertEquals("DAILY_TIMES", (reminder.value as ItemValue.Reminder).kind)
        assertEquals("remind me every morning", (reminder.value as ItemValue.Reminder).basis)
        val status = set.items.single { it.kind == ItemKind.SET_STATUS }
        assertTrue(status.material)
        assertEquals("WAITING", (status.value as ItemValue.Status).status)
        assertEquals("the gym's reply", (status.value as ItemValue.Status).reason)
        val title = set.items.single { it.kind == ItemKind.SET_TITLE }
        assertTrue("user wording is material (PLAN-072)", title.material)
        assertNull("nothing is pre-selected", set.items.firstOrNull { it.status != ItemStatus.PENDING })
    }

    @Test fun anUnsupportedScheduleOffersItsClosestSupportedAlternative() {
        val task = Task("t1", "Water the plants", createdAt = now, updatedAt = now)
        val d = details(task, emptyList())
        val set = PlanningProposalBridge.build(
            draft(reminder = DraftReminder(kind = DraftReminderKind.OFF, unsupported = UnsupportedSchedule("every weekday at 7", DraftReminderKind.DAILY, listOf("07:00"), 1, "Every day at 07:00"))),
            d, TargetSnapshot.of(task, d.steps, null, 0), 1_000,
        )
        val reminder = set.items.single { it.kind == ItemKind.SET_REMINDER }
        assertEquals(listOf("07:00"), (reminder.value as ItemValue.Reminder).times)
        assertTrue((reminder.value as ItemValue.Reminder).basis.contains("every weekday at 7"))
    }
}
