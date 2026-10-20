package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
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
import com.lifeadmin.autopilot.testing.inMemoryDatabase
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A done task keeps its steps read-only until it is reopened; title/notes editing stays available. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CompletedTaskRestrictionsUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer

    @Before fun setUp() {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(),
            clock = FakeClock(at(CHICAGO, 2026, 10, 19, 8, 42)),
            zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds(),
            database = inMemoryDatabase(),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)),
        )
        runBlocking {
            val id = container.taskRepository.createTask(NewTask("Pay the council tax", status = TaskStatus.ACTIONABLE))
            container.taskRepository.addStep(id, "Find the reference number")
            container.taskRepository.addStep(id, "Pay online")
            container.taskRepository.complete(id)
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("nav_tasks")).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() = container.database.close()

    @Test fun doneTaskHidesStepEditingUntilReopened() {
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filter_completed")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_completed").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Pay the council tax", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Pay the council tax", substring = true))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("detail_title")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("steps_locked"))
        compose.onNodeWithTag("steps_locked").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag("step_input")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("steps_edit")).fetchSemanticsNodes().isEmpty())
        // Title and notes stay editable, and the task can be reopened.
        compose.onNodeWithTag("detail_list").performScrollToNode(hasContentDescription("Edit task"))
        compose.onNode(hasContentDescription("Edit task")).assertIsDisplayed()
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("action_reopen"))
        compose.onNodeWithTag("action_reopen").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("step_input")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasTestTag("steps_locked")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("steps_edit"))
        compose.onNodeWithTag("steps_edit").assertIsDisplayed()
    }
}
