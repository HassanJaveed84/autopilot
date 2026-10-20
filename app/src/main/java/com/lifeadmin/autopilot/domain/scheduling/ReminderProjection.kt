package com.lifeadmin.autopilot.domain.scheduling

import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStatus
import java.time.Instant
import java.time.ZoneId

/**
 * Expands a schedule into its next N reminders by iterating [ReminderPlanner.next] — the same
 * rules the engine uses (quiet-hours deferral and coalescing, fixed zones, snooze, check-ins),
 * so the Today rail and the follow-up preview can never disagree with what will actually fire.
 *
 * Only the *first* result is what Android has been handed (the engine keeps one scheduled
 * occurrence per task); later ones are a preview of what the engine will plan after it fires.
 */
object ReminderProjection {

    fun upcoming(
        task: Task,
        policy: ReminderPolicy?,
        settings: AppSettings,
        now: Instant,
        zone: ZoneId,
        limit: Int,
        /** Stop once a planned instant is after this (exclusive). */
        horizon: Instant? = null,
        isSettled: (Instant) -> Boolean = { false },
    ): List<PlannedReminder> {
        val out = ArrayList<PlannedReminder>(limit)
        var from = now
        var t = task
        while (out.size < limit) {
            val next = ReminderPlanner.next(t, policy, settings, from, zone, isSettled) ?: break
            if (horizon != null && next.plannedAt > horizon) break
            out += next
            from = next.plannedAt
            // One-off streams are consumed once they have been projected.
            when (next.kind) {
                OccurrenceKind.SNOOZE_END -> t = t.copy(snoozedUntil = null)
                OccurrenceKind.CHECK_IN -> t = t.copy(waitingCheckInAt = null)
                OccurrenceKind.POLICY -> Unit
            }
        }
        return out
    }

    /** Preview for a schedule that has no task yet (capture, plan review, settings default). */
    fun upcomingForNewTask(policy: ReminderPolicy?, settings: AppSettings, now: Instant, zone: ZoneId, limit: Int): List<PlannedReminder> =
        upcoming(Task(id = "", title = "", status = TaskStatus.ACTIONABLE, createdAt = now, updatedAt = now), policy, settings, now, zone, limit)
}
