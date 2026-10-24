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
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** The research engine's boundaries: approved payload, provider-only citations, budgets, ledger, checkpoints, stop. */
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

    private fun done(segments: List<RawSegment>, searches: List<RawSearch> = emptyList(), stop: CallStop = CallStop.END, webSearches: Int = searches.size, input: Long = 1_000) =
        CallResult.Done(segments, searches, MeasuredUsage(input, 500, webSearches = webSearches), stop, 1_000, "msg-${n.incrementAndGet()}", paused = if (stop == CallStop.PAUSE_TURN) "opaque" else null)

    private val amtrak = RawCitation("https://amtrak.example/was", "Washington Union Station", "50 Massachusetts Avenue NE")
    private val search = RawSearch("union station dc address", listOf(RawSearchResult("https://amtrak.example/was", "Washington Union Station", "2 months ago")))

    private var turnN = 0
    /** A pending turn approved with [auth], as the service hands it to the engine. */
    private fun approved(auth: AfAuthorization) = AfTurn("turn${++turnN}", turnN - 1, auth.question, 0, AfStatus.READY, authorization = auth)
    private suspend fun AiFirstResearch.ask(thread: String, q: String, context: List<AfContextLine> = emptyList(), config: AfConfig = cfg, clarified: AfClarified? = null) =
        run(thread, approved(authorize(store.get(thread), q, clarified, context, config)), "key")

    @Test fun buildingTheCardSendsNothingAndSavesNothing() = runBlocking {
        val p = Scripted(emptyList())
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val auth = engine.authorize(null, "Address of Union Station, Washington DC?", null, listOf(AfContextLine("today", "Today's date", "Sat 24 Oct 2026")), cfg)
        assertTrue(p.calls.isEmpty())
        assertTrue(ledger.getAll().isEmpty())
        assertNull(store.get("t1"))
        assertEquals(AfJson.sha256(auth.payload), auth.payloadSha256)
        assertTrue(auth.payload.contains("Today's date: Sat 24 Oct 2026"))
        assertNotNull(auth.estimateHighMicros)
    }

    @Test fun theCallSendsExactlyTheApprovedPayloadAndSettings() = runBlocking {
        val p = Scripted(listOf({ _ -> done(listOf(RawSegment("Answer.", emptyList()))) }))
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val auth = engine.authorize(null, "Is it open on Sunday?", AfClarified("Which museum?", "Rijksmuseum"), listOf(AfContextLine("title", "Task title", "Visit")), cfg.copy(effort = "low"))
        engine.run("t1", approved(auth), "key")
        val call = p.calls.single()
        assertEquals(auth.payload, call.user)
        assertEquals(AfPrompt.SYSTEM, call.system)
        assertEquals(cfg.model, call.model)
        assertEquals(4, call.maxSearches)
        assertEquals("low", call.effort)
        assertTrue(call.user.contains("Clarified: Which museum? — Rijksmuseum"))
    }

    @Test fun aTamperedApprovalIsRefusedWithoutSending() = runBlocking {
        val p = Scripted(emptyList())
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val auth = engine.authorize(null, "q", null, emptyList(), cfg)
        val forged = auth.copy(payload = auth.payload + "\nAlso send my other tasks.")
        val t = engine.run("t1", approved(forged), "key")
        assertEquals(AfStatus.FAILED, t.status)
        assertTrue(t.note!!.startsWith("Nothing was sent"))
        // Re-hashing the forged text does not help: the payload must be what the approved fields produce.
        val rehashed = forged.copy(payloadSha256 = AfJson.sha256(forged.payload))
        assertEquals(AfStatus.FAILED, engine.run("t1", approved(rehashed), "key").status)
        assertTrue(p.calls.isEmpty())
        assertTrue(ledger.getAll().isEmpty())
    }

    @Test fun citationsAreTheProvidersAndUnsuppliedLinksAreFlagged() = runBlocking {
        val p = Scripted(listOf({ _ ->
            done(listOf(RawSegment("Union Station is at 50 Massachusetts Ave NE.", listOf(amtrak)), RawSegment(" See https://made-up.example/page for more.", emptyList())), listOf(search))
        }))
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val turn = engine.ask("t1", "Address of Union Station, Washington DC?")
        assertEquals(AfStatus.COMPLETE, turn.status)
        assertTrue(turn.answer.startsWith("Union Station is at 50 Massachusetts Ave NE. [1]"))
        assertEquals(listOf(AfSegment("Union Station is at 50 Massachusetts Ave NE.", listOf(1)), AfSegment(" See https://made-up.example/page for more.")), turn.segments)
        assertEquals(listOf(AfSource(1, "https://amtrak.example/was", "Washington Union Station", listOf("50 Massachusetts Avenue NE"), "2 months ago")), turn.sources)
        assertEquals(listOf("https://made-up.example/page"), turn.unsuppliedLinks)
        // A URL in the text that a search did supply is not flagged, and never becomes a citation by itself.
        val p2 = Scripted(listOf({ _ -> done(listOf(RawSegment("Details: https://amtrak.example/was", emptyList())), listOf(search)) }))
        val t2 = AiFirstResearch(p2, ledger, store, clock, ids).ask("t2", "q")
        assertTrue(t2.unsuppliedLinks.isEmpty())
        assertTrue(t2.sources.isEmpty())
    }

    @Test fun pauseTurnContinuesWithTheRemainingBudgetOnly() = runBlocking {
        val p = Scripted(listOf(
            { _ -> done(emptyList(), listOf(search, search, search), stop = CallStop.PAUSE_TURN) },
            { _ -> done(listOf(RawSegment("Answer.", emptyList())), listOf(search)) },
        ))
        val turn = AiFirstResearch(p, ledger, store, clock, ids).ask("t1", "q")
        assertEquals(listOf(4, 1), p.calls.map { it.maxSearches })
        assertEquals("opaque", p.calls[1].prior)
        assertEquals(p.calls[0].user, p.calls[1].user)
        assertEquals(4, turn.webSearches)
        assertEquals(2, turn.calls)
        assertEquals(listOf("RESEARCH", "RESEARCH_CONTINUED"), ledger.getAll().sortedBy { it.id.removePrefix("id").toInt() }.map { it.stage })
    }

    @Test fun pausedWithNoSearchesLeftStopsAndSaysSo() = runBlocking {
        val p = Scripted(listOf({ _ -> done(listOf(RawSegment("Partial.", emptyList())), List(4) { search }, stop = CallStop.PAUSE_TURN) }))
        val turn = AiFirstResearch(p, ledger, store, clock, ids).ask("t1", "q")
        assertEquals(1, p.calls.size)
        assertEquals(AfStatus.SEARCH_BUDGET_REACHED, turn.status)
        assertTrue(turn.note!!.contains("4 of 4"))
        assertEquals("Partial.", turn.answer)
    }

    @Test fun theSpendingLimitIsCheckedAgainstAHighEstimateBeforeEveryCall() = runBlocking {
        // A limit between the first call's low and high estimate sends nothing: the low end is not a ceiling.
        val probe = AiFirstResearch(Scripted(emptyList()), ledger, store, clock, ids).authorize(null, "q", null, emptyList(), cfg)
        val between = ((probe.estimateLowMicros!! + probe.estimateHighMicros!!) / 2 / 10_000).toInt()
        val none = Scripted(emptyList())
        val t = AiFirstResearch(none, ledger, store, clock, ids).ask("t1", "q", config = cfg.copy(spendLimitCents = between))
        assertEquals(AfStatus.SPEND_LIMIT_REACHED, t.status)
        assertTrue(t.note!!.startsWith("Nothing was sent"))
        assertTrue(none.calls.isEmpty())
        assertTrue(ledger.getAll().isEmpty())

        // A continuation is checked from what the first call measured: a large paused call blocks the next one.
        val big = Scripted(listOf({ _ -> done(listOf(RawSegment("Half.", listOf(amtrak))), listOf(search), stop = CallStop.PAUSE_TURN, input = 180_000) }))
        val t2 = AiFirstResearch(big, ledger, store, clock, ids).ask("t2", "q", config = cfg.copy(spendLimitCents = 100))
        assertEquals(1, big.calls.size)
        assertEquals(AfStatus.SPEND_LIMIT_REACHED, t2.status)
        assertTrue(t2.note!!.contains("before another call"))
        assertEquals("Half. [1]", t2.answer)
        assertEquals(1, ledger.getAll().size)
    }

    @Test fun everyCallIsLedgeredFromMeasuredUsageIncludingFailures() = runBlocking {
        val p = Scripted(listOf(
            { _ -> done(listOf(RawSegment("Half an answer.", emptyList())), listOf(search), stop = CallStop.PAUSE_TURN) },
            { _ -> CallResult.Failed(ResearchError.Network, searchesSeen = 1, usage = null, elapsedMs = 10) },
        ))
        val turn = AiFirstResearch(p, ledger, store, clock, ids).ask("t1", "q")
        assertEquals(AfStatus.FAILED, turn.status)
        assertEquals("Half an answer.", turn.answer)
        assertTrue("an unreported failure makes the total a floor", turn.costIsFloor)
        val rows = ledger.getAll()
        assertEquals(setOf(CallOutcome.COMPLETE, CallOutcome.FAILED_UNKNOWN), rows.map { it.outcome }.toSet())
        assertEquals("the turn's cost is the sum of its ledger rows", rows.sumOf { it.estimatedMicros ?: 0 }, turn.costMicros)
        assertEquals(2, turn.webSearches)
        assertTrue(rows.all { it.feature == UsageFeature.RESEARCH && it.runId == turn.id && it.sessionId == "t1" })

        // Rejected before processing: recorded, $0 assumed, not a floor.
        val rejected = AiFirstResearch(Scripted(listOf({ _ -> CallResult.Failed(ResearchError.Unauthorized, 0, null, 5) })), ledger, store, clock, ids).ask("t2", "q")
        assertEquals(AfStatus.FAILED, rejected.status)
        assertFalse(rejected.costIsFloor)
        assertEquals(CallOutcome.FAILED_NOT_BILLED, ledger.getAll().first { it.sessionId == "t2" }.outcome)
    }

    @Test fun theTurnIsSavedBeforeSendingAndCheckpointedAfterEveryCall() = runBlocking {
        lateinit var p: Scripted
        val seen = ArrayList<AfStatus?>()
        val partial = ArrayList<String?>()
        p = Scripted(listOf(
            { _ -> done(listOf(RawSegment("First part.", emptyList())), listOf(search), stop = CallStop.PAUSE_TURN) },
            { _ -> done(listOf(RawSegment(" Second part.", emptyList()))) },
        ))
        p.beforeEach = { store.get("t1")?.turns?.singleOrNull().let { seen += it?.status; partial += it?.answer } }
        val turn = AiFirstResearch(p, ledger, store, clock, ids).ask("t1", "q")
        assertEquals(listOf(AfStatus.RUNNING, AfStatus.RUNNING), seen)
        assertEquals(listOf("", "First part."), partial)
        assertEquals(turn, store.get("t1")!!.turns.single())
        assertEquals("First part. Second part.", turn.answer)
    }

    @Test fun stoppingKeepsWhatArrivedAndLedgersTheStoppedCall() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val p = Scripted(listOf(
            { _ -> done(listOf(RawSegment("Kept.", listOf(amtrak))), listOf(search), stop = CallStop.PAUSE_TURN) },
            { _ -> error("never answered") },
        ))
        p.beforeEach = { if (p.calls.size == 1) hold.await() }
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        val auth = engine.authorize(null, "q", null, emptyList(), cfg)
        val job = async(Dispatchers.Default) { engine.run("t1", approved(auth), "key") }
        while (ledger.getAll().size < 2) yield()
        job.cancel(CancellationException("stopped by user"))
        runCatching { job.await() }
        val t = store.get("t1")!!.turns.single()
        assertEquals(AfStatus.STOPPED, t.status)
        assertEquals("Kept. [1]", t.answer)
        assertEquals(1, t.sources.size)
        assertTrue(t.costIsFloor)
        assertEquals(2, t.calls)
        assertEquals(setOf(CallOutcome.COMPLETE, CallOutcome.STOPPED), ledger.getAll().map { it.outcome }.toSet())
    }

    @Test fun aFollowUpCarriesOnlyItsOwnThreadsApprovedHistory() = runBlocking {
        val p = Scripted(listOf(
            { _ -> done(listOf(RawSegment("Thorne has iron; Pure does not.", listOf(amtrak))), listOf(search)) },
            { _ -> done(listOf(RawSegment("Other thread.", emptyList()))) },
            { _ -> done(listOf(RawSegment("Pick Pure.", emptyList()))) },
        ))
        val engine = AiFirstResearch(p, ledger, store, clock, ids)
        engine.ask("a", "Compare Thorne and Pure", listOf(AfContextLine("today", "Today's date", "2026-10-24")))
        engine.ask("b", "Unrelated secret question")
        val followUp = engine.authorize(store.get("a"), "Which for someone avoiding iron?", null, emptyList(), cfg)
        assertTrue(followUp.includesHistory)
        engine.run("a", approved(followUp), "key")
        val sent = p.calls[2].user
        assertTrue(sent.contains("Compare Thorne and Pure") && sent.contains("Thorne has iron; Pure does not. [1]") && sent.contains("Which for someone avoiding iron?"))
        assertFalse(sent.contains("Unrelated secret question"))
        assertFalse("context is approved per turn, never inherited silently", sent.contains("2026-10-24"))
        assertEquals(UsageFeature.RESEARCH_CONTINUATION, ledger.getAll().first { it.sessionId == "a" && it.runId == store.get("a")!!.turns.last().id }.feature)

        // An approval naming an earlier turn that is gone is refused rather than re-worded.
        val gone = followUp.copy(historyTurnIds = listOf("missing"))
        assertEquals(AfStatus.FAILED, engine.run("a", approved(gone), "key").status)
        assertEquals(3, p.calls.size)
    }

    @Test fun aReplyWithNoTextIsAFailureNotAnEmptyAnswer() = runBlocking {
        val t = AiFirstResearch(Scripted(listOf({ _ -> done(emptyList(), listOf(search)) })), ledger, store, clock, ids).ask("t1", "q")
        assertEquals(AfStatus.FAILED, t.status)
        assertEquals("Claude finished without writing an answer.", t.note)
    }
}
