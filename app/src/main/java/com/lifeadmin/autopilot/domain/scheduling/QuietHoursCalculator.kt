package com.lifeadmin.autopilot.domain.scheduling

import com.lifeadmin.autopilot.domain.model.QuietHours
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Quiet-hours window math. Windows may cross midnight (default 21:00–08:00). */
class QuietHoursCalculator(private val quietHours: QuietHours) {

    fun isQuiet(instant: Instant, zone: ZoneId): Boolean {
        if (!quietHours.isEffective) return false
        val t = instant.atZone(zone).toLocalTime()
        return isQuietLocal(t)
    }

    private fun isQuietLocal(t: LocalTime): Boolean {
        val start = quietHours.start
        val end = quietHours.end
        return if (start < end) {
            t >= start && t < end
        } else {
            t >= start || t < end
        }
    }

    /** Returns [instant] if allowed, otherwise the first allowed instant (end of the quiet window). */
    fun nextAllowed(instant: Instant, zone: ZoneId): Instant {
        if (!isQuiet(instant, zone)) return instant
        val local = instant.atZone(zone)
        val date = local.toLocalDate()
        val t = local.toLocalTime()
        val start = quietHours.start
        val end = quietHours.end
        val endDate = if (start < end) date else if (t >= start) date.plusDays(1) else date
        val candidate = LocalTimeResolver.resolve(endDate, end, zone)
        // DST edge: if resolution lands on/before the input, step one more day.
        return if (candidate > instant) candidate else LocalTimeResolver.resolve(endDate.plusDays(1), end, zone)
    }
}
