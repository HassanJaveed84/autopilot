package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FAKE_KEY
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakePlanningProvider
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.daily
import com.lifeadmin.autopilot.testing.fileDatabase
import com.lifeadmin.autopilot.testing.rawPlan
import com.lifeadmin.autopilot.ui.plan.PlanScreen
import com.lifeadmin.autopilot.ui.plan.PlanViewModel
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PlanScreenUiTest {
    @get:Rule val compose = createComposeRule()
    private val provider = FakePlanningProvider()
    private val alarms = FakeAlarmDriver()
    private var opened: String? = null
    private var openedSettings = false
    private lateinit var container: AppContainer
    private lateinit var vm: PlanViewModel

    private val text = "Need to book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done."

    private fun launch(aiOn: Boolean, initial: String = text) {
        prepare(aiOn)
        show(initial)
    }

    private fun prepare(aiOn: Boolean) {
        val settings = InMemorySettingsRepository(
            AppSettings(onboardingCompleted = true, aiEnabled = aiOn, aiConsentAt = if (aiOn) at(CHICAGO, 2026, 10, 1, 9, 0) else null),
        )
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(),
            clock = FakeClock(at(CHICAGO, 2026, 10, 14, 10, 0)),
            zoneProvider = FakeZoneProvider(CHICAGO),
            ids = SequentialIds("ui"),
            database = fileDatabase("plan-ui.db"),
            settingsRepository = settings,
            alarmDriver = alarms,
            notificationPublisher = FakeNotificationPublisher(),
            environmentProbe = FakeEnvironmentProbe(DeliveryEnvironment()),
            apiKeyStore = InMemoryApiKeyStore(if (aiOn) FAKE_KEY else null),
            planningProvider = provider,
            networkStatus = { true },
        )
        provider.respond(rawPlan(
            "Book train ride for daughter", steps = listOf("Check train times and prices", "Buy the tickets", "Find and book parking"),
            nextStep = "Check train times and prices", reminder = daily("09:00", "18:00", phrase = "remind me at 9 and 6 every day until done"),
            assumptions = listOf("Interpreted '9 and 6' as 09:00 and 18:00"),
        ))
    }

    private fun show(initial: String, taskId: String? = null) {
        vm = PlanViewModel(
            SavedStateHandle(), container.aiPlanning, container.taskRepository, container.taskActions,
            container.settingsRepository, container.clock, container.zoneProvider, taskId, initial,
        )
        compose.setContent {
            LifeAdminTheme(dynamicColor = false) {
                PlanScreen(vm, zone = CHICAGO, now = container.clock::now, onBack = {}, onOpenTask = { opened = it }, onOpenAiSettings = { openedSettings = true })
            }
        }
    }

    /** "Move this forward" on an existing task: the Apply count follows the selected cards. */
    @Test fun followThroughCountFollowsTheSelectedCards() {
        prepare(aiOn = true)
        val taskId = runBlocking { container.taskRepository.createTask(com.lifeadmin.autopilot.data.repository.NewTask("Cancel the gym membership", status = com.lifeadmin.autopilot.domain.model.TaskStatus.ACTIONABLE)) }
        provider.respond(rawPlan("", steps = listOf("Find the cancellation clause", "Email the gym", "Cancel the direct debit"), nextStep = "Find the cancellation clause"))
        show("What first?", taskId)
        waitFor("plan_request")
        compose.onNodeWithTag("plan_request").performClick()
        waitFor("review_step_check_1")
        compose.onNodeWithText("Apply 3 changes").assertExists()
        compose.onNodeWithTag("review_step_check_1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Apply 2 changes").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() = compose.waitForIdle()

    private fun awaitOpened() {
        // Poll the DB first (blocking reads let Room's background write finish under Robolectric),
        // then let the main looper deliver the ViewModel's SAVED state to the screen.
        val written = runCatching { compose.waitUntil(5_000) { runBlocking { container.database.taskDao().getAll().isNotEmpty() } } }.isSuccess
        try { compose.waitUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); opened != null } } catch (e: Throwable) { throw AssertionError("not opened; written=$written state=${vm.state.value.copy(draft = null)}", e) }
    }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollTo(tag: String) = compose.onNodeWithTag("plan_list").performScrollToNode(hasTestTag(tag))

    @Test fun withoutAiTheScreenOffersSetupAndManualSaveAndNeverClaimsParsing() {
        launch(aiOn = false)
        waitFor("plan_setup")
        compose.onNodeWithTag("plan_setup").assertIsDisplayed()
        compose.onNodeWithTag("plan_request").assertIsNotEnabled()
        compose.onNodeWithTag("plan_open_ai_settings").performClick()
        assertTrue(openedSettings)
        compose.onNodeWithTag("plan_save_without_ai").performClick()
        awaitOpened()
        assertTrue(provider.requests.isEmpty())
        assertEquals(1, runBlocking { container.database.taskDao().getAll() }.size)
    }

    @Test fun trainRideReviewShowsOriginalTextStepsAndBothTimesThenCreatesTheTask() {
        launch(aiOn = true)
        waitFor("plan_text")
        compose.onNodeWithTag("plan_request").performClick()
        waitFor("review_title")
        compose.onNodeWithTag("review_original_text").assertIsDisplayed()
        compose.onNodeWithText("“$text”").assertIsDisplayed()
        assertEquals("nothing saved during review", 0, runBlocking { container.database.taskDao().getAll() }.size)
        scrollTo("review_times")
        compose.onNodeWithTag("review_time_0").assertIsDisplayed()
        compose.onNode(hasText("09:00")).assertIsDisplayed()
        compose.onNode(hasText("18:00")).assertIsDisplayed()
        scrollTo("review_reminder_provenance")
        compose.onNodeWithText("You asked for this").assertIsDisplayed()
        compose.onNodeWithTag("plan_approve").performClick()
        awaitOpened()
        val id = opened!!
        val p = runBlocking { container.taskRepository.getPolicy(id) }!!
        assertEquals(ReminderKind.DAILY_TIMES, p.kind)
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), p.times)
        assertEquals(3, runBlocking { container.taskRepository.getSteps(id) }.size)
        assertEquals(at(CHICAGO, 2026, 10, 14, 18, 0), alarms.single().triggerAt)
    }

    @Test fun editsInTheReviewAreWhatGetsSaved() {
        launch(aiOn = true)
        waitFor("plan_text")
        compose.onNodeWithTag("plan_request").performClick()
        waitFor("review_title")
        compose.onNodeWithTag("review_title").performTextClearance()
        compose.onNodeWithTag("review_title").performTextInput("Train tickets for Mia")
        scrollTo("review_step_check_2")
        compose.onNodeWithTag("review_step_check_2").performClick() // untick "Find and book parking"
        compose.onNodeWithTag("plan_approve").performClick()
        awaitOpened()
        assertEquals("Train tickets for Mia", runBlocking { container.taskRepository.getTask(opened!!) }!!.title)
        assertEquals(2, runBlocking { container.taskRepository.getSteps(opened!!) }.size)
    }

    @Test fun errorKeepsTextAndShowsRetry() {
        launch(aiOn = true)
        provider.fail(com.lifeadmin.autopilot.ai.PlanningError.RateLimited(java.time.Duration.ofSeconds(20)))
        waitFor("plan_text")
        compose.onNodeWithTag("plan_request").performClick()
        waitFor("plan_error")
        compose.onNodeWithText("Anthropic is rate-limiting this key (429). Try again in 20 s.", substring = true).assertIsDisplayed() // one explanation, words kept
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithTag("plan_save_without_ai").assertIsDisplayed()
        assertEquals(0, runBlocking { container.database.taskDao().getAll() }.size)
    }

    @Test fun discardReturnsToTheTextWithoutSaving() {
        launch(aiOn = true)
        waitFor("plan_text")
        compose.onNodeWithTag("plan_request").performClick()
        waitFor("plan_reject")
        compose.onNodeWithTag("plan_reject").performClick()
        waitFor("plan_text")
        compose.onNodeWithText(text).assertIsDisplayed()
        assertEquals(0, runBlocking { container.database.taskDao().getAll() }.size)
        assertTrue(alarms.alarms.isEmpty())
    }
}
