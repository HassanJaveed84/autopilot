package com.lifeadmin.autopilot.ui

import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderOccurrence
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.dailyPolicy
import com.lifeadmin.autopilot.testing.t
import com.lifeadmin.autopilot.testing.task
import com.lifeadmin.autopilot.ui.components.CardContext
import com.lifeadmin.autopilot.ui.components.StatusGlyph
import com.lifeadmin.autopilot.ui.components.StatusTone
import com.lifeadmin.autopilot.ui.components.TaskPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The "status becomes a sentence" formatter shared by cards, detail and Today. */
class TaskPresentationTest {
    private val now = at(CHICAGO, 2026, 10, 19, 8, 42)
    private fun occ(plannedAt: Instant, kind: OccurrenceKind = OccurrenceKind.POLICY) =
        ReminderOccurrence("o1", "policy-1", "task-1", kind, plannedAt, plannedAt, now, null, OccurrenceStatus.SCHEDULED, 1)
    private fun summary(task: com.lifeadmin.autopilot.domain.model.Task, policy: ReminderPolicy? = null, next: ReminderOccurrence? = null, steps: Pair<Int, Int> = 0 to 0, nextStep: String? = null) =
        TaskSummary(task, steps.first, steps.second, nextStep, next, policy)

    @Test fun railContextDescribesTheScheduleAndListContextTheNextMoment() {
        val policy = dailyPolicy(t(9), t(18))
        val s = summary(task(), policy, occ(at(CHICAGO, 2026, 10, 19, 9, 0)))
        val rail = TaskPresentation.status(s, now, CHICAGO, CardContext.RAIL)
        val list = TaskPresentation.status(s, now, CHICAGO, CardContext.LIST)
        assertEquals("09:00 & 18:00 daily, until done", rail.text)
        assertEquals(StatusGlyph.BELL, rail.glyph)
        assertEquals(StatusTone.SIGNAL, rail.tone)
        assertEquals("Next nudge today 09:00", list.text)
    }

    @Test fun oneTimeReminderNeverSaysUntilDone() {
        val policy = dailyPolicy().copy(kind = ReminderKind.ONCE, atInstant = at(CHICAGO, 2026, 10, 20, 9, 0), times = emptyList())
        val s = summary(task(), policy, occ(at(CHICAGO, 2026, 10, 20, 9, 0)))
        assertEquals("once, tomorrow 09:00", TaskPresentation.status(s, now, CHICAGO, CardContext.RAIL).text)
    }

    @Test fun waitingSnoozedPausedAndDoneAreOneSentenceEach() {
        val waiting = task(TaskStatus.WAITING).copy(waitingReason = "Greenway Lettings", waitingCheckInAt = at(CHICAGO, 2026, 10, 21, 8, 0))
        assertEquals("Waiting on Greenway Lettings · check in Wed 21 Oct, 08:00", TaskPresentation.status(summary(waiting), now, CHICAGO).text)
        val overdueCheckIn = waiting.copy(waitingCheckInAt = at(CHICAGO, 2026, 10, 18, 8, 0))
        assertEquals(StatusTone.SIGNAL, TaskPresentation.status(summary(overdueCheckIn), now, CHICAGO).tone)

        val snoozed = task().copy(snoozedUntil = at(CHICAGO, 2026, 10, 20, 9, 0))
        assertEquals("Snoozed until tomorrow 09:00", TaskPresentation.status(summary(snoozed, dailyPolicy(t(9))), now, CHICAGO).text)
        assertEquals("Paused · no nudges", TaskPresentation.status(summary(task(TaskStatus.PAUSED), dailyPolicy(t(9))), now, CHICAGO).text)
        val done = TaskPresentation.status(summary(task(TaskStatus.DONE)), now, CHICAGO)
        assertEquals("Done", done.text)
        assertEquals(StatusTone.DONE, done.tone)
        assertEquals("No nudges set", TaskPresentation.status(summary(task()), now, CHICAGO).text)
        assertEquals("In Inbox · no nudges yet", TaskPresentation.status(summary(task(TaskStatus.INBOX)), now, CHICAGO).text)
    }

    @Test fun deadlineLineIsSignalForHighPriorityAndDangerWhenOverdue() {
        val due = task().copy(deadlineType = DeadlineType.DATE_ONLY, deadlineLocalDate = LocalDate.of(2026, 10, 29))
        assertEquals(StatusTone.MUTED, TaskPresentation.deadline(due, now, CHICAGO)!!.tone)
        assertEquals(StatusTone.SIGNAL, TaskPresentation.deadline(due.copy(priority = Priority.HIGH), now, CHICAGO)!!.tone)
        val overdue = TaskPresentation.deadline(due.copy(deadlineLocalDate = LocalDate.of(2026, 10, 18)), now, CHICAGO)!!
        assertEquals(StatusTone.DANGER, overdue.tone)
        assertTrue(overdue.text.startsWith("Overdue"))
        assertNull(TaskPresentation.deadline(task(TaskStatus.DONE).copy(deadlineType = DeadlineType.DATE_ONLY, deadlineLocalDate = LocalDate.of(2026, 10, 18)), now, CHICAGO))
    }

    @Test fun describeReadsLikeTheHandoffExample() {
        val s = summary(task(), dailyPolicy(t(9), t(18)), occ(at(CHICAGO, 2026, 10, 19, 9, 0)), steps = 0 to 3, nextStep = "Check train times and prices")
        assertEquals("Book train ride, next step Check train times and prices, 09:00 & 18:00 daily, until done, 0 of 3 steps", TaskPresentation.describe(s, now, CHICAGO, CardContext.RAIL))
    }
}
