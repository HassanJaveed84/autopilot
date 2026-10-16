package com.lifeadmin.autopilot.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(
    tableName = "tasks",
    indices = [Index("status"), Index("updatedAt")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String?,
    val status: TaskStatus,
    val priority: Priority,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val archivedAt: Instant?,
    val deadlineType: DeadlineType,
    val deadlineLocalDate: LocalDate?,
    val deadlineInstant: Instant?,
    val deadlineZoneId: String?,
    val snoozedUntil: Instant?,
    val waitingReason: String?,
    val waitingCheckInAt: Instant?,
)

@Entity(
    tableName = "task_steps",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class TaskStepEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val sortOrder: Int,
    val title: String,
    val isDone: Boolean,
    val completedAt: Instant?,
)

@Entity(
    tableName = "reminder_policies",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    // One policy per task in the MVP.
    indices = [Index(value = ["taskId"], unique = true)],
)
data class ReminderPolicyEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val enabled: Boolean,
    val kind: ReminderKind,
    val startLocalDate: LocalDate?,
    val zoneMode: ZoneMode,
    val zoneId: String?,
    val dayInterval: Int,
    val quietHoursBehavior: QuietHoursBehavior?,
    val snoozeDefaultMinutes: Int?,
    val atInstant: Instant?,
    val lastEditedAt: Instant,
    val generation: Int,
)

@Entity(
    tableName = "reminder_time_slots",
    foreignKeys = [ForeignKey(entity = ReminderPolicyEntity::class, parentColumns = ["id"], childColumns = ["policyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("policyId")],
)
data class ReminderTimeSlotEntity(
    @PrimaryKey val id: String,
    val policyId: String,
    val localTime: LocalTime,
    val sortOrder: Int,
)

@Entity(
    tableName = "reminder_occurrences",
    foreignKeys = [
        ForeignKey(entity = ReminderPolicyEntity::class, parentColumns = ["id"], childColumns = ["policyId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        // SPEC §4.4: prevents duplicate occurrence creation; also the coalescing key.
        Index(value = ["policyId", "plannedAt"], unique = true),
        Index("taskId"),
        Index("status"),
    ],
)
data class ReminderOccurrenceEntity(
    @PrimaryKey val id: String,
    val policyId: String,
    val taskId: String,
    val kind: OccurrenceKind,
    val plannedAt: Instant,
    val slotAt: Instant,
    val scheduledAt: Instant?,
    val deliveredAt: Instant?,
    val status: OccurrenceStatus,
    val generation: Int,
)

@Entity(
    tableName = "task_events",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["taskId", "at"])],
)
data class TaskEventEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val at: Instant,
    val eventType: EventType,
    val summary: String,
    /** Small allowlisted JSON object (string → string). Never task notes, secrets or prompts. */
    val structuredMetadata: String?,
)
