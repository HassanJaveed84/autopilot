package com.lifeadmin.autopilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.research2.AppliedProposal
import com.lifeadmin.autopilot.ai.research2.Claim
import com.lifeadmin.autopilot.ai.research2.ProvenanceTag
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.ResearchSession
import com.lifeadmin.autopilot.ai.research2.SessionContent
import com.lifeadmin.autopilot.ai.research2.SessionOutcome
import com.lifeadmin.autopilot.ai.research2.SourceRef2
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.InvestigationEntity
import com.lifeadmin.autopilot.data.local.TaskEntity
import com.lifeadmin.autopilot.data.repository.ResearchSessionRepository
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.SavedResearchFixtures
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
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ResearchSessionRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ResearchSessionRepository
    private val now = Instant.parse("2026-10-20T15:00:00Z")

    @Before fun setUp() = runTest {
        db = inMemoryDatabase()
        repo = ResearchSessionRepository(db)
        db.taskDao().insert(TaskEntity("t1", "Supplements", null, TaskStatus.ACTIONABLE, Priority.NORMAL, now, now, null, null, DeadlineType.NONE, null, null, null, null, true, null, null))
    }

    @After fun tearDown() { db.close() }

    private fun content(): SessionContent = SavedResearchFixtures.m3a2Session()

    private fun session(id: String = "s1", c: SessionContent = content()) = ResearchSession(id, "t1", now, now, c)

    @Test fun saveRoundTripsTheWholeStructuredResult() = runTest {
        val s = session()
        repo.save(s)
        val back = repo.get("s1")!!
        assertEquals(s.content, back.content)
        assertEquals(4, back.content.body.picks.size)
        assertEquals(ProvenanceTag.REVIEWS, back.content.claims[0].tag)
        assertEquals(1, repo.observeCount().first())
        assertEquals(listOf("s1"), repo.observeForTask("t1").first().map { it.id })
        val entity = db.investigationDao().get("s1")!!
        assertEquals("a legacy session is one investigation on the task", "t1", entity.taskId)
        assertEquals("TASK", entity.origin)
        assertEquals("DONE", entity.state)
        val turns = db.investigationTurnDao().forInvestigation("s1")
        assertEquals("one turn per run; no runs yet means one placeholder turn", 1, turns.size)
        assertTrue("payload never carries the key or prompt text", !turns[0].payload.contains("You are a careful research assistant"))
    }

    @Test fun appliedProposalsAreRecordedMergedAndCleared() = runTest {
        repo.save(session())
        repo.recordApplied("s1", listOf(AppliedProposal("p-a", 1L, "Buy multivitamin", "step-1")))
        repo.recordApplied("s1", listOf(AppliedProposal("p-a", 2L, "dup", null), AppliedProposal("p-b", 2L, "Buy vitamin D", "step-2")))
        assertEquals(listOf("p-a", "p-b"), repo.get("s1")!!.applied.map { it.proposalId })
        assertEquals(1L, repo.get("s1")!!.applied[0].atEpochMs)
        repo.clearApplied("s1", setOf("p-a"))
        assertEquals(listOf("p-b"), repo.get("s1")!!.applied.map { it.proposalId })
    }

    @Test fun aContinuationSavedAgainMapsOntoTheSameTurnRowsAndKeepsEveryRun() = runTest {
        val c = content()
        val run1 = com.lifeadmin.autopilot.ai.research2.RunRecord("r1", com.lifeadmin.autopilot.ai.research2.RunKind.INITIAL, 1L, com.lifeadmin.autopilot.ai.research2.ResearchMode.THOROUGH, "claude-sonnet-5-5", 10, outcome = SessionOutcome.COMPLETE)
        val run2 = run1.copy(id = "r2", kind = com.lifeadmin.autopilot.ai.research2.RunKind.CONTINUATION, startedAtEpochMs = 2L, outcome = SessionOutcome.STOPPED)
        repo.save(session(c = c.copy(runs = listOf(run1), outcome = SessionOutcome.COMPLETE)))
        assertEquals(listOf("s1-run1"), db.investigationTurnDao().forInvestigation("s1").map { it.id })
        repo.save(session(c = c.copy(runs = listOf(run1, run2), outcome = SessionOutcome.STOPPED)))
        val turns = db.investigationTurnDao().forInvestigation("s1")
        assertEquals(listOf("s1-run1", "s1-run2"), turns.map { it.id })
        assertEquals(listOf("INITIAL", "CONTINUATION"), turns.map { it.kind })
        assertEquals(listOf("DONE", "STOPPED"), turns.map { it.state })
        assertEquals("STOPPED", db.investigationDao().get("s1")!!.state)
        assertEquals(2, db.investigationDao().get("s1")!!.turnCount)
        assertEquals(listOf("r1", "r2"), repo.get("s1")!!.content.runs.map { it.id })
    }

    @Test fun unreadableRowsAreHiddenAndADeletedTaskLeavesTheThreadAsStandaloneHistory() = runTest {
        repo.save(session())
        db.investigationDao().upsert(InvestigationEntity("bad", "TASK", "t1", null, now, now, "x", "DONE", 0, "{not json"))
        assertEquals(listOf("s1"), repo.getForTask("t1").map { it.id })
        db.taskDao().delete("t1")
        assertEquals("nothing is deleted with the task", 2, repo.observeCount().first())
        assertNull("the legacy view needs a task; the thread itself is still there", repo.get("s1"))
        assertNull(db.investigationDao().get("s1")!!.taskId)
        assertEquals(1, db.investigationTurnDao().forInvestigation("s1").size)
    }
}
