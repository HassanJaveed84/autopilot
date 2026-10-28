package com.lifeadmin.autopilot.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.TargetKind
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.ui.theme.LifeAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Rich Steps on the real shell (hybrid A+B): the checklist stays compact, the main link is one tap,
 * the title opens the step's sheet, the box only ticks, links open the browser without touching the
 * step, the editor keeps everything else about a step, and ChatGPT's rich steps are reviewed and
 * edited as units. Also renders light, dark and large-text screens to build/rich-steps-screens.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RichStepsUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 28, 9, 0))
    private lateinit var taskId: String
    private lateinit var plain: String
    private lateinit var rich: String
    private lateinit var done: String
    private val product = StepLink("https://www.chanel.com/us/fragrance/p/107460/bleu-de-chanel-eau-de-parfum-spray/", "Product page")
    private val nordstrom = StepLink("https://www.nordstrom.com/s/bleu-de-chanel-edp/1", "Compare price")

    private fun launch(dark: Boolean = false, fontScale: Float? = null, withReview: Boolean = false) {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = clock, zoneProvider = FakeZoneProvider(CHICAGO), ids = SequentialIds("ui"),
            database = inMemoryDatabase(), settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)),
        )
        runBlocking {
            val repo = container.taskRepository
            taskId = repo.createTask(NewTask("Thank-you gift for Omar", status = TaskStatus.ACTIONABLE))
            done = repo.addStep(taskId, "Ask Leila which scent Omar wears")
            repo.updateStep(done, "Ask Leila which scent Omar wears", "She said the blue one.", listOf(StepLink("https://www.chanel.com/us/fragrance/"), nordstrom))
            repo.setStepDone(done, true)
            rich = repo.addStep(taskId, "Buy Bleu de Chanel EDP 50 mL")
            repo.updateStep(rich, "Buy Bleu de Chanel EDP 50 mL", "Estimated price: about \$135 before tax.\nChoose the complimentary gift wrap.", listOf(product, nordstrom))
            plain = repo.addStep(taskId, "Call dentist")
            if (withReview) {
                val snap = container.proposalApplier.snapshot(taskId)!!
                container.proposalSets.save(
                    ProposalSet(
                        "set1", ProposalSource.HANDOFF, TargetKind.TASK, taskId, madeAtMs = clock.now().toEpochMilli(), baseOriginal = snap, base = snap, title = "From ChatGPT",
                        items = listOf(
                            ProposalItem(
                                "a", ItemKind.ADD_STEP, recommended = true,
                                value = ItemValue.Step(
                                    "Book dinner for Nov 14", ContentOrigin.USER, description = "Table for 4 at 7:30 pm.\nOmar likes Italian.",
                                    links = listOf(StepLink("https://resy.com/cities/new-york-ny/venues/carbone", "Reserve on Resy"), StepLink("https://www.opentable.com/r/carbone-new-york", "OpenTable")),
                                ),
                            ),
                            ProposalItem("d", ItemKind.ADD_STEP_DETAILS, recommended = true, value = ItemValue.StepDetails(stepId = plain, stepTitle = "Call dentist", description = "Ask about X-rays.")),
                        ),
                    ),
                )
            }
        }
        compose.setContent {
            LifeAdminTheme(darkTheme = dark, dynamicColor = false) {
                if (fontScale == null) LifeAdminRoot(container)
                else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { LifeAdminRoot(container) }
            }
        }
        waitFor("nav_tasks")
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Thank-you gift", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Thank-you gift", substring = true))[0].performClick()
        waitFor("detail_title")
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("step_row_$plain"))
    }

    @After fun tearDown() { runCatching { container.database.close() } }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    private fun text(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
    private fun step(id: String) = runBlocking { container.taskRepository.getSteps(taskId).first { it.id == id } }
    private fun save(name: String) {
        val dir = File("build/rich-steps-screens").apply { mkdirs() }
        runCatching {
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { System.err.println("RICH_STEPS_SCREEN $name not captured: $it") }
    }

    @Test fun theChecklistStaysCompactWithTheMainLinkOnTheRow() {
        launch()
        // A plain step looks as before: no pill, no hint.
        assertTrue(gone("step_main_link_$plain"))
        assertTrue(gone("step_hint_$plain"))
        // A rich step shows its main link with the domain, and a quiet hint for the rest.
        waitFor("step_main_link_$rich")
        assertEquals("Description · 1 more link", text("step_hint_$rich"))
        // A completed step keeps its line-through, drops the pill and is not folded away.
        assertTrue(gone("step_main_link_$done"))
        assertEquals("Description · 2 links", text("step_hint_$done"))
        compose.onNodeWithTag("step_row_$done").assertIsDisplayed()
        save("checklist-light")
    }

    @Test fun theBoxOnlyTicksTheTitleOnlyOpensAndTheLinkOnlyOpensTheBrowser() {
        launch()
        // The box ticks and opens nothing.
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Mark done: Call dentist")).performClick()
        compose.waitUntil(5_000) { step(plain).isDone }
        assertTrue(gone("step_sheet"))
        // The link opens the browser on its address and leaves the step alone.
        compose.onNodeWithTag("step_main_link_$rich").performScrollTo().performClick()
        val started = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(product.url, started.dataString)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertFalse("opening a link never ticks the step", step(rich).isDone)
        assertTrue(gone("step_sheet"))
        // The title opens the sheet with the description, the main link and the other links.
        compose.onNodeWithTag("step_open_$rich").performClick()
        waitFor("step_sheet_main_link")
        compose.onNodeWithTag("step_sheet_description", useUnmergedTree = true).assertIsDisplayed()
        waitFor("step_sheet_links")
        save("step-sheet-light")
        compose.onNodeWithTag("step_sheet_done").performClick()
        compose.waitUntil(5_000) { step(rich).isDone }
        assertEquals("the sheet keeps the details", product, step(rich).links.first())
    }

    @Test fun theEditorChangesDetailsAndKeepsDoneStateAndOrder() {
        launch()
        compose.onNodeWithTag("step_open_$done").performClick()
        waitFor("step_sheet_edit")
        compose.onNodeWithTag("step_sheet_edit").performClick()
        waitFor("step_editor_description")
        compose.onNodeWithTag("step_editor_description").performTextClearance()
        compose.onNodeWithTag("step_editor_description").performTextInput("She said the blue bottle, 50 mL.")
        // A bad address is refused with a reason, and nothing is saved.
        compose.onNodeWithTag("step_editor_add_link").performScrollTo().performClick()
        compose.onNodeWithTag("step_editor_link_url_2").performScrollTo().performTextInput("javascript:alert(1)")
        compose.onNodeWithTag("step_editor_save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("starts with https", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("She said the blue one.", step(done).description)
        save("step-editor-error")
        // Fix it: an address without https is accepted as https, tracking removed, the variant kept.
        compose.onNodeWithTag("step_editor_link_url_2").performTextClearance()
        compose.onNodeWithTag("step_editor_link_url_2").performTextInput("www.sephora.com/product/bleu?skuId=123&utm_source=chatgpt.com")
        compose.onNodeWithTag("step_editor_link_label_2").performTextInput("Sephora")
        compose.onNodeWithTag("step_editor_save").performClick()
        compose.waitUntil(5_000) { step(done).links.size == 3 }
        val s = step(done)
        assertEquals("She said the blue bottle, 50 mL.", s.description)
        assertEquals(StepLink("https://www.sephora.com/product/bleu?skuId=123", "Sephora"), s.links.last())
        assertTrue("still done", s.isDone)
        assertEquals("Ask Leila which scent Omar wears", s.title)
        assertEquals(0, s.sortOrder)
    }

    @Test fun manualStepCreationIsStillOneField() {
        launch()
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("step_input"))
        compose.onNodeWithTag("step_input").performTextInput("Wrap the gift")
        compose.onNodeWithTag("step_add").performClick()
        compose.waitUntil(5_000) { runBlocking { container.taskRepository.getSteps(taskId) }.any { it.title == "Wrap the gift" } }
        val added = runBlocking { container.taskRepository.getSteps(taskId) }.first { it.title == "Wrap the gift" }
        assertNull(added.description)
        assertTrue(added.links.isEmpty())
    }

    @Test fun darkThemeRendersTheSameControls() {
        launch(dark = true)
        waitFor("step_main_link_$rich")
        save("checklist-dark")
        compose.onNodeWithTag("step_open_$rich").performClick()
        waitFor("step_sheet_main_link")
        save("step-sheet-dark")
    }

    @Test fun largeTextKeepsEveryTargetReachable() {
        launch(fontScale = 1.6f)
        waitFor("step_main_link_$rich")
        val pill = compose.onNodeWithTag("step_main_link_$rich").fetchSemanticsNode()
        val h = with(pill.layoutInfo.density) { pill.touchBoundsInRoot.height.toDp() }
        assertTrue("pill touch target $h", h >= 44.dp - 0.5.dp)
        save("checklist-large-text")
        compose.onNodeWithTag("step_open_$rich").performClick()
        waitFor("step_sheet_main_link")
        save("step-sheet-large-text")
    }

    @Test fun aRichStepFromChatGptIsReviewedEditedAndAppliedAsOneUnit() {
        launch(withReview = true)
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("detail_review_banner_set1"))
        compose.onNodeWithTag("detail_review_banner_set1").performClick()
        waitFor("review_list")
        // The list exists before the set has loaded (one placeholder row); wait for the loaded review.
        waitFor("review_apply")
        waitFor("review_item_a")
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_links_a"))
        compose.onNodeWithTag("review_description_a", useUnmergedTree = true).assertIsDisplayed()
        save("review-rich-step")
        // Edit: drop the second link before applying.
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_step_edit_a"))
        compose.onNodeWithTag("review_step_edit_a").performClick()
        waitFor("step_editor_link_remove_1")
        compose.onNodeWithTag("step_editor_link_remove_1").performScrollTo().performClick()
        compose.onNodeWithTag("step_editor_save").performClick()
        compose.waitUntil(5_000) { gone("step_editor") }
        // The details for the user's own step keep its wording; the title can't be edited there.
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_step_edit_d"))
        compose.onNodeWithTag("review_step_edit_d").performClick()
        waitFor("step_editor_title_locked")
        assertTrue(gone("step_editor_title"))
        compose.onNodeWithTag("step_editor_cancel").performClick()
        compose.waitUntil(5_000) { gone("step_editor") }
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_select_recommended"))
        compose.onNodeWithTag("review_select_recommended").performClick()
        compose.onNodeWithTag("review_apply").performClick()
        waitFor("review_result")
        val steps = runBlocking { container.taskRepository.getSteps(taskId) }
        val dinner = steps.first { it.title == "Book dinner for Nov 14" }
        assertEquals("Table for 4 at 7:30 pm.\nOmar likes Italian.", dinner.description)
        assertEquals(listOf("https://resy.com/cities/new-york-ny/venues/carbone"), dinner.links.map { it.url })
        val dentist = steps.first { it.id == plain }
        assertEquals("Call dentist", dentist.title)
        assertEquals("Ask about X-rays.", dentist.description)
    }
}

/** An ambiguous match on screen: the user picks the step; nothing is guessed. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RichStepsAmbiguousUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theUserChoosesWhichStepGetsTheDetails() {
        val clock = FakeClock(at(CHICAGO, 2026, 10, 28, 9, 0))
        val container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = clock, zoneProvider = FakeZoneProvider(CHICAGO), ids = SequentialIds("amb"),
            database = inMemoryDatabase(), settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)),
        )
        lateinit var a: String
        lateinit var b: String
        val taskId = runBlocking {
            val id = container.taskRepository.createTask(NewTask("Renew passport", status = TaskStatus.ACTIONABLE))
            a = container.taskRepository.addStep(id, "Call the office")
            b = container.taskRepository.addStep(id, "call the office!")
            val snap = container.proposalApplier.snapshot(id)!!
            container.proposalSets.save(
                ProposalSet(
                    "set1", ProposalSource.HANDOFF, TargetKind.TASK, id, madeAtMs = 0, baseOriginal = snap, base = snap, title = "From ChatGPT",
                    items = listOf(ProposalItem("d", ItemKind.ADD_STEP_DETAILS, recommended = true, value = ItemValue.StepDetails(null, listOf(a, b), "Call the office", "Ask for the passport desk."))),
                ),
            )
            id
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("nav_tasks"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Renew passport", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Renew passport", substring = true))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("detail_review_banner_set1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("detail_review_banner_set1"))
        compose.onNodeWithTag("detail_review_banner_set1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("review_choose_d_1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // "Select all" can't pick it while the step is undecided.
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_select_recommended"))
        compose.onNodeWithTag("review_select_recommended").performClick()
        compose.onNodeWithTag("review_selected_count", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.joinToString { it.text }
            .let { assertTrue(it, it.startsWith("0 of 1")) }
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_choose_d_1"))
        compose.onNodeWithTag("review_choose_d_1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Apply 1 change"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review_apply").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("review_result"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val steps = runBlocking { container.taskRepository.getSteps(taskId) }.associateBy { it.id }
        assertNull(steps.getValue(a).description)
        assertEquals("Ask for the passport desk.", steps.getValue(b).description)
        runCatching { container.database.close() }
    }
}
