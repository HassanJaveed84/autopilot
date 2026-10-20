package com.lifeadmin.autopilot.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** The detail screen's deadline row: a date with its distance, without saying "yesterday" twice. */
class FormatDateTest {
    private val today = LocalDate.of(2026, 10, 20) // a Tuesday

    @Test fun nearDatesAreAlreadyRelativeSoNoDistanceIsAdded() {
        assertEquals("Yesterday", Format.dateWithDistance(today.minusDays(1), today))
        assertEquals("Today", Format.dateWithDistance(today, today))
        assertEquals("Tomorrow", Format.dateWithDistance(today.plusDays(1), today))
    }

    @Test fun fartherDatesShowTheDayAndTheDistance() {
        assertEquals("Fri 30 Oct · in 10 days", Format.dateWithDistance(today.plusDays(10), today))
        assertEquals("Sat 17 Oct · 3 days ago", Format.dateWithDistance(today.minusDays(3), today))
    }
}
