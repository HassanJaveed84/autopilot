package com.lifeadmin.autopilot.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class Task(
    val id: String,
    val title: String,
    val notes: String? = null,
    val status: TaskStatus = TaskStatus.INBOX,
    val priority: Priority = Priority.NORMAL,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    val archivedAt: Instant? = null,
    val deadlineType: DeadlineType = DeadlineType.NONE,
    val deadlineLocalDate: LocalDate? = null,
    val deadlineInstant: Instant? = null,
    val deadlineZoneId: String? = null,
    val snoozedUntil: Instant? = null,
    val snoozeRemindAtEnd: Boolean = true,
    val waitingReason: String? = null,
    val waitingCheckInAt: Instant? = null,
    /** Revision counter: every user change and every applied proposal increments it (handoff §4.2). */
    val rev: Int = 0,
    /** A date the task relates to ("the trip is Nov 12"); never a deadline by itself (PLAN-041). */
    val eventLocalDate: LocalDate? = null,
    val eventLabel: String? = null,
    /** The day the user intends to work on it ("Friday"); not a deadline (PLAN-040). */
    val intendedLocalDate: LocalDate? = null,
    /** A separately approved planning target with its stated basis; never shown as a deadline (PLAN-041, OD-6). */
    val targetLocalDate: LocalDate? = null,
    val targetBasis: String? = null,
    /**
     * "Done for tonight" on a routine (schema v8): policy nudges whose slot is before this instant
     * are skipped, the task stays open and the next cycle's nudges are scheduled as usual.
     */
    val cycleDoneUntil: Instant? = null,
) {
    /** True when the user stated (or approved) a latest acceptable completion. */
    val hasDeadline: Boolean get() = deadlineType != DeadlineType.NONE
}

data class TaskStep(
    val id: String,
    val taskId: String,
    val sortOrder: Int,
    val title: String,
    val isDone: Boolean = false,
    val completedAt: Instant? = null,
    val origin: ContentOrigin = ContentOrigin.USER,
    /** Provenance record id when the step came from an applied proposal (INT-031). */
    val provenanceId: String? = null,
    /** Rich Steps (v0.2.1): optional plain-text description; line breaks allowed. */
    val description: String? = null,
    /** Rich Steps (v0.2.1): labelled web links; the first is the main link shown on the row. */
    val links: List<StepLink> = emptyList(),
)

data class ReminderPolicy(
    val id: String,
    val taskId: String,
    val enabled: Boolean = false,
    val kind: ReminderKind = ReminderKind.DAILY_TIMES,
    val startLocalDate: LocalDate? = null,
    val zoneMode: ZoneMode = ZoneMode.FOLLOW_DEVICE,
    val zoneId: String? = null,
    val dayInterval: Int = 1,
    /** null = use the global quiet-hours behavior from settings. */
    val quietHoursBehavior: QuietHoursBehavior? = null,
    val snoozeDefaultMinutes: Int? = null,
    val atInstant: Instant? = null,
    val lastEditedAt: Instant,
    val generation: Int = 0,
    val times: List<LocalTime> = emptyList(),
    /**
     * Schema v8: a confirmed routine ("every night"). Its nudges repeat every cycle and are
     * answered with "Done for tonight"; the task stays open. Only meaningful for recurring kinds.
     */
    val routine: Boolean = false,
    /**
     * Schema v8: the user allowed this task's nudges to ring during the app's quiet hours. It only
     * lifts the app's own quiet hours; Android's notification settings and Do Not Disturb still apply.
     */
    val ringInQuietHours: Boolean = false,
) {
    /** True for a recurring schedule the user confirmed as a routine. */
    val isRoutine: Boolean get() = enabled && routine && kind != ReminderKind.ONCE
}

data class ReminderOccurrence(
    val id: String,
    val policyId: String,
    val taskId: String,
    val kind: OccurrenceKind,
    /** Effective delivery target (after quiet-hours deferral). Unique per policy: the dedupe key. */
    val plannedAt: Instant,
    /** Nominal slot instant before quiet-hours deferral (informational). */
    val slotAt: Instant,
    /** When the OS alarm was registered. */
    val scheduledAt: Instant?,
    val deliveredAt: Instant?,
    val status: OccurrenceStatus,
    val generation: Int,
    val exact: Boolean = false,
    val note: String? = null,
)

data class TaskEvent(
    val id: String,
    val taskId: String,
    val at: Instant,
    val type: EventType,
    val summary: String,
    val metadata: Map<String, String> = emptyMap(),
)

data class TaskWithDetails(
    val task: Task,
    val steps: List<TaskStep>,
    val policy: ReminderPolicy?,
    val nextOccurrence: ReminderOccurrence?,
)
