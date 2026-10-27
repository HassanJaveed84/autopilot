package com.lifeadmin.autopilot.share.applink

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.proposal.ApplyChoice
import com.lifeadmin.autopilot.domain.proposal.ApplyResult
import com.lifeadmin.autopilot.domain.proposal.CheckOutcome
import com.lifeadmin.autopilot.domain.proposal.ConflictResolution
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.SetStatus
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * The import side end to end against Room: the reference resolves or nothing is chosen, a link only
 * ever saves a pending set, repeats are idempotent, and every task change goes through the
 * canonical review → ProposalApplier path with its conflicts, provenance, history and undo.
 */
@RunWith(AndroidJUnit4::class)
class HandoffImporterTest {
    private lateinit var db: AppDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var sets: ProposalSetRepository
    private lateinit var applier: ProposalApplier
    private lateinit var importer: HandoffImporter
    private val clock = FakeClock(Instant.parse("2026-10-27T15:00:00Z"))
    private val ids = SequentialIds("id")

    @Before fun setUp() {
        db = inMemoryDatabase()
        tasks = TaskRepository(db, clock, ids, { CHICAGO }, ScheduleCoordinator.Direct)
        sets = ProposalSetRepository(db)
        applier = ProposalApplier(db, tasks, sets, clock, ids)
        importer = HandoffImporter(tasks, sets, applier, clock)
    }

    @After fun tearDown() = db.close()

    private suspend fun seedGrinder(): String {
        val id = tasks.createTask(NewTask("Choose a coffee grinder", notes = "Budget is \$1,000", status = TaskStatus.ACTIONABLE, preset = ReminderPreset.DAILY))
        val done = tasks.addStep(id, "Set a budget")
        tasks.setStepDone(done, true)
        tasks.addStep(id, "Read reviews")
        return id
    }

    private fun handoff(ref: String?, title: String = "Choose a coffee grinder") = LinkHandoff(
        task = title, decisions = listOf("Choose Model A"), steps = listOf("Compare retailer prices", "Check warranty coverage", "Set a budget"),
        notes = listOf("Budget is \$1,000", "Model B is louder"), ref = ref,
    )

    private suspend fun ready(h: LinkHandoff, taskId: String) = importer.prepare(h, taskId) as HandoffPrepared.Ready

    @Test fun aValidReferencePreselectsItsTaskAndATitleAloneNeverDoes() = runTest {
        val id = seedGrinder()
        tasks.createTask(NewTask("Choose a coffee grinder", status = TaskStatus.ACTIONABLE)) // same title, another task
        assertEquals(HandoffDestination.Matched(id, titleDiffers = false), importer.resolve(handoff(id)))
        assertEquals(HandoffDestination.Matched(id, titleDiffers = true), importer.resolve(handoff(id, title = "Grinder for the office")))
        assertEquals(HandoffDestination.Unresolved(HandoffDestination.Why.NO_REFERENCE), importer.resolve(handoff(null)))
        assertEquals(HandoffDestination.Unresolved(HandoffDestination.Why.NOT_FOUND), importer.resolve(handoff("id-999")))
        assertEquals(HandoffDestination.Unresolved(HandoffDestination.Why.NOT_FOUND), importer.resolve(handoff("not a ref")))
        // A deleted task is a stale reference, never a fallback to the same-titled one.
        tasks.delete(id)
        assertEquals(HandoffDestination.Unresolved(HandoffDestination.Why.NOT_FOUND), importer.resolve(handoff(id)))
    }

    @Test fun preparingSavesOnlyAPendingSetAndLeavesTheTaskAsItWas() = runTest {
        val id = seedGrinder()
        val before = applier.snapshot(id)!!
        val events = db.eventDao().getForTask(id).size
        val r = ready(handoff(id), id)
        assertFalse(r.existed)
        assertEquals(before.copy(takenAtMs = 0), applier.snapshot(id)!!.copy(takenAtMs = 0))
        assertEquals(events, db.eventDao().getForTask(id).size)
        val set = sets.get(r.setId)!!
        assertEquals(ProposalSource.HANDOFF, set.source)
        assertEquals(SetStatus.OPEN, set.status)
        assertTrue(set.items.all { it.status == ItemStatus.PENDING })
        // "Set a budget" (done) and the existing budget note aren't proposed again.
        assertEquals(listOf(ItemKind.ADD_STEP, ItemKind.ADD_STEP, ItemKind.APPEND_NOTE, ItemKind.APPEND_NOTE), set.items.map { it.kind })
        assertEquals(listOf(set), sets.getForTarget(id))
    }

    @Test fun aRepeatedLinkReopensTheSameSetWhetherPendingAppliedOrDeclined() = runTest {
        val id = seedGrinder()
        val first = ready(handoff(id), id)
        assertEquals(HandoffPrepared.Ready(id, first.setId, existed = true), importer.prepare(handoff(id, title = "Renamed by ChatGPT"), id))
        assertEquals(1, sets.getForTarget(id).size)

        val set = sets.get(first.setId)!!
        val applied = applier.apply(set.id, set.items.take(2).map { ApplyChoice(it.id) }) as ApplyResult.Applied
        assertEquals(2, applied.appliedItemIds.size)
        applier.decline(set.id, listOf(set.items[2].id))
        assertTrue(importer.prepare(handoff(id), id) is HandoffPrepared.Ready)
        assertEquals(1, sets.getForTarget(id).size)
        // Applying the already-applied items again is refused and writes nothing.
        val steps = tasks.getSteps(id).size
        assertTrue(applier.apply(set.id, set.items.take(2).map { ApplyChoice(it.id) }) is ApplyResult.Rejected)
        assertEquals(steps, tasks.getSteps(id).size)
        assertEquals(1, tasks.getSteps(id).count { it.title == "Compare retailer prices" })
    }

    @Test fun aLinkWithNothingNewSavesNothing() = runTest {
        val id = seedGrinder()
        val h = LinkHandoff("Choose a coffee grinder", emptyList(), listOf("Read reviews", "set a budget"), listOf("Budget is \$1,000"), id)
        assertEquals(HandoffPrepared.NothingNew, importer.prepare(h, id))
        assertTrue(sets.getForTarget(id).isEmpty())
        assertEquals(HandoffPrepared.TaskGone, importer.prepare(handoff("id-999"), "id-999"))
    }

    @Test fun applyAndUndoGoThroughTheApplierWithChatGptLabels() = runTest {
        val id = seedGrinder()
        val policyBefore = tasks.getPolicy(id)
        val set = sets.get(ready(handoff(id), id).setId)!!
        val r = applier.apply(set.id, set.items.map { ApplyChoice(it.id) }) as ApplyResult.Applied
        assertEquals(4, r.appliedItemIds.size)
        assertEquals(SetStatus.CLOSED, sets.get(set.id)!!.status)

        val steps = tasks.getSteps(id).sortedBy { it.sortOrder }
        assertEquals(listOf("Set a budget", "Read reviews", "Compare retailer prices", "Check warranty coverage"), steps.map { it.title })
        assertTrue("completed work is kept", steps.first().isDone)
        assertTrue("imported steps are never done", steps.drop(2).none { it.isDone })
        assertTrue(steps.drop(2).all { it.origin == ContentOrigin.USER && it.provenanceId != null })
        assertEquals("Budget is \$1,000\nDecided: Choose Model A\nModel B is louder", tasks.getTask(id)!!.notes)
        assertEquals("Choose a coffee grinder", tasks.getTask(id)!!.title)
        assertEquals(TaskStatus.ACTIONABLE, tasks.getTask(id)!!.status)
        // Reminders aren't part of a handoff: the policy is exactly as it was.
        assertEquals(policyBefore, tasks.getPolicy(id))

        val records = sets.provenanceForTask(id)
        assertEquals(4, records.size)
        assertTrue(records.all { it.source == ProposalSource.HANDOFF && it.proposalSetId == set.id && it.model.isEmpty() })
        val history = db.eventDao().getForTask(id).single { it.eventType == EventType.EDITED && it.summary.startsWith("Applied") }
        assertEquals("Applied 4 reviewed changes from ChatGPT: steps, notes", history.summary)
        assertTrue(history.structuredMetadata.orEmpty().contains("chatgpt_handoff"))

        assertTrue(applier.undo(set.id))
        assertEquals(listOf("Set a budget", "Read reviews"), tasks.getSteps(id).sortedBy { it.sortOrder }.map { it.title })
        assertTrue(tasks.getSteps(id).first { it.title == "Set a budget" }.isDone)
        assertEquals("Budget is \$1,000", tasks.getTask(id)!!.notes)
        assertTrue(sets.provenanceForTask(id).isEmpty())
        assertTrue(sets.get(set.id)!!.items.all { it.status == ItemStatus.PENDING })
        assertTrue(db.eventDao().getForTask(id).any { it.structuredMetadata.orEmpty().contains("chatgpt_handoff_undo") })
    }

    @Test fun editsSinceTheConversationGoThroughTheReconciler() = runTest {
        val id = seedGrinder()
        val set = sets.get(ready(handoff(id), id).setId)!!
        val compare = set.items.first { it.kind == ItemKind.ADD_STEP }
        val note = set.items.first { it.kind == ItemKind.APPEND_NOTE }
        // The user adds the same step by hand and edits the notes after the link was opened.
        clock.advance(Duration.ofMinutes(5))
        tasks.addStep(id, "Compare retailer prices")
        tasks.updateDetails(id, "Choose a coffee grinder", "Budget is \$900", Priority.NORMAL)

        val checks = applier.reconcile(sets.get(set.id)!!).associateBy { it.itemId }
        assertEquals(CheckOutcome.CONFLICT, checks.getValue(compare.id).outcome)
        assertEquals(CheckOutcome.NOTE, checks.getValue(note.id).outcome)
        // An undecided conflict sends the whole apply back to review: nothing is written.
        val rev = tasks.getTask(id)!!.rev
        assertTrue(applier.apply(set.id, listOf(ApplyChoice(compare.id), ApplyChoice(note.id))) is ApplyResult.NeedsReview)
        assertEquals(rev, tasks.getTask(id)!!.rev)
        // "Keep yours" declines the duplicate; the note still applies after the user's new notes.
        val r = applier.apply(set.id, listOf(ApplyChoice(compare.id, resolution = ConflictResolution.KEEP_YOURS), ApplyChoice(note.id))) as ApplyResult.Applied
        assertEquals(listOf(compare.id), r.declinedItemIds)
        assertEquals(1, tasks.getSteps(id).count { it.title == "Compare retailer prices" })
        assertEquals("Budget is \$900\nDecided: Choose Model A", tasks.getTask(id)!!.notes)
    }

    @Test fun aDoneTaskBlocksEveryChange() = runTest {
        val id = seedGrinder()
        val set = sets.get(ready(handoff(id), id).setId)!!
        tasks.complete(id)
        assertTrue(applier.reconcile(sets.get(set.id)!!).all { it.outcome == CheckOutcome.BLOCKED })
    }

    @Test fun changingTheDestinationDiscardsOnlyAnUntouchedSet() = runTest {
        val id = seedGrinder()
        val untouched = ready(handoff(id), id).setId
        importer.discardIfUntouched(untouched)
        assertNull(sets.get(untouched))

        val kept = ready(handoff(id), id).setId
        applier.decline(kept, listOf(sets.get(kept)!!.items.first().id))
        importer.discardIfUntouched(kept)
        assertTrue(sets.get(kept) != null)
    }

    @Test fun deletingAndRecreatingAnImportedTaskDoesNotResurrectAppliedProposals() = runTest {
        val h = handoff(null, title = "Plan the garden")
        val firstTask = importer.createTask(h)
        val first = ready(h, firstTask)
        val firstSet = sets.get(first.setId)!!
        val step = firstSet.items.first { it.kind == ItemKind.ADD_STEP }
        assertTrue(applier.apply(first.setId, listOf(ApplyChoice(step.id))) is ApplyResult.Applied)
        assertTrue(sets.provenanceForTask(firstTask).isNotEmpty())

        tasks.delete(firstTask)
        assertNull(sets.get(first.setId))
        assertTrue(sets.provenanceForTask(firstTask).isEmpty())

        // New task from the same link deliberately reuses its deterministic task id.
        val recreatedTask = importer.createTask(h)
        assertEquals(firstTask, recreatedTask)
        val reopened = ready(h, recreatedTask)
        assertEquals(first.setId, reopened.setId)
        assertFalse(reopened.existed)
        assertTrue(sets.get(reopened.setId)!!.items.all { it.status == ItemStatus.PENDING })
        assertTrue(tasks.getSteps(recreatedTask).isEmpty())
    }

    @Test fun aNewTaskFromAHandoffIsPlainAndMadeOnce() = runTest {
        val h = handoff(null, title = "Plan the garden")
        val first = importer.createTask(h)
        assertEquals(first, importer.createTask(h))
        val task = tasks.getTask(first)!!
        assertEquals("Plan the garden", task.title)
        assertEquals(TaskStatus.INBOX, task.status)
        assertNull(task.notes)
        assertTrue(tasks.getSteps(first).isEmpty())
        assertFalse(tasks.getPolicy(first)!!.enabled)
        assertTrue(TaskRef.isWellFormed(first))
    }
}
