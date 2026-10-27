package com.lifeadmin.autopilot

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeadmin.autopilot.share.applink.LinkHandoff
import com.lifeadmin.autopilot.share.applink.TestLinkParser
import com.lifeadmin.autopilot.ui.applink.AppLinkTestActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The App Link test route through the real intent resolver. The emulator has no verified domain
 * association, so this proves routing and the screen only, not that a tapped link opens the app.
 */
@RunWith(AndroidJUnit4::class)
class AppLinkDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val url = TestLinkParser.testUrl(BuildConfig.APP_LINK_HOST)

    /** A browsable VIEW intent, as a tapped link sends it, narrowed to this package. */
    private fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)

    private fun waitFor(tag: String, text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun theSystemResolvesTheTestLinkToTheTestScreen() {
        assertTrue(context.packageManager.queryIntentActivities(view(url), 0).any { it.activityInfo.name == AppLinkTestActivity::class.java.name })
        ActivityScenario.launch<AppLinkTestActivity>(view(url)).use {
            waitFor("app_link_message", "hello-from-chatgpt")
            waitFor("app_link_no_changes", "No task changes made")
        }
    }

    /** Both launches go through the system with NEW_TASK, as a tapped link does, so singleTop and the empty task affinity are exercised. */
    @Test fun theSameLinkAgainReachesTheOpenScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(AppLinkTestActivity::class.java.name, null, false)
        context.startActivity(view(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
        try {
            waitFor("app_link_delivery", "links received here: 1")
            context.startActivity(view(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("app_link_delivery", "Sent to the already open screen · links received here: 2")
        } finally {
            instrumentation.removeMonitor(monitor)
            activity?.let { instrumentation.runOnMainSync { it.finishAndRemoveTask() } }
        }
    }

    @Test fun theSystemDeliversTheFixedHandoffIntact() {
        val handoff = LinkHandoff.FIXED_EXAMPLE.toUrl(BuildConfig.APP_LINK_HOST)
        assertTrue(context.packageManager.queryIntentActivities(view(handoff), 0).any { it.activityInfo.name == AppLinkTestActivity::class.java.name })
        ActivityScenario.launch<AppLinkTestActivity>(view(handoff)).use {
            waitFor("handoff_task", "Task: Choose a coffee grinder")
            waitFor("handoff_decision_0", "Choose Model A")
            waitFor("handoff_step_0", "1. Check warranty")
            waitFor("handoff_step_1", "2. Compare retailer prices")
            waitFor("handoff_note_0", "Budget is \$1,000")
            waitFor("app_link_validation", "Succeeded")
            waitFor("app_link_fixed_match", "Yes, every field")
        }
    }

    /** Message link cold, then the handoff warm through the system, then Close: the task is removed. */
    @Test fun aHandoffReachesTheOpenScreenAndCloseEndsItsTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(AppLinkTestActivity::class.java.name, null, false)
        context.startActivity(view(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
        try {
            waitFor("app_link_message", "hello-from-chatgpt")
            context.startActivity(view(LinkHandoff.FIXED_EXAMPLE.toUrl(BuildConfig.APP_LINK_HOST)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("handoff_step_1", "Compare retailer prices")
            waitFor("app_link_delivery", "Sent to the already open screen · links received here: 2")
            compose.onNodeWithTag("app_link_close").performScrollTo().performClick()
            val screen = checkNotNull(activity)
            compose.waitUntil(10_000) { screen.isFinishing || screen.isDestroyed }
        } finally {
            instrumentation.removeMonitor(monitor)
            activity?.takeIf { !it.isFinishing }?.let { instrumentation.runOnMainSync { it.finishAndRemoveTask() } }
        }
    }
}
