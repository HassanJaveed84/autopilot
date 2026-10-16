package com.lifeadmin.autopilot.domain.scheduling

import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.ZoneMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PolicyDescriber {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

    fun describe(policy: ReminderPolicy?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (policy == null || !policy.enabled) return "Off"
        val times = policy.times.sorted().joinToString(" and ") { it.format(timeFmt) }
        val base = when (policy.kind) {
            ReminderKind.ONCE -> policy.atInstant?.let { "Once · ${it.atZone(zone).format(dateTimeFmt)}" } ?: "Once"
            ReminderKind.DAILY_TIMES -> "Daily at $times"
            ReminderKind.EVERY_N_DAYS ->
                if (policy.dayInterval <= 1) "Daily at $times" else "Every ${policy.dayInterval} days at $times"
        }
        return if (policy.zoneMode == ZoneMode.FIXED_ZONE && policy.zoneId != null) "$base (${policy.zoneId})" else base
    }
}
