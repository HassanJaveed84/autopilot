package com.lifeadmin.autopilot.domain.scheduling

import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Generates nominal reminder slot instants for a policy, in ascending order, strictly after
 * [fromExclusive]. Pure function of the policy definition: delivery history never shifts the
 * anchor, so EVERY_N_DAYS schedules cannot drift.
 */
object PolicySlotGenerator {

    /** Upper bound on how far ahead we search (days). */
    const val HORIZON_DAYS = 800L

    fun slotsAfter(policy: ReminderPolicy, zone: ZoneId, fromExclusive: Instant): Sequence<Instant> {
        return when (policy.kind) {
            ReminderKind.ONCE -> {
                val at = policy.atInstant
                if (at != null && at > fromExclusive) sequenceOf(at) else emptySequence()
            }
            ReminderKind.DAILY_TIMES -> recurring(policy, zone, fromExclusive, interval = 1)
            ReminderKind.EVERY_N_DAYS -> recurring(policy, zone, fromExclusive, interval = policy.dayInterval.coerceAtLeast(1))
        }
    }

    private fun recurring(policy: ReminderPolicy, zone: ZoneId, fromExclusive: Instant, interval: Int): Sequence<Instant> {
        val times = policy.times.distinct().sorted()
        if (times.isEmpty()) return emptySequence()
        val anchor: LocalDate? = policy.startLocalDate
            ?: if (interval > 1) policy.lastEditedAt.atZone(zone).toLocalDate() else null

        // Start one day early so offset changes around the boundary cannot skip a slot.
        var first = fromExclusive.atZone(zone).toLocalDate().minusDays(1)
        if (anchor != null && first < anchor) first = anchor
        if (anchor != null && interval > 1) {
            val diff = ChronoUnit.DAYS.between(anchor, first)
            val rem = Math.floorMod(diff, interval.toLong())
            if (rem != 0L) first = first.plusDays(interval - rem)
        }
        val maxDays = HORIZON_DAYS / interval + 1
        return generateSequence(first) { it.plusDays(interval.toLong()) }
            .take(maxDays.toInt())
            .flatMap { date -> times.asSequence().map { LocalTimeResolver.resolve(date, it, zone) } }
            .filter { it > fromExclusive }
    }
}
