package com.lifeadmin.autopilot.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.LifeAdminApp
import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures
import com.lifeadmin.autopilot.ui.share.ShareImportActivity
import com.lifeadmin.autopilot.ui.share.ShareImportViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Send to Autopilot" from another app's text-selection menu (PROCESS_TEXT spike). Synthetic text only. */
@RunWith(AndroidJUnit4::class)
class ProcessTextActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<LifeAdminApp>()
    private val sample = "Decision: buy Widget A.\nNext: order it by Friday."

    private fun processText(text: CharSequence?, readOnly: Boolean? = true) = Intent(app, ShareImportActivity::class.java)
        .setAction(Intent.ACTION_PROCESS_TEXT).setType("text/plain").apply {
            if (text != null) putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            if (readOnly != null) putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly)
        }

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun has(tag: String, text: String? = null, substring: Boolean = false) =
        compose.onAllNodes(if (text == null) hasTestTag(tag) else hasTestTag(tag) and hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    private fun taskCount() = runBlocking { app.container.taskRepository.observeSummaries().first().size }

    @Test fun theSystemOffersSendToAutopilotForSelectedText() {
        val pm = app.packageManager
        val handlers = pm.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
        val ours = handlers.single { it.activityInfo.packageName == app.packageName }
        assertEquals(ShareImportActivity::class.java.name, ours.activityInfo.name)
        // The "Send to Autopilot" filter label is checked on a real PackageManager (ShareImportDeviceTest):
        // Robolectric's resolver reports the activity label instead of the matched filter's label.
        val send = pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain").addCategory(Intent.CATEGORY_DEFAULT), 0)
            .single { it.activityInfo.packageName == app.packageName }
        assertEquals(ShareImportActivity::class.java.name, send.activityInfo.name)
    }

    @Test fun selectedTextIsShownExactlyWithItsCount() {
        val before = taskCount()
        ActivityScenario.launch<ShareImportActivity>(processText(sample)).use {
            waitForTag("selection_text")
            assertTrue(has("selection_text", sample))
            assertTrue(has("selection_count", "${sample.length} characters", substring = true))
            assertTrue(has("selection_count", "read-only", substring = true))
            // Not the shared-link flow: no link, no Fetch, nothing downloading.
            assertTrue(!has("share_url") && !has("share_fetch") && !has("share_fetching"))
        }
        assertEquals("no task is written", before, taskCount())
    }

    @Test fun theSenderNeverGetsReplacementText() {
        listOf(true, false, null).forEach { readOnly ->
            val scenario = ActivityScenario.launchActivityForResult<ShareImportActivity>(processText(sample, readOnly))
            waitForTag("selection_text")
            compose.onNodeWithTag("share_import").performScrollToNode(hasTestTag("selection_close"))
            compose.onNodeWithTag("selection_close").performClick()
            val result = scenario.result
            assertEquals("$readOnly", Activity.RESULT_CANCELED, result.resultCode)
            assertNull("$readOnly", result.resultData?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
            scenario.close()
        }
    }

    @Test fun anEditableSelectionIsLabelledAsLeftUnchanged() {
        ActivityScenario.launch<ShareImportActivity>(processText(sample, readOnly = false)).use {
            waitForTag("selection_count")
            assertTrue(has("selection_count", "left unchanged", substring = true))
        }
    }

    @Test fun missingOrBlankTextShowsNothingArrived() {
        listOf<CharSequence?>(null, "", "   \n\t").forEach { text ->
            ActivityScenario.launch<ShareImportActivity>(processText(text)).use {
                waitForTag("selection_empty")
                assertTrue(!has("selection_text"))
            }
        }
        // The wrong extra type reads as no text rather than crashing.
        val wrongType = processText(null).putExtra(Intent.EXTRA_PROCESS_TEXT, 42)
        ActivityScenario.launch<ShareImportActivity>(wrongType).use { waitForTag("selection_empty") }
    }

    @Test fun oversizedTextIsCountedAndCapped() {
        val big = "a".repeat(ShareImportViewModel.MAX_SELECTION + 5_000)
        ActivityScenario.launch<ShareImportActivity>(processText(big)).use {
            waitForTag("selection_count")
            assertTrue(has("selection_count", "%,d characters, showing the first %,d".format(big.length, ShareImportViewModel.MAX_SELECTION), substring = true))
        }
    }

    @Test fun styledTextArrivesAsPlainText() {
        val styled = android.text.SpannableString("<b>bold</b> & https://example.com").apply {
            setSpan(android.text.style.URLSpan("https://evil.example"), 0, 4, 0)
        }
        ActivityScenario.launch<ShareImportActivity>(processText(styled)).use {
            waitForTag("selection_text")
            assertTrue(has("selection_text", "<b>bold</b> & https://example.com"))
        }
    }

    @Test fun repeatedAndNewSelectionsAndRecreation() {
        ActivityScenario.launch<ShareImportActivity>(processText(sample)).use { scenario ->
            waitForTag("selection_text")
            scenario.onActivity { it.onNewIntent(processText(sample)) }
            assertTrue(has("selection_text", sample))
            scenario.onActivity { it.onNewIntent(processText("Second selection")) }
            compose.waitUntil(10_000) { has("selection_text", "Second selection") }
            scenario.recreate()
            waitForTag("selection_text")
            assertTrue("recreation keeps the latest selection and does not replay the launch intent", has("selection_text", "Second selection"))
        }
    }

    @Test fun aShareAfterASelectionStillOpensTheLinkFlow() {
        val launch = processText(sample)
        ActivityScenario.launch<ShareImportActivity>(launch).use { scenario ->
            waitForTag("selection_text")
            val send = Intent(app, ShareImportActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, SharePageFixtures.URL)
            scenario.onActivity { it.onNewIntent(send) }
            compose.waitUntil(10_000) { has("share_url", SharePageFixtures.URL) }
            assertTrue(!has("selection_text") && has("share_fetch"))
            // ActivityScenario finds its activity by the launch intent's action; put it back so close() can.
            scenario.onActivity { it.intent = launch }
        }
    }

    @Test fun anUnexpectedActionIsNotTreatedAsSelectedText() {
        val view = Intent(app, ShareImportActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_PROCESS_TEXT, sample)
        ActivityScenario.launch<ShareImportActivity>(view).use {
            waitForTag("share_no_link")
            assertTrue(!has("selection_text"))
        }
    }
}
