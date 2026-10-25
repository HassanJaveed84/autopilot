package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.fileDatabase
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AppUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 16, 7, 30))

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

    /** Nudging tasks land on the Today rail (card with a check); tasks without nudges sit in "Not today". */
    private fun createTask(title: String, status: TaskStatus = TaskStatus.ACTIONABLE, preset: ReminderPreset? = ReminderPreset.PERSISTENT) = runBlocking {
        container.taskRepository.createTask(NewTask(title, status = status, preset = preset))
    }

    @Test fun quickCaptureSavesToInboxInTwoTaps() {
        compose.onNodeWithTag("fab_capture").performClick()
        compose.onNodeWithTag("capture_title").performTextInput("Renew car registration")
        // The follow-up switch is on by default (Save & nudge); off = Save to Inbox.
        compose.onNodeWithTag("capture_nudge_switch").performClick()
        compose.onNodeWithTag("capture_save").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("nav_inbox").performClick()
        waitForTag("inbox_list")
        waitForText("Renew car registration")
        // Today's inbox prompt also previews the note while the tab transition finishes.
        compose.onAllNodes(hasText("Renew car registration")).onFirst().assertIsDisplayed()
        val task = runBlocking { container.database.taskDao().getAll().single() }
        assertEquals(TaskStatus.INBOX, task.status)
    }

    @Test fun saveAndRemindCreatesActionableTaskWithDefaultSchedule() {
        compose.onNodeWithTag("fab_capture").performClick()
        compose.onNodeWithTag("capture_title").performTextInput("Book train ride")
        compose.onNodeWithTag("capture_save").performClick()
        compose.waitUntil(10_000) { runBlocking { container.database.taskDao().count() == 1 } }
        waitForText("Book train ride")
        // Shown on the Today rail (10:00 card).
        compose.onAllNodesWithText("Book train ride")[0].assertIsDisplayed()
        val task = runBlocking { container.database.taskDao().getAll().single() }
        assertEquals(TaskStatus.ACTIONABLE, task.status)
        // A new install's default is the lighter Gentle preset: every 2 days at 10:00.
        val policy = runBlocking { container.taskRepository.getPolicy(task.id)!! }
        assertEquals(listOf("10:00"), policy.times.map { it.toString() })
        assertEquals(2, policy.dayInterval)
    }

    @Test fun completeFromTodayThenReopenFromDetail() {
        createTask("Call the bank")
        waitForDescription("Mark Call the bank done")
        compose.onNodeWithContentDescription("Mark Call the bank done").performClick()
        compose.waitUntil(10_000) { runBlocking { container.database.taskDao().getAll().single().status == TaskStatus.DONE } }
        compose.onNodeWithTag("nav_tasks").performClick()
        waitForTag("filter_completed")
        compose.onNodeWithTag("filter_completed").performClick()
        waitForText("Call the bank")
        compose.onNodeWithText("Call the bank").performClick()
        waitForTag("action_reopen")
        compose.onNodeWithTag("action_reopen").performClick()
        compose.waitUntil(10_000) { runBlocking { container.database.taskDao().getAll().single().status == TaskStatus.ACTIONABLE } }
    }

    // The Waiting dialog contains a text field inside an AlertDialog; under Robolectric that
    // combination never reports idle, so its UI flow lives in androidTest (emulator, CI) and the
    // logic is covered by TaskRepositoryTest.statusLifecycleAndReopen.

    @Test fun snoozeSheetSnoozesOnOneTapAndShowsTheResultingTime() {
        createTask("Pay invoice", preset = null)
        waitForText("Pay invoice")
        compose.onNodeWithText("Pay invoice").performClick()
        waitForTag("action_snooze")
        compose.onNodeWithTag("action_snooze").performClick()
        // "Tomorrow morning" resolves to Sat 17 Oct 09:00 at a 07:30 Friday clock; one tap applies it.
        waitForTag("snooze_tomorrow_morning")
        compose.onNodeWithTag("snooze_tomorrow_morning").performClick()
        waitForText("Snoozed until tomorrow 09:00")
    }

    @Test fun editReminderScheduleFromDetail() {
        createTask("Book train ride", preset = null)
        waitForText("Book train ride")
        compose.onNodeWithText("Book train ride").performClick()
        waitForTag("policy_summary")
        compose.onNodeWithTag("card_action_reminders").performClick()
        // The Follow-up sheet pre-fills the editable default preset (Gentle on a new install: every
        // 2 days at 10:00) and previews the next nudges with the engine's own planner.
        compose.waitUntil(3_000) { compose.onAllNodesWithTextCount("Today 10:00") > 0 }
        compose.onNodeWithTag("policy_save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Nudging every 2 days at 10:00") > 0 }
    }

    @Test fun searchFiltersTasks() {
        createTask("Book train ride", preset = null)
        createTask("Renew passport", preset = null)
        compose.onNodeWithTag("nav_tasks").performClick()
        waitForText("Renew passport")
        compose.onNodeWithTag("search_field").performTextInput("passport")
        compose.onNodeWithText("Renew passport").assertIsDisplayed()
        compose.waitUntil(3_000) { compose.onAllNodesWithTextCount("Book train ride") == 0 }
    }

    @Test fun iconOnlyControlsHaveAccessibleLabels() {
        createTask("Book train ride")
        waitForDescription("Mark Book train ride done")
        compose.onNode(hasContentDescription("Mark Book train ride done")).assertIsDisplayed()
        compose.onNodeWithText("Book train ride").performClick()
        waitForDescription("Edit task")
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit task").assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").assertIsDisplayed()
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("step_input"))
        compose.onNodeWithTag("step_input").performTextInput("Pack the bag")
        compose.onNodeWithContentDescription("Add step").assertExists()
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTextCount(text) > 0 }
    private fun waitForTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForDescription(d: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(d), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
}
