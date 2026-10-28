package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.InMemoryLegacyApiKey
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SavedResearchFixtures
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Built-in AI is retired: research saved by earlier versions stays readable (offline, read-only)
 * from its task and from Settings, the task changes only through the existing Proposal Review, and
 * no screen offers Plan with AI, Research online or AI setup any more. No network.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedResearchUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 20, 9, 0))
    private lateinit var taskId: String

    /** Tags of every retired entry point; none may appear anywhere. */
    private val retired = listOf(
        "capture_plan_ai", "inbox_plan", "nav_research", "detail_ai_card", "action_move_forward", "action_research",
        "open_ai_settings", "ai_status", "home_new", "thread_prepare", "thread_followup_prepare", "auth_start", "turn_suggest_0", "brief_again", "brief_again_bottom",
    )

    private fun launch(seedBrief: Boolean = false, seedThread: Boolean = false, keyStored: Boolean = false) {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(),
            clock = clock,
            zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds("ui"),
            database = inMemoryDatabase(),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)),
            // As an earlier build with built-in AI would have left it.
            legacyApiKey = InMemoryLegacyApiKey(stored = keyStored),
        )
        runBlocking {
            taskId = container.taskRepository.createTask(NewTask("Book train ride", notes = "Window seat", status = TaskStatus.ACTIONABLE))
            container.taskRepository.addStep(taskId, "Find dates")
            if (seedBrief) container.researchRepository.save(taskId, "When do advance tickets go on sale?", SavedResearchFixtures.brief)
            if (seedThread) container.researchThreads.create(
                "af1", taskId, "Advance tickets", "When do advance tickets go on sale?",
                SavedResearchFixtures.answeredTurn("t0", 0, clock.now().toEpochMilli()), clock.now(),
            )
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        waitFor("nav_tasks")
    }

    private fun openTask() {
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Book train ride", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Book train ride", substring = true))[0].performClick()
        waitFor("detail_title")
    }

    @After fun tearDown() { runCatching { container.database.close() } }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun absent(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    private fun assertNoRetiredEntryPoints() = retired.forEach { assertTrue("$it is still offered", absent(it)) }
    private fun detailScrollTo(tag: String) = compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag(tag))

    @Test fun taskDetailOffersDiscussInChatGptAndNoBuiltInAi() {
        launch()
        openTask()
        detailScrollTo("action_discuss_chatgpt")
        compose.onNodeWithTag("action_discuss_chatgpt").assertIsDisplayed()
        // No saved research: no research card either.
        assertTrue(absent("detail_research_card"))
        assertNoRetiredEntryPoints()
    }

    @Test fun captureAndInboxOfferNoPlanWithAi() {
        launch()
        compose.onNodeWithTag("fab_capture").performClick()
        waitFor("capture_title")
        compose.onNodeWithTag("capture_title").performTextInput("Renew passport")
        compose.onNodeWithTag("capture_save").assertIsEnabled()
        assertNoRetiredEntryPoints()
        compose.onNodeWithTag("capture_nudge_switch").performClick()
        compose.onNodeWithTag("capture_save").performClick()
        compose.onNodeWithTag("nav_inbox").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Renew passport")).fetchSemanticsNodes().isNotEmpty() }
        waitFor("inbox_nudge")
        assertNoRetiredEntryPoints()
    }

    @Test fun settingsHasNoAiSectionAndNoResearchRowWithoutSavedResearch() {
        launch()
        compose.onNodeWithTag("nav_settings").performClick()
        waitFor("settings_list")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_licenses"))
        assertTrue(absent("settings_saved_research"))
        assertTrue(absent("settings_remove_key"))
        assertTrue(compose.onAllNodes(hasText("AI planning", substring = true)).fetchSemanticsNodes().isEmpty())
        assertNoRetiredEntryPoints()
    }

    @Test fun savedBriefStaysReadableFromItsTaskAndCannotStartResearch() {
        launch(seedBrief = true)
        openTask()
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("brief_list")
        compose.onNodeWithTag("brief_question").assertIsDisplayed()
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("source_1"))
        compose.onNodeWithTag("source_1").performClick()
        waitFor("source_sheet")
        // The app has no network check any more: the browser decides whether the page opens.
        compose.onNodeWithTag("source_open").assertIsEnabled()
        assertNoRetiredEntryPoints()
    }

    /**
     * Mirrors the emulator tour: leave the task (scrolled down) for longer than the detail flow's
     * 5 s stop timeout, then come back. The list keeps its scroll position, so the title row may be
     * off screen; the screen itself must be there with the research card still present.
     */
    @Test fun returningToTheTaskAfterALongDetourStillShowsIt() {
        launch(seedBrief = true)
        openTask()
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("brief_list")
        compose.mainClock.advanceTimeBy(7_000)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(7))
        compose.onNodeWithTag("brief_done").performClick()
        waitFor("detail_list")
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").assertIsDisplayed()
        // A saved brief is read-only: no apply path exists, and opening it never changes the task.
        val before = runBlocking { container.taskRepository.getSteps(taskId) }.size
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("brief_list")
        assertTrue(absent("brief_review"))
        compose.onNodeWithTag("brief_done").performClick()
        waitFor("detail_list")
        detailScrollTo("detail_title")
        compose.onNodeWithTag("detail_title").assertIsDisplayed()
        assertEquals(before, runBlocking { container.taskRepository.getSteps(taskId) }.size)
    }

    @Test fun savedThreadOpensReadOnlyFromSettingsAndFromItsTask() {
        launch(seedThread = true)
        compose.onNodeWithTag("nav_settings").performClick()
        waitFor("settings_list")
        // The row appears once the saved-research count has loaded from Room.
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_saved_research")) }.isSuccess }
        compose.onNodeWithTag("settings_saved_research").performClick()
        waitFor("home_item_0")
        compose.onNodeWithTag("home_item_0").performClick()
        waitFor("thread_list")
        waitFor("turn_answer_0")
        compose.onNodeWithTag("thread_readonly").assertIsDisplayed()
        compose.onNodeWithTag("thread_list").performScrollToNode(hasTestTag("turn_sources_0"))
        assertNoRetiredEntryPoints()
        // The thread's task is one tap away, and the same research is on the task.
        compose.onNodeWithTag("thread_task").performClick()
        waitFor("detail_title")
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("thread_readonly")
        assertNoRetiredEntryPoints()
    }

    @Test fun deletingSavedResearchRemovesOnlyTheThread() {
        launch(seedThread = true)
        openTask()
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("thread_delete")
        compose.onNodeWithTag("thread_delete").performClick()
        waitFor("thread_delete_confirm")
        compose.onNodeWithTag("thread_delete_confirm").performClick()
        waitFor("detail_title")
        assertEquals(null, runBlocking { container.researchThreads.get("af1") })
        assertEquals(1, runBlocking { container.taskRepository.getSteps(taskId) }.size)
    }

    @Test fun anOldApiKeyIsOfferedForRemovalAndOnlyRemovedOnRequest() {
        launch(keyStored = true)
        assertTrue(container.legacyApiKey.isStored())
        compose.onNodeWithTag("nav_settings").performClick()
        waitFor("settings_list")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_remove_key"))
        compose.onNodeWithTag("settings_remove_key").performClick()
        waitFor("settings_remove_key_confirm")
        // Nothing is removed until confirmed.
        assertTrue(container.legacyApiKey.isStored())
        compose.onNodeWithTag("settings_remove_key_confirm").performClick()
        compose.waitUntil(10_000) { absent("settings_remove_key") }
        assertFalse(container.legacyApiKey.isStored())
    }
}
