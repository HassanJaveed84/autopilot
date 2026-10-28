package com.lifeadmin.autopilot.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.BuildConfig
import com.lifeadmin.autopilot.LifeAdminApp
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.share.applink.ImportLinkParser
import com.lifeadmin.autopilot.share.applink.LinkHandoff
import com.lifeadmin.autopilot.ui.handoff.HandoffImportActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The production import route in Robolectric: link → (task) → the canonical Proposal Review →
 * apply → Return to ChatGPT. Intent resolution here is the manifest filter only; domain verification
 * and ChatGPT itself are phone-only checks.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HandoffImportActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<LifeAdminApp>()
    private val host = BuildConfig.APP_LINK_HOST
    private val tasks get() = app.container.taskRepository
    private lateinit var grinder: String
    private lateinit var kettle: String

    @Before fun seed() = runBlocking {
        grinder = tasks.createTask(NewTask("Choose a coffee grinder", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.DAILY))
        tasks.setStepDone(tasks.addStep(grinder, "Set a budget"), true)
        kettle = tasks.createTask(NewTask("Buy a kettle", status = TaskStatus.ACTIONABLE))
    }

    private fun link(ref: String?, steps: List<String> = listOf("Compare retailer prices", "Check warranty coverage"), title: String = "Choose a coffee grinder") =
        LinkHandoff(title, listOf("Choose Model A"), steps, listOf("Budget is \$1,000"), ref).toUrl(host, ImportLinkParser.PATH)

    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    /** As ChatGPT sends it: Android reports the opening app as the referrer. */
    private fun explicit(url: String, from: String = "com.openai.chatgpt") =
        view(url).setClass(app, HandoffImportActivity::class.java).putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://$from"))

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun has(tag: String, text: String) = compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun hasText(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun scrollReview(tag: String) = compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag(tag))
    private fun stepTitles(id: String) = runBlocking { tasks.getSteps(id).sortedBy { it.sortOrder }.map { it.title } }
    private fun handoffSets(id: String) = runBlocking { app.container.proposalSets.getForTarget(id).filter { it.source == ProposalSource.HANDOFF } }

    @Test fun aMatchingReferenceGoesStraightToReviewThenApplyAndReturn() {
        ActivityScenario.launch<HandoffImportActivity>(explicit(link(grinder))).use { scenario ->
            waitForTag("handoff_destination")
            compose.waitUntil(10_000) { has("handoff_destination_title", "Choose a coffee grinder") }
            assertTrue(has("review_title", "From ChatGPT"))
            assertTrue(has("review_meta", "Your ChatGPT conversation"))
            assertTrue(has("review_select_recommended", "Select all"))
            assertTrue("every row is the user's own, so no Recommended badge", !hasText("Recommended"))
            assertTrue(compose.onAllNodes(hasTestTag("handoff_title_differs")).fetchSemanticsNodes().isEmpty())
            // Review before write: the set is saved, the task isn't touched.
            assertEquals(listOf("Set a budget"), stepTitles(grinder))
            assertEquals(1, handoffSets(grinder).size)

            scrollReview("review_select_recommended")
            compose.onNodeWithTag("review_select_recommended").performClick()
            compose.waitUntil(10_000) { has("review_apply", "Apply 4 changes") }
            compose.onNodeWithTag("review_apply").performClick()
            waitForTag("review_result")
            assertEquals(listOf("Set a budget", "Compare retailer prices", "Check warranty coverage"), stepTitles(grinder))
            assertEquals("Decided: Choose Model A\nBudget is \$1,000", runBlocking { tasks.getTask(grinder)!!.notes })
            assertTrue(has("review_done", "Return to ChatGPT"))
            assertTrue(has("review_undo", "Undo"))
            scrollReview("handoff_open_task")

            var screen: HandoffImportActivity? = null
            scenario.onActivity { screen = it }
            compose.onNodeWithTag("review_done").performClick()
            compose.waitUntil(10_000) { screen!!.isFinishing }
        }
    }

    @Test fun withoutAReferenceThePickerAsksAndATitleMatchIsNotChosen() {
        val sameTitle = runBlocking { tasks.createTask(NewTask("Choose a coffee grinder", status = TaskStatus.ACTIONABLE)) }
        ActivityScenario.launch<HandoffImportActivity>(explicit(link(null))).use {
            waitForTag("handoff_why")
            assertTrue(has("handoff_heading", "Where should this go?"))
            assertTrue(has("handoff_why", "doesn't say which task"))
            assertTrue(has("handoff_summary", "2 steps, 1 decision and 1 note"))
            assertTrue(handoffSets(grinder).isEmpty() && handoffSets(sameTitle).isEmpty())
            waitForTag("handoff_pick_$kettle")
            compose.onNodeWithTag("handoff_list").performScrollToNode(hasTestTag("handoff_pick_$kettle"))
            compose.onNodeWithTag("handoff_pick_$kettle").performClick()
            compose.waitUntil(10_000) { has("handoff_destination_title", "Buy a kettle") }
            assertEquals(1, handoffSets(kettle).size)
            assertTrue(stepTitles(kettle).isEmpty())
        }
    }

    @Test fun aStaleReferenceSaysSoAndOffersANewTask() {
        runBlocking { tasks.delete(grinder) }
        ActivityScenario.launch<HandoffImportActivity>(explicit(link(grinder))).use {
            waitForTag("handoff_why")
            assertTrue(has("handoff_why", "couldn't find that task"))
            compose.onNodeWithTag("handoff_new_task").performClick()
            compose.waitUntil(10_000) { has("handoff_destination_title", "Choose a coffee grinder") }
            val created = runBlocking { tasks.observeSummaries().first() }.single { it.task.title == "Choose a coffee grinder" }.task
            assertEquals(TaskStatus.INBOX, created.status)
            assertEquals(1, handoffSets(created.id).size)
            assertTrue(stepTitles(created.id).isEmpty())
        }
    }

    @Test fun aRefusedLinkExplainsWithoutEchoingIt() {
        ActivityScenario.launch<HandoffImportActivity>(explicit("https://$host/autopilot/import#ap=v1&t=secret-value&s=a+b")).use {
            waitForTag("handoff_refused")
            assertTrue(has("handoff_refused", "didn't come through right"))
            assertTrue(has("handoff_exit", "Return to ChatGPT"))
            assertTrue(!hasText("secret-value"))
        }
        val tooBig = "https://$host/autopilot/import#ap=v1&t=T" + "&s=S".repeat(11)
        ActivityScenario.launch<HandoffImportActivity>(explicit(tooBig)).use {
            waitForTag("handoff_refused")
            assertTrue(has("handoff_refused", "more than one link can carry"))
        }
    }

    /** A second tap of the same link (a fresh launch, as after Return to ChatGPT) reopens the same set. Warm delivery: HandoffImportViewModelTest and the device test. */
    @Test fun aRepeatedLinkReopensTheSameReview() {
        ActivityScenario.launch<HandoffImportActivity>(explicit(link(grinder))).use {
            compose.waitUntil(10_000) { has("handoff_destination_title", "Choose a coffee grinder") }
            assertTrue(compose.onAllNodes(hasTestTag("handoff_repeat")).fetchSemanticsNodes().isEmpty())
        }
        ActivityScenario.launch<HandoffImportActivity>(explicit(link(grinder))).use {
            waitForTag("handoff_repeat")
            assertTrue(has("handoff_destination_title", "Choose a coffee grinder"))
            assertEquals(1, handoffSets(grinder).size)
        }
    }

    /** Rotation and process recreation come back to the same review without reading the launch link again. */
    @Test fun recreationKeepsTheReviewAndItsSelection() {
        ActivityScenario.launch<HandoffImportActivity>(explicit(link(kettle, steps = listOf("Descale it"), title = "Kettle"))).use { scenario ->
            compose.waitUntil(10_000) { has("handoff_destination_title", "Buy a kettle") }
            scrollReview("review_select_recommended")
            compose.onNodeWithTag("review_select_recommended").performClick()
            compose.waitUntil(10_000) { has("review_selected_count", "3 of 3 selected") }
            scenario.recreate()
            compose.waitUntil(10_000) { has("handoff_destination_title", "Buy a kettle") }
            compose.waitUntil(10_000) { has("review_selected_count", "3 of 3 selected") }
            assertTrue(has("handoff_title_differs", "ChatGPT called it “Kettle”"))
            assertEquals(1, handoffSets(kettle).size)
        }
    }

    @Test fun changeLetsTheUserPickAnotherTaskAndBackReturnsToTheReview() {
        ActivityScenario.launch<HandoffImportActivity>(explicit(link(grinder))).use { scenario ->
            compose.waitUntil(10_000) { has("handoff_destination_title", "Choose a coffee grinder") }
            compose.onNodeWithTag("handoff_change").performClick()
            waitForTag("handoff_why")
            assertTrue(has("handoff_why", "Choose the task these changes are for"))
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { has("handoff_destination_title", "Choose a coffee grinder") }

            compose.onNodeWithTag("handoff_change").performClick()
            waitForTag("handoff_pick_$kettle")
            compose.onNodeWithTag("handoff_list").performScrollToNode(hasTestTag("handoff_pick_$kettle"))
            compose.onNodeWithTag("handoff_pick_$kettle").performClick()
            compose.waitUntil(10_000) { has("handoff_destination_title", "Buy a kettle") }
            // The untouched set for the first task is gone, so it leaves no banner behind.
            compose.waitUntil(10_000) { handoffSets(grinder).isEmpty() }
            assertEquals(1, handoffSets(kettle).size)
        }
    }

    @Test fun backAndCloseFinishTheScreenWithoutChangingTheTask() {
        ActivityScenario.launch<HandoffImportActivity>(explicit(link(grinder))).use { scenario ->
            waitForTag("handoff_destination")
            var screen: HandoffImportActivity? = null
            scenario.onActivity { screen = it; it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { screen!!.isFinishing }
        }
        ActivityScenario.launch<HandoffImportActivity>(explicit(link(grinder))).use { scenario ->
            waitForTag("handoff_destination")
            var screen: HandoffImportActivity? = null
            scenario.onActivity { screen = it }
            compose.onNode(androidx.compose.ui.test.hasContentDescription("Return to ChatGPT")).performClick()
            compose.waitUntil(10_000) { screen!!.isFinishing }
        }
        assertEquals(listOf("Set a budget"), stepTitles(grinder))
        // The pending changes stay reviewable from the task.
        assertEquals(1, handoffSets(grinder).size)
    }

    @Test fun nothingNewSaysSoAndALaunchWithoutALinkToo() {
        ActivityScenario.launch<HandoffImportActivity>(explicit(link(grinder, steps = listOf("Set a budget")).replace("&d=Choose%20Model%20A", "").replace("&n=Budget%20is%20%241%2C000", ""))).use {
            waitForTag("handoff_nothing_new")
            assertTrue(handoffSets(grinder).isEmpty())
        }
        ActivityScenario.launch<HandoffImportActivity>(Intent(app, HandoffImportActivity::class.java)).use { waitForTag("handoff_empty") }
    }

    @Test fun onlyTheImportRouteResolvesAndTheRetiredTestRouteNoLongerOpensTheApp() {
        fun handlers(url: String) = app.packageManager.queryIntentActivities(view(url), 0).map { it.activityInfo }.filter { it.packageName == app.packageName }.map(ActivityInfo::name)
        assertEquals(listOf(HandoffImportActivity::class.java.name), handlers(link(grinder)))
        listOf(
            "https://$host/autopilot/test#message=hello-from-chatgpt", "https://$host/autopilot/test#ap=v1&t=T&s=S",
            "http://$host/autopilot/import#ap=v1&t=T&s=S", "https://$host/autopilot/import/x", "https://evil.example/autopilot/import#ap=v1&t=T&s=S",
        )
            .forEach { assertTrue(it, handlers(it).isEmpty()) }
    }

    @Test fun theExitNamesChatGptUnlessAnotherAppOpenedTheLink() {
        ActivityScenario.launch<HandoffImportActivity>(explicit("https://$host/autopilot/import#x", from = "com.android.chrome")).use {
            waitForTag("handoff_refused")
            assertTrue(has("handoff_exit", "Close"))
        }
        assertEquals("Return to ChatGPT", HandoffImportActivity.exitLabel("com.openai.chatgpt"))
        assertEquals("Return to ChatGPT", HandoffImportActivity.exitLabel(null))
        assertEquals("Close", HandoffImportActivity.exitLabel("com.android.chrome"))
    }
}
