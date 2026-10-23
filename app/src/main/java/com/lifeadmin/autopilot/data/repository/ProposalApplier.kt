package com.lifeadmin.autopilot.data.repository

import androidx.room.withTransaction
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.TaskEntity
import com.lifeadmin.autopilot.data.local.TaskStepEntity
import com.lifeadmin.autopilot.data.local.toDomain
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import com.lifeadmin.autopilot.domain.proposal.ApplyChoice
import com.lifeadmin.autopilot.domain.proposal.ApplyRecord
import com.lifeadmin.autopilot.domain.proposal.ApplyResult
import com.lifeadmin.autopilot.domain.proposal.CheckOutcome
import com.lifeadmin.autopilot.domain.proposal.ConflictResolution
import com.lifeadmin.autopilot.domain.proposal.ElementKind
import com.lifeadmin.autopilot.domain.proposal.ItemCheck
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.PolicyRestore
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalReconciler
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.ProvenanceRecord
import com.lifeadmin.autopilot.domain.proposal.SetStatus
import com.lifeadmin.autopilot.domain.proposal.TargetKind
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import com.lifeadmin.autopilot.domain.scheduling.PolicyDescriber
import com.lifeadmin.autopilot.domain.usecase.TaskStateMachine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The one write path from a proposal to a saved task (INT-050, PLAN-110). One Room transaction,
 * per-item reconciliation against the current task (PLAN-073), completed work protected
 * (PLAN-074), user-stated deadlines never changed (OD-6), provenance written with every applied
 * element (INT-030–033), history recording the set and both revisions (INT-054), and a safe undo.
 *
 * Nothing is applied unless the caller passes an explicit [ApplyChoice] for it. There is no
 * default selection here or anywhere else (OD-5).
 */
class ProposalApplier(
    private val db: AppDatabase,
    private val tasks: TaskRepository,
    private val sets: ProposalSetRepository,
    private val clock: AppClock,
    private val ids: IdGenerator,
) {
    private val taskDao = db.taskDao()
    private val stepDao = db.stepDao()
    private val policyDao = db.policyDao()

    /** Reconciles a set against the task as it is now; the review screen renders these checks. */
    suspend fun reconcile(set: ProposalSet): List<ItemCheck> {
        val current = snapshot(set.targetId) ?: return set.pending.map { ItemCheck(it.id, CheckOutcome.BLOCKED, "This task no longer exists") }
        return ProposalReconciler.reconcile(set, current)
    }

    suspend fun snapshot(taskId: String): TargetSnapshot? {
        val t = taskDao.get(taskId) ?: return null
        val steps = stepDao.getForTask(taskId).map { it.toDomain() }
        val policy = policyDao.getForTask(taskId)?.let { it.toDomain(policyDao.getSlots(it.id)) }
        return TargetSnapshot.of(t.toDomain(), steps, policy, clock.now().toEpochMilli())
    }

    suspend fun apply(setId: String, choices: List<ApplyChoice>): ApplyResult {
        val peek = sets.get(setId) ?: return ApplyResult.Rejected("This proposal no longer exists")
        if (peek.targetKind != TargetKind.TASK) return ApplyResult.Rejected("This proposal is for a draft")
        if (peek.status != SetStatus.OPEN) return ApplyResult.Rejected("This proposal is closed")
        val wanted = choices.distinctBy { it.itemId }.filter { peek.item(it.itemId) != null }
        if (wanted.none { it.resolution != ConflictResolution.KEEP_YOURS }) return ApplyResult.Rejected("Select at least one change to apply")
        val dismiss = wanted.any { peek.item(it.itemId)?.kind == ItemKind.SET_STATUS }
        return try {
            tasks.coordinator.mutateTask(peek.targetId, dismissNotification = dismiss) {
                db.withTransaction {
                    // Re-read under the per-task lock and inside the transaction: a concurrent apply of the same
                    // set may have settled items since the caller looked. An item is applied at most once.
                    val set = sets.get(setId) ?: return@withTransaction ApplyResult.Rejected("This proposal no longer exists")
                    if (set.status != SetStatus.OPEN) return@withTransaction ApplyResult.Rejected("These changes were already applied")
                    val chosen = wanted.filter { set.item(it.itemId)?.status == ItemStatus.PENDING }
                    if (chosen.none { it.resolution != ConflictResolution.KEEP_YOURS }) {
                        return@withTransaction ApplyResult.Rejected(if (chosen.size < wanted.size) "These changes were already applied" else "Select at least one change to apply")
                    }
                    applyNow(set, chosen)
                }
            }
        } catch (e: IllegalArgumentException) {
            // ValidationException and state-machine rejections alike: the transaction rolled back, the task is unchanged.
            ApplyResult.Rejected(e.message ?: "Couldn't apply")
        } catch (e: NoSuchElementException) {
            ApplyResult.Rejected("This task no longer exists")
        }
    }

    private suspend fun applyNow(set: ProposalSet, chosen: List<ApplyChoice>): ApplyResult {
        val task = taskDao.get(set.targetId) ?: return ApplyResult.Rejected("This task no longer exists")
        val before = snapshot(task.id)!!
        val checks = ProposalReconciler.reconcile(set, before).associateBy { it.itemId }
        // A conflict the user has not decided, or anything blocked, sends the whole set back to review: nothing is written.
        val undecided = chosen.filter { c ->
            val check = checks[c.itemId] ?: return@filter true
            check.outcome == CheckOutcome.BLOCKED && c.resolution != ConflictResolution.KEEP_YOURS ||
                check.outcome == CheckOutcome.CONFLICT && c.resolution == ConflictResolution.NONE
        }
        if (undecided.isNotEmpty()) return ApplyResult.NeedsReview(checks.values.toList())

        val now = clock.now()
        val zone = tasks.zoneProvider()
        val declined = chosen.filter { it.resolution == ConflictResolution.KEEP_YOURS }.map { it.itemId }
        val toApply = chosen.filter { it.resolution != ConflictResolution.KEEP_YOURS }
        if (toApply.isEmpty()) {
            val updated = set.copy(items = set.items.map { if (it.id in declined) it.copy(status = ItemStatus.DECLINED, statusReason = "Kept yours") else it })
            sets.save(updated.copy(status = closeIfDone(updated)))
            return ApplyResult.Applied(before.rev, before.rev, emptyList(), declined, updated)
        }

        val previousPolicy = policyDao.getForTask(task.id)?.let { it.toDomain(policyDao.getSlots(it.id)) }
        var entity = task
        val steps = stepDao.getForTask(task.id).sortedBy { it.sortOrder }.toMutableList()
        val changed = linkedSetOf<String>()
        val addedStepIds = mutableListOf<String>()
        val records = mutableListOf<ProvenanceRecord>()
        var policyWritten = false

        for (choice in toApply) {
            val item = set.item(choice.itemId)!!
            val value = choice.value ?: item.value
            val recordId = ids.newId()
            var elementId: String? = null
            var appliedText = ""
            when (item.kind) {
                ItemKind.ADD_STEP -> {
                    val v = value as? ItemValue.Step ?: throw ValidationException("A step needs its wording")
                    val text = TaskRepository.validateStep(v.text)
                    val id = ids.newId()
                    val origin = if (v.verify) ContentOrigin.UNVERIFIED else originOf(set, v.origin)
                    val insertAt = v.afterStepId?.let { a -> steps.indexOfFirst { it.id == a } }?.takeIf { it >= 0 }?.plus(1) ?: steps.size
                    steps.add(insertAt, TaskStepEntity(id, task.id, insertAt, text, false, null, origin.name, recordId))
                    addedStepIds += id
                    elementId = id
                    appliedText = text
                    changed += "steps"
                }
                ItemKind.EDIT_STEP -> {
                    val v = value as? ItemValue.Step ?: throw ValidationException("A step needs its wording")
                    val text = TaskRepository.validateStep(v.text)
                    val idx = steps.indexOfFirst { it.id == item.anchorStepId }
                    if (idx < 0) throw ValidationException("That step no longer exists")
                    val origin = if (v.verify) ContentOrigin.UNVERIFIED else originOf(set, v.origin)
                    steps[idx] = steps[idx].copy(title = text, origin = origin.name, provenanceId = recordId)
                    elementId = steps[idx].id
                    appliedText = text
                    changed += "steps"
                }
                ItemKind.REMOVE_STEP -> {
                    val idx = steps.indexOfFirst { it.id == item.anchorStepId }
                    if (idx < 0) throw ValidationException("That step no longer exists")
                    appliedText = steps[idx].title
                    elementId = steps[idx].id
                    stepDao.delete(steps.removeAt(idx))
                    changed += "steps"
                }
                ItemKind.APPEND_NOTE -> {
                    val text = (value as? ItemValue.Text)?.text?.trim().orEmpty()
                    if (text.isEmpty()) throw ValidationException("Nothing to add to the notes")
                    val notes = TaskRepository.validateNotes(listOfNotNull(entity.notes?.takeIf { it.isNotBlank() }, text).joinToString("\n"))
                    entity = entity.copy(notes = notes)
                    appliedText = text
                    changed += "notes"
                }
                ItemKind.SET_NOTES -> {
                    val notes = TaskRepository.validateNotes((value as? ItemValue.Text)?.text)
                    entity = entity.copy(notes = notes)
                    appliedText = notes.orEmpty()
                    changed += "notes"
                }
                ItemKind.SET_TITLE -> {
                    val title = TaskRepository.validateTitle((value as? ItemValue.Text)?.text.orEmpty())
                    entity = entity.copy(title = title)
                    appliedText = title
                    changed += "title"
                }
                ItemKind.SET_DEADLINE -> {
                    // Reconciliation already blocked this when a deadline exists (OD-6); checked again here on purpose.
                    if (entity.deadlineType != DeadlineType.NONE) throw ValidationException("Your deadline is kept")
                    val v = value as? ItemValue.Date ?: throw ValidationException("A deadline needs a date")
                    val date = parseDate(v.date)
                    val time = v.time?.let(::parseTime)
                    entity = if (time == null) {
                        entity.copy(deadlineType = DeadlineType.DATE_ONLY, deadlineLocalDate = date, deadlineInstant = null, deadlineZoneId = null)
                    } else {
                        entity.copy(deadlineType = DeadlineType.EXACT, deadlineLocalDate = null, deadlineInstant = LocalDateTime.of(date, time).atZone(zone).toInstant(), deadlineZoneId = zone.id)
                    }
                    appliedText = listOfNotNull(v.date, v.time).joinToString(" ") + (v.basis.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    changed += "deadline"
                }
                ItemKind.SET_EVENT_DATE -> {
                    val v = value as? ItemValue.Date ?: throw ValidationException("An event needs a date")
                    entity = entity.copy(eventLocalDate = parseDate(v.date), eventLabel = v.label?.trim()?.take(TaskRepository.MAX_STEP)?.ifBlank { null })
                    appliedText = listOfNotNull(v.label, v.date).joinToString(" · ")
                    changed += "event"
                }
                ItemKind.SET_TARGET -> {
                    val v = value as? ItemValue.Date ?: throw ValidationException("A planning target needs a date")
                    val basis = v.basis.trim().take(TaskRepository.MAX_STEP)
                    if (basis.isEmpty()) throw ValidationException("A planning target needs its basis")
                    entity = entity.copy(targetLocalDate = parseDate(v.date), targetBasis = basis)
                    appliedText = "${v.date} · $basis"
                    changed += "target"
                }
                ItemKind.SET_REMINDER -> {
                    val v = value as? ItemValue.Reminder ?: throw ValidationException("A reminder needs a schedule")
                    val next = reminderPolicy(task.id, v, previousPolicy, now, zone)
                    tasks.writePolicy(next)
                    policyWritten = true
                    appliedText = PolicyDescriber.describe(next, zone)
                    changed += "reminders"
                    tasks.log(task.id, now, EventType.POLICY_EDITED, "Reminders: $appliedText", mapOf("kind" to next.kind.name, "source" to sourceKey(set)))
                }
                ItemKind.SET_STATUS -> {
                    val v = value as? ItemValue.Status ?: throw ValidationException("A status change needs a status")
                    val to = TaskStatus.entries.firstOrNull { it.name == v.status } ?: throw ValidationException("Unknown status")
                    if (to.isTerminal) throw ValidationException("Finishing or cancelling a task is yours to do")
                    if (entity.status != to) TaskStateMachine.requireTransition(entity.status, to)
                    entity = entity.copy(
                        status = to,
                        waitingReason = if (to == TaskStatus.WAITING) v.reason?.trim()?.take(TaskRepository.MAX_STEP)?.ifBlank { null } else null,
                        waitingCheckInAt = if (to == TaskStatus.WAITING) v.checkInAt?.let { LocalDateTime.parse(it).atZone(zone).toInstant() } else null,
                    )
                    appliedText = to.name
                    changed += "status"
                }
            }
            records += ProvenanceRecord(
                id = recordId, taskId = task.id, elementKind = elementKindOf(item.kind), elementId = elementId, source = set.source,
                proposalSetId = set.id, itemId = item.id, investigationId = set.investigationId, turnId = set.turnId, appliedAtMs = now.toEpochMilli(),
                src = item.src, appliedText = appliedText.take(TaskRepository.MAX_STEP), model = set.model,
            )
        }

        // Steps: one upsert keeps the order contiguous whether something was inserted or removed.
        if ("steps" in changed) stepDao.upsertAll(steps.mapIndexed { i, s -> s.copy(sortOrder = i) })
        val statusChanged = entity.status != task.status
        tasks.update(entity.copy(updatedAt = now))
        if (statusChanged && !policyWritten) tasks.bumpGeneration(task.id)
        sets.saveProvenance(records)

        val after = snapshot(task.id)!!
        val applied = toApply.map { it.itemId }
        tasks.log(
            task.id, now, EventType.EDITED, "Applied ${applied.size} reviewed ${if (applied.size == 1) "change" else "changes"} from ${sourceLabel(set)}: ${changed.joinToString()}",
            mapOf(
                "fields" to changed.joinToString(","), "source" to sourceKey(set), PROPOSAL_SET_KEY to set.id, "items" to applied.joinToString(","),
                "revBefore" to before.rev.toString(), "revAfter" to after.rev.toString(),
            ),
        )
        val updatedSet = set.copy(
            items = set.items.map {
                when (it.id) {
                    in applied -> it.copy(status = ItemStatus.APPLIED, statusReason = null)
                    in declined -> it.copy(status = ItemStatus.DECLINED, statusReason = "Kept yours")
                    else -> it
                }
            },
            // The original snapshot never changes; `base` is rebased so remaining items reconcile against what the user saw (OD-2).
            base = after,
            lastApply = ApplyRecord(
                atMs = now.toEpochMilli(), revBefore = before.rev, revAfter = after.rev, appliedItemIds = applied, declinedItemIds = declined,
                addedStepIds = addedStepIds, provenanceIds = records.map { it.id }, previous = before, previousPolicy = previousPolicy?.let { PolicyRestore.of(it) },
            ),
        )
        val finalSet = updatedSet.copy(status = closeIfDone(updatedSet))
        sets.save(finalSet)
        return ApplyResult.Applied(before.rev, after.rev, applied, declined, finalSet)
    }

    /** Planning sets close on apply; research sets stay open while anything is still pending (OD-1). */
    private fun closeIfDone(set: ProposalSet): SetStatus = when {
        set.source == ProposalSource.PLANNING -> SetStatus.CLOSED
        set.pending.isEmpty() -> SetStatus.CLOSED
        else -> SetStatus.OPEN
    }

    /**
     * Reverts the last apply of a set while it is still safe: the task's `rev` must be exactly what
     * the apply produced, so no later change (the user's or another apply) can be lost. Returns
     * false and changes nothing otherwise.
     */
    suspend fun undo(setId: String): Boolean {
        val peek = sets.get(setId) ?: return false
        if (peek.lastApply == null) return false
        return tasks.coordinator.mutateTask(peek.targetId) {
            db.withTransaction {
                // Re-read under the lock: the apply record may have been undone or replaced since the caller looked.
                val set = sets.get(setId) ?: return@withTransaction false
                val la = set.lastApply ?: return@withTransaction false
                val task = taskDao.get(set.targetId) ?: return@withTransaction false
                if (task.rev != la.revAfter || task.status.isTerminal) return@withTransaction false
                val now = clock.now()
                val zone = tasks.zoneProvider()
                val kinds = la.appliedItemIds.mapNotNull { set.item(it)?.kind }.toSet()
                val prev = la.previous
                var entity = task
                if (ItemKind.SET_TITLE in kinds) entity = entity.copy(title = prev.title)
                if (ItemKind.SET_NOTES in kinds || ItemKind.APPEND_NOTE in kinds) entity = entity.copy(notes = prev.notes)
                if (ItemKind.SET_DEADLINE in kinds) entity = entity.copy(deadlineType = DeadlineType.NONE, deadlineLocalDate = null, deadlineInstant = null, deadlineZoneId = null)
                if (ItemKind.SET_EVENT_DATE in kinds) entity = entity.copy(eventLocalDate = prev.eventDate?.let(LocalDate::parse), eventLabel = prev.eventLabel)
                if (ItemKind.SET_TARGET in kinds) entity = entity.copy(targetLocalDate = prev.targetDate?.let(LocalDate::parse), targetBasis = prev.targetBasis)
                var statusRestored = false
                if (ItemKind.SET_STATUS in kinds) {
                    val back = TaskStatus.entries.firstOrNull { it.name == prev.status } ?: return@withTransaction false
                    entity = entity.copy(status = back, waitingReason = prev.waitingReason, waitingCheckInAt = prev.waitingCheckInAtMs?.let(Instant::ofEpochMilli))
                    statusRestored = true
                }
                if (ItemKind.ADD_STEP in kinds || ItemKind.EDIT_STEP in kinds || ItemKind.REMOVE_STEP in kinds) {
                    val current = stepDao.getForTask(task.id)
                    for (s in current) if (s.id in la.addedStepIds) stepDao.delete(s)
                    val keep = current.filter { it.id !in la.addedStepIds }.associateBy { it.id }
                    // Restore the previous order, wording and origin of every step that existed before the apply.
                    val restored = prev.steps.map { ps ->
                        val now0 = keep[ps.id]
                        TaskStepEntity(ps.id, task.id, ps.sortOrder, ps.title, now0?.isDone ?: ps.isDone, now0?.completedAt, ps.origin, ps.provenanceId)
                    }
                    stepDao.upsertAll(restored.mapIndexed { i, s -> s.copy(sortOrder = i) })
                }
                var policyRestored = false
                if (ItemKind.SET_REMINDER in kinds) {
                    val existing = policyDao.getForTask(task.id)
                    val restoredPolicy = la.previousPolicy?.let { p ->
                        ReminderPolicy(
                            id = existing?.id ?: p.id, taskId = task.id, enabled = p.enabled, kind = ReminderKind.valueOf(p.kind),
                            startLocalDate = p.startDate?.let(LocalDate::parse), zoneMode = ZoneMode.valueOf(p.zoneMode), zoneId = p.zoneId, dayInterval = p.dayInterval,
                            quietHoursBehavior = p.quietHoursBehavior?.let(QuietHoursBehavior::valueOf), snoozeDefaultMinutes = p.snoozeDefaultMinutes,
                            atInstant = p.atInstantMs?.let(Instant::ofEpochMilli), lastEditedAt = now, generation = (existing?.generation ?: 0) + 1,
                            times = p.times.map(LocalTime::parse),
                        )
                    } ?: existing?.let { e -> e.toDomain(emptyList()).copy(enabled = false, lastEditedAt = now, generation = e.generation + 1, times = emptyList()) }
                    if (restoredPolicy != null) { tasks.writePolicy(restoredPolicy); policyRestored = true }
                }
                tasks.update(entity.copy(updatedAt = now))
                if (statusRestored && !policyRestored) tasks.bumpGeneration(task.id)
                sets.deleteProvenance(la.provenanceIds)
                tasks.log(
                    task.id, now, EventType.EDITED, "Undid ${la.appliedItemIds.size} applied ${if (la.appliedItemIds.size == 1) "change" else "changes"}",
                    mapOf("source" to sourceKey(set) + "_undo", PROPOSAL_SET_KEY to set.id, "revBefore" to task.rev.toString(), "revAfter" to (task.rev + 1).toString()),
                )
                sets.save(
                    set.copy(
                        status = SetStatus.OPEN,
                        items = set.items.map { if (it.id in la.appliedItemIds) it.copy(status = ItemStatus.PENDING, statusReason = null) else it },
                        base = prev.copy(rev = task.rev + 1, takenAtMs = now.toEpochMilli()),
                        lastApply = null,
                    ),
                )
                true
            }
        }
    }

    private fun reminderPolicy(taskId: String, v: ItemValue.Reminder, existing: ReminderPolicy?, now: Instant, zone: ZoneId): ReminderPolicy {
        val kind = ReminderKind.entries.firstOrNull { it.name == v.kind } ?: throw ValidationException("Unknown reminder kind")
        val times = v.times.map(::parseTime).distinct().sorted()
        val onceAt = v.onceAt?.let { LocalDateTime.parse(it).atZone(zone).toInstant() }
        return ReminderPolicy(
            id = existing?.id ?: ids.newId(), taskId = taskId, enabled = true, kind = kind, times = times, dayInterval = v.dayInterval,
            startLocalDate = v.startDate?.let(::parseDate), atInstant = if (kind == ReminderKind.ONCE) onceAt else null, lastEditedAt = now,
            generation = (existing?.generation ?: 0) + 1,
            // Keep the user's per-task zone, quiet-hours and snooze overrides.
            zoneMode = existing?.zoneMode ?: ZoneMode.FOLLOW_DEVICE, zoneId = existing?.zoneId,
            quietHoursBehavior = existing?.quietHoursBehavior, snoozeDefaultMinutes = existing?.snoozeDefaultMinutes,
        ).also(TaskRepository::validatePolicy)
    }

    private fun originOf(set: ProposalSet, requested: ContentOrigin): ContentOrigin = when (set.source) {
        ProposalSource.RESEARCH -> if (requested == ContentOrigin.UNVERIFIED) requested else ContentOrigin.RESEARCH
        ProposalSource.PLANNING -> if (requested == ContentOrigin.USER) ContentOrigin.USER else ContentOrigin.PLANNING
    }

    private fun elementKindOf(kind: ItemKind): ElementKind = when (kind) {
        ItemKind.ADD_STEP, ItemKind.EDIT_STEP, ItemKind.REMOVE_STEP -> ElementKind.STEP
        ItemKind.APPEND_NOTE, ItemKind.SET_NOTES -> ElementKind.NOTES
        ItemKind.SET_TITLE -> ElementKind.TITLE
        ItemKind.SET_DEADLINE -> ElementKind.DEADLINE
        ItemKind.SET_EVENT_DATE -> ElementKind.EVENT_DATE
        ItemKind.SET_TARGET -> ElementKind.TARGET
        ItemKind.SET_REMINDER -> ElementKind.REMINDER
        ItemKind.SET_STATUS -> ElementKind.STATUS
    }

    private fun parseDate(s: String): LocalDate = runCatching { LocalDate.parse(s.trim()) }.getOrElse { throw ValidationException("Invalid date") }
    private fun parseTime(s: String): LocalTime = runCatching { LocalTime.parse(s.trim()) }.getOrElse { throw ValidationException("Invalid time") }
    private fun sourceKey(set: ProposalSet) = if (set.source == ProposalSource.PLANNING) "ai_plan_proposal" else "ai_research_proposal"
    private fun sourceLabel(set: ProposalSet) = if (set.source == ProposalSource.PLANNING) "Plan with AI" else "Research Online"

    companion object {
        const val PROPOSAL_SET_KEY = "proposalSetId"
    }
}
