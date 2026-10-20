package com.lifeadmin.autopilot.ai.research2

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ResearchSessionRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.Research2Fixtures
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
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

/** Editable proposals → one transactional, idempotent task edit; undo; M3A restrictions kept. */
@RunWith(AndroidJUnit4::class)
class Research2ActionsTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(Instant.parse("2026-10-20T15:00:00Z"))
    private lateinit var tasks: TaskRepository
    private lateinit var store: ResearchSessionRepository
    private lateinit var actions: Research2Actions
    private lateinit var taskId: String
    private lateinit var session: ResearchSession

    @Before fun setUp() = runTest {
        db = inMemoryDatabase()
        tasks = TaskRepository(db, clock, SequentialIds("t"), { CHICAGO }, ScheduleCoordinator.Direct)
        store = ResearchSessionRepository(db)
        actions = Research2Actions(tasks, store, clock)
        taskId = tasks.createTask(NewTask("Supplements", notes = "Monthly order"))
        tasks.addStep(taskId, "Buy magnesium: Pure Encapsulations Magnesium (glycinate)")
        val r = RequirementExtractor.extract(Research2Fixtures.SUPPLEMENTS_REQUEST)
        val registry = SourceRegistry2(runId = "run1")
        val fx = Research2Fixtures.supplementsGather()
        var c = SessionContent("Picks", Research2Fixtures.SUPPLEMENTS_REQUEST, r.template, r.requirements, outcome = SessionOutcome.INTERRUPTED)
        c = SessionAssembler.settlePending(SessionAssembler.merge(c, ContractParser.parse(fx.segments, fx.searches, registry, r.requirements, clock.now().toEpochMilli()), registry.sources()))
        c = c.copy(proposals = ProposalBuilder.complete(c, clock.now().toEpochMilli()), outcome = SessionOutcome.INCOMPLETE_EVIDENCE)
        session = ResearchSession("s1", taskId, clock.now(), clock.now(), c)
        store.save(session)
    }

    @After fun tearDown() { db.close() }

    private suspend fun details(): TaskDetails = tasks.observeDetails(taskId).first()!!
    private fun edits(vararg p: Proposal, include: Boolean = true) = p.map { ProposalEdit(it.id, include, it.title, it.note) }

    @Test fun planDeduplicatesSkipsResearchAndCarriesEvidenceIntoNotes() = runTest {
        val proposals = session.content.proposals
        assertEquals(4, proposals.size)
        val plan = actions.plan(session, details(), edits(*proposals.toTypedArray()), CHICAGO)
        assertEquals("multivitamin, vitamin D and the unticked-by-default 'research glucosamine' step", 3, plan.steps.size)
        assertEquals("Research glucosamine further", plan.steps.last().title)
        val skipped = plan.skipped
        assertEquals(1, skipped.size)
        assertTrue(skipped.any { it.duplicate && it.proposal.requirementId == "r-i2" })
        assertTrue(plan.notesAfter!!.startsWith("Monthly order\n\nFrom web research:"))
        assertTrue(plan.notesAfter!!, plan.notesAfter!!.contains("Buy multivitamin: Thorne Basic Nutrients 2/Day — Two capsules daily; contains vitamin D. (source 1; checked 20 Oct 2026)"))
        assertTrue(plan.proposalId.startsWith("research2:s1:"))
    }

    @Test fun editsChangeTitlesAndNotesAndAddAnywayOverridesTheDuplicateRule() = runTest {
        val proposals = session.content.proposals
        val mag = proposals.first { it.requirementId == "r-i2" }
        val multi = proposals.first { it.requirementId == "r-i0" }
        val plan = actions.plan(
            session, details(),
            listOf(ProposalEdit(multi.id, true, "Order the Thorne multivitamin", "90 capsules"), ProposalEdit(mag.id, true, mag.title, null, addAnyway = true)),
            CHICAGO,
        )
        assertEquals(listOf("Order the Thorne multivitamin", mag.title), plan.steps.map { it.title })
        assertTrue(plan.notesAfter!!.contains("Order the Thorne multivitamin — 90 capsules"))
        val outcome = actions.apply(plan) as ApplyOutcome2.Applied
        assertEquals(2, outcome.applied.size)
        val d = details()
        assertEquals(3, d.steps.size)
        assertTrue(d.task.notes!!.contains("From web research:"))
        assertEquals(listOf(multi.id, mag.id), store.get("s1")!!.applied.map { it.proposalId })
        assertTrue(d.events.any { it.type == EventType.EDITED || it.summary.contains("research", true) })
        // Nothing else about the task moved.
        assertNull(d.task.deadlineLocalDate); assertEquals(d.task.status, details().task.status); assertFalse(d.policy!!.enabled)
        // Same plan again: idempotent conflict, nothing added twice.
        val again = actions.apply(plan)
        assertTrue(again.toString(), again is ApplyOutcome2.Conflict)
        assertEquals(3, details().steps.size)
        // Re-planning after apply marks them applied.
        val replan = actions.plan(store.get("s1")!!, details(), edits(multi, mag), CHICAGO)
        assertTrue(replan.rows.all { it is ProposalRow.Skip && it.reason == "Already applied" })
    }

    @Test fun undoRevertsExactlyThisApplyAndOnlyWhileTheTaskIsUntouched() = runTest {
        val multi = session.content.proposals.first { it.requirementId == "r-i0" }
        val before = details()
        val plan = actions.plan(session, before, edits(multi), CHICAGO)
        val outcome = actions.apply(plan) as ApplyOutcome2.Applied
        val token = outcome.undo!!
        assertTrue(actions.canUndo(token, details()))
        clock.advance(Duration.ofSeconds(5))
        assertTrue(actions.undo(token))
        val after = details()
        assertEquals(before.steps.map { it.title }, after.steps.map { it.title })
        assertEquals("Monthly order", after.task.notes)
        assertEquals(emptyList<AppliedProposal>(), store.get("s1")!!.applied)
        // Undo twice does nothing more; a changed task refuses undo.
        assertFalse(actions.undo(token))
        val plan2 = actions.plan(store.get("s1")!!, details(), edits(multi), CHICAGO)
        val o2 = actions.apply(plan2) as ApplyOutcome2.Applied
        clock.advance(Duration.ofSeconds(5))
        tasks.addStep(taskId, "Something else")
        assertFalse(actions.canUndo(o2.undo!!, details()))
        assertFalse(actions.undo(o2.undo!!))
    }

    @Test fun emptySelectionAndStaleTaskAreRejectedWithoutChanges() = runTest {
        val plan = actions.plan(session, details(), emptyList(), CHICAGO)
        assertTrue(actions.apply(plan) is ApplyOutcome2.Rejected)
        val multi = session.content.proposals.first { it.requirementId == "r-i0" }
        val stale = actions.plan(session, details(), edits(multi), CHICAGO)
        clock.advance(Duration.ofSeconds(1))
        tasks.addStep(taskId, "Changed in between")
        assertTrue(actions.apply(stale) is ApplyOutcome2.Conflict)
        assertEquals(2, details().steps.size)
        assertEquals(emptyList<AppliedProposal>(), store.get("s1")!!.applied)
    }
}
