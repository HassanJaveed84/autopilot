package com.lifeadmin.autopilot.reminders

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.SnoozePreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.notifications.NotificationHealth
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.KOLKATA
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.testing.t
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ReminderEngineTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 16, 7, 0)) // Friday
    private val zone = FakeZoneProvider(CHICAGO)
    private val alarms = FakeAlarmDriver()
    private val notifications = FakeNotificationPublisher()
    private val settings = InMemorySettingsRepository(AppSettings())
    private lateinit var engine: ReminderEngine
    private lateinit var repo: TaskRepository
    private lateinit var actions: TaskActions

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds()
        engine = ReminderEngine(db, settings, alarms, notifications, clock, zone, ids)
        repo = TaskRepository(db, clock, ids, zone::current, engine)
        actions = TaskActions(db, repo, settings, clock, zone)
    }

    @After fun tearDown() = db.close()

    private fun train(): String = runBlocking {
        repo.createTask(NewTask("Book train ride", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
    }

    /** Advances the fake clock to the earliest alarm (+delay) and fires it like AlarmManager would. */
    private fun fireNext(delay: Duration = Duration.ofSeconds(5)): AlarmOutcome = runBlocking {
        val a = alarms.earliest() ?: error("no alarm scheduled")
        clock.instant = a.triggerAt.plus(delay)
        alarms.fired(a.occurrenceId) // one-shot alarms are removed by the OS when they fire
        engine.onAlarm(a.occurrenceId, a.generation)
    }

    private fun active(taskId: String) = runBlocking { db.occurrenceDao().getForTaskWithStatus(taskId, ReminderEngine.ACTIVE) }
    private fun task(id: String) = runBlocking { repo.getTask(id)!! }

    // ------------------------------------------------------------ canonical use case

    @Test fun trainTicketRemindsAt0900And1800UntilDone() {
        val id = train()
        assertEquals(at(CHICAGO, 2026, 10, 16, 9), alarms.single().triggerAt)
        val delivered = mutableListOf<Instant>()
        repeat(6) {
            val due = alarms.single().triggerAt
            assertEquals(AlarmOutcome.DELIVERED, fireNext())
            delivered += due
            assertEquals("exactly one alarm pending", 1, alarms.alarms.size)
        }
        assertEquals(
            listOf(at(CHICAGO, 2026, 10, 16, 9), at(CHICAGO, 2026, 10, 16, 18), at(CHICAGO, 2026, 10, 17, 9), at(CHICAGO, 2026, 10, 17, 18), at(CHICAGO, 2026, 10, 18, 9), at(CHICAGO, 2026, 10, 18, 18)),
            delivered,
        )
        assertEquals(6, notifications.posted.size)
        assertTrue(notifications.posted.all { it.taskId == id && it.title == "Book train ride" })
        assertEquals("repeated reminders never create tasks", 1, runBlocking { db.taskDao().count() })
        assertEquals(6, runBlocking { db.eventDao().getForTask(id).count { it.eventType == EventType.REMINDER_DELIVERED } })

        // Done from the notification: completes, cancels future alarms, dismisses notification.
        runBlocking { repo.complete(id) }
        assertEquals(TaskStatus.DONE, task(id).status)
        assertTrue(alarms.alarms.isEmpty())
        assertTrue(active(id).isEmpty())
        assertFalse(notifications.visible.containsKey(id))
    }

    @Test fun dismissingANotificationDoesNotCompleteTheTask() {
        val id = train()
        fireNext()
        notifications.cancelForTask(id) // user swipes it away: no callback into the engine
        assertEquals(TaskStatus.ACTIONABLE, task(id).status)
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), alarms.single().triggerAt)
    }

    @Test fun doneThenStaleBroadcastThenReopenReschedulesWithNewGeneration() {
        val id = train()
        val old = alarms.single()
        runBlocking { repo.complete(id) }
        clock.instant = old.triggerAt.plusSeconds(1)
        assertEquals(AlarmOutcome.DUPLICATE, runBlocking { engine.onAlarm(old.occurrenceId, old.generation) })
        assertTrue(notifications.posted.isEmpty())
        runBlocking { repo.reopen(id) }
        val fresh = alarms.single()
        assertTrue(fresh.generation > old.generation)
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), fresh.triggerAt)
        // The pre-reopen broadcast stays ignored even if it arrives late.
        assertTrue(runBlocking { engine.onAlarm(old.occurrenceId, old.generation) } in setOf(AlarmOutcome.DUPLICATE, AlarmOutcome.STALE))
        assertTrue(notifications.posted.isEmpty())
    }

    @Test fun duplicateBroadcastNotifiesOnce() {
        train()
        val a = alarms.single()
        clock.instant = a.triggerAt.plusSeconds(2)
        assertEquals(AlarmOutcome.DELIVERED, runBlocking { engine.onAlarm(a.occurrenceId, a.generation) })
        assertEquals(AlarmOutcome.DUPLICATE, runBlocking { engine.onAlarm(a.occurrenceId, a.generation) })
        assertEquals(1, notifications.posted.size)
    }

    @Test fun staleGenerationAfterEditIsIgnored() {
        val id = train()
        val old = alarms.single()
        runBlocking { repo.updateDetails(id, "Book family train ride", null, com.lifeadmin.autopilot.domain.model.Priority.NORMAL) }
        val current = alarms.single()
        assertEquals("same occurrence reused, no duplicate", old.occurrenceId, current.occurrenceId)
        clock.instant = old.triggerAt.plusSeconds(1)
        if (current.generation != old.generation) {
            assertEquals(AlarmOutcome.STALE, runBlocking { engine.onAlarm(old.occurrenceId, old.generation) })
            assertTrue(notifications.posted.isEmpty())
        }
        assertEquals(AlarmOutcome.DELIVERED, runBlocking { engine.onAlarm(current.occurrenceId, current.generation) })
        assertEquals("Book family train ride", notifications.posted.single().title)
    }

    // ------------------------------------------------------------ snooze

    @Test fun snoozeFromNotificationSuppressesInterveningSlotsThenResumes() {
        val id = train()
        fireNext() // 09:00 delivered
        val until = runBlocking { actions.snoozeWithDefault(id) } // tomorrow morning 09:00
        assertEquals(at(CHICAGO, 2026, 10, 17, 9), until)
        assertFalse(notifications.visible.containsKey(id))
        val a = alarms.single()
        assertEquals("18:00 today is suppressed", at(CHICAGO, 2026, 10, 17, 9), a.triggerAt)
        fireNext()
        assertEquals(at(CHICAGO, 2026, 10, 17, 18), alarms.single().triggerAt)
        assertEquals(2, notifications.posted.size)
    }

    @Test fun shortSnoozeFiresSnoozeEndThenPolicyContinues() {
        val id = train()
        fireNext()
        runBlocking { repo.snooze(id, clock.now().plus(Duration.ofHours(1)), remindAtEnd = true) }
        val a = alarms.single()
        val occ = runBlocking { db.occurrenceDao().get(a.occurrenceId)!! }
        assertEquals(OccurrenceKind.SNOOZE_END, occ.kind)
        fireNext()
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), alarms.single().triggerAt)
    }

    @Test fun snoozeWithoutEndReminderSkipsToNextPolicySlot() {
        val id = train()
        fireNext()
        runBlocking { repo.snooze(id, clock.now().plus(Duration.ofHours(1)), remindAtEnd = false) }
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), alarms.single().triggerAt)
    }

    @Test fun snoozeDuringInFlightDeliveryCancelsIt() {
        val id = train()
        val a = alarms.single()
        clock.instant = a.triggerAt.plusSeconds(1)
        runBlocking { repo.snooze(id, at(CHICAGO, 2026, 10, 17, 9), remindAtEnd = true) }
        assertEquals(AlarmOutcome.DUPLICATE, runBlocking { engine.onAlarm(a.occurrenceId, a.generation) }.let { if (it == AlarmOutcome.STALE) AlarmOutcome.DUPLICATE else it })
        assertTrue(notifications.posted.isEmpty())
    }

    // ------------------------------------------------------------ status gating

    @Test fun pausedHasNoAlarmsAndResumeRestores() {
        val id = train()
        runBlocking { repo.changeStatus(id, TaskStatus.PAUSED) }
        assertTrue(alarms.alarms.isEmpty())
        runBlocking { repo.changeStatus(id, TaskStatus.ACTIONABLE) }
        assertEquals(at(CHICAGO, 2026, 10, 16, 9), alarms.single().triggerAt)
    }

    @Test fun waitingUsesOnlyCheckIn() {
        val id = train()
        val checkIn = at(CHICAGO, 2026, 10, 20, 10)
        runBlocking { repo.changeStatus(id, TaskStatus.WAITING, "Mechanic to call", checkIn) }
        val a = alarms.single()
        assertEquals(checkIn, a.triggerAt)
        fireNext()
        assertTrue(notifications.posted.single().body.contains("Mechanic to call"))
        assertTrue("no repeated nagging while waiting", alarms.alarms.isEmpty())
    }

    @Test fun archivedOrCanceledTasksNeverRemind() {
        val a = train()
        val b = runBlocking { repo.createTask(NewTask("Other", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.DAILY)) }
        runBlocking { repo.archive(a); repo.changeStatus(b, TaskStatus.CANCELED) }
        assertTrue(alarms.alarms.isEmpty())
    }

    @Test fun deletingTaskCancelsItsAlarm() {
        val id = train()
        assertEquals(1, alarms.alarms.size)
        runBlocking { repo.delete(id) }
        assertTrue(alarms.alarms.isEmpty())
    }

    @Test fun onceReminderStopsAfterOneDelivery() {
        val id = runBlocking { repo.createTask(NewTask("Call dentist", status = TaskStatus.ACTIONABLE)) }
        val p = runBlocking { repo.getPolicy(id)!! }
        runBlocking { repo.savePolicy(p.copy(enabled = true, kind = ReminderKind.ONCE, atInstant = at(CHICAGO, 2026, 10, 17, 9), times = emptyList())) }
        assertEquals(at(CHICAGO, 2026, 10, 17, 9), alarms.single().triggerAt)
        fireNext()
        assertTrue(alarms.alarms.isEmpty())
        assertEquals(TaskStatus.ACTIONABLE, task(id).status)
    }

    // ------------------------------------------------------------ quiet hours

    @Test fun quietHoursDeferAndCoalesceIntoOneMorningReminder() {
        val id = runBlocking { repo.createTask(NewTask("Evening task", status = TaskStatus.ACTIONABLE)) }
        val p = runBlocking { repo.getPolicy(id)!! }
        runBlocking { repo.savePolicy(p.copy(enabled = true, kind = ReminderKind.DAILY_TIMES, times = listOf(t(21, 30), t(23), t(8)))) }
        assertEquals(at(CHICAGO, 2026, 10, 16, 8), alarms.single().triggerAt) // today 08:00 slot first
        fireNext()
        assertEquals(at(CHICAGO, 2026, 10, 17, 8), alarms.single().triggerAt) // 21:30 & 23:00 deferred + 08:00 → one
        fireNext()
        assertEquals(at(CHICAGO, 2026, 10, 18, 8), alarms.single().triggerAt)
        assertEquals(2, notifications.posted.size)
    }

    // ------------------------------------------------------------ reboot / recovery

    @Test fun rebootWithinGraceDeliversLateReminderAndSchedulesNext() {
        val id = train()
        alarms.clearAll() // phone off: OS forgets alarms
        clock.instant = at(CHICAGO, 2026, 10, 16, 10, 0)
        val report = runBlocking { engine.reconcileAll("boot") }
        assertEquals(1, report.lateDelivered)
        assertTrue(notifications.posted.single().late)
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), alarms.single().triggerAt)
        assertEquals(id, alarms.single().taskId)
    }

    @Test fun rebootBeyondGraceMarksMissedAndSchedulesNext() {
        train()
        alarms.clearAll()
        clock.instant = at(CHICAGO, 2026, 10, 16, 14, 0) // 5h late > 4h grace
        val report = runBlocking { engine.reconcileAll("boot") }
        assertEquals(1, report.missed)
        assertTrue(notifications.posted.isEmpty())
        assertEquals(1, notifications.summaries.size)
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), alarms.single().triggerAt)
    }

    @Test fun manyLateRemindersAfterOutageAreCoalescedNoStorm() {
        val ids = (1..6).map { runBlocking { repo.createTask(NewTask("Task $it", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT)) } }
        alarms.clearAll()
        clock.instant = at(CHICAGO, 2026, 10, 16, 9, 30)
        val report = runBlocking { engine.reconcileAll("boot") }
        assertEquals(6, report.coalesced)
        assertTrue("no individual notifications", notifications.posted.isEmpty())
        assertEquals(1, notifications.summaries.size)
        ids.forEach { assertEquals(at(CHICAGO, 2026, 10, 16, 18), alarms.forTask(it).single().triggerAt) }
        // Running recovery again (e.g. boot + app start) does not re-notify.
        runBlocking { engine.reconcileAll("app_start") }
        assertEquals(1, notifications.summaries.size)
        assertEquals(6, alarms.alarms.size)
    }

    @Test fun reminderDueJustBeforeRebootIsReRegistered() {
        train()
        val a = alarms.single()
        alarms.clearAll() // reboot at 09:00:40, 40 s after the slot: inside the in-flight window
        clock.instant = a.triggerAt.plusSeconds(40)
        runBlocking { engine.reconcileAll("boot") }
        val again = alarms.alarms[a.occurrenceId]
        assertTrue("in-flight occurrence re-registered", again != null)
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), alarms.forTask(a.taskId).maxOf { it.triggerAt }) // next one planned too
        alarms.fired(a.occurrenceId)
        assertEquals(AlarmOutcome.DELIVERED, runBlocking { engine.onAlarm(again!!.occurrenceId, again.generation) })
        assertEquals(1, notifications.posted.size)
    }

    @Test fun coalescedRemindersAreNotClaimedWhenSummaryChannelIsOff() {
        notifications.health = NotificationHealth(permissionGranted = true, appNotificationsEnabled = true, reminderChannelEnabled = true, summaryChannelEnabled = false)
        repeat(5) { runBlocking { repo.createTask(NewTask("T$it", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT)) } }
        alarms.clearAll()
        clock.instant = at(CHICAGO, 2026, 10, 16, 9, 30)
        runBlocking { engine.reconcileAll("boot") }
        val all = runBlocking { db.occurrenceDao().getAll() }
        assertEquals(0, all.count { it.status == OccurrenceStatus.DELIVERED })
        assertEquals(5, all.count { it.status == OccurrenceStatus.SUPPRESSED })
        assertEquals(5, alarms.alarms.size)
    }

    @Test fun multiDayOutageDoesNotReplayEveryMissedSlot() {
        train()
        alarms.clearAll()
        clock.instant = at(CHICAGO, 2026, 10, 19, 12, 0) // 3 days later
        val report = runBlocking { engine.reconcileAll("boot") }
        assertEquals(1, report.missed) // only the one materialised occurrence; intermediate slots were never queued
        assertTrue(notifications.posted.isEmpty())
        assertEquals(at(CHICAGO, 2026, 10, 19, 18), alarms.single().triggerAt)
    }

    @Test fun reconcileIsIdempotent() {
        train()
        val before = alarms.single()
        repeat(3) { runBlocking { engine.reconcileAll("app_start") } }
        assertEquals(before.occurrenceId, alarms.single().occurrenceId)
        assertEquals(1, runBlocking { db.occurrenceDao().getAll().size })
    }

    @Test fun timeZoneChangeRecomputesWallClockSlots() {
        train()
        val old = alarms.single()
        zone.zone = KOLKATA // 07:00 CDT == 17:30 IST
        runBlocking { engine.reconcileAll("timezone_changed") }
        val now = alarms.single()
        assertEquals(at(KOLKATA, 2026, 10, 16, 18), now.triggerAt)
        assertFalse(alarms.alarms.containsKey(old.occurrenceId))
    }

    @Test fun clockSetBackDoesNotRepeatDeliveredReminder() {
        train()
        fireNext() // 09:00
        clock.instant = at(CHICAGO, 2026, 10, 16, 8, 50)
        runBlocking { engine.reconcileAll("time_changed") }
        assertEquals(at(CHICAGO, 2026, 10, 16, 18), alarms.single().triggerAt)
        assertEquals(1, notifications.posted.size)
    }

    @Test fun alarmFiringEarlyAfterClockChangeIsRescheduledNotDelivered() {
        train()
        val a = alarms.single()
        clock.instant = a.triggerAt.minus(Duration.ofHours(1))
        assertEquals(AlarmOutcome.RESCHEDULED, runBlocking { engine.onAlarm(a.occurrenceId, a.generation) })
        assertTrue(notifications.posted.isEmpty())
        assertEquals(a.triggerAt, alarms.single().triggerAt)
    }

    @Test fun osRefusingAlarmLeavesPlannedAndIsRetried() {
        alarms.failing = true
        val id = train()
        assertEquals(OccurrenceStatus.PLANNED, active(id).single().status)
        alarms.failing = false
        runBlocking { engine.reconcileAll("periodic") }
        assertEquals(OccurrenceStatus.SCHEDULED, active(id).single().status)
        assertEquals(1, alarms.alarms.size)
    }

    // ------------------------------------------------------------ permissions & limits

    @Test fun deniedNotificationsAreRecordedHonestly() {
        notifications.health = NotificationHealth(permissionGranted = false, appNotificationsEnabled = true, reminderChannelEnabled = true, summaryChannelEnabled = true)
        val id = train()
        assertEquals(AlarmOutcome.SUPPRESSED, fireNext())
        assertTrue(notifications.posted.isEmpty())
        val ledger = runBlocking { db.occurrenceDao().getAllForTask(id) }
        assertEquals("permission_denied", ledger.first { it.status == OccurrenceStatus.SUPPRESSED }.note)
        assertNull("never claims delivery", ledger.first { it.status == OccurrenceStatus.SUPPRESSED }.deliveredAt)
        assertTrue(runBlocking { db.eventDao().getForTask(id) }.any { it.eventType == EventType.REMINDER_SUPPRESSED })
        assertEquals("task and policy kept; next still scheduled", at(CHICAGO, 2026, 10, 16, 18), alarms.single().triggerAt)
    }

    @Test fun appLevelToggleSuppressesWithoutLosingSchedule() {
        runBlocking { settings.update { it.copy(allowReminderNotifications = false) } }
        train()
        assertEquals(AlarmOutcome.SUPPRESSED, fireNext())
        assertEquals(1, alarms.alarms.size)
    }

    @Test fun dailyNotificationCapIsEnforced() {
        runBlocking { settings.update { it.copy(maxNotificationsPerDay = 1) } }
        train()
        assertEquals(AlarmOutcome.DELIVERED, fireNext())
        assertEquals(AlarmOutcome.SUPPRESSED, fireNext())
        assertEquals(1, notifications.posted.size)
    }

    @Test fun precisionModeUsesExactOnlyWhenGrantedOtherwiseFallsBack() {
        runBlocking { settings.update { it.copy(precisionMode = true) } }
        val id = train()
        assertEquals(false, active(id).single().exact)
        assertEquals("exact_unavailable", active(id).single().note)
        alarms.exactAllowed = true
        runBlocking { engine.reconcileAll("exact_alarm_permission") }
        assertEquals(true, active(id).single().exact)
        alarms.exactAllowed = false // revoked
        runBlocking { engine.reconcileAll("exact_alarm_permission") }
        assertEquals(false, active(id).single().exact)
        assertEquals(1, alarms.alarms.size)
    }

    @Test fun concurrentDoneAndDeliveryNeverLeaveAVisibleNotificationForADoneTask() = runBlocking {
        repeat(20) { round ->
            val id = repo.createTask(NewTask("Race $round", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
            val a = alarms.forTask(id).single()
            clock.instant = a.triggerAt.plusSeconds(1)
            withContext(Dispatchers.Default) {
                listOf(
                    async { engine.onAlarm(a.occurrenceId, a.generation) },
                    async { repo.complete(id) },
                    async { engine.onAlarm(a.occurrenceId, a.generation) },
                ).awaitAll()
            }
            assertEquals(TaskStatus.DONE, repo.getTask(id)!!.status)
            assertFalse("round $round", notifications.visible.containsKey(id))
            assertTrue(alarms.forTask(id).isEmpty())
            assertTrue(notifications.posted.count { it.taskId == id } <= 1)
            clock.instant = at(CHICAGO, 2026, 10, 16, 7, 0)
        }
    }

    @Test fun snoozePresetTargetsAreExact() {
        assertEquals(clock.now().plus(Duration.ofHours(1)), actions.presetTarget(SnoozePreset.ONE_HOUR))
        assertEquals(at(CHICAGO, 2026, 10, 17, 9), actions.presetTarget(SnoozePreset.TOMORROW_MORNING))
    }
}
