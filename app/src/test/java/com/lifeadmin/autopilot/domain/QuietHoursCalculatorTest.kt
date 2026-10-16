package com.lifeadmin.autopilot.domain

import com.lifeadmin.autopilot.domain.model.QuietHours
import com.lifeadmin.autopilot.domain.scheduling.QuietHoursCalculator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.t
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursCalculatorTest {
    private val overnight = QuietHoursCalculator(QuietHours(enabled = true, start = t(21), end = t(8)))

    @Test fun windowCrossingMidnight() {
        assertTrue(overnight.isQuiet(at(CHICAGO, 2026, 10, 16, 21, 0), CHICAGO))
        assertTrue(overnight.isQuiet(at(CHICAGO, 2026, 10, 16, 23, 59), CHICAGO))
        assertTrue(overnight.isQuiet(at(CHICAGO, 2026, 10, 17, 0, 30), CHICAGO))
        assertTrue(overnight.isQuiet(at(CHICAGO, 2026, 10, 17, 7, 59), CHICAGO))
        assertFalse(overnight.isQuiet(at(CHICAGO, 2026, 10, 17, 8, 0), CHICAGO))
        assertFalse(overnight.isQuiet(at(CHICAGO, 2026, 10, 16, 20, 59), CHICAGO))
    }

    @Test fun eveningQuietDefersToNextMorning() {
        assertEquals(at(CHICAGO, 2026, 10, 17, 8), overnight.nextAllowed(at(CHICAGO, 2026, 10, 16, 22, 15), CHICAGO))
    }

    @Test fun earlyMorningQuietDefersToSameMorning() {
        assertEquals(at(CHICAGO, 2026, 10, 17, 8), overnight.nextAllowed(at(CHICAGO, 2026, 10, 17, 6), CHICAGO))
    }

    @Test fun allowedInstantIsUnchanged() {
        val i = at(CHICAGO, 2026, 10, 17, 12)
        assertEquals(i, overnight.nextAllowed(i, CHICAGO))
    }

    @Test fun sameDayWindow() {
        val lunch = QuietHoursCalculator(QuietHours(enabled = true, start = t(12), end = t(13)))
        assertTrue(lunch.isQuiet(at(CHICAGO, 2026, 10, 17, 12, 30), CHICAGO))
        assertEquals(at(CHICAGO, 2026, 10, 17, 13), lunch.nextAllowed(at(CHICAGO, 2026, 10, 17, 12, 30), CHICAGO))
    }

    @Test fun disabledOrEmptyWindowNeverQuiet() {
        assertFalse(QuietHoursCalculator(QuietHours(enabled = false)).isQuiet(at(CHICAGO, 2026, 10, 16, 23), CHICAGO))
        assertFalse(QuietHoursCalculator(QuietHours(true, t(9), t(9))).isQuiet(at(CHICAGO, 2026, 10, 16, 9), CHICAGO))
    }
}
