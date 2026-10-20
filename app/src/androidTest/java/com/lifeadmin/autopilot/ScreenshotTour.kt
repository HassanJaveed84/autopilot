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
import androidx.compose.ui.test.performTextClearance
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
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.ui.ai.AiSettingsScreen
import com.lifeadmin.autopilot.ui.ai.AiSettingsViewModel
import com.lifeadmin.autopilot.ui.plan.PlanScreen
import com.lifeadmin.autopilot.ui.plan.PlanViewModel
import com.lifeadmin.autopilot.ui.LifeAdminRoot
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

/** Closes a sheet or dialog an optional step opened: its Cancel if it has one, else system Back. */
private fun ComposeTestRule.closeOverlay(tag: String) {
    closeDialog()
    if (onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) back()
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

        // Paused, archived and overdue: the remaining statuses and the Today attention row.
        runCatching {
            val spanish = repo.createTask(NewTask(SPANISH, status = TaskStatus.ACTIONABLE, preset = ReminderPreset.DAILY))
            repo.changeStatus(spanish, TaskStatus.PAUSED)
        }.onFailure { Log.w(TAG, "seed paused: $it") }
        runCatching {
            val books = repo.createTask(NewTask(BOOKS, status = TaskStatus.ACTIONABLE))
            repo.complete(books)
            repo.archive(books)
        }.onFailure { Log.w(TAG, "seed archived: $it") }
        runCatching {
            val expenses = repo.createTask(NewTask(EXPENSES, status = TaskStatus.ACTIONABLE))
            repo.setDeadline(expenses, DeadlineType.DATE_ONLY, LocalDate.now().minusDays(1), null)
        }.onFailure { Log.w(TAG, "seed overdue: $it") }
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
        compose.soft("inbox nudge sheet") {
            compose.onNodeWithTag("inbox_nudge").tap()
            compose.waitForTag("policy_save")
            screenshot("b3b-inbox-nudge-sheet")
            compose.closeOverlay("policy_save")
        }
        compose.soft("inbox waiting sheet") {
            compose.onNodeWithTag("inbox_waiting").tap()
            compose.waitForTag("waiting_reason")
            screenshot("b3c-inbox-waiting-sheet")
            compose.closeOverlay("waiting_reason")
        }
        compose.soft("inbox delete confirm") {
            compose.onNodeWithTag("inbox_delete").tap()
            compose.waitForText("Delete this note?")
            screenshot("b3d-inbox-delete-confirm")
            compose.closeDialog()
        }

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
        compose.soft("paused and archived filters") {
            compose.onNodeWithTag("filter_paused").tap()
            compose.waitForText(SPANISH)
            screenshot("b6b-tasks-paused-filter")
            compose.onNodeWithTag("filter_archived").tap()
            compose.waitForText(BOOKS)
            screenshot("b6c-tasks-archived-filter")
        }
        compose.soft("sort and search") {
            compose.onNodeWithTag("filter_all").tap()
            compose.onNodeWithTag("tasks_sort").tap() // cycles Next nudge → Deadline → Newest
            compose.waitForText("Deadline")
            screenshot("b4b-tasks-sorted-by-deadline")
            repeat(2) { compose.onNodeWithTag("tasks_sort").tap() }
            compose.onNodeWithTag("search_field").performTextInput("pass")
            compose.waitForText("Renew passport")
            screenshot("b4c-tasks-search")
            compose.onNodeWithTag("search_field").performTextClearance()
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
        compose.soft("steps edit mode") {
            compose.scrollTo("detail_list", "steps_edit")
            compose.onNodeWithTag("steps_edit").tap()
            compose.waitForIdle()
            Thread.sleep(400)
            screenshot("c3b-steps-edit")
            compose.onNodeWithTag("steps_edit").tap()
        }
        compose.soft("history") {
            compose.scrollTo("detail_list", "detail_history_toggle")
            compose.onNodeWithTag("detail_history_toggle").tap()
            compose.waitForText("Hide history")
            compose.scrollTo("detail_list", "detail_history_toggle")
            screenshot("c3c-history")
            compose.onNodeWithTag("detail_history_toggle").tap()
        }
        compose.soft("priority chooser") {
            compose.scrollTo("detail_list", "card_action_priority")
            compose.onNodeWithTag("card_action_priority").tap()
            compose.waitForIdle()
            Thread.sleep(400)
            screenshot("c3d-priority-chooser")
            compose.onNodeWithTag("card_action_priority").tap()
        }
        compose.soft("date picker") {
            compose.scrollTo("detail_list", "card_action_deadline")
            compose.onNodeWithTag("card_action_deadline").tap()
            compose.waitForText("Date and time")
            compose.onNodeWithText("Date only").tap()
            compose.onNodeWithText("Tomorrow").tap() // the date button; a task without a deadline starts at tomorrow
            compose.waitForIdle()
            Thread.sleep(800)
            screenshot("c6b-date-picker")
            compose.closeDialog() // the picker
            compose.closeDialog() // the deadline dialog
        }
        compose.soft("delete confirm") {
            compose.onNodeWithTag("detail_list").performScrollToIndex(0)
            compose.onNodeWithContentDescription("More options").tap()
            compose.waitForText("Delete task")
            compose.onNodeWithText("Delete task").tap()
            compose.waitForText("Delete task?")
            screenshot("c9b-dialog-delete-confirm")
            compose.closeDialog()
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
        compose.backToTabs()
        compose.soft("paused task") {
            compose.onNodeWithTag("filter_paused").tap()
            compose.waitForText(SPANISH)
            compose.onAllNodes(clickableWithText(SPANISH)).onFirst().tap()
            compose.waitForTag("detail_title")
            screenshot("d4b-detail-paused")
            compose.backToTabs()
        }
        compose.soft("archived task") {
            compose.onNodeWithTag("filter_archived").tap()
            compose.waitForText(BOOKS)
            compose.onAllNodes(clickableWithText(BOOKS)).onFirst().tap()
            compose.waitForTag("detail_title")
            screenshot("d4c-detail-archived")
            compose.backToTabs()
        }
        compose.soft("overdue deadline") {
            compose.onNodeWithTag("filter_all").tap()
            openTask(EXPENSES)
            screenshot("d4d-detail-overdue")
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
        compose.soft("capture follow-up sheet") {
            compose.onNodeWithTag("capture_follow_up").tap()
            compose.waitForTag("policy_save")
            screenshot("e2b-capture-follow-up-sheet")
            compose.closeOverlay("policy_save")
        }
        if (compose.onAllNodes(hasTestTag("capture_title")).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("fab_capture").tap()
            compose.waitForTag("capture_title")
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

    /** Saving from Capture: the confirmation toast (5 s, swipeable) over the Today screen. */
    @Test fun ez_captureSaveToast() {
        launch()
        compose.onNodeWithTag("fab_capture").tap()
        compose.waitForTag("capture_title")
        compose.onNodeWithTag("capture_title").performTextInput("Buy new running shoes")
        compose.onNodeWithTag("capture_save").tap()
        compose.waitForTag("toast")
        screenshot("e8-toast-saved")
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
        compose.soft("default follow-up sheet") {
            compose.scrollTo("settings_list", "settings_default_preset")
            compose.onNodeWithTag("settings_default_preset").tap()
            compose.waitForText("Save & nudge uses")
            screenshot("f1b-sheet-default-follow-up")
            compose.back()
        }
        compose.soft("snooze choices") {
            compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Snooze button on notifications"))
            compose.onNodeWithText("Snooze button on notifications").tap()
            compose.waitForIdle()
            Thread.sleep(400)
            screenshot("f1c-snooze-choices")
            compose.onNodeWithText("Snooze button on notifications").tap()
        }
        compose.soft("quiet hours time picker") {
            compose.onNodeWithTag("settings_list").performScrollToNode(hasText("From ", substring = true))
            compose.onNode(hasClickAction() and hasText("From ", substring = true)).tap()
            compose.waitForText("Quiet hours start")
            screenshot("f2b-time-picker")
            compose.closeDialog()
        }
        compose.soft("export and import dialogs") {
            compose.scrollTo("settings_list", "export_button")
            compose.onNodeWithTag("export_button").tap()
            compose.waitForText("Export plaintext backup?")
            screenshot("f4b-dialog-export")
            compose.closeDialog()
            compose.onNodeWithTag("import_button").tap()
            compose.waitForText("Import backup?")
            screenshot("f4c-dialog-import")
            compose.closeDialog()
        }
        compose.soft("licences") {
            compose.scrollTo("settings_list", "open_licenses")
            compose.onNodeWithTag("open_licenses").tap()
            compose.waitForIdle()
            Thread.sleep(800)
            screenshot("f5b-licences")
            compose.back()
            compose.waitForTag("settings_list")
        }
        compose.scrollTo("settings_list", "open_health")
        compose.onNodeWithTag("open_health").tap()
        compose.waitForText("Reminder health")
        screenshot("f6-health")
        repeat(3) { i ->
            compose.onNodeWithTag("health_list").performTouchInput { swipeUp() }
            screenshot("f${i + 7}-health-scrolled")
        }
        compose.soft("health self-test") {
            compose.scrollTo("health_list", "test_now")
            compose.onNodeWithTag("test_now").tap()
            compose.waitForTag("test_result")
            compose.scrollTo("health_list", "test_result")
            screenshot("f9b-health-test-sent")
            instrumentation.targetContext.getSystemService(NotificationManager::class.java).cancelAll()
        }
    }

    /**
     * The nudge itself (board 06): a one-off reminder due in a few seconds, then the shade is
     * opened with the status-bar shell command (no UiAutomator dependency).
     *
     * Why this used to skip on every CI run: the emulator's clock is UTC and the tour runs in
     * the early hours, inside the default quiet hours (21:00–08:00), so the engine correctly
     * deferred the nudge to 08:00 and nothing could be posted within the wait. That is production
     * behaviour working as designed, not a late alarm. The test therefore turns quiet hours off
     * for its own task only (per-policy override) and asks for an exact alarm (granting the
     * emulator app-op the way a user would in Settings), so Android fires at the planned second
     * instead of batching an inexact alarm minutes later. Scheduling code is untouched.
     */
    @Test fun z_notification() {
        val title = "Book train ride for daughter"
        val pkg = instrumentation.targetContext.packageName
        shell("appops set $pkg SCHEDULE_EXACT_ALARM allow")
        val before = runBlocking { container.settingsRepository.current() }
        // Test setup a user could do in Settings: quiet hours off, precise timing on.
        runBlocking { container.settingsRepository.update { it.copy(precisionMode = true, quietHours = it.quietHours.copy(enabled = false)) } }
        val id = runBlocking {
            val id = container.taskRepository.createTask(NewTask(title, status = TaskStatus.ACTIONABLE))
            container.taskRepository.addStep(id, "Check train times and prices")
            listOf("Buy the tickets", "Find and book parking").forEach { container.taskRepository.addStep(id, it) }
            val p = container.taskRepository.getPolicy(id)!!
            container.taskRepository.savePolicy(p.copy(enabled = true, kind = ReminderKind.ONCE, atInstant = container.clock.now().plusSeconds(8), times = emptyList()))
            id
        }
        val planned = runBlocking { container.taskActions.previewNext(id) }
        launch()
        try {
            compose.soft("notification shade") {
                checkNotNull(planned) { "engine planned no nudge for the one-off reminder" }
                val lead = Duration.between(container.clock.now(), planned.plannedAt).seconds.coerceAtLeast(0)
                check(lead <= 120) { "nudge planned for ${planned.plannedAt}, ${lead / 60} min away (deferred?), not waiting" }
                val wait = lead + 60
                val nm = instrumentation.targetContext.getSystemService(NotificationManager::class.java)
                val deadline = System.currentTimeMillis() + wait * 1_000
                while (System.currentTimeMillis() < deadline && nm.activeNotifications.none { it.tag == id }) Thread.sleep(1_000)
                check(nm.activeNotifications.any { it.tag == id }) {
                    val ledger = runBlocking { container.database.occurrenceDao().getForTaskWithStatus(id, OccurrenceStatus.entries).joinToString { "${it.status}@${it.plannedAt}" } }
                    "reminder not posted within ${wait}s of ${planned.plannedAt} (exact alarms=${container.alarmDriver.canScheduleExact()}); ledger: $ledger"
                }
                shell("cmd statusbar expand-notifications")
                Thread.sleep(2_000)
                screenshot("n1-notification")
            }
        } finally {
            shell("cmd statusbar collapse")
            runBlocking {
                container.taskRepository.delete(id)
                container.settingsRepository.update { it.copy(precisionMode = before.precisionMode, quietHours = before.quietHours) }
            }
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
        const val SPANISH = "Learn Spanish for the trip"
        const val BOOKS = "Return the library books"
        const val EXPENSES = "Submit the expense claim"
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
        compose.soft("saved") {
            compose.onNodeWithTag("plan_approve").tap()
            compose.waitForTag("plan_saved")
            screenshot("g6-plan-saved")
        }
    }

    @Test fun g_planUnsupported() {
        val plan = trainPlan.copy(
            reminder = RawReminder(kind = "weekly_days", provenance = "explicit", times = listOf("09:00"), weekdays = listOf("monday", "wednesday"), requestedPhrase = "every Monday and Wednesday at 9"),
            assumptions = emptyList(),
        )
        val provider = ScriptedProvider { PlanningResult.Success(plan, PlanningUsage(812, 356, "scripted")) }
        planScreen(container(provider), "Book the train ride for my daughter, remind me every Monday and Wednesday at 9")
        compose.onNodeWithTag("plan_request").tap()
        compose.waitForTag("plan_approve")
        compose.scrollTo("plan_list", "review_unsupported")
        screenshot("g7-review-unsupported")
    }

    @Test fun h_planErrorsUnauthorized() {
        val provider = ScriptedProvider { PlanningResult.Failure(PlanningError.Unauthorized) }
        planScreen(container(provider), "Renew the car insurance before the end of the month")
        compose.onNodeWithTag("plan_request").tap()
        compose.waitForTag("plan_error")
        screenshot("h2-plan-error-unauthorized")
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
        compose.soft("test connection") {
            compose.scrollTo("ai_settings_list", "ai_test_connection")
            compose.onNodeWithTag("ai_test_connection").tap()
            compose.waitForTag("ai_test_result")
            screenshot("k1b-ai-test-connection")
        }
        compose.soft("delete all confirm") {
            compose.scrollTo("ai_settings_list", "ai_delete_all")
            compose.onNodeWithTag("ai_delete_all").tap()
            compose.waitForText("Delete AI settings?")
            screenshot("k3b-ai-delete-all-confirm")
            compose.closeDialog()
        }
    }
}

/**
 * M3 research screens (boards R1–R20) on the real shell with a scripted provider: entry tiles,
 * preflight, one-time explainer, progress, brief, source sheet, apply, applied, history, AI-off
 * card and error/stop states. The provider never touches the network; the brief's sources use the
 * reserved .example domain and are clearly fictional.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ScreenshotTourResearch {
    @get:Rule val compose = createComposeRule()

    private class ScriptedResearchProvider(private val answer: () -> com.lifeadmin.autopilot.ai.research.ResearchResponse) : com.lifeadmin.autopilot.ai.research.ResearchProvider {
        var hold: CompletableDeferred<Unit>? = null
        override val id = "scripted-research"
        override suspend fun research(
            request: com.lifeadmin.autopilot.ai.research.ResearchRequest,
            apiKey: String,
            model: String,
            onProgress: (com.lifeadmin.autopilot.ai.research.ResearchProgress) -> Unit,
        ): com.lifeadmin.autopilot.ai.research.ResearchResponse {
            onProgress(com.lifeadmin.autopilot.ai.research.ResearchProgress.Sent)
            onProgress(com.lifeadmin.autopilot.ai.research.ResearchProgress.Searching(1))
            hold?.await()
            return answer()
        }
    }

    private val rail = com.lifeadmin.autopilot.ai.research.RawCitation("https://www.northlinerail.example/tickets", "Tickets | Northline Rail (sample)", "Advance tickets are released 12 weeks ahead.")
    private val carPark = com.lifeadmin.autopilot.ai.research.RawCitation("https://parking.riverside-central.example/book", "Riverside Central car park (sample)", "Pre-booking closes at 23:59 the day before.")
    private val blog = com.lifeadmin.autopilot.ai.research.RawCitation("https://travel-tips.example/trains", "Cheap train tips (sample)", "Fares are cheapest in the first week of release.")

    private fun seg(text: String, vararg c: com.lifeadmin.autopilot.ai.research.RawCitation) = com.lifeadmin.autopilot.ai.research.RawSegment(text, c.toList())

    private val sampleResponse = com.lifeadmin.autopilot.ai.research.ResearchResponse.Success(
        segments = listOf(
            seg("## QUICK ANSWER\n"),
            seg("Advance tickets for this route go on sale 12 weeks before travel.", rail),
            seg(" The station car park can be pre-booked until the day before.", carPark),
            seg(" I couldn't confirm whether child fares are discounted.\n\n## NEXT MOVES\n- STEP: Check the 12-week release date for your travel day — "),
            seg("Northline releases advance fares 12 weeks out.", rail),
            seg("\n- STEP: Pre-book the station car park — "),
            seg("Booking closes at 23:59 the day before.", carPark),
            seg("\n- NOTE: Advance fares are released 12 weeks before travel — "),
            seg("From the operator's ticket page.", rail),
            seg("\n- CHECK: Whether child fares are discounted, with the operator — nothing online settled this\n- STEP: Set a reminder a week before release — Claude's suggestion\n\n## FINDINGS\n- "),
            seg("Advance fares are released 12 weeks ahead.", rail),
            seg("\n- "),
            seg("Car park pre-booking closes at 23:59 the day before travel.", carPark),
            seg("\n- DISAGREE: When fares are cheapest | "),
            seg("At release", blog),
            seg(" | "),
            seg("Prices vary by train, not by date", rail),
            seg("\n- Booking early usually beats booking late.\n\n## UNCONFIRMED\n- Child fare discounts — I searched but no page covered this route"),
        ),
        searches = listOf(
            com.lifeadmin.autopilot.ai.research.RawSearch("Northline Rail advance tickets release date", listOf(com.lifeadmin.autopilot.ai.research.RawSearchResult(rail.url, rail.title!!, "September 2026"))),
            com.lifeadmin.autopilot.ai.research.RawSearch("Riverside Central station car park booking", listOf(com.lifeadmin.autopilot.ai.research.RawSearchResult(carPark.url, carPark.title!!, "August 2026"), com.lifeadmin.autopilot.ai.research.RawSearchResult(blog.url, blog.title!!, null))),
        ),
        usage = com.lifeadmin.autopilot.ai.research.ResearchUsage(2140, 612, 2, "claude-haiku-5-5", 9800),
        truncated = false,
    )

    private lateinit var container: AppContainer
    private lateinit var taskId: String

    /**
     * A crash during the activity's teardown hides the failure that ended the test. Record the
     * first uncaught throwable (and any composition exception) in the screenshots folder so the
     * artifact carries it.
     */
    @Before fun captureCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText("thread=${t.name}\n" + Log.getStackTraceString(e))
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun container(provider: com.lifeadmin.autopilot.ai.research.ResearchProvider, aiEnabled: Boolean = true, explainerAccepted: Boolean = false, online: Boolean = true): AppContainer {
        val context = instrumentation.targetContext
        return AppContainer(
            context = context,
            database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(),
            settingsRepository = InMemorySettingsRepository(
                AppSettings(
                    onboardingCompleted = true, aiEnabled = aiEnabled, aiConsentAt = Instant.now(),
                    researchExplainerVersion = if (explainerAccepted) com.lifeadmin.autopilot.ai.research.ResearchConfig.EXPLAINER_VERSION else 0,
                ),
            ),
            apiKeyStore = InMemoryApiKeyStore("test-key-0000000000000000000000000000"),
            researchProvider = provider,
            networkStatus = { online },
        )
    }

    private fun launch(c: AppContainer, seedBrief: Boolean = false) {
        container = c
        runBlocking {
            taskId = c.taskRepository.createTask(NewTask("Book family train ride", notes = "Need tickets and parking details", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
            c.taskRepository.addStep(taskId, "Check train times and prices")
            if (seedBrief) {
                val content = com.lifeadmin.autopilot.ai.research.BriefParser.parse(sampleResponse.segments, sampleResponse.searches, sampleResponse.usage, false, listOf("Task title", "Your question and details", "Notes", "Date and language"), c.ids::newId)
                c.researchRepository.save(taskId, "When do advance tickets go on sale, and can I pre-book parking?", content)
            }
        }
        compose.setContent { LifeAdminTheme { LifeAdminRoot(c) } }
        compose.waitForTag("nav_tasks")
        compose.onNodeWithTag("nav_tasks").tap()
        compose.waitForTag("tasks_list")
        compose.onNodeWithTag("tasks_list").performScrollToNode(hasText("Book family train ride", substring = true))
        compose.onAllNodes(clickableWithText("Book family train ride")).onFirst().tap()
        compose.waitForTag("detail_title")
    }

    @Test fun r1_savedBriefSourcesApply() {
        launch(container(ScriptedResearchProvider { sampleResponse }, explainerAccepted = true), seedBrief = true)
        compose.scrollTo("detail_list", "detail_ai_card")
        screenshot("r01-detail-move-forward-tiles")
        compose.scrollTo("detail_list", "detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").tap()
        compose.soft("brief-open") { compose.waitForTag("brief_list") }
        screenshot("r07-brief-top")
        compose.scrollTo("brief_list", "brief_answer")
        screenshot("r08-brief-answer")
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("finding_sourced"))
        screenshot("r09-brief-moves-findings")
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("finding_sources_disagree"))
        screenshot("r10-brief-findings-disagree")
        compose.scrollTo("brief_list", "brief_sources")
        screenshot("r11-brief-sources")
        compose.scrollTo("brief_list", "brief_log")
        screenshot("r12-brief-log")
        compose.onNodeWithTag("brief_list").performScrollToNode(hasTestTag("source_2"))
        compose.onNodeWithTag("source_2").tap()
        compose.waitForTag("source_sheet")
        screenshot("r13-source-sheet")
        compose.back()
        compose.waitForTag("brief_review")
        compose.onNodeWithTag("brief_review").tap()
        compose.waitForTag("apply_rows")
        screenshot("r14-apply-review")
        compose.onNodeWithTag("apply_confirm").tap()
        compose.waitForTag("apply_result")
        screenshot("r15-applied-undo")
        compose.onNodeWithTag("apply_done").tap()
        compose.waitForTag("detail_list")
        compose.scrollTo("detail_list", "detail_research_card")
        screenshot("r16-detail-research-card")
        compose.onNodeWithTag("detail_research_history").tap()
        compose.waitForTag("history_list")
        screenshot("r17-history")
    }

    @Test fun r2_aiOffKeepsSavedResearch() {
        launch(container(ScriptedResearchProvider { sampleResponse }, aiEnabled = false), seedBrief = true)
        compose.scrollTo("detail_list", "detail_research_card")
        screenshot("r18-detail-ai-off-with-research")
    }

    @Test fun r3_offlineBriefAndBlockedPreflight() {
        launch(container(ScriptedResearchProvider { sampleResponse }, explainerAccepted = true, online = false), seedBrief = true)
        compose.scrollTo("detail_list", "detail_research_latest")
        compose.onNodeWithTag("detail_research_latest").tap()
        compose.waitForTag("brief_offline")
        screenshot("r19-brief-offline")
        compose.back()
        compose.waitForTag("detail_list")
        compose.scrollTo("detail_list", "action_research")
        compose.onNodeWithTag("action_research").tap()
        compose.waitForTag("preflight_blocker")
        screenshot("r20-preflight-offline")
    }

    @Test fun r6_settingsResearchSection() {
        launch(container(ScriptedResearchProvider { sampleResponse }, explainerAccepted = true), seedBrief = true)
        compose.back()
        compose.waitForTag("nav_tasks")
        compose.onNodeWithTag("nav_settings").tap()
        compose.waitForTag("settings_list")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Plan with AI"))
        compose.onAllNodes(clickableWithText("Plan with AI")).onFirst().tap()
        compose.waitForTag("ai_settings_list")
        compose.scrollTo("ai_settings_list", "ai_research")
        screenshot("r23-settings-web-research")
    }
}

/**
 * M3A.2 Deep Research tour: preflight with extracted requirements, modes and Adjust; investigation;
 * result templates C (supplements) and B (fragrance gift); editable proposals; stop sheet; error;
 * usage dashboard. Scripted responses from the shared debug fixtures — no network, no key.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTourResearch2 {
    @get:Rule val compose = createComposeRule()

    private class ScriptedCalls : com.lifeadmin.autopilot.ai.research2.ResearchCallProvider {
        override val id = "scripted-calls"
        val queue = ArrayDeque<com.lifeadmin.autopilot.ai.research2.CallResult>()
        var hold: CompletableDeferred<Unit>? = null
        var calls = 0
        override suspend fun call(call: com.lifeadmin.autopilot.ai.research2.ModelCall, apiKey: String): com.lifeadmin.autopilot.ai.research2.CallResult {
            calls++
            if (calls == 1) hold?.await()
            return queue.removeFirstOrNull() ?: com.lifeadmin.autopilot.ai.research2.CallResult.Failed(com.lifeadmin.autopilot.ai.research.ResearchError.Unexpected("no script"), 0, null, 0)
        }
    }

    private lateinit var container: AppContainer
    private lateinit var taskId: String

    @Before fun captureCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText("thread=${t.name}\n" + Log.getStackTraceString(e))
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun container(calls: ScriptedCalls, aiEnabled: Boolean = true, explainerAccepted: Boolean = true, online: Boolean = true): AppContainer {
        val context = instrumentation.targetContext
        return AppContainer(
            context = context,
            database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(),
            settingsRepository = InMemorySettingsRepository(
                AppSettings(onboardingCompleted = true, aiEnabled = aiEnabled, aiConsentAt = Instant.now(), researchExplainerVersion = if (explainerAccepted) com.lifeadmin.autopilot.ai.research.ResearchConfig.EXPLAINER_VERSION else 0),
            ),
            apiKeyStore = InMemoryApiKeyStore("test-key-0000000000000000000000000000"),
            researchCalls = calls,
            networkStatus = { online },
        )
    }

    private fun launch(c: AppContainer, title: String, notes: String, step: String? = null) {
        container = c
        runBlocking {
            taskId = c.taskRepository.createTask(NewTask(title, notes = notes, status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
            step?.let { c.taskRepository.addStep(taskId, it) }
        }
        compose.setContent { LifeAdminTheme { LifeAdminRoot(c) } }
        compose.waitForTag("nav_tasks")
        compose.onNodeWithTag("nav_tasks").tap()
        compose.waitForTag("tasks_list")
        compose.onNodeWithTag("tasks_list").performScrollToNode(hasText(title, substring = true))
        compose.onAllNodes(clickableWithText(title)).onFirst().tap()
        compose.waitForTag("detail_title")
    }

    private fun openPreflight(request: String) {
        compose.scrollTo("detail_list", "action_research")
        compose.onNodeWithTag("action_research").tap()
        compose.waitForTag("preflight_question")
        compose.onNodeWithTag("preflight_question").performTextInput(request)
    }

    @Test fun s1_supplementsPreflightInvestigationResultProposals() {
        val calls = ScriptedCalls().also { it.hold = CompletableDeferred() }
        calls.queue.addLast(com.lifeadmin.autopilot.testing.Research2Fixtures.supplementsGather())
        calls.queue.addLast(
            com.lifeadmin.autopilot.ai.research2.CallResult.Done(
                listOf(com.lifeadmin.autopilot.ai.research.RawSegment("## REQ r-i3 · status=not_found\nStill nothing from the preferred brands.\n", emptyList())),
                emptyList(), com.lifeadmin.autopilot.ai.usage.MeasuredUsage(9_000, 200, 0, 0, 2_000, 2), com.lifeadmin.autopilot.ai.research2.CallStop.END, 5_000, "msg_2",
            ),
        )
        launch(container(calls, explainerAccepted = false), "Supplements", "Monthly order", step = "Buy magnesium: Pure Encapsulations Magnesium (glycinate)")
        openPreflight(com.lifeadmin.autopilot.testing.Research2Fixtures.SUPPLEMENTS_REQUEST)
        compose.scrollTo("preflight_list", "preflight_for")
        screenshot("s01-preflight-request")
        compose.scrollTo("preflight_list", "preflight_requirements")
        screenshot("s02-preflight-requirements")
        compose.scrollTo("preflight_list", "mode_deep")
        screenshot("s03-preflight-modes")
        compose.scrollTo("preflight_list", "preflight_adjust")
        compose.onNodeWithTag("preflight_adjust").tap()
        compose.waitForTag("adjust_panel")
        compose.scrollTo("preflight_list", "limit_none")
        screenshot("s04-preflight-adjust")
        compose.scrollTo("preflight_list", "preflight_usage")
        screenshot("s05-preflight-scope-estimate")
        compose.onNodeWithTag("preflight_start").tap()
        compose.waitForTag("explainer_sheet")
        screenshot("s06-explainer")
        compose.onNodeWithTag("explainer_accept").tap()
        compose.waitForTag("research_stop")
        screenshot("s07-investigating")
        compose.onNodeWithTag("research_stop").tap()
        compose.waitForTag("stop_sheet")
        screenshot("s08-stop-sheet")
        compose.onNodeWithTag("stop_cancel").tap()
        calls.hold!!.complete(Unit)
        compose.soft("result-open") { compose.waitForTag("brief_list") }
        screenshot("s09-result-c-top")
        compose.scrollTo("brief_list", "brief_coverage")
        screenshot("s10-result-c-coverage")
        compose.scrollTo("brief_list", "item_r-i1")
        screenshot("s11-result-c-item-cards")
        compose.scrollTo("brief_list", "item_r-i3")
        compose.onNodeWithTag("item_r-i3").tap()
        screenshot("s12-result-c-not-found")
        compose.scrollTo("brief_list", "continue_card")
        screenshot("s13-result-continue-card")
        compose.onNodeWithTag("continue_button").tap()
        compose.waitForTag("continue_sheet")
        screenshot("s14-continue-sheet")
        compose.back()
        compose.waitForTag("brief_review")
        compose.scrollTo("brief_list", "brief_log_toggle")
        compose.onNodeWithTag("brief_log_toggle").tap()
        compose.waitForTag("brief_log")
        compose.scrollTo("brief_list", "brief_log")
        screenshot("s15-result-how-researched")
        compose.onNodeWithTag("brief_review").tap()
        compose.waitForTag("apply_list")
        screenshot("s16-proposals")
        compose.onNodeWithTag("apply_confirm").tap()
        compose.waitForTag("apply_result")
        screenshot("s17-applied-undo")
        compose.onNodeWithTag("apply_done").tap()
        compose.waitForTag("detail_list")
        compose.scrollTo("detail_list", "detail_research_card")
        screenshot("s18-detail-research-card")
        compose.onNodeWithTag("detail_research_latest").tap()
        compose.waitForTag("brief_list")
        compose.onNodeWithTag("cost_chip").tap()
        compose.waitForTag("usage_list")
        screenshot("s19-usage-dashboard")
        compose.scrollTo("usage_list", "usage_runs")
        compose.onAllNodes(hasTestTag("usage_run_" + runBlocking { container.usageLedger.getAll() }.first().runId)).onFirst().tap()
        compose.waitForTag("usage_breakdown")
        screenshot("s20-usage-breakdown")
    }

    @Test fun s2_fragranceShortlist() {
        val calls = ScriptedCalls()
        calls.queue.addLast(com.lifeadmin.autopilot.testing.Research2Fixtures.fragranceGather())
        calls.queue.addLast(com.lifeadmin.autopilot.testing.Research2Fixtures.fragranceFillGaps())
        launch(container(calls), "Gift for Dad", "Birthday in November")
        openPreflight(com.lifeadmin.autopilot.testing.Research2Fixtures.FRAGRANCE_REQUEST)
        compose.scrollTo("preflight_list", "preflight_requirements")
        screenshot("s21-preflight-gift-requirements")
        compose.onNodeWithTag("preflight_start").tap()
        compose.soft("result-open") { compose.waitForTag("brief_list") }
        screenshot("s22-result-b-top")
        compose.scrollTo("brief_list", "cand_c1")
        screenshot("s23-result-b-hero")
        compose.scrollTo("brief_list", "style_woody")
        compose.onNodeWithTag("style_woody").tap()
        screenshot("s24-result-b-rerank-woody")
        compose.scrollTo("brief_list", "compare_table")
        screenshot("s25-result-b-compare")
        compose.scrollTo("brief_list", "excluded_c4")
        screenshot("s26-result-b-excluded-conflict")
        compose.scrollTo("brief_list", "cand_details_c1")
        compose.onNodeWithTag("cand_details_c1").tap()
        compose.waitForTag("candidate_sheet")
        screenshot("s27-candidate-sheet")
        compose.back()
        compose.waitForTag("brief_review")
        compose.onNodeWithTag("brief_review").tap()
        compose.waitForTag("apply_list")
        screenshot("s28-proposals-gift")
    }

    @Test fun s3_errorAndOffline() {
        val calls = ScriptedCalls()
        calls.queue.addLast(com.lifeadmin.autopilot.ai.research2.CallResult.Failed(com.lifeadmin.autopilot.ai.research.ResearchError.WebSearchNotEnabled, 0, null, 100))
        launch(container(calls), "Renew passport", "Expires in March")
        openPreflight("How do I renew a UK passport from abroad?")
        compose.onNodeWithTag("preflight_start").tap()
        compose.waitForTag("result_headline")
        screenshot("s29-error-web-search-not-enabled")
    }

    @Test fun s4_offlinePreflight() {
        launch(container(ScriptedCalls(), online = false), "Renew passport", "Expires in March")
        openPreflight("How do I renew a UK passport from abroad?")
        compose.waitForTag("preflight_blocker")
        screenshot("s30-preflight-offline")
    }

    @Test fun s5_settingsUsageEntry() {
        launch(container(ScriptedCalls()), "Supplements", "Monthly order")
        compose.back()
        compose.waitForTag("nav_tasks")
        compose.onNodeWithTag("nav_settings").tap()
        compose.waitForTag("settings_list")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Plan with AI"))
        compose.onAllNodes(clickableWithText("Plan with AI")).onFirst().tap()
        compose.waitForTag("ai_settings_list")
        compose.scrollTo("ai_settings_list", "ai_usage_card")
        screenshot("s31-settings-ai-usage-entry")
        compose.onNodeWithTag("ai_usage_open").tap()
        compose.waitForTag("usage_list")
        compose.scrollTo("usage_list", "usage_prefs")
        screenshot("s32-usage-preferences")
    }
}
