package com.lifeadmin.autopilot.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.BuildConfig
import com.lifeadmin.autopilot.LifeAdminApp
import com.lifeadmin.autopilot.MainActivity
import com.lifeadmin.autopilot.share.applink.TestLinkParser
import com.lifeadmin.autopilot.ui.applink.AppLinkTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The App Link test route in Robolectric. Intent resolution here is the manifest filter only; it says
 * nothing about domain verification, which only a phone with the published assetlinks.json can show.
 */
@RunWith(AndroidJUnit4::class)
class AppLinkTestActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<LifeAdminApp>()
    private val host = BuildConfig.APP_LINK_HOST
    private val url = TestLinkParser.testUrl(host)

    private fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
    private fun explicit(link: String) = view(link).setClass(app, AppLinkTestActivity::class.java)

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun has(tag: String, text: String) = compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theTestLinkShowsTheFixedMessage() {
        ActivityScenario.launch<AppLinkTestActivity>(explicit(url)).use {
            waitForTag("app_link_message")
            assertTrue(compose.onAllNodes(hasText("Link received successfully")).fetchSemanticsNodes().isNotEmpty())
            assertTrue(has("app_link_message", "hello-from-chatgpt"))
            assertTrue(has("app_link_source", "Android App Link"))
            assertTrue(has("app_link_no_changes", "No task changes made"))
            assertTrue(has("app_link_delivery", "Opened this screen · links received here: 1"))
        }
    }

    @Test fun aRepeatedLinkReachesTheOpenScreenAndRecreationDoesNotReplayIt() {
        ActivityScenario.launch<AppLinkTestActivity>(explicit(url)).use { scenario ->
            waitForTag("app_link_message")
            scenario.onActivity { it.onNewIntent(explicit(url)) }
            compose.waitUntil(10_000) { has("app_link_delivery", "Sent to the already open screen · links received here: 2") }
            // Rotation keeps the view model; the launch intent still attached to the activity is not read again.
            scenario.recreate()
            waitForTag("app_link_message")
            assertTrue(has("app_link_delivery", "links received here: 2"))
            assertTrue(has("app_link_message", "hello-from-chatgpt"))
        }
    }

    @Test fun anUnexpectedLinkIsRefusedWithoutEchoingIt() {
        ActivityScenario.launch<AppLinkTestActivity>(explicit("https://evil.example/autopilot/test#message=secret-value")).use {
            waitForTag("app_link_rejected")
            assertTrue(has("app_link_rejected", "isn't from $host"))
            assertTrue(compose.onAllNodes(hasText("secret-value", substring = true)).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun aLaunchWithoutALinkSaysSo() {
        ActivityScenario.launch<AppLinkTestActivity>(Intent(app, AppLinkTestActivity::class.java)).use { waitForTag("app_link_none") }
    }

    @Test fun onlyTheTestRouteResolvesToTheApp() {
        val pm = app.packageManager
        fun handlers(link: String) = pm.queryIntentActivities(view(link), 0).map { it.activityInfo }.filter { it.packageName == app.packageName }
        assertEquals(listOf(AppLinkTestActivity::class.java.name), handlers(url).map(ActivityInfo::name))
        listOf(
            "http://$host/autopilot/test#message=hello",
            "https://$host/autopilot/test/extra",
            "https://$host/autopilot",
            "https://$host/",
            "https://evil.example/autopilot/test#message=hello",
            "https://sub.$host/autopilot/test",
        ).forEach { assertTrue(it, handlers(it).isEmpty()) }
    }

    @Test fun ordinaryLaunchesStillOpenToday() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("today_list")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onAllNodes(hasTestTag("app_link_test")).fetchSemanticsNodes().isEmpty())
        }
        assertEquals(MainActivity::class.java.name, app.packageManager.getLaunchIntentForPackage(app.packageName)?.component?.className)
    }
}
