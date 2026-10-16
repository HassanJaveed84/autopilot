package com.lifeadmin.autopilot.domain

import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.QuietHours
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import com.lifeadmin.autopilot.domain.scheduling.ReminderPlanner
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.KOLKATA
import com.lifeadmin.autopilot.testing.NEW_YORK
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.dailyPolicy
import com.lifeadmin.autopilot.testing.t
import com.lifeadmin.autopilot.testing.task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class ReminderPlannerTest {
    private val noQuiet = AppSettings(quietHours = QuietHours(enabled = false))
    private val defaults = AppSettings() // quiet 21:00–08:00, defer & coalesce
    private val train = dailyPolicy(t(9), t(18))

    private fun next(
        now: Instant,
        status: TaskStatus = TaskStatus.ACTIONABLE,
        policy: com.lifeadmin.autopilot.domain.model.ReminderPolicy? = train,
        settings: AppSettings = noQuiet,
        zone: java.time.ZoneId = CHICAGO,
        snoozedUntil: Instant? = null,
        remindAtEnd: Boolean = true,
        checkIn: Instant? = null,
        settled: Set<Instant> = emptySet(),
    ) = ReminderPlanner.next(
        task(status).copy(snoozedUntil = snoozedUntil, snoozeRemindAtEnd = remindAtEnd, waitingCheckInAt = checkIn),
        policy, settings, now, zone, isSettled = { it in settled },
    )

    // --- canonical case: 09:00 and 18:00 daily until done

    @Test fun twiceDailyPicksNextUpcomingSlot() {
        assertEquals(at(CHICAGO, 2026, 10, 16, 9), next(at(CHICAGO, 2026, 10, 16, 7))!!.plannedAt)
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), next(at(CHICAGO, 2026, 10, 16, 9, 1))!!.plannedAt)
        assertEquals(at(CHICAGO, 2026, 10, 17, 9), next(at(CHICAGO, 2026, 10, 16, 18, 0))!!.plannedAt)
        assertEquals(at(CHICAGO, 2026, 10, 17, 9), next(at(CHICAGO, 2026, 10, 16, 23, 30))!!.plannedAt)
    }

    @Test fun twiceDailySimulatedWeekKeepsAlternating() {
        var now = at(CHICAGO, 2026, 10, 16, 8)
        val seen = mutableListOf<Instant>()
        repeat(14) {
            val n = next(now)!!
            assertEquals(OccurrenceKind.POLICY, n.kind)
            seen += n.plannedAt
            now = n.plannedAt.plusSeconds(30) // delivered slightly late
        }
        val expected = (0 until 7).flatMap { d -> listOf(at(CHICAGO, 2026, 10, 16 + d, 9), at(CHICAGO, 2026, 10, 16 + d, 18)) }
        assertEquals(expected, seen)
    }

    @Test fun slotExactlyAtNowIsNotRepeated() {
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), next(at(CHICAGO, 2026, 10, 16, 9))!!.plannedAt)
    }

    @Test fun settledInstantIsNeverPlannedAgainAfterClockMovesBack() {
        val nineAm = at(CHICAGO, 2026, 10, 16, 9)
        // Clock set back to 08:50 after 09:00 was delivered: skip to 18:00.
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), next(at(CHICAGO, 2026, 10, 16, 8, 50), settled = setOf(nineAm))!!.plannedAt)
    }

    // --- status gating

    @Test fun waitingPausedDoneCanceledSuppressPolicyReminders() {
        val now = at(CHICAGO, 2026, 10, 16, 7)
        listOf(TaskStatus.WAITING, TaskStatus.PAUSED, TaskStatus.DONE, TaskStatus.CANCELED).forEach {
            assertNull("$it", next(now, status = it))
        }
    }

    @Test fun inboxWithExplicitPolicyStillReminds() {
        assertEquals(at(CHICAGO, 2026, 10, 16, 9), next(at(CHICAGO, 2026, 10, 16, 7), status = TaskStatus.INBOX)!!.plannedAt)
    }

    @Test fun disabledPolicyProducesNothing() {
        assertNull(next(at(CHICAGO, 2026, 10, 16, 7), policy = train.copy(enabled = false)))
        assertNull(next(at(CHICAGO, 2026, 10, 16, 7), policy = null))
    }

    @Test fun waitingUsesOnlyExplicitCheckIn() {
        val checkIn = at(CHICAGO, 2026, 10, 20, 10)
        val n = next(at(CHICAGO, 2026, 10, 16, 7), status = TaskStatus.WAITING, checkIn = checkIn)!!
        assertEquals(OccurrenceKind.CHECK_IN, n.kind)
        assertEquals(checkIn, n.plannedAt)
        assertNull(next(at(CHICAGO, 2026, 10, 16, 7), status = TaskStatus.WAITING, checkIn = checkIn, settled = setOf(checkIn)))
    }

    // --- once

    @Test fun onceFiresOnlyOnce() {
        val at = at(CHICAGO, 2026, 10, 17, 9)
        val once = train.copy(kind = ReminderKind.ONCE, atInstant = at, times = emptyList())
        assertEquals(at, next(at(CHICAGO, 2026, 10, 16, 12), policy = once)!!.plannedAt)
        assertNull(next(at.plusSeconds(5), policy = once))
        assertNull(next(at(CHICAGO, 2026, 10, 16, 12), policy = once, settled = setOf(at)))
    }

    // --- every N days anchored to start date

    @Test fun everyThreeDaysAnchoredToStartDate() {
        val p = train.copy(kind = ReminderKind.EVERY_N_DAYS, dayInterval = 3, times = listOf(t(10)), startLocalDate = LocalDate.of(2026, 10, 1))
        // Anchor 10-01 → 10-04, 10-07, 10-10, 10-13, 10-16, 10-19 …
        assertEquals(at(CHICAGO, 2026, 10, 16, 10), next(at(CHICAGO, 2026, 10, 15, 12), policy = p)!!.plannedAt)
        assertEquals(at(CHICAGO, 2026, 10, 19, 10), next(at(CHICAGO, 2026, 10, 16, 10, 0), policy = p)!!.plannedAt)
        // Late delivery (2 days late) does not shift the cadence.
        assertEquals(at(CHICAGO, 2026, 10, 22, 10), next(at(CHICAGO, 2026, 10, 21, 10), policy = p)!!.plannedAt)
    }

    @Test fun startDateInFutureIsRespected() {
        val p = train.copy(startLocalDate = LocalDate.of(2026, 10, 20))
        assertEquals(at(CHICAGO, 2026, 10, 20, 9), next(at(CHICAGO, 2026, 10, 16, 7), policy = p)!!.plannedAt)
    }

    // --- DST and time zones

    @Test fun dstSpringForwardSlotInGapResolvesOncePerDay() {
        val p = dailyPolicy(t(2, 30))
        val n = next(at(NEW_YORK, 2026, 3, 7, 12), policy = p, zone = NEW_YORK)!!
        assertEquals(at(NEW_YORK, 2026, 3, 8, 3, 0), n.plannedAt)
        val after = next(n.plannedAt.plusSeconds(1), policy = p, zone = NEW_YORK)!!
        assertEquals(at(NEW_YORK, 2026, 3, 9, 2, 30), after.plannedAt)
    }

    @Test fun dstFallBackRepeatedSlotFiresOnceAtEarlierOffset() {
        val p = dailyPolicy(t(1, 30))
        val first = next(at(NEW_YORK, 2026, 10, 31, 12), policy = p, zone = NEW_YORK)!!
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first.plannedAt)
        // One hour later the wall clock reads 01:30 again (EST) — must NOT fire a second time.
        val second = next(first.plannedAt.plusSeconds(60), policy = p, zone = NEW_YORK)!!
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), second.plannedAt) // next day 01:30 EST
    }

    @Test fun nineAndSixKeepWallClockAcrossDstChange() {
        val beforeFallBack = next(at(NEW_YORK, 2026, 10, 31, 19), zone = NEW_YORK)!!
        assertEquals(t(9), beforeFallBack.plannedAt.atZone(NEW_YORK).toLocalTime())
        // 18:00 → 09:00 is 15 wall-clock hours, but 16 real hours because 01:00–02:00 repeats.
        assertEquals(Duration.ofHours(16), Duration.between(at(NEW_YORK, 2026, 10, 31, 18), beforeFallBack.plannedAt))
    }

    @Test fun halfHourOffsetZone() {
        assertEquals(at(KOLKATA, 2026, 10, 16, 18), next(at(KOLKATA, 2026, 10, 16, 12), zone = KOLKATA)!!.plannedAt)
    }

    @Test fun deviceTimeZoneChangeRecomputesWallClock() {
        val now = Instant.parse("2026-10-16T12:00:00Z")
        val chicago = next(now, zone = CHICAGO)!!.plannedAt // 07:00 CDT now → 09:00 CDT
        val kolkata = next(now, zone = KOLKATA)!!.plannedAt // 17:30 IST now → 18:00 IST
        assertEquals(at(CHICAGO, 2026, 10, 16, 9), chicago)
        assertEquals(at(KOLKATA, 2026, 10, 16, 18), kolkata)
    }

    @Test fun fixedZoneIgnoresDeviceZone() {
        val p = train.copy(zoneMode = ZoneMode.FIXED_ZONE, zoneId = "Asia/Kolkata")
        val now = Instant.parse("2026-10-16T12:00:00Z")
        assertEquals(at(KOLKATA, 2026, 10, 16, 18), next(now, policy = p, zone = CHICAGO)!!.plannedAt)
    }

    // --- quiet hours

    @Test fun quietHoursDeferToMorning() {
        val p = dailyPolicy(t(22))
        val n = next(at(CHICAGO, 2026, 10, 16, 12), policy = p, settings = defaults)!!
        assertEquals(at(CHICAGO, 2026, 10, 16, 22), n.slotAt)
        assertEquals(at(CHICAGO, 2026, 10, 17, 8), n.plannedAt)
        assertTrue(n.deferred)
    }

    @Test fun quietHoursCoalesceSeveralSuppressedSlotsIntoOneMorningReminder() {
        val p = dailyPolicy(t(21, 30), t(23), t(8))
        val first = next(at(CHICAGO, 2026, 10, 16, 20), policy = p, settings = defaults)!!
        assertEquals(at(CHICAGO, 2026, 10, 17, 8), first.plannedAt)
        // After 08:00 is delivered, the deferred 23:00 and the real 08:00 slot do not fire again.
        val second = next(first.plannedAt.plusMillis(200), policy = p, settings = defaults, settled = setOf(first.plannedAt))!!
        // Next is 21:30 deferred to the following morning.
        assertEquals(at(CHICAGO, 2026, 10, 18, 8), second.plannedAt)
    }

    @Test fun quietHoursSkipBehaviorDropsSlot() {
        val p = dailyPolicy(t(22), t(12))
        val n = next(at(CHICAGO, 2026, 10, 16, 13), policy = p, settings = defaults.copy(quietHoursBehavior = QuietHoursBehavior.SKIP))!!
        assertEquals(at(CHICAGO, 2026, 10, 17, 12), n.plannedAt)
    }

    @Test fun perPolicyQuietOverrideWins() {
        val p = dailyPolicy(t(22), t(12)).copy(quietHoursBehavior = QuietHoursBehavior.SKIP)
        assertEquals(at(CHICAGO, 2026, 10, 17, 12), next(at(CHICAGO, 2026, 10, 16, 13), policy = p, settings = defaults)!!.plannedAt)
    }

    @Test fun skipWithAllSlotsQuietTerminates() {
        val p = dailyPolicy(t(23))
        assertNull(next(at(CHICAGO, 2026, 10, 16, 13), policy = p, settings = defaults.copy(quietHoursBehavior = QuietHoursBehavior.SKIP)))
    }

    // --- snooze

    @Test fun snoozeSuppressesInterveningSlotsAndRemindsAtEnd() {
        val until = at(CHICAGO, 2026, 10, 17, 9, 30)
        val n = next(at(CHICAGO, 2026, 10, 16, 8), snoozedUntil = until)!!
        assertEquals(OccurrenceKind.SNOOZE_END, n.kind)
        assertEquals(until, n.plannedAt)
        // After the snooze reminder, the policy resumes at 18:00.
        assertEquals(at(CHICAGO, 2026, 10, 17, 18), next(until.plusSeconds(1), snoozedUntil = until, settled = setOf(until))!!.plannedAt)
    }

    @Test fun snoozeWithoutEndReminderResumesPolicyAtOrAfterSnoozeEnd() {
        val until = at(CHICAGO, 2026, 10, 17, 9, 0)
        val n = next(at(CHICAGO, 2026, 10, 16, 8), snoozedUntil = until, remindAtEnd = false)!!
        assertEquals(OccurrenceKind.POLICY, n.kind)
        assertEquals(until, n.plannedAt) // 09:00 slot coincides with snooze end
    }

    @Test fun snoozeEndCoincidingWithSlotCoalescesIntoPolicyOccurrence() {
        val until = at(CHICAGO, 2026, 10, 17, 9, 0)
        val n = next(at(CHICAGO, 2026, 10, 16, 8), snoozedUntil = until)!!
        assertEquals(OccurrenceKind.POLICY, n.kind)
        assertEquals(until, n.plannedAt)
    }

    @Test fun expiredSnoozeIsIgnored() {
        assertEquals(at(CHICAGO, 2026, 10, 16, 9), next(at(CHICAGO, 2026, 10, 16, 8), snoozedUntil = at(CHICAGO, 2026, 10, 16, 7))!!.plannedAt)
    }

    @Test fun snoozeEndDuringQuietHoursIsDeferred() {
        val until = at(CHICAGO, 2026, 10, 16, 23)
        val n = next(at(CHICAGO, 2026, 10, 16, 20), snoozedUntil = until, settings = defaults)!!
        assertEquals(at(CHICAGO, 2026, 10, 17, 8), n.plannedAt)
    }

    @Test fun repeatedRemindersNeverChangeTaskIdentity() {
        // The planner only returns occurrences; there is no API that creates tasks.
        val n = next(at(CHICAGO, 2026, 10, 16, 7))!!
        assertEquals(OccurrenceKind.POLICY, n.kind)
    }
}
