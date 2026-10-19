package com.lifeadmin.autopilot

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeadmin.autopilot.domain.model.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Flows that need a real device input stack (typing inside dialogs). */
@RunWith(AndroidJUnit4::class)
class InstrumentedUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.appContainer

    @Before fun dismissOnboarding() = runBlocking { container.settingsRepository.update { it.copy(onboardingCompleted = true, notificationPermissionRequested = true) } }

    private fun waitForText(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun captureThenMarkWaitingWithTypedReason() {
        val title = "Repair car ${System.nanoTime() % 100000}"
        compose.onNodeWithTag("fab_capture").performClick()
        compose.onNodeWithTag("capture_title").performTextInput(title)
        compose.onNodeWithTag("capture_nudge_switch").performClick() // off = Save to Inbox
        compose.onNodeWithTag("capture_save").performClick()
        compose.onNodeWithTag("nav_inbox").performClick()
        waitForText(title)
        // During the tab crossfade the title is also on Today's inbox prompt; pick the Inbox card.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("inbox_list")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("inbox_list"))).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("action_waiting")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("action_waiting").performClick()
        compose.onNodeWithTag("waiting_reason").performTextInput("Mechanic to call back")
        compose.onNodeWithTag("waiting_save").performClick()
        compose.waitUntil(10_000) {
            runBlocking { container.database.taskDao().getAll().firstOrNull { it.title == title }?.status == TaskStatus.WAITING }
        }
        val t = runBlocking { container.database.taskDao().getAll().first { it.title == title } }
        assertEquals("Mechanic to call back", t.waitingReason)
        runBlocking { container.taskRepository.delete(t.id) }
    }
}
