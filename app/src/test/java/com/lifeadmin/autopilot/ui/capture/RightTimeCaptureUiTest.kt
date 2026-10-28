package com.lifeadmin.autopilot.ui.capture

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.fileDatabase
import com.lifeadmin.autopilot.ui.LifeAdminRoot
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalTime

/** The capture sheet on the real app shell: what it understood, the quiet-hours choice, and what gets saved. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class RightTimeCaptureUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 23, 19)) // Fri 19:00, before the 21:30 bedtime

    @Before fun setUp() {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(),
            clock = clock,
            zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds(),
            database = fileDatabase(),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)),
        )
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
    }

    @After fun tearDown() = container.database.close()

    private fun type(text: String) {
        compose.onNodeWithTag("fab_capture").performClick()
        compose.onNodeWithTag("capture_title").performTextInput(text)
        compose.waitForIdle()
    }

    private fun save(): String {
        compose.onNodeWithTag("capture_save").performClick()
        compose.waitUntil(10_000) { runBlocking { container.database.taskDao().count() == 1 } }
        return runBlocking { container.database.taskDao().getAll().single().id }
    }

    private fun hasNode(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(text: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun fanBeforeBedShowsWhatWasUnderstoodAndAsksBeforeRingingInQuietHours() {
        type("Turn off fan before bed")
        waitForText("Nudge tonight 21:30 · your bedtime")
        waitForText("from “before bed”")
        waitForText("Once · from your words")
        // 21:30 is inside the default 21:00–08:00 quiet hours: the choice is shown, off until the user turns it on.
        assertTrue(hasNode("capture_ring_quiet"))
        waitForText("would wait until 08:00")
        compose.onNodeWithTag("capture_ring_quiet").performClick()
        waitForText("Rings at its time even during your quiet hours")
        waitForText("Do Not Disturb")
        val id = save()
        val task = runBlocking { container.taskRepository.getTask(id)!! }
        assertEquals("the words are kept as typed", "Turn off fan before bed", task.title)
        val p = runBlocking { container.taskRepository.getPolicy(id)!! }
        assertEquals(ReminderKind.ONCE, p.kind)
        assertEquals(at(CHICAGO, 2026, 10, 23, 21, 30), p.atInstant)
        assertTrue(p.ringInQuietHours)
        assertEquals(15, p.snoozeDefaultMinutes)
    }

    @Test fun withoutTheUsersChoiceQuietHoursStillApply() {
        type("Check daughter's blanket before bed")
        waitForText("Nudge tonight 21:30")
        val id = save()
        assertFalse(runBlocking { container.taskRepository.getPolicy(id)!!.ringInQuietHours })
    }

    @Test fun everyNightIsARoutineAnsweredWithDoneForTonight() {
        type("Check daughter's blanket every night")
        waitForText("21:30 daily · a routine · your bedtime")
        waitForText("Every day, a routine · from your words")
        compose.onNodeWithTag("capture_ring_quiet").performClick()
        val id = save()
        val p = runBlocking { container.taskRepository.getPolicy(id)!! }
        assertTrue(p.isRoutine)
        assertEquals(listOf(LocalTime.of(21, 30)), p.times)

        // The detail screen answers the routine per night and keeps Mark done for ending it.
        compose.onNodeWithTag("nav_tasks").performClick()
        waitForText("Check daughter's blanket every night")
        compose.onAllNodes(hasText("Check daughter's blanket every night"), useUnmergedTree = true)[0].performClick()
        compose.waitUntil(5_000) { hasNode("action_done_for_now") }
        assertTrue(hasNode("action_done"))
        waitForText("Mark done · ends the routine")
        compose.onNodeWithTag("action_done_for_now").performClick()
        compose.waitUntil(5_000) { runBlocking { container.taskRepository.getTask(id)!!.cycleDoneUntil != null } }
        val t = runBlocking { container.taskRepository.getTask(id)!! }
        assertEquals(TaskStatus.ACTIONABLE, t.status)
        assertNull(t.completedAt)
        compose.waitUntil(5_000) { hasNode("action_undo_done_for_now") }
        waitForText("Done for tonight · next tomorrow 21:30")
    }

    @Test fun aOneOffBedtimeNudgeCanBecomeEveryNight() {
        type("Turn off fan before bed")
        compose.waitUntil(5_000) { hasNode("capture_timing_nightly") }
        compose.onNodeWithTag("capture_timing_nightly").performClick()
        waitForText("a routine")
        val id = save()
        val p = runBlocking { container.taskRepository.getPolicy(id)!! }
        assertTrue(p.isRoutine)
        assertEquals(listOf(LocalTime.of(21, 30)), p.times)
    }

    @Test fun aDeadlineInTheWordsIsPrefilledAndSaved() {
        type("Complete work update by Monday 5pm")
        waitForText("Deadline Mon 26 Oct, 17:00")
        waitForText("from “by monday 5pm”")
        val id = save()
        val t = runBlocking { container.taskRepository.getTask(id)!! }
        assertEquals(DeadlineType.EXACT, t.deadlineType)
        assertEquals(at(CHICAGO, 2026, 10, 26, 17), t.deadlineInstant)
        assertEquals("Complete work update by Monday 5pm", t.title)
    }

    @Test fun aGiftForSomeoneShowsNothingInferredAndUsesTheGentleDefault() {
        type("Buy a gift for someone")
        compose.onNodeWithTag("capture_follow_up").assertIsDisplayed()
        assertFalse(hasNode("capture_timing"))
        assertFalse(hasNode("capture_ring_quiet"))
        waitForText("your default")
        val id = save()
        val t = runBlocking { container.taskRepository.getTask(id)!! }
        assertNull(t.notes)
        assertEquals(DeadlineType.NONE, t.deadlineType)
        val p = runBlocking { container.taskRepository.getPolicy(id)!! }
        assertEquals(2, p.dayInterval)
        assertEquals(listOf(LocalTime.of(10, 0)), p.times)
    }

    @Test fun ignoreFallsBackToTheDefault() {
        type("Turn off fan before bed")
        compose.waitUntil(5_000) { hasNode("capture_timing_ignore") }
        compose.onNodeWithTag("capture_timing_ignore").performClick()
        compose.waitUntil(5_000) { !hasNode("capture_timing") }
        val id = save()
        val p = runBlocking { container.taskRepository.getPolicy(id)!! }
        assertEquals(ReminderKind.EVERY_N_DAYS, p.kind)
        assertFalse(p.ringInQuietHours)
    }
}
