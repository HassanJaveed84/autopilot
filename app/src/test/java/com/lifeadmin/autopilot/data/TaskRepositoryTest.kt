package com.lifeadmin.autopilot.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.ValidationException
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.testing.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TaskRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TaskRepository
    private val clock = FakeClock(Instant.parse("2026-10-16T14:00:00Z"))

    @Before fun setUp() {
        db = inMemoryDatabase()
        repo = TaskRepository(db, clock, SequentialIds(), { CHICAGO }, ScheduleCoordinator.Direct)
    }

    @After fun tearDown() = db.close()

    @Test fun quickCaptureToInboxHasNoReminderAndNoDeadline() = runTest {
        val id = repo.createTask(NewTask("Need to renew car registration next month"))
        val task = repo.getTask(id)!!
        assertEquals(TaskStatus.INBOX, task.status)
        assertEquals(com.lifeadmin.autopilot.domain.model.DeadlineType.NONE, task.deadlineType)
        val policy = repo.getPolicy(id)!!
        assertEquals(false, policy.enabled)
        assertEquals(EventType.CREATED, db.eventDao().getForTask(id).single().eventType)
    }

    @Test fun persistentPresetCreatesOneTaskWithTwoSlots() = runTest {
        val id = repo.createTask(NewTask("Book train ride", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
        val policy = repo.getPolicy(id)!!
        assertTrue(policy.enabled)
        assertEquals(ReminderKind.DAILY_TIMES, policy.kind)
        assertEquals(listOf(t(9), t(18)), policy.times)
        assertEquals(1, db.taskDao().count())
        assertEquals(1, db.policyDao().getAll().size)
        assertEquals(2, db.policyDao().getAllSlots().size)
    }

    @Test fun editLogsChangedFieldsWithoutNotesContent() = runTest {
        val id = repo.createTask(NewTask("Arrange repair"))
        repo.updateDetails(id, "Arrange boiler repair", "secret door code 1234", Priority.HIGH)
        val t = repo.getTask(id)!!
        assertEquals("Arrange boiler repair", t.title)
        assertEquals(Priority.HIGH, t.priority)
        val edit = db.eventDao().getForTask(id).first { it.eventType == EventType.EDITED }
        assertTrue(edit.summary.contains("notes"))
        assertTrue("event must not contain note text", !edit.summary.contains("1234") && !(edit.structuredMetadata ?: "").contains("1234"))
    }

    @Test fun blankTitleIsRejected() = runTest {
        try {
            repo.createTask(NewTask("   "))
            throw AssertionError("expected validation failure")
        } catch (e: ValidationException) {
            assertEquals(0, db.taskDao().count())
        }
    }

    @Test fun statusLifecycleAndReopen() = runTest {
        val id = repo.createTask(NewTask("Follow up on request"))
        repo.changeStatus(id, TaskStatus.ACTIONABLE)
        repo.changeStatus(id, TaskStatus.WAITING, "Waiting for the mechanic", clock.now().plus(Duration.ofDays(3)))
        assertEquals("Waiting for the mechanic", repo.getTask(id)!!.waitingReason)
        repo.changeStatus(id, TaskStatus.ACTIONABLE)
        assertNull(repo.getTask(id)!!.waitingCheckInAt)
        repo.complete(id)
        val done = repo.getTask(id)!!
        assertEquals(TaskStatus.DONE, done.status)
        assertNotNull(done.completedAt)
        val genBefore = repo.getPolicy(id)!!.generation
        repo.reopen(id)
        val reopened = repo.getTask(id)!!
        assertEquals(TaskStatus.ACTIONABLE, reopened.status)
        assertNull(reopened.completedAt)
        assertTrue("reopen bumps generation", repo.getPolicy(id)!!.generation > genBefore)
        val types = db.eventDao().getForTask(id).map { it.eventType }
        assertTrue(EventType.REOPENED in types)
        assertEquals(4, types.count { it == EventType.STATUS_CHANGED })
    }

    @Test(expected = IllegalArgumentException::class)
    fun doneCannotGoDirectlyToWaiting() = runTest {
        val id = repo.createTask(NewTask("x"))
        repo.complete(id)
        repo.changeStatus(id, TaskStatus.WAITING)
    }

    @Test fun stepsAddToggleReorderDeleteAndCascade() = runTest {
        val id = repo.createTask(NewTask("Book train ride"))
        val a = repo.addStep(id, "Find dates")
        val b = repo.addStep(id, "Check parking")
        val c = repo.addStep(id, "Purchase")
        repo.setStepDone(b, true)
        repo.moveStep(c, -2)
        assertEquals(listOf("Purchase", "Find dates", "Check parking"), repo.getSteps(id).map { it.title })
        assertTrue(repo.getSteps(id).first { it.id == b }.isDone)
        // Steps never auto-complete the parent.
        repo.setStepDone(a, true); repo.setStepDone(c, true)
        assertEquals(TaskStatus.INBOX, repo.getTask(id)!!.status)
        repo.deleteStep(a)
        assertEquals(2, repo.getSteps(id).size)
        repo.delete(id)
        assertEquals(0, db.stepDao().getAll().size)
        assertEquals(0, db.eventDao().getAll().size)
    }

    @Test fun snoozeIsOrthogonalToStatus() = runTest {
        val id = repo.createTask(NewTask("Call bank", status = TaskStatus.ACTIONABLE))
        repo.snooze(id, clock.now().plus(Duration.ofHours(3)), remindAtEnd = false)
        val t = repo.getTask(id)!!
        assertEquals(TaskStatus.ACTIONABLE, t.status)
        assertEquals(false, t.snoozeRemindAtEnd)
        repo.clearSnooze(id)
        assertNull(repo.getTask(id)!!.snoozedUntil)
    }

    @Test fun archiveHidesButKeepsTask() = runTest {
        val id = repo.createTask(NewTask("Old thing"))
        repo.archive(id)
        assertNotNull(repo.getTask(id)!!.archivedAt)
        repo.unarchive(id)
        assertNull(repo.getTask(id)!!.archivedAt)
    }

    @Test fun summariesExposeStepProgress() = runTest {
        val id = repo.createTask(NewTask("Book train ride"))
        repo.addStep(id, "One"); val two = repo.addStep(id, "Two")
        repo.setStepDone(two, true)
        val s = repo.observeSummaries().first().single()
        assertEquals(1, s.stepsDone)
        assertEquals(2, s.stepsTotal)
        assertEquals("One", s.nextStepTitle)
    }

    @Test fun tasksSurviveProcessRestart() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "restart-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).setQueryCoroutineContext(Dispatchers.IO).build()
        val first = open()
        val r1 = TaskRepository(first, clock, SequentialIds("a"), { CHICAGO }, ScheduleCoordinator.Direct)
        val id = r1.createTask(NewTask("Persist me", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
        r1.addStep(id, "Step survives")
        first.close() // simulate process death

        val second = open()
        val r2 = TaskRepository(second, clock, SequentialIds("b"), { CHICAGO }, ScheduleCoordinator.Direct)
        assertEquals("Persist me", r2.getTask(id)!!.title)
        assertEquals(listOf("Step survives"), r2.getSteps(id).map { it.title })
        assertEquals(listOf(t(9), t(18)), r2.getPolicy(id)!!.times)
        second.close()
        context.deleteDatabase(name)
    }
}
