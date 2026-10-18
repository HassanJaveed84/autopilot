package com.lifeadmin.autopilot

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real AlarmManager → receiver → NotificationManager on an emulator. Delivery is best-effort
 * (inexact alarm), so the wait is generous; it verifies wiring, not punctuality.
 */
@RunWith(AndroidJUnit4::class)
class InstrumentedReminderTest {
    @get:Rule
    val permission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer
    private var savedQuietHours: com.lifeadmin.autopilot.domain.model.QuietHours? = null

    // CI runs at any hour (emulator clock is UTC); inside quiet hours the engine correctly defers
    // the reminder to the window's end, which this 3-minute wiring test can't wait for.
    @Before fun disableQuietHours() = runBlocking {
        container.settingsRepository.update { s -> savedQuietHours = s.quietHours; s.copy(quietHours = s.quietHours.copy(enabled = false)) }
    }

    @After fun restoreQuietHours() = runBlocking {
        savedQuietHours?.let { q -> container.settingsRepository.update { it.copy(quietHours = q) } }
        Unit
    }

    @Test fun oneTimeReminderPostsNotificationThenDoneClearsIt() {
        val title = "Instrumented reminder ${System.nanoTime()}"
        val id = runBlocking {
            val id = container.taskRepository.createTask(NewTask(title, status = TaskStatus.ACTIONABLE))
            val p = container.taskRepository.getPolicy(id)!!
            container.taskRepository.savePolicy(p.copy(enabled = true, kind = ReminderKind.ONCE, atInstant = container.clock.now().plusSeconds(10), times = emptyList()))
            id
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline && nm.activeNotifications.none { it.tag == id }) Thread.sleep(1_000)
        val posted = nm.activeNotifications.firstOrNull { it.tag == id }
        assertTrue("reminder notification should appear within 3 minutes", posted != null)
        assertEquals(3, posted!!.notification.actions.size)

        posted.notification.actions[0].actionIntent.send() // Done
        val doneBy = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < doneBy && runBlocking { container.taskRepository.getTask(id)!!.status } != TaskStatus.DONE) Thread.sleep(250)
        assertEquals(TaskStatus.DONE, runBlocking { container.taskRepository.getTask(id)!!.status })
        Thread.sleep(500)
        assertTrue(nm.activeNotifications.none { it.tag == id })
        runBlocking { container.taskRepository.delete(id) }
    }
}
