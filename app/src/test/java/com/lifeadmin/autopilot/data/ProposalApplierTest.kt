package com.lifeadmin.autopilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.proposal.ApplyChoice
import com.lifeadmin.autopilot.domain.proposal.ApplyResult
import com.lifeadmin.autopilot.domain.proposal.CheckOutcome
import com.lifeadmin.autopilot.domain.proposal.ConflictResolution
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
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Handoff §14 item 7 and §13 "Atomic, conflict-aware task updates": one transaction, per-item
 * conflicts, done steps protected, provenance with every element, history with both revisions,
 * the original snapshot preserved, safe undo, and no write without an explicit choice.
 */
@RunWith(AndroidJUnit4::class)
class ProposalApplierTest {
    private lateinit var db: AppDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var sets: ProposalSetRepository
    private lateinit var applier: ProposalApplier
    private val clock = FakeClock(Instant.parse("2026-10-23T09:00:00Z"))
    private val ids = SequentialIds("id")

    @Before fun setUp() {
        db = inMemoryDatabase()
        tasks = TaskRepository(db, clock, ids, { CHICAGO }, ScheduleCoordinator.Direct)
        sets = ProposalSetRepository(db)
        applier = ProposalApplier(db, tasks, sets, clock, ids)
    }

    @After fun tearDown() = db.close()

    private suspend fun seedTask(): String {
        val id = tasks.createTask(NewTask("Renew passport", notes = "Expires in March", status = TaskStatus.ACTIONABLE))
        tasks.addStep(id, "Find old passport")
        tasks.addStep(id, "Take photo")
        val done = tasks.addStep(id, "Fill in form")
        tasks.setStepDone(done, true)
        return id
    }

    private suspend fun makeSet(taskId: String, source: ProposalSource = ProposalSource.PLANNING, vararg items: ProposalItem): ProposalSet {
        val snap = applier.snapshot(taskId)!!
        val set = ProposalSet("set-${ids.newId()}", source, TargetKind.TASK, taskId, investigationId = if (source == ProposalSource.RESEARCH) "inv1" else null, turnId = if (source == ProposalSource.RESEARCH) "turn1" else null, madeAtMs = clock.now().toEpochMilli(), model = "claude-sonnet-5-5", baseOriginal = snap, base = snap, items = items.toList())
        sets.save(set)
        return set
    }

    private suspend fun stepTitles(taskId: String) = tasks.getSteps(taskId).map { it.title }
    private suspend fun stepId(taskId: String, title: String) = tasks.getSteps(taskId).first { it.title == title }.id

    @Test fun savingASetWritesNothingToTheTask() = runTest {
        val id = seedTask()
        val before = tasks.getTask(id)!!
        makeSet(id, ProposalSource.PLANNING, ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")))
        assertEquals(before, tasks.getTask(id))
        assertEquals(3, tasks.getSteps(id).size)
        assertEquals("no choices → nothing applied", ApplyResult.Rejected::class, applier.apply(sets.getAll().single().id, emptyList())::class)
        assertEquals(before, tasks.getTask(id))
    }

    @Test fun appliesOnlyChosenItemsAtomicallyWithProvenanceHistoryAndRevs() = runTest {
        val id = seedTask()
        val revBefore = tasks.getTask(id)!!.rev
        val photo = stepId(id, "Take photo")
        val set = makeSet(
            id, ProposalSource.RESEARCH,
            ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment", afterStepId = photo), recommended = true,
                src = ItemSrc(listOf(Premise("Appointments are required", listOf("F1"))), listOf(FindingRef("F1", "turn1", "f-1", "Appointments required since 2025", "established", "2026-10-20", listOf("src-3"))), "established", "2026-10-20")),
            ProposalItem("b", ItemKind.APPEND_NOTE, value = ItemValue.Text("Processing takes about 3 weeks (checked 20 Oct 2026)"), recommended = true),
            ProposalItem("c", ItemKind.SET_TARGET, value = ItemValue.Date("2026-11-10", basis = "3-week processing time, verified"), material = true),
            ProposalItem("d", ItemKind.SET_TITLE, value = ItemValue.Text("Renew passport before the trip")),
        )
        clock.advance(Duration.ofMinutes(1))
        val result = applier.apply(set.id, listOf(ApplyChoice("a"), ApplyChoice("b"), ApplyChoice("c")))
        val applied = result as ApplyResult.Applied
        assertEquals(listOf("a", "b", "c"), applied.appliedItemIds)
        assertEquals(revBefore, applied.revBefore)
        assertEquals(revBefore + 1, applied.revAfter)

        val task = tasks.getTask(id)!!
        assertEquals("unchosen item untouched", "Renew passport", task.title)
        assertEquals(listOf("Find old passport", "Take photo", "Book appointment", "Fill in form"), stepTitles(id))
        assertTrue(task.notes!!.endsWith("Processing takes about 3 weeks (checked 20 Oct 2026)"))
        assertEquals(LocalDate.of(2026, 11, 10), task.targetLocalDate)
        assertEquals("3-week processing time, verified", task.targetBasis)
        assertEquals("a target is not a deadline", DeadlineType.NONE, task.deadlineType)

        // Provenance: the step carries its record; the record carries src unchanged (INT-031/033).
        val step = tasks.getSteps(id).first { it.title == "Book appointment" }
        assertEquals(ContentOrigin.RESEARCH, step.origin)
        val record = sets.provenance(step.provenanceId!!)!!
        assertEquals("inv1", record.investigationId)
        assertEquals("F1", record.src!!.findings.single().ref)
        assertEquals("2026-10-20", record.src!!.asOf)
        assertEquals(3, sets.provenanceForTask(id).size)

        // History names the set and both revisions (INT-054); the original snapshot is untouched (OD-2).
        val event = db.eventDao().getForTask(id).first { it.eventType == EventType.EDITED && it.summary.startsWith("Applied 3") }
        val meta = com.lifeadmin.autopilot.data.local.decodeMetadata(event.structuredMetadata)
        assertEquals(set.id, meta["proposalSetId"])
        assertEquals(revBefore.toString(), meta["revBefore"])
        assertEquals((revBefore + 1).toString(), meta["revAfter"])
        val after = sets.get(set.id)!!
        assertEquals(set.baseOriginal, after.baseOriginal)
        assertEquals("base is rebased", revBefore + 1, after.base.rev)
        assertEquals(ItemStatus.APPLIED, after.item("a")!!.status)
        assertEquals(ItemStatus.PENDING, after.item("d")!!.status)
        assertEquals("research sets stay open while something is pending (OD-1)", SetStatus.OPEN, after.status)
    }

    @Test fun planningSetsCloseOnApply() = runTest {
        val id = seedTask()
        val set = makeSet(id, ProposalSource.PLANNING, ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")), ProposalItem("b", ItemKind.ADD_STEP, value = ItemValue.Step("Pay the fee")))
        applier.apply(set.id, listOf(ApplyChoice("a")))
        val after = sets.get(set.id)!!
        assertEquals(SetStatus.CLOSED, after.status)
        assertEquals(ContentOrigin.PLANNING, tasks.getSteps(id).first { it.title == "Book appointment" }.origin)
        assertEquals(ApplyResult.Rejected::class, applier.apply(set.id, listOf(ApplyChoice("b")))::class)
    }

    @Test fun anUndecidedConflictAtApplyTimeWritesNothing() = runTest {
        val id = seedTask()
        val photo = stepId(id, "Take photo")
        val set = makeSet(id, ProposalSource.PLANNING,
            ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")),
            ProposalItem("c", ItemKind.EDIT_STEP, anchorStepId = photo, value = ItemValue.Step("Take a passport photo")))
        // The user ticks the step between review and apply.
        tasks.setStepDone(photo, true)
        val before = tasks.getTask(id)!!
        val result = applier.apply(set.id, listOf(ApplyChoice("a"), ApplyChoice("c")))
        val review = result as ApplyResult.NeedsReview
        assertEquals(CheckOutcome.CONFLICT, review.checks.first { it.itemId == "c" }.outcome)
        assertEquals("nothing written, not even the compatible item", before, tasks.getTask(id))
        assertEquals(3, tasks.getSteps(id).size)
        assertTrue(sets.provenanceForTask(id).isEmpty())
        // Keep yours declines the conflicting item; the compatible one applies.
        val second = applier.apply(set.id, listOf(ApplyChoice("a"), ApplyChoice("c", resolution = ConflictResolution.KEEP_YOURS))) as ApplyResult.Applied
        assertEquals(listOf("a"), second.appliedItemIds)
        assertEquals(listOf("c"), second.declinedItemIds)
        assertEquals("Take photo", tasks.getSteps(id).first { it.id == photo }.title)
        assertEquals(ItemStatus.DECLINED, sets.get(set.id)!!.item("c")!!.status)
    }

    @Test fun doneStepsChangeOnlyThroughAnExplicitChoice() = runTest {
        val id = seedTask()
        val form = stepId(id, "Fill in form")
        val set = makeSet(id, ProposalSource.PLANNING, ProposalItem("c", ItemKind.EDIT_STEP, anchorStepId = form, value = ItemValue.Step("Fill in the online form")))
        assertEquals(ApplyResult.NeedsReview::class, applier.apply(set.id, listOf(ApplyChoice("c")))::class)
        assertEquals("Fill in form", tasks.getSteps(id).first { it.id == form }.title)
        val ok = applier.apply(set.id, listOf(ApplyChoice("c", resolution = ConflictResolution.APPLY_ANYWAY))) as ApplyResult.Applied
        assertEquals(listOf("c"), ok.appliedItemIds)
        val step = tasks.getSteps(id).first { it.id == form }
        assertEquals("Fill in the online form", step.title)
        assertTrue("still done: the proposal never reopens it", step.isDone)
    }

    @Test fun userStatedDeadlineIsNeverChangedEvenWhenChosen() = runTest {
        val id = seedTask()
        tasks.setDeadline(id, DeadlineType.DATE_ONLY, LocalDate.of(2026, 11, 1), null)
        val set = makeSet(id, ProposalSource.RESEARCH, ProposalItem("f", ItemKind.SET_DEADLINE, value = ItemValue.Date("2026-10-20", basis = "processing time"), material = true))
        val result = applier.apply(set.id, listOf(ApplyChoice("f", resolution = ConflictResolution.APPLY_ANYWAY)))
        assertEquals(ApplyResult.NeedsReview::class, result::class)
        assertEquals(CheckOutcome.BLOCKED, (result as ApplyResult.NeedsReview).checks.single().outcome)
        assertEquals(LocalDate.of(2026, 11, 1), tasks.getTask(id)!!.deadlineLocalDate)
    }

    @Test fun deadlineProposalAppliesOnlyWhenTheTaskHasNoneAndIsLabeled() = runTest {
        val id = seedTask()
        val set = makeSet(id, ProposalSource.PLANNING, ProposalItem("f", ItemKind.SET_DEADLINE, value = ItemValue.Date("2026-11-05", basis = "derived from your trip date"), material = true))
        applier.apply(set.id, listOf(ApplyChoice("f"))) as ApplyResult.Applied
        val task = tasks.getTask(id)!!
        assertEquals(DeadlineType.DATE_ONLY, task.deadlineType)
        assertEquals(LocalDate.of(2026, 11, 5), task.deadlineLocalDate)
        assertTrue(sets.provenanceForTask(id).single().appliedText.contains("derived from your trip date"))
    }

    @Test fun reminderAndStatusItemsGoThroughThePolicyAndStateMachine() = runTest {
        val id = seedTask()
        val set = makeSet(id, ProposalSource.PLANNING,
            ProposalItem("r", ItemKind.SET_REMINDER, value = ItemValue.Reminder("DAILY_TIMES", listOf("18:00", "09:00"), basis = "you asked to be reminded"), material = true),
            ProposalItem("s", ItemKind.SET_STATUS, value = ItemValue.Status("WAITING", reason = "the consulate", checkInAt = "2026-10-30T09:00"), material = true))
        applier.apply(set.id, listOf(ApplyChoice("r"), ApplyChoice("s"))) as ApplyResult.Applied
        val policy = tasks.getPolicy(id)!!
        assertTrue(policy.enabled)
        assertEquals(listOf("09:00", "18:00"), policy.times.map { it.toString() })
        val task = tasks.getTask(id)!!
        assertEquals(TaskStatus.WAITING, task.status)
        assertEquals("the consulate", task.waitingReason)
        assertNotNull(task.waitingCheckInAt)
        // Finishing a task is never a proposal.
        val finish = makeSet(id, ProposalSource.PLANNING, ProposalItem("x", ItemKind.SET_STATUS, value = ItemValue.Status("DONE")))
        assertEquals(ApplyResult.NeedsReview::class, applier.apply(finish.id, listOf(ApplyChoice("x", resolution = ConflictResolution.APPLY_ANYWAY)))::class)
        assertEquals(TaskStatus.WAITING, tasks.getTask(id)!!.status)
    }

    @Test fun editedValueIsAppliedButNeverPersistedBackAsTheProposal() = runTest {
        val id = seedTask()
        val set = makeSet(id, ProposalSource.PLANNING, ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")))
        applier.apply(set.id, listOf(ApplyChoice("a", value = ItemValue.Step("Book appointment at the consulate")))) as ApplyResult.Applied
        assertTrue(stepTitles(id).contains("Book appointment at the consulate"))
        assertEquals("Book appointment", (sets.get(set.id)!!.item("a")!!.value as ItemValue.Step).text)
    }

    @Test fun undoRestoresEverythingWhileTheTaskHasNotMovedOn() = runTest {
        val id = seedTask()
        val photo = stepId(id, "Take photo")
        val original = tasks.getTask(id)!!
        val originalSteps = tasks.getSteps(id)
        val set = makeSet(id, ProposalSource.RESEARCH,
            ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")),
            ProposalItem("c", ItemKind.EDIT_STEP, anchorStepId = photo, value = ItemValue.Step("Take a passport photo")),
            ProposalItem("o", ItemKind.REMOVE_STEP, anchorStepId = stepId(id, "Find old passport"), value = ItemValue.None),
            ProposalItem("b", ItemKind.APPEND_NOTE, value = ItemValue.Text("Processing takes 3 weeks")),
            ProposalItem("r", ItemKind.SET_REMINDER, value = ItemValue.Reminder("DAILY_TIMES", listOf("18:00")), material = true),
            ProposalItem("t", ItemKind.SET_TARGET, value = ItemValue.Date("2026-11-10", basis = "verified"), material = true))
        clock.advance(Duration.ofMinutes(1))
        val applied = applier.apply(set.id, listOf("a", "c", "o", "b", "r", "t").map { ApplyChoice(it, resolution = ConflictResolution.APPLY_ANYWAY) }) as ApplyResult.Applied
        assertEquals(6, applied.appliedItemIds.size)
        assertEquals(listOf("Take a passport photo", "Fill in form", "Book appointment"), stepTitles(id))
        clock.advance(Duration.ofMinutes(1))
        assertTrue(applier.undo(set.id))
        val back = tasks.getTask(id)!!
        assertEquals(original.title, back.title)
        assertEquals(original.notes, back.notes)
        assertNull(back.targetLocalDate)
        assertEquals(originalSteps.map { it.title }, stepTitles(id))
        assertEquals(originalSteps.map { it.id }, tasks.getSteps(id).map { it.id })
        assertEquals(ContentOrigin.USER, tasks.getSteps(id).first { it.id == photo }.origin)
        assertFalse(tasks.getPolicy(id)!!.enabled)
        assertTrue(sets.provenanceForTask(id).isEmpty())
        val after = sets.get(set.id)!!
        assertNull(after.lastApply)
        assertTrue(after.items.all { it.status == ItemStatus.PENDING })
        assertEquals(SetStatus.OPEN, after.status)
        assertTrue("rev moved forward twice: apply and undo are both changes", back.rev > applied.revAfter)
    }

    @Test fun undoRefusesAfterAnyLaterChange() = runTest {
        val id = seedTask()
        val set = makeSet(id, ProposalSource.PLANNING, ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")))
        applier.apply(set.id, listOf(ApplyChoice("a"))) as ApplyResult.Applied
        tasks.updateDetails(id, "Renew passport soon", null, com.lifeadmin.autopilot.domain.model.Priority.NORMAL)
        assertFalse(applier.undo(set.id))
        assertTrue(stepTitles(id).contains("Book appointment"))
        assertEquals(1, sets.provenanceForTask(id).size)
    }

    @Test fun userRenameKeepsTheOriginAndMarksTheUserAsLatestEditor() = runTest {
        val id = seedTask()
        val set = makeSet(id, ProposalSource.RESEARCH, ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")))
        applier.apply(set.id, listOf(ApplyChoice("a"))) as ApplyResult.Applied
        val step = tasks.getSteps(id).first { it.title == "Book appointment" }
        tasks.renameStep(step.id, "Book an appointment online")
        val renamed = tasks.getSteps(id).first { it.id == step.id }
        assertEquals(ContentOrigin.RESEARCH, renamed.origin)
        val record = sets.provenance(step.provenanceId!!)!!
        assertTrue(record.editedByUser)
        assertEquals("inv1", record.investigationId)
    }

    @Test fun everyUserChangeBumpsRev() = runTest {
        val id = seedTask()
        val r0 = tasks.getTask(id)!!.rev
        tasks.updateDetails(id, "Renew passport!", null, com.lifeadmin.autopilot.domain.model.Priority.HIGH)
        val r1 = tasks.getTask(id)!!.rev
        tasks.addStep(id, "Another")
        val r2 = tasks.getTask(id)!!.rev
        tasks.snooze(id, clock.now().plusSeconds(3600))
        val r3 = tasks.getTask(id)!!.rev
        assertTrue(r0 < r1 && r1 < r2 && r2 < r3)
    }

    @Test fun partialApplyThenTheRestKeepsTheOriginalSnapshotAndRecordsTwoApplies() = runTest {
        val id = seedTask()
        val set = makeSet(id, ProposalSource.RESEARCH,
            ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")),
            ProposalItem("b", ItemKind.APPEND_NOTE, value = ItemValue.Text("Processing takes 3 weeks")),
            ProposalItem("c", ItemKind.SET_TARGET, value = ItemValue.Date("2026-11-10", basis = "verified processing time"), material = true))
        val first = applier.apply(set.id, listOf(ApplyChoice("a"))) as ApplyResult.Applied
        // The user ticks the new step and snoozes in between: unrelated to what is left.
        tasks.setStepDone(stepId(id, "Book appointment"), true)
        tasks.snooze(id, clock.now().plusSeconds(3600))
        val checks = applier.reconcile(sets.get(set.id)!!)
        assertTrue("remaining items reconcile against the rebased base and stay applicable", checks.none { it.blocksApply })
        val second = applier.apply(set.id, listOf(ApplyChoice("b"), ApplyChoice("c"))) as ApplyResult.Applied
        assertTrue(second.revBefore > first.revAfter)
        val after = sets.get(set.id)!!
        assertEquals("baseOriginal never changes (OD-2)", set.baseOriginal, after.baseOriginal)
        assertEquals("base follows the latest apply", second.revAfter, after.base.rev)
        assertEquals(SetStatus.CLOSED, after.status)
        assertEquals(listOf("b", "c"), after.lastApply!!.appliedItemIds)
        val applies = db.eventDao().getForTask(id).filter { it.eventType == EventType.EDITED && it.summary.startsWith("Applied") }
        assertEquals(2, applies.size)
        assertEquals("the first apply's history entry is untouched", "1", com.lifeadmin.autopilot.data.local.decodeMetadata(applies.last().structuredMetadata)["items"]!!.split(",").size.toString())
        assertEquals(3, sets.provenanceForTask(id).size)
        // Undo now reverts only the second apply; the step from the first stays.
        assertTrue(applier.undo(set.id))
        assertTrue(stepTitles(id).contains("Book appointment"))
        assertNull(tasks.getTask(id)!!.targetLocalDate)
        assertEquals("Expires in March", tasks.getTask(id)!!.notes)
    }

    @Test fun reapplyingASettledItemIsRefusedAndChangesNothing() = runTest {
        val id = seedTask()
        val set = makeSet(id, ProposalSource.RESEARCH,
            ProposalItem("b", ItemKind.APPEND_NOTE, value = ItemValue.Text("Processing takes 3 weeks")),
            ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")))
        applier.apply(set.id, listOf(ApplyChoice("b"))) as ApplyResult.Applied
        val notes = tasks.getTask(id)!!.notes
        val rev = tasks.getTask(id)!!.rev
        val again = applier.apply(set.id, listOf(ApplyChoice("b")))
        assertEquals(ApplyResult.Rejected::class, again::class)
        assertTrue((again as ApplyResult.Rejected).message.contains("already applied"))
        assertEquals(notes, tasks.getTask(id)!!.notes)
        assertEquals(rev, tasks.getTask(id)!!.rev)
        assertEquals(1, sets.provenanceForTask(id).size)
        // A mixed request applies only what is still pending.
        val mixed = applier.apply(set.id, listOf(ApplyChoice("b"), ApplyChoice("a"))) as ApplyResult.Applied
        assertEquals(listOf("a"), mixed.appliedItemIds)
        assertEquals(notes, tasks.getTask(id)!!.notes)
    }

    @Test fun nothingButAChosenReminderItemEverTouchesReminders() = runTest {
        val id = seedTask()
        val before = tasks.getPolicy(id)!!
        val set = makeSet(id, ProposalSource.PLANNING,
            ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")),
            ProposalItem("r", ItemKind.SET_REMINDER, value = ItemValue.Reminder("DAILY_TIMES", listOf("09:00")), material = true),
            ProposalItem("d", ItemKind.SET_DEADLINE, value = ItemValue.Date("2026-11-05", basis = "derived from your trip date"), material = true))
        applier.apply(set.id, listOf(ApplyChoice("a"), ApplyChoice("d"))) as ApplyResult.Applied
        val after = tasks.getPolicy(id)!!
        assertEquals("not enabled", false, after.enabled)
        assertEquals(before.times, after.times)
        assertEquals(before.kind, after.kind)
        assertEquals(ItemStatus.PENDING, sets.get(set.id)!!.item("r")!!.status)
        assertTrue(db.eventDao().getForTask(id).none { it.eventType == EventType.POLICY_EDITED })
    }

    @Test fun declineMarksItemsWithoutTouchingTheTaskAndClosesWhenNothingIsLeft() = runTest {
        val id = seedTask()
        val before = tasks.getTask(id)!!
        val set = makeSet(id, ProposalSource.RESEARCH,
            ProposalItem("a", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")),
            ProposalItem("b", ItemKind.APPEND_NOTE, value = ItemValue.Text("Processing takes 3 weeks")))
        val afterOne = applier.decline(set.id, listOf("a"))!!
        assertEquals(ItemStatus.DECLINED, afterOne.item("a")!!.status)
        assertEquals("Rejected by you", afterOne.item("a")!!.statusReason)
        assertEquals("still one pending", SetStatus.OPEN, afterOne.status)
        assertEquals("the task is untouched", before, tasks.getTask(id))
        val afterAll = applier.decline(set.id, listOf("b"), "Kept yours")!!
        assertEquals(SetStatus.CLOSED, afterAll.status)
        assertEquals("Kept yours", afterAll.item("b")!!.statusReason)
        assertEquals(before, tasks.getTask(id))
        assertEquals(ApplyResult.Rejected::class, applier.apply(set.id, listOf(ApplyChoice("a")))::class)
    }
}
