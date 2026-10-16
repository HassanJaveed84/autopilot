package com.lifeadmin.autopilot.domain.scheduling

import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import java.time.Instant
import java.time.ZoneId

data class PlannedReminder(
    val kind: OccurrenceKind,
    /** Nominal slot before quiet-hours deferral. */
    val slotAt: Instant,
    /** Effective delivery target; equals [slotAt] unless deferred by quiet hours. */
    val plannedAt: Instant,
) {
    val deferred: Boolean get() = plannedAt != slotAt
}

/**
 * Deterministic "what is the single next reminder for this task?" calculation.
 * No Android, network or AI dependencies; every input is explicit.
 */
object ReminderPlanner {

    private const val MAX_CANDIDATES = 5_000

    fun policyZone(policy: ReminderPolicy?, deviceZone: ZoneId): ZoneId {
        if (policy?.zoneMode == ZoneMode.FIXED_ZONE) {
            policy.zoneId?.let { id -> runCatching { ZoneId.of(id) }.getOrNull()?.let { return it } }
        }
        return deviceZone
    }

    /**
     * @param isSettled returns true when an occurrence with this planned instant has already been
     *   delivered/suppressed/missed for the policy; such instants are never planned again.
     */
    fun next(
        task: Task,
        policy: ReminderPolicy?,
        settings: AppSettings,
        now: Instant,
        deviceZone: ZoneId,
        isSettled: (Instant) -> Boolean = { false },
    ): PlannedReminder? {
        val status = task.status
        if (status.isTerminal || status == TaskStatus.PAUSED || task.archivedAt != null) return null

        val snoozeEnd = task.snoozedUntil?.takeIf { it > now }
        val zone = policyZone(policy, deviceZone)
        val streams = mutableListOf<Sequence<Pair<OccurrenceKind, Instant>>>()

        if ((status == TaskStatus.ACTIONABLE || status == TaskStatus.INBOX) && policy != null && policy.enabled) {
            // While snoozed, intervening policy slots are suppressed; slots at/after snooze end resume.
            val from = if (snoozeEnd != null) snoozeEnd.minusNanos(1) else now
            streams += PolicySlotGenerator.slotsAfter(policy, zone, from).map { OccurrenceKind.POLICY to it }
        }
        if (status == TaskStatus.WAITING) {
            val checkIn = task.waitingCheckInAt
            if (checkIn != null && checkIn > now && (snoozeEnd == null || checkIn >= snoozeEnd)) {
                streams += sequenceOf(OccurrenceKind.CHECK_IN to checkIn)
            }
        }
        if (snoozeEnd != null && task.snoozeRemindAtEnd) {
            streams += sequenceOf(OccurrenceKind.SNOOZE_END to snoozeEnd)
        }
        if (streams.isEmpty()) return null

        val behavior = policy?.quietHoursBehavior ?: settings.quietHoursBehavior
        val quiet = QuietHoursCalculator(settings.quietHours)
        // Quiet hours are about the user's current location/sleep, so use the device zone.
        var examined = 0
        for ((kind, slot) in mergeSorted(streams)) {
            if (++examined > MAX_CANDIDATES) break
            if (slot <= now) continue
            val planned = if (quiet.isQuiet(slot, deviceZone)) {
                when (behavior) {
                    QuietHoursBehavior.SKIP -> continue
                    QuietHoursBehavior.DEFER_AND_COALESCE -> quiet.nextAllowed(slot, deviceZone)
                }
            } else {
                slot
            }
            if (planned <= now) continue
            if (isSettled(planned)) continue
            return PlannedReminder(kind, slot, planned)
        }
        return null
    }

    /** Merges ascending sequences; ties are broken by stream order (POLICY wins). */
    private fun mergeSorted(streams: List<Sequence<Pair<OccurrenceKind, Instant>>>): Sequence<Pair<OccurrenceKind, Instant>> = sequence {
        val iterators = streams.map { it.iterator() }
        val heads = iterators.map { if (it.hasNext()) it.next() else null }.toMutableList()
        while (true) {
            var best = -1
            for (i in heads.indices) {
                val h = heads[i] ?: continue
                if (best == -1 || h.second < heads[best]!!.second) best = i
            }
            if (best == -1) break
            yield(heads[best]!!)
            heads[best] = if (iterators[best].hasNext()) iterators[best].next() else null
        }
    }
}
