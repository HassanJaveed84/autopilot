package com.lifeadmin.autopilot.domain.scheduling

import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.SnoozePreset
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object SnoozeCalculator {
    /** Before this local time, "tomorrow morning" means the coming morning (same calendar day). */
    private val EARLY_MORNING_CUTOFF: LocalTime = LocalTime.of(5, 0)

    fun target(preset: SnoozePreset, now: Instant, zone: ZoneId): Instant = when (preset) {
        SnoozePreset.ONE_HOUR -> now.plus(Duration.ofHours(1))
        SnoozePreset.THREE_HOURS -> now.plus(Duration.ofHours(3))
        SnoozePreset.TOMORROW_MORNING -> {
            val local = now.atZone(zone)
            val date = if (local.toLocalTime() < EARLY_MORNING_CUTOFF) local.toLocalDate() else local.toLocalDate().plusDays(1)
            LocalTimeResolver.resolve(date, AppSettings.MORNING, zone)
        }
    }

    fun targetMinutes(minutes: Int, now: Instant): Instant = now.plus(Duration.ofMinutes(minutes.toLong().coerceAtLeast(1)))
}
