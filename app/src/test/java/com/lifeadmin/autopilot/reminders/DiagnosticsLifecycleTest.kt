package com.lifeadmin.autopilot.reminders

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.model.DiagnosticEntry
import com.lifeadmin.autopilot.domain.model.DiagnosticType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.RingerMode
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.notifications.NotificationHealth
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.testing.t
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * Reproduces the S24 Ultra report shape (one-time 18:25 reminder posted ~24 min late) and checks
 * the ledger now records what actually happened: scheduled time, broadcast receipt time, post
 * request time, delivery path and environment — so the cause is visible instead of guessed.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsLifecycleTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 16, 17, 30))
    private val zone = FakeZoneProvider(CHICAGO)
    private val alarms = FakeAlarmDriver()
    private val notifications = FakeNotificationPublisher()
    private val env = FakeEnvironmentProbe(DeliveryEnvironment(ringerMode = RingerMode.VIBRATE, channelExists = true, channelImportance = 3, channelHasSound = true, channelVibrates = false))
    private val settings = InMemorySettingsRepository(AppSettings(quietHours = AppSettings().quietHours.copy(enabled = false)))
    private lateinit var log: RoomDiagnosticsLog
    private lateinit var engine: ReminderEngine
    private lateinit var repo: TaskRepository
    private lateinit var actions: TaskActions

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds()
        log = RoomDiagnosticsLog(db, clock, ids)
        engine = ReminderEngine(db, settings, alarms, notifications, clock, zone, ids, log, env)
        repo = TaskRepository(db, clock, ids, zone::current, engine, log)
        actions = TaskActions(db, repo, settings, clock, zone)
    }

    @After fun tearDown() = db.close()

    private val due = at(CHICAGO, 2026, 10, 16, 18, 25)

    private fun oneTimeTask(title: String = "Test"): String = runBlocking {
        val id = repo.createTask(NewTask(title, status = TaskStatus.ACTIONABLE))
        val p = repo.getPolicy(id)!!
        repo.savePolicy(p.copy(enabled = true, kind = ReminderKind.ONCE, atInstant = due, times = emptyList()))
        id
    }

    private fun ledger(): List<DiagnosticEntry> = runBlocking { log.recent(500) }.reversed()
    private fun ofType(t: DiagnosticType) = ledger().filter { it.type == t }

    @Test fun schedulingRecordsPlannedAndTriggerTimeAndApproximateMode() {
        oneTimeTask()
        val s = ofType(DiagnosticType.SCHEDULED).single()
        assertEquals(due, s.plannedAt)
        assertEquals(due, s.triggerAt)
        assertEquals(false, s.exact)
        assertEquals("mutation", s.source)
        assertEquals(clock.now(), s.at)
    }

    @Test fun lateAlarmRecordsActualReceiptAndPostTimesNotThePlannedTime() {
        val id = oneTimeTask()
        val alarm = alarms.single()
        val receivedAt = due.plus(Duration.ofMinutes(24)).plusSeconds(3)
        clock.instant = receivedAt.plusMillis(400) // engine work runs a moment after receipt
        alarms.fired(alarm.occurrenceId)
        runBlocking { engine.onAlarm(alarm.occurrenceId, alarm.generation, receivedAt) }

        val received = ofType(DiagnosticType.ALARM_RECEIVED).single()
        assertEquals(receivedAt, received.at)
        assertEquals(due, received.plannedAt)
        assertEquals(Duration.ofMinutes(24).plusSeconds(3), received.lateness)

        val requested = ofType(DiagnosticType.POST_REQUESTED).single()
        assertEquals(clock.now(), requested.at)
        assertEquals("alarm", requested.source)
        assertEquals(false, requested.exact)
        assertTrue("environment captured", requested.detail!!.contains("ringer=vibrate") && requested.detail!!.contains("vibrate=n"))

        val posted = ofType(DiagnosticType.POSTED).single()
        assertEquals("listed_active", posted.outcome)

        // Notification shows the real posting time; due time is carried separately.
        val n = notifications.posted.single()
        assertEquals(clock.now(), n.postedAt)
        assertEquals("18:25", n.dueLabel)
        assertTrue(n.late)

        val event = runBlocking { db.eventDao().getForTask(id) }.first { it.eventType == EventType.REMINDER_DELIVERED }
        assertEquals("Reminder delivered 24 min late · alarm arrived late (approximate timing)", event.summary)
        assertTrue(event.structuredMetadata!!.contains("\"via\":\"alarm\""))
    }

    @Test fun catchUpDeliveryIsAttributedToTheReconcileNotToAnAlarm() {
        val id = oneTimeTask()
        alarms.clearAll() // alarm never fires (e.g. OS dropped it)
        clock.instant = due.plus(Duration.ofMinutes(24))
        runBlocking { engine.reconcileAll("app_resume") }
        assertTrue("no broadcast was ever received", ofType(DiagnosticType.ALARM_RECEIVED).isEmpty())
        val requested = ofType(DiagnosticType.POST_REQUESTED).single()
        assertEquals("reconcile:app_resume", requested.source)
        assertEquals(Duration.ofMinutes(24), requested.lateness)
        val event = runBlocking { db.eventDao().getForTask(id) }.first { it.eventType == EventType.REMINDER_DELIVERED }
        assertEquals("Reminder delivered 24 min late · caught up when the app was opened or started (approximate timing)", event.summary)
        assertNotNull(ofType(DiagnosticType.RECONCILE).singleOrNull())
    }

    @Test fun coldStartRaceShowsAlarmReceiptEvenWhenCatchUpPostedFirst() {
        oneTimeTask()
        val alarm = alarms.single()
        val receivedAt = due.plus(Duration.ofMinutes(10))
        clock.instant = receivedAt
        // Process cold-started by the alarm: app_start catch-up wins the lock first.
        runBlocking { engine.reconcileAll("app_start") }
        runBlocking { engine.onAlarm(alarm.occurrenceId, alarm.generation, receivedAt) }
        assertEquals(1, notifications.posted.size)
        assertEquals(receivedAt, ofType(DiagnosticType.ALARM_RECEIVED).single().at)
        assertEquals("duplicate", ofType(DiagnosticType.ALARM_IGNORED).single().outcome)
        assertEquals("reconcile:app_start", ofType(DiagnosticType.POST_REQUESTED).single().source)
    }

    @Test fun duplicateBroadcastIsRecordedAndPostsOnce() {
        oneTimeTask()
        val alarm = alarms.single()
        clock.instant = due.plusSeconds(5)
        runBlocking { engine.onAlarm(alarm.occurrenceId, alarm.generation) }
        runBlocking { engine.onAlarm(alarm.occurrenceId, alarm.generation) }
        assertEquals(2, ofType(DiagnosticType.ALARM_RECEIVED).size)
        assertEquals(1, ofType(DiagnosticType.POSTED).size)
        assertEquals("duplicate", ofType(DiagnosticType.ALARM_IGNORED).single().outcome)
        assertEquals(1, notifications.posted.size)
    }

    @Test fun onTimeDeliveryIsReportedOnTime() {
        val id = oneTimeTask()
        val alarm = alarms.single()
        clock.instant = due.plusSeconds(20)
        runBlocking { engine.onAlarm(alarm.occurrenceId, alarm.generation) }
        assertFalse(notifications.posted.single().late)
        val event = runBlocking { db.eventDao().getForTask(id) }.first { it.eventType == EventType.REMINDER_DELIVERED }
        assertEquals("Reminder delivered on time (approximate timing)", event.summary)
    }

    @Test fun deniedPermissionIsRecordedAsSuppressedWithEnvironment() {
        notifications.health = NotificationHealth(false, true, true, true)
        oneTimeTask()
        val alarm = alarms.single()
        clock.instant = due.plusSeconds(5)
        runBlocking { engine.onAlarm(alarm.occurrenceId, alarm.generation) }
        val s = ofType(DiagnosticType.SUPPRESSED).single()
        assertEquals("permission_denied", s.outcome)
        assertNotNull(s.detail)
        assertTrue(ofType(DiagnosticType.POSTED).isEmpty())
        assertTrue(ofType(DiagnosticType.POST_REQUESTED).isEmpty())
    }

    @Test fun completionLogsCancellationWithReason() {
        val id = oneTimeTask()
        runBlocking { repo.complete(id) }
        assertEquals("task_done", ofType(DiagnosticType.CANCELED).single().outcome)
        assertTrue(alarms.alarms.isEmpty())
    }

    @Test fun pauseLogsCancellationWithReason() {
        val id = runBlocking { repo.createTask(NewTask("Daily", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT)) }
        runBlocking { repo.changeStatus(id, TaskStatus.PAUSED) }
        assertEquals("task_paused", ofType(DiagnosticType.CANCELED).single().outcome)
    }

    @Test fun snoozeAndUnsnoozeAreRecordedAndRescheduleIsLogged() {
        val id = runBlocking { repo.createTask(NewTask("Train", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT)) }
        assertEquals(at(CHICAGO, 2026, 10, 16, 18, 0), alarms.single().triggerAt)
        val until = at(CHICAGO, 2026, 10, 17, 9, 30)
        runBlocking { repo.snooze(id, until, remindAtEnd = true) }
        val sn = ofType(DiagnosticType.SNOOZED).single()
        assertEquals(until, sn.plannedAt)
        assertEquals("snoozed", ofType(DiagnosticType.CANCELED).single().outcome) // 18:00 slot suppressed
        assertEquals(until, ofType(DiagnosticType.SCHEDULED).last().plannedAt)
        runBlocking { repo.clearSnooze(id) }
        assertNotNull(ofType(DiagnosticType.UNSNOOZED).singleOrNull())
        assertEquals(at(CHICAGO, 2026, 10, 16, 18, 0), alarms.single().triggerAt)
    }

    @Test fun exactUnavailableIsVisibleInTheScheduleEntry() {
        runBlocking { settings.update { it.copy(precisionMode = true) } }
        oneTimeTask()
        val s = ofType(DiagnosticType.SCHEDULED).single()
        assertEquals(false, s.exact)
        assertTrue(s.detail!!.contains("exact_unavailable"))
        alarms.exactAllowed = true
        runBlocking { engine.reconcileAll("exact_alarm_permission") }
        val upgraded = ofType(DiagnosticType.SCHEDULED).last()
        assertEquals(true, upgraded.exact)
        assertEquals("mode_changed", upgraded.outcome)
    }

    @Test fun quietHoursDeferralIsRecorded() {
        runBlocking { settings.update { it.copy(quietHours = AppSettings().quietHours) } } // 21:00–08:00
        val id = runBlocking { repo.createTask(NewTask("Late slot", status = TaskStatus.ACTIONABLE)) }
        val p = runBlocking { repo.getPolicy(id)!! }
        runBlocking { repo.savePolicy(p.copy(enabled = true, kind = ReminderKind.DAILY_TIMES, times = listOf(t(22)))) }
        val s = ofType(DiagnosticType.SCHEDULED).single()
        assertEquals(at(CHICAGO, 2026, 10, 17, 8), s.plannedAt)
        assertTrue(s.detail!!.contains("quiet_hours_deferred_from"))
    }

    @Test fun bootRecoveryRecordsReRegistrationAndMissedEntries() {
        oneTimeTask()
        alarms.clearAll()
        clock.instant = due.plus(Duration.ofHours(5)) // beyond 4 h grace
        runBlocking { engine.reconcileAll("boot") }
        val missed = ofType(DiagnosticType.MISSED).single()
        assertEquals(Duration.ofHours(5), missed.lateness)
        assertEquals("reconcile:boot", missed.source)
    }

    @Test fun idempotentReconcilesDoNotFloodTheLedger() {
        runBlocking { repo.createTask(NewTask("Train", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT)) }
        repeat(5) { runBlocking { engine.reconcileAll("app_resume") } }
        assertEquals("one SCHEDULED for the single unchanged alarm", 1, ofType(DiagnosticType.SCHEDULED).size)
        assertEquals(5, ofType(DiagnosticType.RECONCILE).size)
        runBlocking { engine.reconcileAll("boot") }
        assertEquals("recovery re-registration is recorded", "recovered", ofType(DiagnosticType.SCHEDULED).last().outcome)
    }

    @Test fun exportContainsTimingButNoTaskContent() {
        runBlocking { repo.createTask(NewTask("Secret dentist appointment", notes = "private notes", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.DAILY)) }
        val a = alarms.single()
        clock.instant = a.triggerAt.plus(Duration.ofMinutes(3))
        runBlocking { engine.onAlarm(a.occurrenceId, a.generation) }
        val text = runBlocking { log.exportText(CHICAGO) }
        assertTrue(text.contains("ALARM_RECEIVED"))
        assertTrue(text.contains("late=+3m00s"))
        assertTrue(text.contains("mode=approximate"))
        assertFalse(text.contains("Secret"))
        assertFalse(text.contains("dentist"))
        assertFalse(text.contains("private"))
    }

    @Test fun pruningKeepsTheNewestRows() {
        repeat(30) { runBlocking { log.record(DiagnosticType.RECONCILE) { copy(source = "x$it") } }; clock.advance(Duration.ofSeconds(1)) }
        runBlocking { log.prune(keep = 10) }
        val rows = runBlocking { log.recent(100) }
        assertEquals(10, rows.size)
        assertEquals("x29", rows.first().source)
        assertNull(rows.firstOrNull { it.source == "x0" })
    }
}
