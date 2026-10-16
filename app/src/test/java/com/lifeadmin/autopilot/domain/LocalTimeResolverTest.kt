package com.lifeadmin.autopilot.domain

import com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver
import com.lifeadmin.autopilot.testing.KOLKATA
import com.lifeadmin.autopilot.testing.LORD_HOWE
import com.lifeadmin.autopilot.testing.NEW_YORK
import com.lifeadmin.autopilot.testing.t
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class LocalTimeResolverTest {

    @Test fun normalTimeResolvesWithZoneOffset() {
        val i = LocalTimeResolver.resolve(LocalDate.of(2026, 10, 16), t(9), NEW_YORK)
        assertEquals(Instant.parse("2026-10-16T13:00:00Z"), i) // EDT = UTC-4
    }

    @Test fun springForwardGapMovesToFirstValidTimeAfterGap() {
        // 2026-03-08 02:00 EST → 03:00 EDT; 02:30 does not exist.
        val i = LocalTimeResolver.resolve(LocalDate.of(2026, 3, 8), t(2, 30), NEW_YORK)
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), i) // 03:00 EDT, not 03:30
        assertEquals(t(3, 0), i.atZone(NEW_YORK).toLocalTime())
    }

    @Test fun fallBackOverlapUsesEarlierOffset() {
        // 2026-11-01 02:00 EDT → 01:00 EST; 01:30 occurs twice.
        val i = LocalTimeResolver.resolve(LocalDate.of(2026, 11, 1), t(1, 30), NEW_YORK)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), i) // first 01:30 (EDT, UTC-4)
    }

    @Test fun halfHourOffsetZone() {
        val i = LocalTimeResolver.resolve(LocalDate.of(2026, 10, 16), t(9), KOLKATA)
        assertEquals(Instant.parse("2026-10-16T03:30:00Z"), i)
    }

    @Test fun halfHourDstShiftZoneGap() {
        // Lord Howe Island moves 02:00 → 02:30 on 2026-10-04 (first Sunday in October).
        val i = LocalTimeResolver.resolve(LocalDate.of(2026, 10, 4), t(2, 15), LORD_HOWE)
        assertEquals(t(2, 30), i.atZone(LORD_HOWE).toLocalTime())
    }
}
