package com.lifeadmin.autopilot.ui.capture

import com.lifeadmin.autopilot.domain.capture.CaptureTiming
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.TaskPresentation
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Plain words for what the capture sheet understood from the text. Pure, so it is unit-testable. */
object CaptureTimingText {
    private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    /** "Nudge tonight 21:30 · your bedtime", "21:30 daily · a routine", "From Mon 26 Oct · your usual nudges". */
    fun nudge(n: CaptureTiming.Nudge, policy: ReminderPolicy, settings: AppSettings, now: Instant, zone: ZoneId): String = when (n) {
        is CaptureTiming.Nudge.Once -> {
            val local = n.at.atZone(zone)
            val today = now.atZone(zone).toLocalDate()
            val day = if (local.toLocalDate() == today && local.hour >= 17) "tonight ${hhmm(local.toLocalTime())}" else Format.relativeLower(n.at, now, zone)
            "Nudge $day" + if (n.bedtime && local.toLocalTime() == settings.bedtime) " · your bedtime" else ""
        }
        is CaptureTiming.Nudge.Daily ->
            TaskPresentation.scheduleShort(policy, zone, now).replaceFirstChar { it.uppercase() } + " · a routine" +
                if (n.bedtime && policy.times == listOf(settings.bedtime)) " · your bedtime" else ""
        is CaptureTiming.Nudge.FromDay -> "From ${Format.dateLower(n.date, now.atZone(zone).toLocalDate())} · your usual nudges (${TaskPresentation.scheduleShort(policy, zone, now)})"
    }

    /** "Deadline Mon 26 Oct, 17:00", "Deadline tomorrow". */
    fun deadline(d: CaptureTiming.Deadline, now: Instant, zone: ZoneId): String =
        "Deadline " + Format.deadlineSpec(d.spec, now, zone).let { if (it.startsWith("Today") || it.startsWith("Tomorrow")) it.replaceFirstChar { c -> c.lowercase() } else it }

    fun source(phrase: String): String = "from “$phrase”"

    /** The quiet-hours switch's explanation, before saving. */
    fun quietHours(settings: AppSettings, on: Boolean): String {
        val q = settings.quietHours
        val window = "${hhmm(q.start)}–${hhmm(q.end)}"
        return if (on) "Rings at its time even during your quiet hours ($window), for this task only. Android's Do Not Disturb and notification settings still apply."
        else "It falls in your quiet hours ($window), so it would wait until ${hhmm(q.end)}. Turn this on to let it ring at its time, for this task only."
    }
}
