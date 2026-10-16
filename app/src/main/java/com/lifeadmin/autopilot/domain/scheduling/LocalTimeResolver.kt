package com.lifeadmin.autopilot.domain.scheduling

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Converts a wall-clock (date, time) in a zone to an absolute instant with explicit DST rules:
 *
 * - **Spring-forward gap** (local time does not exist): resolve to the first valid local time
 *   following the gap, i.e. the transition instant (02:30 in a 02:00→03:00 gap becomes 03:00).
 *   Note this intentionally differs from `ZonedDateTime.of`, which would shift to 03:30.
 * - **Fall-back overlap** (local time occurs twice): resolve to the earlier offset, so a slot
 *   fires once per local calendar day at its first occurrence.
 */
object LocalTimeResolver {
    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
        val local = LocalDateTime.of(date, time)
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        return when {
            offsets.isEmpty() -> rules.getTransition(local).instant
            else -> ZonedDateTime.ofLocal(local, zone, null).withEarlierOffsetAtOverlap().toInstant()
        }
    }
}
