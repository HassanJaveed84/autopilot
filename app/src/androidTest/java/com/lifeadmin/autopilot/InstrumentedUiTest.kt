package com.lifeadmin.autopilot

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    /**
     * Typing focuses the field and the keyboard then opens on its own schedule, which Compose's
     * idling does not track. A sheet's pinned footer moves up with it (imePadding), so a tap aimed
     * before the keyboard settles can land where the button was. Waits until the button sits above
     * the open keyboard, as the person sees it; a button left behind the keyboard never passes.
     */
    private fun waitAboveKeyboard(tag: String) = compose.waitUntil(10_000) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        compose.runOnUiThread {
            val view = (node.root as ViewRootForTest).view
            val insets = ViewCompat.getRootWindowInsets(view)
            insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                node.boundsInWindow.bottom <= view.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
    }

    @Test fun captureThenMarkWaitingWithTypedReason() {
        val title = "Repair car ${System.nanoTime() % 100000}"
        compose.onNodeWithTag("fab_capture").performClick()
        compose.onNodeWithTag("capture_title").performTextInput(title)
        waitAboveKeyboard("capture_save")
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
        waitAboveKeyboard("waiting_save")
        compose.onNodeWithTag("waiting_save").performClick()
        compose.waitUntil(10_000) {
            runBlocking { container.database.taskDao().getAll().firstOrNull { it.title == title }?.status == TaskStatus.WAITING }
        }
        val t = runBlocking { container.database.taskDao().getAll().first { it.title == title } }
        assertEquals("Mechanic to call back", t.waitingReason)
        runBlocking { container.taskRepository.delete(t.id) }
    }
}
