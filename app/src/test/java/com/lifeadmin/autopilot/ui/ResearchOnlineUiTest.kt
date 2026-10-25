package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.aifirst.AfRunState
import com.lifeadmin.autopilot.ai.aifirst.AfStatus
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
import com.lifeadmin.autopilot.testing.LegacySeed
import com.lifeadmin.autopilot.testing.ResearchFixtures
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.fileDatabase
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
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
 * Research Online on the real app shell with a scripted provider: question → no-search check →
 * authorization card (context switches, exact payload) → run → answer with provider citations →
 * source sheet → suggestions in the one review; clarification before any search; stop keeping the
 * partial answer; a follow-up; legacy research opened read-only.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResearchOnlineUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val provider = FakeCallProvider()
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 23, 9, 0))
    private lateinit var taskId: String

    private fun launch(title: String = "Renew passport", openTask: Boolean = true, seed: suspend (AppContainer) -> Unit = {}) {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = clock, zoneProvider = FakeZoneProvider(CHICAGO), ids = SequentialIds("ui"),
            database = fileDatabase("research-online-ui.db"),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true, aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION)),
            apiKeyStore = InMemoryApiKeyStore(FAKE_KEY), researchProvider = FakeResearchProvider(), researchCalls = provider, networkStatus = { true },
        )
        runBlocking {
            taskId = container.taskRepository.createTask(NewTask(title, notes = "Expires in March, passport no. X1234", status = TaskStatus.ACTIONABLE))
            container.taskRepository.addStep(taskId, "Gather old passport and two photos")
            seed(container)
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        waitFor("nav_tasks")
        if (!openTask) return
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(title, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(title, substring = true))[0].performClick()
        waitFor("detail_title")
    }

    /** Lets view-model work started by the last tap finish before the database closes under it. */
    @After fun tearDown() {
        runCatching { repeat(30) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(10) }; compose.waitForIdle() }
        runCatching { container.database.close() }
    }

    private fun waitFor(tag: String) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

    /** Waits on service or database state, pumping the main looper (see the stabilization notes in StabilizationTestSupport). */
    private fun until(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        if (compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) runCatching { compose.onNodeWithTag("thread_list").performScrollToNode(hasTestTag(tag)) }
        waitFor(tag)
        runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo() }
        return compose.onNodeWithTag(tag, useUnmergedTree = true)
    }

    private fun hasTextIn(tag: String, text: String) = compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun anyText(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun thread() = runBlocking { container.researchThreads.summaries().filter { !it.legacy }.single().let { container.researchThreads.get(it.id)!! } }
    private fun closeSheet() = compose.onAllNodes(hasContentDescription("Close") or hasText("Close"))[0].performClick()

    private fun ask(question: String, field: String = "thread_question", button: String = "thread_prepare") {
        scrollTo(field).performTextInput(question)
        scrollTo(button).performClick()
    }

    @Test fun taskLinkedResearchFromQuestionToAnswerAndTheOneReview() {
        launch()
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("action_research"))
        compose.onNodeWithTag("action_research").performClick()
        waitFor("thread_question")
        compose.onNodeWithTag("thread_prepare").assertIsNotEnabled()
        provider.enqueue(ResearchFixtures.clear())
        ask(ResearchFixtures.PASSPORT)
        waitFor("auth_card")
        assertEquals("only the no-search check ran", 1, provider.calls.size)
        assertEquals(0, provider.calls[0].maxSearches)
        assertTrue(scrollTo("clarify_clear").let { true })

        // Switch the notes off: the exact payload drops them; the title stays.
        scrollTo("ctx_notes").performClick()
        compose.waitForIdle()
        scrollTo("auth_payload").performClick()
        waitFor("payload_text")
        assertFalse(hasTextIn("payload_text", "X1234"))
        assertTrue(hasTextIn("payload_text", "Task title: Renew passport"))
        closeSheet()
        gone("payload_sheet")

        provider.enqueue(ResearchFixtures.passportAnswer())
        scrollTo("auth_start").assertIsEnabled().performClick()
        waitFor("turn_answer_0")
        assertEquals(2, provider.calls.size)
        val sent = provider.calls[1]
        assertTrue(sent.maxSearches > 0)
        assertTrue(sent.user.contains("Task title: Renew passport"))
        assertFalse("switched-off notes never left the phone", sent.user.contains("X1234"))
        val t = thread()
        assertEquals(taskId, t.taskId)
        assertEquals(AfStatus.COMPLETE, t.turns.single().status)
        assertEquals("what was sent is what was approved", t.turns.single().authorization!!.payload, sent.user)

        // Citations come from the provider; the source sheet shows its cited text and URL.
        scrollTo("source_1").performClick()
        waitFor("source_sheet")
        assertTrue(hasTextIn("source_url", "passports.gov.example/renew"))
        closeSheet()
        gone("source_sheet")

        // Suggestions: one confirmed no-search call, then the one review; nothing on the task changes.
        scrollTo("turn_suggest_0").performClick()
        waitFor("suggest_payload")
        assertEquals("opening the confirmation sends nothing", 2, provider.calls.size)
        provider.enqueue(ResearchFixtures.suggestions())
        compose.onNodeWithTag("suggest_confirm").performClick()
        waitFor("review_apply")
        assertEquals(3, provider.calls.size)
        assertEquals(0, provider.calls[2].maxSearches)
        val steps = runBlocking { container.taskRepository.observeDetails(taskId).first()!!.steps.map { it.title } }
        assertEquals(listOf("Gather old passport and two photos"), steps)
    }

    @Test fun ambiguousQuestionIsClarifiedBeforeAnyWebSearch() {
        launch(openTask = false)
        compose.onNodeWithTag("nav_research").performClick()
        waitFor("home_new")
        compose.onNodeWithTag("home_new").performClick()
        provider.enqueue(ResearchFixtures.askWhichCity())
        ask(ResearchFixtures.UNION_STATION)
        waitFor("clarify_question")
        assertTrue(hasTextIn("clarify_question", "Which city"))
        assertTrue("no authorization card before the answer", compose.onAllNodes(hasTestTag("auth_card")).fetchSemanticsNodes().isEmpty())
        scrollTo("clarify_option_0").performClick()
        until("the answer to be saved") { thread().turns.single().status == AfStatus.READY }
        waitFor("auth_card")
        assertTrue(anyText("You clarified"))
        scrollTo("auth_payload").performClick()
        waitFor("payload_text")
        assertTrue(hasTextIn("payload_text", "Clarified: Which city's Union Station do you mean? — Washington, DC"))
        closeSheet()
        assertEquals("only the no-search check has been sent", 1, provider.calls.size)
        assertTrue(provider.calls.all { it.maxSearches == 0 })
    }

    @Test fun stopKeepsThePartialAnswerAndTheNextQuestionIsAFollowUp() {
        launch()
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("action_research"))
        compose.onNodeWithTag("action_research").performClick()
        provider.enqueue(ResearchFixtures.clear(), ResearchFixtures.pausedPartial())
        provider.gate = CompletableDeferred(); provider.gateCall = 3
        ask("Does the AquaPure X9 remove 99.99% of microplastics?")
        waitFor("auth_card")
        scrollTo("auth_start").performClick()
        until("the continuation call") { provider.calls.size >= 3 }
        waitFor("research_stop")
        compose.onNodeWithTag("research_stop").performClick()
        waitFor("stop_confirm")
        compose.onNodeWithTag("stop_confirm").performClick()
        until("the run to end") { container.researchOnline.state.value !is AfRunState.Running }
        waitFor("turn_answer_0")
        assertTrue(anyText("Stopped by you"))
        val turn = thread().turns.single()
        assertEquals(AfStatus.STOPPED, turn.status)
        assertTrue(turn.answer.contains("99% reduction"))
        assertTrue(turn.costIsFloor)

        // A follow-up carries this thread's earlier answer.
        provider.gate = null
        provider.enqueue(ResearchFixtures.clear())
        ask("What about independent tests?", field = "thread_followup", button = "thread_followup_prepare")
        waitFor("auth_card")
        // The card appears before its authorization preview is computed; the history row follows it.
        compose.waitUntil(WAIT_MS) { anyText("This thread's earlier answers") }
    }

    @Test fun legacyResearchOpensReadOnly() {
        lateinit var legacyId: String
        launch(openTask = false) { c -> legacyId = LegacySeed.m4Thread(c.investigations, null) }
        compose.onNodeWithTag("nav_research").performClick()
        waitFor("home_item_0")
        assertTrue(anyText("earlier format"))
        compose.onNodeWithTag("home_item_0").performClick()
        waitFor("legacy_thread")
        assertTrue(anyText("Recorded status: Answered"))
        assertTrue(anyText("50 Massachusetts Ave NE"))
        assertTrue("no follow-up composer on legacy research", compose.onAllNodes(hasTestTag("thread_followup")).fetchSemanticsNodes().isEmpty())
        assertTrue(provider.calls.isEmpty())
        assertTrue(legacyId.isNotEmpty())
    }
}
