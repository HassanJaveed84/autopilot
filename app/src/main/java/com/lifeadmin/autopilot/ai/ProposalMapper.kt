package com.lifeadmin.autopilot.ai

import com.lifeadmin.autopilot.data.repository.ValidationException
import com.lifeadmin.autopilot.domain.model.DeadlineSpec
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.PlannedTaskCommand
import com.lifeadmin.autopilot.domain.model.PolicySpec
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskEditCommand
import com.lifeadmin.autopilot.domain.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Converts a reviewed draft into domain commands, re-validating every user edit. Local
 * wall-clock values are resolved to instants here with the same zone rules the scheduler uses.
 */
object ProposalMapper {

    fun toCreate(draft: ProposalDraft, now: Instant, zone: ZoneId): PlannedTaskCommand {
        val title = draft.title.trim().ifEmpty { throw ValidationException("Add a title before saving") }
        if (title.length > ProposalValidator.MAX_TITLE) throw ValidationException("Title is too long (max ${ProposalValidator.MAX_TITLE} characters)")
        val steps = selectedSteps(draft)
        val waiting = draft.waiting.takeIf { it.apply }
        val checkIn = waiting?.checkInLocal?.let { toInstant(it, zone) }
        if (checkIn != null && !checkIn.isAfter(now)) throw ValidationException("The check-in time is in the past")
        return PlannedTaskCommand(
            taskId = draft.proposalId,
            title = title,
            notes = draft.notes.trim().ifEmpty { null },
            priority = priorityOf(draft) ?: Priority.NORMAL,
            status = if (waiting != null) TaskStatus.WAITING else TaskStatus.ACTIONABLE,
            steps = steps,
            deadline = deadlineSpec(draft.deadline, now, zone),
            policy = policySpec(draft.reminder, now, zone),
            waitingReason = waiting?.waitingFor?.trim()?.ifEmpty { null },
            waitingCheckInAt = checkIn,
            source = "ai_plan",
        )
    }

    fun toEdit(draft: ProposalDraft, now: Instant, zone: ZoneId): TaskEditCommand {
        val taskId = draft.targetTaskId ?: throw ValidationException("No task to update")
        val waiting = draft.waiting.takeIf { it.apply }
        val checkIn = waiting?.checkInLocal?.let { toInstant(it, zone) }
        if (checkIn != null && !checkIn.isAfter(now)) throw ValidationException("The check-in time is in the past")
        return TaskEditCommand(
            taskId = taskId,
            expectedUpdatedAt = draft.targetUpdatedAtMillis?.let(Instant::ofEpochMilli),
            title = draft.title.trim().takeIf { draft.applyTitle }?.also {
                if (it.isEmpty()) throw ValidationException("Title can't be empty")
            },
            notes = draft.notes.trim().takeIf { draft.applyNotes },
            priority = priorityOf(draft),
            addSteps = selectedSteps(draft),
            deadline = deadlineSpec(draft.deadline, now, zone),
            policy = policySpec(draft.reminder, now, zone),
            markWaiting = waiting != null,
            waitingReason = waiting?.waitingFor?.trim()?.ifEmpty { null },
            waitingCheckInAt = checkIn,
            source = "ai_follow_through",
        )
    }

    fun selectedSteps(draft: ProposalDraft): List<String> {
        val steps = draft.steps.filter { it.include }.map { it.text.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (draft.addNextStep && draft.nextStep.isNotBlank() && !ProposalValidator.nextStepListed(draft)) steps.add(0, draft.nextStep.trim())
        if (steps.any { it.length > ProposalValidator.MAX_STEP }) throw ValidationException("A step is too long (max ${ProposalValidator.MAX_STEP} characters)")
        if (steps.size > 20) throw ValidationException("Too many steps")
        return steps.distinctBy { it.lowercase() }
    }

    private fun priorityOf(draft: ProposalDraft): Priority? =
        if (draft.applyPriority) draft.priority?.let { p -> Priority.entries.firstOrNull { it.name == p } } else null

    fun deadlineSpec(d: DraftDeadline, now: Instant, zone: ZoneId): DeadlineSpec? {
        if (!d.apply) return null
        val date = d.localDate ?: throw ValidationException("Pick a valid deadline date or turn the deadline off")
        val today = now.atZone(zone).toLocalDate()
        if (date.isBefore(today)) throw ValidationException("The deadline is in the past")
        val time = d.localTime
        return if (time == null) DeadlineSpec(DeadlineType.DATE_ONLY, date = date)
        else DeadlineSpec(DeadlineType.EXACT, instant = toInstant(LocalDateTime.of(date, time), zone))
    }

    fun policySpec(r: DraftReminder, now: Instant, zone: ZoneId): PolicySpec? {
        if (!r.apply || r.kind == DraftReminderKind.OFF) return null
        val today = now.atZone(zone).toLocalDate()
        return when (r.kind) {
            DraftReminderKind.ONCE -> {
                val at = r.onceLocal?.let { toInstant(it, zone) } ?: throw ValidationException("Pick a date and time for the reminder")
                if (!at.isAfter(now)) throw ValidationException("The reminder time has already passed")
                PolicySpec(ReminderKind.ONCE, atInstant = at)
            }
            DraftReminderKind.DAILY, DraftReminderKind.EVERY_N_DAYS -> {
                val times = r.localTimes
                if (times.isEmpty()) throw ValidationException("Add at least one reminder time")
                if (times.size > ProposalValidator.MAX_TIMES) throw ValidationException("At most ${ProposalValidator.MAX_TIMES} times per day")
                val interval = if (r.kind == DraftReminderKind.DAILY) 1 else r.dayInterval
                if (interval !in 1..365) throw ValidationException("Interval must be between 1 and 365 days")
                PolicySpec(
                    kind = if (interval == 1) ReminderKind.DAILY_TIMES else ReminderKind.EVERY_N_DAYS,
                    times = times, dayInterval = interval,
                    startLocalDate = (r.startLocalDate ?: today).let { if (it.isBefore(today)) today else it },
                )
            }
            DraftReminderKind.OFF -> null
        }
    }

    /**
     * Same gap/overlap policy as the scheduler: a nonexistent local time (spring-forward gap)
     * moves forward past the gap; an ambiguous one (fall-back) uses the earlier offset.
     */
    fun toInstant(local: LocalDateTime, zone: ZoneId): Instant =
        com.lifeadmin.autopilot.domain.scheduling.LocalTimeResolver.resolve(local.toLocalDate(), local.toLocalTime(), zone)

    fun today(now: Instant, zone: ZoneId): LocalDate = now.atZone(zone).toLocalDate()
}
