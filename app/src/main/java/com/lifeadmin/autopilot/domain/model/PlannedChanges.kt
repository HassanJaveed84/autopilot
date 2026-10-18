package com.lifeadmin.autopilot.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/*
 * Plain domain commands produced from an approved plan. They know nothing about AI providers;
 * the repository applies each in a single transaction.
 */

/** Reminder schedule to apply; mirrors what the policy editor can express. */
data class PolicySpec(
    val kind: ReminderKind,
    val times: List<LocalTime> = emptyList(),
    val dayInterval: Int = 1,
    val startLocalDate: LocalDate? = null,
    val atInstant: Instant? = null,
)

data class DeadlineSpec(val type: DeadlineType, val date: LocalDate? = null, val instant: Instant? = null)

/** Create one task with its steps, deadline, reminders and waiting state, atomically. */
data class PlannedTaskCommand(
    /** Idempotency key and task id: re-applying the same command creates nothing new. */
    val taskId: String,
    val title: String,
    val notes: String?,
    val priority: Priority,
    val status: TaskStatus,
    val steps: List<String>,
    val deadline: DeadlineSpec?,
    val policy: PolicySpec?,
    val waitingReason: String?,
    val waitingCheckInAt: Instant?,
    /** Small allowlisted metadata for the activity log (never prompts or keys). */
    val source: String,
)

/** Selected changes to an existing task. Null = leave unchanged. */
data class TaskEditCommand(
    val taskId: String,
    /** Idempotency key: a suggestion is applied at most once per task. */
    val proposalId: String,
    /** Rejects the edit if the task changed after the suggestion was produced. */
    val expectedUpdatedAt: Instant?,
    val title: String? = null,
    val notes: String? = null,
    val priority: Priority? = null,
    val addSteps: List<String> = emptyList(),
    val deadline: DeadlineSpec? = null,
    val policy: PolicySpec? = null,
    val markWaiting: Boolean = false,
    val waitingReason: String? = null,
    val waitingCheckInAt: Instant? = null,
    val source: String,
) {
    val isEmpty: Boolean get() = title == null && notes == null && priority == null && addSteps.isEmpty() && deadline == null && policy == null && !markWaiting
}
