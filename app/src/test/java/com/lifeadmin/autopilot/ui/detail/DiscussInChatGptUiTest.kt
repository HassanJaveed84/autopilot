package com.lifeadmin.autopilot.ui.detail

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
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
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.ui.LifeAdminRoot
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Discuss in ChatGPT" works with AI off, shows what will be shared, keeps notes out by default and writes nothing. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DiscussInChatGptUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private lateinit var taskId: String

    @Before fun setUp() {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(),
            clock = FakeClock(at(CHICAGO, 2026, 10, 27, 9, 0)),
            zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds(),
            database = inMemoryDatabase(),
            // AI planning stays off and no key is stored: the action must not depend on either.
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)),
        )
        runBlocking {
            taskId = container.taskRepository.createTask(NewTask("Choose a coffee grinder", notes = "Card ending 4242", status = TaskStatus.ACTIONABLE))
            container.taskRepository.addStep(taskId, "Read reviews")
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("nav_tasks")).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() = container.database.close()

    private fun has(tag: String, text: String) = compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theSheetSharesTheTaskWithoutNotesUnlessChosenAndChangesNothing() {
        assertFalse(container.settingsRepository.let { runBlocking { it.current().aiEnabled } })
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Choose a coffee grinder")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Choose a coffee grinder"))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("detail_title")).fetchSemanticsNodes().isNotEmpty() }
        val rev = runBlocking { container.taskRepository.getTask(taskId)!!.rev }

        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("action_discuss_chatgpt"))
        compose.onNodeWithTag("action_discuss_chatgpt").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("discuss_sheet")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(has("discuss_task", "Choose a coffee grinder"))
        assertTrue(has("discuss_steps", "1 to do"))
        assertTrue(has("discuss_notes", "Card ending 4242"))
        // No ChatGPT on the Robolectric "phone": the sheet says so and offers the chooser.
        assertTrue(has("discuss_explainer", "ChatGPT isn't on this phone"))
        assertTrue(has("discuss_open", "Share…"))

        compose.onNodeWithTag("discuss_full_toggle").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("discuss_full")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(has("discuss_full", "Still to do: Read reviews"))
        assertTrue(has("discuss_full", "r=$taskId"))
        assertFalse(has("discuss_full", "4242"))
        compose.onNodeWithTag("discuss_notes").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { has("discuss_full", "My notes:\nCard ending 4242") }

        compose.onNodeWithTag("discuss_open").performClick()
        val started = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val shared = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/plain", shared.type)
        assertTrue(shared.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Card ending 4242"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("discuss_sheet")).fetchSemanticsNodes().isEmpty() }
        // Opening the discussion is not a task change.
        assertEquals(rev, runBlocking { container.taskRepository.getTask(taskId)!!.rev })
    }
}
