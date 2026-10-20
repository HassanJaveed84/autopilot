package com.lifeadmin.autopilot.data.local

import androidx.room.ColumnInfo
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
    /** Added in schema v2. */
    @ColumnInfo(defaultValue = "1") val snoozeRemindAtEnd: Boolean = true,
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
    /** Added in schema v2: whether the OS alarm was scheduled as exact. */
    @ColumnInfo(defaultValue = "0") val exact: Boolean = false,
    /** Added in schema v2: short machine reason (e.g. suppression cause). Never task content. */
    val note: String? = null,
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

/**
 * Delivery-lifecycle diagnostics (schema v3). Intentionally no foreign keys: entries must
 * survive task deletion and ledger pruning so past incidents stay explainable. Holds ids and
 * timestamps only — never titles, notes or steps.
 */
@Entity(
    tableName = "reminder_diagnostics",
    indices = [Index("at"), Index("occurrenceId")],
)
data class ReminderDiagnosticEntity(
    @PrimaryKey val id: String,
    val at: Instant,
    val type: String,
    val occurrenceId: String?,
    val taskId: String?,
    val plannedAt: Instant?,
    val triggerAt: Instant?,
    val source: String?,
    val exact: Boolean?,
    val latenessMs: Long?,
    val outcome: String?,
    val detail: String?,
)

/**
 * A saved research brief (M3): the question, the parsed brief as JSON and which moves were applied.
 * Owned by its task (deleted with it). Holds normalized findings and source metadata only — never
 * raw API transcripts, page content or credentials.
 */
@Entity(
    tableName = "research_briefs",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId"), Index("createdAt")],
)
data class ResearchBriefEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val createdAt: Instant,
    val question: String,
    val model: String,
    /** VERIFIED, UNVERIFIED or TRUNCATED. */
    val status: String,
    val searchCount: Int,
    val sourceCount: Int,
    /** JSON of [com.lifeadmin.autopilot.ai.research.BriefContent]. */
    val payload: String,
    /** JSON list of [com.lifeadmin.autopilot.ai.research.AppliedMove]. */
    val applied: String,
)
