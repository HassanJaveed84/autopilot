package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.CallStop
import com.lifeadmin.autopilot.ai.research2.ContractParser
import com.lifeadmin.autopilot.ai.research2.ProposalBuilder
import com.lifeadmin.autopilot.ai.research2.RequirementExtractor
import com.lifeadmin.autopilot.ai.research2.ResearchSession
import com.lifeadmin.autopilot.ai.research2.SessionAssembler
import com.lifeadmin.autopilot.ai.research2.SessionContent
import com.lifeadmin.autopilot.ai.research2.SessionOutcome
import com.lifeadmin.autopilot.ai.research2.SourceRegistry2
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M3A.2 end to end on the real shell with a scripted call provider: tile → preflight (extracted
 * requirements, template, modes, adjust, estimate) → explainer → investigation → result templates →
 * editable proposals → apply/undo → usage dashboard; stop sheet; continuation; offline and AI-off.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Research2FlowUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val provider = FakeCallProvider()
    private var online: Boolean? = true
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 20, 9, 0))
    private lateinit var taskId: String

    private fun launch(aiEnabled: Boolean = true, explainerAccepted: Boolean = true, title: String = "Supplements", seedSession: Boolean = false, existingStep: String? = null) {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = clock, zoneProvider = FakeZoneProvider(CHICAGO), ids = SequentialIds("ui"),
            database = inMemoryDatabase(),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true, aiEnabled = aiEnabled, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = if (explainerAccepted) ResearchConfig.EXPLAINER_VERSION else 0)),
            apiKeyStore = InMemoryApiKeyStore(FAKE_KEY), researchProvider = FakeResearchProvider(), researchCalls = provider, networkStatus = { online },
        )
        runBlocking {
            taskId = container.taskRepository.createTask(NewTask(title, notes = "Monthly order", status = TaskStatus.ACTIONABLE))
            existingStep?.let { container.taskRepository.addStep(taskId, it) }
            if (seedSession) container.sessionRepository.save(seeded())
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        waitFor("nav_tasks")
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(title, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(title, substring = true))[0].performClick()
        waitFor("detail_title")
    }

    private fun seeded(): ResearchSession {
        val r = RequirementExtractor.extract(Research2Fixtures.SUPPLEMENTS_REQUEST)
        val registry = SourceRegistry2(runId = "run1")
        val fx = Research2Fixtures.supplementsGather()
        var c = SessionContent("Thorne & Pure picks", Research2Fixtures.SUPPLEMENTS_REQUEST, r.template, r.requirements, outcome = SessionOutcome.INTERRUPTED)
        c = SessionAssembler.settlePending(SessionAssembler.merge(c, ContractParser.parse(fx.segments, fx.searches, registry, r.requirements, clock.now().toEpochMilli()), registry.sources()))
        c = c.copy(proposals = ProposalBuilder.complete(c, clock.now().toEpochMilli()), outcome = SessionOutcome.INCOMPLETE_EVIDENCE, runs = listOf(com.lifeadmin.autopilot.ai.research2.RunRecord("run1", com.lifeadmin.autopilot.ai.research2.RunKind.INITIAL, clock.now().toEpochMilli(), com.lifeadmin.autopilot.ai.research2.ResearchMode.THOROUGH, "claude-sonnet-5-5", 10, null, emptyList(), com.lifeadmin.autopilot.ai.research2.RunUsage(41_000, 2_600, 0, 0, 5, 42_000), SessionOutcome.INCOMPLETE_EVIDENCE)))
        return ResearchSession("seed", taskId, clock.now(), clock.now(), c)
    }

    @After fun tearDown() { runCatching { container.database.close() } }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun tagPrefix(prefix: String) = SemanticsMatcher("testTag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }
    private fun detailScrollTo(tag: String) = compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag(tag))
    private fun listScrollTo(list: String, tag: String) = compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))

    private fun openPreflight(request: String) {
        detailScrollTo("action_research")
        compose.onNodeWithTag("action_research").performClick()
        waitFor("preflight_question")
        compose.onNodeWithTag("preflight_pill").assertIsDisplayed()
        compose.onNodeWithTag("preflight_start").assertIsNotEnabled()
        compose.onNodeWithTag("preflight_question").performTextInput(request)
    }

    @Test fun supplementsBenchmark_preflightToProposalsApplyUndoAndUsage() {
        provider.enqueue(Research2Fixtures.supplementsGather())
        provider.enqueue(CallResult.Done(listOf(RawSegment("## REQ r-i3 · status=not_found\nStill nothing from the preferred brands.\n", emptyList())), emptyList(), MeasuredUsage(9_000, 200, 0, 0, 2_000, 2), CallStop.END, 5_000, "msg_2"))
        launch(explainerAccepted = false, existingStep = "Buy magnesium: Pure Encapsulations Magnesium (glycinate)")
        openPreflight(Research2Fixtures.SUPPLEMENTS_REQUEST)

        // Requirements extracted on the phone: four items, the brand constraint, the output wish; template C; Thorough default with the buying badge.
        listScrollTo("preflight_list", "preflight_requirements")
        assertEquals(6, compose.onAllNodes(tagPrefix("req_"), useUnmergedTree = true).fetchSemanticsNodes().count { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("req_r-") == true })
        compose.onNodeWithTag("req_r-i3", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("preflight_list").performScrollToNode(hasTestTag("req_r-c0"))
        compose.onNodeWithTag("req_r-c0", useUnmergedTree = true).assert(androidx.compose.ui.test.hasContentDescription("Constraint: Brand preference: Thorne and Pure Encapsulations", substring = true))
        compose.onNodeWithTag("template_PICK_PER_ITEM").assertIsDisplayed()
        // Remove one, add one typed requirement: ids stay stable.
        compose.onNodeWithTag("req_remove_r-o0", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("req_new").performTextInput("vegan capsules")
        compose.onNodeWithTag("req_add", useUnmergedTree = true).performClick()
        waitFor("req_r-u0")
        listScrollTo("preflight_list", "mode_thorough")
        compose.onNode(hasText("Best for buying decisions")).assertIsDisplayed()
        compose.onNodeWithTag("mode_deep").assertIsDisplayed()
        // Adjust: model, searches, spending limit — no limit is the default.
        listScrollTo("preflight_list", "preflight_adjust")
        compose.onNodeWithTag("preflight_adjust").performClick()
        waitFor("adjust_panel")
        listScrollTo("preflight_list", "limit_none")
        compose.onNodeWithTag("limit_none").assertIsDisplayed()
        compose.onNodeWithTag("adjust_more").performClick()
        compose.onNodeWithTag("adjust_searches").assertTextContainsSafely("11 searches max")
        compose.onNodeWithTag("adjust_fewer").performClick()
        compose.onNodeWithTag("estimate_value").assertIsDisplayed()
        compose.onNode(hasText("Start · Thorough · 10 searches")).assertIsDisplayed()
        assertTrue("nothing sent before Start", provider.calls.isEmpty())
        compose.onNodeWithTag("preflight_start").assertIsEnabled().performClick()

        // One-time explainer, then the run. Investigation shows real stages.
        waitFor("explainer_sheet")
        assertTrue(provider.calls.isEmpty())
        compose.onNodeWithTag("explainer_accept").performClick()
        waitFor("brief_list")
        assertEquals(2, provider.calls.size)
        assertTrue("the request carries the edited requirements only", provider.calls[0].user.contains("r-u0 · user_added · vegan capsules") && !provider.calls[0].user.contains("r-o0"))
        assertTrue(provider.calls[0].user.contains("Task title: Supplements"))

        // Result: template C, coverage line, item cards, callouts, honest banner, continue card.
        compose.onNodeWithTag("brief_title").assertIsDisplayed()
        compose.onNodeWithTag("outcome_banner").assertIsDisplayed()
        compose.onNode(hasText("Incomplete · not enough evidence")).assertIsDisplayed()
        listScrollTo("brief_list", "brief_coverage_line")
        compose.onNodeWithTag("brief_coverage_line").assertTextContainsSafely("3 of 5 ready")
        listScrollTo("brief_list", "callout_health")
        compose.onNodeWithTag("callout_health").assertIsDisplayed()
        listScrollTo("brief_list", "item_r-i3")
        compose.onNodeWithTag("item_r-i0").assertIsDisplayed()
        compose.onNodeWithTag("item_r-i3").performClick()
        assertTrue("glucosamine and the typed 'vegan capsules' were both not found", compose.onAllNodes(hasText("Nothing reliable found")).fetchSemanticsNodes().size == 2)
        listScrollTo("brief_list", "continue_card")
        compose.onNodeWithTag("continue_card").assertIsDisplayed()
        listScrollTo("brief_list", "brief_sources")
        compose.onNodeWithTag("source_1").performClick()
        waitFor("source_sheet")
        compose.onNodeWithTag("source_open").assertIsEnabled()
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close"))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("source_sheet")).fetchSemanticsNodes().isEmpty() }

        // Proposals: one per requested item (+ the user's own), sourced steps ticked, research-later unticked, duplicate flagged with Add anyway.
        compose.onNodeWithTag("brief_review").assertIsEnabled().performClick()
        waitFor("apply_list")
        waitFor("apply_confirm")
        // Rows below the fold aren't composed yet: take the proposal list from the saved session and visit each row.
        val saved = runBlocking { container.sessionRepository.getForTask(taskId) }.single()
        val proposals = saved.content.proposals
        assertEquals(5, proposals.size)
        assertEquals(listOf("r-i0", "r-i1", "r-i2", "r-i3", "r-u0"), proposals.map { it.requirementId })
        fun stateOf(id: String): String? {
            listScrollTo("apply_list", "proposal_check_$id")
            return compose.onNodeWithTag("proposal_check_$id", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        }
        assertEquals(listOf("Selected", "Selected", "Will be skipped", "Not selected", "Not selected"), proposals.map { stateOf(it.id) })
        val stepsBefore = runBlocking { container.taskRepository.getSteps(taskId) }.size
        compose.onNode(hasText("Apply 2 changes")).assertIsDisplayed()
        // Edit a title and add anyway on the duplicate.
        val firstId = proposals[0].id
        listScrollTo("apply_list", "proposal_title_$firstId")
        compose.onNodeWithTag("proposal_title_$firstId").performTextClearance()
        compose.onNodeWithTag("proposal_title_$firstId").performTextInput("Order the Thorne multivitamin")
        compose.onNode(hasText("· EDITED BY YOU")).assertIsDisplayed()
        val dupId = proposals[2].id
        listScrollTo("apply_list", "proposal_anyway_$dupId")
        compose.onNodeWithTag("proposal_anyway_$dupId").performClick()
        compose.onNode(hasText("Apply 3 changes")).assertIsDisplayed()
        compose.onNodeWithTag("apply_confirm").performClick()
        waitFor("apply_result")
        compose.onNode(hasText("Added 3 changes")).assertIsDisplayed()
        val steps = runBlocking { container.taskRepository.getSteps(taskId) }
        assertEquals(stepsBefore + 3, steps.size)
        assertTrue(steps.any { it.title == "Order the Thorne multivitamin" })
        val task = runBlocking { container.taskRepository.getTask(taskId) }!!
        assertTrue(task.notes!!.contains("From web research:") && task.notes!!.contains("checked 20 Oct 2026"))
        assertTrue("no reminders were created", runBlocking { container.taskRepository.getPolicy(taskId) }?.enabled != true)
        clock.instant = clock.instant.plusSeconds(20)
        compose.onNodeWithTag("apply_undo").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Changes removed")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(stepsBefore, runBlocking { container.taskRepository.getSteps(taskId) }.size)
        assertEquals("Monthly order", runBlocking { container.taskRepository.getTask(taskId) }!!.notes)
        compose.onNodeWithTag("apply_done").performClick()
        waitFor("detail_list")
        detailScrollTo("detail_research_card")
        compose.onNodeWithTag("detail_research_latest").assertIsDisplayed()

        // Usage: both calls recorded, complete, with an estimate; dashboard reachable from AI settings.
        val recs = runBlocking { container.usageLedger.getAll() }
        assertEquals(2, recs.size)
        assertTrue(recs.all { it.outcome == com.lifeadmin.autopilot.ai.usage.CallOutcome.COMPLETE && it.estimatedMicros!! > 0 })
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("brief_list")
        compose.onNodeWithTag("cost_chip").assertIsDisplayed().performClick()
        waitFor("usage_list")
        compose.onNodeWithTag("usage_month").assertIsDisplayed()
        listScrollTo("usage_list", "usage_runs")
        compose.onAllNodes(tagPrefix("usage_run_"))[0].performClick()
        waitFor("usage_breakdown")
        assertEquals("one breakdown block per billed call", 2, compose.onAllNodes(hasText("Web searches")).fetchSemanticsNodes().size)
    }

    @Test fun fragranceBenchmark_shortlistCompareRerankAndExcluded() {
        provider.enqueue(Research2Fixtures.fragranceGather(), Research2Fixtures.fragranceFillGaps())
        launch(title = "Gift for Dad")
        openPreflight(Research2Fixtures.FRAGRANCE_REQUEST)
        compose.onNodeWithTag("template_SHORTLIST").assertIsDisplayed()
        compose.onNode(hasText("Budget: up to $150")).assertIsDisplayed()
        compose.onNodeWithTag("preflight_start").performClick()
        waitFor("brief_list")
        assertEquals(2, provider.calls.size)
        assertTrue(compose.onAllNodes(hasTestTag("outcome_banner")).fetchSemanticsNodes().isEmpty())
        listScrollTo("brief_list", "cand_c1")
        compose.onNodeWithTag("cand_c1").assertIsDisplayed()
        compose.onNode(hasText("TOP PICK")).assertIsDisplayed()
        compose.onNode(hasText("$98")).assertIsDisplayed()
        // Local re-rank by taste: free, no new call.
        listScrollTo("brief_list", "style_woody")
        compose.onNodeWithTag("style_woody").performClick()
        compose.onNode(hasText("Top pick for “woody”")).assertIsDisplayed()
        assertEquals(2, provider.calls.size)
        listScrollTo("brief_list", "compare_table")
        compose.onNodeWithTag("compare_table").assertIsDisplayed()
        listScrollTo("brief_list", "excluded_c4")
        compose.onNodeWithTag("excluded_c4").assertIsDisplayed()
        listScrollTo("brief_list", "conflict")
        compose.onNodeWithTag("conflict").assertIsDisplayed()
        compose.onNodeWithTag("brief_review").assertIsEnabled()
    }

    @Test fun stopSheetDiscardSavesNothingButRecordsTheCall() {
        provider.gate = CompletableDeferred()
        launch()
        openPreflight("When do advance train tickets go on sale?")
        compose.onNodeWithTag("preflight_start").performClick()
        waitFor("research_stop")
        compose.onNodeWithTag("progress_stages").assertIsDisplayed()
        compose.onNodeWithTag("progress_events").assertIsDisplayed()
        compose.onNodeWithTag("research_stop").performClick()
        waitFor("stop_sheet")
        compose.onNodeWithTag("stop_discard").performClick()
        waitFor("result_headline")
        compose.onNode(hasText("Research stopped")).assertIsDisplayed()
        assertTrue(runBlocking { container.sessionRepository.getAll() }.isEmpty())
        assertEquals(com.lifeadmin.autopilot.ai.usage.CallOutcome.STOPPED, runBlocking { container.usageLedger.getAll() }.single().outcome)
        provider.gate!!.complete(Unit)
    }

    @Test fun providerFailureShowsHonestStateAndChangesNothing() {
        provider.enqueue(CallResult.Failed(ResearchError.WebSearchNotEnabled, 0, null, 100))
        launch()
        openPreflight("How much is a child fare?")
        compose.onNodeWithTag("preflight_start").performClick()
        waitFor("result_headline")
        compose.onNode(hasText("Web search isn't enabled for this key")).assertIsDisplayed()
        compose.onNodeWithTag("result_settings").assertIsDisplayed()
        assertTrue(runBlocking { container.sessionRepository.getAll() }.isEmpty())
        assertEquals(com.lifeadmin.autopilot.ai.usage.CallOutcome.FAILED_NOT_BILLED, runBlocking { container.usageLedger.getAll() }.single().outcome)
    }

    @Test fun offlinePreflightBlocksStartAndAiOffKeepsSavedSessionReadable() {
        online = false
        launch(seedSession = true)
        openPreflight("Anything")
        waitFor("preflight_blocker")
        compose.onNodeWithTag("preflight_start").assertIsNotEnabled()
        assertTrue(provider.calls.isEmpty())
        compose.onNodeWithTag("preflight_cancel").performClick()
        waitFor("detail_list")
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("brief_list")
        compose.onNodeWithTag("brief_offline").assertIsDisplayed()
        compose.onNodeWithTag("brief_title").assertIsDisplayed()
        listScrollTo("brief_list", "brief_sources")
        compose.onNodeWithTag("source_1").performClick()
        waitFor("source_sheet")
        compose.onNodeWithTag("source_open").assertIsNotEnabled()
    }

    @Test fun continueResearchingRunsANewRunOnTheSameSession() {
        provider.enqueue(
            CallResult.Done(
                listOf(RawSegment("## REQ r-i3 · status=answered\nFound a glucosamine product.\n## PICK r-i3 · status=answered\nPick: Thorne Glucosamine & Chondroitin\nWhy: preferred brand\n## PROPOSE step · req=r-i3\nBuy glucosamine: Thorne Glucosamine & Chondroitin\n", emptyList())),
                emptyList(), MeasuredUsage(12_000, 400, 0, 0, 0, 2), CallStop.END, 6_000, "msg_c",
            ),
        )
        launch(seedSession = true)
        detailScrollTo("detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("brief_list")
        compose.onNodeWithTag("banner_continue").performClick()
        waitFor("continue_sheet")
        compose.onNodeWithTag("focus_r-i3").assertIsDisplayed()
        compose.onNodeWithTag("continue_estimate").assertIsDisplayed()
        compose.onNodeWithTag("continue_go").performClick()
        waitFor("brief_list")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("outcome_banner")).fetchSemanticsNodes().isEmpty() }
        val s = runBlocking { container.sessionRepository.get("seed") }!!
        assertEquals(2, s.content.runs.size)
        assertEquals(SessionOutcome.COMPLETE, s.outcome)
        assertTrue(provider.calls.single().user.contains("Continue earlier research"))
        assertFalse(compose.onAllNodes(hasTestTag("continue_card")).fetchSemanticsNodes().isNotEmpty())
    }
}

/** Text may be merged with siblings; check the node's own text or its state description. */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextContainsSafely(text: String) =
    assert(androidx.compose.ui.test.hasText(text, substring = true).or(androidx.compose.ui.test.hasStateDescription(text.substringBefore(' '))))
