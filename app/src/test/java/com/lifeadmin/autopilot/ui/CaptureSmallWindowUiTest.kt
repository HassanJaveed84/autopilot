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

/**
 * Capture on a short, narrow window (what a 320dp phone with the keyboard open leaves): the
 * primary actions stay pinned and visible while the notes/priority/deadline fields scroll.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h480dp-xhdpi", fontScale = 1.3f)
class CaptureSmallWindowUiTest {
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
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("fab_capture")).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() = container.database.close()

    @Test fun saveStaysVisibleWhileDetailsScroll() {
        compose.onNodeWithTag("fab_capture").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("capture_title")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture_title").performTextInput("Book the train ride for my daughter, find parking and tickets")
        compose.onNodeWithTag("capture_details").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("capture_notes")).fetchSemanticsNodes().isNotEmpty() }
        // Pinned footer: reachable without scrolling, at 48dp or more.
        compose.onNodeWithTag("sheet_footer").assertIsDisplayed()
        compose.onNodeWithTag("capture_save").assertIsDisplayed().assertTouchTargetAtLeast(48.dp)
        // Every detail field can be scrolled into view and the sheet is still saveable afterwards.
        compose.onNodeWithTag("capture_priority_high").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("capture_notes").performScrollTo().assertIsDisplayed().performTextInput("Platform 4, usually")
        compose.onNodeWithTag("capture_save").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { runBlocking { container.database.taskDao().getAll() }.size == 1 }
        val saved = runBlocking { container.database.taskDao().getAll() }.single()
        assertEquals("Book the train ride for my daughter, find parking and tickets", saved.title)
        assertEquals("Platform 4, usually", saved.notes)
    }

    @Test fun filterChipsShowAnEdgeFadeAndKeepTheSelectedChipInView() {
        runBlocking {
            val id = container.taskRepository.createTask(NewTask("Return the library books", status = TaskStatus.ACTIONABLE))
            container.taskRepository.complete(id); container.taskRepository.archive(id)
        }
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filter_row")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_more_end", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("filter_archived").performSemanticsAction(SemanticsActions.OnClick)
        // On this short window the card sits below the fold of the lazy list; scroll the list to it.
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("tasks_list").performScrollToNode(hasText("Return the library books", substring = true)) }.isSuccess }
        compose.onNodeWithTag("tasks_list").performScrollToNode(hasTestTag("filter_row"))
        compose.onNodeWithTag("filter_archived").assertIsDisplayed()
    }
}
