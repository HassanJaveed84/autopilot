package com.lifeadmin.autopilot.ai.research

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ResearchBrief
import com.lifeadmin.autopilot.data.repository.ResearchRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ResearchActionsTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(Instant.parse("2026-10-20T15:00:00Z"))
    private lateinit var tasks: TaskRepository
    private lateinit var briefs: ResearchRepository
    private lateinit var actions: ResearchActions
    private lateinit var taskId: String
    private lateinit var brief: ResearchBrief

    private val content = BriefContent(
        quickAnswer = listOf(Segment("Advance tickets go on sale 12 weeks ahead.", listOf(1))),
        moves = listOf(
            NextMove("m1", MoveKind.STEP, "Check the 12-week release date", "12 weeks out", listOf(1)),
            NextMove("m2", MoveKind.CHECK, "Whether the fare includes a seat reservation", "nothing covered it"),
            NextMove("m3", MoveKind.NOTE, "Advance fares are released 12 weeks before travel", "From your research", listOf(1)),
            NextMove("m4", MoveKind.STEP, "Find dates", "duplicate of an existing step"),
            NextMove("m5", MoveKind.STEP, "Compare fares on the operator site", "cheapest at release"),
        ),
        findings = emptyList(),
        sources = listOf(SourceRef(1, "https://www.northlinerail.example/tickets", "Tickets", "northlinerail.example", SourceTier.OFFICIAL, null, emptyList(), 2)),
        queries = listOf("q"), usage = ResearchUsage(1, 1, 1, "claude-haiku-5-5", 1), verified = true, truncated = false, injectionNoticed = false, shared = emptyList(),
    )

    @Before fun setUp() = runTest {
        db = inMemoryDatabase()
        tasks = TaskRepository(db, clock, SequentialIds("t"), { CHICAGO }, ScheduleCoordinator.Direct)
        briefs = ResearchRepository(db, clock, SequentialIds("b"))
        actions = ResearchActions(tasks, briefs, clock)
        taskId = tasks.createTask(NewTask("Book train ride", notes = "Window seat", status = TaskStatus.ACTIONABLE))
        tasks.addStep(taskId, "Find dates")
        brief = briefs.get(briefs.save(taskId, "When do tickets go on sale?", content))!!
    }

    @After fun tearDown() = db.close()

    private suspend fun details(): TaskDetails = tasks.observeDetails(taskId).first()!!

    @Test fun planDeduplicatesAndShapesRows() = runTest {
        val plan = actions.plan(brief, details(), setOf("m1", "m2", "m3", "m4", "m5"))
        assertEquals(listOf("Check the 12-week release date", "Check: Whether the fare includes a seat reservation", "Compare fares on the operator site"), plan.steps.map { it.title })
        assertEquals(listOf("• Advance fares are released 12 weeks before travel"), plan.notes.map { it.line })
        assertEquals(listOf("m4" to "A step like this already exists"), plan.skipped.map { it.move.id to it.reason })
        assertEquals("Window seat\n\nFrom web research:\n• Advance fares are released 12 weeks before travel", plan.notesAfter)
        assertEquals(4, plan.changeCount)
        assertTrue(plan.proposalId.startsWith("research:${brief.id}:"))
        assertEquals("selection order does not matter", plan.proposalId, actions.plan(brief, details(), setOf("m5", "m3", "m2", "m1")).proposalId)
        assertTrue(actions.plan(brief, details(), setOf("m1")).proposalId != plan.proposalId)
        assertTrue(actions.plan(brief, details(), setOf("m4")).isEmpty)
    }

    @Test fun applyIsTransactionalIdempotentAndRecorded() = runTest {
        val plan = actions.plan(brief, details(), setOf("m1", "m3"))
        val outcome = actions.apply(plan) as ApplyOutcome.Applied
        val d = details()
        assertEquals(listOf("Find dates", "Check the 12-week release date"), d.steps.map { it.title })
        assertEquals("Window seat\n\nFrom web research:\n• Advance fares are released 12 weeks before travel", d.task.notes)
        assertEquals("deadline/priority/status untouched", Priority.NORMAL, d.task.priority)
        assertEquals(TaskStatus.ACTIONABLE, d.task.status)
        assertTrue("no reminders were created", d.policy?.enabled != true)
        assertEquals(listOf("m1", "m3"), outcome.applied.map { it.moveId })
        assertEquals(d.steps[1].id, outcome.applied[0].stepId)
        assertEquals(listOf("m1", "m3"), briefs.get(brief.id)!!.applied.map { it.moveId })
        assertTrue(d.events.any { it.type == EventType.EDITED && it.metadata["source"] == "ai_research" && it.metadata["proposalId"] == plan.proposalId })
        // Same plan again: rejected as already applied, nothing duplicated.
        val again = actions.apply(plan)
        assertTrue(again.toString(), again is ApplyOutcome.Conflict)
        assertEquals(2, details().steps.size)
        // A fresh plan after the apply skips what was applied and dedupes against the new step.
        val next = actions.plan(briefs.get(brief.id)!!, details(), setOf("m1", "m3", "m5"))
        assertEquals(listOf("m1" to "Already applied", "m3" to "Already applied"), next.skipped.map { it.move.id to it.reason })
        assertEquals(listOf("Compare fares on the operator site"), next.steps.map { it.title })
        assertTrue(actions.apply(next) is ApplyOutcome.Applied)
        assertEquals(3, details().steps.size)
    }

    @Test fun applyRefusesWhenTheTaskChangedUnderneath() = runTest {
        val plan = actions.plan(brief, details(), setOf("m1"))
        clock.instant = clock.instant.plus(Duration.ofMinutes(1))
        tasks.addStep(taskId, "Something else")
        val outcome = actions.apply(plan)
        assertTrue(outcome.toString(), outcome is ApplyOutcome.Conflict)
        assertEquals(listOf("Find dates", "Something else"), details().steps.map { it.title })
        assertTrue(briefs.get(brief.id)!!.applied.isEmpty())
    }

    @Test fun applyRefusesOnDoneTasks() = runTest {
        tasks.complete(taskId)
        val plan = actions.plan(brief, details(), setOf("m1"))
        val outcome = actions.apply(plan)
        assertTrue(outcome.toString(), outcome is ApplyOutcome.Rejected)
        assertEquals(1, details().steps.size)
    }

    @Test fun undoRestoresOnlyWhileSafe() = runTest {
        val applied = actions.apply(actions.plan(brief, details(), setOf("m1", "m3"))) as ApplyOutcome.Applied
        val token = applied.undo!!
        assertTrue(actions.canUndo(token, details()))
        clock.instant = clock.instant.plus(Duration.ofSeconds(30))
        assertTrue(actions.undo(token))
        val d = details()
        assertEquals(listOf("Find dates"), d.steps.map { it.title })
        assertEquals("Window seat", d.task.notes)
        assertTrue(briefs.get(brief.id)!!.applied.isEmpty())
        assertFalse("already undone", actions.canUndo(token, details()))
        assertFalse(actions.undo(token))

        // Apply again, then edit the task: undo is no longer offered and refuses.
        val second = actions.apply(actions.plan(briefs.get(brief.id)!!, details(), setOf("m1"))) as ApplyOutcome.Applied
        clock.instant = clock.instant.plus(Duration.ofMinutes(2))
        tasks.setStepDone(second.applied[0].stepId!!, true)
        assertFalse(actions.canUndo(second.undo!!, details()))
        assertFalse(actions.undo(second.undo!!))
        assertEquals(2, details().steps.size)
    }

    @Test fun notesAreAppendedUnderOneHeaderAndNeverReplaced() = runTest {
        actions.apply(actions.plan(brief, details(), setOf("m3")))
        val secondBrief = briefs.get(briefs.save(taskId, "q2", content.copy(moves = listOf(NextMove("n1", MoveKind.NOTE, "Child fares are half price", "x")))))!!
        actions.apply(actions.plan(secondBrief, details(), setOf("n1")))
        assertEquals("Window seat\n\nFrom web research:\n• Advance fares are released 12 weeks before travel\n\n• Child fares are half price", details().task.notes)
        val third = actions.plan(briefs.get(secondBrief.id)!!, details(), setOf("n1"))
        assertEquals(listOf("Already in your notes"), actions.plan(secondBrief, details(), setOf("n1")).skipped.map { it.reason }) // stale brief object
        assertEquals(listOf("Already applied"), third.skipped.map { it.reason })
    }

    /** Bookkeeping that fails inside the transaction must take the task edit down with it (finding C). */
    private class FailingBriefs(db: com.lifeadmin.autopilot.data.local.AppDatabase, clock: FakeClock, var failRecord: Boolean = false, var failClear: Boolean = false) :
        ResearchRepository(db, clock, SequentialIds("f")) {
        override suspend fun recordApplied(briefId: String, applied: List<AppliedMove>) {
            if (failRecord) throw IllegalStateException("disk full")
            super.recordApplied(briefId, applied)
        }
        override suspend fun clearApplied(briefId: String, moveIds: Set<String>) {
            if (failClear) throw IllegalStateException("disk full")
            super.clearApplied(briefId, moveIds)
        }
    }

    @Test fun bookkeepingFailureRollsBackTheTaskEditAndARetrySucceedsOnce() = runTest {
        val failing = FailingBriefs(db, clock, failRecord = true)
        val flaky = ResearchActions(tasks, failing, clock)
        val plan = flaky.plan(brief, details(), setOf("m1", "m3"))
        val outcome = flaky.apply(plan)
        assertTrue(outcome.toString(), outcome is ApplyOutcome.Rejected)
        val d = details()
        assertEquals("steps rolled back", listOf("Find dates"), d.steps.map { it.title })
        assertEquals("notes rolled back", "Window seat", d.task.notes)
        assertTrue("no history event either", d.events.none { it.metadata["proposalId"] == plan.proposalId })
        assertTrue(briefs.get(brief.id)!!.applied.isEmpty())
        // The task is untouched, so the same plan applies cleanly on retry — exactly once.
        failing.failRecord = false
        val retry = flaky.apply(flaky.plan(briefs.get(brief.id)!!, details(), setOf("m1", "m3")))
        assertTrue(retry.toString(), retry is ApplyOutcome.Applied)
        assertEquals(listOf("Find dates", "Check the 12-week release date"), details().steps.map { it.title })
        assertEquals(listOf("m1", "m3"), briefs.get(brief.id)!!.applied.map { it.moveId })
        assertTrue(flaky.apply(plan) is ApplyOutcome.Conflict) // and never twice
        assertEquals(2, details().steps.size)
    }

    @Test fun undoBookkeepingFailureRollsBackTheUndo() = runTest {
        val failing = FailingBriefs(db, clock, failClear = true)
        val flaky = ResearchActions(tasks, failing, clock)
        val applied = flaky.apply(flaky.plan(brief, details(), setOf("m1", "m3"))) as ApplyOutcome.Applied
        clock.instant = clock.instant.plus(Duration.ofSeconds(5))
        assertFalse(flaky.undo(applied.undo!!))
        val d = details()
        assertEquals("steps kept", 2, d.steps.size)
        assertTrue("notes kept", d.task.notes!!.contains("From web research:"))
        assertEquals("record kept, consistent with the task", listOf("m1", "m3"), briefs.get(brief.id)!!.applied.map { it.moveId })
        // Once bookkeeping works again the undo goes through, once.
        failing.failClear = false
        assertTrue(flaky.undo(applied.undo!!))
        assertEquals(listOf("Find dates"), details().steps.map { it.title })
        assertTrue(briefs.get(brief.id)!!.applied.isEmpty())
        assertFalse(flaky.undo(applied.undo!!))
    }

    @Test fun undoNeverOverwritesALaterUserEdit() = runTest {
        val applied = actions.apply(actions.plan(brief, details(), setOf("m1", "m3"))) as ApplyOutcome.Applied
        clock.instant = clock.instant.plus(Duration.ofSeconds(5))
        tasks.updateDetails(taskId, "Book train ride", details().task.notes + "\nMy own line", Priority.NORMAL)
        assertFalse(actions.canUndo(applied.undo!!, details()))
        assertFalse(actions.undo(applied.undo!!))
        val d = details()
        assertTrue(d.task.notes!!.endsWith("My own line"))
        assertEquals(2, d.steps.size)
    }
}
