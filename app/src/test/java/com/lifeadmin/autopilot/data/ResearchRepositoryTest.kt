package com.lifeadmin.autopilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.research.AppliedMove
import com.lifeadmin.autopilot.ai.research.BriefContent
import com.lifeadmin.autopilot.ai.research.Finding
import com.lifeadmin.autopilot.ai.research.MoveKind
import com.lifeadmin.autopilot.ai.research.NextMove
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchUsage
import com.lifeadmin.autopilot.ai.research.Segment
import com.lifeadmin.autopilot.ai.research.Side
import com.lifeadmin.autopilot.ai.research.SourceRef
import com.lifeadmin.autopilot.ai.research.SourceTier
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.ResearchBriefEntity
import com.lifeadmin.autopilot.data.local.TaskEntity
import com.lifeadmin.autopilot.data.repository.BriefStatus
import com.lifeadmin.autopilot.data.repository.ResearchRepository
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.testing.FakeClock
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

@RunWith(AndroidJUnit4::class)
class ResearchRepositoryTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(Instant.parse("2026-10-20T15:00:00Z"))
    private lateinit var repo: ResearchRepository

    private val content = BriefContent(
        quickAnswer = listOf(Segment("Advance tickets go on sale 12 weeks ahead.", listOf(1))),
        moves = listOf(NextMove("m1", MoveKind.STEP, "Check the release date", "12 weeks out", listOf(1)), NextMove("m2", MoveKind.CHECK, "Confirm seat reservation", "nothing covered it")),
        findings = listOf(
            Finding.Sourced("Fares are released 12 weeks ahead.", listOf(1), SourceTier.OFFICIAL),
            Finding.Disagree("Release window", listOf(Side(1, "12 weeks"), Side(2, "10 weeks")), "Confirm with the operator."),
            Finding.Unconfirmed("Child fares", "no page covered it"),
            Finding.Suggestion("Cheapest at release."),
        ),
        sources = listOf(
            SourceRef(1, "https://www.northlinerail.example/tickets", "Tickets", "northlinerail.example", SourceTier.OFFICIAL, "September 2026", listOf("released 12 weeks ahead"), 3),
            SourceRef(2, "https://travelblog.example/tips", "Tips", "travelblog.example", SourceTier.WEB, null, listOf("10 weeks"), 1),
        ),
        queries = listOf("northline advance tickets"),
        usage = ResearchUsage(1000, 300, 1, "claude-haiku-5-5", 3000),
        verified = true,
        truncated = false,
        injectionNoticed = false,
        shared = listOf("Task title", "Your question"),
    )

    @Before fun setUp() = runTest {
        db = inMemoryDatabase()
        repo = ResearchRepository(db, clock, SequentialIds("b"))
        db.taskDao().upsert(task("t1"))
        db.taskDao().upsert(task("t2"))
    }

    @After fun tearDown() = db.close()

    private fun task(id: String) = TaskEntity(
        id, "Task $id", null, TaskStatus.ACTIONABLE, Priority.NORMAL, clock.now(), clock.now(), null, null,
        DeadlineType.NONE, null, null, null, null, true, null, null,
    )

    @Test fun saveRoundTripsEveryFindingTypeAndStatus() = runTest {
        val id = repo.save("t1", "  When do tickets go on sale?  ", content)
        val brief = repo.get(id)!!
        assertEquals("When do tickets go on sale?", brief.question)
        assertEquals(content, brief.content)
        assertEquals(BriefStatus.VERIFIED, brief.status)
        assertEquals(1, brief.searchCount)
        assertEquals(2, brief.sourceCount)
        assertEquals("claude-haiku-5-5", brief.model)
        assertTrue(brief.applied.isEmpty())
        assertFalse(brief.isStale(clock.now()))
        assertTrue(brief.isStale(clock.now().plus(ResearchConfig.STALE_AFTER).plusSeconds(1)))
        assertEquals(BriefStatus.UNVERIFIED, repo.get(repo.save("t1", "q", content.copy(verified = false)))!!.status)
        assertEquals(BriefStatus.TRUNCATED, repo.get(repo.save("t1", "q", content.copy(truncated = true)))!!.status)
        assertEquals(3, repo.observeCount().first())
        assertEquals(3, repo.observeForTask("t1").first().size)
        assertTrue(repo.observeForTask("t2").first().isEmpty())
    }

    @Test fun appliedMovesAreRecordedMergedAndCleared() = runTest {
        val id = repo.save("t1", "q", content)
        repo.recordApplied(id, listOf(AppliedMove("m1", 1L, "Check the release date", "s1")))
        repo.recordApplied(id, listOf(AppliedMove("m1", 2L, "dup", "s1"), AppliedMove("m2", 2L)))
        assertEquals(listOf("m1", "m2"), repo.get(id)!!.applied.map { it.moveId })
        assertEquals(1L, repo.get(id)!!.applied[0].atEpochMs) // first record wins
        repo.clearApplied(id, setOf("m1"))
        assertEquals(listOf("m2"), repo.get(id)!!.applied.map { it.moveId })
    }

    @Test fun oldestBriefsBeyondTheCapArePrunedPerTask() = runTest {
        repeat(ResearchConfig.MAX_BRIEFS_PER_TASK + 3) { i ->
            clock.instant = clock.instant.plus(Duration.ofMinutes(1))
            repo.save("t1", "q$i", content)
        }
        repo.save("t2", "other", content)
        val kept = repo.getForTask("t1")
        assertEquals(ResearchConfig.MAX_BRIEFS_PER_TASK, kept.size)
        assertEquals("newest first", "q${ResearchConfig.MAX_BRIEFS_PER_TASK + 2}", kept.first().question)
        assertEquals("q3", kept.last().question)
        assertEquals(1, repo.getForTask("t2").size)
    }

    @Test fun oversizedPayloadIsTrimmedNotRejected() = runTest {
        val huge = content.copy(
            findings = (1..400).map { Finding.Suggestion("Finding number $it " + "x".repeat(200)) },
            sources = content.sources + (3..12).map { SourceRef(it, "https://s$it.example/", "S$it", "s$it.example", SourceTier.WEB, null, listOf("q".repeat(150), "r".repeat(150)), 0) },
        )
        val id = repo.save("t1", "q", huge)
        val saved = repo.get(id)!!
        val bytes = db.researchDao().get(id)!!.payload.toByteArray().size
        assertTrue("payload $bytes should fit the cap", bytes <= ResearchConfig.MAX_PAYLOAD_BYTES)
        assertTrue(saved.content.findings.size < 400)
        assertEquals("uncited sources dropped first", listOf(1, 2), saved.content.sources.map { it.n })
        assertEquals("quick answer and moves survive", content.quickAnswer, saved.content.quickAnswer)
        assertEquals(content.moves, saved.content.moves)
    }

    @Test fun unreadableRowsAreHiddenInsteadOfCrashing() = runTest {
        db.researchDao().upsert(ResearchBriefEntity("bad", "t1", clock.now(), "q", "m", "VERIFIED", 1, 1, "{not json", "[]"))
        assertNull(repo.get("bad"))
        assertTrue(repo.getForTask("t1").isEmpty())
        repo.save("t1", "ok", content)
        assertEquals(1, repo.getForTask("t1").size)
    }

    @Test fun deleteAndCascade() = runTest {
        val a = repo.save("t1", "a", content)
        repo.save("t1", "b", content)
        repo.save("t2", "c", content)
        repo.delete(a)
        assertEquals(1, repo.getForTask("t1").size)
        db.taskDao().delete("t1")
        assertTrue("foreign key cascade", repo.getForTask("t1").isEmpty())
        assertEquals(1, repo.getAll().size)
        repo.deleteAll()
        assertTrue(repo.getAll().isEmpty())
    }

    @Test fun restoreKeepsIdsAndAppliedState() = runTest {
        val id = repo.save("t1", "q", content)
        repo.recordApplied(id, listOf(AppliedMove("m1", 5L, "Check the release date", "s1")))
        val brief = repo.get(id)!!
        repo.deleteAll()
        repo.restore(brief)
        assertEquals(brief, repo.get(id))
    }
}
