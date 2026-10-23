package com.lifeadmin.autopilot.data.repository

import androidx.room.withTransaction
import com.lifeadmin.autopilot.ai.research.AppliedMove
import com.lifeadmin.autopilot.ai.research.BriefContent
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.ReminderPolicyEntity
import com.lifeadmin.autopilot.data.local.ResearchBriefEntity
import com.lifeadmin.autopilot.data.local.ReminderTimeSlotEntity
import com.lifeadmin.autopilot.data.local.TaskEntity
import com.lifeadmin.autopilot.data.local.TaskEventEntity
import com.lifeadmin.autopilot.data.local.TaskStepEntity
import com.lifeadmin.autopilot.data.local.encodeMetadata
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: String,
    val appVersion: String = "",
    val tasks: List<TaskDto> = emptyList(),
    val steps: List<StepDto> = emptyList(),
    val policies: List<PolicyDto> = emptyList(),
    val events: List<EventDto> = emptyList(),
    /** Saved research briefs (M3). Older app versions ignore this field. */
    val research: List<ResearchBriefDto> = emptyList(),
    /** Saved research sessions (M3A.2). Older app versions ignore this field. */
    val sessions: List<ResearchSessionDto> = emptyList(),
    /** Local AI usage ledger (M3A.2): counts and estimates only, never prompts or keys. */
    val usage: List<UsageRecordDto> = emptyList(),
    /** Production kickoff M1: drafts, proposal sets and provenance records (schema v6). Optional for older files. */
    val drafts: List<DraftDto> = emptyList(),
    val proposals: List<ProposalSetDto> = emptyList(),
    val provenance: List<ProvenanceDto> = emptyList(),
) {
    companion object {
        const val FORMAT = "life-admin-autopilot-backup"
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class TaskDto(
    val id: String, val title: String, val notes: String? = null, val status: String, val priority: String,
    val createdAt: String, val updatedAt: String, val completedAt: String? = null, val archivedAt: String? = null,
    val deadlineType: String = "NONE", val deadlineLocalDate: String? = null, val deadlineInstant: String? = null,
    val deadlineZoneId: String? = null, val snoozedUntil: String? = null, val snoozeRemindAtEnd: Boolean = true,
    val waitingReason: String? = null, val waitingCheckInAt: String? = null,
    val rev: Int = 0, val eventLocalDate: String? = null, val eventLabel: String? = null, val intendedLocalDate: String? = null,
    val targetLocalDate: String? = null, val targetBasis: String? = null,
)

@Serializable
data class StepDto(
    val id: String, val taskId: String, val sortOrder: Int, val title: String, val isDone: Boolean, val completedAt: String? = null,
    val origin: String = "USER", val provenanceId: String? = null,
)

/** Opaque payloads are encoded by their own codecs (ProposalJson / DraftJson) and validated by decoding on import. */
@Serializable
data class DraftDto(val id: String, val createdAt: String, val updatedAt: String, val status: String, val text: String, val savedTaskId: String? = null, val payload: String)

@Serializable
data class ProposalSetDto(val id: String, val source: String, val targetKind: String, val targetId: String, val investigationId: String? = null, val madeAt: String, val status: String, val payload: String)

@Serializable
data class ProvenanceDto(
    val id: String, val taskId: String? = null, val draftId: String? = null, val elementKind: String, val elementId: String? = null,
    val proposalSetId: String, val itemId: String, val appliedAt: String, val editedByUser: Boolean = false, val payload: String,
)

@Serializable
data class PolicyDto(
    val id: String, val taskId: String, val enabled: Boolean, val kind: String, val startLocalDate: String? = null,
    val zoneMode: String = "FOLLOW_DEVICE", val zoneId: String? = null, val dayInterval: Int = 1,
    val quietHoursBehavior: String? = null, val snoozeDefaultMinutes: Int? = null, val atInstant: String? = null,
    val lastEditedAt: String, val times: List<String> = emptyList(),
)

@Serializable
data class EventDto(val id: String, val taskId: String, val at: String, val type: String, val summary: String, val metadata: Map<String, String> = emptyMap())

/** A research brief as exported: the parsed brief only — never the transcript, request or key. */
@Serializable
data class ResearchBriefDto(
    val id: String, val taskId: String, val createdAt: String, val question: String, val model: String, val status: String,
    val searchCount: Int, val sourceCount: Int, val content: BriefContent, val applied: List<AppliedMove> = emptyList(),
)

/** A research session as exported: parsed content and applied proposals only. */
@Serializable
data class ResearchSessionDto(
    val id: String, val taskId: String, val createdAt: String, val updatedAt: String,
    val content: com.lifeadmin.autopilot.ai.research2.SessionContent,
    val applied: List<com.lifeadmin.autopilot.ai.research2.AppliedProposal> = emptyList(),
)

/** One ledger row as exported. Ids, counts, outcome and the local estimate — nothing else. */
@Serializable
data class UsageRecordDto(
    val id: String, val feature: String, val model: String, val startedAt: String,
    val runId: String? = null, val sessionId: String? = null, val taskId: String? = null, val stage: String? = null,
    val durationMs: Long? = null, val inputTokens: Long? = null, val outputTokens: Long? = null,
    val cacheWrite5mTokens: Long? = null, val cacheWrite1hTokens: Long? = null, val cacheReadTokens: Long? = null, val webSearches: Int? = null,
    val outcome: String, val usageStatus: String, val estimatedMicros: Long? = null, val priceVersion: String = "", val providerMessageId: String? = null,
)

data class ImportResult(val created: Int, val updated: Int, val skipped: Int)

class ImportException(message: String) : Exception(message)

/**
 * User-initiated plaintext JSON backup. Occurrences are not exported: they are a projection
 * that the scheduler regenerates after import (so imports never replay old notifications).
 */
class BackupRepository(
    private val db: AppDatabase,
    private val clock: AppClock,
    private val ids: IdGenerator,
    private val appVersion: String,
    /** Hook for the database→alarms reconciliation after a successful import. */
    private val afterImport: suspend () -> Unit = {},
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }
    private val briefJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }

    suspend fun exportJson(): String {
        val file = db.withTransaction {
            val slots = db.policyDao().getAllSlots().groupBy { it.policyId }
            val drafts = db.draftDao().getAll().map { DraftDto(it.id, it.createdAt.toString(), it.updatedAt.toString(), it.status, it.text, it.savedTaskId, it.payload) }
            val proposals = db.proposalSetDao().getAll().map { ProposalSetDto(it.id, it.source, it.targetKind, it.targetId, it.investigationId, it.madeAt.toString(), it.status, it.payload) }
            val provenance = db.provenanceDao().getAll().map { ProvenanceDto(it.id, it.taskId, it.draftId, it.elementKind, it.elementId, it.proposalSetId, it.itemId, it.appliedAt.toString(), it.editedByUser, it.payload) }
            BackupFile(
                exportedAt = clock.now().toString(),
                appVersion = appVersion,
                tasks = db.taskDao().getAll().map { it.toDto() },
                steps = db.stepDao().getAll().map { StepDto(it.id, it.taskId, it.sortOrder, it.title, it.isDone, it.completedAt?.toString(), it.origin, it.provenanceId) },
                policies = db.policyDao().getAll().map { p -> p.toDto(slots[p.id].orEmpty()) },
                events = db.eventDao().getAll().map {
                    EventDto(it.id, it.taskId, it.at.toString(), it.eventType.name, it.summary, com.lifeadmin.autopilot.data.local.decodeMetadata(it.structuredMetadata))
                },
                research = db.researchDao().getAll().mapNotNull { it.toDto() },
                sessions = db.researchSessionDao().getAll().mapNotNull { it.toDto() },
                usage = db.usageDao().getAll().map { it.toDto() },
                drafts = drafts,
                proposals = proposals,
                provenance = provenance,
            )
        }
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /** Reads at most [MAX_BYTES]; larger inputs are rejected before parsing. */
    fun readBounded(input: InputStream): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw ImportException("Backup file is too large (max ${MAX_BYTES / 1024 / 1024} MB)")
            buffer.write(chunk, 0, n)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    suspend fun importJson(raw: String): ImportResult {
        if (raw.length > MAX_BYTES) throw ImportException("Backup file is too large")
        val file = try {
            json.decodeFromString(BackupFile.serializer(), raw)
        } catch (e: Exception) {
            throw ImportException("Not a valid backup file")
        }
        val validated = validate(file)
        val result = db.withTransaction { write(validated) }
        // The data is committed; always rebuild the alarm projection even if the caller is cancelled.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { afterImport() }
        return result
    }

    private class Validated(
        val tasks: List<TaskEntity>,
        val steps: List<TaskStepEntity>,
        val policies: List<Pair<ReminderPolicyEntity, List<LocalTime>>>,
        val events: List<TaskEventEntity>,
        val research: List<ResearchBriefEntity>,
        val sessions: List<com.lifeadmin.autopilot.data.local.ResearchSessionEntity> = emptyList(),
        val usage: List<com.lifeadmin.autopilot.data.local.UsageRecordEntity> = emptyList(),
        val drafts: List<com.lifeadmin.autopilot.data.local.DraftEntity> = emptyList(),
        val proposals: List<com.lifeadmin.autopilot.data.local.ProposalSetEntity> = emptyList(),
        val provenance: List<com.lifeadmin.autopilot.data.local.ProvenanceRecordEntity> = emptyList(),
    )

    private fun validate(f: BackupFile): Validated {
        if (f.format != BackupFile.FORMAT) throw ImportException("Unrecognized file format")
        if (f.schemaVersion !in 1..BackupFile.SCHEMA_VERSION) throw ImportException("Backup was made by a newer app version (schema ${f.schemaVersion})")
        if (f.tasks.size > MAX_TASKS || f.steps.size > MAX_STEPS || f.events.size > MAX_EVENTS || f.policies.size > MAX_TASKS || f.research.size > MAX_BRIEFS || f.sessions.size > MAX_BRIEFS || f.usage.size > MAX_USAGE ||
            f.drafts.size > MAX_TASKS || f.proposals.size > MAX_BRIEFS || f.provenance.size > MAX_STEPS
        ) {
            throw ImportException("Backup has too many records")
        }
        fun id(v: String, what: String): String {
            if (!ID_PATTERN.matches(v)) throw ImportException("Invalid $what id")
            return v
        }
        fun instant(v: String?, what: String): Instant? = v?.let { runCatching { Instant.parse(it) }.getOrElse { throw ImportException("Invalid $what timestamp") } }
        fun reqInstant(v: String, what: String): Instant = instant(v, what)!!
        fun <E : Enum<E>> enumOf(values: Array<E>, v: String, what: String): E = values.firstOrNull { it.name == v } ?: throw ImportException("Invalid $what")
        fun unique(list: List<String>, what: String) { if (list.size != list.toSet().size) throw ImportException("Duplicate $what ids") }

        unique(f.tasks.map { it.id }, "task"); unique(f.steps.map { it.id }, "step"); unique(f.policies.map { it.id }, "policy"); unique(f.events.map { it.id }, "event")
        unique(f.research.map { it.id }, "research brief"); unique(f.sessions.map { it.id }, "research session"); unique(f.usage.map { it.id }, "usage record")
        unique(f.drafts.map { it.id }, "draft"); unique(f.proposals.map { it.id }, "proposal set"); unique(f.provenance.map { it.id }, "provenance record")
        val taskIds = f.tasks.map { id(it.id, "task") }.toSet()
        if (f.policies.map { it.taskId }.toSet().size != f.policies.size) throw ImportException("More than one reminder policy for a task")

        val tasks = f.tasks.map { t ->
            val title = try { TaskRepository.validateTitle(t.title) } catch (e: ValidationException) { throw ImportException("Invalid task title") }
            val notes = try { TaskRepository.validateNotes(t.notes) } catch (e: ValidationException) { throw ImportException("Task notes too long") }
            val deadlineType = enumOf(DeadlineType.entries.toTypedArray(), t.deadlineType, "deadline type")
            TaskEntity(
                id = t.id, title = title, notes = notes,
                status = enumOf(TaskStatus.entries.toTypedArray(), t.status, "task status"),
                priority = enumOf(Priority.entries.toTypedArray(), t.priority, "priority"),
                createdAt = reqInstant(t.createdAt, "created"), updatedAt = reqInstant(t.updatedAt, "updated"),
                completedAt = instant(t.completedAt, "completed"), archivedAt = instant(t.archivedAt, "archived"),
                deadlineType = deadlineType,
                deadlineLocalDate = t.deadlineLocalDate?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ImportException("Invalid deadline date") } },
                deadlineInstant = instant(t.deadlineInstant, "deadline"),
                deadlineZoneId = t.deadlineZoneId?.also { z -> if (runCatching { ZoneId.of(z) }.isFailure) throw ImportException("Invalid time zone") },
                snoozedUntil = instant(t.snoozedUntil, "snooze"),
                snoozeRemindAtEnd = t.snoozeRemindAtEnd,
                waitingReason = t.waitingReason?.take(TaskRepository.MAX_STEP),
                waitingCheckInAt = instant(t.waitingCheckInAt, "check-in"),
                rev = t.rev.coerceAtLeast(0),
                eventLocalDate = t.eventLocalDate?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ImportException("Invalid event date") } },
                eventLabel = t.eventLabel?.take(TaskRepository.MAX_STEP),
                intendedLocalDate = t.intendedLocalDate?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ImportException("Invalid intended date") } },
                targetLocalDate = t.targetLocalDate?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ImportException("Invalid target date") } },
                targetBasis = t.targetBasis?.take(TaskRepository.MAX_STEP),
            )
        }
        val steps = f.steps.map { s ->
            id(s.id, "step")
            if (s.taskId !in taskIds) throw ImportException("Step refers to a missing task")
            val title = try { TaskRepository.validateStep(s.title) } catch (e: ValidationException) { throw ImportException("Invalid step title") }
            val origin = com.lifeadmin.autopilot.domain.model.ContentOrigin.entries.firstOrNull { it.name == s.origin } ?: throw ImportException("Invalid step origin")
            TaskStepEntity(s.id, s.taskId, s.sortOrder, title, s.isDone, instant(s.completedAt, "step completed"), origin.name, s.provenanceId?.let { id(it, "provenance") })
        }
        val policies = f.policies.map { p ->
            id(p.id, "policy")
            if (p.taskId !in taskIds) throw ImportException("Reminder policy refers to a missing task")
            val times = p.times.map { runCatching { LocalTime.parse(it) }.getOrElse { throw ImportException("Invalid reminder time") } }
            if (times.size > TaskRepository.MAX_TIMES_PER_POLICY) throw ImportException("Too many reminder times")
            if (p.dayInterval !in 1..TaskRepository.MAX_DAY_INTERVAL) throw ImportException("Invalid reminder interval")
            val zoneMode = enumOf(ZoneMode.entries.toTypedArray(), p.zoneMode, "zone mode")
            p.zoneId?.let { z -> if (runCatching { ZoneId.of(z) }.isFailure) throw ImportException("Invalid time zone") }
            ReminderPolicyEntity(
                id = p.id, taskId = p.taskId, enabled = p.enabled,
                kind = enumOf(ReminderKind.entries.toTypedArray(), p.kind, "reminder kind"),
                startLocalDate = p.startLocalDate?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ImportException("Invalid start date") } },
                zoneMode = zoneMode, zoneId = p.zoneId, dayInterval = p.dayInterval,
                quietHoursBehavior = p.quietHoursBehavior?.let { enumOf(QuietHoursBehavior.entries.toTypedArray(), it, "quiet hours behavior") },
                snoozeDefaultMinutes = p.snoozeDefaultMinutes?.takeIf { it in 1..(60 * 24 * 30) },
                atInstant = instant(p.atInstant, "reminder"),
                lastEditedAt = reqInstant(p.lastEditedAt, "policy edited"),
                generation = 1,
            ) to times
        }
        val events = f.events.map { e ->
            id(e.id, "event")
            if (e.taskId !in taskIds) throw ImportException("History entry refers to a missing task")
            if (e.metadata.size > 20) throw ImportException("Invalid history metadata")
            TaskEventEntity(
                e.id, e.taskId, reqInstant(e.at, "history"), enumOf(EventType.entries.toTypedArray(), e.type, "history type"),
                e.summary.take(TaskRepository.MAX_SUMMARY), encodeMetadata(e.metadata.entries.take(20).associate { it.key.take(40) to it.value.take(200) }),
            )
        }
        val research = f.research.map { b ->
            id(b.id, "research brief")
            if (b.taskId !in taskIds) throw ImportException("Research brief refers to a missing task")
            if (b.status !in BRIEF_STATUSES) throw ImportException("Invalid research brief status")
            val payload = briefJson.encodeToString(BriefContent.serializer(), b.content)
            if (payload.length > ResearchConfig.MAX_PAYLOAD_BYTES * 2) throw ImportException("Research brief too large")
            ResearchBriefEntity(
                id = b.id, taskId = b.taskId, createdAt = reqInstant(b.createdAt, "research"), question = b.question.take(ResearchConfig.MAX_QUESTION_CHARS),
                model = b.model.take(80), status = b.status, searchCount = b.searchCount.coerceIn(0, 1000), sourceCount = b.sourceCount.coerceIn(0, 1000),
                payload = payload, applied = briefJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(AppliedMove.serializer()), b.applied.take(50)),
            )
        }
        val sessionRepo = ResearchSessionRepository(db)
        val sessions = f.sessions.map { b ->
            id(b.id, "research session")
            if (b.taskId !in taskIds) throw ImportException("Research session refers to a missing task")
            val payload = sessionRepo.encode(b.content)
            if (payload.length > com.lifeadmin.autopilot.ai.research2.Research2Config.MAX_PAYLOAD_BYTES * 2) throw ImportException("Research session too large")
            com.lifeadmin.autopilot.data.local.ResearchSessionEntity(
                id = b.id, taskId = b.taskId, createdAt = reqInstant(b.createdAt, "research session"), updatedAt = reqInstant(b.updatedAt, "research session"),
                title = b.content.title.take(com.lifeadmin.autopilot.ai.research2.Research2Config.MAX_TITLE_CHARS), template = b.content.template.name, outcome = b.content.outcome.name,
                model = (b.content.latestRun?.model ?: "").take(80), searchCount = b.content.searchesTotal.coerceIn(0, 10_000), sourceCount = b.content.sources.size,
                payload = payload, applied = sessionRepo.encodeApplied(b.applied.take(50)),
            )
        }
        val usage = f.usage.map { u ->
            id(u.id, "usage record")
            enumOf(com.lifeadmin.autopilot.ai.usage.UsageFeature.entries.toTypedArray(), u.feature, "usage feature")
            val outcome = enumOf(com.lifeadmin.autopilot.ai.usage.CallOutcome.entries.toTypedArray(), u.outcome, "usage outcome")
            enumOf(com.lifeadmin.autopilot.ai.usage.UsageStatus.entries.toTypedArray(), u.usageStatus, "usage status")
            com.lifeadmin.autopilot.data.local.UsageRecordEntity(
                u.id, u.feature, u.model.take(80), reqInstant(u.startedAt, "usage"), u.runId?.take(64), u.sessionId?.take(64), u.taskId?.take(64), u.stage?.take(32),
                u.durationMs, u.inputTokens, u.outputTokens, u.cacheWrite5mTokens, u.cacheWrite1hTokens, u.cacheReadTokens, u.webSearches,
                // A call still "in flight" in a backup can never finish: it is interrupted.
                (if (outcome == com.lifeadmin.autopilot.ai.usage.CallOutcome.IN_FLIGHT) com.lifeadmin.autopilot.ai.usage.CallOutcome.INTERRUPTED else outcome).name,
                u.usageStatus, u.estimatedMicros, u.priceVersion.take(32), u.providerMessageId?.take(64),
            )
        }
        val drafts = f.drafts.map { d ->
            id(d.id, "draft")
            val status = com.lifeadmin.autopilot.domain.draft.DraftStatus.entries.firstOrNull { it.name == d.status } ?: throw ImportException("Invalid draft status")
            if (d.payload.length > MAX_PAYLOAD_CHARS) throw ImportException("Draft too large")
            val content = com.lifeadmin.autopilot.domain.draft.DraftJson.decode(d.payload) ?: throw ImportException("Invalid draft")
            com.lifeadmin.autopilot.data.local.DraftEntity(
                d.id, reqInstant(d.createdAt, "draft"), reqInstant(d.updatedAt, "draft"), status.name, content.text.take(DraftRepository.MAX_TEXT),
                d.savedTaskId?.let { id(it, "task") }, com.lifeadmin.autopilot.domain.draft.DraftJson.encode(content),
            )
        }
        val draftIds = drafts.map { it.id }.toSet()
        val proposals = f.proposals.map { p ->
            id(p.id, "proposal set")
            if (p.payload.length > MAX_PAYLOAD_CHARS) throw ImportException("Proposal set too large")
            val set = com.lifeadmin.autopilot.domain.proposal.ProposalJson.decodeSet(p.payload) ?: throw ImportException("Invalid proposal set")
            if (set.id != p.id) throw ImportException("Proposal set id mismatch")
            if (set.targetKind == com.lifeadmin.autopilot.domain.proposal.TargetKind.TASK && set.targetId !in taskIds) throw ImportException("Proposal set refers to a missing task")
            if (set.targetKind == com.lifeadmin.autopilot.domain.proposal.TargetKind.DRAFT && set.targetId !in draftIds) throw ImportException("Proposal set refers to a missing draft")
            com.lifeadmin.autopilot.data.local.ProposalSetEntity(
                set.id, set.source.name, set.targetKind.name, set.targetId, set.investigationId, reqInstant(p.madeAt, "proposal set"), set.status.name,
                com.lifeadmin.autopilot.domain.proposal.ProposalJson.encodeSet(set),
            )
        }
        val provenance = f.provenance.map { r ->
            id(r.id, "provenance record")
            if (r.payload.length > MAX_PAYLOAD_CHARS) throw ImportException("Provenance record too large")
            val record = com.lifeadmin.autopilot.domain.proposal.ProposalJson.decodeRecord(r.payload) ?: throw ImportException("Invalid provenance record")
            if (record.id != r.id) throw ImportException("Provenance record id mismatch")
            if (record.taskId != null && record.taskId !in taskIds) throw ImportException("Provenance record refers to a missing task")
            if (record.draftId != null && record.draftId !in draftIds) throw ImportException("Provenance record refers to a missing draft")
            com.lifeadmin.autopilot.data.local.ProvenanceRecordEntity(
                record.id, record.taskId, record.draftId, record.elementKind.name, record.elementId, record.proposalSetId, record.itemId,
                reqInstant(r.appliedAt, "provenance record"), record.editedByUser, com.lifeadmin.autopilot.domain.proposal.ProposalJson.encodeRecord(record),
            )
        }
        return Validated(tasks, steps, policies, events, research, sessions, usage, drafts, proposals, provenance)
    }

    private suspend fun write(v: Validated): ImportResult {
        var created = 0
        var updated = 0
        var skipped = 0
        val now = clock.now()
        val accepted = mutableSetOf<String>()
        for (t in v.tasks) {
            val existing = db.taskDao().get(t.id)
            when {
                existing == null -> { db.taskDao().insert(t); created++; accepted += t.id }
                existing.updatedAt < t.updatedAt -> { db.taskDao().update(t); updated++; accepted += t.id }
                else -> skipped++
            }
        }
        for (taskId in accepted) db.stepDao().deleteForTask(taskId)
        db.stepDao().upsertAll(v.steps.filter { it.taskId in accepted })
        for ((policy, times) in v.policies.filter { it.first.taskId in accepted }) {
            db.policyDao().getForTask(policy.taskId)?.let { old ->
                // Keep the local policy row id (unique per task) so the ledger stays attached.
                val merged = policy.copy(id = old.id, generation = old.generation + 1)
                db.policyDao().upsert(merged)
                db.policyDao().deleteSlots(old.id)
                db.policyDao().insertSlots(times.mapIndexed { i, t -> ReminderTimeSlotEntity(ids.newId(), old.id, t, i) })
            } ?: run {
                db.policyDao().upsert(policy)
                db.policyDao().insertSlots(times.mapIndexed { i, t -> ReminderTimeSlotEntity(ids.newId(), policy.id, t, i) })
            }
        }
        db.eventDao().insertAll(v.events.filter { it.taskId in accepted })
        for (taskId in accepted) {
            db.eventDao().insert(TaskEventEntity(ids.newId(), taskId, now, EventType.IMPORTED, "Imported from backup", null))
        }
        // Briefs are append-only records: restore every one whose task exists locally (idempotent by id).
        val present = db.taskDao().getAll().map { it.id }.toSet()
        for (brief in v.research) if (brief.taskId in present) db.researchDao().upsert(brief)
        for (session in v.sessions) if (session.taskId in present) db.researchSessionDao().upsert(session)
        // The ledger is append-only: rows already present are never overwritten by a backup.
        // Append-only, and never the same provider response twice under two local ids.
        for (u in v.usage) if (db.usageDao().get(u.id) == null && (u.providerMessageId == null || db.usageDao().byProviderMessage(u.providerMessageId).isEmpty())) db.usageDao().upsert(u)
        // Drafts: newer wins, like tasks. Proposal sets and provenance: the local copy is authoritative once present (its target may have moved on).
        for (d in v.drafts) {
            val local = db.draftDao().get(d.id)
            if (local == null || local.updatedAt < d.updatedAt) db.draftDao().upsert(d)
        }
        val draftsPresent = db.draftDao().getAll().map { it.id }.toSet()
        for (p in v.proposals) {
            val targetPresent = if (p.targetKind == "TASK") p.targetId in present else p.targetId in draftsPresent
            if (targetPresent && db.proposalSetDao().get(p.id) == null) db.proposalSetDao().upsert(p)
        }
        for (r in v.provenance) {
            val targetPresent = (r.taskId == null || r.taskId in present) && (r.draftId == null || r.draftId in draftsPresent)
            if (targetPresent && db.provenanceDao().get(r.id) == null) db.provenanceDao().upsert(r)
        }
        return ImportResult(created, updated, skipped)
    }

    private fun TaskEntity.toDto() = TaskDto(
        id, title, notes, status.name, priority.name, createdAt.toString(), updatedAt.toString(), completedAt?.toString(), archivedAt?.toString(),
        deadlineType.name, deadlineLocalDate?.toString(), deadlineInstant?.toString(), deadlineZoneId, snoozedUntil?.toString(), snoozeRemindAtEnd,
        waitingReason, waitingCheckInAt?.toString(),
        rev, eventLocalDate?.toString(), eventLabel, intendedLocalDate?.toString(), targetLocalDate?.toString(), targetBasis,
    )

    private fun com.lifeadmin.autopilot.data.local.ResearchSessionEntity.toDto(): ResearchSessionDto? {
        val repo = ResearchSessionRepository(db)
        val content = repo.decodeContent(payload) ?: return null
        return ResearchSessionDto(id, taskId, createdAt.toString(), updatedAt.toString(), content, repo.decodeApplied(applied))
    }

    private fun com.lifeadmin.autopilot.data.local.UsageRecordEntity.toDto() = UsageRecordDto(
        id, feature, model, startedAt.toString(), runId, sessionId, taskId, stage, durationMs, inputTokens, outputTokens,
        cacheWrite5mTokens, cacheWrite1hTokens, cacheReadTokens, webSearches, outcome, usageStatus, estimatedMicros, priceVersion, providerMessageId,
    )

    private fun ResearchBriefEntity.toDto(): ResearchBriefDto? = runCatching {
        ResearchBriefDto(
            id, taskId, createdAt.toString(), question, model, status, searchCount, sourceCount,
            briefJson.decodeFromString(BriefContent.serializer(), payload),
            runCatching { briefJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AppliedMove.serializer()), applied) }.getOrDefault(emptyList()),
        )
    }.getOrNull()

    private fun ReminderPolicyEntity.toDto(slots: List<ReminderTimeSlotEntity>) = PolicyDto(
        id, taskId, enabled, kind.name, startLocalDate?.toString(), zoneMode.name, zoneId, dayInterval, quietHoursBehavior?.name,
        snoozeDefaultMinutes, atInstant?.toString(), lastEditedAt.toString(), slots.sortedBy { it.sortOrder }.map { it.localTime.toString() },
    )

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val MAX_TASKS = 5_000
        const val MAX_STEPS = 50_000
        const val MAX_EVENTS = 200_000
        const val MAX_BRIEFS = 20_000
        const val MAX_USAGE = 200_000
        const val MAX_PAYLOAD_CHARS = 512 * 1024
        private val BRIEF_STATUSES = setOf("VERIFIED", "UNVERIFIED", "TRUNCATED")
        private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
