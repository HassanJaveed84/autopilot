package com.lifeadmin.autopilot.reminders

import java.time.Instant

data class AlarmRequest(
    val occurrenceId: String,
    val taskId: String,
    val generation: Int,
    val triggerAt: Instant,
    val preferExact: Boolean,
)

data class AlarmScheduleResult(val scheduled: Boolean, val exact: Boolean, val error: String? = null)

/** Thin adapter over AlarmManager so scheduling logic is testable without a device. */
interface AlarmDriver {
    /** True when exact alarms may be scheduled (special access granted, or API < 31). */
    fun canScheduleExact(): Boolean

    /** Idempotent: re-scheduling the same occurrence replaces its previous alarm. */
    fun schedule(request: AlarmRequest): AlarmScheduleResult

    fun cancel(occurrenceId: String)
}
