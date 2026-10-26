package com.lifeadmin.autopilot.ui

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.LifeAdminApp
import com.lifeadmin.autopilot.MainActivity
import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures
import com.lifeadmin.autopilot.ui.share.ShareImportActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The share target end to end in Robolectric, without the network: these tests never tap Fetch,
 * which is the point (a share must not download anything by itself).
 */
@RunWith(AndroidJUnit4::class)
class ShareImportActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<LifeAdminApp>()
    private val url = SharePageFixtures.URL
    private val other = "https://chatgpt.com/share/11111111-2222-4333-8444-555555555555"

    private fun share(text: String?) = Intent(app, ShareImportActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").apply { if (text != null) putExtra(Intent.EXTRA_TEXT, text) }

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun hasNode(tag: String, text: String? = null) =
        compose.onAllNodes(if (text == null) hasTestTag(tag) else hasTestTag(tag) and hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun aSharedChatGptLinkOpensAPreviewWithoutFetching() {
        ActivityScenario.launch<ShareImportActivity>(share("A decision\n$url?ogimg=plain")).use {
            waitForTag("share_url")
            assertTrue(hasNode("share_url", url))
            assertTrue(hasNode("share_privacy"))
            assertTrue(hasNode("share_fetch"))
            assertTrue("nothing is downloaded until Fetch", !hasNode("share_fetching"))
        }
    }

    @Test fun anotherHostIsRefused() {
        ActivityScenario.launch<ShareImportActivity>(share("https://evil.example/share/${SharePageFixtures.ID}")).use {
            waitForTag("share_no_link")
            assertTrue(compose.onAllNodes(hasText("isn't a ChatGPT shared link", substring = true)).fetchSemanticsNodes().isNotEmpty())
            assertTrue(!hasNode("share_fetch"))
        }
    }

    @Test fun aLaunchWithoutSharedTextShowsNothingToImport() {
        ActivityScenario.launch<ShareImportActivity>(Intent(app, ShareImportActivity::class.java)).use {
            waitForTag("share_no_link")
            assertTrue(compose.onAllNodes(hasText("Nothing to import", substring = true)).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test fun aNewShareWhileOpenReplacesThePreview() {
        ActivityScenario.launch<ShareImportActivity>(share(url)).use { scenario ->
            waitForTag("share_url")
            scenario.onActivity { it.onNewIntent(share(other)) }
            compose.waitUntil(10_000) { hasNode("share_url", other) }
            assertTrue(!hasNode("share_url", url))
        }
    }

    @Test fun rotationKeepsThePreviewAndDoesNotReplayTheIntent() {
        ActivityScenario.launch<ShareImportActivity>(share(url)).use { scenario ->
            waitForTag("share_url")
            // A newer share arrives, then the activity is recreated with the original launch intent still attached.
            scenario.onActivity { it.onNewIntent(share(other)) }
            compose.waitUntil(10_000) { hasNode("share_url", other) }
            scenario.recreate()
            waitForTag("share_url")
            assertTrue(hasNode("share_url", other))
            assertTrue(!hasNode("share_fetching"))
        }
    }

    @Test fun theShareTargetIsRegisteredForPlainTextOnly() {
        val pm = app.packageManager
        val plain = pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain").addCategory(Intent.CATEGORY_DEFAULT), 0)
        assertTrue(plain.any { it.activityInfo.name == ShareImportActivity::class.java.name })
        val image = pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("image/png").addCategory(Intent.CATEGORY_DEFAULT), 0)
        assertTrue(image.none { it.activityInfo.packageName == app.packageName })
    }

    @Test fun ordinaryLaunchesStillOpenToday() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("today_list")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(!hasNode("share_import"))
        }
        val launcher = app.packageManager.getLaunchIntentForPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, launcher?.component?.className)
    }
}
