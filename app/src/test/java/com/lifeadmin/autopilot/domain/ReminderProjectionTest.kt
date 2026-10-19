package com.lifeadmin.autopilot.domain

import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.QuietHours
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.scheduling.ReminderPlanner
import com.lifeadmin.autopilot.domain.scheduling.ReminderProjection
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.dailyPolicy
import com.lifeadmin.autopilot.testing.t
import com.lifeadmin.autopilot.testing.task
import org.junit.Assert.assertEquals
import org.junit.Test

/** The next-N projection iterates the engine's own planner, so previews can't disagree with alarms. */
class ReminderProjectionTest {
    private val now = at(CHICAGO, 2026, 10, 19, 8, 42)
    private val settings = AppSettings(quietHours = QuietHours(enabled = true, start = t(22), end = t(7)))

    @Test fun projectsTheNextThreeDailyNudges() {
        val next = ReminderProjection.upcoming(task(), dailyPolicy(t(9), t(18)), settings, now, CHICAGO, 3)
        assertEquals(
            listOf(at(CHICAGO, 2026, 10, 19, 9, 0), at(CHICAGO, 2026, 10, 19, 18, 0), at(CHICAGO, 2026, 10, 20, 9, 0)),
            next.map { it.plannedAt },
        )
        assertEquals(next.first(), ReminderPlanner.next(task(), dailyPolicy(t(9), t(18)), settings, now, CHICAGO))
    }

    @Test fun quietHoursCoalesceLikeTheEngine() {
        // 23:00 and 06:00 both fall in quiet hours and defer to 07:00 — projected once, not twice.
        val next = ReminderProjection.upcoming(task(), dailyPolicy(t(23), t(6)), settings, now, CHICAGO, 3)
        assertEquals(
            listOf(at(CHICAGO, 2026, 10, 20, 7, 0), at(CHICAGO, 2026, 10, 21, 7, 0), at(CHICAGO, 2026, 10, 22, 7, 0)),
            next.map { it.plannedAt },
        )
        assertEquals(listOf(true, true, true), next.map { it.deferred })
    }

    @Test fun onceFiresOnceAndHorizonCutsOff() {
        val once = dailyPolicy().copy(kind = ReminderKind.ONCE, atInstant = at(CHICAGO, 2026, 10, 19, 10, 0), times = emptyList())
        assertEquals(1, ReminderProjection.upcoming(task(), once, settings, now, CHICAGO, 5).size)
        val endOfDay = at(CHICAGO, 2026, 10, 20, 0, 0).minusNanos(1)
        val today = ReminderProjection.upcoming(task(), dailyPolicy(t(9), t(18)), settings, now, CHICAGO, 8, horizon = endOfDay)
        assertEquals(2, today.size)
    }

    @Test fun snoozeEndComesFirstThenThePolicyResumes() {
        val snoozed = task().copy(snoozedUntil = at(CHICAGO, 2026, 10, 19, 12, 0), snoozeRemindAtEnd = true)
        val next = ReminderProjection.upcoming(snoozed, dailyPolicy(t(9), t(18)), settings, now, CHICAGO, 3)
        assertEquals(listOf(OccurrenceKind.SNOOZE_END, OccurrenceKind.POLICY, OccurrenceKind.POLICY), next.map { it.kind })
        assertEquals(listOf(at(CHICAGO, 2026, 10, 19, 12, 0), at(CHICAGO, 2026, 10, 19, 18, 0), at(CHICAGO, 2026, 10, 20, 9, 0)), next.map { it.plannedAt })
    }
}
