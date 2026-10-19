package com.lifeadmin.autopilot

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.espresso.Espresso
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
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.ui.plan.PlanScreen
import com.lifeadmin.autopilot.ui.plan.PlanViewModel
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.LocalDate

/**
 * Captures real device screenshots (UiAutomation) for visual review. Files go to the app's
 * external files dir: /sdcard/Android/data/<package>/files/screenshots/ (CI pulls them with adb).
 */
private fun screenshot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    Thread.sleep(600) // let ripples/animations settle
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
    val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** The installed app, launched normally, with a few realistic tasks. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    @get:Rule val permission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.appContainer

    init {
        // Seed before the activity starts so the first frame already shows real data.
        runBlocking {
            container.settingsRepository.update { it.copy(onboardingCompleted = true, notificationPermissionRequested = true) }
            if (container.database.taskDao().getAll().none { it.title == "Book train ride" }) {
                val repo = container.taskRepository
                val train = repo.createTask(NewTask("Book train ride", notes = "For Mia's school trip", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
                listOf("Check train times and prices", "Buy the tickets", "Find and book parking").forEach { repo.addStep(train, it) }
                val passport = repo.createTask(NewTask("Renew passport", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.GENTLE))
                repo.setDeadline(passport, DeadlineType.DATE_ONLY, LocalDate.now().plusDays(10), null)
                val lease = repo.createTask(NewTask("Sign the lease renewal", status = TaskStatus.ACTIONABLE))
                repo.changeStatus(lease, TaskStatus.WAITING, "Landlord to send the contract", container.clock.now().plus(Duration.ofDays(2)))
                repo.createTask(NewTask("Call the bank about the card fee"))
            }
        }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForText(text: String) = compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun tour() {
        waitForText("Book train ride")
        screenshot("01-today")

        compose.onNodeWithTag("nav_inbox").performClick()
        waitForText("Call the bank")
        screenshot("02-inbox")

        compose.onNodeWithTag("nav_tasks").performClick()
        waitForText("Renew passport")
        screenshot("03-tasks")

        compose.onNodeWithText("Book train ride").performClick()
        waitForTag("detail_title")
        screenshot("04-task-detail")
        Espresso.pressBack()

        compose.onNodeWithTag("fab_capture").performClick()
        waitForTag("capture_title")
        compose.onNodeWithTag("capture_title").performTextInput("Book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done")
        Espresso.closeSoftKeyboard()
        screenshot("05-capture")

        compose.onNodeWithTag("capture_plan_ai").performClick()
        waitForTag("plan_setup")
        screenshot("06-plan-with-ai-not-set-up")
        compose.onNodeWithTag("plan_open_ai_settings").performClick()
        waitForTag("ai_settings_list")
        screenshot("07-ai-settings")
        Espresso.pressBack()
        Espresso.pressBack()

        compose.onNodeWithTag("nav_settings").performClick()
        waitForTag("settings_list")
        screenshot("08-settings")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_ai_settings"))
        screenshot("09-settings-ai-section")

        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_health"))
        compose.onNodeWithTag("open_health").performClick()
        waitForText("Reminder health")
        screenshot("10-reminder-health")
    }
}

/**
 * The review screen needs an AI answer. Without a real key this hosts the real PlanScreen with a
 * scripted provider (the same JSON shape the API returns) — no network call is made.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTourAiReview {
    @get:Rule val compose = createComposeRule()

    private class ScriptedProvider : TaskPlanningProvider {
        override val id = "scripted"
        override suspend fun plan(request: PlanningRequest, apiKey: String, model: String) = PlanningResult.Success(
            RawPlan(
                title = "Book train ride for daughter",
                steps = listOf("Check train times and prices", "Buy the tickets", "Find and book parking"),
                nextStep = "Check train times and prices",
                reminder = RawReminder(kind = "daily_times", provenance = "explicit", times = listOf("09:00", "18:00"), requestedPhrase = "remind me at 9 and 6 every day until done"),
                assumptions = listOf("Interpreted '9 and 6' as 09:00 and 18:00"),
            ),
            PlanningUsage(812, 356, model),
        )
        override suspend fun testConnection(apiKey: String, model: String): PlanningError? = null
    }

    @Test fun reviewScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer(
            context = context,
            database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(),
            settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true, aiEnabled = true, aiConsentAt = java.time.Instant.now())),
            apiKeyStore = InMemoryApiKeyStore("test-key-0000000000000000000000000000"),
            planningProvider = ScriptedProvider(),
            networkStatus = { true },
        )
        val vm = PlanViewModel(
            SavedStateHandle(), container.aiPlanning, container.taskRepository, container.taskActions,
            container.settingsRepository, container.clock, container.zoneProvider, null,
            "Need to book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done.",
        )
        compose.setContent {
            LifeAdminTheme {
                PlanScreen(vm, zone = container.zoneProvider.current(), now = container.clock::now, onBack = {}, onOpenTask = {}, onOpenAiSettings = {})
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("plan_request")).fetchSemanticsNodes().isNotEmpty() }
        screenshot("11-plan-input")
        compose.onNodeWithTag("plan_request").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("review_title")).fetchSemanticsNodes().isNotEmpty() }
        screenshot("12-plan-review-top")
        compose.onNodeWithTag("plan_list").performScrollToNode(hasTestTag("review_reminders"))
        screenshot("13-plan-review-reminders")
        compose.onNodeWithTag("plan_list").performScrollToNode(hasTestTag("review_assumptions"))
        screenshot("14-plan-review-assumptions")
    }
}
