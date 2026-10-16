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
)

data class TaskStep(
    val id: String,
    val taskId: String,
    val sortOrder: Int,
    val title: String,
    val isDone: Boolean = false,
    val completedAt: Instant? = null,
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
)

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
