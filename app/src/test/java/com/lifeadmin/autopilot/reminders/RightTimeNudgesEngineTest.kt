package com.lifeadmin.autopilot.reminders

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.ValidationException
import com.lifeadmin.autopilot.domain.capture.CaptureTiming
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.StallDetector
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * Right-time nudges end to end through Room and the engine: what capture saves for the
 * milestone's phrases, the per-task quiet-hours exception, bedtime snooze, and "Done for
 * tonight" versus permanent Done.
 */
@RunWith(AndroidJUnit4::class)
class RightTimeNudgesEngineTest {
    private lateinit var db: AppDatabase
    private val zone = FakeZoneProvider(CHICAGO)
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 23, 20)) // Fri 20:00
    private val alarms = FakeAlarmDriver()
    private val notifications = FakeNotificationPublisher()
    private val settings = InMemorySettingsRepository(AppSettings()) // quiet hours 21:00–08:00, bedtime 21:30
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

    /** What the capture sheet saves for [text] (the reading, then the user's quiet-hours choice). */
    private fun capture(text: String, ring: Boolean = false): String = runBlocking {
        val s = settings.current()
        val reading = CaptureTiming.read(text, clock.now(), zone.zone, s.bedtime)
        val policy = reading.nudge?.let { CaptureTiming.policyFor(it, s, clock.now(), zone.zone, ringInQuietHours = ring) }
        actions.capture(text, null, Priority.NORMAL, nudge = true, policy = policy, deadline = reading.deadline?.spec).taskId
    }

    private fun fireNext(delay: Duration = Duration.ofSeconds(5)): AlarmOutcome = runBlocking {
        val a = alarms.earliest() ?: error("no alarm scheduled")
        clock.instant = a.triggerAt.plus(delay)
        alarms.fired(a.occurrenceId)
        engine.onAlarm(a.occurrenceId, a.generation)
    }

    private fun events(id: String) = runBlocking { db.eventDao().getForTask(id).map { it.eventType } }

    @Test fun fanBeforeBedWithoutTheExceptionIsHeldUntilMorningAndSaysSo() {
        val id = capture("Turn off fan before bed")
        val p = runBlocking { repo.getPolicy(id)!! }
        assertEquals(ReminderKind.ONCE, p.kind)
        assertEquals(at(CHICAGO, 2026, 10, 23, 21, 30), p.atInstant)
        assertFalse(p.ringInQuietHours)
        assertEquals("held by quiet hours (the capture sheet says so before saving)", at(CHICAGO, 2026, 10, 24, 8), alarms.single().triggerAt)
    }

    @Test fun fanBeforeBedWithTheExceptionRingsAtBedtime() {
        val id = capture("Turn off fan before bed", ring = true)
        assertEquals(at(CHICAGO, 2026, 10, 23, 21, 30), alarms.single().triggerAt)
        assertEquals(AlarmOutcome.DELIVERED, fireNext())
        val n = notifications.posted.single()
        assertNull("a one-off nudge keeps the permanent Done", n.doneForNowLabel)
        // Bedtime snooze: 15 minutes, still in the evening thanks to the exception.
        val until = runBlocking { actions.snoozeWithDefault(id) }
        assertEquals("15 minutes after the 21:30:05 delivery", at(CHICAGO, 2026, 10, 23, 21, 45).plusSeconds(5), until)
        assertEquals(until, alarms.single().triggerAt)
        assertEquals(AlarmOutcome.DELIVERED, fireNext())
        assertEquals(2, notifications.posted.size)
    }

    @Test fun blanketEveryNightIsARoutineAnsweredPerNight() {
        val id = capture("Check daughter's blanket every night", ring = true)
        val p = runBlocking { repo.getPolicy(id)!! }
        assertTrue(p.isRoutine)
        assertEquals(at(CHICAGO, 2026, 10, 23, 21, 30), alarms.single().triggerAt)
        assertEquals(AlarmOutcome.DELIVERED, fireNext())
        assertEquals("Done for tonight", notifications.posted.single().doneForNowLabel)

        val resume = runBlocking { repo.finishCycle(id) }
        assertEquals(at(CHICAGO, 2026, 10, 24, 5), resume)
        val t = runBlocking { repo.getTask(id)!! }
        assertEquals("the task stays open", TaskStatus.ACTIONABLE, t.status)
        assertNull(t.completedAt)
        assertFalse("the notification is cleared", notifications.visible.containsKey(id))
        assertEquals("tomorrow's nudge is scheduled", at(CHICAGO, 2026, 10, 24, 21, 30), alarms.single().triggerAt)
        assertEquals(1, events(id).count { it == EventType.OCCURRENCE_DONE })

        // Same night again: nothing new.
        runBlocking { repo.finishCycle(id) }
        assertEquals(1, events(id).count { it == EventType.OCCURRENCE_DONE })

        // Next night it rings and can be answered again.
        assertEquals(AlarmOutcome.DELIVERED, fireNext())
        runBlocking { repo.finishCycle(id) }
        assertEquals(at(CHICAGO, 2026, 10, 25, 21, 30), alarms.single().triggerAt)
    }

    @Test fun lastNightsNudgeHeldUntilMorningAnswersOnlyLastNight() {
        // No quiet-hours exception: Friday's 21:30 is held until Saturday 08:00.
        val id = capture("Check daughter's blanket every night")
        assertEquals(at(CHICAGO, 2026, 10, 24, 8), alarms.single().triggerAt)
        assertEquals(AlarmOutcome.DELIVERED, fireNext())
        assertEquals("Done for last night", notifications.posted.single().doneForNowLabel)
        val slot = runBlocking { repo.latestDeliveredSlot(id) }
        assertEquals(at(CHICAGO, 2026, 10, 23, 21, 30), slot)
        runBlocking { repo.finishCycle(id, slot) }
        assertNull("nothing about tonight is silenced", runBlocking { repo.getTask(id)!!.cycleDoneUntil })
        assertTrue(EventType.OCCURRENCE_DONE in events(id))
        assertFalse(notifications.visible.containsKey(id))
        assertEquals("Saturday's nudge (held to Sunday 08:00) is still planned", at(CHICAGO, 2026, 10, 25, 8), alarms.single().triggerAt)
    }

    @Test fun doneForTonightBeforeTheNudgeSilencesTonightAndUndoBringsItBack() {
        val id = capture("Check daughter's blanket every night", ring = true)
        runBlocking { repo.finishCycle(id) } // 20:00, before tonight's 21:30
        assertEquals(at(CHICAGO, 2026, 10, 24, 21, 30), alarms.single().triggerAt)
        runBlocking { repo.undoFinishCycle(id) }
        assertEquals(at(CHICAGO, 2026, 10, 23, 21, 30), alarms.single().triggerAt)
        assertNull(runBlocking { repo.getTask(id)!!.cycleDoneUntil })
        assertTrue(EventType.OCCURRENCE_UNDONE in events(id))
    }

    @Test fun markDoneOnARoutineIsStillPermanent() {
        val id = capture("Check daughter's blanket every night", ring = true)
        runBlocking { repo.complete(id) }
        val t = runBlocking { repo.getTask(id)!! }
        assertEquals(TaskStatus.DONE, t.status)
        assertTrue(alarms.alarms.isEmpty())
        try {
            runBlocking { repo.finishCycle(id) }
            fail("a closed task has no cycle to finish")
        } catch (e: ValidationException) { /* expected */ }
    }

    @Test fun doneForNowIsRefusedForTasksThatAreNotRoutines() {
        val id = runBlocking { repo.createTask(NewTask("Buy a gift for someone", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.GENTLE)) }
        try {
            runBlocking { repo.finishCycle(id) }
            fail("“until done” nudges are not a routine")
        } catch (e: ValidationException) { /* expected */ }
        assertNull(runBlocking { repo.getTask(id)!!.cycleDoneUntil })
        assertFalse(EventType.OCCURRENCE_DONE in events(id))
    }

    @Test fun answeringARoutineNightlyNeverLooksStuck() {
        val id = capture("Check daughter's blanket every night", ring = true)
        repeat(8) {
            assertEquals(AlarmOutcome.DELIVERED, fireNext())
            runBlocking { repo.finishCycle(id) }
        }
        val d = runBlocking { repo.observeDetails(id).first()!! }
        assertNull("nightly “Done for tonight” is progress, not a stall", StallDetector.detect(d, clock.now(), zone.zone))
        assertEquals("never counted as snoozes", 0, d.events.count { it.type == EventType.SNOOZED })
    }

    @Test fun ignoringARoutineForAWeekIsStillNoticed() {
        val id = capture("Check daughter's blanket every night", ring = true)
        repeat(6) { assertEquals(AlarmOutcome.DELIVERED, fireNext()) }
        val d = runBlocking { repo.observeDetails(id).first()!! }
        assertTrue(StallDetector.detect(d, clock.now(), zone.zone)!!.message.contains("reminded 6 times"))
    }

    @Test fun workUpdateByMonday5pmSavesTheDeadlineAndTheDefaultNudge() {
        val id = capture("Complete work update by Monday 5pm")
        val t = runBlocking { repo.getTask(id)!! }
        assertEquals(at(CHICAGO, 2026, 10, 26, 17), t.deadlineInstant)
        val p = runBlocking { repo.getPolicy(id)!! }
        assertEquals("no timing for the nudge itself → the default (Gentle on a new install)", ReminderKind.EVERY_N_DAYS, p.kind)
        assertEquals("Complete work update by Monday 5pm", t.title)
    }

    @Test fun giftForSomeoneGetsTheLighterDefaultAndNothingElse() {
        val id = capture("Buy a gift for someone")
        val t = runBlocking { repo.getTask(id)!! }
        assertEquals("Buy a gift for someone", t.title)
        assertNull(t.notes)
        assertNull(t.deadlineInstant)
        assertNull(t.deadlineLocalDate)
        assertTrue(runBlocking { repo.getSteps(id) }.isEmpty())
        val p = runBlocking { repo.getPolicy(id)!! }
        assertEquals(2, p.dayInterval)
        assertEquals(listOf(java.time.LocalTime.of(10, 0)), p.times)
        assertFalse(p.routine)
    }

    @Test fun anExistingTasksScheduleIsUntouchedByTheNewDefaults() {
        // A task saved by an earlier version with Persistent stays on 09:00 and 18:00.
        val id = runBlocking { repo.createTask(NewTask("Book train ride", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT)) }
        runBlocking { settings.update { it.copy(bedtime = java.time.LocalTime.of(22, 15)) } }
        runBlocking { engine.reconcileAll("app_start") }
        val p = runBlocking { repo.getPolicy(id)!! }
        assertEquals(listOf(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0)), p.times)
        assertFalse(p.routine)
        assertFalse(p.ringInQuietHours)
        assertEquals(at(CHICAGO, 2026, 10, 24, 9), alarms.single().triggerAt)
    }
}
