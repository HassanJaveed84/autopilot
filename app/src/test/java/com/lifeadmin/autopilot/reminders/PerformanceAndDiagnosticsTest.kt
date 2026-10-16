package com.lifeadmin.autopilot.reminders

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.fileDatabase
import com.lifeadmin.autopilot.testing.t
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/** SPEC §10.3 #14 baseline on the JVM: 100 tasks / 200+ slots → sane alarm count, fast reconcile. */
@RunWith(AndroidJUnit4::class)
class PerformanceAndDiagnosticsTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 16, 7, 0))
    private val zone = FakeZoneProvider(CHICAGO)
    private val alarms = FakeAlarmDriver()
    private val notifications = FakeNotificationPublisher()
    private val settings = InMemorySettingsRepository(AppSettings())
    private lateinit var engine: ReminderEngine
    private lateinit var repo: TaskRepository

    @Before fun setUp() {
        db = fileDatabase("perf-test.db")
        val ids = SequentialIds()
        engine = ReminderEngine(db, settings, alarms, notifications, clock, zone, ids)
        repo = TaskRepository(db, clock, ids, zone::current, engine)
    }

    @After fun tearDown() = db.close()

    @Test fun hundredTasksWithThreeSlotsEachKeepOneAlarmPerTask() = runBlocking {
        val createMs = measureTimeMillis {
            repeat(100) { i ->
                val id = repo.createTask(NewTask("Secret task title $i", notes = "private note $i", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
                val p = repo.getPolicy(id)!!
                repo.savePolicy(p.copy(times = listOf(t(9), t(13), t(18))))
            }
        }
        assertEquals(300, db.policyDao().getAllSlots().size)
        assertEquals("one pending OS alarm per task, far below Android's 500-alarm cap", 100, alarms.alarms.size)

        val reconcileMs = measureTimeMillis { engine.reconcileAll("perf") }
        println("PERF create100=${createMs}ms reconcileAll100=${reconcileMs}ms")
        assertTrue("reconcileAll of 100 tasks should be well under 5 s on the JVM (was $reconcileMs ms)", reconcileMs < 5_000)
        assertEquals(100, alarms.alarms.size)

        val report = Diagnostics(db, settings, alarms, notifications, clock, zone, "test", 35).report()
        println(report)
        assertTrue(report.contains("tasks_total=100"))
        assertTrue(report.contains("occurrences_active=100"))
        assertFalse("diagnostics must not leak titles", report.contains("Secret task title"))
        assertFalse("diagnostics must not leak notes", report.contains("private note"))
    }
}
