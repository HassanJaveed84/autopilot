package com.lifeadmin.autopilot.domain.scheduling

import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStatus
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * "Done for tonight" on a routine. A cycle runs from [CYCLE_START] to the next [CYCLE_START] in
 * local time, so a bedtime nudge answered after midnight still belongs to the evening it came
 * from, and a 09:00 + 18:00 routine done at 10:00 is quiet for the rest of the day. Finishing a
 * cycle never changes the task's status; the next cycle's nudges are planned as usual.
 */
object RoutineCycle {
    /** Same early-morning boundary [SnoozeCalculator] uses for "tomorrow morning". */
    val CYCLE_START: LocalTime = LocalTime.of(5, 0)
    private val EVENING: LocalTime = LocalTime.of(17, 0)

    /** Whether "Done for tonight" applies: an open, unarchived task on a confirmed routine. */
    fun canFinishCycle(task: Task, policy: ReminderPolicy?): Boolean =
        policy?.isRoutine == true && task.archivedAt == null &&
            (task.status == TaskStatus.ACTIONABLE || task.status == TaskStatus.INBOX)

    /** End of the cycle containing [now]: the first [CYCLE_START] strictly after it. */
    fun cycleEnd(now: Instant, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        val today = LocalTimeResolver.resolve(local.toLocalDate(), CYCLE_START, zone)
        return if (today > now) today else LocalTimeResolver.resolve(local.toLocalDate().plusDays(1), CYCLE_START, zone)
    }

    fun isCycleDone(task: Task, now: Instant): Boolean = task.cycleDoneUntil?.let { it > now } ?: false

    /**
     * The first action on a routine's notification. A nudge whose slot belongs to an earlier
     * cycle (held by quiet hours until morning, or delivered late) answers that cycle only.
     */
    fun notificationLabel(slot: Instant, now: Instant, zone: ZoneId): String =
        if (cycleEnd(slot, zone) > now) label(now, zone)
        else if (label(slot, zone) == "Done for tonight") "Done for last night" else "Done for yesterday"

    /** "Done for tonight" in the evening and night, "Done for today" otherwise. */
    fun label(now: Instant, zone: ZoneId): String {
        val t = now.atZone(zone).toLocalTime()
        return if (t >= EVENING || t < CYCLE_START) "Done for tonight" else "Done for today"
    }
}
