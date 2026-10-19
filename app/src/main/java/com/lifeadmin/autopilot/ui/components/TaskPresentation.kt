package com.lifeadmin.autopilot.ui.components

import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Glyph shown next to a status sentence; mapped to an icon by the UI, so meaning never relies on color alone. */
enum class StatusGlyph { BELL, BELL_OFF, HOURGLASS, MOON, PAUSE, CHECK, CALENDAR, WARN, TRAY, CLOSE }

/** Color role of a status line. */
enum class StatusTone { SIGNAL, MUTED, DONE, DANGER }

data class StatusLine(val glyph: StatusGlyph, val text: String, val tone: StatusTone)

/** Where a card is shown; the rail describes the schedule, lists describe the next moment. */
enum class CardContext { RAIL, LIST }

/**
 * One formatter for the "status becomes a sentence" rule, shared by cards, detail and Today so
 * wording stays consistent: "09:00 & 18:00 daily, until done", "Waiting on Greenway Lettings ·
 * check in Wed 21 Oct, 08:00", "Snoozed until tomorrow 09:00", "Paused · no nudges".
 *
 * Pure: no Android, no clock access; every input is explicit so it is unit-testable.
 */
object TaskPresentation {
    private val hhmm get() = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    fun status(summary: TaskSummary, now: Instant, zone: ZoneId, context: CardContext = CardContext.LIST): StatusLine {
        val t = summary.task
        val next = summary.nextReminder
        val policy = summary.policy
        return when {
            t.status == TaskStatus.DONE -> StatusLine(StatusGlyph.CHECK, "Done", StatusTone.DONE)
            t.status == TaskStatus.CANCELED -> StatusLine(StatusGlyph.CLOSE, "Canceled", StatusTone.MUTED)
            t.archivedAt != null -> StatusLine(StatusGlyph.TRAY, "Archived", StatusTone.MUTED)
            t.status == TaskStatus.WAITING -> waitingLine(t, now, zone)
            t.status == TaskStatus.PAUSED -> StatusLine(StatusGlyph.PAUSE, "Paused · no nudges", StatusTone.MUTED)
            t.isSnoozed(now) -> StatusLine(StatusGlyph.MOON, "Snoozed until ${Format.relativeLower(t.snoozedUntil!!, now, zone)}", StatusTone.MUTED)
            t.status == TaskStatus.INBOX && (policy == null || !policy.enabled) -> StatusLine(StatusGlyph.TRAY, "In Inbox · no nudges yet", StatusTone.MUTED)
            next != null && next.kind == OccurrenceKind.POLICY && policy != null && policy.enabled -> when (context) {
                CardContext.RAIL -> StatusLine(StatusGlyph.BELL, scheduleSentence(policy, zone, now), StatusTone.SIGNAL)
                CardContext.LIST -> StatusLine(StatusGlyph.BELL, "Next nudge ${Format.relativeLower(next.plannedAt, now, zone)}", StatusTone.SIGNAL)
            }
            next != null && next.kind == OccurrenceKind.SNOOZE_END ->
                StatusLine(StatusGlyph.MOON, "Snooze ends ${Format.relativeLower(next.plannedAt, now, zone)}", StatusTone.MUTED)
            policy != null && policy.enabled -> StatusLine(StatusGlyph.BELL, "No upcoming nudge · ${scheduleSentence(policy, zone, now)}", StatusTone.MUTED)
            else -> StatusLine(StatusGlyph.BELL_OFF, "No nudges set", StatusTone.MUTED)
        }
    }

    private fun waitingLine(t: Task, now: Instant, zone: ZoneId): StatusLine {
        val who = t.waitingReason?.takeIf { it.isNotBlank() }
        val base = if (who != null) "Waiting on $who" else "Waiting"
        val checkIn = t.waitingCheckInAt
        return when {
            checkIn == null -> StatusLine(StatusGlyph.HOURGLASS, "$base · no check-in set", StatusTone.MUTED)
            checkIn <= now -> StatusLine(StatusGlyph.HOURGLASS, "$base · check-in was due ${Format.relativeLower(checkIn, now, zone)}", StatusTone.SIGNAL)
            else -> StatusLine(StatusGlyph.HOURGLASS, "$base · check in ${Format.relativeLower(checkIn, now, zone)}", StatusTone.MUTED)
        }
    }

    /** "09:00 & 18:00 daily, until done" / "every 2 days at 10:00, until done" / "once, tomorrow 09:00". */
    fun scheduleSentence(policy: ReminderPolicy, zone: ZoneId, now: Instant, withUntil: Boolean = true): String {
        if (!policy.enabled) return "No nudges"
        val times = policy.times.sorted().joinToString(" & ") { it.format(hhmm) }
        return when (policy.kind) {
            ReminderKind.ONCE -> "once, " + (policy.atInstant?.let { Format.relativeLower(it, now, zone) } ?: "time not set")
            ReminderKind.DAILY_TIMES -> "$times daily" + if (withUntil) ", until done" else ""
            ReminderKind.EVERY_N_DAYS ->
                (if (policy.dayInterval <= 1) "$times daily" else "every ${policy.dayInterval} days at $times") + if (withUntil) ", until done" else ""
        }
    }

    /** Short form for the capture row: "09:00 & 18:00 daily" / "every 2 days at 10:00" / "once, tomorrow 09:00". */
    fun scheduleShort(policy: ReminderPolicy, zone: ZoneId, now: Instant): String = scheduleSentence(policy, zone, now, withUntil = false)

    /** Deadline line, or null when none. Overdue and high priority are signal-toned. */
    fun deadline(t: Task, now: Instant, zone: ZoneId): StatusLine? {
        if (t.deadlineType == DeadlineType.NONE || t.status.isTerminal) return null
        val text = Format.deadline(t, now, zone) ?: return null
        val overdue = t.isOverdue(now, zone)
        return when {
            overdue -> StatusLine(StatusGlyph.WARN, "Overdue · ${text.removePrefix("Due ")}", StatusTone.DANGER)
            t.priority == Priority.HIGH -> StatusLine(StatusGlyph.CALENDAR, text, StatusTone.SIGNAL)
            else -> StatusLine(StatusGlyph.CALENDAR, text, StatusTone.MUTED)
        }
    }

    /** Single-string description for TalkBack: title, next step, status, deadline, steps. */
    fun describe(summary: TaskSummary, now: Instant, zone: ZoneId, context: CardContext = CardContext.LIST): String = buildString {
        append(summary.task.title)
        summary.nextStepTitle?.takeIf { !summary.task.status.isTerminal }?.let { append(", next step ").append(it) }
        append(", ").append(status(summary, now, zone, context).text)
        deadline(summary.task, now, zone)?.let { append(", ").append(it.text) }
        if (summary.stepsTotal > 0 && !summary.task.status.isTerminal) append(", ${summary.stepsDone} of ${summary.stepsTotal} steps")
        if (summary.task.priority == Priority.HIGH && !summary.task.status.isTerminal) append(", High priority")
    }

    /** Status pill on the detail screen. */
    fun pill(summary: TaskSummary, now: Instant, zone: ZoneId): StatusLine {
        val t = summary.task
        val policy = summary.policy
        return when {
            t.status == TaskStatus.DONE -> StatusLine(StatusGlyph.CHECK, "Done", StatusTone.DONE)
            t.status == TaskStatus.CANCELED -> StatusLine(StatusGlyph.CLOSE, "Canceled", StatusTone.MUTED)
            t.archivedAt != null -> StatusLine(StatusGlyph.TRAY, "Archived", StatusTone.MUTED)
            t.status == TaskStatus.WAITING -> StatusLine(StatusGlyph.HOURGLASS, "Waiting", StatusTone.MUTED)
            t.status == TaskStatus.PAUSED -> StatusLine(StatusGlyph.PAUSE, "Paused", StatusTone.MUTED)
            t.isSnoozed(now) -> StatusLine(StatusGlyph.MOON, "Snoozed", StatusTone.MUTED)
            t.status == TaskStatus.INBOX -> StatusLine(StatusGlyph.TRAY, "Inbox", StatusTone.MUTED)
            summary.nextReminder != null && policy?.enabled == true -> StatusLine(StatusGlyph.BELL, "Nudging", StatusTone.SIGNAL)
            else -> StatusLine(StatusGlyph.BELL_OFF, "No nudges", StatusTone.MUTED)
        }
    }
}
