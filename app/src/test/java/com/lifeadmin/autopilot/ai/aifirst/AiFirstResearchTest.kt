package com.lifeadmin.autopilot.ai.aifirst

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.CallStop
import com.lifeadmin.autopilot.ai.research2.ModelCall
import com.lifeadmin.autopilot.ai.research2.ResearchCallProvider
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.InMemoryUsageLedger
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Boundary tests for the experimental AI-first engine: payload, provenance, budgets, ledger and persistence. */
class AiFirstResearchTest {
    private val clock = AppClock { Instant.parse("2026-10-24T12:00:00Z") }
    private val n = AtomicInteger()
    private val ids = IdGenerator { "id${n.incrementAndGet()}" }
    private val ledger = InMemoryUsageLedger()
    private val store = InMemoryAfThreadStore()
    private val cfg = AfConfig("claude-sonnet-5-5", searchBudget = 4, spendLimitCents = 300)

    private class Scripted(private val replies: List<(ModelCall) -> CallResult>) : ResearchCallProvider {
        override val id = "scripted"
        val calls = ArrayList<ModelCall>()
        var beforeEach: (suspend () -> Unit)? = null
        override suspend fun call(call: ModelCall, apiKey: String): CallResult {
            beforeEach?.invoke()
            calls += call
            return replies[calls.size - 1](call)
        }
    }

    private fun done(segments: List<RawSegment>, searches: List<RawSearch> = emptyList(), stop: CallStop = CallStop.END, webSearches: Int = searches.size) =
        CallResult.Done(segments, searches, MeasuredUsage(1_000, 500, webSearches = webSearches), stop, 1_000, "msg-${n.incrementAndGet()}", paused = if (stop == CallStop.PAUSE_TURN) "opaque" else null)

    private val search = RawSearch("union station dc address", listOf(RawSearchResult("https://amtrak.example/was", "Washington Union Station", "2 months ago")))

    @Test fun citationsAreTheProvidersAndUnsuppliedLinksAreFlagged() = runBlocking {
        val p = Scripted(listOf({ _ ->
            done(
                listOf(
                    RawSegment("Union Station is at 50 Massachusetts Ave NE.", listOf(RawCitation("https://amtrak.example/was", "Washington Union Station", "50 Massachusetts Avenue NE"))),
                    RawSegment(" See https://made-up.example/page for more.", emptyList()),
                ),
                listOf(search),
            )
        }))
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val turn = engine.run("t1", engine.authorize(null, "Address of Union Station, Washington DC?", emptyList(), cfg), "key")

        assertEquals(AfStatus.COMPLETE, turn.status)
        assertTrue(turn.answer.startsWith("Union Station is at 50 Massachusetts Ave NE. [1]"))
        assertEquals(listOf(AfSource(1, "https://amtrak.example/was", "Washington Union Station", listOf("50 Massachusetts Avenue NE"), "2 months ago")), turn.sources)
        assertEquals(listOf("https://made-up.example/page"), turn.unsuppliedLinks)
        val rows = ledger.getAll()
        assertEquals(1, rows.size)
        assertEquals(CallOutcome.COMPLETE, rows.single().outcome)
        assertEquals(turn.costMicros, rows.single().estimatedMicros)
        assertEquals(4, p.calls.single().maxSearches)
    }

    @Test fun pauseTurnContinuesWithTheRemainingBudgetOnly() = runBlocking {
        val p = Scripted(listOf(
            { _ -> done(emptyList(), listOf(search, search, search), stop = CallStop.PAUSE_TURN) },
            { _ -> done(listOf(RawSegment("Answer.", emptyList())), listOf(search)) },
        ))
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val turn = engine.run("t1", engine.authorize(null, "q", emptyList(), cfg), "key")
        assertEquals(listOf(4, 1), p.calls.map { it.maxSearches })
        assertEquals("opaque", p.calls[1].prior)
        assertEquals(4, turn.webSearches)
        assertEquals(2, ledger.getAll().size)
    }

    @Test fun pausedWithNoSearchesLeftStopsAndSaysSo() = runBlocking {
        val p = Scripted(listOf({ _ -> done(listOf(RawSegment("Partial.", emptyList())), List(4) { search }, stop = CallStop.PAUSE_TURN) }))
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val turn = engine.run("t1", engine.authorize(null, "q", emptyList(), cfg), "key")
        assertEquals(1, p.calls.size)
        assertEquals(AfStatus.SEARCH_BUDGET_REACHED, turn.status)
        assertTrue(turn.note!!.contains("4 of 4"))
    }

    @Test fun spendingLimitStopsBeforeACallThatCouldPassIt() = runBlocking {
        val p = Scripted(emptyList())
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val turn = engine.run("t1", engine.authorize(null, "q", emptyList(), cfg.copy(spendLimitCents = 1)), "key")
        assertEquals(AfStatus.SPEND_LIMIT_REACHED, turn.status)
        assertTrue(p.calls.isEmpty())
        assertTrue(ledger.getAll().isEmpty())
    }

    @Test fun followUpSendsOnlyThisThreadAndTheAuthorizedContext() = runBlocking {
        val p = Scripted(listOf(
            { _ -> done(listOf(RawSegment("Thorne has iron; Pure does not.", listOf(RawCitation("https://amtrak.example/was", "T", "iron")))), listOf(search)) },
            { _ -> done(listOf(RawSegment("Pick Pure.", emptyList()))) },
            { _ -> done(listOf(RawSegment("Other thread.", emptyList()))) },
        ))
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        engine.run("a", engine.authorize(null, "Compare Thorne and Pure", listOf("Today is 2026-10-24"), cfg), "key")
        engine.run("b", engine.authorize(null, "Unrelated secret question", emptyList(), cfg), "key")
        val followUp = engine.authorize(store.get("a"), "Which for someone avoiding iron?", emptyList(), cfg)
        assertTrue(followUp.includesHistory)
        engine.run("a", followUp, "key")
        val sent = p.calls[2].user
        assertTrue(sent.contains("Compare Thorne and Pure") && sent.contains("Thorne has iron; Pure does not. [1]") && sent.contains("Which for someone avoiding iron?"))
        assertFalse(sent.contains("Unrelated secret question"))
        assertFalse(sent.contains("Today is 2026-10-24")) // context is per authorization, not inherited silently
        assertEquals(2, store.get("a")!!.turns.size)
    }

    @Test fun theTurnIsSavedBeforeSendingAndAFailureKeepsWhatRan() = runBlocking {
        lateinit var p: Scripted
        p = Scripted(listOf(
            { _ -> done(listOf(RawSegment("Half an answer.", emptyList())), listOf(search), stop = CallStop.PAUSE_TURN) },
            { _ -> CallResult.Failed(ResearchError.Network, searchesSeen = 1, usage = null, elapsedMs = 10) },
        ))
        var savedBeforeFirstCall = false
        p.beforeEach = { if (p.calls.isEmpty()) savedBeforeFirstCall = store.get("t1")?.turns?.singleOrNull()?.status == AfStatus.RUNNING }
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val turn = engine.run("t1", engine.authorize(null, "q", emptyList(), cfg), "key")
        assertTrue(savedBeforeFirstCall)
        assertEquals(AfStatus.FAILED, turn.status)
        assertEquals("Half an answer.", turn.answer)
        assertTrue(turn.costIsFloor)
        assertEquals(setOf(CallOutcome.COMPLETE, CallOutcome.FAILED_UNKNOWN), ledger.getAll().map { it.outcome }.toSet())
        assertEquals(turn, store.get("t1")!!.turns.single())
    }

    @Test fun cancellationIsLedgeredAndThePartialTurnSaved() = runBlocking {
        val p = Scripted(listOf({ _ -> throw CancellationException("stop") }))
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val result = runCatching { engine.run("t1", engine.authorize(null, "q", emptyList(), cfg), "key") }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(AfStatus.STOPPED, store.get("t1")!!.turns.single().status)
        assertEquals(CallOutcome.STOPPED, ledger.getAll().single().outcome)
    }
}
