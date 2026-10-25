package com.lifeadmin.autopilot.domain

import com.lifeadmin.autopilot.domain.capture.CaptureTiming
import com.lifeadmin.autopilot.domain.capture.CaptureTiming.Nudge
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.t
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The offline timing reader behind capture: the milestone's phrases, and what it must not guess. */
class CaptureTimingTest {
    private val zone = CHICAGO
    private val friday2pm = at(zone, 2026, 10, 23, 14) // Fri 23 Oct 2026, 14:00
    private val bedtime = t(21, 30)

    private fun read(text: String, now: java.time.Instant = friday2pm) = CaptureTiming.read(text, now, zone, bedtime)

    // ------------------------------------------------------------ the milestone's tasks

    @Test fun turnOffFanBeforeBedIsOneNudgeTonightAtBedtime() {
        val r = read("Turn off fan before bed")
        val n = r.nudge as Nudge.Once
        assertEquals(at(zone, 2026, 10, 23, 21, 30), n.at)
        assertTrue(n.bedtime)
        assertEquals("before bed", n.phrase)
        assertNull("no deadline from bedtime words", r.deadline)
    }

    @Test fun blanketBeforeBedIsOneNudgeNotARoutine() {
        val n = read("Check daughter's blanket before bed").nudge
        assertTrue("before bed alone is not a confirmed routine", n is Nudge.Once)
    }

    @Test fun blanketEveryNightIsARoutineAtBedtime() {
        val n = read("Check daughter's blanket every night").nudge as Nudge.Daily
        assertEquals(listOf(bedtime), n.times)
        assertTrue(n.bedtime)
        val p = CaptureTiming.policyFor(n, AppSettings(), friday2pm, zone)
        assertEquals(ReminderKind.DAILY_TIMES, p.kind)
        assertTrue(p.routine)
        assertTrue(p.isRoutine)
        assertEquals(listOf(t(21, 30)), p.times)
        assertEquals("bedtime snoozes stay in the evening", CaptureTiming.BEDTIME_SNOOZE_MINUTES, p.snoozeDefaultMinutes)
        assertFalse("the quiet-hours exception is never automatic", p.ringInQuietHours)
    }

    @Test fun workUpdateByMonday5pmIsAnExactDeadlineAndNoNudge() {
        val r = read("Complete work update by Monday 5pm")
        val d = r.deadline!!
        assertEquals(DeadlineType.EXACT, d.spec.type)
        assertEquals(at(zone, 2026, 10, 26, 17), d.spec.instant)
        assertEquals("by monday 5pm", d.phrase)
        assertNull("the deadline's day and time are not reused as a nudge", r.nudge)
    }

    @Test fun giftForSomeoneInfersNothing() {
        val r = read("Buy a gift for someone")
        assertTrue(r.isEmpty)
    }

    // ------------------------------------------------------------ the supported expressions

    @Test fun tonightWithATimeAndABareEveningHour() {
        assertEquals(at(zone, 2026, 10, 23, 22), (read("Call mum tonight at 10pm").nudge as Nudge.Once).at)
        assertEquals("“tonight at 10” reads as 22:00", at(zone, 2026, 10, 23, 22), (read("Call mum tonight at 10").nudge as Nudge.Once).at)
        assertEquals(at(zone, 2026, 10, 23, 19, 30), (read("Take bins out this evening at 7:30").nudge as Nudge.Once).at)
    }

    @Test fun explicitTimesWithoutADay() {
        assertEquals("later today", at(zone, 2026, 10, 23, 17), (read("Ring the garage at 5pm").nudge as Nudge.Once).at)
        assertEquals("already passed today → tomorrow", at(zone, 2026, 10, 24, 9), (read("Ring the garage at 9am").nudge as Nudge.Once).at)
        assertEquals(at(zone, 2026, 10, 23, 19, 30), (read("Water plants 19:30").nudge as Nudge.Once).at)
        assertEquals(at(zone, 2026, 10, 24, 12), (read("Lunch with Sam noon").nudge as Nudge.Once).at)
        assertEquals("a dot as the separator", at(zone, 2026, 10, 23, 19, 30), (read("Water plants 19.30").nudge as Nudge.Once).at)
        assertEquals("p.m. with dots", at(zone, 2026, 10, 27, 18), (read("Gym Tuesday 6 p.m.").nudge as Nudge.Once).at)
    }

    @Test fun tomorrowAndWeekdays() {
        assertEquals(at(zone, 2026, 10, 24, 9), (read("Pay rent tomorrow at 9am").nudge as Nudge.Once).at)
        assertEquals(at(zone, 2026, 10, 27, 18), (read("Gym Tuesday 6pm").nudge as Nudge.Once).at)
        // A day without a time keeps the user's usual nudges, starting that day.
        val fromDay = read("Get car inspected Monday").nudge as Nudge.FromDay
        assertEquals(LocalDate.of(2026, 10, 26), fromDay.date)
        val p = CaptureTiming.policyFor(fromDay, AppSettings(defaultPreset = ReminderPreset.GENTLE), friday2pm, zone)
        assertEquals(LocalDate.of(2026, 10, 26), p.startLocalDate)
        assertEquals(listOf(t(10)), p.times)
        assertEquals(LocalDate.of(2026, 10, 24), (read("Call the bank tomorrow").nudge as Nudge.FromDay).date)
        // Today is Friday: "Friday 5pm" is still ahead → today; "Friday 9am" has passed → next week.
        assertEquals(at(zone, 2026, 10, 23, 17), (read("Send invoice Friday 5pm").nudge as Nudge.Once).at)
        assertEquals(at(zone, 2026, 10, 30, 9), (read("Send invoice Friday 9am").nudge as Nudge.Once).at)
    }

    @Test fun everyDayUsesTheUsualTimesOrTheStatedOne() {
        val n = read("Take vitamins every day").nudge as Nudge.Daily
        assertNull(n.times)
        val p = CaptureTiming.policyFor(n, AppSettings(defaultPreset = ReminderPreset.DAILY), friday2pm, zone)
        assertEquals(listOf(t(9)), p.times)
        assertTrue(p.routine)
        assertEquals(listOf(t(8)), (read("Take vitamins daily at 8am").nudge as Nudge.Daily).times)
        assertEquals("“every night at 10” reads as 22:00", listOf(t(22)), (read("Lock the back door every night at 10").nudge as Nudge.Daily).times)
    }

    @Test fun bedtimeAfterItHasPassedMeansTomorrowsBedtime() {
        val late = at(zone, 2026, 10, 23, 23)
        assertEquals(at(zone, 2026, 10, 24, 21, 30), (read("Turn off fan before bed", late).nudge as Nudge.Once).at)
        assertEquals(at(zone, 2026, 10, 24, 21, 30), (read("Charge phone tomorrow before bed").nudge as Nudge.Once).at)
    }

    @Test fun deadlinesByDayByTimeAndDue() {
        val byFriday = read("Return library books by Friday").deadline!!.spec
        assertEquals(DeadlineType.DATE_ONLY, byFriday.type)
        assertEquals("today is Friday", LocalDate.of(2026, 10, 23), byFriday.date)
        assertEquals(DeadlineType.DATE_ONLY, read("Submit claim due tomorrow").deadline!!.spec.type)
        assertEquals(at(zone, 2026, 10, 23, 17), read("Finish the report by 5pm").deadline!!.spec.instant)
        assertEquals("passed today → tomorrow", at(zone, 2026, 10, 24, 9), read("Finish the report by 9am").deadline!!.spec.instant)
        // A deadline and a separate nudge in one sentence.
        val both = read("Remind me at 7pm to finish the report by Monday 5pm")
        assertEquals(at(zone, 2026, 10, 26, 17), both.deadline!!.spec.instant)
        assertEquals(at(zone, 2026, 10, 23, 19), (both.nudge as Nudge.Once).at)
    }

    // ------------------------------------------------------------ never guessed

    @Test fun ambiguousHoursAreLeftToTheUser() {
        val r = read("Call the dentist at 7")
        assertNull(r.nudge)
        assertTrue(r.notes.single().contains("morning or evening"))
        val r2 = read("Call the dentist at 7:30")
        assertNull("7:30 without am/pm outside an evening is a guess", r2.nudge)
        val dl = read("Pay the bill by Monday 7:30").deadline!!.spec
        assertEquals("an ambiguous deadline time keeps the day only", DeadlineType.DATE_ONLY, dl.type)
    }

    @Test fun vagueAndEventWordingSetsNothing() {
        listOf(
            "Book a haircut soon", "Plan the trip next week", "Buy sunscreen before my trip", "Sort the garage this weekend",
            "Buy sun cream", "Reply when they get back to me", "Stand by me playlist", "Buy a gift for someone",
        ).forEach { text -> assertTrue("“$text” should infer nothing: ${read(text)}", read(text).nudge == null && read(text).deadline == null) }
    }

    @Test fun nextWeekdayIsAmbiguousAndNotGuessed() {
        val r = read("Dinner with Ana next Friday at 7pm")
        assertNull(r.nudge)
        assertTrue(r.notes.single().contains("two different weeks"))
        val dl = read("Pay the deposit by next Friday")
        assertNull(dl.deadline)
        assertTrue(dl.notes.single().contains("two different weeks"))
    }

    @Test fun aDayWithNoTimeTonightOrPassedTimesAreExplained() {
        val tonight = read("Pack the bag tonight")
        assertNull(tonight.nudge)
        assertTrue(tonight.notes.single().contains("No time given"))
        val passed = read("Call the bank today at 9am")
        assertNull(passed.nudge)
        assertTrue(passed.notes.single().contains("already passed"))
        val pastDeadline = read("Send it by today 9am")
        assertNull(pastDeadline.deadline)
    }

    @Test fun theTextIsOnlyRead() {
        val text = "Check daughter's blanket before bed"
        val copy = String(text.toCharArray())
        read(text)
        assertEquals(copy, text)
    }
}
