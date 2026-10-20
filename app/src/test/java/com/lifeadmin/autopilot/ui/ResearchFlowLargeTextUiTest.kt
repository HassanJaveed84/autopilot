package com.lifeadmin.autopilot.ui

import android.content.Context
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
import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.ResearchConfig
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The whole research flow at 200% text on a phone: footers stack, details go one-column, nothing is cut off. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResearchFlowLargeTextUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val provider = FakeResearchProvider()

    private val rail = RawCitation("https://www.northlinerail.example/tickets", "Tickets | Northline Rail", "Advance tickets are released 12 weeks ahead.")
    private val carPark = RawCitation("https://parking.riverside-central.example/book", "Riverside Central car park", "Pre-booking closes at 23:59 the day before.")
    private val response = ResearchResponse.Success(
        segments = listOf(
            RawSegment("## QUICK ANSWER\n", emptyList()),
            RawSegment("Advance tickets for this route go on sale 12 weeks before travel.", listOf(rail)),
            RawSegment(" The station car park can be pre-booked until the day before.", listOf(carPark)),
            RawSegment("\n\n## NEXT MOVES\n- STEP: Check the 12-week release date for your travel day — ", emptyList()),
            RawSegment("Northline releases advance fares 12 weeks out.", listOf(rail)),
            RawSegment("\n- STEP: Pre-book the station car park — ", emptyList()),
            RawSegment("Booking closes at 23:59 the day before.", listOf(carPark)),
            RawSegment("\n- CHECK: Whether child fares are discounted, with the operator — nothing online settled this\n\n## FINDINGS\n- ", emptyList()),
            RawSegment("Advance fares are released 12 weeks ahead.", listOf(rail)),
            RawSegment("\n- DISAGREE: When fares are cheapest | ", emptyList()),
            RawSegment("At release", listOf(carPark)),
            RawSegment(" | ", emptyList()),
            RawSegment("Prices vary by train, not by date", listOf(rail)),
            RawSegment("\n- Booking early usually beats booking late.\n\n## UNCONFIRMED\n- Child fare discounts — no page covered this route", emptyList()),
        ),
        searches = listOf(RawSearch("Northline Rail advance tickets", listOf(RawSearchResult(rail.url, rail.title!!, "September 2026")))),
        usage = ResearchUsage(2140, 612, 2, "claude-haiku-5-5", 9800),
        truncated = false,
    )

    @After fun tearDown() { runCatching { container.database.close() } }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun researchFlowAt200Percent() {
        provider.gate = CompletableDeferred()
        provider.next = { response }
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = FakeClock(at(CHICAGO, 2026, 10, 20, 9, 0)), zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds("ui"), database = inMemoryDatabase(),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true, aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = 0)),
            apiKeyStore = InMemoryApiKeyStore(FAKE_KEY), researchProvider = provider, networkStatus = { true },
        )
        val taskId = runBlocking { container.taskRepository.createTask(NewTask("Book family train ride", notes = "Need tickets and parking details", status = TaskStatus.ACTIONABLE)) }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        waitFor("nav_tasks")
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Book family train ride", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Book family train ride", substring = true))[0].performClick()
        waitFor("detail_list")
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("action_research"))
        compose.onNodeWithTag("action_research").performClick()
        waitFor("preflight_question")
        compose.onNodeWithTag("preflight_question").performTextInput("When do advance tickets go on sale, and can I pre-book parking?")
        compose.onNodeWithTag("preflight_list").performScrollToNode(hasTestTag("detail_OPERATOR"))
        compose.onNodeWithTag("detail_OPERATOR").performTextInput("Northline Rail")
        compose.onNodeWithTag("preflight_list").performScrollToNode(hasTestTag("preflight_cost"))
        compose.onNodeWithTag("preflight_start").assertIsEnabled().performClick()
        waitFor("explainer_sheet")
        compose.onNodeWithTag("explainer_accept").performClick()
        waitFor("research_stop")
        provider.gate!!.complete(Unit)
        waitFor("brief_list")
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("brief_answer"))
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("finding_sources_disagree"))
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("brief_sources"))
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("source_2"))
        compose.onNodeWithTag("source_2").performClick()
        waitFor("source_sheet")
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close"))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("source_sheet")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("brief_review").assertIsEnabled().performClick()
        waitFor("apply_rows")
        compose.onNodeWithTag("apply_confirm").performClick()
        waitFor("apply_result")
        compose.onNodeWithTag("apply_done").performClick()
        waitFor("detail_list")
        assertEquals(2, runBlocking { container.taskRepository.getSteps(taskId) }.size)
    }
}
