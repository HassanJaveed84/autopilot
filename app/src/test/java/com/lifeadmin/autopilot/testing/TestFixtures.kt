package com.lifeadmin.autopilot.testing

import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic, mutable clock for scheduler tests. */
class FakeClock(var instant: Instant) : AppClock {
    override fun now(): Instant = instant
    fun advance(d: Duration) { instant = instant.plus(d) }
    fun set(local: LocalDateTime, zone: ZoneId) { instant = local.atZone(zone).toInstant() }
}

class FakeZoneProvider(var zone: ZoneId) : ZoneProvider {
    override fun current(): ZoneId = zone
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private val n = AtomicInteger()
    override fun newId(): String = "%s-%08d".format(prefix, n.incrementAndGet())
}

val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
val CHICAGO: ZoneId = ZoneId.of("America/Chicago")
val KOLKATA: ZoneId = ZoneId.of("Asia/Kolkata")
val LORD_HOWE: ZoneId = ZoneId.of("Australia/Lord_Howe")
val UTC: ZoneId = ZoneId.of("UTC")

fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0): Instant =
    LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

fun t(h: Int, m: Int = 0): LocalTime = LocalTime.of(h, m)

fun task(status: TaskStatus = TaskStatus.ACTIONABLE, created: Instant = Instant.parse("2026-01-01T00:00:00Z")) = Task(
    id = "task-1", title = "Book train ride", status = status, createdAt = created, updatedAt = created,
)

fun dailyPolicy(vararg times: LocalTime, start: LocalDate? = null) = ReminderPolicy(
    id = "policy-1", taskId = "task-1", enabled = true, kind = ReminderKind.DAILY_TIMES,
    times = times.toList(), startLocalDate = start, lastEditedAt = Instant.parse("2026-01-01T00:00:00Z"),
)
