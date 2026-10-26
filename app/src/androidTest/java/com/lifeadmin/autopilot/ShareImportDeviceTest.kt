package com.lifeadmin.autopilot

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeadmin.autopilot.ui.share.ShareImportActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ChatGPT share target through the real intent resolver (proof of concept). Synthetic links
 * only, and Fetch is never tapped: no request leaves the device.
 */
@RunWith(AndroidJUnit4::class)
class ShareImportDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val first = "https://chatgpt.com/share/0f0e0d0c-0b0a-4909-8807-060504030201"
    private val second = "https://chatgpt.com/share/11111111-2222-4333-8444-555555555555"

    /** An implicit share, as the ChatGPT app sends it, narrowed to this package. */
    private fun share(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(context.packageName)

    private fun waitForUrl(url: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("share_url") and hasText(url)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun theSystemResolvesAPlainTextShareToTheImportScreen() {
        val targets = context.packageManager.queryIntentActivities(share(first), 0)
        assertTrue(targets.any { it.activityInfo.name == ShareImportActivity::class.java.name })
        ActivityScenario.launch<ShareImportActivity>(share("A decision $first?ogimg=plain")).use {
            waitForUrl(first)
            assertTrue(compose.onAllNodes(hasTestTag("share_fetch")).fetchSemanticsNodes().isNotEmpty())
            assertTrue(compose.onAllNodes(hasTestTag("share_fetching")).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun aSecondShareReachesTheOpenScreen() {
        ActivityScenario.launch<ShareImportActivity>(share(first)).use {
            waitForUrl(first)
            context.startActivity(share(second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForUrl(second)
        }
    }

    @Test fun anotherHostIsRefused() {
        ActivityScenario.launch<ShareImportActivity>(share("https://evil.example/share/0f0e0d0c-0b0a-4909-8807-060504030201")).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("share_no_link")).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
