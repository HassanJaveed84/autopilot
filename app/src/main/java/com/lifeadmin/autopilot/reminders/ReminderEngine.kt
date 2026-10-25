package com.lifeadmin.autopilot.reminders

import androidx.room.withTransaction
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.ReminderOccurrenceEntity
import com.lifeadmin.autopilot.data.local.ReminderPolicyEntity
import com.lifeadmin.autopilot.data.local.TaskEntity
import com.lifeadmin.autopilot.data.local.TaskEventEntity
import com.lifeadmin.autopilot.data.local.encodeMetadata
import com.lifeadmin.autopilot.data.local.toDomain
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.scheduling.SnoozeCalculator
import com.lifeadmin.autopilot.domain.model.DiagnosticEntry
import com.lifeadmin.autopilot.domain.model.DiagnosticType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import com.lifeadmin.autopilot.domain.scheduling.PolicySlotGenerator
import com.lifeadmin.autopilot.domain.scheduling.ReminderPlanner
import com.lifeadmin.autopilot.domain.scheduling.RoutineCycle
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.notifications.NotificationPublisher
import com.lifeadmin.autopilot.notifications.PostResult
import com.lifeadmin.autopilot.notifications.ReminderNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class AlarmOutcome { DELIVERED, SUPPRESSED, MISSED, CANCELED, DUPLICATE, STALE, NOT_FOUND, RESCHEDULED }

data class ReconcileReport(
    val reason: String,
    val at: Instant,
    val scheduled: Int,
    val exact: Int,
    val notHandedToOs: Int,
    val lateDelivered: Int,
    val coalesced: Int,
    val missed: Int,
) {
    fun summary(): String = buildString {
        append("$reason: $scheduled scheduled")
        if (exact > 0) append(" ($exact exact)")
        if (notHandedToOs > 0) append(", $notHandedToOs pending retry")
        if (lateDelivered > 0) append(", $lateDelivered late delivered")
        if (coalesced > 0) append(", $coalesced combined into a summary")
        if (missed > 0) append(", $missed missed")
    }
}

/**
 * The scheduler. **The database is authoritative; OS alarms are a disposable projection of it.**
 *
 * - Exactly one future occurrence per task is materialised and handed to [AlarmDriver].
 * - Every mutation, alarm delivery and reconciliation runs under one process-wide [Mutex], and
 *   delivery claims are compare-and-set updates, so duplicate/stale broadcasts never double-notify.
 * - Occurrence identity is `(policyId, plannedAt)`; a planned instant that was already delivered,
 *   suppressed or missed is never planned again (idempotent across clock changes and restarts).
 */
class ReminderEngine(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val alarms: AlarmDriver,
    private val notifications: NotificationPublisher,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val ids: IdGenerator,
    private val diagnostics: DiagnosticsRecorder = DiagnosticsRecorder.None,
    private val environment: EnvironmentProbe = EnvironmentProbe.UNKNOWN,
) : ScheduleCoordinator {

    private val mutex = Mutex()

    /** All engine work (Room + AlarmManager + NotificationManager) runs off the main thread. */
    // NonCancellable: once started, a mutation/delivery/reconcile always runs to completion, so a
    // cancelled caller (e.g. a receiver's scope) can never leave a claimed-but-unposted reminder,
    // a committed-but-unreconciled Done, or a half-finished boot reconcile.
    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(Dispatchers.Default + NonCancellable) { mutex.withLock { block() } }
    private val taskDao = db.taskDao()
    private val policyDao = db.policyDao()
    private val occurrenceDao = db.occurrenceDao()
    private val stepDao = db.stepDao()
    private val eventDao = db.eventDao()

    /** [source] says what triggered this unit of work: "alarm", "mutation" or "reconcile:<reason>". */
    private class Ctx(val now: Instant, val settings: AppSettings, val zone: ZoneId, val source: String) {
        val isRecovery: Boolean get() = source.removePrefix("reconcile:") in RECOVERY_REASONS
    }

    private class CatchUp {
        val late = mutableListOf<ReminderOccurrenceEntity>()
        var missed = 0
    }

    // ------------------------------------------------------------------ public API

    override suspend fun <T> mutateTask(taskId: String, dismissNotification: Boolean, block: suspend () -> T): T = locked {
        val before = occurrenceDao.getForTaskWithStatus(taskId, ACTIVE)
        val result = block()
        if (dismissNotification) notifications.cancelForTask(taskId)
        reconcileTaskLocked(taskId, before, ctx("mutation"), catchUp = null)
        result
    }

    suspend fun reconcileTask(taskId: String) = locked { reconcileTaskLocked(taskId, emptyList(), ctx("mutation"), null) }

    /** Full rebuild: app start, boot, package update, time/zone change, permission change, periodic. */
    suspend fun reconcileAll(reason: String): ReconcileReport = locked {
        val c = ctx("reconcile:$reason")
        val catchUp = CatchUp()
        val tasks = taskDao.getAll()
        for (t in tasks) reconcileTaskLocked(t.id, emptyList(), c, catchUp)

        var lateDelivered = 0
        var coalesced = 0
        if (catchUp.late.size <= MAX_INDIVIDUAL_LATE) {
            for (occ in catchUp.late) {
                val task = taskDao.get(occ.taskId) ?: continue
                if (deliverLocked(occ, task, c, late = true) == AlarmOutcome.DELIVERED) lateDelivered++
            }
        } else {
            // Avoid a notification storm: one summary instead of many late reminders.
            val health = notifications.health()
            val canPost = c.settings.allowReminderNotifications && health.canPostReminders && health.summaryChannelEnabled
            for (occ in catchUp.late) {
                val status = if (canPost) OccurrenceStatus.DELIVERED else OccurrenceStatus.SUPPRESSED
                occurrenceDao.update(occ.copy(status = status, deliveredAt = if (canPost) c.now else null, note = if (canPost) "coalesced" else "notifications_blocked"))
                // POSTED rows are written only once the summary has actually been posted (below).
                if (!canPost) diag(DiagnosticType.SUPPRESSED, c, occ, outcome = "notifications_blocked", withLateness = true)
                log(occ.taskId, c.now, if (canPost) EventType.REMINDER_DELIVERED else EventType.REMINDER_SUPPRESSED,
                    if (canPost) "Late reminder included in a summary" else "Late reminder not shown (notifications blocked)")
            }
            coalesced = catchUp.late.size
        }
        if ((coalesced > 0 || catchUp.missed > 0) && c.settings.allowReminderNotifications) {
            val body = buildString {
                if (coalesced > 0) append("$coalesced reminders came due while the app couldn't run. ")
                if (catchUp.missed > 0) append("${catchUp.missed} older reminders were skipped. ")
                append("Open Today to review.")
            }
            val posted = notifications.postSummary("Reminders while you were away", body) == PostResult.POSTED
            if (posted && coalesced > 0) {
                for (occ in catchUp.late) {
                    occurrenceDao.get(occ.id)?.takeIf { it.status == OccurrenceStatus.DELIVERED }
                        ?.let { diag(DiagnosticType.POSTED, c, it, outcome = "in_summary", withLateness = true) }
                }
            }
            if (!posted && coalesced > 0) {
                // Never claim delivery that didn't happen.
                for (occ in catchUp.late) {
                    occurrenceDao.get(occ.id)?.takeIf { it.status == OccurrenceStatus.DELIVERED }?.let {
                        occurrenceDao.update(it.copy(status = OccurrenceStatus.SUPPRESSED, deliveredAt = null, note = "summary_not_shown"))
                        diag(DiagnosticType.POST_FAILED, c, it, outcome = "summary_not_shown", withLateness = true)
                    }
                }
            }
        }
        // Tasks whose late occurrence was just settled still have their next one planned.
        if (catchUp.late.isNotEmpty()) catchUp.late.map { it.taskId }.distinct().forEach { reconcileTaskLocked(it, emptyList(), c, null) }

        val active = occurrenceDao.getWithStatus(ACTIVE)
        val report = ReconcileReport(
            reason = reason, at = c.now,
            scheduled = active.count { it.status == OccurrenceStatus.SCHEDULED },
            exact = active.count { it.status == OccurrenceStatus.SCHEDULED && it.exact },
            notHandedToOs = active.count { it.status == OccurrenceStatus.PLANNED },
            lateDelivered = lateDelivered, coalesced = coalesced, missed = catchUp.missed,
        )
        settings.update { it.copy(lastReconcileAt = c.now, lastReconcileSummary = report.summary()) }
        diagnostics.record(
            DiagnosticEntry(
                id = ids.newId(), at = c.now, type = DiagnosticType.RECONCILE, source = c.source,
                outcome = "scheduled=${report.scheduled} exact=${report.exact} pending=${report.notHandedToOs} late=${report.lateDelivered} coalesced=${report.coalesced} missed=${report.missed}",
                detail = runCatching { environment.snapshot().compact() }.getOrNull(),
            ),
        )
        report
    }

    /**
     * Alarm broadcast entry point. Safe to call any number of times for the same occurrence.
     * [receivedAt] is captured by the receiver before any locking, so the ledger shows when the OS
     * actually delivered the broadcast even if other engine work (e.g. app-start catch-up) ran first.
     */
    suspend fun onAlarm(occurrenceId: String, generation: Int, receivedAt: Instant = clock.now()): AlarmOutcome {
        val seen = runCatching { occurrenceDao.get(occurrenceId) }.getOrNull()
        diagnostics.record(
            DiagnosticEntry(
                id = ids.newId(), at = receivedAt, type = DiagnosticType.ALARM_RECEIVED, occurrenceId = occurrenceId,
                taskId = seen?.taskId, plannedAt = seen?.plannedAt, triggerAt = seen?.plannedAt, source = "alarm",
                exact = seen?.exact, lateness = seen?.let { Duration.between(it.plannedAt, receivedAt) },
                outcome = seen?.status?.name?.lowercase() ?: "unknown_occurrence",
            ),
        )
        return locked {
            val c = ctx("alarm")
            val occ = occurrenceDao.get(occurrenceId)
            val ignored: AlarmOutcome? = when {
                occ == null -> AlarmOutcome.NOT_FOUND
                occ.status !in ACTIVE -> AlarmOutcome.DUPLICATE
                occ.generation != generation -> {
                    // A newer schedule superseded this broadcast; make sure the current one is registered.
                    reconcileTaskLocked(occ.taskId, emptyList(), c, null)
                    AlarmOutcome.STALE
                }
                taskDao.get(occ.taskId) == null -> AlarmOutcome.NOT_FOUND
                occ.plannedAt > c.now.plus(EARLY_TOLERANCE) -> {
                    // Wall clock moved backwards after scheduling; re-derive instead of delivering early.
                    reconcileTaskLocked(occ.taskId, emptyList(), c, null)
                    AlarmOutcome.RESCHEDULED
                }
                else -> null
            }
            if (ignored != null) {
                diagnostics.record(
                    DiagnosticEntry(
                        id = ids.newId(), at = c.now, type = DiagnosticType.ALARM_IGNORED, occurrenceId = occurrenceId,
                        taskId = occ?.taskId, plannedAt = occ?.plannedAt, source = "alarm", outcome = ignored.name.lowercase(),
                        detail = occ?.let { "status=${it.status.name.lowercase()} gen=${it.generation} broadcastGen=$generation" },
                    ),
                )
                return@locked ignored
            }
            val task = taskDao.get(occ!!.taskId)!!
            val outcome = deliverLocked(occ, task, c, late = Duration.between(occ.plannedAt, c.now) > LATE_LABEL)
            reconcileTaskLocked(task.id, emptyList(), c, null)
            outcome
        }
    }

    /** Next active occurrence across all tasks (for the health panel). */
    suspend fun nextScheduled(): ReminderOccurrenceEntity? = occurrenceDao.getWithStatus(ACTIVE).minByOrNull { it.plannedAt }

    suspend fun pruneLedger(olderThan: Duration = Duration.ofDays(90)): Int = locked {
        occurrenceDao.pruneSettledBefore(clock.now().minus(olderThan))
    }

    // ------------------------------------------------------------------ core

    private suspend fun ctx(source: String) = Ctx(clock.now(), settings.current(), zoneProvider.current(), source)

    private suspend fun diag(
        type: DiagnosticType,
        c: Ctx,
        occ: ReminderOccurrenceEntity,
        outcome: String? = null,
        detail: String? = null,
        withLateness: Boolean = false,
        exact: Boolean? = occ.exact,
        triggerAt: Instant? = null,
    ) {
        diagnostics.record(
            DiagnosticEntry(
                id = ids.newId(), at = c.now, type = type, occurrenceId = occ.id, taskId = occ.taskId,
                plannedAt = occ.plannedAt, triggerAt = triggerAt, source = c.source, exact = exact,
                lateness = if (withLateness) Duration.between(occ.plannedAt, c.now) else null,
                outcome = outcome, detail = detail,
            ),
        )
    }

    /** Machine-readable reason an occurrence is no longer wanted (for the CANCELED ledger entry). */
    private fun cancelReason(task: TaskEntity, policy: ReminderPolicy, occ: ReminderOccurrenceEntity): String = when {
        task.status == TaskStatus.DONE -> "task_done"
        task.status == TaskStatus.CANCELED -> "task_canceled"
        task.archivedAt != null -> "task_archived"
        task.status == TaskStatus.PAUSED -> "task_paused"
        occ.kind == OccurrenceKind.POLICY && task.status == TaskStatus.WAITING -> "task_waiting"
        occ.kind == OccurrenceKind.POLICY && !policy.enabled -> "reminders_off"
        occ.kind == OccurrenceKind.POLICY && (task.snoozedUntil?.let { it > occ.slotAt } ?: false) -> "snoozed"
        occ.kind == OccurrenceKind.POLICY && policy.isRoutine && (task.cycleDoneUntil?.let { it > occ.slotAt } ?: false) -> "done_for_now"
        occ.kind == OccurrenceKind.SNOOZE_END && task.snoozedUntil != occ.slotAt -> "snooze_changed"
        occ.kind == OccurrenceKind.CHECK_IN && task.waitingCheckInAt != occ.slotAt -> "check_in_changed"
        else -> "schedule_changed"
    }

    private suspend fun reconcileTaskLocked(taskId: String, before: List<ReminderOccurrenceEntity>, c: Ctx, catchUp: CatchUp?) {
        val taskEntity = taskDao.get(taskId)
        if (taskEntity == null) {
            // Deleted: occurrences cascaded away, so cancel alarms using the pre-mutation snapshot.
            before.forEach { alarms.cancel(it.id) }
            notifications.cancelForTask(taskId)
            return
        }
        val policyEntity = policyDao.getForTask(taskId) ?: ReminderPolicyEntity(
            id = ids.newId(), taskId = taskId, enabled = false, kind = ReminderKind.DAILY_TIMES, startLocalDate = null,
            zoneMode = ZoneMode.FOLLOW_DEVICE, zoneId = null, dayInterval = 1, quietHoursBehavior = null,
            snoozeDefaultMinutes = null, atInstant = null, lastEditedAt = c.now, generation = 0,
        ).also { policyDao.upsert(it) }
        val policy = policyEntity.toDomain(policyDao.getSlots(policyEntity.id))
        val task = taskEntity.toDomain()

        var active = occurrenceDao.getForTaskWithStatus(taskId, ACTIVE)
        (before.map { it.id }.toSet() - active.map { it.id }.toSet()).forEach { alarms.cancel(it) }

        // 1) Occurrences whose time has come.
        for (occ in active.filter { it.plannedAt <= c.now }) {
            val eligible = isEligible(occ, taskEntity, policy, c)
            val overdueBy = Duration.between(occ.plannedAt, c.now)
            when {
                !eligible -> cancel(occ, c, cancelReason(taskEntity, policy, occ))
                overdueBy <= IN_FLIGHT_TOLERANCE -> {
                    // Presumably in flight; re-register anyway in case the OS dropped it (reboot,
                    // force-stop). A duplicate broadcast is ignored by the delivery claim.
                    val r = alarms.schedule(AlarmRequest(occ.id, taskId, occ.generation, occ.plannedAt, preferExact = c.settings.precisionMode))
                    if (r.scheduled) {
                        if (r.exact != occ.exact) occurrenceDao.update(occ.copy(exact = r.exact))
                        diag(DiagnosticType.SCHEDULED, c, occ, outcome = "reregistered_due", exact = r.exact, triggerAt = occ.plannedAt)
                    } else {
                        diag(DiagnosticType.SCHEDULE_FAILED, c, occ, outcome = r.error ?: "refused", exact = r.exact, triggerAt = occ.plannedAt)
                    }
                }
                overdueBy > Duration.ofMinutes(c.settings.graceWindowMinutes.toLong()) -> {
                    occurrenceDao.update(occ.copy(status = OccurrenceStatus.MISSED, note = "beyond_grace"))
                    alarms.cancel(occ.id)
                    diag(DiagnosticType.MISSED, c, occ, outcome = "beyond_grace", withLateness = true)
                    log(taskId, c.now, EventType.REMINDER_MISSED, "Reminder missed — ${lateText(overdueBy)} overdue when the app could next run (phone off, app stopped or alarm not delivered)", mapOf("plannedAt" to occ.plannedAt.toString(), "via" to c.source))
                    catchUp?.let { it.missed++ }
                }
                catchUp != null -> catchUp.late += occ
                else -> deliverLocked(occ, taskEntity, c, late = overdueBy > LATE_LABEL)
            }
        }

        // 2) Plan the single next occurrence.
        val settled = occurrenceDao.getSettledPlannedAfter(policy.id, c.now).toSet()
        val plan = ReminderPlanner.next(task, policy, c.settings, c.now, c.zone) { it in settled }
        active = occurrenceDao.getForTaskWithStatus(taskId, ACTIVE)
        for (occ in active) {
            if (occ.plannedAt <= c.now) continue // handled above / in flight
            if (plan == null || occ.plannedAt != plan.plannedAt) {
                val reason = if (plan == null || !isEligible(occ, taskEntity, policy, c)) cancelReason(taskEntity, policy, occ) else "superseded"
                cancel(occ, c, reason)
            }
        }
        if (plan == null) {
            // Closed or paused tasks must not keep a stale reminder on screen (e.g. after import).
            if (taskEntity.status.isTerminal || taskEntity.archivedAt != null) notifications.cancelForTask(taskId)
            return
        }

        val existing = occurrenceDao.findByPlanned(policy.id, plan.plannedAt)
        val occ = when {
            existing == null -> ReminderOccurrenceEntity(
                id = ids.newId(), policyId = policy.id, taskId = taskId, kind = plan.kind,
                plannedAt = plan.plannedAt, slotAt = plan.slotAt, scheduledAt = null, deliveredAt = null,
                status = OccurrenceStatus.PLANNED, generation = policy.generation,
            ).also { occurrenceDao.insert(it) }
            existing.status.isSettled -> return // defensive: planner excludes settled instants
            else -> existing.copy(kind = plan.kind, slotAt = plan.slotAt, generation = policy.generation, deliveredAt = null)
        }
        val result = alarms.schedule(AlarmRequest(occ.id, taskId, policy.generation, plan.plannedAt, preferExact = c.settings.precisionMode))
        val wasScheduled = existing != null && existing.status == OccurrenceStatus.SCHEDULED
        val change = when {
            !result.scheduled -> null
            existing == null || existing.status != OccurrenceStatus.SCHEDULED -> "new"
            existing.exact != result.exact -> "mode_changed"
            existing.generation != policy.generation -> "updated"
            c.isRecovery -> "recovered"
            else -> null // idempotent re-registration of an unchanged alarm: not worth a ledger row
        }
        if (!result.scheduled) {
            diag(DiagnosticType.SCHEDULE_FAILED, c, occ, outcome = result.error ?: "refused", exact = false, triggerAt = plan.plannedAt)
        } else if (change != null) {
            val notes = listOfNotNull(
                if (plan.deferred) "quiet_hours_deferred_from=${plan.slotAt}" else null,
                result.error, // e.g. exact_unavailable when precise timing is on but not permitted
                if (wasScheduled && change == "recovered") "re-registered after ${c.source}" else null,
            ).joinToString(" ").ifEmpty { null }
            diag(DiagnosticType.SCHEDULED, c, occ.copy(plannedAt = plan.plannedAt), outcome = change, detail = notes, exact = result.exact, triggerAt = plan.plannedAt)
        }
        occurrenceDao.update(
            occ.copy(
                status = if (result.scheduled) OccurrenceStatus.SCHEDULED else OccurrenceStatus.PLANNED,
                scheduledAt = if (result.scheduled) c.now else null,
                exact = result.exact,
                note = result.error,
            ),
        )
    }

    private suspend fun cancel(occ: ReminderOccurrenceEntity, c: Ctx, reason: String) {
        alarms.cancel(occ.id)
        occurrenceDao.update(occ.copy(status = OccurrenceStatus.CANCELED))
        diag(DiagnosticType.CANCELED, c, occ, outcome = reason)
    }

    /** Re-validates an occurrence against current durable state; notification extras are never trusted. */
    private fun isEligible(occ: ReminderOccurrenceEntity, task: TaskEntity, policy: ReminderPolicy, c: Ctx): Boolean {
        if (task.archivedAt != null || task.status.isTerminal || task.status == TaskStatus.PAUSED) return false
        return when (occ.kind) {
            OccurrenceKind.POLICY -> {
                val statusOk = task.status == TaskStatus.ACTIONABLE || task.status == TaskStatus.INBOX
                val snoozedOver = task.snoozedUntil?.let { it > occ.slotAt } ?: false
                val cycleDone = policy.isRoutine && (task.cycleDoneUntil?.let { it > occ.slotAt } ?: false)
                statusOk && policy.enabled && !snoozedOver && !cycleDone && policyStillProduces(policy, occ.slotAt, c)
            }
            OccurrenceKind.CHECK_IN -> task.status == TaskStatus.WAITING && task.waitingCheckInAt == occ.slotAt
            OccurrenceKind.SNOOZE_END -> task.snoozeRemindAtEnd && task.snoozedUntil == occ.slotAt
        }
    }

    private fun policyStillProduces(policy: ReminderPolicy, slot: Instant, c: Ctx): Boolean {
        val zone = ReminderPlanner.policyZone(policy, c.zone)
        return PolicySlotGenerator.slotsAfter(policy, zone, slot.minusMillis(1)).firstOrNull() == slot
    }

    private suspend fun deliverLocked(occ: ReminderOccurrenceEntity, task: TaskEntity, c: Ctx, late: Boolean): AlarmOutcome {
        // Whatever the outcome, this occurrence's OS alarm is spent; release its PendingIntent.
        alarms.cancel(occ.id)
        val policy = policyDao.get(occ.policyId)?.let { it.toDomain(policyDao.getSlots(it.id)) }
            ?: return AlarmOutcome.NOT_FOUND.also { cancel(occ, c, "policy_missing") }
        if (!isEligible(occ, task, policy, c)) {
            cancel(occ, c, cancelReason(task, policy, occ))
            return AlarmOutcome.CANCELED
        }
        val lateness = Duration.between(occ.plannedAt, c.now)
        if (lateness > Duration.ofMinutes(c.settings.graceWindowMinutes.toLong())) {
            occurrenceDao.update(occ.copy(status = OccurrenceStatus.MISSED, note = "beyond_grace"))
            diag(DiagnosticType.MISSED, c, occ, outcome = "beyond_grace", withLateness = true)
            log(task.id, c.now, EventType.REMINDER_MISSED, "Reminder missed — ${lateText(lateness)} late, beyond the grace window", mapOf("plannedAt" to occ.plannedAt.toString(), "via" to c.source))
            return AlarmOutcome.MISSED
        }
        val env = runCatching { environment.snapshot() }.getOrNull()
        val health = notifications.health()
        val blockReason = when {
            !c.settings.allowReminderNotifications -> "turned_off_in_app"
            !health.permissionGranted -> "permission_denied"
            !health.appNotificationsEnabled -> "app_notifications_off"
            !health.reminderChannelEnabled -> "channel_off"
            occurrenceDao.countDeliveredSince(c.now.minus(Duration.ofHours(24))) >= c.settings.maxNotificationsPerDay -> "daily_limit"
            else -> null
        }
        if (blockReason != null) {
            occurrenceDao.update(occ.copy(status = OccurrenceStatus.SUPPRESSED, note = blockReason))
            diag(DiagnosticType.SUPPRESSED, c, occ, outcome = blockReason, detail = env?.compact(), withLateness = true)
            log(task.id, c.now, EventType.REMINDER_SUPPRESSED, "Reminder not shown: ${blockReason.replace('_', ' ')}", mapOf("reason" to blockReason))
            return AlarmOutcome.SUPPRESSED
        }
        val claimed = db.withTransaction {
            occurrenceDao.compareAndSetStatus(occ.id, occ.status, OccurrenceStatus.DELIVERED, occ.generation, c.now)
        }
        if (claimed != 1) return AlarmOutcome.DUPLICATE

        diag(DiagnosticType.POST_REQUESTED, c, occ, detail = env?.compact(), withLateness = true)
        val result = notifications.postReminder(
            ReminderNotification(
                taskId = task.id, occurrenceId = occ.id, title = task.title,
                body = notificationBody(task, occ),
                plannedAt = occ.plannedAt, late = late, redactOnLockScreen = c.settings.lockScreenPrivacy,
                postedAt = c.now, dueLabel = dueLabel(occ.plannedAt, c),
                snoozeLabel = snoozeLabel(policy, c),
                // A routine is answered per cycle from the notification; permanent Done stays in the app.
                doneForNowLabel = if (policy.isRoutine && (task.status == TaskStatus.ACTIONABLE || task.status == TaskStatus.INBOX)) RoutineCycle.notificationLabel(occ.slotAt, c.now, ReminderPlanner.policyZone(policy, c.zone)) else null,
            ),
        )
        val meta = mapOf(
            "kind" to occ.kind.name, "plannedAt" to occ.plannedAt.toString(), "postedAt" to c.now.toString(),
            "latenessSec" to lateness.seconds.toString(), "via" to c.source, "mode" to if (occ.exact) "exact" else "approximate",
        )
        return if (result == PostResult.POSTED) {
            // notify() only enqueues; the active list is updated asynchronously and is keyed by
            // task, so an immediate check here would be neither timely nor specific.
            diag(DiagnosticType.POSTED, c, occ, withLateness = true, outcome = "notify_returned")
            log(task.id, c.now, EventType.REMINDER_DELIVERED, deliverySummary(lateness, c.source, occ.exact), meta)
            AlarmOutcome.DELIVERED
        } else {
            occurrenceDao.get(occ.id)?.let { occurrenceDao.update(it.copy(status = OccurrenceStatus.SUPPRESSED, deliveredAt = null, note = result.name.lowercase())) }
            diag(DiagnosticType.POST_FAILED, c, occ, outcome = result.name.lowercase(), detail = env?.compact(), withLateness = true)
            log(task.id, c.now, EventType.REMINDER_SUPPRESSED, "Reminder not shown: ${result.name.lowercase().replace('_', ' ')}", meta + ("reason" to result.name))
            AlarmOutcome.SUPPRESSED
        }
    }

    /** Same target the Snooze action will use (per-policy minutes, else the global preset), as a label. */
    private fun snoozeLabel(policy: ReminderPolicy, c: Ctx): String {
        val target = policy.snoozeDefaultMinutes?.let { SnoozeCalculator.targetMinutes(it, c.now) }
            ?: SnoozeCalculator.target(c.settings.defaultSnooze, c.now, c.zone)
        val local = target.atZone(c.zone)
        val today = c.now.atZone(c.zone).toLocalDate()
        val time = local.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return when (local.toLocalDate()) {
            today -> time
            today.plusDays(1) -> "tomorrow $time"
            else -> "${local.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))} $time"
        }
    }

    private fun dueLabel(plannedAt: Instant, c: Ctx): String {
        val local = plannedAt.atZone(c.zone)
        val today = c.now.atZone(c.zone).toLocalDate()
        val time = local.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return if (local.toLocalDate() == today) time else "${local.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))} $time"
    }

    /** Human-readable, task-content-free account of how this delivery happened. */
    private fun deliverySummary(lateness: Duration, source: String, exact: Boolean): String {
        val mode = if (exact) "precise timing" else "approximate timing"
        if (lateness <= ON_TIME) return "Reminder delivered on time ($mode)"
        val how = when {
            source == "alarm" -> "alarm arrived late"
            source == "mutation" -> "caught up after an edit"
            source.startsWith("reconcile:app_") -> "caught up when the app was opened or started"
            source.startsWith("reconcile:boot") -> "caught up after restart"
            source.startsWith("reconcile:") -> "caught up by ${source.removePrefix("reconcile:").replace('_', ' ')} check"
            else -> source
        }
        return "Reminder delivered ${lateText(lateness)} late · $how ($mode)"
    }

    private fun lateText(d: Duration): String {
        val m = d.toMinutes()
        return when {
            m < 1 -> "${d.seconds} s"
            m < 120 -> "$m min"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    private suspend fun notificationBody(task: TaskEntity, occ: ReminderOccurrenceEntity): String = when (occ.kind) {
        OccurrenceKind.CHECK_IN -> task.waitingReason?.let { "Check in: $it" } ?: "Time to check in on this"
        OccurrenceKind.SNOOZE_END -> stepDao.getForTask(task.id).firstOrNull { !it.isDone }?.let { "Snooze over · next: ${it.title}" } ?: "Snooze over — tap Done when it's finished"
        OccurrenceKind.POLICY -> stepDao.getForTask(task.id).firstOrNull { !it.isDone }?.let { "Next: ${it.title}" } ?: "Still open — tap Done when it's finished"
    }

    private suspend fun log(taskId: String, at: Instant, type: EventType, summary: String, metadata: Map<String, String> = emptyMap()) {
        eventDao.insert(TaskEventEntity(ids.newId(), taskId, at, type, summary, encodeMetadata(metadata)))
    }

    companion object {
        val ACTIVE = listOf(OccurrenceStatus.PLANNED, OccurrenceStatus.SCHEDULED)
        /** Overdue by less than this ⇒ the alarm broadcast is presumably in flight. */
        val IN_FLIGHT_TOLERANCE: Duration = Duration.ofSeconds(60)
        /** Alarms never fire early; anything earlier than this means the wall clock moved back. */
        val EARLY_TOLERANCE: Duration = Duration.ofSeconds(60)
        val LATE_LABEL: Duration = Duration.ofMinutes(15)
        /** More late reminders than this after downtime are coalesced into one summary. */
        const val MAX_INDIVIDUAL_LATE = 3
        /** Deliveries within this of the planned time are reported as on time. */
        val ON_TIME: Duration = Duration.ofSeconds(90)
        /** Full reconciles that follow events which clear or invalidate OS alarms. */
        val RECOVERY_REASONS = setOf("boot", "app_updated", "time_changed", "timezone_changed", "exact_alarm_permission", "precision_mode")
    }
}
