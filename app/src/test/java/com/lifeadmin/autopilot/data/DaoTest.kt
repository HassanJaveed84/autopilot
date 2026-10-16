package com.lifeadmin.autopilot.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.ReminderOccurrenceEntity
import com.lifeadmin.autopilot.data.local.ReminderPolicyEntity
import com.lifeadmin.autopilot.data.local.ReminderTimeSlotEntity
import com.lifeadmin.autopilot.data.local.TaskEntity
import com.lifeadmin.autopilot.data.local.TaskStepEntity
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: AppDatabase
    private val now = Instant.parse("2026-10-16T12:00:00Z")

    @Before fun setUp() { db = inMemoryDatabase() }
    @After fun tearDown() { db.close() }

    private fun taskEntity(id: String) = TaskEntity(
        id, "Book train ride", null, TaskStatus.ACTIONABLE, Priority.NORMAL, now, now, null, null,
        DeadlineType.NONE, null, null, null, null, null, null,
    )

    private fun policy(id: String, taskId: String) = ReminderPolicyEntity(
        id, taskId, true, ReminderKind.DAILY_TIMES, null, ZoneMode.FOLLOW_DEVICE, null, 1, null, null, null, now, 0,
    )

    private fun occurrence(id: String, plannedAt: Instant) = ReminderOccurrenceEntity(
        id, "p1", "t1", OccurrenceKind.POLICY, plannedAt, plannedAt, null, null, OccurrenceStatus.SCHEDULED, 0,
    )

    @Test fun uniqueOccurrenceIndexPreventsDuplicates() = runTest {
        db.taskDao().insert(taskEntity("t1"))
        db.policyDao().upsert(policy("p1", "t1"))
        val at = now.plusSeconds(3600)
        db.occurrenceDao().insert(occurrence("o1", at))
        try {
            db.occurrenceDao().insert(occurrence("o2", at))
            fail("Expected unique constraint violation")
        } catch (expected: SQLiteConstraintException) {
            // ok
        }
        assertEquals(1, db.occurrenceDao().getAll().size)
    }

    @Test fun deletingTaskCascadesToChildren() = runTest {
        db.taskDao().insert(taskEntity("t1"))
        db.policyDao().upsert(policy("p1", "t1"))
        db.policyDao().insertSlots(listOf(ReminderTimeSlotEntity("s1", "p1", LocalTime.of(9, 0), 0), ReminderTimeSlotEntity("s2", "p1", LocalTime.of(18, 0), 1)))
        db.stepDao().upsert(TaskStepEntity("st1", "t1", 0, "Find dates", false, null))
        db.occurrenceDao().insert(occurrence("o1", now.plusSeconds(60)))

        db.taskDao().delete("t1")

        assertNull(db.policyDao().getForTask("t1"))
        assertEquals(0, db.policyDao().getAllSlots().size)
        assertEquals(0, db.stepDao().getAll().size)
        assertEquals(0, db.occurrenceDao().getAll().size)
    }

    @Test fun stepRequiresExistingTask() = runTest {
        try {
            db.stepDao().upsert(TaskStepEntity("st1", "missing", 0, "Orphan", false, null))
            fail("Expected foreign key violation")
        } catch (expected: SQLiteConstraintException) {
            // ok
        }
    }

    @Test fun compareAndSetClaimsOnlyOnce() = runTest {
        db.taskDao().insert(taskEntity("t1"))
        db.policyDao().upsert(policy("p1", "t1"))
        db.occurrenceDao().insert(occurrence("o1", now))
        val first = db.occurrenceDao().compareAndSetStatus("o1", OccurrenceStatus.SCHEDULED, OccurrenceStatus.DELIVERED, 0, now)
        val second = db.occurrenceDao().compareAndSetStatus("o1", OccurrenceStatus.SCHEDULED, OccurrenceStatus.DELIVERED, 0, now)
        assertEquals(1, first)
        assertEquals(0, second)
    }

    @Test fun timeSlotsRoundTripInOrder() = runTest {
        db.taskDao().insert(taskEntity("t1"))
        db.policyDao().upsert(policy("p1", "t1"))
        db.policyDao().insertSlots(listOf(ReminderTimeSlotEntity("s2", "p1", LocalTime.of(18, 0), 1), ReminderTimeSlotEntity("s1", "p1", LocalTime.of(9, 0), 0)))
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), db.policyDao().getSlots("p1").map { it.localTime })
    }
}
