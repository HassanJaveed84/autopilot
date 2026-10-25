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
        // The action is a broadcast handled off the main thread; on a busy CI emulator delivery to
        // the backgrounded app has taken longer than 15 s (CI run #39), so allow a minute.
        val doneBy = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < doneBy && runBlocking { container.taskRepository.getTask(id)!!.status } != TaskStatus.DONE) Thread.sleep(250)
        assertEquals(TaskStatus.DONE, runBlocking { container.taskRepository.getTask(id)!!.status })
        Thread.sleep(500)
        assertTrue(nm.activeNotifications.none { it.tag == id })
        runBlocking { container.taskRepository.delete(id) }
    }

    /**
     * Quiet hours are on and cover "now". A routine allowed to ring in quiet hours still posts, its
     * first action is "Done for tonight/today", and tapping it keeps the task open with the next
     * cycle scheduled. The same schedule without the exception is held until quiet hours end.
     */
    @Test fun routineMayRingInQuietHoursAndDoneForNowKeepsTheTask() {
        val zone = container.zoneProvider.current()
        val now = container.clock.now()
        val local = now.atZone(zone).toLocalTime()
        runBlocking {
            container.settingsRepository.update {
                it.copy(quietHours = it.quietHours.copy(enabled = true, start = local.minusHours(1).withSecond(0).withNano(0), end = local.plusHours(2).withSecond(0).withNano(0)))
            }
        }
        val slot = now.plusSeconds(60).atZone(zone).toLocalTime().withSecond(0).withNano(0)
        fun routine(title: String, ring: Boolean): String = runBlocking {
            val id = container.taskRepository.createTask(NewTask(title, status = TaskStatus.ACTIONABLE))
            val p = container.taskRepository.getPolicy(id)!!
            container.taskRepository.savePolicy(p.copy(enabled = true, kind = ReminderKind.DAILY_TIMES, times = listOf(slot), startLocalDate = null, routine = true, ringInQuietHours = ring, snoozeDefaultMinutes = 15))
            id
        }
        val held = routine("Held routine ${System.nanoTime()}", ring = false)
        val id = routine("Bedtime routine ${System.nanoTime()}", ring = true)
        val heldNext = runBlocking { container.taskActions.previewNext(held)!! }
        assertTrue("without the exception it waits for quiet hours to end", heldNext.deferred)

        val nm = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline && nm.activeNotifications.none { it.tag == id }) Thread.sleep(1_000)
        val posted = nm.activeNotifications.firstOrNull { it.tag == id }
        assertTrue("the routine rings during quiet hours within 3 minutes", posted != null)
        assertTrue("the held one did not ring", nm.activeNotifications.none { it.tag == held })
        val first = posted!!.notification.actions[0].title.toString()
        assertTrue("first action is per-cycle: $first", first == "Done for tonight" || first == "Done for today")

        posted.notification.actions[0].actionIntent.send()
        val by = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < by && runBlocking { container.taskRepository.getTask(id)!!.cycleDoneUntil } == null) Thread.sleep(250)
        val task = runBlocking { container.taskRepository.getTask(id)!! }
        assertEquals("the task stays open", TaskStatus.ACTIONABLE, task.status)
        assertTrue(task.cycleDoneUntil != null)
        val next = runBlocking { container.taskActions.previewNext(id)!! }
        assertTrue("the next cycle is planned", next.plannedAt >= task.cycleDoneUntil!!)
        Thread.sleep(500)
        assertTrue(nm.activeNotifications.none { it.tag == id })
        runBlocking { container.taskRepository.delete(id); container.taskRepository.delete(held) }
    }
}
