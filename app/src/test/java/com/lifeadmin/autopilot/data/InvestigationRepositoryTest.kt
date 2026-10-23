package com.lifeadmin.autopilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.investigation.Answer
import com.lifeadmin.autopilot.ai.investigation.AnswerSegment
import com.lifeadmin.autopilot.ai.investigation.AskKind
import com.lifeadmin.autopilot.ai.investigation.Claim
import com.lifeadmin.autopilot.ai.investigation.ClaimStatus
import com.lifeadmin.autopilot.ai.investigation.ClaimType
import com.lifeadmin.autopilot.ai.investigation.EvidenceRef
import com.lifeadmin.autopilot.ai.investigation.Investigation
import com.lifeadmin.autopilot.ai.investigation.InvestigationMeta
import com.lifeadmin.autopilot.ai.investigation.InvestigationOrigin
import com.lifeadmin.autopilot.ai.investigation.Objective
import com.lifeadmin.autopilot.ai.investigation.ResultState
import com.lifeadmin.autopilot.ai.investigation.Turn
import com.lifeadmin.autopilot.ai.investigation.TurnEnding
import com.lifeadmin.autopilot.ai.investigation.TurnKind
import com.lifeadmin.autopilot.ai.investigation.TurnResult
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.InvestigationEntity
import com.lifeadmin.autopilot.data.local.TaskEntity
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Room v7: threads with distinct historical turns, standalone or task-linked, surviving task deletion and process death. */
@RunWith(AndroidJUnit4::class)
class InvestigationRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: InvestigationRepository
    private val now = Instant.parse("2026-10-23T09:00:00Z")

    @Before fun setUp() = runTest {
        db = inMemoryDatabase()
        repo = InvestigationRepository(db)
        db.taskDao().insert(TaskEntity("t1", "Renew passport", null, TaskStatus.ACTIONABLE, Priority.NORMAL, now, now, null, null, DeadlineType.NONE, null, null, null, null, true, null, null))
    }

    @After fun tearDown() { db.close() }

    private fun objective() = Objective("What does renewal cost?", "Find the renewal fee", AskKind.FACT)
    private fun result(claimText: String) = TurnResult(
        Answer("Fee", "Answered", listOf(AnswerSegment(claimText, listOf("c1")))), ResultState.ANSWERED, TurnEnding.COMPLETE,
        listOf(Claim("c1", claimText, ClaimType.FACT, loadBearing = true, support = listOf(EvidenceRef(1, claimText, retrievedAtMs = now.toEpochMilli(), originKey = "gov.example")), status = ClaimStatus.ESTABLISHED, turnId = "turn1")),
        emptyList(), emptyList(), finishedAtMs = now.toEpochMilli(),
    )
    private fun turn(id: String, index: Int, state: TurnState, kind: TurnKind = if (index == 0) TurnKind.INITIAL else TurnKind.CONTINUATION, res: TurnResult? = null) =
        Turn(id, index, kind, state, now.toEpochMilli() + index, objective(), result = res)
    private fun investigation(id: String = "inv1", taskId: String? = "t1", turns: List<Turn>) =
        Investigation(id, if (taskId == null) InvestigationOrigin.STANDALONE else InvestigationOrigin.TASK, taskId, null, now.toEpochMilli(), now.toEpochMilli(), InvestigationMeta("Passport fee", "What does renewal cost?"), turns)

    @Test fun saveRoundTripsTurnsInOrderAndTheListColumnsFollowTheLatestTurn() = runTest {
        repo.save(investigation(turns = listOf(turn("turn1", 0, TurnState.DONE, res = result("The fee is $130.")), turn("turn2", 1, TurnState.RUNNING))))
        val back = repo.get("inv1")!!
        assertEquals(listOf("turn1", "turn2"), back.turns.map { it.id })
        assertEquals("The fee is $130.", back.turns[0].result!!.claims[0].text)
        assertEquals(ClaimStatus.ESTABLISHED, back.turns[0].result!!.claims[0].status)
        assertTrue(back.running)
        val row = db.investigationDao().get("inv1")!!
        assertEquals("RUNNING", row.state)
        assertEquals(2, row.turnCount)
        assertEquals(listOf("inv1"), repo.observeForTask("t1").first().map { it.id })
        assertEquals(1, repo.running().size)
    }

    @Test fun savingOneTurnNeverTouchesEarlierTurnsAndRefreshesTheThread() = runTest {
        repo.save(investigation(turns = listOf(turn("turn1", 0, TurnState.DONE, res = result("The fee is $130.")))))
        repo.saveTurn("inv1", turn("turn2", 1, TurnState.RUNNING), now.plusSeconds(60))
        repo.saveTurn("inv1", turn("turn2", 1, TurnState.STOPPED, res = result("The fee is $130 as of October 2026.")), now.plusSeconds(120))
        val back = repo.get("inv1")!!
        assertEquals("The fee is $130.", back.turns[0].result!!.claims[0].text)
        assertEquals(TurnState.STOPPED, back.turns[1].state)
        assertEquals(now.plusSeconds(120).toEpochMilli(), back.updatedAtMs)
        assertEquals("STOPPED", db.investigationDao().get("inv1")!!.state)
        assertEquals(0, repo.running().size)
    }

    @Test fun standaloneThreadsExistAndAssociationIsAnExplicitRecordedEvent() = runTest {
        repo.save(investigation("solo", taskId = null, turns = listOf(turn("turn1", 0, TurnState.DONE, res = result("x")))))
        assertNull(repo.get("solo")!!.taskId)
        assertEquals(InvestigationOrigin.STANDALONE, repo.get("solo")!!.origin)
        repo.associate("solo", "t1", "Linked from the task", now.plusSeconds(5))
        val linked = repo.get("solo")!!
        assertEquals("t1", linked.taskId)
        assertEquals(listOf("Linked from the task"), linked.meta.associations.map { it.note })
        assertEquals(listOf("solo"), repo.getForTask("t1").map { it.id })
        repo.associate("solo", null, "Unlinked", now.plusSeconds(9))
        assertNull(repo.get("solo")!!.taskId)
        assertEquals(2, repo.get("solo")!!.meta.associations.size)
    }

    @Test fun deletingTheTaskKeepsTheThreadWithItsTurnsAsStandaloneHistory() = runTest {
        repo.save(investigation(turns = listOf(turn("turn1", 0, TurnState.DONE, res = result("x")), turn("turn2", 1, TurnState.DONE, res = result("y")))))
        db.taskDao().delete("t1")
        val back = repo.get("inv1")!!
        assertNull(back.taskId)
        assertEquals(2, back.turns.size)
        assertEquals(1, repo.observeCount().first())
    }

    @Test fun deletingAThreadRemovesItsTurnsAndUnreadableRowsAreHiddenNotDeleted() = runTest {
        repo.save(investigation(turns = listOf(turn("turn1", 0, TurnState.DONE, res = result("x")))))
        db.investigationDao().upsert(InvestigationEntity("bad", "TASK", "t1", null, now, now, "x", "DONE", 0, "{not json"))
        assertEquals(listOf("inv1"), repo.getForTask("t1").map { it.id })
        assertEquals(2, repo.observeCount().first())
        repo.delete("inv1")
        assertEquals(0, db.investigationTurnDao().getAll().count { it.investigationId == "inv1" })
        assertEquals(1, repo.observeCount().first())
    }

    @Test fun multipleThreadsPerTaskAreListedNewestFirst() = runTest {
        repo.save(investigation("a", turns = listOf(turn("a1", 0, TurnState.DONE, res = result("x")))).copy(updatedAtMs = now.toEpochMilli()))
        repo.save(investigation("b", turns = listOf(turn("b1", 0, TurnState.DONE, res = result("y")))).copy(updatedAtMs = now.plusSeconds(1).toEpochMilli()))
        assertEquals(listOf("b", "a"), repo.observeForTask("t1").first().map { it.id })
        assertEquals(listOf("b", "a"), repo.observeAll().first().map { it.id })
    }
}
