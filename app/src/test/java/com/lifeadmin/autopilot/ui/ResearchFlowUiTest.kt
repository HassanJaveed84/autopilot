package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchResponse
import com.lifeadmin.autopilot.ai.research.ResearchUsage
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FAKE_KEY
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeResearchProvider
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Legacy M3A briefs on the real shell: saved briefs stay readable (AI off, offline), their apply
 * screen still works, and the task screen survives a long detour. New runs go through the M3A.2
 * flow (see Research2FlowUiTest). No network.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResearchFlowUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val provider = FakeResearchProvider()
    private var online: Boolean? = true
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 20, 9, 0))
    private lateinit var taskId: String

    private val rail = RawCitation("https://www.northlinerail.example/tickets", "Tickets | Northline Rail", "Advance tickets are released 12 weeks ahead.")
    private val goodResponse = ResearchResponse.Success(
        segments = listOf(
            RawSegment("## QUICK ANSWER\n", emptyList()),
            RawSegment("Advance tickets go on sale 12 weeks before travel.", listOf(rail)),
            RawSegment(" Prices were not listed on any page.\n\n## NEXT MOVES\n- STEP: Check the 12-week release date — ", emptyList()),
            RawSegment("Northline releases advance fares 12 weeks out.", listOf(rail)),
            RawSegment("\n- NOTE: Advance fares are released 12 weeks before travel — ", emptyList()),
            RawSegment("From the operator's ticket page.", listOf(rail)),
            RawSegment("\n- CHECK: Whether the fare includes a seat reservation — nothing covered it\n- STEP: Find dates — already a step\n\n## FINDINGS\n- ", emptyList()),
            RawSegment("Advance fares are released 12 weeks ahead.", listOf(rail)),
            RawSegment("\n- Fares are usually cheapest at release.\n\n## UNCONFIRMED\n- Whether child fares are discounted — no page covered it", emptyList()),
        ),
        searches = listOf(RawSearch("northline rail advance tickets release", listOf(RawSearchResult(rail.url, rail.title!!, "September 2026")))),
        usage = ResearchUsage(1200, 480, 1, "claude-haiku-5-5", 4200),
        truncated = false,
    )

    private fun launch(aiEnabled: Boolean = true, explainerAccepted: Boolean = false, seedBrief: Boolean = false) {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(),
            clock = clock,
            zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds("ui"),
            database = inMemoryDatabase(),
            settingsRepository = InMemorySettingsRepository(
                AppSettings(
                    onboardingCompleted = true, aiEnabled = aiEnabled, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0),
                    researchExplainerVersion = if (explainerAccepted) ResearchConfig.EXPLAINER_VERSION else 0,
                ),
            ),
            apiKeyStore = InMemoryApiKeyStore(FAKE_KEY),
            researchProvider = provider,
            networkStatus = { online },
        )
        runBlocking {
            taskId = container.taskRepository.createTask(NewTask("Book train ride", notes = "Window seat", status = TaskStatus.ACTIONABLE))
            container.taskRepository.addStep(taskId, "Find dates")
            if (seedBrief) {
                val content = com.lifeadmin.autopilot.ai.research.BriefParser.parse(goodResponse.segments, goodResponse.searches, goodResponse.usage, false, listOf("Task title", "Your question"), container.ids::newId)
                container.researchRepository.save(taskId, "When do advance tickets go on sale?", content)
            }
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        waitFor("nav_tasks")
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Book train ride", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Book train ride", substring = true))[0].performClick()
        waitFor("detail_title")
    }

    @After fun tearDown() { runCatching { container.database.close() } }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun tagPrefix(prefix: String) = SemanticsMatcher("testTag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }
    private fun detailScrollTo(tag: String) = compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag(tag))

    @Test fun aiOffKeepsSavedBriefReadableAndOffline() {
        online = false
        launch(aiEnabled = false, seedBrief = true)
        detailScrollTo("detail_ai_card")
        compose.onNode(hasText("AI suggestions and research are off")).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag("action_research")).fetchSemanticsNodes().isEmpty())
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("brief_list")
        compose.onNodeWithTag("brief_offline").assertIsDisplayed()
        compose.onNodeWithTag("brief_question").assertIsDisplayed()
        // The brief is readable; links wait for connectivity.
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("source_1"))
        compose.onNodeWithTag("source_1").performClick()
        waitFor("source_sheet")
        compose.onNodeWithTag("source_open").assertIsNotEnabled()
        assertTrue(provider.requests.isEmpty())
    }

    /**
     * Mirrors the emulator tour: leave the task (scrolled down) for longer than the detail flow's
     * 5 s stop timeout, then come back. The list keeps its scroll position, so the title row may be
     * off screen; the screen itself must be there with the research card still present.
     */
    @Test fun returningToTheTaskAfterALongDetourStillShowsIt() {
        launch(aiEnabled = true, explainerAccepted = true, seedBrief = true)
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("brief_list")
        compose.mainClock.advanceTimeBy(7_000)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(7))
        compose.onNodeWithTag("brief_done").performClick()
        waitFor("detail_list")
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").assertIsDisplayed()
        // And the same through the apply screen's "Back to task".
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("brief_list")
        compose.onNodeWithTag("brief_review").performClick()
        waitFor("apply_rows")
        compose.mainClock.advanceTimeBy(7_000)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(7))
        compose.onNodeWithTag("apply_confirm").performClick()
        waitFor("apply_result")
        compose.onNodeWithTag("apply_done").performClick()
        waitFor("detail_list")
        detailScrollTo("detail_title")
        compose.onNodeWithTag("detail_title").assertIsDisplayed()
        assertEquals(2, runBlocking { container.taskRepository.getSteps(taskId) }.size)
    }
}
