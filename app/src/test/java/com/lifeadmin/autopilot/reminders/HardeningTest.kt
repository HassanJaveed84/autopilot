package com.lifeadmin.autopilot.reminders

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DiagnosticEntry
import com.lifeadmin.autopilot.domain.model.DiagnosticType
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.NEW_YORK
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.fileDatabase
import com.lifeadmin.autopilot.ui.health.ReminderHealthViewModel
import com.lifeadmin.autopilot.ui.health.recentLateApproximateDelivery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class HardeningTest {
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 16, 17, 0))
    private val zone = FakeZoneProvider(CHICAGO)
    private val alarms = FakeAlarmDriver()
    private val notifications = FakeNotificationPublisher()
    private val probe = FakeEnvironmentProbe()
    private val settings = InMemorySettingsRepository(AppSettings(quietHours = AppSettings().quietHours.copy(enabled = false)))
    private lateinit var c: AppContainer

    @Before fun setUp() {
        c = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = clock, zoneProvider = zone, ids = SequentialIds(),
            database = fileDatabase("hardening.db"), settingsRepository = settings, alarmDriver = alarms,
            notificationPublisher = notifications, environmentProbe = probe,
        )
    }

    private fun oneTime(atInstant: java.time.Instant): String = runBlocking {
        val id = c.taskRepository.createTask(NewTask("Test", status = TaskStatus.ACTIONABLE))
        val p = c.taskRepository.getPolicy(id)!!
        c.taskRepository.savePolicy(p.copy(enabled = true, kind = ReminderKind.ONCE, atInstant = atInstant, times = emptyList()))
        id
    }

    @Test fun oneTimeReminderKeepsItsAbsoluteMomentAcrossTimeZoneChange() {
        val due = at(CHICAGO, 2026, 10, 16, 18, 25)
        oneTime(due)
        zone.zone = NEW_YORK
        runBlocking { c.reconcileAll("timezone_changed") }
        assertEquals("once = a specific moment; recurring slots follow wall-clock", due, alarms.single().triggerAt)
    }

    @Test fun hourlySafetyNetCatchesUpADroppedAlarmWithinTheGraceWindow() {
        val due = at(CHICAGO, 2026, 10, 16, 18, 25)
        oneTime(due)
        alarms.clearAll() // OS dropped the alarm
        clock.instant = due.plus(Duration.ofMinutes(55)) // next hourly periodic run
        runBlocking { c.reconcileAll("periodic") }
        assertEquals(1, notifications.posted.size)
        val req = runBlocking { c.diagnosticsLog.recentOfTypes(setOf(DiagnosticType.POST_REQUESTED)) }.single()
        assertEquals("reconcile:periodic", req.source)
    }

    @Test fun lateBannerOnlyAppearsForRecentLateApproximateDeliveries() {
        fun post(late: Duration, exact: Boolean, ago: Duration) = runBlocking {
            c.diagnosticsLog.record(DiagnosticEntry(id = "d${late.seconds}${exact}${ago.seconds}", at = clock.now().minus(ago), type = DiagnosticType.POST_REQUESTED, exact = exact, lateness = late))
        }
        val s = AppSettings()
        post(Duration.ofMinutes(24), exact = false, ago = Duration.ofHours(1))
        assertEquals(Duration.ofMinutes(24), runBlocking { recentLateApproximateDelivery(c, s, canExact = false) })
        assertNull("precise timing active", runBlocking { recentLateApproximateDelivery(c, s.copy(precisionMode = true), canExact = true) })
        post(Duration.ofMinutes(2), exact = false, ago = Duration.ofMinutes(30)) // newer, on time
        assertNull(runBlocking { recentLateApproximateDelivery(c, s, canExact = false) })
    }

    @Test fun lateBannerIgnoresOldDeliveries() {
        runBlocking { c.diagnosticsLog.record(DiagnosticEntry(id = "old", at = clock.now().minus(Duration.ofDays(3)), type = DiagnosticType.POST_REQUESTED, exact = false, lateness = Duration.ofMinutes(40))) }
        assertNull(runBlocking { recentLateApproximateDelivery(c, AppSettings(), canExact = false) })
    }

    @Test fun returningFromExactAlarmSettingsUpgradesExistingAlarms() {
        runBlocking { settings.update { it.copy(precisionMode = true) } }
        runBlocking { c.taskRepository.createTask(NewTask("Train", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT)) }
        assertEquals(false, alarms.exactFlags.values.single())
        alarms.exactAllowed = true // user granted "Alarms & reminders" and came back
        probe.env = probe.env.copy(canScheduleExact = true) // the real probe reads AlarmManager.canScheduleExactAlarms()
        val vm = ReminderHealthViewModel(c)
        vm.refresh()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && alarms.exactFlags.values.singleOrNull() != true) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
        }
        assertEquals(true, alarms.exactFlags.values.single())
        val modeChange = runBlocking { c.diagnosticsLog.recentOfTypes(setOf(DiagnosticType.SCHEDULED)) }.first()
        assertEquals("mode_changed", modeChange.outcome)
        assertTrue(modeChange.exact == true)
    }
}
