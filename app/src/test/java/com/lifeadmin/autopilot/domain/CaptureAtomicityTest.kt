package com.lifeadmin.autopilot.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.ValidationException
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineSpec
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.reminders.ReminderEngine
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.dailyPolicy
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.testing.t
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * A capture is one transaction: a failure in the follow-up or deadline leaves no half-made task,
 * and a retry with the same capture id creates nothing new.
 */
@RunWith(AndroidJUnit4::class)
class CaptureAtomicityTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 19, 8, 30))
    private val zone = FakeZoneProvider(CHICAGO)
    private val alarms = FakeAlarmDriver()
    private val settings = InMemorySettingsRepository(AppSettings())
    private lateinit var repo: TaskRepository
    private lateinit var actions: TaskActions

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds("cap")
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, alarms, FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        repo = TaskRepository(db, clock, ids, zone::current, engine, log)
        actions = TaskActions(db, repo, settings, clock, zone)
    }

    @After fun tearDown() = db.close()

    private fun tasks() = runBlocking { db.taskDao().getAll() }

    @Test fun invalidCustomFollowUpLeavesNoTaskBehind() = runBlocking {
        val broken = dailyPolicy().copy(kind = ReminderKind.DAILY_TIMES, times = emptyList(), enabled = true)
        try {
            actions.capture("Book train ride", null, Priority.NORMAL, nudge = true, policy = broken, deadline = null)
            fail("expected validation to fail")
        } catch (e: ValidationException) {
            assertTrue(e.message!!.contains("at least one reminder time"))
        }
        assertEquals(0, tasks().size)
        assertEquals(0, db.policyDao().getAll().size)
        assertTrue(alarms.alarms.isEmpty())
    }

    @Test fun invalidDeadlineLeavesNoTaskBehind() = runBlocking {
        try {
            actions.capture("Book train ride", null, Priority.NORMAL, nudge = true, policy = null, deadline = DeadlineSpec(DeadlineType.EXACT, instant = null))
            fail("expected validation to fail")
        } catch (e: ValidationException) {
            assertTrue(e.message!!.contains("Exact deadline needs a time"))
        }
        assertEquals(0, tasks().size)
        assertTrue(alarms.alarms.isEmpty())
    }

    @Test fun retryWithTheSameCaptureIdCreatesOneTask() = runBlocking {
        val first = actions.capture("Book train ride", "For Mia", Priority.HIGH, nudge = true, policy = null, deadline = null, captureId = "capture-1")
        val second = actions.capture("Book train ride", "For Mia", Priority.HIGH, nudge = true, policy = null, deadline = null, captureId = "capture-1")
        assertEquals(first.taskId, second.taskId)
        assertEquals(1, tasks().size)
        assertEquals(1, alarms.forTask(first.taskId).size)
    }

    @Test fun customFollowUpAndDeadlineAreWrittenTogetherAndScheduledOnce() = runBlocking {
        val custom = dailyPolicy(t(7), t(19)).copy(enabled = true)
        val due = LocalDate.of(2026, 10, 30)
        val r = actions.capture("Renew passport", null, Priority.HIGH, nudge = true, policy = custom, deadline = DeadlineSpec(DeadlineType.DATE_ONLY, date = due))
        val task = tasks().single()
        assertEquals(TaskStatus.ACTIONABLE, task.status)
        assertEquals(DeadlineType.DATE_ONLY, task.deadlineType)
        assertEquals(due, task.deadlineLocalDate)
        val policy = repo.getPolicy(r.taskId)!!
        assertTrue(policy.enabled)
        assertEquals(listOf(t(7), t(19)), policy.times)
        assertEquals(r.taskId, policy.taskId)
        assertNotNull("first nudge is planned", r.firstNudge)
        assertEquals(at(CHICAGO, 2026, 10, 19, 19, 0), r.firstNudge!!.plannedAt)
        assertEquals(1, alarms.forTask(r.taskId).size)
    }

    @Test fun inboxCaptureIgnoresTheFollowUpAndSchedulesNothing() = runBlocking {
        val r = actions.capture("Call the bank", null, Priority.NORMAL, nudge = false, policy = dailyPolicy(t(9)).copy(enabled = true), deadline = null)
        assertEquals(TaskStatus.INBOX, tasks().single().status)
        assertTrue(repo.getPolicy(r.taskId)?.enabled != true)
        assertTrue(alarms.alarms.isEmpty())
    }
}
