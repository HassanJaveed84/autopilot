package com.lifeadmin.autopilot.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.LifeAdminApp
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.notifications.AndroidNotificationPublisher
import com.lifeadmin.autopilot.receivers.NotificationActionReceiver
import com.lifeadmin.autopilot.receivers.ReminderAlarmReceiver
import com.lifeadmin.autopilot.receivers.SystemEventReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration

/**
 * End-to-end through the real Android adapters (Robolectric shadows): AlarmManager scheduling,
 * the alarm receiver, the notification with Done/Snooze/Open actions, and the action receiver.
 */
@RunWith(AndroidJUnit4::class)
class AndroidIntegrationTest {
    private lateinit var app: LifeAdminApp
    private lateinit var container: AppContainer
    private lateinit var alarmManager: AlarmManager
    private lateinit var notificationManager: NotificationManager

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        container = app.container
        // Robolectric's unit-test manifest does not include this app's <receiver> entries, so the
        // real receiver classes are registered dynamically here; the manifest declarations are
        // verified separately by ManifestContractTest.
        val fire = IntentFilter(IntentContract.ACTION_FIRE).apply { addDataScheme("lifeadmin") }
        val actions = IntentFilter().apply { addAction(IntentContract.ACTION_DONE); addAction(IntentContract.ACTION_SNOOZE); addDataScheme("lifeadmin") }
        val system = IntentFilter().apply { addAction(Intent.ACTION_BOOT_COMPLETED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED) }
        app.registerReceiver(ReminderAlarmReceiver(), fire, Context.RECEIVER_NOT_EXPORTED)
        app.registerReceiver(NotificationActionReceiver(), actions, Context.RECEIVER_NOT_EXPORTED)
        app.registerReceiver(SystemEventReceiver(), system, Context.RECEIVER_NOT_EXPORTED)
        alarmManager = app.getSystemService(AlarmManager::class.java)
        notificationManager = app.getSystemService(NotificationManager::class.java)
        await { runBlocking { container.settingsRepository.current().lastReconcileAt != null } } // app_start reconcile done
        // These tests use the real wall clock; disable quiet hours so results don't depend on the time of day.
        runBlocking { container.settingsRepository.update { it.copy(quietHours = it.quietHours.copy(enabled = false)) } }
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        System.err.println("LEDGER\n" + runBlocking { container.diagnosticsLog.exportText(java.time.ZoneId.of("UTC")) })
        throw AssertionError("condition not met in $timeoutMs ms")
    }

    private fun scheduledAlarms(): List<ShadowAlarmManager.ScheduledAlarm> = shadowOf(alarmManager).scheduledAlarms

    /** Task with a one-time reminder 20 s from now, so firing "now" is on time for the engine. */
    private fun taskDueNow(title: String): String = runBlocking {
        val id = container.taskRepository.createTask(NewTask(title, status = TaskStatus.ACTIONABLE))
        val p = container.taskRepository.getPolicy(id)!!
        container.taskRepository.savePolicy(p.copy(enabled = true, kind = ReminderKind.ONCE, atInstant = container.clock.now().plusSeconds(20), times = emptyList()))
        id
    }

    private fun fireAlarm(alarm: ShadowAlarmManager.ScheduledAlarm) {
        alarm.operation!!.send()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun inexactAllowWhileIdleAlarmIsScheduledByDefault() {
        taskDueNow("Pay invoice")
        val alarm = scheduledAlarms().single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue("best-effort alarm uses allow-while-idle", alarm.isAllowWhileIdle)
        assertNull("not an exact-alarm clock", alarm.showIntent)
    }

    @Test fun alarmPostsNotificationWithActionsAndDoneCompletesTask() {
        val id = taskDueNow("Book train ride")
        fireAlarm(scheduledAlarms().single())
        await { shadowOf(notificationManager).allNotifications.isNotEmpty() }
        val n = shadowOf(notificationManager).allNotifications.single()
        assertEquals(AndroidNotificationPublisher.CHANNEL_REMINDERS, n.channelId)
        assertEquals(listOf("Done", "Snooze", "Open"), n.actions.map { it.title.toString() })
        assertNotNull(n.contentIntent)
        assertTrue("one-time reminder consumed", scheduledAlarms().isEmpty())

        n.actions[0].actionIntent.send() // Done
        await { runBlocking { container.taskRepository.getTask(id)!!.status == TaskStatus.DONE } }
        await { shadowOf(notificationManager).allNotifications.isEmpty() }
        assertTrue(scheduledAlarms().isEmpty())
    }

    @Test fun snoozeActionKeepsTaskOpenAndSchedulesSnoozeEnd() {
        val id = taskDueNow("Call bank")
        fireAlarm(scheduledAlarms().single())
        await { shadowOf(notificationManager).allNotifications.isNotEmpty() }
        shadowOf(notificationManager).allNotifications.single().actions[1].actionIntent.send()
        await { runBlocking { container.taskRepository.getTask(id)!!.snoozedUntil != null } }
        await { shadowOf(notificationManager).allNotifications.isEmpty() }
        assertEquals(TaskStatus.ACTIONABLE, runBlocking { container.taskRepository.getTask(id)!!.status })
        val until = runBlocking { container.taskRepository.getTask(id)!!.snoozedUntil!! }
        await { scheduledAlarms().isNotEmpty() }
        val alarm = scheduledAlarms().single()
        assertTrue(alarm.triggerAtTime >= until.toEpochMilli() - 1)
    }

    @Test fun duplicateAlarmBroadcastPostsOnce() {
        taskDueNow("Renew passport")
        val alarm = scheduledAlarms().single()
        val duplicate = shadowOf(alarm.operation!!).savedIntent // same broadcast, re-delivered by the OS
        fireAlarm(alarm)
        app.sendBroadcast(Intent(duplicate))
        await { shadowOf(notificationManager).allNotifications.isNotEmpty() }
        Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)
        val delivered = runBlocking { container.database.occurrenceDao().getAll().count { it.status == com.lifeadmin.autopilot.domain.model.OccurrenceStatus.DELIVERED } }
        assertEquals(1, delivered)
    }

    @Test fun deniedPermissionDoesNotPostAndIsRecorded() {
        shadowOf(app as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val id = taskDueNow("Hidden reminder")
        fireAlarm(scheduledAlarms().single())
        await { runBlocking { container.database.occurrenceDao().getAllForTask(id).any { it.status == com.lifeadmin.autopilot.domain.model.OccurrenceStatus.SUPPRESSED } } }
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test fun bootBroadcastRebuildsAlarmsAfterTheyWereCleared() {
        runBlocking {
            container.taskRepository.createTask(NewTask("Daily thing", status = TaskStatus.ACTIONABLE, preset = com.lifeadmin.autopilot.domain.model.ReminderPreset.DAILY))
        }
        val before = scheduledAlarms().single().triggerAtTime
        // Simulate reboot: the OS drops all alarms.
        scheduledAlarms().toList().forEach { alarmManager.cancel(it.operation!!) }
        assertTrue(scheduledAlarms().isEmpty())
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setClass(app, SystemEventReceiver::class.java))
        await { scheduledAlarms().isNotEmpty() }
        assertEquals(before, scheduledAlarms().single().triggerAtTime)
        await { runBlocking { container.settingsRepository.current().lastReconcileSummary?.startsWith("boot") == true } }
    }

    @Test fun unknownActionsAndMalformedIdsAreIgnored() {
        val id = taskDueNow("Safe task")
        val ctx: Context = app
        ctx.sendBroadcast(Intent("com.evil.ACTION").setClass(ctx, NotificationActionReceiver::class.java).setData(IntentContract.taskUri(id, "done")))
        ctx.sendBroadcast(Intent(IntentContract.ACTION_DONE).setClass(ctx, NotificationActionReceiver::class.java).setData(android.net.Uri.parse("lifeadmin://task/..%2F..%2Fx/done")))
        ctx.sendBroadcast(Intent(IntentContract.ACTION_DONE).setClass(ctx, NotificationActionReceiver::class.java).setData(android.net.Uri.parse("https://example.com/task/$id/done")))
        Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TaskStatus.ACTIONABLE, runBlocking { container.taskRepository.getTask(id)!!.status })
    }

    @Test fun cancelingAnOccurrenceRemovesItsAlarm() {
        val id = taskDueNow("Cancelable")
        assertEquals(1, scheduledAlarms().size)
        runBlocking { container.taskRepository.changeStatus(id, TaskStatus.PAUSED) }
        assertTrue(scheduledAlarms().isEmpty())
    }

    @Test fun reminderWindowIsReasonable() {
        val id = taskDueNow("Window check")
        val alarm = scheduledAlarms().single()
        val planned = runBlocking { container.database.occurrenceDao().getForTaskWithStatus(id, ReminderEngine.ACTIVE).single().plannedAt }
        assertEquals(planned.toEpochMilli(), alarm.triggerAtTime)
        // Compare against the app clock (Robolectric's System.currentTimeMillis is a separate fake clock).
        assertTrue(Duration.ofMillis(alarm.triggerAtTime - container.clock.now().toEpochMilli()) <= Duration.ofSeconds(25))
    }
}
