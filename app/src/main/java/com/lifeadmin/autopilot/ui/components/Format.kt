package com.lifeadmin.autopilot.ui.components

import com.lifeadmin.autopilot.domain.model.DeadlineSpec
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Format {
    // Resolved per call so a locale change while the app runs is respected.
    private val time get() = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dayDate get() = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val dayDateYear get() = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
    private val full get() = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.getDefault())

    fun time(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(time)

    /** "Today 18:00", "Tomorrow 09:00", "Tue 20 Oct, 09:00", "Tue 20 Oct 2027, 09:00". */
    fun relative(instant: Instant, now: Instant, zone: ZoneId): String {
        val date = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val t = instant.atZone(zone).format(time)
        return when (ChronoUnit.DAYS.between(today, date)) {
            0L -> "Today $t"
            1L -> "Tomorrow $t"
            -1L -> "Yesterday $t"
            else -> "${date(date, today)}, $t"
        }
    }

    /** Lower-case variant for mid-sentence use: "today 18:00", "tomorrow 09:00", "Tue 20 Oct, 09:00". */
    fun relativeLower(instant: Instant, now: Instant, zone: ZoneId): String {
        val r = relative(instant, now, zone)
        return when {
            r.startsWith("Today") || r.startsWith("Tomorrow") || r.startsWith("Yesterday") -> r.replaceFirstChar { it.lowercase() }
            else -> r
        }
    }

    /** Lower-case date for mid-sentence use. */
    fun dateLower(date: LocalDate, today: LocalDate): String {
        val r = date(date, today)
        return if (r == "Today" || r == "Tomorrow" || r == "Yesterday") r.lowercase() else r
    }

    /** "Monday 19 October" for screen headers. */
    fun longDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))

    /** "in 4 days", "today", "2 days ago". */
    fun daysUntil(date: LocalDate, today: LocalDate): String = when (val d = ChronoUnit.DAYS.between(today, date)) {
        0L -> "today"
        1L -> "tomorrow"
        -1L -> "yesterday"
        else -> if (d > 0) "in $d days" else "${-d} days ago"
    }

    /**
     * Date plus its distance, e.g. "Fri 30 Oct · in 10 days". Within a day of today the date is
     * already relative ("Yesterday"), so the distance is left out rather than repeated.
     */
    fun dateWithDistance(date: LocalDate, today: LocalDate): String {
        val label = date(date, today)
        return if (Math.abs(ChronoUnit.DAYS.between(today, date)) <= 1) label else "$label · ${daysUntil(date, today)}"
    }

    fun date(date: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "Today"
        1L -> "Tomorrow"
        -1L -> "Yesterday"
        else -> if (date.year == today.year) date.format(dayDate) else date.format(dayDateYear)
    }

    fun full(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(full)

    /** A deadline being chosen (capture sheet chips): "Tomorrow", "Fri 30 Oct", "Wed 21 Oct, 17:00". */
    fun deadlineSpec(d: DeadlineSpec, now: Instant, zone: ZoneId): String = when (d.type) {
        DeadlineType.DATE_ONLY -> d.date?.let { date(it, now.atZone(zone).toLocalDate()) } ?: "Date"
        DeadlineType.EXACT -> d.instant?.let { relative(it, now, zone) } ?: "Date & time"
        DeadlineType.NONE -> "None"
    }

    /**
     * The deadline row on a task's detail screen: "None", a date with its distance ("Fri 30 Oct ·
     * in 10 days", "Yesterday") or an exact time, plus " · overdue" when it has passed.
     */
    fun deadlineDetail(task: Task, now: Instant, zone: ZoneId): String {
        val today = now.atZone(zone).toLocalDate()
        val value = when (task.deadlineType) {
            DeadlineType.NONE -> "None"
            DeadlineType.DATE_ONLY -> task.deadlineLocalDate?.let { dateWithDistance(it, today) } ?: "None"
            DeadlineType.EXACT -> task.deadlineInstant?.let { relative(it, now, zone) } ?: "None"
        }
        return if (task.isOverdue(now, zone)) "$value · overdue" else value
    }

    fun deadline(task: Task, now: Instant, zone: ZoneId): String? = when (task.deadlineType) {
        DeadlineType.NONE -> null
        DeadlineType.DATE_ONLY -> task.deadlineLocalDate?.let { "Due ${date(it, now.atZone(zone).toLocalDate())}" }
        DeadlineType.EXACT -> task.deadlineInstant?.let { "Due ${relative(it, now, zone)}" }
    }
}

/** Deadline passed? Date-only deadlines are overdue the day after the due date. */
fun Task.isOverdue(now: Instant, zone: ZoneId): Boolean {
    if (status.isTerminal || archivedAt != null) return false
    return when (deadlineType) {
        DeadlineType.NONE -> false
        DeadlineType.DATE_ONLY -> deadlineLocalDate?.let { it < now.atZone(zone).toLocalDate() } ?: false
        DeadlineType.EXACT -> deadlineInstant?.let { it < now } ?: false
    }
}

fun Task.isSnoozed(now: Instant): Boolean = snoozedUntil?.let { it > now } ?: false
