package com.lifeadmin.autopilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.aifirst.AfAuthorization
import com.lifeadmin.autopilot.ai.aifirst.AfConfig
import com.lifeadmin.autopilot.ai.aifirst.AfJson
import com.lifeadmin.autopilot.ai.aifirst.AfSegment
import com.lifeadmin.autopilot.ai.aifirst.AfSource
import com.lifeadmin.autopilot.ai.aifirst.AfStatus
import com.lifeadmin.autopilot.ai.aifirst.AfTurn
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.AfThreadRepository
import com.lifeadmin.autopilot.data.repository.BackupRepository
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.LegacySeed
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
import java.time.Instant

/**
 * AI-first threads share the `investigations` tables with research saved by the retired M4 engine.
 * Neither store misreads the other's rows, legacy research stays readable and read-only, and a
 * backup carries both.
 */
@RunWith(AndroidJUnit4::class)
class AfThreadRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var store: AfThreadRepository
    private lateinit var legacy: InvestigationRepository
    private val now = Instant.parse("2026-10-24T09:00:00Z")

    @Before fun setUp() { db = inMemoryDatabase(); store = AfThreadRepository(db); legacy = InvestigationRepository(db) }
    @After fun tearDown() { db.close() }

    private val auth = AfAuthorization("Address?", config = AfConfig("claude-sonnet-5-5", 3, 100), promptVersion = 2, payload = "Address?", payloadSha256 = AfJson.sha256("Address?"), authorizedAtMs = now.toEpochMilli())
    private fun answered(id: String, index: Int) = AfTurn(
        id, index, "Address?", now.toEpochMilli(), AfStatus.COMPLETE, authorization = auth, segments = listOf(AfSegment("At 50 Massachusetts Ave NE.", listOf(1))),
        answer = "At 50 Massachusetts Ave NE. [1]", sources = listOf(AfSource(1, "https://amtrak.example/was", "Amtrak", listOf("50 Massachusetts Ave NE"), "October 2026")),
        calls = 1, webSearches = 1, costMicros = 45_000, startedAtMs = now.toEpochMilli(), finishedAtMs = now.toEpochMilli() + 6_000,
    )

    @Test fun newAndLegacyThreadsLiveSideBySideWithoutMisreadingEachOther() = runTest {
        store.create("af1", null, "Address?", "Address?", answered("t0", 0), now)
        store.saveTurn("af1", answered("t1", 1))
        LegacySeed.m4Thread(legacy, null)

        val t = store.get("af1")!!
        assertEquals(listOf("t0", "t1"), t.turns.map { it.id })
        assertEquals(answered("t1", 1), t.turns[1])
        assertEquals("AI_FIRST", db.investigationTurnDao().get("t1")!!.kind)
        assertEquals("COMPLETE", db.investigationDao().get("af1")!!.state)
        assertEquals(2, db.investigationDao().get("af1")!!.turnCount)

        assertNull("the new store never reads a legacy thread", store.get(LegacySeed.ID))
        assertNull("the legacy store never reads a new thread", legacy.get("af1"))
        assertEquals(LegacySeed.m4Investigation(null), legacy.get(LegacySeed.ID))
        assertTrue(store.isLegacy(LegacySeed.ID))
        assertFalse(store.isLegacy("af1"))
        assertEquals(listOf(LegacySeed.ID), legacy.getAll().map { it.id })

        val summaries = store.observeSummaries().first().associateBy { it.id }
        assertEquals(setOf("af1", LegacySeed.ID), summaries.keys)
        assertFalse(summaries["af1"]!!.legacy)
        assertEquals("At 50 Massachusetts Ave NE.", summaries["af1"]!!.preview)
        assertEquals(1, summaries["af1"]!!.sources)
        assertTrue(summaries[LegacySeed.ID]!!.legacy)
        assertEquals("Answered", summaries[LegacySeed.ID]!!.preview)
    }

    @Test fun legacyResearchIsNeverRewrittenByTheNewStore() = runTest {
        LegacySeed.m4Thread(legacy, null)
        val before = db.investigationTurnDao().forInvestigation(LegacySeed.ID)
        assertTrue(runCatching { store.saveTurn(LegacySeed.ID, answered("x", 1)) }.isFailure)
        assertEquals(before, db.investigationTurnDao().forInvestigation(LegacySeed.ID))
        // Association is the one change allowed on legacy research, and keeps its own meta format.
        store.associate(LegacySeed.ID, null, "Unlinked", now)
        assertEquals("Unlinked", legacy.get(LegacySeed.ID)!!.meta.associations.single().note)
    }

    @Test fun aBackupCarriesBothFormatsAndSettlesARunningTurn() = runTest {
        store.create("af1", null, "Address?", "Address?", answered("t0", 0), now)
        store.saveTurn("af1", answered("t1", 1).copy(status = AfStatus.RUNNING, finishedAtMs = null))
        LegacySeed.m4Thread(legacy, null)
        val clock = FakeClock(now)
        val json = BackupRepository(db, clock, SequentialIds("b"), "test", afterImport = {}).exportJson()

        val target = inMemoryDatabase()
        try {
            BackupRepository(target, clock, SequentialIds("i"), "test", afterImport = {}).importJson(json)
            val t = AfThreadRepository(target).get("af1")!!
            assertEquals(answered("t0", 0), t.turns[0])
            assertEquals(AfStatus.INTERRUPTED, t.turns[1].status)
            assertTrue(t.turns[1].costIsFloor)
            assertEquals(LegacySeed.m4Investigation(null), InvestigationRepository(target).get(LegacySeed.ID))
        } finally { target.close() }

        // A corrupted AI-first turn rejects the whole import rather than being half-read.
        val broken = json.replace("\\\"status\\\":\\\"COMPLETE\\\"", "\\\"status\\\":\\\"NOT_A_STATUS\\\"")
        assertTrue(broken != json)
        val target2 = inMemoryDatabase()
        try {
            assertTrue(runCatching { BackupRepository(target2, clock, SequentialIds("j"), "test", afterImport = {}).importJson(broken) }.isFailure)
            assertTrue(target2.investigationDao().getAll().isEmpty())
        } finally { target2.close() }
    }
}
