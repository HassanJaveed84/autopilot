package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.ai.research.ResearchConfig
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
import com.lifeadmin.autopilot.testing.InvestigationFixtures
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.fileDatabase
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

/** Robolectric under full-suite load can take several seconds per recomposition. */
private const val WAIT_MS = 20_000L

/**
 * The Investigation Thread on the real shell with a scripted provider (M4): question → objective →
 * authorization (context switches, exact payload) → run → objective-led result → evidence → review;
 * clarification before any authorization; stop and resume; a follow-up with an excluded finding;
 * a legacy M3A.2 session opened as a thread; standalone research from Today.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InvestigationThreadUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val provider = FakeCallProvider()
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 23, 9, 0))
    private lateinit var taskId: String

    private fun launch(title: String = "Renew passport", openTask: Boolean = true, seed: suspend () -> Unit = {}) {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = clock, zoneProvider = FakeZoneProvider(CHICAGO), ids = SequentialIds("ui"),
            database = fileDatabase("investigation-ui.db"),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true, aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION)),
            apiKeyStore = InMemoryApiKeyStore(FAKE_KEY), researchProvider = FakeResearchProvider(), researchCalls = provider, networkStatus = { true },
        )
        runBlocking {
            taskId = container.taskRepository.createTask(NewTask(title, notes = "Expires in March, passport no. X1234", status = TaskStatus.ACTIONABLE))
            container.taskRepository.addStep(taskId, "Gather old passport and two photos")
            seed()
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        waitFor("nav_tasks")
        if (!openTask) return
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(title, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(title, substring = true))[0].performClick()
        waitFor("detail_title")
    }

    @After fun tearDown() { runCatching { container.database.close() } }

    private fun waitFor(tag: String) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

    /**
     * Waits on service or database state rather than on the UI. Compose's waitUntil only runs the
     * Robolectric main looper while there is UI work, so a coroutine resuming on Main after a
     * database hop can sit unrun; this pumps the looper explicitly.
     */
    private fun until(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun scrollTo(tag: String, via: String = tag): SemanticsNodeInteraction {
        // A composed node scrolls itself into view; the list-level search is only for items not composed yet.
        fun composed(t: String) = compose.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        fun merged(t: String) = compose.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty()
        // The list-level scroll only for a node that exists (it spins forever on one that never appears).
        if (!composed(via)) runCatching { compose.onNodeWithTag("thread_list").performScrollToNode(hasTestTag(via)) }
        waitFor(tag)
        val target = if (merged(tag)) tag else via
        if (merged(target)) runCatching { compose.onNodeWithTag("thread_list").performScrollToNode(hasTestTag(target)) }
        runCatching { compose.onNodeWithTag(target, useUnmergedTree = true).performScrollTo() }
        return compose.onNodeWithTag(tag, useUnmergedTree = true)
    }
    private fun hasTextIn(tag: String, text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun thread() = runBlocking { container.investigations.getAll().single() }

    private fun startFromTask(question: String) {
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("action_research"))
        compose.onNodeWithTag("action_research").performClick()
        waitFor("thread_question")
        compose.onNodeWithTag("thread_pill").assertIsDisplayed()
        compose.onNodeWithTag("thread_prepare").assertIsNotEnabled()
        compose.onNodeWithTag("thread_question").performTextInput(question)
        compose.onNodeWithTag("thread_prepare").performClick()
        waitFor("auth_card")
    }

    @Test fun taskLinkedResearchRunsFromObjectiveToEvidenceAndTheOneReview() {
        launch()
        startFromTask(InvestigationFixtures.PASSPORT)
        assertTrue("preparing and showing the card sent nothing", provider.calls.isEmpty())
        assertTrue(hasTextIn("objective_text", "Work out how to"))
        // Switch the notes off: they must not be in the payload, and the card says so.
        scrollTo("ctx_notes", via = "auth_context").performClick()
        until("the finished run to be dismissed") { container.investigationService.state.value is com.lifeadmin.autopilot.ai.investigation.TurnRunState.Idle }
        scrollTo("auth_payload").performClick()
        waitFor("payload_json")
        assertTrue(hasTextIn("payload_sheet", "Not sent: Task notes"))
        assertTrue("the payload itself never holds the switched-off notes", compose.onAllNodes(hasTestTag("payload_json") and hasText("X1234", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("payload_json") and hasText("Renew passport", substring = true)).fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodes(hasContentDescription("Close") or hasText("Close"))[0].performClick()
        gone("payload_sheet")

        provider.enqueue(InvestigationFixtures.passportProcedure())
        compose.onNodeWithTag("auth_start").assertIsEnabled().performClick()
        waitFor("turn_0")
        waitFor("turn_title_0")
        assertEquals(1, provider.calls.size)
        assertTrue(provider.calls[0].user.contains("Task title: Renew passport"))
        assertFalse("the switched-off notes never left the phone", provider.calls[0].user.contains("X1234"))
        assertTrue(hasTextIn("turn_title_0", "Renewing a passport: documents"))
        scrollTo("turn_section_steps_0").assertIsDisplayed()
        scrollTo("turn_section_conditions_0").assertIsDisplayed()
        val t = thread()
        assertEquals(taskId, t.taskId)
        assertEquals(TurnState.DONE, t.turns.single().state)
        assertTrue(t.turns.single().authorization!!.context.any { it.id == "notes" && !it.included })

        // Evidence one tap away.
        scrollTo("turn_evidence_toggle_0").performClick()
        scrollTo("claim_c1").performClick()
        waitFor("evidence_sheet")
        assertTrue(hasTextIn("evidence_reason", "One source"))
        compose.onAllNodes(hasContentDescription("Close") or hasText("Close"))[0].performClick()
        gone("evidence_sheet")

        // The proposal goes through the canonical review, never applied from here.
        scrollTo("turn_review_0").performClick()
        waitFor("review_apply")
        assertEquals(listOf("Gather old passport and two photos"), runBlocking { container.database.stepDao().getForTask(taskId).map { it.title } })
    }

    @Test fun anUnclearQuestionAsksFirstAndCannotStartUntilAnswered_standaloneFromToday() {
        launch(openTask = false)
        compose.onNodeWithTag("nav_research").performClick()
        waitFor("home_new")
        compose.onNodeWithTag("home_new").performClick()
        waitFor("thread_question")
        compose.onNodeWithTag("thread_standalone").assertIsDisplayed()
        compose.onNodeWithTag("thread_question").performTextInput("is it worth it?")
        compose.onNodeWithTag("thread_prepare").performClick()
        waitFor("clarify_question")
        assertTrue("no authorization card exists while the objective is unclear", compose.onAllNodes(hasTestTag("auth_start")).fetchSemanticsNodes().isEmpty())
        scrollTo("clarify_answer").performTextInput("the AquaPure X9 water filter, for my kitchen")
        scrollTo("clarify_submit").performClick()
        waitFor("auth_start")
        compose.onNodeWithTag("auth_start").assertIsEnabled()
        assertTrue(provider.calls.isEmpty())
        val t = thread()
        assertEquals(null, t.taskId)
        assertTrue(t.turns.single().objective.question.contains("AquaPure X9"))
        // Declining costs nothing and is recorded.
        compose.onNodeWithTag("auth_decline").performClick()
        waitFor("turn_declined_0")
        assertTrue(provider.calls.isEmpty())
    }

    @Test fun stoppingKeepsWhatWasFoundAndResumeNeedsANewApproval() {
        launch(title = "Water filter")
        startFromTask("Does the AquaPure X9 remove 99.99% of microplastics?")
        scrollTo("depth_standard").performClick()
        provider.gate = CompletableDeferred(); provider.gateCall = 2
        provider.enqueue(InvestigationFixtures.unverifiableNothingFound())
        compose.waitForIdle()
        compose.onNodeWithTag("auth_start").assertIsEnabled().performClick()
        // Under full-suite load the run can start before the list recomposes; wait on the service, then find the card.
        until("the run started") { container.investigationService.state.value is com.lifeadmin.autopilot.ai.investigation.TurnRunState.Running }
        scrollTo("research_progress")
        until("the second call and a checkpoint") { provider.calls.size == 2 && (container.investigationService.state.value as? com.lifeadmin.autopilot.ai.investigation.TurnRunState.Running)?.checkpointed == true }
        compose.onNodeWithTag("research_stop").performClick()
        waitFor("stop_keep")
        compose.onNodeWithTag("stop_keep").performClick()
        provider.gate!!.complete(Unit)
        waitFor("turn_ending_0")
        scrollTo("turn_recovery_0").assertIsDisplayed()
        assertEquals(TurnState.STOPPED, thread().turns.single().state)
        assertEquals("the call cut off is recorded", 2, runBlocking { container.usageLedger.getAll() }.size)
        scrollTo("turn_resume_0").performClick()
        waitFor("auth_start")
        scrollTo("thread_pending")
        assertTrue(hasTextIn("thread_pending", "RESUMING"))
        assertEquals("resuming sent nothing on its own", 2, provider.calls.size)
    }

    @Test fun aFollowUpCanExcludeAnEarlierFindingAndTheNextTurnNeverSeesIt() {
        launch(title = "Multivitamins")
        startFromTask(InvestigationFixtures.MULTIS)
        scrollTo("depth_quick").performClick()
        provider.enqueue(InvestigationFixtures.multisCompare())
        compose.onNodeWithTag("auth_start").performClick()
        waitFor("turn_title_0")
        scrollTo("turn_section_comparison_0").assertIsDisplayed()
        scrollTo("thread_followup").performTextInput("Is Thorne's NSF certification still current?")
        scrollTo("thread_followup_prepare").performClick()
        waitFor("auth_inherited")
        scrollTo("inherit_c2").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Excluded by you", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        scrollTo("depth_quick").performClick()
        provider.enqueue(InvestigationFixtures.multisVerifyResolvesCertification())
        compose.onNodeWithTag("auth_start").performClick()
        waitFor("turn_1")
        until("the follow-up turn to finish") { thread().turns.size == 2 && thread().turns[1].state == TurnState.DONE }
        assertEquals(2, provider.calls.size)
        assertFalse("the excluded finding is not in the follow-up request", provider.calls[1].user.contains("- c2 ·"))
        assertTrue(provider.calls[1].user.contains("- c4 ·"))
        assertEquals(listOf("c2"), thread().meta.excludedClaimIds)
    }

    @Test fun aLegacySessionOpensAsAThreadAndStaysReadable() {
        launch(seed = {
            val legacy = com.lifeadmin.autopilot.ai.research2.SessionContent(
                "Passport renewal (saved earlier)", InvestigationFixtures.PASSPORT, com.lifeadmin.autopilot.ai.research2.DeliverableTemplate.HOW_TO, emptyList(),
                outcome = com.lifeadmin.autopilot.ai.research2.SessionOutcome.COMPLETE,
            )
            container.sessionRepository.save(com.lifeadmin.autopilot.ai.research2.ResearchSession("legacy1", taskId, clock.now(), clock.now(), legacy))
        })
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("detail_research_latest"))
        compose.onNodeWithTag("detail_research_latest").performClick()
        waitFor("turn_legacy_0")
        assertTrue(hasTextIn("thread_title", "Passport renewal (saved earlier)"))
        scrollTo("turn_legacy_open_0").performClick()
        waitFor("brief_title")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w320dp-h640dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InvestigationThreadLargeTextUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val provider = FakeCallProvider()

    @After fun tearDown() { runCatching { container.database.close() } }

    private fun waitFor(tag: String) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollTo(tag: String, via: String = tag): SemanticsNodeInteraction {
        // A composed node scrolls itself into view; the list-level search is only for items not composed yet.
        fun composed(t: String) = compose.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        fun merged(t: String) = compose.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty()
        // The list-level scroll only for a node that exists (it spins forever on one that never appears).
        if (!composed(via)) runCatching { compose.onNodeWithTag("thread_list").performScrollToNode(hasTestTag(via)) }
        waitFor(tag)
        val target = if (merged(tag)) tag else via
        if (merged(target)) runCatching { compose.onNodeWithTag("thread_list").performScrollToNode(hasTestTag(target)) }
        runCatching { compose.onNodeWithTag(target, useUnmergedTree = true).performScrollTo() }
        return compose.onNodeWithTag(tag, useUnmergedTree = true)
    }

    @Test fun theWholeThreadWorksAt320dpAnd200Percent() {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = FakeClock(at(CHICAGO, 2026, 10, 23, 9, 0)), zoneProvider = FakeZoneProvider(CHICAGO), ids = SequentialIds("lt"),
            database = fileDatabase("investigation-ui.db"),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true, aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION)),
            apiKeyStore = InMemoryApiKeyStore(FAKE_KEY), researchProvider = FakeResearchProvider(), researchCalls = provider, networkStatus = { true },
        )
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        waitFor("nav_research")
        compose.onNodeWithTag("nav_research").performClick()
        waitFor("home_new")
        compose.onNodeWithTag("home_new").performClick()
        waitFor("thread_question")
        compose.onNodeWithTag("thread_question").performTextInput(InvestigationFixtures.MULTIS)
        scrollTo("thread_prepare").performClick()
        waitFor("auth_card")
        scrollTo("depth_quick").performClick()
        scrollTo("auth_adjust").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("adjust_panel")
        scrollTo("adjust_model_claude-sonnet-5-5").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("auth_config") and hasText("SONNET", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        scrollTo("auth_payload").assertIsDisplayed()
        provider.enqueue(InvestigationFixtures.multisCompare())
        compose.onNodeWithTag("auth_start").assertIsDisplayed().performClick()
        waitFor("turn_title_0")
        scrollTo("turn_title_0").assertIsDisplayed()
        scrollTo("turn_caveats_0").assertIsDisplayed()
        scrollTo("turn_section_comparison_0").assertIsDisplayed()
        scrollTo("turn_recommendation_0").assertIsDisplayed()
        scrollTo("thread_followup").assertIsDisplayed()
    }
}
