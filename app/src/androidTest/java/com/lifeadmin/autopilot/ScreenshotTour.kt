package com.lifeadmin.autopilot

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.lifeadmin.autopilot.ai.PlanningError
import com.lifeadmin.autopilot.ai.PlanningRequest
import com.lifeadmin.autopilot.ai.PlanningResult
import com.lifeadmin.autopilot.ai.PlanningUsage
import com.lifeadmin.autopilot.ai.RawPlan
import com.lifeadmin.autopilot.ai.RawReminder
import com.lifeadmin.autopilot.ai.TaskPlanningProvider
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.ui.ai.AiSettingsScreen
import com.lifeadmin.autopilot.ui.ai.AiSettingsViewModel
import com.lifeadmin.autopilot.ui.plan.PlanScreen
import com.lifeadmin.autopilot.ui.plan.PlanViewModel
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/*
 * Screenshot tour for visual review (UI polish), run on demand by .github/workflows/screenshots.yml
 * and excluded from the regular instrumented suite. Captures the whole device screen with
 * UiAutomation, so dialogs, sheets and system bars appear as a user sees them. Files go to
 * /sdcard/Android/data/<package>/files/screenshots/ and CI pulls them with adb.
 *
 * Each @Test covers one area and starts fresh, so one broken step doesn't lose the other areas.
 */

private const val TAG = "ScreenshotTour"
private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

private fun screenshot(name: String) {
    dismissSystemAnrDialog()
    instrumentation.waitForIdleSync()
    Thread.sleep(600) // let ripples/animations settle
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
    val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/**
 * CI emulators sometimes show "<another app> isn't responding" over the app under test (run #19:
 * Pixel Launcher). Tap "Wait" so the dialog neither appears in screenshots nor steals focus.
 */
private fun dismissSystemAnrDialog() {
    val automation = instrumentation.uiAutomation
    repeat(3) {
        val root = automation.rootInActiveWindow ?: return
        if (root.findAccessibilityNodeInfosByText("isn't responding").isEmpty()) return
        root.findAccessibilityNodeInfosByText("Wait").firstOrNull { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ?: root.findAccessibilityNodeInfosByText("Wait").firstOrNull()?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(1_000)
    }
}

/** System Back (closes dialogs, sheets and screens). Unlike Espresso.pressBack it doesn't wait for window focus. */
private fun ComposeTestRule.back() {
    dismissSystemAnrDialog()
    instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    waitForIdle()
    Thread.sleep(300)
}

private fun ComposeTestRule.waitForTag(tag: String) =
    waitUntil(15_000) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

private fun ComposeTestRule.waitForText(text: String) =
    waitUntil(15_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

private fun ComposeTestRule.scrollTo(list: String, child: String) = onNodeWithTag(list).performScrollToNode(hasTestTag(child))

/** Optional extra shots: log and carry on, so a renamed label doesn't cost the rest of the tour. */
private fun ComposeTestRule.soft(name: String, block: () -> Unit) {
    runCatching(block).onFailure {
        Log.w(TAG, "Skipped '$name': $it")
        runCatching { back() }
    }
}

/** The installed app, launched normally, with realistic data. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ScreenshotTour {
    @get:Rule val permission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    @get:Rule val compose = createEmptyComposeRule()

    private val container = instrumentation.targetContext.appContainer
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun seed() = runBlocking {
        container.settingsRepository.update { it.copy(onboardingCompleted = true, notificationPermissionRequested = true) }
        if (container.database.taskDao().getAll().any { it.title == TRAIN }) return@runBlocking
        val repo = container.taskRepository
        val now = container.clock.now()

        val train = repo.createTask(NewTask(TRAIN, notes = "For Mia's school trip", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
        val first = repo.addStep(train, "Check train times and prices")
        listOf("Buy the tickets", "Find and book parking").forEach { repo.addStep(train, it) }
        repo.setStepDone(first, true)

        val passport = repo.createTask(NewTask("Renew passport", priority = Priority.HIGH, status = TaskStatus.ACTIONABLE, preset = ReminderPreset.GENTLE))
        repo.setDeadline(passport, DeadlineType.DATE_ONLY, LocalDate.now().plusDays(10), null)

        val lease = repo.createTask(NewTask("Sign the lease renewal", status = TaskStatus.ACTIONABLE))
        repo.changeStatus(lease, TaskStatus.WAITING, "Landlord to send the contract", now.plus(Duration.ofDays(2)))

        repo.createTask(NewTask("Call the bank about the card fee"))

        // Snoozed three times with no progress: shows the "seems stuck" hint.
        val gym = repo.createTask(NewTask(GYM, status = TaskStatus.ACTIONABLE, preset = ReminderPreset.DAILY))
        repeat(3) { i -> repo.snooze(gym, now.plus(Duration.ofHours(1L + i))) }

        val tax = repo.createTask(NewTask("Pay the council tax", status = TaskStatus.ACTIONABLE))
        repo.complete(tax)
    }

    @After fun close() { scenario?.close() }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForText(TRAIN)
    }

    private fun openTask(title: String) {
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitForTag("tasks_list")
        compose.onNodeWithTag("tasks_list").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
        compose.waitForTag("detail_title")
    }

    @Test fun a_onboarding() {
        runBlocking { container.settingsRepository.update { it.copy(onboardingCompleted = false) } }
        try {
            launch()
            screenshot("a1-today-first-run")
        } finally {
            runBlocking { container.settingsRepository.update { it.copy(onboardingCompleted = true) } }
        }
    }

    @Test fun b_tabs() {
        launch()
        screenshot("b1-today")
        compose.onNodeWithTag("today_list").performTouchInput { swipeUp() }
        screenshot("b2-today-scrolled")

        compose.onNodeWithTag("nav_inbox").performClick()
        compose.waitForText("Call the bank")
        screenshot("b3-inbox")

        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitForTag("tasks_list")
        screenshot("b4-tasks")
        compose.onNodeWithTag("tasks_list").performTouchInput { swipeUp() }
        screenshot("b5-tasks-scrolled")
        compose.soft("tasks done filter") {
            compose.onNodeWithTag("tasks_list").performScrollToNode(hasText("Done", substring = true))
            compose.onAllNodesWithText("Done", substring = true)[0].performClick()
            compose.waitForText("Pay the council tax")
            screenshot("b6-tasks-done-filter")
        }
    }

    @Test fun c_taskDetail() {
        launch()
        openTask(TRAIN)
        screenshot("c1-detail")
        compose.scrollTo("detail_list", "policy_summary")
        screenshot("c2-detail-reminders")
        compose.onNodeWithTag("detail_list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("detail_list").performTouchInput { swipeUp() }
        screenshot("c3-detail-steps-history")

        compose.soft("edit dialog") {
            compose.onNodeWithContentDescription("Edit task").performClick()
            compose.waitForTag("edit_title")
            screenshot("c4-dialog-edit")
            compose.back()
        }
        compose.soft("reminder editor") {
            compose.scrollTo("detail_list", "policy_summary")
            compose.onAllNodesWithText("Edit")[1].performClick()
            compose.waitForTag("policy_save")
            screenshot("c5-dialog-reminders")
            compose.back()
        }
        compose.soft("deadline dialog") {
            compose.scrollTo("detail_list", "policy_summary")
            compose.onAllNodesWithText("Edit")[0].performClick()
            Thread.sleep(500)
            screenshot("c6-dialog-deadline")
            compose.back()
        }
        compose.soft("snooze dialog") {
            compose.scrollTo("detail_list", "action_snooze")
            compose.onNodeWithTag("action_snooze").performClick()
            Thread.sleep(500)
            screenshot("c7-dialog-snooze")
            compose.back()
        }
        compose.soft("waiting dialog") {
            compose.scrollTo("detail_list", "action_waiting")
            compose.onNodeWithTag("action_waiting").performClick()
            compose.waitForTag("waiting_reason")
            screenshot("c8-dialog-waiting")
            compose.back()
        }
        compose.soft("overflow menu") {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.waitForText("Delete permanently")
            screenshot("c9-menu")
            compose.back()
        }
    }

    @Test fun d_otherDetails() {
        launch()
        openTask(GYM)
        screenshot("d1-detail-stuck-hint")
        compose.soft("small step") {
            compose.scrollTo("detail_list", "stall_hint")
            compose.onNodeWithTag("stall_break_down").performClick()
            compose.waitForTag("small_step_input")
            screenshot("d2-dialog-small-step")
            compose.back()
        }
        compose.back()
        openTask("Sign the lease renewal")
        screenshot("d3-detail-waiting")
        compose.back()
        compose.soft("done task") {
            compose.onNodeWithTag("tasks_list").performScrollToNode(hasText("Done", substring = true))
            compose.onAllNodesWithText("Done", substring = true)[0].performClick()
            compose.waitForText("Pay the council tax")
            compose.onNodeWithText("Pay the council tax").performClick()
            compose.waitForTag("detail_title")
            screenshot("d4-detail-done")
        }
    }

    @Test fun e_captureAndAiEntry() {
        launch()
        compose.onNodeWithTag("fab_capture").performClick()
        compose.waitForTag("capture_title")
        screenshot("e1-capture-empty")
        compose.onNodeWithTag("capture_title").performTextInput("Book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done")
        screenshot("e2-capture-typed-keyboard")
        compose.soft("notes and priority") {
            compose.onNodeWithText("Add notes or priority").performClick()
            compose.waitForTag("capture_notes")
            screenshot("e3-capture-more")
        }
        compose.onNodeWithTag("capture_plan_ai").performClick()
        compose.waitForTag("plan_setup")
        screenshot("e4-plan-ai-not-set-up")
        compose.onNodeWithTag("plan_open_ai_settings").performClick()
        compose.waitForTag("ai_settings_list")
        screenshot("e5-ai-settings-off")
        compose.onNodeWithTag("ai_settings_list").performTouchInput { swipeUp() }
        screenshot("e6-ai-settings-off-scrolled")
        compose.soft("consent dialog") {
            compose.scrollTo("ai_settings_list", "ai_enabled_row")
            compose.onNodeWithTag("ai_enabled_row").performClick()
            compose.waitForTag("ai_consent_text")
            screenshot("e7-ai-consent")
            compose.back() // declined: AI stays off in the installed app
        }
    }

    @Test fun f_settingsAndHealth() {
        launch()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.waitForTag("settings_list")
        screenshot("f1-settings")
        repeat(4) { i ->
            compose.onNodeWithTag("settings_list").performTouchInput { swipeUp() }
            screenshot("f${i + 2}-settings-scrolled")
        }
        compose.scrollTo("settings_list", "open_health")
        compose.onNodeWithTag("open_health").performClick()
        compose.waitForText("Reminder health")
        screenshot("f6-health")
        repeat(3) { i ->
            compose.onNodeWithTag("health_list").performTouchInput { swipeUp() }
            screenshot("f${i + 7}-health-scrolled")
        }
    }

    private companion object {
        const val TRAIN = "Book train ride"
        const val GYM = "Cancel the gym membership"
    }
}

/**
 * AI screens need an AI answer. Without a real key these host the real screens on an in-memory
 * container with a scripted provider (the same RawPlan shape the API returns): no network call.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ScreenshotTourAiReview {
    @get:Rule val compose = createComposeRule()

    private class ScriptedProvider(private val answer: () -> PlanningResult) : TaskPlanningProvider {
        var hold: CompletableDeferred<Unit>? = null
        override val id = "scripted"
        override suspend fun plan(request: PlanningRequest, apiKey: String, model: String): PlanningResult {
            hold?.await()
            return answer()
        }
        override suspend fun testConnection(apiKey: String, model: String): PlanningError? = null
    }

    private fun container(provider: TaskPlanningProvider, online: Boolean = true): AppContainer {
        val context = instrumentation.targetContext
        return AppContainer(
            context = context,
            database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true, aiEnabled = true, aiConsentAt = Instant.now())),
            apiKeyStore = InMemoryApiKeyStore("test-key-0000000000000000000000000000"),
            planningProvider = provider,
            networkStatus = { online },
        )
    }

    private fun planScreen(c: AppContainer, text: String, taskId: String? = null): PlanViewModel {
        val vm = PlanViewModel(SavedStateHandle(), c.aiPlanning, c.taskRepository, c.taskActions, c.settingsRepository, c.clock, c.zoneProvider, taskId, text)
        compose.setContent {
            LifeAdminTheme {
                PlanScreen(vm, zone = c.zoneProvider.current(), now = c.clock::now, onBack = {}, onOpenTask = {}, onOpenAiSettings = {})
            }
        }
        compose.waitForTag("plan_list")
        return vm
    }

    private val trainPlan = RawPlan(
        title = "Book train ride for daughter",
        steps = listOf("Check train times and prices", "Buy the tickets", "Find and book parking"),
        nextStep = "Check train times and prices",
        reminder = RawReminder(kind = "daily_times", provenance = "explicit", times = listOf("09:00", "18:00"), requestedPhrase = "remind me at 9 and 6 every day until done"),
        assumptions = listOf("Interpreted '9 and 6' as 09:00 and 18:00"),
    )

    @Test fun g_planCapture() {
        val provider = ScriptedProvider { PlanningResult.Success(trainPlan, PlanningUsage(812, 356, "scripted")) }
        provider.hold = CompletableDeferred()
        planScreen(container(provider), "Need to book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done.")
        screenshot("g1-plan-input")
        compose.onNodeWithTag("plan_request").performClick()
        compose.waitForTag("plan_working")
        screenshot("g2-plan-working")
        provider.hold!!.complete(Unit)
        compose.waitForTag("review_title")
        screenshot("g3-review-top")
        compose.scrollTo("plan_list", "review_reminders")
        screenshot("g4-review-reminders")
        compose.scrollTo("plan_list", "review_assumptions")
        screenshot("g5-review-assumptions")
        compose.onNodeWithTag("plan_approve").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("plan_saved")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("plan_saved_card")).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("g6-plan-saved")
    }

    @Test fun h_planErrors() {
        val provider = ScriptedProvider { PlanningResult.Failure(PlanningError.RateLimited(null)) }
        planScreen(container(provider), "Renew the car insurance before the end of the month")
        compose.onNodeWithTag("plan_request").performClick()
        compose.waitForTag("plan_error")
        screenshot("h1-plan-error-rate-limited")
    }

    @Test fun i_planOffline() {
        val provider = ScriptedProvider { PlanningResult.Success(trainPlan, null) }
        planScreen(container(provider, online = false), "Renew the car insurance before the end of the month")
        compose.onNodeWithTag("plan_request").performClick()
        compose.waitForTag("plan_error")
        screenshot("i1-plan-error-offline")
    }

    @Test fun j_followThrough() {
        val plan = RawPlan(
            steps = listOf("Find the cancellation clause in the contract", "Email the gym to cancel", "Cancel the direct debit"),
            nextStep = "Find the cancellation clause in the contract",
            advice = "Gyms often need written notice; check the notice period first.",
        )
        val provider = ScriptedProvider { PlanningResult.Success(plan, PlanningUsage(640, 300, "scripted")) }
        val c = container(provider)
        val taskId = runBlocking {
            c.taskRepository.createTask(NewTask("Cancel the gym membership", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.DAILY))
        }
        planScreen(c, "", taskId)
        screenshot("j1-move-forward-input")
        compose.onNodeWithTag("plan_request").performClick()
        compose.waitForTag("review_title")
        screenshot("j2-move-forward-review")
        compose.onNodeWithTag("plan_list").performTouchInput { swipeUp() }
        screenshot("j3-move-forward-review-scrolled")
        compose.soft("apply confirmation") {
            compose.onNodeWithTag("plan_approve").performClick()
            compose.waitForTag("plan_confirm_summary")
            screenshot("j4-move-forward-confirm")
        }
    }

    @Test fun k_aiSettingsReady() {
        val c = container(ScriptedProvider { PlanningResult.Success(trainPlan, null) })
        val vm = AiSettingsViewModel(c.settingsRepository, c.apiKeyStore, c.aiPlanning, c.clock)
        compose.setContent { LifeAdminTheme { AiSettingsScreen(vm, onBack = {}) } }
        compose.waitForTag("ai_settings_list")
        screenshot("k1-ai-settings-ready")
        compose.onNodeWithTag("ai_settings_list").performTouchInput { swipeUp() }
        screenshot("k2-ai-settings-ready-scrolled")
        compose.onNodeWithTag("ai_settings_list").performTouchInput { swipeUp() }
        screenshot("k3-ai-settings-ready-bottom")
    }
}
