package com.lifeadmin.autopilot.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class WaitingCheckInOptionsTest {
    private val zone = ZoneId.of("Europe/London")

    @Test fun datedChoicesAreDistinctOnEveryWeekday() {
        val monday = LocalDate.of(2026, 10, 19).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        for (offset in 0L..6L) {
            val today = monday.plusDays(offset)
            val dated = waitingCheckInOptions(today, zone).mapNotNull { it.second }
            assertEquals("duplicate check-in instants on ${today.dayOfWeek}", dated.size, dated.toSet().size)
        }
    }

    @Test fun nextWeekIsSevenDaysLaterAt0900() {
        // Monday 19 Oct 2026: the old "next Tuesday" rule gave tomorrow, the same as "Tomorrow 09:00".
        val today = LocalDate.of(2026, 10, 19)
        val options = waitingCheckInOptions(today, zone)
        assertNull(options[0].second)
        val (label, at) = options[3]
        assertEquals(today.plusDays(7).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant(), at)
        assertEquals("Next week (Mon 26 Oct) 09:00", label)
    }
}
