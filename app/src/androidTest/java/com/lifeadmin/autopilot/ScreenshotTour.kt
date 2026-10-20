package com.lifeadmin.autopilot

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
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
import com.lifeadmin.autopilot.domain.model.ReminderKind
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

/**
 * Back until the tab bar is on screen again. A dialog that outlived closeDialog() (its Cancel
 * click still settling) would otherwise swallow the one press meant for the screen.
 */
private fun ComposeTestRule.backToTabs() {
    repeat(3) {
        if (onAllNodes(hasTestTag("nav_tasks")).fetchSemanticsNodes().isNotEmpty()) return
        back()
    }
}

/**
 * Closes a dialog with its own Cancel / Discard button. System Back is unreliable there: with a
 * text field focused, the first Back only hides the keyboard. Does nothing when no dialog is open,
 * so a failed optional step can't navigate away from the screen the next steps need.
 */
private fun ComposeTestRule.closeDialog() {
    val cancel = onAllNodes(hasText("Cancel")).fetchSemanticsNodes()
    when {
        cancel.isNotEmpty() -> onAllNodes(hasText("Cancel"))[cancel.size - 1].tap()
        onAllNodes(hasContentDescription("Discard changes")).fetchSemanticsNodes().isNotEmpty() ->
            onNode(hasContentDescription("Discard changes")).tap()
    }
    waitForIdle()
}

/** The screen's state has loaded (the header date is blank until it has). */
private val hasNonBlankText = SemanticsMatcher("has non-blank text") { node ->
    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true
}

private fun ComposeTestRule.waitForTag(tag: String) =
    waitUntil(15_000) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

private fun ComposeTestRule.waitForText(text: String) =
    waitUntil(15_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

private fun ComposeTestRule.scrollTo(list: String, child: String) = onNodeWithTag(list).performScrollToNode(hasTestTag(child))

/**
 * A task card merges its text into the clickable column (one TalkBack announcement), so the title
 * is on the clickable node itself; compact rows keep the text in a descendant. Match either.
 */
private fun clickableWithText(text: String) =
    hasClickAction() and (hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

/**
 * Clicks through the semantics tree instead of injecting a touch, so a target hidden behind the
 * keyboard or the Capture button (or scrolled just off screen) still gets the click.
 */
private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

/**
 * Optional extra shots: record and carry on, so a renamed label doesn't cost the rest of the tour.
 * Skips are listed in screenshots/skipped.txt next to the PNGs.
 */
private fun ComposeTestRule.soft(name: String, block: () -> Unit) {
    runCatching(block).onFailure {
        Log.w(TAG, "Skipped '$name': $it")
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "skipped.txt").appendText("$name: ${it.toString().lineSequence().take(3).joinToString(" | ")}\n")
        runCatching { closeDialog() }
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
        // Wait for the day's headline rather than a card: at 200% text or on a small display the
        // train card can sit below the fold of the lazy rail, where it is not composed yet. The
        // tab bar and a non-empty header date mean the first frame after launch is behind us.
        compose.waitForTag("today_headline")
        compose.waitForTag("nav_tasks")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("header_date") and hasNonBlankText).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openTask(title: String) {
        compose.onNodeWithTag("nav_tasks").tap()
        compose.waitForTag("tasks_list")
        compose.onNodeWithTag("tasks_list").performScrollToNode(hasText(title, substring = true))
        compose.onAllNodes(clickableWithText(title)).onFirst().tap()
        compose.waitForTag("detail_title")
    }

    /** Empty states (board 13 and 14): no tasks at all. The next test's seed puts the data back. */
    @Test fun a0_emptyStates() {
        runBlocking { container.database.taskDao().getAll().forEach { container.taskRepository.delete(it.id) } }
        launch()
        compose.waitForText("Try something like") // the whole empty state, not just its headline
        compose.waitForIdle()
        Thread.sleep(800)
        screenshot("a0-today-empty")
        compose.onNodeWithTag("nav_inbox").tap()
        compose.waitForText("Inbox clear")
        screenshot("a0-inbox-clear")
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

        compose.onNodeWithTag("nav_inbox").tap()
        compose.waitForText("Call the bank")
        screenshot("b3-inbox")

        compose.onNodeWithTag("nav_tasks").tap()
        compose.waitForTag("tasks_list")
        screenshot("b4-tasks")
        compose.onNodeWithTag("tasks_list").performTouchInput { swipeUp() }
        screenshot("b5-tasks-scrolled")
        compose.soft("tasks done filter") {
            compose.onNodeWithTag("tasks_list").performScrollToIndex(0)
            compose.onNodeWithTag("filter_completed").tap()
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
            compose.onNodeWithTag("detail_list").performScrollToIndex(0) // the header is the list's first item
            compose.onNodeWithContentDescription("Edit task").tap()
            compose.waitForTag("edit_title")
            screenshot("c4-dialog-edit")
            compose.closeDialog()
        }
        compose.soft("reminder editor") {
            compose.scrollTo("detail_list", "card_action_reminders")
            compose.onNodeWithTag("card_action_reminders").tap()
            compose.waitForTag("policy_save")
            screenshot("c5-sheet-follow-up")
            compose.back()
        }
        compose.soft("deadline dialog") {
            compose.scrollTo("detail_list", "card_action_deadline")
            compose.onNodeWithTag("card_action_deadline").tap()
            compose.waitForText("Date and time")
            screenshot("c6-dialog-deadline")
            compose.closeDialog()
        }
        compose.soft("snooze dialog") {
            compose.scrollTo("detail_list", "action_snooze")
            compose.onNodeWithTag("action_snooze").tap()
            compose.waitForText("Tomorrow morning")
            screenshot("c7-dialog-snooze")
            compose.back()
        }
        compose.soft("waiting dialog") {
            compose.scrollTo("detail_list", "action_waiting")
            compose.onNodeWithTag("action_waiting").tap()
            compose.waitForTag("waiting_reason")
            screenshot("c8-dialog-waiting")
            compose.closeDialog()
        }
        compose.soft("overflow menu") {
            compose.onNodeWithTag("detail_list").performScrollToIndex(0)
            compose.onNodeWithContentDescription("More options").tap()
            compose.waitForText("Delete task")
            screenshot("c9-more-actions")
            compose.back()
        }
    }

    @Test fun d_otherDetails() {
        launch()
        openTask(GYM)
        screenshot("d1-detail-stuck-hint")
        compose.soft("small step") {
            compose.scrollTo("detail_list", "stall_hint")
            compose.onNodeWithTag("stall_break_down").tap()
            compose.waitForTag("small_step_input")
            screenshot("d2-dialog-small-step")
            compose.closeDialog()
        }
        compose.backToTabs()
        openTask("Sign the lease renewal")
        screenshot("d3-detail-waiting")
        compose.backToTabs()
        compose.soft("done task") {
            compose.onNodeWithTag("tasks_list").performScrollToIndex(0)
            compose.onNodeWithTag("filter_completed").tap()
            compose.waitForText("Pay the council tax")
            compose.onAllNodes(clickableWithText("Pay the council tax")).onFirst().tap()
            compose.waitForTag("detail_title")
            screenshot("d4-detail-done")
        }
    }

    @Test fun e_captureAndAiEntry() {
        launch()
        compose.onNodeWithTag("fab_capture").tap()
        compose.waitForTag("capture_title")
        screenshot("e1-capture-empty")
        compose.onNodeWithTag("capture_title").performTextInput("Book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done")
        compose.waitForText("104 / 2000") // the typed text reaches the field through the view model
        screenshot("e2-capture-typed-keyboard")
        compose.soft("notes, priority, deadline") {
            compose.onNodeWithTag("capture_details").tap()
            compose.waitForTag("capture_notes")
            screenshot("e3-capture-more")
        }
        // With AI off, "Plan with AI" opens the setup sheet in place (the note is kept).
        compose.onNodeWithTag("capture_plan_ai").tap()
        compose.waitForTag("capture_ai_turn_on")
        screenshot("e4-ai-setup-in-place")
        compose.onNodeWithTag("capture_ai_settings").tap()
        compose.waitForTag("ai_settings_list")
        screenshot("e5-ai-settings-off")
        compose.onNodeWithTag("ai_settings_list").performTouchInput { swipeUp() }
        screenshot("e6-ai-settings-off-scrolled")
        compose.soft("consent dialog") {
            compose.scrollTo("ai_settings_list", "ai_enabled_row")
            compose.onNodeWithTag("ai_enabled_row").tap()
            compose.waitForTag("ai_consent_text")
            screenshot("e7-ai-consent")
            compose.back() // declined: AI stays off in the installed app
        }
    }

    @Test fun f_settingsAndHealth() {
        launch()
        compose.onNodeWithTag("nav_settings").tap()
        compose.waitForTag("settings_list")
        screenshot("f1-settings")
        repeat(4) { i ->
            compose.onNodeWithTag("settings_list").performTouchInput { swipeUp() }
            screenshot("f${i + 2}-settings-scrolled")
        }
        compose.scrollTo("settings_list", "open_health")
        compose.onNodeWithTag("open_health").tap()
        compose.waitForText("Reminder health")
        screenshot("f6-health")
        repeat(3) { i ->
            compose.onNodeWithTag("health_list").performTouchInput { swipeUp() }
            screenshot("f${i + 7}-health-scrolled")
        }
    }

    /**
     * The nudge itself (board 06): a one-off reminder due in a few seconds, then the shade is
     * opened with the status-bar shell command (no UiAutomator dependency). Best effort: the
     * alarm can take a while on a busy emulator, so a timeout only skips the shot.
     */
    @Test fun z_notification() {
        val title = "Book train ride for daughter"
        val id = runBlocking {
            val id = container.taskRepository.createTask(NewTask(title, status = TaskStatus.ACTIONABLE))
            container.taskRepository.addStep(id, "Check train times and prices")
            listOf("Buy the tickets", "Find and book parking").forEach { container.taskRepository.addStep(id, it) }
            val p = container.taskRepository.getPolicy(id)!!
            container.taskRepository.savePolicy(p.copy(enabled = true, kind = ReminderKind.ONCE, atInstant = container.clock.now().plusSeconds(5), times = emptyList()))
            id
        }
        launch()
        try {
            compose.soft("notification shade") {
                val nm = instrumentation.targetContext.getSystemService(NotificationManager::class.java)
                val deadline = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < deadline && nm.activeNotifications.none { it.tag == id }) Thread.sleep(1_000)
                check(nm.activeNotifications.any { it.tag == id }) { "reminder not posted within 2 minutes" }
                shell("cmd statusbar expand-notifications")
                Thread.sleep(2_000)
                screenshot("n1-notification")
            }
        } finally {
            shell("cmd statusbar collapse")
            runBlocking { container.taskRepository.delete(id) }
        }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
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
        compose.onNodeWithTag("plan_request").tap()
        compose.waitForTag("plan_working")
        screenshot("g2-plan-working")
        provider.hold!!.complete(Unit)
        compose.waitForTag("plan_approve") // the review bottom bar is always on screen
        compose.scrollTo("plan_list", "review_title") // the title card can be below the fold at 200% text
        screenshot("g3-review-top")
        compose.scrollTo("plan_list", "review_reminders")
        screenshot("g4-review-reminders")
        compose.scrollTo("plan_list", "review_assumptions")
        screenshot("g5-review-assumptions")
    }

    @Test fun h_planErrors() {
        val provider = ScriptedProvider { PlanningResult.Failure(PlanningError.RateLimited(null)) }
        planScreen(container(provider), "Renew the car insurance before the end of the month")
        compose.onNodeWithTag("plan_request").tap()
        compose.waitForTag("plan_error")
        screenshot("h1-plan-error-rate-limited")
    }

    @Test fun i_planOffline() {
        val provider = ScriptedProvider { PlanningResult.Success(trainPlan, null) }
        planScreen(container(provider, online = false), "Renew the car insurance before the end of the month")
        compose.onNodeWithTag("plan_request").tap()
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
        planScreen(c, "I keep putting this off. What's the first thing to do?", taskId)
        screenshot("j1-move-forward-input")
        compose.onNodeWithTag("plan_request").tap()
        compose.waitForTag("plan_approve")
        screenshot("j2-move-forward-review")
        compose.onNodeWithTag("plan_list").performTouchInput { swipeUp() }
        screenshot("j3-move-forward-review-scrolled")
        compose.soft("deselect a change") {
            compose.scrollTo("plan_list", "review_step_check_1")
            compose.onNodeWithTag("review_step_check_1").tap() // the Apply count follows the selection
            compose.waitForText("Apply 2 changes")
            screenshot("j4-move-forward-two-changes")
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
