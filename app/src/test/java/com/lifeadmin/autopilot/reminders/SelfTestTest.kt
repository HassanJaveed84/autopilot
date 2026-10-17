package com.lifeadmin.autopilot.reminders

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.model.DiagnosticType
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class SelfTestTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 17, 9, 0))
    private val alarms = FakeAlarmDriver()
    private val notifications = FakeNotificationPublisher()
    private val settings = InMemorySettingsRepository(AppSettings())
    private lateinit var log: RoomDiagnosticsLog
    private lateinit var selfTest: ReminderSelfTest

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds("t")
        log = RoomDiagnosticsLog(db, clock, ids)
        selfTest = ReminderSelfTest(log, alarms, notifications, FakeEnvironmentProbe(DeliveryEnvironment()), settings, clock, FakeZoneProvider(CHICAGO), ids) { log.forOccurrence(it) }
    }

    @After fun tearDown() = db.close()

    @Test fun testNotificationNowPostsOnTheReminderChannelAndIsLogged() {
        val e = runBlocking { selfTest.sendTestNotificationNow() }
        assertEquals(DiagnosticType.TEST_NOTIFICATION, e.type)
        assertEquals("listed_active", e.outcome)
        assertEquals(1, notifications.tests.size)
        assertEquals(clock.now(), notifications.tests.single().postedAt)
    }

    @Test fun testNotificationWhenBlockedIsReportedAsFailed() {
        notifications.health = NotificationHealth(false, true, true, true)
        val e = runBlocking { selfTest.sendTestNotificationNow() }
        assertEquals(DiagnosticType.POST_FAILED, e.type)
        assertEquals("blocked_permission", e.outcome)
    }

    @Test fun scheduledTestReminderMeasuresAlarmLatenessAndPostsOnce() {
        val s = runBlocking { selfTest.scheduleTestReminder(Duration.ofMinutes(2)) }
        assertEquals(clock.now().plus(Duration.ofMinutes(2)), s.triggerAt)
        assertEquals(false, s.exact)
        assertEquals(s.triggerAt to false, alarms.tests[s.testId])

        val receivedAt = s.triggerAt.plusSeconds(47)
        clock.instant = receivedAt
        runBlocking { selfTest.onTestAlarm(s.testId, receivedAt) }
        runBlocking { selfTest.onTestAlarm(s.testId, receivedAt.plusSeconds(1)) } // duplicate broadcast

        val entries = runBlocking { log.forOccurrence(s.testId) }
        val received = entries.single { it.type == DiagnosticType.TEST_ALARM_RECEIVED }
        assertEquals(Duration.ofSeconds(47), received.lateness)
        assertEquals(1, entries.count { it.type == DiagnosticType.TEST_NOTIFICATION })
        assertEquals("duplicate", entries.single { it.type == DiagnosticType.ALARM_IGNORED }.outcome)
        assertEquals(1, notifications.tests.size)
        assertTrue(notifications.tests.single().body.contains("+47s"))
    }

    @Test fun testReminderUsesExactWhenPreciseTimingOnAndGranted() {
        runBlocking { settings.update { it.copy(precisionMode = true) } }
        alarms.exactAllowed = true
        val s = runBlocking { selfTest.scheduleTestReminder() }
        assertTrue(s.exact)
        alarms.exactAllowed = false
        val s2 = runBlocking { selfTest.scheduleTestReminder() }
        assertEquals(false, s2.exact)
        assertEquals("exact_unavailable", s2.error)
    }
}
