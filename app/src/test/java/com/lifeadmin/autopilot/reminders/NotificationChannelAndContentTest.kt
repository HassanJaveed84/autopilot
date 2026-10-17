package com.lifeadmin.autopilot.reminders

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.domain.model.RingerMode
import com.lifeadmin.autopilot.notifications.AndroidNotificationPublisher
import com.lifeadmin.autopilot.notifications.PostResult
import com.lifeadmin.autopilot.notifications.ReminderNotification
import com.lifeadmin.autopilot.notifications.TestNotification
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Runs with a plain Application so no channel exists before each test (LifeAdminApp would create
 * it on start, and Android restores a deleted channel's old settings when it is re-created).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NotificationChannelAndContentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private lateinit var publisher: AndroidNotificationPublisher

    @Before fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        publisher = AndroidNotificationPublisher(context)
    }

    @Test fun newInstallChannelVibratesAlertsWithSoundAndDoesNotForcePopUp() {
        publisher.ensureChannels()
        val c = nm.getNotificationChannel(AndroidNotificationPublisher.CHANNEL_REMINDERS)
        assertNotNull(c)
        assertTrue("vibration enabled for new installs", c.shouldVibrate())
        assertArrayEquals(AndroidNotificationPublisher.VIBRATION_PATTERN, c.vibrationPattern)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, c.importance)
        assertNotNull("default notification sound kept", c.sound)
    }

    @Test fun existingUserConfiguredChannelIsNeverOverwritten() {
        // Simulates an install created by the previous version (no vibration), then user changes.
        val old = NotificationChannel(AndroidNotificationPublisher.CHANNEL_REMINDERS, "Task reminders", NotificationManager.IMPORTANCE_LOW).apply {
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(old)
        publisher.ensureChannels()
        val c = nm.getNotificationChannel(AndroidNotificationPublisher.CHANNEL_REMINDERS)
        assertFalse(c.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_LOW, c.importance)
        assertEquals(null, c.sound)
    }

    @Test fun reminderShowsActualPostingTimeAndDueTimeAsSubtext() {
        publisher.ensureChannels()
        val planned = Instant.parse("2026-10-16T23:25:00Z")
        val posted = Instant.parse("2026-10-16T23:49:00Z")
        val r = publisher.postReminder(ReminderNotification("task-1", "occ-1", "Test", "Still open", planned, late = true, redactOnLockScreen = false, postedAt = posted, dueLabel = "18:25"))
        assertEquals(PostResult.POSTED, r)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(posted.toEpochMilli(), n.`when`)
        assertEquals("Late · due 18:25", n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals(Notification.CATEGORY_REMINDER, n.category)
    }

    @Test fun testNotificationUsesTheReminderChannel() {
        val r = publisher.postTest(TestNotification("t1", "Life Admin test reminder", "body", Instant.parse("2026-10-17T09:00:00Z")))
        assertEquals(PostResult.POSTED, r)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(AndroidNotificationPublisher.CHANNEL_REMINDERS, n.channelId)
        assertEquals(true, publisher.isTestShowing("t1"))
        assertEquals(false, publisher.isTestShowing("other"))
    }

    @Test fun environmentProbeReadsRingerModeAndChannelVibration() {
        nm.createNotificationChannel(NotificationChannel(AndroidNotificationPublisher.CHANNEL_REMINDERS, "Task reminders", NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(false) })
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val env = AndroidEnvironmentProbe(context, AndroidAlarmDriver(context)).snapshot()
        assertEquals(RingerMode.VIBRATE, env.ringerMode)
        assertEquals(false, env.channelVibrates)
        assertEquals(3, env.channelImportance)
        assertTrue(env.channelExists)
    }
}
