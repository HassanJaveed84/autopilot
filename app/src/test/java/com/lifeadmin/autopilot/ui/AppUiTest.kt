package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    private fun createTask(title: String, status: TaskStatus = TaskStatus.ACTIONABLE) = runBlocking {
        container.taskRepository.createTask(NewTask(title, status = status))
    }

    @Test fun quickCaptureSavesToInboxInTwoTaps() {
        compose.onNodeWithTag("fab_capture").performClick()
        compose.onNodeWithTag("capture_title").performTextInput("Renew car registration")
        compose.onNodeWithTag("capture_save_inbox").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("nav_inbox").performClick()
        waitForText("Renew car registration")
        compose.onNodeWithText("Renew car registration").assertIsDisplayed()
        val task = runBlocking { container.database.taskDao().getAll().single() }
        assertEquals(TaskStatus.INBOX, task.status)
    }

    @Test fun saveAndRemindCreatesActionableTaskWithDefaultSchedule() {
        compose.onNodeWithTag("fab_capture").performClick()
        compose.onNodeWithTag("capture_title").performTextInput("Book train ride")
        compose.onNodeWithTag("capture_save_remind").performClick()
        compose.waitUntil(10_000) { runBlocking { container.database.taskDao().count() == 1 } }
        waitForText("Book train ride")
        // Shown under "Needs action" and "Upcoming reminders".
        compose.onAllNodesWithText("Book train ride")[0].assertIsDisplayed()
        val task = runBlocking { container.database.taskDao().getAll().single() }
        assertEquals(TaskStatus.ACTIONABLE, task.status)
        val policy = runBlocking { container.taskRepository.getPolicy(task.id)!! }
        assertEquals(listOf("09:00", "18:00"), policy.times.map { it.toString() })
    }

    @Test fun completeFromTodayThenReopenFromDetail() {
        createTask("Call the bank")
        waitForDescription("Mark Call the bank done")
        compose.onNodeWithContentDescription("Mark Call the bank done").performClick()
        compose.waitUntil(10_000) { runBlocking { container.database.taskDao().getAll().single().status == TaskStatus.DONE } }
        compose.onNodeWithTag("nav_tasks").performClick()
        waitForText("Completed 1")
        compose.onNodeWithText("Completed 1").performClick()
        waitForText("Call the bank")
        compose.onNodeWithText("Call the bank").performClick()
        waitForTag("action_reopen")
        compose.onNodeWithTag("action_reopen").performClick()
        compose.waitUntil(10_000) { runBlocking { container.database.taskDao().getAll().single().status == TaskStatus.ACTIONABLE } }
    }

    // The Waiting dialog contains a text field inside an AlertDialog; under Robolectric that
    // combination never reports idle, so its UI flow lives in androidTest (emulator, CI) and the
    // logic is covered by TaskRepositoryTest.statusLifecycleAndReopen.

    @Test fun snoozeDialogShowsExactNextReminderBeforeConfirming() {
        createTask("Pay invoice")
        waitForText("Pay invoice")
        compose.onNodeWithText("Pay invoice").performClick()
        waitForTag("action_snooze")
        compose.onNodeWithTag("action_snooze").performClick()
        // Default selection is "Tomorrow morning" (Sat 17 Oct 09:00 at a 07:30 Friday clock).
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Snooze-end reminder: Sat 17 Oct 2026, 09:00") > 0 }
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun editReminderScheduleFromDetail() {
        createTask("Book train ride")
        waitForText("Book train ride")
        compose.onNodeWithText("Book train ride").performClick()
        compose.onNodeWithTag("detail_list").let { waitForTag("policy_summary") }
        compose.onNodeWithText("Set up").performClick()
        // Editor pre-fills the editable Persistent preset (09:00 and 18:00).
        compose.waitUntil(3_000) { compose.onAllNodesWithTextCount("Next reminder: Fri 16 Oct 2026, 09:00") > 0 }
        compose.onNodeWithTag("policy_save").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithTextCount("Daily at 09:00 and 18:00") > 0 }
    }

    @Test fun searchFiltersTasks() {
        createTask("Book train ride")
        createTask("Renew passport")
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
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("step_add"))
        compose.onNodeWithContentDescription("Add step").assertExists()
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTextCount(text) > 0 }
    private fun waitForTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForDescription(d: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(d), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
}
