package com.lifeadmin.autopilot.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.proposal.FindingRef
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemSrc
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.Premise
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.SetStatus
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The canonical Proposal Review on the real shell (handoff §7, §14 item 7; Document F Task-safety
 * gate): task-detail banner → review; nothing selected; Select recommended; material "Accept";
 * inline edit; conflict after the user ticks a step mid-review; Keep yours; Apply writes only the
 * chosen items; provenance marker and sheet on the task; undo; user-stated deadline never changed.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProposalReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 23, 9, 0))
    private lateinit var taskId: String
    private lateinit var setId: String

    private fun launch(withDeadline: Boolean = true, source: ProposalSource = ProposalSource.RESEARCH) {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = clock, zoneProvider = FakeZoneProvider(CHICAGO), ids = SequentialIds("ui"),
            database = inMemoryDatabase(), settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)), networkStatus = { true },
        )
        runBlocking {
            taskId = container.taskRepository.createTask(NewTask("Renew passport", notes = "Expires in March", status = TaskStatus.ACTIONABLE))
            container.taskRepository.addStep(taskId, "Find old passport")
            container.taskRepository.addStep(taskId, "Take photo")
            if (withDeadline) container.taskRepository.setDeadline(taskId, DeadlineType.DATE_ONLY, LocalDate.of(2026, 11, 20), null)
            val photo = container.taskRepository.getSteps(taskId).first { it.title == "Take photo" }
            val snap = container.proposalApplier.snapshot(taskId)!!
            setId = "set1"
            container.proposalSets.save(
                ProposalSet(
                    setId, source, TargetKind.TASK, taskId, investigationId = if (source == ProposalSource.RESEARCH) "inv1" else null, turnId = "run1",
                    madeAtMs = clock.now().toEpochMilli(), model = "claude-sonnet-5-5", baseOriginal = snap, base = snap, title = "From research: passport renewal",
                    items = listOf(
                        ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book an appointment", ContentOrigin.RESEARCH), why = "Appointments are required since 2025", recommended = true,
                            src = ItemSrc(listOf(Premise("Appointments are required", listOf("s1"))), listOf(FindingRef("s1", "run1", "f1", "Appointments required since 2025", "established", "2026-10-20", listOf("https://gov.example/passports"))), "established", "2026-10-20")),
                        ProposalItem("e", ItemKind.EDIT_STEP, anchorStepId = photo.id, value = ItemValue.Step("Take a passport photo (35×45 mm)", ContentOrigin.RESEARCH), why = "Size rule from the official page", recommended = true),
                        ProposalItem("n", ItemKind.APPEND_NOTE, value = ItemValue.Text("Processing takes about 3 weeks (checked 20 Oct 2026)"), recommended = true),
                        ProposalItem("t", ItemKind.SET_TARGET, value = ItemValue.Date("2026-10-30", basis = "3-week processing time, verified"), why = "Your deadline stays as it is", material = true),
                        ProposalItem("dl", ItemKind.SET_DEADLINE, value = ItemValue.Date("2026-10-30", basis = "processing time"), material = true),
                        ProposalItem("r", ItemKind.SET_REMINDER, value = ItemValue.Reminder("DAILY_TIMES", listOf("09:00"), basis = "suggested by the planner"), material = true, recommended = true),
                    ),
                ),
            )
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        waitFor("nav_tasks")
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Renew passport", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Renew passport", substring = true))[0].performClick()
        waitFor("detail_title")
    }

    @After fun tearDown() { runCatching { container.database.close() } }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollReview(tag: String) = compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag(tag))
    private fun stateOf(tag: String): String? { scrollReview(tag); return compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) }

    private fun openReview() {
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("detail_review_banner_$setId"))
        compose.onNodeWithTag("detail_review_banner_$setId").assertIsDisplayed().performClick()
        waitFor("review_list")
        waitFor("review_apply")
    }

    @Test fun nothingSelectedSelectRecommendedMaterialAcceptEditApplyProvenanceAndUndo() {
        launch()
        openReview()
        compose.onNodeWithTag("review_selected_count").assertTextContainsSafely("0 of 6 selected")
        compose.onNodeWithTag("review_apply").assertIsNotEnabled()
        // Current vs proposed on an edit; the user-stated deadline is blocked outright, never a change.
        scrollReview("review_current_e")
        compose.onNodeWithTag("review_current_e", useUnmergedTree = true).assertTextContainsSafely("Now: Take photo")
        compose.onNodeWithTag("review_proposed_e", useUnmergedTree = true).assertTextContainsSafely("Take a passport photo")
        scrollReview("review_item_dl")
        compose.onNodeWithTag("review_check_note_dl", useUnmergedTree = true).assertTextContainsSafely("Your deadline is kept")
        compose.onAllNodes(hasTestTag("review_accept_dl")).fetchSemanticsNodes().isEmpty().let { assertTrue("a blocked item has no accept control", it) }
        // Select recommended: the three plain recommended items, never the material reminder.
        scrollReview("review_select_recommended")
        compose.onNodeWithTag("review_select_recommended").performClick()
        compose.onNodeWithTag("review_selected_count").assertTextContainsSafely("3 of 6 selected")
        assertEquals("Not accepted", stateOf("review_accept_r"))
        assertEquals("Not accepted", stateOf("review_accept_t"))
        // The planning target needs its own visible acceptance.
        compose.onNodeWithTag("review_accept_t").performClick()
        compose.waitUntil(5_000) { stateOf("review_accept_t") == "Accepted" }
        compose.onNode(hasText("Apply 4 changes")).assertIsDisplayed()
        // Edit before accepting; editing never changes the selection.
        scrollReview("review_edit_toggle_a")
        compose.onNodeWithTag("review_edit_toggle_a").performClick()
        compose.onNodeWithTag("review_edit_a").performTextClearance()
        compose.onNodeWithTag("review_edit_a").performTextInput("Book an appointment at the consulate")
        compose.onNode(hasText("Apply 4 changes")).assertIsDisplayed()
        // Explanations stay collapsed until asked.
        scrollReview("review_why_a")
        compose.onNodeWithTag("review_why_a").performClick()
        compose.onNodeWithTag("review_why_text_a", useUnmergedTree = true).assertTextContainsSafely("Appointments are required since 2025")

        compose.onNodeWithTag("review_apply").assertIsEnabled().performClick()
        waitFor("review_result")
        compose.onNodeWithTag("review_result_title").assertTextContainsSafely("Applied 4 changes")
        val task = runBlocking { container.taskRepository.getTask(taskId) }!!
        val steps = runBlocking { container.taskRepository.getSteps(taskId) }
        assertEquals(listOf("Find old passport", "Take a passport photo (35×45 mm)", "Book an appointment at the consulate"), steps.map { it.title })
        assertTrue(task.notes!!.endsWith("Processing takes about 3 weeks (checked 20 Oct 2026)"))
        assertEquals(LocalDate.of(2026, 10, 30), task.targetLocalDate)
        assertEquals("the user's deadline is untouched", LocalDate.of(2026, 11, 20), task.deadlineLocalDate)
        assertFalse("the unaccepted reminder was not written", runBlocking { container.taskRepository.getPolicy(taskId) }!!.enabled)
        val set = runBlocking { container.proposalSets.get(setId) }!!
        assertEquals(ItemStatus.PENDING, set.item("r")!!.status)
        assertEquals("research sets stay open while something is pending", SetStatus.OPEN, set.status)
        assertEquals(4, runBlocking { container.proposalSets.provenanceForTask(taskId) }.size)

        // Back on the task: the step carries a light provenance marker that opens the full record.
        compose.onNodeWithTag("review_done").performClick()
        waitFor("detail_list")
        val stepId = steps.first { it.title.startsWith("Book an appointment") }.id
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("step_prov_$stepId"))
        compose.onNodeWithTag("step_prov_$stepId").performClick()
        waitFor("provenance_sheet")
        assertTrue(compose.onAllNodes(hasText("Appointments required since 2025", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("provenance_meta", useUnmergedTree = true).assertTextContainsSafely("claude-sonnet-5-5")
        compose.onAllNodes(hasText("https://gov.example/passports")).fetchSemanticsNodes().isNotEmpty().let { assertTrue(it) }
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close"))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("provenance_sheet")).fetchSemanticsNodes().isEmpty() }
        // The banner now counts the two still-pending items.
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("detail_review_banner_$setId"))
        compose.onAllNodes(hasText("2 changes waiting for your review")).fetchSemanticsNodes().isNotEmpty().let { assertTrue(it) }
        // Undo from the review while the task has not moved on.
        compose.onNodeWithTag("detail_review_banner_$setId").performClick()
        waitFor("review_list")
        waitFor("review_apply")
        waitFor("review_selected_count")
        // Reopened review: the applied items are listed as settled, nothing is selected again, undo is not offered here (it belongs to the apply summary).
        scrollReview("review_selected_count")
        compose.onNodeWithTag("review_selected_count").assertTextContainsSafely("0 of 2 selected")
        compose.onNodeWithTag("review_apply").assertIsNotEnabled()
        scrollReview("review_settled_a")
        compose.onNodeWithTag("review_settled_a").assertIsDisplayed()
    }

    @Test fun aStepTickedMidReviewIsFlaggedAndKeepYoursDeclinesWithoutWriting() {
        launch(withDeadline = false, source = ProposalSource.PLANNING)
        openReview()
        compose.onNodeWithTag("review_select_recommended").performClick()
        compose.onNode(hasText("Apply 3 changes")).assertIsDisplayed()
        // The user ticks the anchored step while the review is open: the edit becomes a conflict, the rest still applies.
        val photo = runBlocking { container.taskRepository.getSteps(taskId) }.first { it.title == "Take photo" }
        runBlocking { container.taskRepository.setStepDone(photo.id, true) }
        waitFor("review_stale")
        compose.onNodeWithTag("review_stale").assertIsDisplayed()
        scrollReview("review_check_note_e")
        compose.onNodeWithTag("review_check_note_e", useUnmergedTree = true).assertTextContainsSafely("ticked")
        compose.onNodeWithTag("review_proposed_e", useUnmergedTree = true).assertTextContainsSafely("Take a passport photo")
        compose.onNodeWithTag("review_apply").assertIsNotEnabled()
        compose.onNodeWithTag("review_keep_yours_e").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Apply 2 changes")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review_apply").assertIsEnabled().performClick()
        waitFor("review_result")
        val steps = runBlocking { container.taskRepository.getSteps(taskId) }
        assertEquals("the ticked step kept its wording and stays done", "Take photo", steps.first { it.id == photo.id }.title)
        assertTrue(steps.first { it.id == photo.id }.isDone)
        assertTrue(steps.any { it.title == "Book an appointment" })
        val set = runBlocking { container.proposalSets.get(setId) }!!
        assertEquals(ItemStatus.DECLINED, set.item("e")!!.status)
        assertEquals("planning sets close on apply", SetStatus.CLOSED, set.status)
        assertEquals(ContentOrigin.PLANNING, steps.first { it.title == "Book an appointment" }.origin)
        // A deadline proposed for a task without one applies only when chosen; here it was never chosen.
        assertEquals(DeadlineType.NONE, runBlocking { container.taskRepository.getTask(taskId) }!!.deadlineType)
        // Undo from the summary reverts the applied items only.
        clock.instant = clock.instant.plusSeconds(10)
        compose.onNodeWithTag("review_undo").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Changes removed again")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Find old passport", "Take photo"), runBlocking { container.taskRepository.getSteps(taskId) }.map { it.title })
    }

    @Test fun rejectAllClosesTheSetWithoutTouchingTheTask() {
        launch()
        val before = runBlocking { container.taskRepository.getTask(taskId) }!!
        openReview()
        compose.onNodeWithTag("review_reject_all").assertIsEnabled().performClick()
        waitFor("review_done")
        compose.waitUntil(10_000) { runBlocking { container.proposalSets.get(setId) }!!.status == SetStatus.CLOSED }
        assertEquals(before, runBlocking { container.taskRepository.getTask(taskId) })
        assertTrue(runBlocking { container.proposalSets.get(setId) }!!.items.all { it.status == ItemStatus.DECLINED })
    }

    @Test fun touchTargetsAndLabelsAreAccessible() {
        launch()
        openReview()
        scrollReview("review_check_a")
        compose.onNodeWithTag("review_check_a").assertTouchTargetAtLeast(48.dp)
        compose.onNodeWithTag("review_check_a").assert(androidx.compose.ui.test.hasContentDescription("Select: New step"))
        scrollReview("review_accept_r")
        compose.onNodeWithTag("review_accept_r").assertTouchTargetAtLeast(48.dp)
        compose.onNodeWithTag("review_accept_r").assert(androidx.compose.ui.test.hasContentDescription("Accept: Reminders"))
        compose.onNodeWithTag("review_apply").assertTouchTargetAtLeast(48.dp)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTouchTargetAtLeast(min: androidx.compose.ui.unit.Dp) {
        val node = fetchSemanticsNode()
        val density = node.layoutInfo.density
        val bounds = node.touchBoundsInRoot
        val w = with(density) { bounds.width.toDp() }
        val h = with(density) { bounds.height.toDp() }
        assertTrue("touch target ${w}x$h smaller than $min", w >= min - 0.5.dp && h >= min - 0.5.dp)
    }
}

/** The same review at 320 dp wide and 200 % text: every control reachable and usable. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w320dp-h640dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProposalReviewLargeTextUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var container: AppContainer
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 23, 9, 0))

    @After fun tearDown() { runCatching { container.database.close() } }

    @Test fun reviewWorksAtNarrowWidthAndDoubleFontScale() {
        container = AppContainer(
            context = ApplicationProvider.getApplicationContext<Context>(), clock = clock, zoneProvider = FakeZoneProvider(CHICAGO), ids = SequentialIds("ui"),
            database = inMemoryDatabase(), settingsRepository = InMemorySettingsRepository(AppSettings(onboardingCompleted = true)), networkStatus = { true },
        )
        val taskId = runBlocking { container.taskRepository.createTask(NewTask("Renew passport", status = TaskStatus.ACTIONABLE)) }
        runBlocking {
            val snap = container.proposalApplier.snapshot(taskId)!!
            container.proposalSets.save(
                ProposalSet(
                    "set1", ProposalSource.PLANNING, TargetKind.TASK, taskId, madeAtMs = clock.now().toEpochMilli(), baseOriginal = snap, base = snap, title = "Plan next step",
                    items = listOf(
                        ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book an appointment at the consulate as early as possible"), recommended = true),
                        ProposalItem("r", ItemKind.SET_REMINDER, value = ItemValue.Reminder("DAILY_TIMES", listOf("09:00")), material = true),
                    ),
                ),
            )
        }
        compose.setContent { LifeAdminTheme(dynamicColor = false) { LifeAdminRoot(container) } }
        waitFor("nav_tasks")
        compose.onNodeWithTag("nav_tasks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Renew passport", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Renew passport", substring = true))[0].performClick()
        waitFor("detail_title")
        compose.onNodeWithTag("detail_list").performScrollToNode(hasTestTag("detail_review_banner_set1"))
        compose.onNodeWithTag("detail_review_banner_set1").performClick()
        waitFor("review_list")
        waitFor("review_apply")
        // At 200 % text the header pushes the selection row below the fold: scroll to it like a user would.
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_select_recommended"))
        compose.onNodeWithTag("review_select_recommended").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Apply 1 change")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("review_accept_r"))
        compose.onNodeWithTag("review_accept_r").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onNodeWithTag("review_accept_r", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "Accepted" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Apply 2 changes")).fetchSemanticsNodes().isNotEmpty() }
        // The bottom bar stacks its buttons at this scale and stays reachable.
        compose.onNodeWithTag("review_apply").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithTag("review_reject_all").assertIsDisplayed()
        compose.onNodeWithTag("review_apply").performClick()
        waitFor("review_result")
        compose.onNodeWithTag("review_result_title").assertTextContainsSafely("Applied 2 changes")
        assertTrue(runBlocking { container.taskRepository.getPolicy(taskId) }!!.enabled)
        compose.onNodeWithTag("review_done").assertIsDisplayed()
    }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextContainsSafely(text: String) =
    assert(hasText(text, substring = true))
