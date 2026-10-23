package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FAKE_KEY
import com.lifeadmin.autopilot.testing.FakeCallProvider
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeResearchProvider
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.Research2Fixtures
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The whole M3A.2 research flow at 200% text on a phone: footers stack, nothing is cut off, every control stays reachable. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResearchFlowLargeTextUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val provider = FakeCallProvider()

    @After fun tearDown() { runCatching { container.database.close() } }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun researchFlowAt200Percent() {
        provider.gate = CompletableDeferred(); provider.gateCall = 1
        provider.enqueue(Research2Fixtures.fragranceGather(), Research2Fixtures.fragranceFillGaps())
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = FakeClock(at(CHICAGO, 2026, 10, 20, 9, 0)), zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds("ui"), database = inMemoryDatabase(),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true, aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = 0)),
            apiKeyStore = InMemoryApiKeyStore(FAKE_KEY), researchProvider = FakeResearchProvider(), researchCalls = provider, networkStatus = { true },
        )
        val taskId = runBlocking { container.taskRepository.createTask(NewTask("Gift for Dad", notes = "Birthday in November", status = TaskStatus.ACTIONABLE)) }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        waitFor("nav_tasks")
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Gift for Dad", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Gift for Dad", substring = true))[0].performClick()
        waitFor("detail_list")
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("action_research"))
        compose.onNodeWithTag("action_research").performClick()
        waitFor("preflight_question")
        compose.onNodeWithTag("preflight_question").performTextInput(Research2Fixtures.FRAGRANCE_REQUEST)
        compose.onNodeWithTag("preflight_list").performScrollToNode(hasTestTag("preflight_requirements"))
        compose.onNodeWithTag("preflight_list").performScrollToNode(hasTestTag("mode_deep"))
        compose.onNodeWithTag("preflight_list").performScrollToNode(hasTestTag("preflight_adjust"))
        compose.onNodeWithTag("preflight_adjust").performClick()
        waitFor("adjust_panel")
        compose.onNodeWithTag("preflight_list").performScrollToNode(hasTestTag("limit_none"))
        compose.onNodeWithTag("preflight_list").performScrollToNode(hasTestTag("preflight_usage"))
        compose.onNodeWithTag("preflight_start").assertIsEnabled().performClick()
        waitFor("explainer_sheet")
        compose.onNodeWithTag("explainer_accept").performClick()
        waitFor("research_stop")
        compose.onNodeWithTag("research_stop").performClick()
        waitFor("stop_sheet")
        compose.onNodeWithTag("stop_cancel").performClick()
        provider.gate!!.complete(Unit)
        waitFor("brief_list")
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("brief_coverage"))
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("cand_c1"))
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("compare_table"))
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("brief_sources"))
        compose.onNodeWithTag("source_1").performClick()
        waitFor("source_sheet")
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close"))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("source_sheet")).fetchSemanticsNodes().isEmpty() }
        // M2: "Review N changes" opens the canonical Proposal Review. Nothing is selected on open;
        // at 200 % the selection row sits below the header, so scroll to it like a user would.
        compose.onNodeWithTag("brief_review").assertIsEnabled().performClick()
        waitFor("review_list")
        waitFor("review_apply")
        waitFor("review_selected_count")
        compose.onNodeWithTag("review_apply").assertIsNotEnabled()
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_select_recommended"))
        compose.onNodeWithTag("review_select_recommended").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Apply 1 change")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review_apply").assertIsEnabled().assertIsDisplayed().performClick()
        waitFor("review_result")
        compose.onNodeWithTag("review_done").assertIsDisplayed().performClick()
        waitFor("detail_list")
        assertEquals("the sourced 'Buy Fresh Aqua' step is the recommended one; the decide step is not recommended and was never selected", 1, runBlocking { container.taskRepository.getSteps(taskId) }.size)
    }
}
