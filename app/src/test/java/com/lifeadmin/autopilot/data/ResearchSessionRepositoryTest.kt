package com.lifeadmin.autopilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.research2.AppliedProposal
import com.lifeadmin.autopilot.ai.research2.Claim
import com.lifeadmin.autopilot.ai.research2.ContractParser
import com.lifeadmin.autopilot.ai.research2.ProvenanceTag
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.RequirementExtractor
import com.lifeadmin.autopilot.ai.research2.ResearchSession
import com.lifeadmin.autopilot.ai.research2.SessionAssembler
import com.lifeadmin.autopilot.ai.research2.SessionContent
import com.lifeadmin.autopilot.ai.research2.SessionOutcome
import com.lifeadmin.autopilot.ai.research2.SourceRef2
import com.lifeadmin.autopilot.ai.research2.SourceRegistry2
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.ai.usage.UsageRecord
import com.lifeadmin.autopilot.ai.usage.UsageStatus
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.ResearchSessionEntity
import com.lifeadmin.autopilot.data.local.TaskEntity
import com.lifeadmin.autopilot.data.repository.ResearchSessionRepository
import com.lifeadmin.autopilot.data.repository.RoomUsageLedger
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.Research2Fixtures
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
    private lateinit var ledger: RoomUsageLedger
    private val now = Instant.parse("2026-10-20T15:00:00Z")

    @Before fun setUp() = runTest {
        db = inMemoryDatabase()
        repo = ResearchSessionRepository(db)
        ledger = RoomUsageLedger(db)
        db.taskDao().insert(TaskEntity("t1", "Supplements", null, TaskStatus.ACTIONABLE, Priority.NORMAL, now, now, null, null, DeadlineType.NONE, null, null, null, null, true, null, null))
    }

    @After fun tearDown() { db.close() }

    private fun content(): SessionContent {
        val r = RequirementExtractor.extract(Research2Fixtures.SUPPLEMENTS_REQUEST)
        val registry = SourceRegistry2(runId = "run1")
        var s = SessionContent("t", Research2Fixtures.SUPPLEMENTS_REQUEST, r.template, r.requirements, outcome = SessionOutcome.INTERRUPTED)
        val fx = Research2Fixtures.supplementsGather()
        s = SessionAssembler.merge(s, ContractParser.parse(fx.segments, fx.searches, registry, r.requirements, now.toEpochMilli()), registry.sources())
        return SessionAssembler.settlePending(s).copy(outcome = SessionOutcome.INCOMPLETE_EVIDENCE)
    }

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
        val entity = db.researchSessionDao().get("s1")!!
        assertEquals("PICK_PER_ITEM", entity.template)
        assertEquals("INCOMPLETE_EVIDENCE", entity.outcome)
        assertEquals(4, entity.sourceCount)
        assertTrue("payload never carries the key or prompt text", !entity.payload.contains("You are a careful research assistant"))
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

    @Test fun oldestSessionsBeyondTheCapArePrunedPerTask() = runTest {
        for (i in 0 until Research2Config.MAX_SESSIONS_PER_TASK + 3) repo.save(session("s$i").copy(updatedAt = now.plusSeconds(i.toLong())))
        val kept = repo.getForTask("t1")
        assertEquals(Research2Config.MAX_SESSIONS_PER_TASK, kept.size)
        assertEquals("s${Research2Config.MAX_SESSIONS_PER_TASK + 2}", kept.first().id)
        assertNull(repo.get("s0"))
    }

    @Test fun oversizedPayloadIsTrimmedNotRejected() = runTest {
        val big = content().copy(
            claims = (1..1200).map { Claim("claim $it ".repeat(40), ProvenanceTag.JUDGMENT) },
            sources = (1..60).map { SourceRef2(it, "https://x$it.example/p", "T$it", "x$it.example", false, null, listOf("q".repeat(150), "r".repeat(150)), if (it <= 3) 1 else 0) },
        )
        val fitted = repo.fit(big)
        assertTrue(repo.encode(fitted).toByteArray().size <= Research2Config.MAX_PAYLOAD_BYTES)
        assertEquals("cited sources survive", 3, fitted.sources.size)
        assertEquals(4, fitted.body.picks.size)
        repo.save(session(c = big))
        assertTrue(repo.get("s1")!!.content.claims.size < 1200)
    }

    @Test fun unreadableRowsAreHiddenAndCascadeDeletesWithTheTask() = runTest {
        repo.save(session())
        db.researchSessionDao().upsert(ResearchSessionEntity("bad", "t1", now, now, "x", "X", "X", "", 0, 0, "{not json", "[]"))
        assertEquals(listOf("s1"), repo.getForTask("t1").map { it.id })
        db.taskDao().delete("t1")
        assertEquals(0, repo.observeCount().first())
    }

    @Test fun roomLedgerRecordsDeduplicatesSettlesAndPrunes() = runTest {
        val base = UsageRecord("c1", UsageFeature.RESEARCH, "claude-sonnet-5-5", now, runId = "r1", sessionId = "s1", taskId = "t1", stage = "GATHER")
        ledger.record(base)
        assertEquals(CallOutcome.IN_FLIGHT, ledger.get("c1")!!.outcome)
        ledger.record(UsageRecord.priced(base, MeasuredUsage(41_000, 2_600, 0, 0, 0, 5), CallOutcome.COMPLETE, 42_000, "msg_1"))
        val r = ledger.get("c1")!!
        assertEquals(UsageStatus.COMPLETE, r.usageStatus)
        assertEquals(5, r.usage!!.webSearches)
        assertEquals(158_000L, r.estimatedMicros)
        // Duplicate provider response under a new id replaces, never adds.
        ledger.record(UsageRecord.priced(base.copy(id = "c1-dup"), MeasuredUsage(41_000, 2_600, 0, 0, 0, 5), CallOutcome.COMPLETE, 42_000, "msg_1"))
        assertEquals(1, ledger.forSession("s1").size)
        assertEquals("c1-dup", ledger.forSession("s1")[0].id)
        // Process death: in-flight rows are settled as interrupted/unknown.
        ledger.record(UsageRecord("c2", UsageFeature.PLANNING, "claude-haiku-5-5", now.plusSeconds(60)))
        assertEquals(1, ledger.settleInterrupted())
        assertEquals(CallOutcome.INTERRUPTED, ledger.get("c2")!!.outcome)
        assertNull(ledger.get("c2")!!.estimatedMicros)
        assertEquals(2, ledger.observeAll().first().size)
        assertEquals(1, ledger.deleteOlderThan(now.plusSeconds(30)))
        assertEquals(listOf("c2"), ledger.getAll().map { it.id })
        assertFalse(ledger.restoreIfAbsent(UsageRecord("c2", UsageFeature.RESEARCH, "x", now)))
        assertEquals("append-only: an existing row is never overwritten by a restore", UsageFeature.PLANNING, ledger.get("c2")!!.feature)
        ledger.deleteAll()
        assertEquals(0, ledger.getAll().size)
    }

    @Test fun restoreNeverDuplicatesAProviderResponseUnderANewId() = runTest {
        val base = UsageRecord("u1", UsageFeature.RESEARCH, "claude-sonnet-5-5", now, runId = "r1", sessionId = "s1")
        ledger.record(UsageRecord.priced(base, MeasuredUsage(1_000, 100, 0, 0, 0, 1), CallOutcome.COMPLETE, 1_000, "msg_same"))
        // A backup made on another phone holds the same provider response under a different local id.
        assertFalse(ledger.restoreIfAbsent(UsageRecord.priced(base.copy(id = "u1-from-backup"), MeasuredUsage(1_000, 100, 0, 0, 0, 1), CallOutcome.COMPLETE, 1_000, "msg_same")))
        assertEquals(listOf("u1"), ledger.getAll().map { it.id })
        assertTrue(ledger.restoreIfAbsent(UsageRecord.priced(base.copy(id = "u2"), MeasuredUsage(500, 50, 0, 0, 0, 0), CallOutcome.COMPLETE, 800, "msg_other")))
        assertEquals(2, ledger.getAll().size)
        assertEquals("totals count each provider response once", 2, com.lifeadmin.autopilot.ai.usage.UsageTotals.of(ledger.getAll()).calls)
    }
}
