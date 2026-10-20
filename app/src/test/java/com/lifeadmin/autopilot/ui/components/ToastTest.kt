package com.lifeadmin.autopilot.ui.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Toasts with an action used to stay up forever (Material 3's default duration with an action
 * label is Indefinite) and queued every later message behind them. Now every toast lasts
 * [TOAST_MILLIS], can be swiped away, and the next one follows.
 */
@RunWith(AndroidJUnit4::class)
class ToastTest {
    @get:Rule val compose = createComposeRule()

    private fun host(block: suspend (SnackbarHostState) -> Unit) {
        val state = SnackbarHostState()
        compose.setContent {
            LifeAdminTheme {
                SnackbarHost(state) { AutopilotSnackbar(it) }
                LaunchedEffect(Unit) { block(state) }
            }
        }
    }

    @Test fun toastWithActionDisappearsAfterFiveSeconds() {
        var result: SnackbarResult? = null
        host { result = it.showToast("Saved to Inbox.", actionLabel = "Open") }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Saved to Inbox.").assertIsDisplayed()
        compose.onNodeWithText("Open").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(TOAST_MILLIS - 500)
        compose.onNodeWithText("Saved to Inbox.").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Saved to Inbox.").assertDoesNotExist()
        assertEquals(SnackbarResult.Dismissed, result)
    }

    @Test fun plainToastDisappearsAndNextOneFollows() {
        val shown = mutableListOf<SnackbarResult>()
        host {
            shown += it.showToast("Deleted.")
            shown += it.showToast("Undone.")
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Deleted.").assertIsDisplayed()
        compose.onNodeWithText("Undone.").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(TOAST_MILLIS + 1_500)
        compose.onNodeWithText("Deleted.").assertDoesNotExist()
        compose.onNodeWithText("Undone.").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(TOAST_MILLIS + 1_500)
        compose.onNodeWithText("Undone.").assertDoesNotExist()
        assertEquals(listOf(SnackbarResult.Dismissed, SnackbarResult.Dismissed), shown)
    }

    @Test fun tappingTheActionReportsItAndCloses() {
        var result: SnackbarResult? = null
        host { result = it.showToast("Done. Nudges stopped.", actionLabel = "Undo") }
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Done. Nudges stopped.").assertDoesNotExist()
        assertEquals(SnackbarResult.ActionPerformed, result)
    }

    @Test fun swipingDismissesTheToast() {
        var result: SnackbarResult? = null
        host { result = it.showToast("Saved to Inbox.", actionLabel = "Open") }
        compose.onNodeWithText("Saved to Inbox.").assertIsDisplayed()
        compose.onNodeWithTag("toast").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Saved to Inbox.").assertDoesNotExist()
        assertEquals(SnackbarResult.Dismissed, result)
    }
}
