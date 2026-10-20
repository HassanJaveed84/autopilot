package com.lifeadmin.autopilot.data.repository

import androidx.room.withTransaction
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.ReminderPolicyEntity
import com.lifeadmin.autopilot.data.local.ReminderTimeSlotEntity
import com.lifeadmin.autopilot.data.local.TaskEntity
import com.lifeadmin.autopilot.data.local.TaskEventEntity
import com.lifeadmin.autopilot.data.local.TaskStepEntity
import com.lifeadmin.autopilot.data.local.encodeMetadata
import com.lifeadmin.autopilot.data.local.toDomain
import com.lifeadmin.autopilot.data.local.toEntity
import com.lifeadmin.autopilot.domain.model.DeadlineSpec
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.DiagnosticEntry
import com.lifeadmin.autopilot.domain.model.DiagnosticType
import com.lifeadmin.autopilot.reminders.DiagnosticsRecorder
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.PlannedTaskCommand
import com.lifeadmin.autopilot.domain.model.TaskEditCommand
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderOccurrence
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskEvent
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.TaskStep
import com.lifeadmin.autopilot.domain.model.ZoneMode
import com.lifeadmin.autopilot.domain.scheduling.PolicyDescriber
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.domain.usecase.TaskStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class NewTask(
    val title: String,
    val notes: String? = null,
    val priority: Priority = Priority.NORMAL,
    val status: TaskStatus = TaskStatus.INBOX,
    val preset: ReminderPreset? = null,
)

data class TaskSummary(
    val task: Task,
    val stepsDone: Int,
    val stepsTotal: Int,
    val nextStepTitle: String?,
    val nextReminder: ReminderOccurrence?,
    /** The task's reminder policy (read-only here; edited through [TaskRepository.savePolicy]). */
    val policy: ReminderPolicy? = null,
)

data class TaskDetails(
    val task: Task,
    val steps: List<TaskStep>,
    val policy: ReminderPolicy?,
    val nextReminder: ReminderOccurrence?,
    val recentOccurrences: List<ReminderOccurrence>,
    val events: List<TaskEvent>,
)

class ValidationException(message: String) : IllegalArgumentException(message)

/**
 * Room is the single source of truth. Every schedule-affecting mutation runs inside
 * [ScheduleCoordinator.mutateTask] and bumps the policy generation in the same transaction,
 * which invalidates any in-flight alarm broadcast for the previous state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepository(
    private val db: AppDatabase,
    private val clock: AppClock,
    private val ids: IdGenerator,
    private val zoneProvider: () -> ZoneId,
    private val coordinator: ScheduleCoordinator,
    private val diagnostics: DiagnosticsRecorder = DiagnosticsRecorder.None,
) {
    private val taskDao = db.taskDao()
    private val stepDao = db.stepDao()
    private val policyDao = db.policyDao()
    private val occurrenceDao = db.occurrenceDao()
    private val eventDao = db.eventDao()

    // ---------------------------------------------------------------- queries

    fun observeSummaries(): Flow<List<TaskSummary>> = combine(
        taskDao.observeAll(),
        stepDao.observeAll(),
        occurrenceDao.observeWithStatus(ACTIVE),
        policyDao.observeAll(),
        policyDao.observeAllSlots(),
    ) { tasks, steps, occurrences, policies, slots ->
        val stepsByTask = steps.groupBy { it.taskId }
        val nextByTask = occurrences.groupBy { it.taskId }.mapValues { (_, v) -> v.minBy { it.plannedAt } }
        val slotsByPolicy = slots.groupBy { it.policyId }
        val policyByTask = policies.associateBy({ it.taskId }, { it.toDomain(slotsByPolicy[it.id].orEmpty()) })
        tasks.map { t ->
            val s = stepsByTask[t.id].orEmpty()
            TaskSummary(
                task = t.toDomain(),
                stepsDone = s.count { it.isDone },
                stepsTotal = s.size,
                nextStepTitle = s.sortedBy { it.sortOrder }.firstOrNull { !it.isDone }?.title,
                nextReminder = nextByTask[t.id]?.toDomain(),
                policy = policyByTask[t.id],
            )
        }
    }

    fun observeDetails(taskId: String): Flow<TaskDetails?> = combine(
        taskDao.observe(taskId),
        stepDao.observeForTask(taskId),
        policyDao.observeForTask(taskId).flatMapLatest { p ->
            if (p == null) flowOf(null) else policyDao.observeSlots(p.id).let { slots -> combine(flowOf(p), slots) { pe, se -> pe.toDomain(se) } }
        },
        occurrenceDao.observeRecentForTask(taskId, 30),
        eventDao.observeForTask(taskId),
    ) { task, steps, policy, occurrences, events ->
        task ?: return@combine null
        TaskDetails(
            task = task.toDomain(),
            steps = steps.map { it.toDomain() },
            policy = policy,
            nextReminder = occurrences.filter { it.status in ACTIVE }.minByOrNull { it.plannedAt }?.toDomain(),
            recentOccurrences = occurrences.map { it.toDomain() },
            events = events.map { it.toDomain() },
        )
    }

    fun observeActiveOccurrences(): Flow<List<ReminderOccurrence>> =
        occurrenceDao.observeWithStatus(ACTIVE).let { f -> combine(f, flowOf(Unit)) { o, _ -> o.map { it.toDomain() } } }

    suspend fun getTask(taskId: String): Task? = taskDao.get(taskId)?.toDomain()

    suspend fun getPolicy(taskId: String): ReminderPolicy? =
        policyDao.getForTask(taskId)?.let { it.toDomain(policyDao.getSlots(it.id)) }

    suspend fun getSteps(taskId: String): List<TaskStep> = stepDao.getForTask(taskId).map { it.toDomain() }

    // ---------------------------------------------------------------- task lifecycle

    suspend fun createTask(draft: NewTask): String {
        val title = validateTitle(draft.title)
        val notes = validateNotes(draft.notes)
        require(draft.status == TaskStatus.INBOX || draft.status == TaskStatus.ACTIONABLE) { "New tasks start in Inbox or Actionable" }
        val now = clock.now()
        val id = ids.newId()
        return coordinator.mutateTask(id) {
            db.withTransaction {
                taskDao.insert(
                    TaskEntity(
                        id = id, title = title, notes = notes, status = draft.status, priority = draft.priority,
                        createdAt = now, updatedAt = now, completedAt = null, archivedAt = null,
                        deadlineType = DeadlineType.NONE, deadlineLocalDate = null, deadlineInstant = null, deadlineZoneId = null,
                        snoozedUntil = null, waitingReason = null, waitingCheckInAt = null,
                    ),
                )
                var policy = ReminderPolicy(id = ids.newId(), taskId = id, lastEditedAt = now)
                if (draft.preset != null) {
                    policy = ReminderPresets.applyTo(policy, draft.preset, now.atZone(zoneProvider()).toLocalDate())
                }
                writePolicy(policy)
                log(id, now, EventType.CREATED, "Created in ${draft.status.label()}")
                if (draft.preset != null) {
                    log(id, now, EventType.POLICY_EDITED, "Reminders: ${PolicyDescriber.describe(policy)}", mapOf("preset" to draft.preset.name))
                }
            }
            id
        }
    }

    /**
     * Creates a task from the Capture sheet in ONE schedule mutation + Room transaction: the
     * task, its reminder policy (a custom follow-up, the default preset, or none) and its
     * deadline are written together, so a failure anywhere leaves nothing behind and the engine
     * schedules exactly once afterwards. [taskId] is the idempotency key: saving the same capture
     * again (retry after an error, double tap) returns the existing task and writes nothing.
     *
     * @return the task id and whether it was newly created.
     */
    suspend fun createCapturedTask(draft: NewTask, customPolicy: ReminderPolicy?, deadline: DeadlineSpec?, taskId: String? = null): Pair<String, Boolean> {
        val title = validateTitle(draft.title)
        val notes = validateNotes(draft.notes)
        require(draft.status == TaskStatus.INBOX || draft.status == TaskStatus.ACTIONABLE) { "New tasks start in Inbox or Actionable" }
        val d = deadline?.takeIf { it.type != DeadlineType.NONE }
        d?.let { validateDeadline(it.type, it.date, it.instant) }
        customPolicy?.let(::validatePolicy)
        val id = taskId?.takeIf { it.isNotBlank() } ?: ids.newId()
        val now = clock.now()
        val zone = zoneProvider()
        return coordinator.mutateTask(id) {
            db.withTransaction {
                if (taskDao.get(id) != null) return@withTransaction id to false
                taskDao.insert(
                    TaskEntity(
                        id = id, title = title, notes = notes, status = draft.status, priority = draft.priority,
                        createdAt = now, updatedAt = now, completedAt = null, archivedAt = null,
                        deadlineType = d?.type ?: DeadlineType.NONE,
                        deadlineLocalDate = if (d?.type == DeadlineType.DATE_ONLY) d.date else null,
                        deadlineInstant = if (d?.type == DeadlineType.EXACT) d.instant else null,
                        deadlineZoneId = if (d?.type == DeadlineType.EXACT) zone.id else null,
                        snoozedUntil = null, waitingReason = null, waitingCheckInAt = null,
                    ),
                )
                val blank = ReminderPolicy(id = ids.newId(), taskId = id, lastEditedAt = now)
                val policy = when {
                    customPolicy != null -> customPolicy.copy(
                        id = blank.id, taskId = id, lastEditedAt = now, times = customPolicy.times.distinct().sorted(),
                        zoneId = if (customPolicy.zoneMode == ZoneMode.FIXED_ZONE) customPolicy.zoneId ?: zone.id else null,
                    )
                    draft.preset != null -> ReminderPresets.applyTo(blank, draft.preset, now.atZone(zone).toLocalDate())
                    else -> blank
                }
                writePolicy(policy)
                log(id, now, EventType.CREATED, "Created in ${draft.status.label()}")
                if (d != null) log(id, now, EventType.EDITED, "Deadline: ${describeDeadline(d.type, d.date, d.instant, zone)}", mapOf("fields" to "deadline"))
                if (policy.enabled) {
                    log(id, now, EventType.POLICY_EDITED, "Reminders: ${PolicyDescriber.describe(policy)}", draft.preset?.let { mapOf("preset" to it.name) } ?: mapOf("kind" to policy.kind.name))
                }
                id to true
            }
        }
    }

    /**
     * Creates a task from an approved plan in ONE schedule mutation + Room transaction: task,
     * steps, deadline, waiting state and reminder policy are written together, then the engine
     * schedules alarms once. [PlannedTaskCommand.taskId] is the idempotency key — re-applying
     * the same command (double tap, retry after process death) returns the existing task and
     * writes nothing.
     *
     * @return the task id and whether it was newly created.
     */
    suspend fun createPlannedTask(cmd: PlannedTaskCommand): Pair<String, Boolean> {
        val title = validateTitle(cmd.title)
        val notes = validateNotes(cmd.notes)
        val steps = cmd.steps.map(::validateStep)
        require(cmd.status in setOf(TaskStatus.INBOX, TaskStatus.ACTIONABLE, TaskStatus.WAITING)) { "Planned tasks start in Inbox, Actionable or Waiting" }
        if (cmd.taskId.isBlank()) throw ValidationException("Missing plan id")
        val zone = zoneProvider()
        val now = clock.now()
        val policy = cmd.policy?.let { spec ->
            ReminderPolicy(
                id = ids.newId(), taskId = cmd.taskId, enabled = true, kind = spec.kind, times = spec.times.distinct().sorted(),
                dayInterval = spec.dayInterval, startLocalDate = spec.startLocalDate, atInstant = spec.atInstant, lastEditedAt = now,
            ).also(::validatePolicy)
        } ?: ReminderPolicy(id = ids.newId(), taskId = cmd.taskId, lastEditedAt = now)
        cmd.deadline?.let { validateDeadline(it.type, it.date, it.instant) }
        return coordinator.mutateTask(cmd.taskId) {
            db.withTransaction {
                if (taskDao.get(cmd.taskId) != null) return@withTransaction cmd.taskId to false
                val d = cmd.deadline
                taskDao.insert(
                    TaskEntity(
                        id = cmd.taskId, title = title, notes = notes, status = cmd.status, priority = cmd.priority,
                        createdAt = now, updatedAt = now, completedAt = null, archivedAt = null,
                        deadlineType = d?.type ?: DeadlineType.NONE,
                        deadlineLocalDate = if (d?.type == DeadlineType.DATE_ONLY) d.date else null,
                        deadlineInstant = if (d?.type == DeadlineType.EXACT) d.instant else null,
                        deadlineZoneId = if (d?.type == DeadlineType.EXACT) zone.id else null,
                        snoozedUntil = null,
                        waitingReason = if (cmd.status == TaskStatus.WAITING) cmd.waitingReason?.take(MAX_STEP) else null,
                        waitingCheckInAt = if (cmd.status == TaskStatus.WAITING) cmd.waitingCheckInAt else null,
                    ),
                )
                steps.forEachIndexed { i, step -> stepDao.upsert(TaskStepEntity(ids.newId(), cmd.taskId, i, step, false, null)) }
                writePolicy(policy)
                log(cmd.taskId, now, EventType.CREATED, "Created from a reviewed AI plan in ${cmd.status.label()}", mapOf("source" to cmd.source))
                if (steps.isNotEmpty()) log(cmd.taskId, now, EventType.STEP_ADDED, "${steps.size} checklist step(s) from the plan")
                if (d != null) log(cmd.taskId, now, EventType.EDITED, "Deadline: ${describeDeadline(d.type, d.date, d.instant, zone)}", mapOf("fields" to "deadline"))
                if (policy.enabled) log(cmd.taskId, now, EventType.POLICY_EDITED, "Reminders: ${PolicyDescriber.describe(policy, zone)}", mapOf("kind" to policy.kind.name, "source" to cmd.source))
                cmd.taskId to true
            }
        }
    }

    /**
     * Applies the user-selected parts of an AI suggestion to an existing task, atomically. If the
     * task changed after the suggestion was produced ([TaskEditCommand.expectedUpdatedAt]), the
     * edit is rejected instead of overwriting newer data — which also makes a repeated apply a
     * no-op error rather than a duplicate.
     */
    suspend fun applyTaskEdit(cmd: TaskEditCommand, inTransaction: (suspend (addedStepIds: List<String>) -> Unit)? = null) {
        if (cmd.isEmpty) throw ValidationException("Select at least one change to apply")
        val title = cmd.title?.let(::validateTitle)
        val notes = cmd.notes?.let { validateNotes(it) ?: "" }
        val steps = cmd.addSteps.map(::validateStep)
        cmd.deadline?.let { validateDeadline(it.type, it.date, it.instant) }
        val zone = zoneProvider()
        coordinator.mutateTask(cmd.taskId, dismissNotification = cmd.markWaiting) {
            db.withTransaction {
                val t = requireTask(cmd.taskId)
                if (cmd.expectedUpdatedAt != null && t.updatedAt != cmd.expectedUpdatedAt) {
                    throw ValidationException("This task changed after the suggestion was made. Ask again for a fresh suggestion.")
                }
                if (t.status.isTerminal) throw ValidationException("Reopen the task before applying suggestions")
                if (eventDao.getForTask(cmd.taskId).any { it.toDomain().metadata[PROPOSAL_KEY] == cmd.proposalId }) {
                    throw ValidationException("This suggestion has already been applied.")
                }
                val now = clock.now()
                val changed = mutableListOf<String>()
                var updated = t
                if (title != null && title != t.title) { updated = updated.copy(title = title); changed += "title" }
                if (notes != null && notes.ifEmpty { null } != t.notes) { updated = updated.copy(notes = notes.ifEmpty { null }); changed += "notes" }
                if (cmd.priority != null && cmd.priority != t.priority) { updated = updated.copy(priority = cmd.priority); changed += "priority" }
                cmd.deadline?.let { d ->
                    updated = updated.copy(
                        deadlineType = d.type,
                        deadlineLocalDate = if (d.type == DeadlineType.DATE_ONLY) d.date else null,
                        deadlineInstant = if (d.type == DeadlineType.EXACT) d.instant else null,
                        deadlineZoneId = if (d.type == DeadlineType.EXACT) zone.id else null,
                    )
                    changed += "deadline"
                }
                if (cmd.markWaiting) {
                    if (t.status != TaskStatus.WAITING) TaskStateMachine.requireTransition(t.status, TaskStatus.WAITING)
                    updated = updated.copy(status = TaskStatus.WAITING, waitingReason = cmd.waitingReason?.take(MAX_STEP), waitingCheckInAt = cmd.waitingCheckInAt)
                    changed += "status"
                }
                taskDao.update(updated.copy(updatedAt = now))
                val addedStepIds = ArrayList<String>()
                if (steps.isNotEmpty()) {
                    var order = stepDao.maxSortOrder(cmd.taskId)
                    steps.forEach { step ->
                        val id = ids.newId()
                        stepDao.upsert(TaskStepEntity(id, cmd.taskId, ++order, step, false, null))
                        addedStepIds += id
                    }
                    changed += "steps"
                }
                cmd.policy?.let { spec ->
                    val existing = policyDao.getForTask(cmd.taskId)
                    val next = ReminderPolicy(
                        id = existing?.id ?: ids.newId(), taskId = cmd.taskId, enabled = true, kind = spec.kind,
                        times = spec.times.distinct().sorted(), dayInterval = spec.dayInterval, startLocalDate = spec.startLocalDate,
                        atInstant = spec.atInstant, lastEditedAt = now, generation = (existing?.generation ?: 0) + 1,
                        // Keep the user's per-task zone, quiet-hours and snooze overrides.
                        zoneMode = existing?.zoneMode ?: ZoneMode.FOLLOW_DEVICE, zoneId = existing?.zoneId,
                        quietHoursBehavior = existing?.quietHoursBehavior, snoozeDefaultMinutes = existing?.snoozeDefaultMinutes,
                    ).also(::validatePolicy)
                    writePolicy(next)
                    changed += "reminders"
                    log(cmd.taskId, now, EventType.POLICY_EDITED, "Reminders: ${PolicyDescriber.describe(next, zone)}", mapOf("kind" to next.kind.name, "source" to cmd.source))
                }
                // A status change invalidates in-flight alarms; a policy write already bumped it.
                if (cmd.markWaiting && cmd.policy == null) bumpGeneration(cmd.taskId)
                log(
                    cmd.taskId, now, EventType.EDITED, "Applied reviewed AI suggestion: ${changed.joinToString()}",
                    mapOf("fields" to changed.joinToString(","), "source" to cmd.source, PROPOSAL_KEY to cmd.proposalId),
                )
                // Caller bookkeeping (e.g. which research moves were applied) commits or rolls back with the edit.
                inTransaction?.invoke(addedStepIds)
            }
        }
    }

    suspend fun updateDetails(taskId: String, title: String, notes: String?, priority: Priority) {
        val cleanTitle = validateTitle(title)
        val cleanNotes = validateNotes(notes)
        coordinator.mutateTask(taskId) {
            db.withTransaction {
                val t = requireTask(taskId)
                val changed = buildList {
                    if (t.title != cleanTitle) add("title")
                    if (t.notes != cleanNotes) add("notes")
                    if (t.priority != priority) add("priority")
                }
                if (changed.isEmpty()) return@withTransaction
                val now = clock.now()
                taskDao.update(t.copy(title = cleanTitle, notes = cleanNotes, priority = priority, updatedAt = now))
                log(taskId, now, EventType.EDITED, "Edited ${changed.joinToString()}", mapOf("fields" to changed.joinToString(",")))
            }
        }
    }

    suspend fun setDeadline(taskId: String, type: DeadlineType, date: LocalDate?, instant: Instant?) {
        when (type) {
            DeadlineType.NONE -> Unit
            DeadlineType.DATE_ONLY -> requireNotNull(date) { "Date-only deadline needs a date" }
            DeadlineType.EXACT -> requireNotNull(instant) { "Exact deadline needs a time" }
        }
        db.withTransaction {
            val t = requireTask(taskId)
            val now = clock.now()
            val zone = zoneProvider()
            taskDao.update(
                t.copy(
                    deadlineType = type,
                    deadlineLocalDate = if (type == DeadlineType.DATE_ONLY) date else null,
                    deadlineInstant = if (type == DeadlineType.EXACT) instant else null,
                    deadlineZoneId = if (type == DeadlineType.EXACT) zone.id else null,
                    updatedAt = now,
                ),
            )
            val summary = when (type) {
                DeadlineType.NONE -> "Deadline removed"
                DeadlineType.DATE_ONLY -> "Deadline set to $date"
                DeadlineType.EXACT -> "Deadline set to ${instant!!.atZone(zone).toLocalDateTime()}"
            }
            log(taskId, now, EventType.EDITED, summary, mapOf("fields" to "deadline"))
        }
    }

    suspend fun changeStatus(taskId: String, to: TaskStatus, waitingReason: String? = null, checkInAt: Instant? = null) {
        val dismiss = to != TaskStatus.ACTIONABLE && to != TaskStatus.INBOX
        coordinator.mutateTask(taskId, dismissNotification = dismiss) {
            db.withTransaction {
                val t = requireTask(taskId)
                if (t.status == to && to != TaskStatus.WAITING) return@withTransaction
                if (t.status != to) TaskStateMachine.requireTransition(t.status, to)
                val now = clock.now()
                val updated = t.copy(
                    status = to,
                    updatedAt = now,
                    completedAt = if (to.isTerminal) now else null,
                    snoozedUntil = if (to.isTerminal) null else t.snoozedUntil,
                    waitingReason = if (to == TaskStatus.WAITING) waitingReason?.trim()?.take(MAX_STEP)?.ifBlank { null } else null,
                    waitingCheckInAt = if (to == TaskStatus.WAITING) checkInAt else null,
                )
                taskDao.update(updated)
                bumpGeneration(taskId)
                val summary = buildString {
                    append(if (t.status == to) "Waiting details updated" else "${t.status.label()} → ${to.label()}")
                    if (to == TaskStatus.WAITING && checkInAt != null) append(" · check in ${checkInAt.atZone(zoneProvider()).toLocalDateTime()}")
                }
                log(taskId, now, EventType.STATUS_CHANGED, summary, mapOf("from" to t.status.name, "to" to to.name))
            }
        }
    }

    suspend fun complete(taskId: String) = changeStatus(taskId, TaskStatus.DONE)

    suspend fun reopen(taskId: String) {
        coordinator.mutateTask(taskId) {
            db.withTransaction {
                val t = requireTask(taskId)
                require(TaskStateMachine.canReopen(t.status)) { "Only Done or Canceled tasks can be reopened" }
                val now = clock.now()
                taskDao.update(t.copy(status = TaskStatus.ACTIONABLE, completedAt = null, updatedAt = now, snoozedUntil = null))
                bumpGeneration(taskId)
                log(taskId, now, EventType.REOPENED, "Reopened from ${t.status.label()}", mapOf("from" to t.status.name))
            }
        }
    }

    suspend fun snooze(taskId: String, until: Instant, remindAtEnd: Boolean = true) {
        coordinator.mutateTask(taskId, dismissNotification = true) {
            db.withTransaction {
                val t = requireTask(taskId)
                require(!t.status.isTerminal) { "Done or Canceled tasks cannot be snoozed" }
                val now = clock.now()
                require(until > now) { "Snooze must end in the future" }
                taskDao.update(t.copy(snoozedUntil = until, snoozeRemindAtEnd = remindAtEnd, updatedAt = now))
                bumpGeneration(taskId)
                diagnostics.record(
                    DiagnosticEntry(
                        id = ids.newId(), at = now, type = DiagnosticType.SNOOZED, taskId = taskId, plannedAt = until,
                        source = "user", outcome = if (remindAtEnd) "remind_at_end" else "silent_until_end",
                    ),
                )
                log(
                    taskId, now, EventType.SNOOZED,
                    "Snoozed until ${until.atZone(zoneProvider()).toLocalDateTime()}" + if (remindAtEnd) " (remind at end)" else "",
                    mapOf("until" to until.toString(), "remindAtEnd" to remindAtEnd.toString()),
                )
            }
        }
    }

    suspend fun clearSnooze(taskId: String) {
        coordinator.mutateTask(taskId) {
            db.withTransaction {
                val t = requireTask(taskId)
                if (t.snoozedUntil == null) return@withTransaction
                val now = clock.now()
                taskDao.update(t.copy(snoozedUntil = null, updatedAt = now))
                bumpGeneration(taskId)
                diagnostics.record(DiagnosticEntry(id = ids.newId(), at = now, type = DiagnosticType.UNSNOOZED, taskId = taskId, source = "user"))
                log(taskId, now, EventType.SNOOZE_CLEARED, "Snooze cleared")
            }
        }
    }

    suspend fun savePolicy(policy: ReminderPolicy) {
        validatePolicy(policy)
        coordinator.mutateTask(policy.taskId) {
            db.withTransaction {
                requireTask(policy.taskId)
                val existing = policyDao.getForTask(policy.taskId)
                val now = clock.now()
                val next = policy.copy(
                    id = existing?.id ?: policy.id,
                    generation = (existing?.generation ?: 0) + 1,
                    lastEditedAt = now,
                    times = policy.times.distinct().sorted(),
                    zoneId = if (policy.zoneMode == ZoneMode.FIXED_ZONE) policy.zoneId ?: zoneProvider().id else null,
                )
                writePolicy(next)
                touch(policy.taskId, now)
                log(policy.taskId, now, EventType.POLICY_EDITED, "Reminders: ${PolicyDescriber.describe(next)}", mapOf("kind" to next.kind.name, "enabled" to next.enabled.toString()))
            }
        }
    }

    suspend fun archive(taskId: String) {
        coordinator.mutateTask(taskId, dismissNotification = true) {
            db.withTransaction {
                val t = requireTask(taskId)
                if (t.archivedAt != null) return@withTransaction
                val now = clock.now()
                taskDao.update(t.copy(archivedAt = now, updatedAt = now))
                bumpGeneration(taskId)
                log(taskId, now, EventType.ARCHIVED, "Archived")
            }
        }
    }

    suspend fun unarchive(taskId: String) {
        coordinator.mutateTask(taskId) {
            db.withTransaction {
                val t = requireTask(taskId)
                if (t.archivedAt == null) return@withTransaction
                val now = clock.now()
                taskDao.update(t.copy(archivedAt = null, updatedAt = now))
                bumpGeneration(taskId)
                log(taskId, now, EventType.UNARCHIVED, "Restored from archive")
            }
        }
    }

    /** Hard delete; steps, policy, slots, occurrences and events cascade. */
    suspend fun delete(taskId: String) {
        coordinator.mutateTask(taskId, dismissNotification = true) {
            taskDao.delete(taskId)
        }
    }

    // ---------------------------------------------------------------- steps

    suspend fun addStep(taskId: String, title: String): String {
        val clean = validateStep(title)
        return db.withTransaction {
            requireTask(taskId)
            val now = clock.now()
            val id = ids.newId()
            stepDao.upsert(TaskStepEntity(id, taskId, stepDao.maxSortOrder(taskId) + 1, clean, false, null))
            touch(taskId, now)
            log(taskId, now, EventType.STEP_ADDED, "Step added: $clean")
            id
        }
    }

    suspend fun setStepDone(stepId: String, done: Boolean) {
        db.withTransaction {
            val s = stepDao.get(stepId) ?: return@withTransaction
            if (s.isDone == done) return@withTransaction
            val now = clock.now()
            stepDao.upsert(s.copy(isDone = done, completedAt = if (done) now else null))
            touch(s.taskId, now)
            log(s.taskId, now, if (done) EventType.STEP_COMPLETED else EventType.STEP_REOPENED, (if (done) "Step done: " else "Step reopened: ") + s.title)
        }
    }

    suspend fun renameStep(stepId: String, title: String) {
        val clean = validateStep(title)
        db.withTransaction {
            val s = stepDao.get(stepId) ?: return@withTransaction
            if (s.title == clean) return@withTransaction
            stepDao.upsert(s.copy(title = clean))
            touch(s.taskId, clock.now())
        }
    }

    suspend fun deleteStep(stepId: String) {
        db.withTransaction {
            val s = stepDao.get(stepId) ?: return@withTransaction
            stepDao.delete(s)
            val now = clock.now()
            touch(s.taskId, now)
            log(s.taskId, now, EventType.STEP_REMOVED, "Step removed: ${s.title}")
        }
    }

    suspend fun moveStep(stepId: String, delta: Int) {
        db.withTransaction {
            val s = stepDao.get(stepId) ?: return@withTransaction
            val steps = stepDao.getForTask(s.taskId).toMutableList()
            val index = steps.indexOfFirst { it.id == stepId }
            val target = (index + delta).coerceIn(0, steps.lastIndex)
            if (index == target) return@withTransaction
            steps.add(target, steps.removeAt(index))
            stepDao.upsertAll(steps.mapIndexed { i, step -> step.copy(sortOrder = i) })
            touch(s.taskId, clock.now())
        }
    }

    /**
     * Reverts one applied research selection, only while it is still safe: the task must not have
     * changed since the apply ([expectedUpdatedAt]), the steps must still exist unticked, and the
     * notes must still be exactly what the apply wrote. Otherwise nothing changes and false is
     * returned. Reminders, deadline and status are never touched.
     */
    suspend fun undoAppliedEdit(
        taskId: String,
        proposalId: String,
        expectedUpdatedAt: Instant,
        stepIds: List<String>,
        notesAfterApply: String?,
        notesBeforeApply: String?,
        inTransaction: (suspend () -> Unit)? = null,
    ): Boolean = db.withTransaction {
        val t = taskDao.get(taskId) ?: return@withTransaction false
        if (t.updatedAt != expectedUpdatedAt || t.status.isTerminal) return@withTransaction false
        val steps = stepIds.map { stepDao.get(it) ?: return@withTransaction false }
        if (steps.any { it.isDone || it.taskId != taskId }) return@withTransaction false
        if (notesAfterApply != null && (t.notes ?: "") != notesAfterApply) return@withTransaction false
        val now = clock.now()
        steps.forEach { stepDao.delete(it) }
        val notes = if (notesAfterApply != null) validateNotes(notesBeforeApply) else t.notes
        taskDao.update(t.copy(notes = notes, updatedAt = now))
        log(taskId, now, EventType.EDITED, "Undid applied research changes", mapOf("source" to "ai_research_undo", PROPOSAL_KEY to "$proposalId:undo"))
        inTransaction?.invoke()
        true
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun requireTask(taskId: String): TaskEntity =
        taskDao.get(taskId) ?: throw NoSuchElementException("Task not found")

    private suspend fun touch(taskId: String, now: Instant) {
        taskDao.get(taskId)?.let { taskDao.update(it.copy(updatedAt = now)) }
    }

    private suspend fun bumpGeneration(taskId: String) {
        val p = policyDao.getForTask(taskId)
        if (p == null) {
            writePolicy(ReminderPolicy(id = ids.newId(), taskId = taskId, lastEditedAt = clock.now(), generation = 1))
        } else {
            policyDao.bumpGeneration(p.id)
        }
    }

    private suspend fun writePolicy(policy: ReminderPolicy) {
        policyDao.upsert(policy.toEntity())
        policyDao.deleteSlots(policy.id)
        policyDao.insertSlots(policy.times.mapIndexed { i, t -> ReminderTimeSlotEntity(ids.newId(), policy.id, t, i) })
    }

    private suspend fun log(taskId: String, at: Instant, type: EventType, summary: String, metadata: Map<String, String> = emptyMap()) {
        eventDao.insert(TaskEventEntity(ids.newId(), taskId, at, type, summary.take(MAX_SUMMARY), encodeMetadata(metadata)))
    }

    companion object {
        private const val PROPOSAL_KEY = "proposalId"
        const val MAX_TITLE = 300
        const val MAX_NOTES = 10_000
        const val MAX_STEP = 300
        const val MAX_SUMMARY = 400
        const val MAX_TIMES_PER_POLICY = 12
        const val MAX_DAY_INTERVAL = 365
        val ACTIVE = listOf(OccurrenceStatus.PLANNED, OccurrenceStatus.SCHEDULED)

        fun validateTitle(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) throw ValidationException("Title can't be empty")
            if (t.length > MAX_TITLE) throw ValidationException("Title is too long (max $MAX_TITLE characters)")
            return t
        }

        fun validateNotes(raw: String?): String? {
            val n = raw?.trim()?.ifEmpty { null } ?: return null
            if (n.length > MAX_NOTES) throw ValidationException("Notes are too long (max $MAX_NOTES characters)")
            return n
        }

        fun validateStep(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) throw ValidationException("Step can't be empty")
            if (t.length > MAX_STEP) throw ValidationException("Step is too long (max $MAX_STEP characters)")
            return t
        }

        fun validateDeadline(type: DeadlineType, date: LocalDate?, instant: Instant?) {
            when (type) {
                DeadlineType.NONE -> Unit
                DeadlineType.DATE_ONLY -> if (date == null) throw ValidationException("Date-only deadline needs a date")
                DeadlineType.EXACT -> if (instant == null) throw ValidationException("Exact deadline needs a time")
            }
        }

        fun describeDeadline(type: DeadlineType, date: LocalDate?, instant: Instant?, zone: ZoneId): String = when (type) {
            DeadlineType.NONE -> "none"
            DeadlineType.DATE_ONLY -> date.toString()
            DeadlineType.EXACT -> instant!!.atZone(zone).toLocalDateTime().toString()
        }

        fun validatePolicy(p: ReminderPolicy) {
            if (!p.enabled) return
            when (p.kind) {
                ReminderKind.ONCE -> if (p.atInstant == null) throw ValidationException("Pick a date and time for the one-time reminder")
                ReminderKind.DAILY_TIMES, ReminderKind.EVERY_N_DAYS -> {
                    if (p.times.isEmpty()) throw ValidationException("Add at least one reminder time")
                    if (p.times.distinct().size > MAX_TIMES_PER_POLICY) throw ValidationException("At most $MAX_TIMES_PER_POLICY times per day")
                }
            }
            if (p.kind == ReminderKind.EVERY_N_DAYS && p.dayInterval !in 1..MAX_DAY_INTERVAL) {
                throw ValidationException("Interval must be between 1 and $MAX_DAY_INTERVAL days")
            }
            if (p.zoneMode == ZoneMode.FIXED_ZONE && p.zoneId != null && runCatching { ZoneId.of(p.zoneId) }.isFailure) {
                throw ValidationException("Unknown time zone")
            }
        }
    }
}

fun TaskStatus.label(): String = when (this) {
    TaskStatus.INBOX -> "Inbox"
    TaskStatus.ACTIONABLE -> "Actionable"
    TaskStatus.WAITING -> "Waiting"
    TaskStatus.PAUSED -> "Paused"
    TaskStatus.DONE -> "Done"
    TaskStatus.CANCELED -> "Canceled"
}
