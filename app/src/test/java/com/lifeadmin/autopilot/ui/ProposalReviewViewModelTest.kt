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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
@OptIn(ExperimentalCoroutinesApi::class)
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
        Dispatchers.setMain(StandardTestDispatcher())
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

    @After fun tearDown() { Dispatchers.resetMain(); db.close() }

    private fun vm(handle: SavedStateHandle) = ProposalReviewViewModel(handle, taskId, "set1", sets, tasks, applier, clock, FakeZoneProvider(CHICAGO))

    private fun ready(vm: ProposalReviewViewModel): ReviewUi = runBlocking {
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { vm.state.collect { } }
        try { vm.state.first { !it.loading && it.set != null && it.snapshot != null } } finally { job.cancel() }
    }

    @Test fun nothingIsSelectedOnOpenAndSelectRecommendedSkipsMaterialAndConflictingItems() {
        val vm = vm(SavedStateHandle())
        val s = ready(vm)
        assertEquals(0, s.selectedCount)
        assertEquals("the duplicate wording is a conflict", true, s.rows.first { it.item.id == "d" }.conflict)
        assertEquals(listOf("a", "b", "d"), s.plain.map { it.item.id })
        assertEquals(listOf("r"), s.material.map { it.item.id })
        vm.selectRecommended()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val after = ready(vm)
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
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val second = vm(handle)
        val s = ready(second)
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
        vm.apply()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        var s = ready(vm)
        assertNull("an undecided conflict blocks the apply; nothing is written", s.applied)
        assertTrue(s.message!!.contains("Decide"))
        assertEquals(1, runBlocking { tasks.getSteps(taskId) }.size)
        vm.resolve("d", ConflictResolution.KEEP_YOURS)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        runBlocking { sets.observe("set1").first { it?.item("d")?.status == ItemStatus.DECLINED } }
        vm.apply()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        s = runBlocking { vm.state.first { it.applied != null } }
        assertEquals(listOf("a"), s.applied!!.appliedItemIds)
        assertEquals(listOf("Take photo", "Book appointment"), runBlocking { tasks.getSteps(taskId) }.map { it.title })
        assertFalse("the material reminder was never chosen, so no reminder was written", runBlocking { tasks.getPolicy(taskId) }!!.enabled)
        assertTrue(s.canUndo)
        vm.undo()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        runBlocking { vm.state.first { it.undone } }
        assertEquals(listOf("Take photo"), runBlocking { tasks.getSteps(taskId) }.map { it.title })
    }
}
