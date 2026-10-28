package com.lifeadmin.autopilot.ui

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.proposal.ConflictResolution
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.TargetKind
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.ui.review.ProposalReviewViewModel
import com.lifeadmin.autopilot.ui.review.ReviewUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant

/** Review state (selection, edits, conflict decisions) survives recreation; nothing is selected on first open; Select recommended obeys the rule. */
@RunWith(AndroidJUnit4::class)
class ProposalReviewViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var sets: ProposalSetRepository
    private lateinit var applier: ProposalApplier
    private val clock = FakeClock(Instant.parse("2026-10-23T09:00:00Z"))
    private lateinit var taskId: String
    private lateinit var set: ProposalSet

    @Before fun setUp() {
        db = inMemoryDatabase()
        tasks = TaskRepository(db, clock, SequentialIds("t"), { CHICAGO }, ScheduleCoordinator.Direct)
        sets = ProposalSetRepository(db)
        applier = ProposalApplier(db, tasks, sets, clock, SequentialIds("a"))
        runBlocking {
            taskId = tasks.createTask(NewTask("Renew passport", status = TaskStatus.ACTIONABLE))
            tasks.addStep(taskId, "Take photo")
            val snap = applier.snapshot(taskId)!!
            set = ProposalSet(
                "set1", ProposalSource.PLANNING, TargetKind.TASK, taskId, madeAtMs = clock.now().toEpochMilli(), baseOriginal = snap, base = snap,
                items = listOf(
                    ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment"), recommended = true),
                    ProposalItem("b", ItemKind.ADD_STEP, value = ItemValue.Step("Pay the fee"), recommended = false),
                    ProposalItem("r", ItemKind.SET_REMINDER, value = ItemValue.Reminder("DAILY_TIMES", listOf("09:00")), recommended = true, material = true),
                    ProposalItem("d", ItemKind.ADD_STEP, value = ItemValue.Step("take photo"), recommended = true),
                ),
            )
            sets.save(set)
        }
    }

    @After fun tearDown() { subscriptions.values.forEach { it.cancel() }; db.close() }

    private fun vm(handle: SavedStateHandle) = ProposalReviewViewModel(handle, taskId, "set1", sets, tasks, applier, clock, FakeZoneProvider(CHICAGO))

    /** Keeps the state flow subscribed (stateIn is WhileSubscribed) and pumps the Robolectric main looper until it is loaded and [until] holds. */
    private val subscriptions = mutableMapOf<ProposalReviewViewModel, kotlinx.coroutines.Job>()
    private fun ready(vm: ProposalReviewViewModel, until: (ReviewUi) -> Boolean = { true }): ReviewUi {
        subscriptions.getOrPut(vm) { kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { vm.state.collect { } } }
        repeat(600) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val s = vm.state.value
            if (!s.loading && s.set != null && s.snapshot != null && until(s)) return s
            Thread.sleep(10)
        }
        throw AssertionError("review state not ready: ${vm.state.value.copy(rows = emptyList())}")
    }

    @Test fun nothingIsSelectedOnOpenAndSelectRecommendedSkipsMaterialAndConflictingItems() {
        val vm = vm(SavedStateHandle())
        val s = ready(vm)
        assertEquals(0, s.selectedCount)
        assertEquals("the duplicate wording is a conflict", true, s.rows.first { it.item.id == "d" }.conflict)
        assertEquals(listOf("a", "b", "d"), s.plain.map { it.item.id })
        assertEquals(listOf("r"), s.material.map { it.item.id })
        vm.selectRecommended()
        val after = ready(vm) { it.selectedCount == 1 }
        assertEquals("only 'a': 'b' is not recommended, 'r' is material, 'd' conflicts", setOf("a"), after.pending.filter { it.selected }.map { it.item.id }.toSet())
        assertEquals(1, after.applyCount)
    }

    @Test fun selectionEditsAndDecisionsSurviveRecreation() {
        val handle = SavedStateHandle()
        val first = vm(handle)
        ready(first)
        first.setSelected("a", true)
        first.editText("a", "Book an appointment online")
        first.resolve("d", ConflictResolution.APPLY_ANYWAY)
        first.setSelected("r", true)

        val second = vm(handle)
        val s = ready(second) { it.selectedCount == 3 }
        assertEquals(setOf("a", "d", "r"), s.pending.filter { it.selected }.map { it.item.id }.toSet())
        assertEquals("Book an appointment online", (s.rows.first { it.item.id == "a" }.edited as ItemValue.Step).text)
        assertEquals(ConflictResolution.APPLY_ANYWAY, s.rows.first { it.item.id == "d" }.resolution)
        assertEquals(3, s.applyCount)
        assertTrue(s.undecided.isEmpty())
    }

    @Test fun applyWritesOnlyChosenItemsAndKeepYoursDeclinesWithoutWriting() {
        val vm = vm(SavedStateHandle())
        ready(vm)
        vm.setSelected("a", true)
        vm.setSelected("d", true)
        ready(vm) { it.selectedCount == 2 }
        vm.apply()
        var s = ready(vm) { it.message != null || it.applied != null }
        assertNull("an undecided conflict blocks the apply; nothing is written", s.applied)
        assertTrue(s.message!!.contains("Decide"))
        assertEquals(1, runBlocking { tasks.getSteps(taskId) }.size)
        vm.resolve("d", ConflictResolution.KEEP_YOURS)
        // Deciding clears the "decide first" message; the declined item is no longer selected or pending.
        s = ready(vm) { it.rows.first { r -> r.item.id == "d" }.item.status == ItemStatus.DECLINED && it.message == null && it.selectedCount == 1 }
        assertEquals(setOf("a"), s.pending.filter { it.selected }.map { it.item.id }.toSet())
        vm.apply()
        // The apply summary comes from the applier result; Undo is offered once the observed task and set carry the new revision.
        s = ready(vm) { (it.applied != null && it.canUndo) || it.message != null }
        assertEquals(null, s.message)
        assertEquals(listOf("a"), s.applied!!.appliedItemIds)
        assertEquals(listOf("Take photo", "Book appointment"), runBlocking { tasks.getSteps(taskId) }.map { it.title })
        assertFalse("the material reminder was never chosen, so no reminder was written", runBlocking { tasks.getPolicy(taskId) }!!.enabled)
        assertTrue(s.canUndo)
        vm.undo()
        s = ready(vm) { it.undone || it.message != null }
        assertEquals(null, s.message)
        assertTrue(s.undone)
        assertEquals(listOf("Take photo"), runBlocking { tasks.getSteps(taskId) }.map { it.title })
    }
}
