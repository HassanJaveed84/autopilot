package com.lifeadmin.autopilot

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.share.applink.ImportLinkParser
import com.lifeadmin.autopilot.share.applink.LinkHandoff
import com.lifeadmin.autopilot.ui.handoff.HandoffImportActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ChatGPT round trip's import route through the real intent resolver and a real task stack:
 * launched with NEW_TASK as a tapped link is, it runs as the root of its own task, reviews, applies
 * through the canonical path and closes that task. The emulator has no verified domain and no
 * ChatGPT, so returning to ChatGPT itself is a phone check.
 */
@RunWith(AndroidJUnit4::class)
class HandoffImportDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as LifeAdminApp).container
    private lateinit var taskId: String

    /** A tapped link as ChatGPT sends it; Android would report ChatGPT as the referrer, so the test says so explicitly. */
    private fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        .putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://com.openai.chatgpt"))
    private fun waitFor(tag: String, text: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Before fun seed() = runBlocking {
        taskId = container.taskRepository.createTask(NewTask("Choose a coffee grinder (device test)", status = TaskStatus.ACTIONABLE))
    }

    @After fun cleanUp() = runBlocking { container.taskRepository.delete(taskId) }

    @Test fun aTappedLinkReviewsAppliesAndClosesItsOwnTask() {
        val link = LinkHandoff("Choose a coffee grinder", listOf("Choose Model A"), listOf("Check warranty coverage"), emptyList(), taskId)
            .toUrl(BuildConfig.APP_LINK_HOST, ImportLinkParser.PATH)
        assertTrue(context.packageManager.queryIntentActivities(view(link), 0).any { it.activityInfo.name == HandoffImportActivity::class.java.name })

        val monitor = instrumentation.addMonitor(HandoffImportActivity::class.java.name, null, false)
        context.startActivity(view(link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 15_000))
        try {
            waitFor("handoff_destination_title", "Choose a coffee grinder (device test)")
            assertTrue("the import screen is the root of its own task", activity.isTaskRoot)
            assertTrue(runBlocking { container.taskRepository.getSteps(taskId) }.isEmpty())

            compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_select_recommended"))
            compose.onNodeWithTag("review_select_recommended").performClick()
            waitFor("review_apply", "Apply 2 changes")
            compose.onNodeWithTag("review_apply").performClick()
            waitFor("review_done", "Return to ChatGPT")
            assertEquals(listOf("Check warranty coverage"), runBlocking { container.taskRepository.getSteps(taskId) }.map { it.title })
            assertEquals("Decided: Choose Model A", runBlocking { container.taskRepository.getTask(taskId) }!!.notes)

            compose.onNodeWithTag("review_done").performClick()
            compose.waitUntil(15_000) { activity.isFinishing || activity.isDestroyed }
        } finally {
            instrumentation.removeMonitor(monitor)
            if (!activity.isFinishing) instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
        }
    }

    /** A second link while the screen is open reaches the same instance (singleTop), and a repeat of the first adds nothing. */
    @Test fun aRepeatedLinkReachesTheOpenScreenAndAddsNothing() {
        fun link(step: String) = LinkHandoff("Choose a coffee grinder", emptyList(), listOf(step), emptyList(), taskId).toUrl(BuildConfig.APP_LINK_HOST, ImportLinkParser.PATH)
        val first = link("Compare retailer prices")
        val second = link("Check warranty coverage")
        val monitor = instrumentation.addMonitor(HandoffImportActivity::class.java.name, null, false)
        context.startActivity(view(first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 15_000))
        try {
            waitFor("handoff_destination_title", "Choose a coffee grinder (device test)")
            context.startActivity(view(second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(15_000) { activity.intent?.dataString == second }
            assertTrue("the open screen took the link, no new instance", !activity.isFinishing && !activity.isDestroyed)
            context.startActivity(view(first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(15_000) { activity.intent?.dataString == first }
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("handoff_repeat"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, runBlocking { container.proposalSets.getForTarget(taskId) }.size)
            assertTrue(runBlocking { container.taskRepository.getSteps(taskId) }.isEmpty())
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
        }
    }
}
