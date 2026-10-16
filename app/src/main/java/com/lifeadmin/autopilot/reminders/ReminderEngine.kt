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
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import com.lifeadmin.autopilot.domain.scheduling.PolicySlotGenerator
import com.lifeadmin.autopilot.domain.scheduling.ReminderPlanner
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

    private class Ctx(val now: Instant, val settings: AppSettings, val zone: ZoneId)

    private class CatchUp {
        val late = mutableListOf<ReminderOccurrenceEntity>()
        var missed = 0
    }

    // ------------------------------------------------------------------ public API

    override suspend fun <T> mutateTask(taskId: String, dismissNotification: Boolean, block: suspend () -> T): T = locked {
        val before = occurrenceDao.getForTaskWithStatus(taskId, ACTIVE)
        val result = block()
        if (dismissNotification) notifications.cancelForTask(taskId)
        reconcileTaskLocked(taskId, before, ctx(), catchUp = null)
        result
    }

    suspend fun reconcileTask(taskId: String) = locked { reconcileTaskLocked(taskId, emptyList(), ctx(), null) }

    /** Full rebuild: app start, boot, package update, time/zone change, permission change, periodic. */
    suspend fun reconcileAll(reason: String): ReconcileReport = locked {
        val c = ctx()
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
            if (!posted && coalesced > 0) {
                // Never claim delivery that didn't happen.
                for (occ in catchUp.late) {
                    occurrenceDao.get(occ.id)?.takeIf { it.status == OccurrenceStatus.DELIVERED }?.let {
                        occurrenceDao.update(it.copy(status = OccurrenceStatus.SUPPRESSED, deliveredAt = null, note = "summary_not_shown"))
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
        report
    }

    /** Alarm broadcast entry point. Safe to call any number of times for the same occurrence. */
    suspend fun onAlarm(occurrenceId: String, generation: Int): AlarmOutcome = locked {
        val c = ctx()
        val occ = occurrenceDao.get(occurrenceId) ?: return@locked AlarmOutcome.NOT_FOUND
        if (occ.status !in ACTIVE) return@locked AlarmOutcome.DUPLICATE
        if (occ.generation != generation) {
            // A newer schedule superseded this broadcast; make sure the current one is registered.
            reconcileTaskLocked(occ.taskId, emptyList(), c, null)
            return@locked AlarmOutcome.STALE
        }
        val task = taskDao.get(occ.taskId) ?: return@locked AlarmOutcome.NOT_FOUND
        if (occ.plannedAt > c.now.plus(EARLY_TOLERANCE)) {
            // Wall clock moved backwards after scheduling; re-derive instead of delivering early.
            reconcileTaskLocked(task.id, emptyList(), c, null)
            return@locked AlarmOutcome.RESCHEDULED
        }
        val outcome = deliverLocked(occ, task, c, late = Duration.between(occ.plannedAt, c.now) > LATE_LABEL)
        reconcileTaskLocked(task.id, emptyList(), c, null)
        outcome
    }

    /** Next active occurrence across all tasks (for the health panel). */
    suspend fun nextScheduled(): ReminderOccurrenceEntity? = occurrenceDao.getWithStatus(ACTIVE).minByOrNull { it.plannedAt }

    suspend fun pruneLedger(olderThan: Duration = Duration.ofDays(90)): Int = locked {
        occurrenceDao.pruneSettledBefore(clock.now().minus(olderThan))
    }

    // ------------------------------------------------------------------ core

    private suspend fun ctx() = Ctx(clock.now(), settings.current(), zoneProvider.current())

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
                !eligible -> cancel(occ)
                overdueBy <= IN_FLIGHT_TOLERANCE -> {
                    // Presumably in flight; re-register anyway in case the OS dropped it (reboot,
                    // force-stop). A duplicate broadcast is ignored by the delivery claim.
                    alarms.schedule(AlarmRequest(occ.id, taskId, occ.generation, occ.plannedAt, preferExact = c.settings.precisionMode))
                }
                overdueBy > Duration.ofMinutes(c.settings.graceWindowMinutes.toLong()) -> {
                    occurrenceDao.update(occ.copy(status = OccurrenceStatus.MISSED, note = "beyond_grace"))
                    alarms.cancel(occ.id)
                    log(taskId, c.now, EventType.REMINDER_MISSED, "Reminder missed (device off or app stopped)", mapOf("plannedAt" to occ.plannedAt.toString()))
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
            if (plan == null || occ.plannedAt != plan.plannedAt) cancel(occ)
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
        occurrenceDao.update(
            occ.copy(
                status = if (result.scheduled) OccurrenceStatus.SCHEDULED else OccurrenceStatus.PLANNED,
                scheduledAt = if (result.scheduled) c.now else null,
                exact = result.exact,
                note = result.error,
            ),
        )
    }

    private suspend fun cancel(occ: ReminderOccurrenceEntity) {
        alarms.cancel(occ.id)
        occurrenceDao.update(occ.copy(status = OccurrenceStatus.CANCELED))
    }

    /** Re-validates an occurrence against current durable state; notification extras are never trusted. */
    private fun isEligible(occ: ReminderOccurrenceEntity, task: TaskEntity, policy: ReminderPolicy, c: Ctx): Boolean {
        if (task.archivedAt != null || task.status.isTerminal || task.status == TaskStatus.PAUSED) return false
        return when (occ.kind) {
            OccurrenceKind.POLICY -> {
                val statusOk = task.status == TaskStatus.ACTIONABLE || task.status == TaskStatus.INBOX
                val snoozedOver = task.snoozedUntil?.let { it > occ.slotAt } ?: false
                statusOk && policy.enabled && !snoozedOver && policyStillProduces(policy, occ.slotAt, c)
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
            ?: return AlarmOutcome.NOT_FOUND.also { cancel(occ) }
        if (!isEligible(occ, task, policy, c)) {
            cancel(occ)
            return AlarmOutcome.CANCELED
        }
        if (Duration.between(occ.plannedAt, c.now) > Duration.ofMinutes(c.settings.graceWindowMinutes.toLong())) {
            occurrenceDao.update(occ.copy(status = OccurrenceStatus.MISSED, note = "beyond_grace"))
            log(task.id, c.now, EventType.REMINDER_MISSED, "Reminder missed (delivered too late)", mapOf("plannedAt" to occ.plannedAt.toString()))
            return AlarmOutcome.MISSED
        }
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
            log(task.id, c.now, EventType.REMINDER_SUPPRESSED, "Reminder not shown: ${blockReason.replace('_', ' ')}", mapOf("reason" to blockReason))
            return AlarmOutcome.SUPPRESSED
        }
        val claimed = db.withTransaction {
            occurrenceDao.compareAndSetStatus(occ.id, occ.status, OccurrenceStatus.DELIVERED, occ.generation, c.now)
        }
        if (claimed != 1) return AlarmOutcome.DUPLICATE

        val result = notifications.postReminder(
            ReminderNotification(
                taskId = task.id, occurrenceId = occ.id, title = task.title,
                body = notificationBody(task, occ),
                plannedAt = occ.plannedAt, late = late, redactOnLockScreen = c.settings.lockScreenPrivacy,
            ),
        )
        return if (result == PostResult.POSTED) {
            log(task.id, c.now, EventType.REMINDER_DELIVERED, if (late) "Reminder delivered (late)" else "Reminder delivered", mapOf("kind" to occ.kind.name))
            AlarmOutcome.DELIVERED
        } else {
            occurrenceDao.get(occ.id)?.let { occurrenceDao.update(it.copy(status = OccurrenceStatus.SUPPRESSED, deliveredAt = null, note = result.name.lowercase())) }
            log(task.id, c.now, EventType.REMINDER_SUPPRESSED, "Reminder not shown: ${result.name.lowercase().replace('_', ' ')}", mapOf("reason" to result.name))
            AlarmOutcome.SUPPRESSED
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
    }
}
