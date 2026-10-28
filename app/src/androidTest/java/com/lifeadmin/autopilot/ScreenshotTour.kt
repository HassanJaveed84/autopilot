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
import androidx.compose.ui.test.performScrollTo
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
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.TargetKind
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

/** The review's Apply button once something is selected ("Apply N changes"), whatever N the fixture yields. */
private fun ComposeTestRule.waitForApplyCount() = waitUntil(15_000) {
    onAllNodes(SemanticsMatcher("Apply N changes") { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(Regex("Apply \\d+ changes?")) } == true
    }).fetchSemanticsNodes().isNotEmpty()
}

private fun ComposeTestRule.scrollTo(list: String, child: String) = onNodeWithTag(list).performScrollToNode(hasTestTag(child))

/**
 * A lazy list only composes what is on screen, so a tag below the fold is invisible to [waitForTag].
 * Scroll to it instead, retrying while the list (or the item) is still on its way.
 */
private fun ComposeTestRule.waitAndScrollTo(list: String, child: String, timeoutMs: Long = 30_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try { scrollTo(list, child); return } catch (t: Throwable) { last = t; Thread.sleep(500) }
    }
    throw AssertionError("$child never appeared in $list within $timeoutMs ms", last)
}

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

    @Test fun e_capture() {
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
    }

    /** Research saved by an earlier version with built-in AI: read-only from its task and from Settings. */
    @Test fun eq_savedResearch() {
        runBlocking {
            val task = container.database.taskDao().getAll().first { it.title == TRAIN }.id
            if (container.researchThreads.get(SAVED_THREAD) == null) {
                val at = container.clock.now().minus(Duration.ofDays(3)).toEpochMilli()
                val q = "When do advance tickets for this route go on sale?"
                val turn = com.lifeadmin.autopilot.ai.aifirst.AfTurn(
                    id = "$SAVED_THREAD-t0", index = 0, question = q, createdAtMs = at, status = com.lifeadmin.autopilot.ai.aifirst.AfStatus.COMPLETE,
                    authorization = com.lifeadmin.autopilot.ai.aifirst.AfAuthorization(q, config = com.lifeadmin.autopilot.ai.aifirst.AfConfig("claude-sonnet-5-5", 3, 100), promptVersion = 2, payload = q, payloadSha256 = "0".repeat(64), authorizedAtMs = at),
                    segments = listOf(
                        com.lifeadmin.autopilot.ai.aifirst.AfSegment("Advance tickets go on sale 12 weeks before travel (sample).", listOf(1)),
                        com.lifeadmin.autopilot.ai.aifirst.AfSegment(" Prices are usually lowest in the first days after release."),
                    ),
                    answer = "Advance tickets go on sale 12 weeks before travel (sample). [1] Prices are usually lowest in the first days after release.",
                    sources = listOf(com.lifeadmin.autopilot.ai.aifirst.AfSource(1, "https://www.northlinerail.example/tickets", "Tickets | Northline Rail (sample)", listOf("Advance tickets are released 12 weeks ahead."), "September 2026")),
                    searches = listOf(com.lifeadmin.autopilot.ai.aifirst.AfSearch("northline rail advance tickets release", 4)),
                    calls = 1, webSearches = 1, costMicros = 42_000, startedAtMs = at, finishedAtMs = at + 30_000,
                )
                container.researchThreads.create(SAVED_THREAD, task, "Advance tickets", q, turn, Instant.ofEpochMilli(at))
            }
        }
        launch()
        openTask(TRAIN)
        compose.scrollTo("detail_list", "detail_research_card")
        screenshot("eq1-detail-saved-research")
        compose.onNodeWithTag("detail_research_latest").tap()
        compose.waitForTag("thread_readonly")
        screenshot("eq2-saved-thread-read-only")
        compose.backToTabs()
        compose.onNodeWithTag("nav_settings").tap()
        compose.waitForTag("settings_list")
        compose.scrollTo("settings_list", "settings_saved_research")
        screenshot("eq3-settings-saved-research")
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
     * Rich Steps (v0.2.1): the compact checklist with a main link on the row, the step sheet, the
     * step editor and a rich step from ChatGPT in Proposal Review. Addresses are realistic shapes;
     * nothing is opened.
     */
    @Test fun r_richSteps() {
        val title = "Thank-you gift for Omar"
        runBlocking {
            val repo = container.taskRepository
            if (container.database.taskDao().getAll().none { it.title == title }) {
                val id = repo.createTask(NewTask(title, notes = "For being best man. Give it at dinner on Nov 14.", status = TaskStatus.ACTIONABLE))
                val asked = repo.addStep(id, "Ask Leila which scent Omar wears")
                repo.updateStep(asked, "Ask Leila which scent Omar wears", "She said the blue one.", emptyList())
                repo.setStepDone(asked, true)
                val buy = repo.addStep(id, "Buy Bleu de Chanel EDP 50 mL")
                repo.updateStep(
                    buy, "Buy Bleu de Chanel EDP 50 mL",
                    "Estimated price: about \$135 before tax; check the total at checkout.\nGift packaging: choose the complimentary gift wrap.\nCard: “Omar, thank you for standing next to me. — Sam”",
                    listOf(
                        StepLink("https://www.chanel.com/us/fragrance/p/107460/bleu-de-chanel-eau-de-parfum-spray/", "Product page"),
                        StepLink("https://www.nordstrom.com/s/bleu-de-chanel-eau-de-parfum/4224960", "Compare price"),
                    ),
                )
                val dentist = repo.addStep(id, "Call dentist")
                val snap = container.proposalApplier.snapshot(id)!!
                container.proposalSets.save(
                    ProposalSet(
                        "tour-rich-steps", ProposalSource.HANDOFF, TargetKind.TASK, id, madeAtMs = container.clock.now().toEpochMilli(), baseOriginal = snap, base = snap, title = "From ChatGPT",
                        items = listOf(
                            ProposalItem(
                                "a", ItemKind.ADD_STEP, recommended = true,
                                value = ItemValue.Step(
                                    "Book dinner for Nov 14", ContentOrigin.USER, description = "Table for 4 at 7:30 pm. Omar likes Italian; ask for a quiet table.",
                                    links = listOf(StepLink("https://resy.com/cities/new-york-ny/venues/carbone", "Reserve on Resy"), StepLink("https://www.opentable.com/r/carbone-new-york", "OpenTable")),
                                ),
                            ),
                            ProposalItem("d", ItemKind.ADD_STEP_DETAILS, recommended = true, value = ItemValue.StepDetails(stepId = dentist, stepTitle = "Call dentist", description = "Ask whether X-rays are due.")),
                        ),
                    ),
                )
            }
        }
        launch()
        openTask(title)
        val buy = runBlocking { container.database.stepDao().getAll().first { it.title == "Buy Bleu de Chanel EDP 50 mL" }.id }
        compose.soft("rich steps checklist") {
            // Frame the rows themselves, main-link pill included, not just the card's header.
            compose.waitAndScrollTo("detail_list", "step_main_link_$buy")
            screenshot("r1-rich-steps-checklist")
        }
        compose.soft("step sheet") {
            compose.onNodeWithTag("step_open_$buy").tap()
            compose.waitForTag("step_sheet_main_link")
            screenshot("r2-step-sheet")
        }
        compose.soft("step editor") {
            compose.onNodeWithTag("step_sheet_edit").tap()
            compose.waitForTag("step_editor_description")
            screenshot("r3-step-editor")
            compose.onNodeWithTag("step_editor_cancel").tap()
        }
        compose.soft("rich review") {
            // Below the fold on a small screen at 200 % text, so it is scrolled into composition first.
            compose.waitAndScrollTo("detail_list", "detail_review_banner_tour-rich-steps")
            compose.onNodeWithTag("detail_review_banner_tour-rich-steps").tap()
            compose.waitForTag("review_list")
            compose.waitAndScrollTo("review_list", "review_links_a")
            screenshot("r4-review-rich-step")
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
        const val SAVED_THREAD = "tour-saved-research"
    }
}
