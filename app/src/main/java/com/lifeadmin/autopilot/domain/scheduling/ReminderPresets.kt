package com.lifeadmin.autopilot.domain.scheduling

import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import java.time.LocalDate
import java.time.LocalTime

/** Presets only pre-populate an editable policy draft. */
object ReminderPresets {
    fun label(preset: ReminderPreset): String = when (preset) {
        ReminderPreset.GENTLE -> "Gentle · every 2 days at 10:00"
        ReminderPreset.DAILY -> "Daily · 09:00"
        ReminderPreset.PERSISTENT -> "Persistent · 09:00 and 18:00 daily"
    }

    fun shortLabel(preset: ReminderPreset): String = when (preset) {
        ReminderPreset.GENTLE -> "Gentle"
        ReminderPreset.DAILY -> "Daily"
        ReminderPreset.PERSISTENT -> "Persistent"
    }

    fun applyTo(policy: ReminderPolicy, preset: ReminderPreset, today: LocalDate): ReminderPolicy = when (preset) {
        ReminderPreset.GENTLE -> policy.copy(
            enabled = true, kind = ReminderKind.EVERY_N_DAYS, dayInterval = 2,
            times = listOf(LocalTime.of(10, 0)), startLocalDate = today, atInstant = null,
        )
        ReminderPreset.DAILY -> policy.copy(
            enabled = true, kind = ReminderKind.DAILY_TIMES, dayInterval = 1,
            times = listOf(LocalTime.of(9, 0)), startLocalDate = today, atInstant = null,
        )
        ReminderPreset.PERSISTENT -> policy.copy(
            enabled = true, kind = ReminderKind.DAILY_TIMES, dayInterval = 1,
            times = listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), startLocalDate = today, atInstant = null,
        )
    }
}
