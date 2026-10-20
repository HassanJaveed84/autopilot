package com.lifeadmin.autopilot.ui

import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.QuietHours
import com.lifeadmin.autopilot.domain.model.ReminderOccurrence
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.dailyPolicy
import com.lifeadmin.autopilot.testing.t
import com.lifeadmin.autopilot.ui.today.RailEntry
import com.lifeadmin.autopilot.ui.today.TodayPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Today rail rules: real next occurrences, one card per task with ghost repeats, attention rows
 * for deadlines and check-ins without nudges, snoozed/paused/waiting kept out of "needs you now".
 */
class TodayPlannerTest {
    private val now = at(CHICAGO, 2026, 10, 19, 8, 42)
    private val settings = AppSettings(quietHours = QuietHours(enabled = true, start = t(22), end = t(7)))
    private val created = Instant.parse("2026-10-01T00:00:00Z")

    private fun task(id: String, title: String, status: TaskStatus = TaskStatus.ACTIONABLE) = Task(id, title, status = status, createdAt = created, updatedAt = created)
    private fun policy(id: String, vararg times: java.time.LocalTime) = dailyPolicy(*times).copy(id = "p-$id", taskId = id)
    private fun occ(taskId: String, at: Instant, kind: OccurrenceKind = OccurrenceKind.POLICY) =
        ReminderOccurrence("o-$taskId", "p-$taskId", taskId, kind, at, at, now, null, OccurrenceStatus.SCHEDULED, 1)
    private fun summary(task: Task, policy: ReminderPolicy? = null, next: ReminderOccurrence? = null) = TaskSummary(task, 0, 0, null, next, policy)

    @Test fun firstNudgeIsACardAndLaterOnesAreGhosts() {
        val train = task("train", "Book train ride")
        val plan = TodayPlanner.plan(listOf(summary(train, policy("train", t(9), t(18)), occ("train", at(CHICAGO, 2026, 10, 19, 9, 0)))), settings, now, CHICAGO)
        val rail = plan.rail
        assertTrue(rail[0] is RailEntry.Now)
        val card = rail[1] as RailEntry.Card
        assertEquals("09:00", card.time)
        val ghost = rail[2] as RailEntry.Ghost
        assertEquals("18:00", ghost.time)
        assertEquals(3, rail.size)
        assertEquals(1, plan.tasksToday)
        assertEquals("One thing to move today.", plan.headline)
        assertEquals("Nudges at 09:00 and 18:00. Each one repeats until it's done.", plan.sub)
        assertEquals(1, rail.count { it is RailEntry.Card })
    }

    @Test fun deadlinesWithoutRemindersStillNeedAttention() {
        val overdue = task("tax", "Pay the council tax").copy(deadlineType = DeadlineType.DATE_ONLY, deadlineLocalDate = LocalDate.of(2026, 10, 17))
        val dueToday = task("form", "Return the school form").copy(deadlineType = DeadlineType.EXACT, deadlineInstant = at(CHICAGO, 2026, 10, 19, 17, 0))
        val plan = TodayPlanner.plan(listOf(summary(overdue), summary(dueToday)), settings, now, CHICAGO)
        val attention = plan.rail.filterIsInstance<RailEntry.Attention>()
        assertEquals(listOf("Overdue", "Due 17:00"), attention.map { it.label })
        assertEquals("Two things to move today.", plan.headline)
        assertEquals("2 need you now.", plan.sub)
    }

    @Test fun snoozedPausedAndWaitingAreNotPresentedAsNeedingActionNow() {
        val snoozed = task("dentist", "Book dentist").copy(snoozedUntil = at(CHICAGO, 2026, 10, 20, 9, 0))
        val paused = task("garage", "Sort the garage", TaskStatus.PAUSED)
        val waiting = task("lease", "Sign the lease", TaskStatus.WAITING).copy(waitingReason = "Greenway Lettings", waitingCheckInAt = at(CHICAGO, 2026, 10, 21, 8, 0))
        val plan = TodayPlanner.plan(
            listOf(summary(snoozed, policy("dentist", t(10))), summary(paused, policy("garage", t(10))), summary(waiting)),
            settings, now, CHICAGO,
        )
        assertTrue(plan.rail.isEmpty())
        assertEquals(0, plan.tasksToday)
        assertEquals(listOf("dentist", "garage"), plan.later.map { it.task.id })
        assertEquals(listOf("lease"), plan.waiting.map { it.task.id })
        assertEquals("Nothing nudges you today.", plan.headline)
    }

    @Test fun snoozeEndingTodayAndCheckInDueTodayAppearOnTheRailWithoutDuplicates() {
        val snoozed = task("dentist", "Book dentist").copy(snoozedUntil = at(CHICAGO, 2026, 10, 19, 11, 0))
        val waiting = task("lease", "Sign the lease", TaskStatus.WAITING).copy(waitingCheckInAt = at(CHICAGO, 2026, 10, 19, 14, 0))
        val plan = TodayPlanner.plan(listOf(summary(snoozed, policy("dentist", t(10), t(18))), summary(waiting)), settings, now, CHICAGO)
        val entries = plan.rail.drop(1)
        assertTrue(entries[0] is RailEntry.Card && (entries[0] as RailEntry.Card).kind == OccurrenceKind.SNOOZE_END && (entries[0] as RailEntry.Card).time == "11:00")
        assertTrue(entries[1] is RailEntry.CheckIn && (entries[1] as RailEntry.CheckIn).time == "14:00")
        assertTrue(entries[2] is RailEntry.Ghost && (entries[2] as RailEntry.Ghost).time == "18:00")
        assertEquals(listOf("lease"), plan.waiting.map { it.task.id })
        assertEquals(1, plan.rail.count { it is RailEntry.Card })
    }

    @Test fun inboxNotesDoneTodayAndEmptyState() {
        val note = task("bank", "Call the bank", TaskStatus.INBOX)
        val done = task("fee", "Dispute the fee", TaskStatus.DONE).copy(completedAt = at(CHICAGO, 2026, 10, 19, 8, 0))
        val plan = TodayPlanner.plan(listOf(summary(note), summary(done)), settings, now, CHICAGO)
        assertEquals(listOf("bank"), plan.inbox.map { it.task.id })
        assertEquals(listOf("fee"), plan.doneToday.map { it.task.id })
        assertTrue(plan.rail.isEmpty())
        assertEquals("Enjoy the quiet.", plan.sub)
    }
}
