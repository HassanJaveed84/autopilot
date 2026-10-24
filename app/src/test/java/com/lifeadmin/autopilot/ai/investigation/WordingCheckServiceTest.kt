package com.lifeadmin.autopilot.ai.investigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.CallStop
import com.lifeadmin.autopilot.ai.research2.StageKind
import com.lifeadmin.autopilot.ai.research2.StageResult
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.InMemoryUsageLedger
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.reminders.ReminderEngine
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
import com.lifeadmin.autopilot.testing.FAKE_KEY
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeCallProvider
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakePlanningProvider
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * N4, model-assisted part, through the real [InvestigationService] with scripted replies (no paid call). A paraphrase
 * can go beyond its cited text without any checkable detail ("increased lean mass" → "builds muscle strength"); the
 * lexical checks cannot see that. For a consequential objective the authorization includes one extra call without
 * search that compares each cited sentence with its verbatim cited text. Its findings only qualify or remove wording;
 * a span it quotes that is not the sentence's own words is not trusted; if it cannot run, the answer says so; a
 * simple fact (R-N(a)) never gets the extra call.
 */
@RunWith(AndroidJUnit4::class)
class WordingCheckServiceTest {
    private val at = Instant.parse("2026-10-24T09:00:00Z")
    private val clock = FakeClock(at)
    private val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = at.minusSeconds(3600), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION))
    private val provider = FakeCallProvider()
    private val ledger = InMemoryUsageLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var store: InvestigationRepository
    private lateinit var service: InvestigationService

    @Before fun setUp() {
        db = inMemoryDatabase()
        val zone = FakeZoneProvider(ZoneOffset.UTC)
        val ids = SequentialIds("wording")
        val keys = InMemoryApiKeyStore(FAKE_KEY)
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        val tasks = TaskRepository(db, clock, ids, zone::current, engine, log)
        store = InvestigationRepository(db)
        val planning = AiPlanningService(settings, keys, FakePlanningProvider(), tasks, clock, zone, ids) { true }
        service = InvestigationService(settings, keys, planning, provider, tasks, store, ledger, clock, zone, ids, scope, { true }, { Locale.US })
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun run(question: String, searches: Int = 3): Pair<Turn, TurnResult> = runBlocking {
        val ready = service.prepare(question, null, null, false) as PrepareResult.Ready
        val cfg = ResearchPresets.resolve(ResearchDepth.STANDARD, settings.current(), searches = searches, limitCents = 300)
        val inv = store.get(ready.investigationId)!!
        val auth = service.authorization(inv, inv.turns.first { it.id == ready.turn.id }, service.contextItems(null, ResearchScope()), cfg)
        assertTrue(service.authorize(ready.investigationId, ready.turn.id, auth, cfg) is StartResult.Started)
        val finished = withTimeout(10_000) { service.state.filterIsInstance<TurnRunState.Finished>().first() }
        val turn = store.get(finished.investigationId)!!.turns.first { it.id == finished.turnId }
        turn to turn.result!!
    }

    private val review = RawCitation("https://www.reviews.example/creatine-older-adults", "Creatine in older adults: a systematic review", "Creatine with resistance training increased lean mass in older adults.")
    private val question = "Does creatine as a supplement help older adults?"

    private fun gather() = CallResult.Done(
        listOf(
            RawSegment("## ANSWER\n", emptyList()),
            RawSegment("Creatine builds muscle strength in older adults [c1].", listOf(review)),
            RawSegment("\n## CLAIM c1 · type=fact · load=yes · scope=creatine with resistance training in older adults · asof=- · volatile=no · needs=review · needs_input=no\nCreatine with resistance training increases lean mass in older adults.\nEVIDENCE: (kind=review · origin=a systematic review) ", emptyList()),
            RawSegment("Increased lean mass in older adults.", listOf(review)),
            RawSegment("\n", emptyList()),
        ),
        listOf(RawSearch("creatine older adults systematic review", listOf(RawSearchResult(review.url, review.title!!, null)))),
        MeasuredUsage(9_000, 700, 0, 0, 0, 1), CallStop.END, 10, "msg_gather",
    )

    private fun check(text: String) = CallResult.Done(listOf(RawSegment(text, emptyList())), emptyList(), MeasuredUsage(900, 40, 0, 0, 0, 0), CallStop.END, 5, "msg_check")

    private fun lead(r: TurnResult) = r.answer.lead.joinToString(" ") { it.text }

    @Test fun aParaphraseThatGoesBeyondItsCitedTextIsQualifiedByTheCheck() {
        provider.enqueue(gather(), check("S1 · overstated · \"builds muscle strength\""))
        val (turn, r) = run(question)
        assertTrue("the authorization disclosed the extra call", turn.authorization!!.wordingCheck)
        assertEquals(2, provider.calls.size)
        val call = provider.calls[1]
        assertEquals("the check never searches", 0, call.maxSearches)
        assertEquals(WordingCheck.SYSTEM, call.system)
        assertTrue(call.user, call.user.contains("S1: Creatine builds muscle strength in older adults.") && call.user.contains("cited: \"Creatine with resistance training increased lean mass in older adults.\""))
        assertEquals("the lexical checks alone find nothing here", ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
        assertEquals("Creatine builds muscle strength in older adults (the cited sources do not show: “builds muscle strength”).", lead(r))
        assertEquals(StageResult.DONE, r.stages.single { it.kind == StageKind.CHECK_WORDING }.result)
        val rows = runBlocking { ledger.getAll() }.filter { it.runId == turn.id }
        assertEquals(listOf("CHECK_WORDING", "GATHER"), rows.mapNotNull { it.stage }.sorted())
        assertTrue("its cost is counted", turn.usage.spentMicros > 0)
    }

    @Test fun aSpanThatIsNotTheSentencesOwnWordsIsNotTrusted() {
        provider.enqueue(gather(), check("S1 · unsupported · \"reverses ageing\""))
        val (_, r) = run(question)
        assertEquals("Not shown by the cited sources: Creatine builds muscle strength in older adults.", lead(r))
    }

    @Test fun aSupportedVerdictNeverOverridesTheLexicalChecks() {
        provider.enqueue(
            CallResult.Done(
                listOf(
                    RawSegment("## ANSWER\n", emptyList()),
                    RawSegment("Creatine increased lean mass by 9 kg in older adults [c1].", listOf(review)),
                    RawSegment("\n## CLAIM c1 · type=fact · load=yes · scope=creatine in older adults · asof=- · volatile=no · needs=review · needs_input=no\nCreatine with resistance training increases lean mass in older adults.\nEVIDENCE: (kind=review · origin=a systematic review) ", emptyList()),
                    RawSegment("Increased lean mass.", listOf(review)),
                ),
                emptyList(), MeasuredUsage(9_000, 700, 0, 0, 0, 1), CallStop.END, 10, "msg_gather2",
            ),
            check("S1 · supported"),
        )
        val (_, r) = run(question)
        assertEquals("Creatine increased lean mass by 9 kg in older adults (the cited sources do not show: 9 kg).", lead(r))
    }

    @Test fun ifTheCheckCannotRunTheAnswerSaysSoAndKeepsItsFindings() {
        provider.enqueue(gather(), CallResult.Failed(ResearchError.ServerError(529), 1, null, 3_000))
        val (turn, r) = run(question)
        assertEquals(TurnState.DONE, turn.state)
        assertEquals(TurnEnding.COMPLETE, r.ending)
        assertEquals(ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
        assertTrue(r.answer.caveats.toString(), r.answer.caveats.any { it.contains("wording against the cited pages did not run (the check call failed)") })
        assertEquals(StageResult.FAILED, r.stages.single { it.kind == StageKind.CHECK_WORDING }.result)
        assertTrue(runBlocking { ledger.getAll() }.any { it.stage == "CHECK_WORDING" && it.outcome != CallOutcome.COMPLETE })
    }

    @Test fun aSimpleFactNeverGetsTheExtraCall() {
        val replay = LiveReplay.load("fixtures/live/R-N-a-2026-10-24-run3.replies.json")
        provider.enqueue(replay.exchanges.single().reply)
        val (turn, r) = run(replay.question)
        assertFalse(turn.authorization!!.wordingCheck)
        assertEquals("R-N(a) stays one call", 1, provider.calls.size)
        assertTrue(r.stages.none { it.kind == StageKind.CHECK_WORDING })
        assertTrue(r.answer.caveats.none { it.contains("wording") })
    }

    @Test fun theEstimateIncludesTheCheckWhenItIsPlanned() {
        val ready = runBlocking { service.prepare(question, null, null, false) as PrepareResult.Ready }
        val cfg = ResearchPresets.resolve(ResearchDepth.STANDARD, runBlocking { settings.current() }, searches = 3, limitCents = 300)
        val inv = runBlocking { store.get(ready.investigationId)!! }
        val auth = runBlocking { service.authorization(inv, inv.turns.first { it.id == ready.turn.id }, service.contextItems(null, ResearchScope()), cfg) }
        assertEquals(cfg.estimate()!!.highMicros + WordingCheck.estimate(cfg.model)!!.highMicros, auth.estimateHighMicros)
    }
}
