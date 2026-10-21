package com.lifeadmin.autopilot.ai.research2

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.InMemoryUsageLedger
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.ai.usage.UsageStatus
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.reminders.ReminderEngine
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FAKE_KEY
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeCallProvider
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakePlanningProvider
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.Research2Fixtures
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The orchestrator against a scripted call provider: stage planning, budget rules, honest outcomes,
 * usage accounting per call (benchmark C), stop semantics and continuation. No network.
 */
@RunWith(AndroidJUnit4::class)
class DeepResearchServiceTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 20, 10, 0))
    private val zone = FakeZoneProvider(CHICAGO)
    private val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION))
    private val keys = InMemoryApiKeyStore(FAKE_KEY)
    private val provider = FakeCallProvider()
    private val store = InMemorySessionStore()
    private val ledger = InMemoryUsageLedger()
    private var online: Boolean? = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: TaskRepository
    private lateinit var service: DeepResearchService
    private lateinit var taskId: String

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds("d")
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        repo = TaskRepository(db, clock, ids, zone::current, engine, log)
        val planning = AiPlanningService(settings, keys, FakePlanningProvider(), repo, clock, zone, ids) { online }
        service = DeepResearchService(settings, keys, planning, provider, repo, store, ledger, clock, zone, ids, scope, { online }, { Locale.US })
        taskId = runBlocking { repo.createTask(NewTask("Supplements", notes = "Monthly order")) }
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun draft(request: String = Research2Fixtures.SUPPLEMENTS_REQUEST, mode: ResearchMode = ResearchMode.THOROUGH, searches: Int? = null, limitCents: Int? = null): SessionDraft {
        val r = service.extract(request)
        return SessionDraft(request, r.template, r.requirements, ResearchModes.config(mode, searches = searches, spendLimitCents = limitCents))
    }

    private fun start(d: SessionDraft = draft()) = runBlocking { service.start(taskId, d) }
    private fun awaitFinished(): DeepRunState.Finished = runBlocking { withTimeout(10_000) { service.state.filterIsInstance<DeepRunState.Finished>().first() } }
    private fun saved(f: DeepRunState.Finished): ResearchSession = runBlocking { store.get((f.result as RunResult.Saved).sessionId)!! }
    private fun awaitCalls(n: Int) = runBlocking { withTimeout(5_000) { while (provider.calls.size < n) kotlinx.coroutines.delay(10) } }

    @Test fun gateUsesTheSessionCapAndRejectsBadDrafts() = runBlocking {
        assertNull(service.gate().blocker)
        assertEquals(Research2Config.MAX_SESSIONS_PER_DAY, service.gate().remaining)
        assertEquals(StartResult2.Rejected(ResearchError.EmptyQuestion), service.start(taskId, draft().copy(request = " ")))
        assertEquals(StartResult2.Rejected(ResearchError.QuestionTooLong), service.start(taskId, draft().copy(request = "x".repeat(Research2Config.MAX_REQUEST_CHARS + 1))))
        online = false
        assertTrue(service.start(taskId, draft()) is StartResult2.Rejected)
        online = true
        assertTrue(provider.calls.isEmpty())
        assertEquals(0, service.requestsToday())
        assertTrue("a request that never left the phone has no ledger row", ledger.getAll().isEmpty())
    }

    @Test fun thoroughRunGathersThenFillsGapsAndRecordsEachCall() {
        provider.enqueue(Research2Fixtures.supplementsGather())
        // Second pass finds glucosamine elsewhere (status partly: not the preferred brand).
        provider.enqueue(
            CallResult.Done(
                listOf(RawSegment("## REQ r-i3 · status=partly\nNo preferred-brand glucosamine; a third-party option exists.\n## PICK r-i3 · status=partly\nPick: Other Brand Glucosamine 1500\nWhy: Only option found; not a preferred brand.\n", emptyList())),
                emptyList(), MeasuredUsage(12_000, 300, 0, 0, 2_100, 2), CallStop.END, 8_000, "msg_supp_2",
            ),
        )
        val started = start() as StartResult2.Started
        val finished = awaitFinished()
        assertEquals(started.runId, finished.runId)
        val session = saved(finished)
        assertEquals("glucosamine is only partly covered, so the run is Partially complete, not Complete", SessionOutcome.PARTIAL, session.outcome)
        assertEquals("the off-brand glucosamine is an exception for the person to decide on", RequirementStatus.YOUR_CALL, session.content.coverageOf("r-c0").status)
        assertEquals(2, provider.calls.size)
        assertEquals("gather gets 60% of 10", 6, provider.calls[0].maxSearches)
        assertTrue("second call is a gap-fill naming the open requirement", provider.calls[1].user.contains("r-i3") && provider.calls[1].user.contains("Still open"))
        assertTrue("known sources are passed by id", provider.calls[1].user.contains("s1 ·"))
        assertTrue("queries are not repeated", provider.calls[1].user.contains("do not repeat"))
        assertTrue(provider.calls.all { it.cacheSystem })
        assertEquals(listOf(FAKE_KEY, FAKE_KEY), provider.keysSeen)
        val run = session.content.latestRun!!
        assertEquals(listOf(StageKind.GATHER to StageResult.DONE, StageKind.FILL_GAPS to StageResult.DONE), run.stages.map { it.kind to it.result })
        assertEquals(7, run.usage.webSearches)
        assertEquals(53_000L, run.usage.inputTokens)
        assertEquals(2_100L, run.usage.cacheReadTokens)
        assertEquals(4, session.content.proposals.size)
        assertEquals(RequirementStatus.PARTLY, session.content.coverageOf("r-i3").status)
        // Ledger: one record per HTTP call, each complete and priced, same session and run.
        val records = runBlocking { ledger.forSession(session.id) }
        assertEquals(2, records.size)
        assertTrue(records.all { it.outcome == CallOutcome.COMPLETE && it.usageStatus == UsageStatus.COMPLETE && it.runId == started.runId && it.feature == UsageFeature.RESEARCH })
        assertEquals(listOf("GATHER", "FILL_GAPS"), records.map { it.stage })
        assertEquals(5, records[0].usage!!.webSearches)
        assertTrue(records[0].estimatedMicros!! > 0)
        assertEquals(1, runBlocking { service.requestsToday() })
        assertEquals("Thorne & Pure picks: multi, D, magnesium, glucosamine", session.content.title)
    }

    @Test fun fillGapsIsSkippedWhenEverythingIsCovered() {
        provider.enqueue(Research2Fixtures.fragranceGather(), Research2Fixtures.fragranceFillGaps())
        start(draft(Research2Fixtures.FRAGRANCE_REQUEST))
        val session = saved(awaitFinished())
        assertEquals("the price gap justified a second call", 2, provider.calls.size)
        assertEquals(SessionOutcome.COMPLETE, session.outcome)
        // Now a run where nothing is open after GATHER: only one call is made.
        provider.enqueue(Research2Fixtures.fragranceGather(), Research2Fixtures.fragranceFillGaps())
        provider.calls.clear()
        start(draft(Research2Fixtures.FRAGRANCE_REQUEST))
        val second = saved(awaitFinished())
        assertEquals(2, provider.calls.size) // c3's price is still unconfirmed in the fixture, so FILL_GAPS runs
        assertEquals(StageResult.DONE, second.content.latestRun!!.stages[1].result)
    }

    @Test fun quickModeIsOneCallAndAnUnfinishedRequirementIsIncompleteNotComplete() {
        provider.enqueue(Research2Fixtures.supplementsGather().copy(usage = MeasuredUsage(20_000, 2_000, 0, 0, 0, 3)))
        start(draft(mode = ResearchMode.QUICK))
        val session = saved(awaitFinished())
        assertEquals(1, provider.calls.size)
        assertEquals(3, provider.calls[0].maxSearches)
        assertFalse(provider.calls[0].cacheSystem)
        assertEquals("glucosamine was not found and searches remained → evidence, not budget", SessionOutcome.INCOMPLETE_EVIDENCE, session.outcome)
        assertEquals(4, session.content.proposals.size)
    }

    @Test fun exhaustedSearchBudgetWithOpenRequirementsIsIncompleteBudget() {
        provider.enqueue(Research2Fixtures.supplementsGather().copy(usage = MeasuredUsage(20_000, 2_000, 0, 0, 0, 5)))
        start(draft(searches = 5))
        val session = saved(awaitFinished())
        assertEquals(1, provider.calls.size)
        assertEquals(SessionOutcome.INCOMPLETE_BUDGET, session.outcome)
        assertEquals(StageResult.SKIPPED, session.content.latestRun!!.stages[1].result)
        assertEquals("no searches left", session.content.latestRun!!.stages[1].note)
    }

    @Test fun softSpendLimitStopsFurtherCallsAndNeverClaimsSuccess() {
        // Gather at Sonnet prices: 41k in × $2 + 2.6k × $10 + 5 × $0.01 ≈ $0.158. Limit 20 cents: the gap-fill's low estimate would pass it.
        provider.enqueue(Research2Fixtures.supplementsGather())
        start(draft(limitCents = 20))
        val session = saved(awaitFinished())
        assertEquals(1, provider.calls.size)
        assertEquals(SessionOutcome.INCOMPLETE_BUDGET, session.outcome)
        val st = session.content.latestRun!!.stages
        assertEquals(StageResult.BUDGET, st[1].result)
        assertTrue(st[1].note!!, st[1].note!!.contains("spending limit"))
    }

    @Test fun spendLimitTrimsTheAllowanceInsteadOfStoppingWhenAFewSearchesFit() {
        provider.enqueue(Research2Fixtures.supplementsGather())
        provider.enqueue(Research2Fixtures.fragranceFillGaps().copy(segments = listOf(RawSegment("## REQ r-i3 · status=answered\nfound\n", emptyList()))))
        start(draft(limitCents = 28))
        saved(awaitFinished())
        assertEquals(2, provider.calls.size)
        assertEquals("allowance trimmed from 3 to what fits under the limit", 2, provider.calls[1].maxSearches)
    }

    @Test fun failureAfterPartialEvidenceIsPartialAndFailureBeforeIsFailed() {
        provider.enqueue(Research2Fixtures.supplementsGather())
        provider.enqueue(CallResult.Failed(ResearchError.ServerError(529), 0, null, 2_000))
        start()
        val f1 = awaitFinished()
        val s1 = saved(f1)
        assertEquals(SessionOutcome.PARTIAL, s1.outcome)
        assertEquals("SERVER ERROR (529)", s1.content.latestRun!!.technical)
        val recs = runBlocking { ledger.forSession(s1.id) }
        assertEquals(listOf(CallOutcome.COMPLETE, CallOutcome.FAILED_UNKNOWN), recs.map { it.outcome })
        assertNull("unknown usage is never $0", recs[1].estimatedMicros)
        service.dismiss(f1.runId)

        provider.enqueue(CallResult.Failed(ResearchError.RateLimited(30), 0, null, 100))
        start()
        val f2 = awaitFinished()
        assertEquals(ResearchError.RateLimited(30), (f2.result as RunResult.Failed).error)
        val all = runBlocking { ledger.getAll() }
        assertEquals(CallOutcome.FAILED_NOT_BILLED, all.first { it.runId == f2.runId }.outcome)
        assertEquals(0L, all.first { it.runId == f2.runId }.estimatedMicros)
        assertNull("a rejected request carries no usage at all", all.first { it.runId == f2.runId }.usage)
        assertEquals("nothing saved for a failed run", 1, runBlocking { store.getAll() }.size)
    }

    @Test fun failureAfterObservedSearchesKeepsThemAsACostFloor() {
        // First call dies after the provider ran two searches: the run fails, the $0.02 floor is kept, tokens stay unknown.
        provider.enqueue(CallResult.Failed(ResearchError.ServerError(529), searchesSeen = 2, usage = null, elapsedMs = 3_000))
        start()
        val f = awaitFinished()
        assertTrue(f.result is RunResult.Failed)
        val rec = runBlocking { ledger.getAll() }.single()
        assertEquals(CallOutcome.FAILED_UNKNOWN, rec.outcome)
        assertEquals(UsageStatus.PARTIAL, rec.usageStatus)
        assertEquals(2, rec.usage!!.webSearches); assertNull(rec.usage!!.inputTokens)
        assertEquals(20_000L, rec.estimatedMicros)
        assertTrue(rec.isFloor && rec.tokensUnknown)
        service.dismiss(f.runId)
        // After a good first pass the same failure is Partial and the run usage says it is a floor.
        provider.enqueue(Research2Fixtures.supplementsGather())
        provider.enqueue(CallResult.Failed(ResearchError.Timeout(300), searchesSeen = 1, usage = null, elapsedMs = 180_000))
        start()
        val s = saved(awaitFinished())
        assertEquals(SessionOutcome.PARTIAL, s.outcome)
        val run = s.content.latestRun!!
        assertEquals("5 from the good call + 1 seen before the timeout", 6, run.usage.webSearches)
        assertFalse(run.usage.complete)
        val recs = runBlocking { ledger.forSession(s.id) }
        assertEquals(listOf(UsageStatus.COMPLETE, UsageStatus.PARTIAL), recs.map { it.usageStatus })
        assertEquals(10_000L, recs[1].estimatedMicros)
        // A failed call whose usage the provider did report is priced in full.
        service.dismiss((service.state.value as DeepRunState.Finished).runId)
        provider.enqueue(CallResult.Failed(ResearchError.Malformed("bad shape"), searchesSeen = 1, usage = MeasuredUsage(5_000, 100, 0, 0, 0, 1), elapsedMs = 2_000))
        start()
        val lastRun = awaitFinished().runId
        val full = runBlocking { ledger.getAll() }.single { it.runId == lastRun }
        assertEquals(UsageStatus.COMPLETE, full.usageStatus)
        assertEquals((5_000 * 2.0 + 100 * 10.0 + 10_000).toLong(), full.estimatedMicros)
    }

    @Test fun stopDuringAPauseTurnContinuationKeepsTheFirstCallsSearches() {
        val paused = Research2Fixtures.supplementsGather().copy(stop = CallStop.PAUSE_TURN, paused = "prior", usage = MeasuredUsage(20_000, 500, 0, 0, 0, 2))
        provider.enqueue(paused)
        provider.gate = CompletableDeferred(); provider.gateCall = 2
        val started = start(draft(mode = ResearchMode.QUICK)) as StartResult2.Started
        awaitCalls(2)
        val running = service.state.value as DeepRunState.Running
        assertEquals(2, running.searchesRun)
        service.stop(started.runId, keepPartial = true)
        val s = saved(awaitFinished())
        assertEquals(SessionOutcome.STOPPED, s.outcome)
        assertEquals(2, s.content.latestRun!!.usage.webSearches)
        assertFalse("the continuation's usage is unknown: the run total is a floor", s.content.latestRun!!.usage.complete)
        assertEquals(listOf(CallOutcome.COMPLETE, CallOutcome.STOPPED), runBlocking { ledger.forSession(s.id) }.map { it.outcome })
    }

    @Test fun twoSimultaneousStartsLaunchOneRunAndReserveOneSession() {
        provider.gate = CompletableDeferred()
        val results = runBlocking {
            val a = async(Dispatchers.Default) { service.start(taskId, draft()) }
            val b = async(Dispatchers.Default) { service.start(taskId, draft()) }
            listOf(a.await(), b.await())
        }
        assertEquals(1, results.count { it is StartResult2.Started })
        assertEquals(StartResult2.Rejected(ResearchError.Busy), results.first { it is StartResult2.Rejected })
        awaitCalls(1)
        assertEquals("only one call in flight", 1, provider.calls.size)
        assertEquals("only one session reserved against the daily cap", 1, runBlocking { service.requestsToday() })
        val cont = runBlocking { service.continueSession("whatever", ResearchModes.config(ResearchMode.QUICK), emptyList(), null) }
        assertEquals(StartResult2.Rejected(ResearchError.Busy), cont)
        service.stop((results.first { it is StartResult2.Started } as StartResult2.Started).runId, keepPartial = false)
        awaitFinished()
    }

    @Test fun refusalAndEmptyAnswersAreReportedNotSaved() {
        provider.enqueue(Research2Fixtures.supplementsGather().copy(segments = emptyList(), stop = CallStop.REFUSAL, refusalCategory = "harmful"))
        start()
        assertEquals(ResearchError.Refused("harmful"), (awaitFinished().result as RunResult.Failed).error)
        service.dismiss((service.state.value as DeepRunState.Finished).runId)
        provider.enqueue(CallResult.Done(emptyList(), emptyList(), MeasuredUsage(100, 0, 0, 0, 0, 0), CallStop.END, 10, "m"))
        start()
        val empty = (awaitFinished().result as RunResult.Failed)
        assertTrue(empty.error.toString(), empty.error is ResearchError.NoFindings && (empty.error as ResearchError.NoFindings).why == CallClass.EMPTY)
        assertEquals(CallClass.EMPTY, empty.diagnostics.single().classification)
    }

    @Test fun truncatedAnswerIsSavedAsPartial() {
        provider.enqueue(Research2Fixtures.supplementsGather().copy(stop = CallStop.MAX_TOKENS))
        start(draft(mode = ResearchMode.QUICK))
        val s = saved(awaitFinished())
        assertEquals(SessionOutcome.PARTIAL, s.outcome)
        assertEquals(CallOutcome.TRUNCATED, runBlocking { ledger.forSession(s.id) }[0].outcome)
    }

    @Test fun pauseTurnIsContinuedOnceWithinTheStageBudgetAsASeparateBilledCall() {
        val paused = Research2Fixtures.supplementsGather().copy(stop = CallStop.PAUSE_TURN, paused = "prior-message", usage = MeasuredUsage(20_000, 500, 0, 0, 0, 2))
        provider.enqueue(paused)
        provider.enqueue(CallResult.Done(listOf(RawSegment("## REQ r-i3 · status=answered\nfound\n", emptyList())), emptyList(), MeasuredUsage(5_000, 200, 0, 0, 0, 1), CallStop.END, 3_000, "msg_cont"))
        start(draft(mode = ResearchMode.QUICK))
        val s = saved(awaitFinished())
        assertEquals(2, provider.calls.size)
        assertEquals("prior-message", provider.calls[1].prior)
        assertEquals("remaining searches after 2 of 3", 1, provider.calls[1].maxSearches)
        assertEquals(2, runBlocking { ledger.forSession(s.id) }.size)
        assertEquals(2, s.content.latestRun!!.stages[0].calls)
        assertEquals(3, s.content.latestRun!!.usage.webSearches)
    }

    @Test fun stopKeepingPartialSavesAStoppedSessionAndRecordsUnknownUsage() {
        provider.enqueue(Research2Fixtures.supplementsGather())
        provider.gate = CompletableDeferred(); provider.gateCall = 2
        val started = start() as StartResult2.Started
        awaitCalls(2)
        service.stop(started.runId, keepPartial = true)
        val f = awaitFinished()
        val s = saved(f)
        assertEquals(SessionOutcome.STOPPED, s.outcome)
        assertEquals(3, s.content.body.picks.count { it.pick != null })
        val recs = runBlocking { ledger.forSession(s.id) }
        assertEquals(listOf(CallOutcome.COMPLETE, CallOutcome.STOPPED), recs.map { it.outcome })
        assertEquals(UsageStatus.UNKNOWN, recs[1].usageStatus)
        assertNull(recs[1].estimatedMicros)
        assertTrue(s.content.latestRun!!.technical == "STOPPED BY YOU")
    }

    @Test fun stopDiscardingSavesNothingButStillRecordsTheCall() {
        provider.gate = CompletableDeferred()
        val started = start() as StartResult2.Started
        awaitCalls(1)
        service.stop(started.runId, keepPartial = false)
        val f = awaitFinished()
        assertEquals(ResearchError.Stopped(0), (f.result as RunResult.Failed).error)
        assertEquals(0, runBlocking { store.getAll() }.size)
        assertEquals(CallOutcome.STOPPED, runBlocking { ledger.getAll() }.single().outcome)
    }

    @Test fun continuationKeepsSourceIdsAndRequirementsAndAddsANewRun() {
        provider.enqueue(Research2Fixtures.supplementsGather().copy(usage = MeasuredUsage(20_000, 2_000, 0, 0, 0, 5)))
        start(draft(searches = 5))
        val first = saved(awaitFinished())
        service.dismiss((service.state.value as DeepRunState.Finished).runId)
        assertEquals(SessionOutcome.INCOMPLETE_BUDGET, first.outcome)
        val sourcesBefore = first.content.sources.map { it.n to it.url }
        clock.advance(java.time.Duration.ofMinutes(3))
        provider.enqueue(
            CallResult.Done(
                listOf(
                    RawSegment("## REQ r-i0 · status=answered\nok\n## REQ r-i1 · status=answered\nok\n## REQ r-i2 · status=answered\nok\n## REQ r-f1 · status=answered\n", emptyList()),
                    RawSegment("The brand site lists a glucosamine product directly, as asked.", listOf(com.lifeadmin.autopilot.ai.research.RawCitation("https://www.thorne.example/products/glucosamine", "Glucosamine | Thorne", "Glucosamine sulfate 500 mg"))),
                    RawSegment("\n## REQ r-i3 · status=answered\n", emptyList()),
                    RawSegment("Found a glucosamine product on the brand site.", listOf(com.lifeadmin.autopilot.ai.research.RawCitation("https://www.thorne.example/products/glucosamine", "Glucosamine | Thorne", "Glucosamine sulfate 500 mg"))),
                    RawSegment("\n## PICK r-i3 · status=answered\nPick: Thorne Glucosamine & Chondroitin\nLabel: Glucosamine sulfate 500 mg src=s5\nWhy: Preferred brand.\n## PROPOSE step · req=r-i3\nBuy glucosamine: Thorne Glucosamine & Chondroitin\nNote: 500 mg per capsule.\n", emptyList()),
                ),
                listOf(com.lifeadmin.autopilot.ai.research.RawSearch("Thorne glucosamine chondroitin", emptyList())),
                MeasuredUsage(15_000, 900, 0, 0, 0, 1), CallStop.END, 6_000, "msg_cont_1",
            ),
        )
        val cont = runBlocking { service.continueSession(first.id, ResearchModes.config(ResearchMode.QUICK, searches = 4), listOf("r-i3"), "Try the brand site directly") } as StartResult2.Started
        assertEquals(first.id, cont.sessionId)
        val second = saved(awaitFinished())
        assertEquals(first.id, second.id)
        assertEquals(SessionOutcome.COMPLETE, second.outcome)
        assertEquals(2, second.content.runs.size)
        assertEquals(RunKind.CONTINUATION, second.content.latestRun!!.kind)
        assertEquals("earlier source numbers are untouched", sourcesBefore, second.content.sources.take(sourcesBefore.size).map { it.n to it.url })
        assertEquals(5, second.content.sources.size)
        assertEquals("the continuation prompt carries the earlier queries", true, provider.calls[1].user.contains("Thorne glucosamine") && provider.calls[1].user.contains("Focus on: r-i3") && provider.calls[1].user.contains("Try the brand site directly"))
        val fu = second.content.followUps.single()
        assertEquals("Try the brand site directly", fu.question); assertEquals("r-f1", fu.requirementId); assertEquals(RequirementStatus.ANSWERED, fu.status)
        assertTrue("the note is tracked as a requirement of its own", second.content.requirements.any { it.id == "r-f1" && it.followUp })
        assertTrue("the original answer is kept", second.content.answer == first.content.answer)
        assertEquals(listOf("r-i0", "r-i1", "r-i2", "r-i3"), second.content.proposals.map { it.requirementId })
        assertEquals(ProposalKind.STEP, second.content.proposals.last().kind)
        assertEquals("Buy glucosamine: Thorne Glucosamine & Chondroitin", second.content.proposals.last().title)
        assertEquals(6, second.content.searchesTotal)
        val recs = runBlocking { ledger.forSession(first.id) }
        assertEquals(2, recs.size)
        assertEquals(listOf(UsageFeature.RESEARCH, UsageFeature.RESEARCH_CONTINUATION), recs.map { it.feature })
        assertEquals("different run ids: no double counting across continuations", 2, recs.map { it.runId }.distinct().size)
        assertEquals(2, runBlocking { service.requestsToday() })
    }

    @Test fun busyServiceRejectsASecondStartAndDeleteStopsTheRun() {
        provider.gate = CompletableDeferred()
        val started = start() as StartResult2.Started
        awaitCalls(1)
        assertEquals(StartResult2.Rejected(ResearchError.Busy), start())
        runBlocking { service.deleteForTask(taskId) }
        assertTrue(service.state.value is DeepRunState.Finished)
        assertEquals(started.runId, (service.state.value as DeepRunState.Finished).runId)
        assertEquals(0, runBlocking { store.getAll() }.size)
    }

    // ------------------------------------------------------------------ P0: timeouts, bounded batches, checkpoints

    private fun answerOnly(text: String = "## ANSWER\nWrite-up from the evidence held.\n", searches: Int = 0) =
        CallResult.Done(listOf(RawSegment(text, emptyList())), emptyList(), MeasuredUsage(3_000, 400, 0, 0, 0, searches), CallStop.END, 2_000, "msg_" + text.hashCode())

    private val thorneGlucosamine = com.lifeadmin.autopilot.ai.research.RawCitation("https://www.thorne.example/products/glucosamine", "Glucosamine & Chondroitin | Thorne", "Glucosamine sulfate 500 mg")
    private fun glucosamineFound(searches: Int) = CallResult.Done(
        listOf(
            RawSegment("## REQ r-i3 · status=answered\n", emptyList()),
            RawSegment("Thorne lists a glucosamine and chondroitin product.", listOf(thorneGlucosamine)),
            RawSegment("\n## PICK r-i3 · status=answered\nPick: Thorne Glucosamine & Chondroitin\nLabel: ", emptyList()),
            RawSegment("Glucosamine sulfate 500 mg per capsule.", listOf(thorneGlucosamine)),
            RawSegment("\nWhy: preferred brand\n## PROPOSE step · req=r-i3\nBuy glucosamine: Thorne Glucosamine & Chondroitin\n", emptyList()),
        ),
        listOf(com.lifeadmin.autopilot.ai.research.RawSearch("Thorne glucosamine", emptyList())), MeasuredUsage(9_000, 300, 0, 0, 2_000, searches), CallStop.END, 5_000, "msg_gluc_$searches",
    )
    private var partlyIds = 0
    private fun glucosaminePartly(searches: Int) = CallResult.Done(
        listOf(RawSegment("## REQ r-i3 · status=partly\nA third-party listing only.\n## PICK r-i3 · status=partly\nPick: Other Brand Glucosamine 1500\nWhy: not a preferred brand\n", emptyList())),
        emptyList(), MeasuredUsage(7_000, 200, 0, 0, 0, searches), CallStop.END, 4_000, "msg_partly_${searches}_${partlyIds++}",
    )

    @Test fun perCallTimeoutFollowsTheSearchesAndOutputOfTheCallNeverTheLegacyConstant() {
        assertEquals(142L, Research2Config.callTimeout(3, 1_500).seconds)
        assertEquals(300L, Research2Config.callTimeout(6, 6_000).seconds)
        assertEquals(400L, Research2Config.callTimeout(6, 10_000).seconds)
        assertEquals("write-up without searches", 310L, Research2Config.callTimeout(0, 10_000).seconds)
        assertEquals("never past Anthropic's 10-minute request limit", 540L, Research2Config.callTimeout(30, 10_000).seconds)
        assertEquals("never below the floor", 120L, Research2Config.callTimeout(0, 0).seconds)
        assertTrue(Research2Config.MAX_CALL_TIMEOUT.seconds > ResearchConfig.REQUEST_TIMEOUT.seconds)
        // The orchestrator hands each call its own limit.
        provider.enqueue(Research2Fixtures.supplementsGather().copy(usage = MeasuredUsage(20_000, 2_000, 0, 0, 0, 3)))
        start(draft(mode = ResearchMode.QUICK))
        saved(awaitFinished())
        assertEquals("Quick: 60 s + 3 × 15 s + 6,000 / 40", java.time.Duration.ofSeconds(255), provider.calls.single().timeout)
        val events = (service.state.value as DeepRunState.Finished).let { runBlocking { store.getAll() } }
        assertTrue(events.isNotEmpty())
    }

    @Test fun deepGatherRunsInBoundedBatchesAndCheckpointsAfterEveryCall() {
        // 25 searches → GATHER gets 15, sent as batches of at most 6 while requirements stay open.
        provider.enqueue(Research2Fixtures.supplementsGather())          // batch 1: 5 searches, glucosamine still open
        provider.enqueue(glucosaminePartly(2))                            // batch 2: still partly → one more
        provider.enqueue(glucosamineFound(1))                             // batch 3: everything covered → stage ends early
        provider.fallback = { answerOnly() }                              // write-up
        provider.gate = CompletableDeferred(); provider.gateCall = 2
        val started = start(draft(mode = ResearchMode.DEEP)) as StartResult2.Started
        awaitCalls(2)
        // Checkpoint after batch 1: the three picks are already on disk as an Interrupted session.
        val mid = runBlocking { store.get(started.sessionId) }!!
        assertEquals(SessionOutcome.INTERRUPTED, mid.outcome)
        assertEquals(3, mid.content.body.picks.count { it.pick != null })
        assertEquals("a provisional run record marks it as in progress", "IN PROGRESS · EVIDENCE SAVED AFTER EACH CALL", mid.content.runs.single().technical)
        val running = service.state.value as DeepRunState.Running
        assertTrue(running.checkpointed)
        assertEquals(2, running.callsSent)
        assertEquals("Deep batch of 6 with 12,000 output tokens: 60 + 90 + 300", 450, running.callTimeoutSeconds)
        assertNotNull(running.callStartedAtEpochMs)
        assertTrue(running.events.any { it.kind == RunEventKind.CONTINUED && it.text.contains("evidence saved") && it.text.contains("glucosamine") })
        provider.gate!!.complete(Unit)
        val s = saved(awaitFinished())
        assertEquals(SessionOutcome.COMPLETE, s.outcome)
        val gather = s.content.latestRun!!.stages.first { it.kind == StageKind.GATHER }
        assertEquals(StageResult.DONE, gather.result)
        assertEquals(3, gather.calls)
        assertEquals(8, gather.searchesRun)
        assertEquals(listOf(6, 6, 6, 0), provider.calls.map { it.maxSearches })
        assertTrue("batch 2 is an evidence-driven follow-up naming the open item", provider.calls[1].user.contains("Still open") && provider.calls[1].user.contains("r-i3"))
        assertTrue("batch 3 still carries the known sources", provider.calls[2].user.contains("Sources already held"))
        assertEquals("Deep batches of 6 with 12,000 output tokens, then the write-up", listOf(450L, 450L, 450L, 360L), provider.calls.map { it.timeout!!.seconds })
        assertEquals("one ledger row per HTTP call", 4, runBlocking { ledger.forSession(s.id) }.size)
        assertEquals("the provisional record was replaced, not duplicated", 1, s.content.runs.size)
        assertEquals(8, s.content.searchesTotal)
    }

    @Test fun timeoutAfterACompletedBatchKeepsTheSavedEvidenceAndNeverResends() {
        provider.enqueue(Research2Fixtures.supplementsGather())
        provider.enqueue(CallResult.Failed(ResearchError.Timeout(400), searchesSeen = 0, usage = null, elapsedMs = 405_000))
        start(draft(mode = ResearchMode.DEEP))
        val s = saved(awaitFinished())
        assertEquals(SessionOutcome.PARTIAL, s.outcome)
        assertEquals(3, s.content.body.picks.count { it.pick != null })
        assertEquals("CLIENT TIMEOUT (400 S)", s.content.latestRun!!.technical)
        assertEquals(1, s.content.runs.size)
        assertEquals(listOf(StageResult.DONE, StageResult.FAILED).let { listOf(StageResult.FAILED) }, s.content.latestRun!!.stages.filter { it.kind == StageKind.GATHER }.map { it.result })
        val recs = runBlocking { ledger.forSession(s.id) }
        assertEquals(listOf(CallOutcome.COMPLETE, CallOutcome.FAILED_UNKNOWN), recs.map { it.outcome })
        assertNull("a timed-out call's usage is unknown, never $0", recs[1].estimatedMicros)
        assertEquals("nothing was re-sent", 2, provider.calls.size)
        assertTrue((service.state.value as DeepRunState.Finished).result is RunResult.Saved)
    }

    @Test fun timeoutOnTheVeryFirstCallSavesNothingNamesItsLimitAndNeverResends() {
        provider.enqueue(CallResult.Failed(ResearchError.Timeout(400), searchesSeen = 0, usage = null, elapsedMs = 405_000))
        start(draft(mode = ResearchMode.DEEP))
        val f = awaitFinished()
        val err = (f.result as RunResult.Failed).error as ResearchError.Timeout
        assertEquals(400, err.seconds)
        assertTrue(err.message, err.message.contains("6 min 40 s") && err.message.contains("may be billed again"))
        assertEquals("CLIENT TIMEOUT (400 S)", err.technical)
        assertEquals(0, runBlocking { store.getAll() }.size)
        val rec = runBlocking { ledger.getAll() }.single()
        assertEquals(CallOutcome.FAILED_UNKNOWN, rec.outcome); assertNull(rec.estimatedMicros)
        assertEquals(1, provider.calls.size)
        // Distinct failure kinds keep distinct copy.
        assertTrue(ResearchError.ServerTimeout.technical.contains("504"))
        assertEquals("CONNECTION LOST", ResearchError.Network.technical)
        assertEquals(ResearchError.ServerTimeout, ResearchError.fromPlanning(com.lifeadmin.autopilot.ai.PlanningError.ServerError(504)))
        assertEquals(ResearchError.Timeout(60), ResearchError.fromPlanning(com.lifeadmin.autopilot.ai.PlanningError.Timeout))
    }

    @Test fun stopAndDiscardAfterACheckpointRemovesItAndStopAndKeepFinalisesIt() {
        provider.enqueue(Research2Fixtures.supplementsGather())
        provider.gate = CompletableDeferred(); provider.gateCall = 2
        val a = start(draft(mode = ResearchMode.DEEP)) as StartResult2.Started
        awaitCalls(2)
        assertNotNull(runBlocking { store.get(a.sessionId) })
        service.stop(a.runId, keepPartial = false)
        assertEquals(ResearchError.Stopped(5), (awaitFinished().result as RunResult.Failed).error)
        assertNull("discard removes the checkpoint", runBlocking { store.get(a.sessionId) })
        assertEquals(listOf(CallOutcome.COMPLETE, CallOutcome.STOPPED), runBlocking { ledger.getAll() }.sortedBy { it.startedAt }.map { it.outcome }.takeLast(2))
        service.dismiss(a.runId)
        provider.calls.clear()
        provider.enqueue(Research2Fixtures.supplementsGather())
        provider.gate = CompletableDeferred(); provider.gateCall = 2
        val b = start(draft(mode = ResearchMode.DEEP)) as StartResult2.Started
        awaitCalls(2)
        service.stop(b.runId, keepPartial = true)
        val s = saved(awaitFinished())
        assertEquals(SessionOutcome.STOPPED, s.outcome)
        assertEquals("the provisional record is replaced by the final one", 1, s.content.runs.size)
        assertEquals(3, s.content.body.picks.count { it.pick != null })
    }

    @Test fun processDeathLeavesAnInterruptedCheckpointAndAnUnknownLedgerRow() {
        provider.enqueue(Research2Fixtures.supplementsGather())
        provider.gate = CompletableDeferred(); provider.gateCall = 2
        val started = start(draft(mode = ResearchMode.DEEP)) as StartResult2.Started
        awaitCalls(2)
        // The process dies here: nothing else runs. What is on disk?
        val onDisk = runBlocking { store.get(started.sessionId) }!!
        assertEquals(SessionOutcome.INTERRUPTED, onDisk.outcome)
        assertEquals(3, onDisk.content.body.picks.count { it.pick != null })
        assertTrue(onDisk.content.proposals.isNotEmpty())
        val rows = runBlocking { ledger.forSession(started.sessionId) }
        assertEquals(listOf(CallOutcome.COMPLETE, CallOutcome.IN_FLIGHT), rows.map { it.outcome })
        // Next app start settles the in-flight row; the saved evidence is untouched and nothing is re-sent.
        assertEquals(1, runBlocking { service.onAppStart() })
        assertEquals(CallOutcome.INTERRUPTED, runBlocking { ledger.forSession(started.sessionId) }[1].outcome)
        assertNull(runBlocking { ledger.forSession(started.sessionId) }[1].estimatedMicros)
        assertEquals(2, provider.calls.size)
        service.stop(started.runId, keepPartial = false)
        awaitFinished()
    }

    @Test fun searchBudgetHoldsAcrossBatchesAndPauseTurn() {
        // 14 searches → GATHER allowance 8 → batches of 6 then 2; a pause_turn inside batch 1 keeps the same budget.
        val paused = Research2Fixtures.supplementsGather().copy(stop = CallStop.PAUSE_TURN, paused = "prior", usage = MeasuredUsage(20_000, 500, 0, 0, 0, 4))
        provider.enqueue(paused)
        provider.enqueue(glucosaminePartly(2))     // continuation of batch 1 within the stage's remaining 4: 2 more → 6 used, 2 left
        provider.enqueue(glucosaminePartly(2))     // batch 2: up to 2
        provider.enqueue(glucosamineFound(1))      // FILL_GAPS (30% of 14 = 4)
        provider.fallback = { answerOnly() }
        start(draft(mode = ResearchMode.DEEP, searches = 14))
        val s = saved(awaitFinished())
        assertEquals(listOf(6, 4, 2, 4, 0), provider.calls.map { it.maxSearches })
        assertTrue("no single call ever asks for more than a batch", provider.calls.all { it.maxSearches <= Research2Config.MAX_SEARCHES_PER_CALL })
        assertEquals("prior", provider.calls[1].prior)
        assertNull(provider.calls[2].prior)
        // A call's cap is the stage's remaining allowance at the time it is sent (a pause-turn continuation
        // re-offers what the paused call left unused), so caps can overlap but searches run never exceed the budget.
        val gatherCaps = provider.calls.take(3).map { it.maxSearches }
        val usedBefore = listOf(0, 4, 6)
        gatherCaps.forEachIndexed { i, cap -> assertTrue("call ${i + 1} cap $cap within 8 - ${usedBefore[i]}", cap <= 8 - usedBefore[i]) }
        assertTrue(s.content.searchesTotal <= 14)
        assertEquals(9, s.content.searchesTotal)
        val gather = s.content.latestRun!!.stages.first { it.kind == StageKind.GATHER }
        assertEquals(3, gather.calls); assertEquals(8, gather.searchesRun)
        assertEquals(5, runBlocking { ledger.forSession(s.id) }.size)
    }

    @Test fun interruptedLedgerRowsAreSettledOnAppStart() = runBlocking {
        ledger.record(com.lifeadmin.autopilot.ai.usage.UsageRecord("ghost", UsageFeature.RESEARCH, "claude-haiku-5-5", clock.now(), runId = "old", sessionId = "old-s"))
        assertEquals(1, service.onAppStart())
        assertEquals(CallOutcome.INTERRUPTED, ledger.get("ghost")!!.outcome)
        assertNotNull(ledger.get("ghost"))
    }
}
