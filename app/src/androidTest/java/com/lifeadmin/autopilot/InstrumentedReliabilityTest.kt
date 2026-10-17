package com.lifeadmin.autopilot

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.lifeadmin.autopilot.domain.model.DiagnosticType
import com.lifeadmin.autopilot.notifications.AndroidNotificationPublisher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * Real AlarmManager / NotificationManager / channel behaviour on an emulator. Verifies wiring and
 * recorded timestamps; it cannot verify that a human noticed anything.
 */
@RunWith(AndroidJUnit4::class)
class InstrumentedReliabilityTest {
    @get:Rule
    val permission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer
    private val nm = context.getSystemService(NotificationManager::class.java)

    @Test fun freshInstallReminderChannelVibratesAndAlerts() {
        container.notificationPublisher.ensureChannels()
        val c = nm.getNotificationChannel(AndroidNotificationPublisher.CHANNEL_REMINDERS)
        assertNotNull(c)
        // CI installs the app on a fresh emulator, so this is the new-install default.
        assertTrue(c.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, c.importance)
    }

    @Test fun testNotificationNowIsPostedAndListedActive() {
        val e = runBlocking { container.selfTest.sendTestNotificationNow() }
        assertEquals(DiagnosticType.TEST_NOTIFICATION, e.type)
        assertEquals("listed_active", e.outcome)
        val n = nm.activeNotifications.firstOrNull { it.tag == AndroidNotificationPublisher.TEST_TAG_PREFIX + e.occurrenceId }
        assertNotNull(n)
        assertEquals(AndroidNotificationPublisher.CHANNEL_REMINDERS, n!!.notification.channelId)
        nm.cancel(n.tag, n.id)
    }

    @Test fun testAlarmArrivesThroughAlarmManagerAndLatenessIsRecorded() {
        val s = runBlocking { container.selfTest.scheduleTestReminder(Duration.ofSeconds(5)) }
        assertTrue(s.scheduled)
        val deadline = System.currentTimeMillis() + 180_000
        var received: com.lifeadmin.autopilot.domain.model.DiagnosticEntry? = null
        while (System.currentTimeMillis() < deadline && received == null) {
            Thread.sleep(1_000)
            received = runBlocking { container.diagnosticsLog.forOccurrence(s.testId) }.firstOrNull { it.type == DiagnosticType.TEST_ALARM_RECEIVED }
        }
        assertNotNull("test alarm broadcast should arrive within 3 minutes (best-effort)", received)
        assertTrue("alarms never fire early", !received!!.lateness!!.isNegative)
        val postDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < postDeadline && runBlocking { container.diagnosticsLog.forOccurrence(s.testId) }.none { it.type == DiagnosticType.TEST_NOTIFICATION }) Thread.sleep(200)
        assertTrue(nm.activeNotifications.any { it.tag == AndroidNotificationPublisher.TEST_TAG_PREFIX + s.testId })
        nm.cancelAll()
    }
}
