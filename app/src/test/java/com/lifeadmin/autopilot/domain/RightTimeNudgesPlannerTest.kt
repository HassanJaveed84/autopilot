package com.lifeadmin.autopilot.domain

import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.scheduling.ReminderPlanner
import com.lifeadmin.autopilot.domain.scheduling.ReminderProjection
import com.lifeadmin.autopilot.domain.scheduling.RoutineCycle
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.dailyPolicy
import com.lifeadmin.autopilot.testing.t
import com.lifeadmin.autopilot.testing.task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bedtime nudges with the per-task quiet-hours exception, and "Done for tonight" on routines. */
class RightTimeNudgesPlannerTest {
    private val zone = CHICAGO
    private val settings = AppSettings() // quiet hours 21:00–08:00, defer and coalesce
    private val evening = at(zone, 2026, 10, 23, 20) // Fri 20:00
    private val bedtimeRoutine = dailyPolicy(t(21, 30)).copy(routine = true, snoozeDefaultMinutes = 15)

    @Test fun withoutTheExceptionABedtimeNudgeWaitsForMorning() {
        val next = ReminderPlanner.next(task(), bedtimeRoutine, settings, evening, zone)!!
        assertEquals(at(zone, 2026, 10, 24, 8), next.plannedAt)
        assertTrue(next.deferred)
    }

    @Test fun theExceptionRingsAtBedtimeForThisTaskOnly() {
        val ringing = bedtimeRoutine.copy(ringInQuietHours = true)
        val next = ReminderPlanner.next(task(), ringing, settings, evening, zone)!!
        assertEquals(at(zone, 2026, 10, 23, 21, 30), next.plannedAt)
        assertFalse(next.deferred)
        // Another task at the same time without the exception still waits.
        assertEquals(at(zone, 2026, 10, 24, 8), ReminderPlanner.next(task(), bedtimeRoutine, settings, evening, zone)!!.plannedAt)
        // It also beats "skip" for this task, and does nothing once the nudges are off.
        assertEquals(at(zone, 2026, 10, 23, 21, 30), ReminderPlanner.next(task(), ringing.copy(quietHoursBehavior = QuietHoursBehavior.SKIP), settings, evening, zone)!!.plannedAt)
        assertNull(ReminderPlanner.next(task(), ringing.copy(enabled = false), settings, evening, zone))
    }

    @Test fun aSnoozedBedtimeNudgeStaysInTheEveningWithTheException() {
        val ringing = bedtimeRoutine.copy(ringInQuietHours = true)
        val at2130 = at(zone, 2026, 10, 23, 21, 30)
        val snoozed = task().copy(snoozedUntil = at2130.plusSeconds(15 * 60), snoozeRemindAtEnd = true)
        val next = ReminderPlanner.next(snoozed, ringing, settings, at2130.plusSeconds(1), zone)!!
        assertEquals(OccurrenceKind.SNOOZE_END, next.kind)
        assertEquals(at(zone, 2026, 10, 23, 21, 45), next.plannedAt)
        // Without the exception the same snooze end waits until 08:00.
        assertEquals(at(zone, 2026, 10, 24, 8), ReminderPlanner.next(snoozed, bedtimeRoutine, settings, at2130.plusSeconds(1), zone)!!.plannedAt)
    }

    @Test fun doneForTonightSkipsTonightAndKeepsTomorrow() {
        val ringing = bedtimeRoutine.copy(ringInQuietHours = true)
        val until = RoutineCycle.cycleEnd(evening, zone)
        assertEquals(at(zone, 2026, 10, 24, 5), until)
        val done = task().copy(cycleDoneUntil = until)
        val upcoming = ReminderProjection.upcoming(done, ringing, settings, evening, zone, 3)
        assertEquals(listOf(at(zone, 2026, 10, 24, 21, 30), at(zone, 2026, 10, 25, 21, 30), at(zone, 2026, 10, 26, 21, 30)), upcoming.map { it.plannedAt })
        assertEquals(TaskStatus.ACTIONABLE, done.status)
    }

    @Test fun aFinishedCycleOnlyAppliesToRoutines() {
        val notRoutine = dailyPolicy(t(18)) // "until done" nudges
        val done = task().copy(cycleDoneUntil = RoutineCycle.cycleEnd(evening, zone))
        val afternoon = at(zone, 2026, 10, 23, 14)
        assertEquals(at(zone, 2026, 10, 23, 18), ReminderPlanner.next(done, notRoutine, settings, afternoon, zone)!!.plannedAt)
        assertFalse(RoutineCycle.canFinishCycle(task(), notRoutine))
        assertFalse("a one-off nudge is never a routine", RoutineCycle.canFinishCycle(task(), notRoutine.copy(kind = ReminderKind.ONCE, routine = true)))
        assertFalse(RoutineCycle.canFinishCycle(task(TaskStatus.WAITING), bedtimeRoutine))
        assertTrue(RoutineCycle.canFinishCycle(task(), bedtimeRoutine))
    }

    @Test fun aMorningAndEveningRoutineDoneAtTenIsQuietForTheRestOfTheDay() {
        val twice = dailyPolicy(t(9), t(18)).copy(routine = true)
        val ten = at(zone, 2026, 10, 23, 10)
        val done = task().copy(cycleDoneUntil = RoutineCycle.cycleEnd(ten, zone))
        assertEquals(at(zone, 2026, 10, 24, 9), ReminderPlanner.next(done, twice, settings, ten, zone)!!.plannedAt)
        assertEquals("Done for today", RoutineCycle.label(ten, zone))
        assertEquals("Done for tonight", RoutineCycle.label(evening, zone))
    }

    @Test fun answeringAfterMidnightStillBelongsToLastNight() {
        val lateRoutine = dailyPolicy(t(23, 30)).copy(routine = true, ringInQuietHours = true)
        val halfPastMidnight = at(zone, 2026, 10, 24, 0, 30)
        val until = RoutineCycle.cycleEnd(halfPastMidnight, zone)
        assertEquals(at(zone, 2026, 10, 24, 5), until)
        val next = ReminderPlanner.next(task().copy(cycleDoneUntil = until), lateRoutine, settings, halfPastMidnight, zone)!!
        assertEquals("tonight's 23:30 still rings", at(zone, 2026, 10, 24, 23, 30), next.plannedAt)
    }

    @Test fun cycleEndIsAValidInstantAcrossDst() {
        // US fall-back night: 1 Nov 2026 02:00 → 01:00. 05:00 exists once either side.
        val saturdayNight = at(zone, 2026, 10, 31, 22)
        assertEquals(at(zone, 2026, 11, 1, 5), RoutineCycle.cycleEnd(saturdayNight, zone))
        val springSaturday = at(zone, 2027, 3, 13, 22)
        assertEquals(at(zone, 2027, 3, 14, 5), RoutineCycle.cycleEnd(springSaturday, zone))
    }
}
