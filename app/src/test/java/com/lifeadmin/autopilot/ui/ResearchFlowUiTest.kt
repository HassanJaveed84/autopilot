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
 * End-to-end research flow on the real shell with a fake provider: entry tiles, preflight,
 * one-time explainer, progress, brief with provenance, source sheet, selective apply + undo,
 * saved research readable with AI off and offline, honest error and Stop states. No network.
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
                val content = com.lifeadmin.autopilot.ai.research.BriefParser.parse(goodResponse.segments, goodResponse.searches, emptyMap(), goodResponse.usage, false, listOf("Task title", "Your question"), container.ids::newId)
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

    private fun startResearch(question: String = "When do advance tickets go on sale?") {
        detailScrollTo("action_research")
        compose.onNodeWithTag("action_research").performClick()
        waitFor("preflight_question")
        compose.onNodeWithTag("preflight_pill").assertIsDisplayed()
        compose.onNodeWithTag("preflight_start").assertIsNotEnabled() // empty question
        compose.onNodeWithTag("preflight_question").performTextInput(question)
        compose.onNodeWithTag("preflight_list").performScrollToNode(hasTestTag("preflight_usage"))
        compose.onNodeWithTag("preflight_usage").assertIsDisplayed()
        compose.onNodeWithTag("preflight_start").assertIsEnabled().performClick()
    }

    @Test fun researchEndToEnd_explainerBriefSourcesApplyUndo() {
        provider.next = { goodResponse }
        launch()
        startResearch()

        // One-time explainer stands between the tap and the paid request.
        waitFor("explainer_sheet")
        assertTrue("nothing sent before acceptance", provider.requests.isEmpty())
        compose.onNodeWithTag("explainer_accept").performClick()

        // Progress hands over to the saved brief.
        waitFor("brief_list")
        assertEquals(1, provider.requests.size)
        assertEquals("Book train ride", provider.requests[0].title)
        compose.onNodeWithTag("brief_question").assertIsDisplayed()
        compose.onNodeWithTag("brief_stamp").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag("brief_unverified")).fetchSemanticsNodes().isEmpty())

        // Provenance in words: a sourced finding and a suggestion, each announced by type.
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("finding_sourced"))
        compose.onNodeWithTag("finding_sourced").assertIsDisplayed()
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("finding_claude_s_suggestion"))
        compose.onNodeWithTag("finding_claude_s_suggestion").assertIsDisplayed()
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("finding_couldn_t_confirm"))

        // Source sheet from the Sources list: URL shown, Copy link, Open in browser enabled.
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("source_1"))
        compose.onNodeWithTag("source_1").assertTouchTargetAtLeast(48.dp).performClick()
        waitFor("source_sheet")
        compose.onNodeWithTag("source_url", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("source_open").assertIsEnabled()
        compose.onNodeWithTag("source_copy").assertIsDisplayed()
        compose.onNode(hasTestTag("source_sheet")).assertIsDisplayed()
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close"))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("source_sheet")).fetchSemanticsNodes().isEmpty() }

        // Moves: sourced STEP and NOTE pre-selected, CHECK not, duplicate shown as skipped.
        compose.onNodeWithTag("brief_list").performScrollToNode(tagPrefix("move_"))
        val moves = compose.onAllNodes(tagPrefix("move_")).fetchSemanticsNodes()
        assertEquals(4, moves.size)
        val states = moves.map { it.config.getOrNull(SemanticsProperties.StateDescription) }
        assertEquals(listOf("Selected", "Selected", "Not selected", "Already in your steps, will be skipped"), states)
        compose.onNodeWithTag("brief_review").assertIsEnabled().performClick()

        // Review: exactly the two changes, nothing applied yet.
        waitFor("apply_rows")
        assertEquals(1, runBlocking { container.taskRepository.getSteps(taskId) }.size)
        compose.onNodeWithTag("apply_confirm").assertIsEnabled().performClick()
        waitFor("apply_result")
        compose.onNode(hasText("Added 2 changes")).assertIsDisplayed()
        val steps = runBlocking { container.taskRepository.getSteps(taskId) }
        assertEquals(listOf("Find dates", "Check the 12-week release date"), steps.map { it.title })
        val task = runBlocking { container.taskRepository.getTask(taskId) }!!
        assertTrue(task.notes!!.contains("From web research:"))
        assertTrue("no reminders were created", runBlocking { container.taskRepository.getPolicy(taskId) }?.enabled != true)

        // Undo while still safe.
        clock.instant = clock.instant.plusSeconds(20)
        compose.onNodeWithTag("apply_undo").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Changes removed")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Find dates"), runBlocking { container.taskRepository.getSteps(taskId) }.map { it.title })
        assertEquals("Window seat", runBlocking { container.taskRepository.getTask(taskId) }!!.notes)

        // Back to the task: the research card shows the saved brief.
        compose.onNodeWithTag("apply_done").performClick()
        waitFor("detail_title")
        detailScrollTo("detail_research_card")
        compose.onNodeWithTag("detail_research_latest").assertIsDisplayed()
        assertEquals(1, runBlocking { container.research.requestsToday() })
    }

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

    @Test fun providerFailureKeepsTheQuestionAndChangesNothing() {
        provider.next = { ResearchResponse.Failure(ResearchError.WebSearchNotEnabled, 0) }
        launch(explainerAccepted = true)
        startResearch("How much is a child fare?")
        waitFor("result_headline")
        compose.onNode(hasText("Web search isn't enabled for this key")).assertIsDisplayed()
        compose.onNode(hasText("“How much is a child fare?”")).assertIsDisplayed()
        compose.onNodeWithTag("result_technical").assertIsDisplayed()
        compose.onNodeWithTag("result_settings").assertIsDisplayed()
        assertEquals(1, runBlocking { container.taskRepository.getSteps(taskId) }.size)
        assertTrue(runBlocking { container.researchRepository.getForTask(taskId) }.isEmpty())
        assertEquals("one attempt, no retry", 1, provider.requests.size)
    }

    @Test fun stopAbortsTheRunAndSaysSearchesMayBeBilled() {
        provider.gate = CompletableDeferred()
        provider.progress = listOf(com.lifeadmin.autopilot.ai.research.ResearchProgress.Searching(1))
        provider.next = { goodResponse }
        launch(explainerAccepted = true)
        startResearch()
        waitFor("research_stop")
        compose.onNodeWithTag("progress_stages").assertIsDisplayed()
        compose.onNodeWithTag("research_stop").performClick()
        waitFor("result_headline")
        compose.onNode(hasText("Research stopped")).assertIsDisplayed()
        compose.onNode(hasText("1 search had already run and may be billed.", substring = true)).assertIsDisplayed()
        assertTrue(runBlocking { container.researchRepository.getForTask(taskId) }.isEmpty())
        provider.gate!!.complete(Unit)
    }

    @Test fun offlinePreflightBlocksStartAndSendsNothing() {
        online = false
        launch(explainerAccepted = true)
        detailScrollTo("action_research")
        compose.onNodeWithTag("action_research").performClick()
        waitFor("preflight_blocker")
        compose.onNode(hasText("You're offline")).assertIsDisplayed()
        compose.onNodeWithTag("preflight_question").performTextInput("Anything")
        compose.onNodeWithTag("preflight_start").assertIsNotEnabled()
        assertTrue(provider.requests.isEmpty())
    }
}
