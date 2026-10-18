package com.lifeadmin.autopilot.domain

import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskEvent
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.InMemoryHintDismissals
import com.lifeadmin.autopilot.domain.usecase.StallAction
import com.lifeadmin.autopilot.domain.usecase.StallDetector
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class StallDetectorTest {
    private val now = at(CHICAGO, 2026, 10, 14, 10, 0)
    private val created = now.minus(Duration.ofDays(20))

    private fun task(status: TaskStatus = TaskStatus.ACTIONABLE, deadline: LocalDate? = null, checkIn: Instant? = null) = Task(
        id = "t", title = "Book train", status = status, createdAt = created, updatedAt = created,
        deadlineType = if (deadline != null) DeadlineType.DATE_ONLY else DeadlineType.NONE, deadlineLocalDate = deadline, waitingCheckInAt = checkIn,
    )
    private fun ev(type: EventType, daysAgo: Long, i: Int = 0) = TaskEvent("e$type$daysAgo$i", "t", now.minus(Duration.ofDays(daysAgo)).plusSeconds(i.toLong()), type, "")
    private fun details(t: Task, events: List<TaskEvent>) = TaskDetails(t, emptyList(), null, null, emptyList(), events + ev(EventType.CREATED, 20))

    @Test fun noHintForAHealthyTask() {
        assertNull(StallDetector.detect(details(task(), listOf(ev(EventType.SNOOZED, 3))), now, CHICAGO))
    }

    @Test fun repeatedSnoozesSuggestGentleOptions() {
        val h = StallDetector.detect(details(task(), (1..4).map { ev(EventType.SNOOZED, it.toLong()) }), now, CHICAGO)!!
        assertTrue(h.message.contains("4 times"))
        assertEquals(listOf(StallAction.BREAK_DOWN, StallAction.MARK_WAITING, StallAction.PAUSE), h.actions)
    }

    @Test fun progressResetsTheSnoozeSignal() {
        val events = (5..8).map { ev(EventType.SNOOZED, it.toLong()) } + ev(EventType.STEP_COMPLETED, 2)
        assertNull(StallDetector.detect(details(task(), events), now, CHICAGO))
    }

    @Test fun manyRemindersWithoutProgress() {
        val events = (0 until 7).map { ev(EventType.REMINDER_DELIVERED, 3, it) }
        val h = StallDetector.detect(details(task(), events), now, CHICAGO)!!
        assertTrue(h.message.contains("reminded 7 times"))
    }

    @Test fun overdueDeadlineSuggestsMovingTheDate() {
        val h = StallDetector.detect(details(task(deadline = LocalDate.of(2026, 10, 9)), emptyList()), now, CHICAGO)!!
        assertEquals(StallAction.MOVE_DATE, h.actions.first())
    }

    @Test fun waitingPastCheckIn() {
        val h = StallDetector.detect(details(task(TaskStatus.WAITING, checkIn = now.minus(Duration.ofDays(5))), emptyList()), now, CHICAGO)!!
        assertEquals(listOf(StallAction.CHECK_IN, StallAction.PAUSE), h.actions)
    }

    @Test fun pausedDoneAndCanceledTasksAreLeftAlone() {
        val snoozes = (1..5).map { ev(EventType.SNOOZED, it.toLong()) }
        listOf(TaskStatus.PAUSED, TaskStatus.DONE, TaskStatus.CANCELED).forEach { s ->
            assertNull(StallDetector.detect(details(task(s), snoozes), now, CHICAGO))
        }
    }

    @Test fun dismissedHintReturnsOnlyWhenTheSignalGrows() {
        val d = InMemoryHintDismissals()
        val h3 = StallDetector.detect(details(task(), (1..3).map { ev(EventType.SNOOZED, it.toLong()) }), now, CHICAGO)!!
        d.dismiss("t", h3.signature)
        assertTrue(d.isDismissed("t", h3.signature))
        val h4 = StallDetector.detect(details(task(), (1..4).map { ev(EventType.SNOOZED, it.toLong()) }), now, CHICAGO)!!
        assertFalse(d.isDismissed("t", h4.signature))
    }
}
