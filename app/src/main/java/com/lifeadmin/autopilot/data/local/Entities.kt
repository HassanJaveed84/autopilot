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
    /** Added in schema v6 (production kickoff M1). */
    @ColumnInfo(defaultValue = "0") val rev: Int = 0,
    val eventLocalDate: LocalDate? = null,
    val eventLabel: String? = null,
    val intendedLocalDate: LocalDate? = null,
    val targetLocalDate: LocalDate? = null,
    val targetBasis: String? = null,
    /** Added in schema v8 (right-time nudges): end of a routine's finished cycle. */
    val cycleDoneUntil: Instant? = null,
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
    /** Added in schema v6: [com.lifeadmin.autopilot.domain.model.ContentOrigin] name. */
    @ColumnInfo(defaultValue = "USER") val origin: String = "USER",
    val provenanceId: String? = null,
    /** Added in schema v9 (Rich Steps): optional plain-text description. */
    val description: String? = null,
    /** Added in schema v9 (Rich Steps): links as a JSON array of [com.lifeadmin.autopilot.domain.model.StepLink]; null when none. */
    val links: String? = null,
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
    /** Added in schema v8 (right-time nudges). */
    @ColumnInfo(defaultValue = "0") val routine: Boolean = false,
    @ColumnInfo(defaultValue = "0") val ringInQuietHours: Boolean = false,
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

/**
 * One Investigation Thread (M4): standalone or associated with a task (later a draft). Turns live in
 * [InvestigationTurnEntity]. Deleting a task leaves its investigations as standalone history
 * (the association is recorded in the meta JSON), never deletes the person's research.
 */
@Entity(
    tableName = "investigations",
    indices = [Index("taskId"), Index("draftId"), Index("updatedAt")],
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.SET_NULL)],
)
data class InvestigationEntity(
    @PrimaryKey val id: String,
    /** [com.lifeadmin.autopilot.ai.investigation.InvestigationOrigin] name. */
    val origin: String,
    val taskId: String?,
    val draftId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val title: String,
    /** State of the latest turn ([com.lifeadmin.autopilot.ai.investigation.TurnState] name), for lists. */
    val state: String,
    val turnCount: Int,
    /** JSON of [com.lifeadmin.autopilot.ai.investigation.InvestigationMeta]. */
    val meta: String,
)

/** One turn of an investigation: an immutable record once its state is final (RES-032/092). */
@Entity(
    tableName = "investigation_turns",
    indices = [Index("investigationId"), Index(value = ["investigationId", "turnIndex"], unique = true)],
    foreignKeys = [ForeignKey(entity = InvestigationEntity::class, parentColumns = ["id"], childColumns = ["investigationId"], onDelete = ForeignKey.CASCADE)],
)
data class InvestigationTurnEntity(
    @PrimaryKey val id: String,
    val investigationId: String,
    val turnIndex: Int,
    /** [com.lifeadmin.autopilot.ai.investigation.TurnKind] name. */
    val kind: String,
    /** [com.lifeadmin.autopilot.ai.investigation.TurnState] name. */
    val state: String,
    val createdAt: Instant,
    /** JSON of [com.lifeadmin.autopilot.ai.investigation.Turn]. */
    val payload: String,
)

/**
 * One billable API call (planning or research). No foreign key: the ledger outlives tasks and
 * sessions. Holds ids, counts, outcome and the local price estimate only — never prompts, task text,
 * queries or keys.
 */
@Entity(tableName = "usage_records", indices = [Index("startedAt"), Index("sessionId"), Index("providerMessageId")])
data class UsageRecordEntity(
    @PrimaryKey val id: String,
    val feature: String,
    val model: String,
    val startedAt: Instant,
    val runId: String?,
    val sessionId: String?,
    val taskId: String?,
    val stage: String?,
    val durationMs: Long?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheWrite5mTokens: Long?,
    val cacheWrite1hTokens: Long?,
    val cacheReadTokens: Long?,
    val webSearches: Int?,
    val outcome: String,
    val usageStatus: String,
    val estimatedMicros: Long?,
    val priceVersion: String,
    val providerMessageId: String?,
    /** Effort level requested for the call (M4); null when none was sent. */
    @ColumnInfo(defaultValue = "NULL") val effort: String? = null,
)

/**
 * An unsaved draft (PLAN-010/013/014): the user's input, their edits and any paid planning result,
 * kept locally until the user saves or discards it. Never a task, never reminds. No foreign key:
 * a draft outlives nothing and depends on nothing.
 */
@Entity(tableName = "drafts", indices = [Index("status"), Index("updatedAt")])
data class DraftEntity(
    @PrimaryKey val id: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** DRAFT, SAVED or DISCARDED. */
    val status: String,
    /** The raw input, verbatim, for lists and recovery. */
    val text: String,
    /** The task this draft became, once saved. */
    val savedTaskId: String?,
    /** JSON [com.lifeadmin.autopilot.domain.draft.DraftContent]. */
    val payload: String,
)

/**
 * A set of AI-proposed changes against one target (draft or task), with the snapshot it was made
 * against (handoff §4.4). Items apply only through an explicit accept. No foreign key so an
 * obsolete set can still be shown struck through after its target is gone.
 */
@Entity(tableName = "proposal_sets", indices = [Index("targetId"), Index("status"), Index("madeAt")])
data class ProposalSetEntity(
    @PrimaryKey val id: String,
    val source: String,
    val targetKind: String,
    val targetId: String,
    val investigationId: String?,
    val madeAt: Instant,
    val status: String,
    /** JSON [com.lifeadmin.autopilot.domain.proposal.ProposalSet]. */
    val payload: String,
)

/**
 * Provenance of applied content (INT-030–033): kept with the task, survives user edits and the
 * deletion of the investigation it came from. No foreign keys by design.
 */
@Entity(tableName = "provenance_records", indices = [Index("taskId"), Index("draftId"), Index("elementId")])
data class ProvenanceRecordEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val draftId: String?,
    val elementKind: String,
    val elementId: String?,
    val proposalSetId: String,
    val itemId: String,
    val appliedAt: Instant,
    @ColumnInfo(defaultValue = "0") val editedByUser: Boolean = false,
    /** JSON [com.lifeadmin.autopilot.domain.proposal.ProvenanceRecord]. */
    val payload: String,
)
