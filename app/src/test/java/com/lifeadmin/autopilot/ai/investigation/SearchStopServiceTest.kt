package com.lifeadmin.autopilot.ai.investigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.CallStop
import com.lifeadmin.autopilot.ai.research2.StageResult
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.ai.usage.InMemoryUsageLedger
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
import java.time.ZoneOffset
import java.util.Locale

/**
 * N2 through the real [InvestigationService]: the turn's record states how many of the authorized searches were used
 * and why it stopped, from the engine's own stage records; a reply's remark about its call allowance never stands in
 * for either, and a real load-bearing gap is still reported.
 */
@RunWith(AndroidJUnit4::class)
class SearchStopServiceTest {
    private val rerun = LiveReplay.load("fixtures/live/R-N-a-2026-10-24-rerun.replies.json")
    private val clock = FakeClock(rerun.ranAt)
    private val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = rerun.ranAt.minusSeconds(3600), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION))
    private val provider = FakeCallProvider()
    private val ledger = InMemoryUsageLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var store: InvestigationRepository
    private lateinit var service: InvestigationService

    @Before fun setUp() {
        db = inMemoryDatabase()
        val zone = FakeZoneProvider(ZoneOffset.UTC)
        val ids = SequentialIds("stop")
        val keys = InMemoryApiKeyStore(FAKE_KEY)
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        val tasks = TaskRepository(db, clock, ids, zone::current, engine, log)
        store = InvestigationRepository(db)
        val planning = AiPlanningService(settings, keys, FakePlanningProvider(), tasks, clock, zone, ids) { true }
        service = InvestigationService(settings, keys, planning, provider, tasks, store, ledger, clock, zone, ids, scope, { true }, { Locale.US })
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun run(question: String, searches: Int): Pair<Turn, TurnResult> = runBlocking {
        val ready = service.prepare(question, null, null, false) as PrepareResult.Ready
        val cfg = ResearchPresets.resolve(ResearchDepth.STANDARD, settings.current(), searches = searches, limitCents = 300)
        val inv = store.get(ready.investigationId)!!
        val auth = service.authorization(inv, inv.turns.first { it.id == ready.turn.id }, service.contextItems(null, ResearchScope()), cfg)
        assertTrue(service.authorize(ready.investigationId, ready.turn.id, auth, cfg) is StartResult.Started)
        val finished = withTimeout(10_000) { service.state.filterIsInstance<TurnRunState.Finished>().first() }
        val turn = store.get(finished.investigationId)!!.turns.first { it.id == finished.turnId }
        turn to turn.result!!
    }

    private fun unresolved(r: TurnResult) = r.answer.sections.filter { it.kind == SectionKind.UNRESOLVED }.flatMap { it.items }.map { it.text }

    @Test fun stoppingOnSufficiencyIsReportedAsSuchNotAsAnAllowance() {
        provider.enqueue(rerun.exchanges.single().reply)
        val (turn, r) = run(rerun.question, searches = 3)
        assertEquals(1, provider.calls.size)
        assertTrue("the call was told its share of the budget", provider.calls.single().user.contains("Searches allowed on this call: 1 of the 3 authorized"))
        assertEquals("Used 1 of 3 authorized searches; stopped once every load-bearing claim was established.", ResearchSummary.line(r, turn.authorization!!.config.searchBudget))
        assertEquals(ResultState.ANSWERED, r.state)
        assertTrue(unresolved(r).toString(), unresolved(r).none { it.contains("allowance", true) || it.contains("one search", true) })
    }

    private fun openClaimReply(extraUnresolved: String) = CallResult.Done(
        listOf(
            RawSegment("## ANSWER\nThe library's Sunday hours could not be confirmed [c1]. I was limited to one search on this call.\n## CLAIM c1 · type=fact · load=yes · scope=Riverside library, Sunday hours · asof=- · volatile=no · needs=direct · needs_input=no\nThe Riverside library opens on Sundays.\n## UNRESOLVED\n- Sunday hours — searched the library's own site; no Sunday hours are listed, and the search allowance was one search.\n$extraUnresolved", emptyList()),
        ),
        listOf(RawSearch("Riverside library Sunday hours", emptyList())),
        MeasuredUsage(300, 400, 0, 0, 0, 1), CallStop.END, 10, "msg_open",
    )

    @Test fun stoppingOnTheBudgetWithAGapOpenSaysSoAndKeepsTheGap() {
        provider.enqueue(openClaimReply(""))
        val (turn, r) = run("When does the Riverside library in Springfield open on Sundays?", searches = 1)
        assertEquals("the one authorized search was used; nothing else was sent", 1, provider.calls.size)
        assertEquals(TurnEnding.BUDGET_SEARCHES, r.ending)
        assertEquals("Used 1 of 1 authorized search; stopped because the authorized searches were used up while a load-bearing question was still open.", ResearchSummary.line(r, turn.authorization!!.config.searchBudget))
        assertTrue("the load-bearing gap is in the main answer", r.answer.caveats.any { it.contains("Riverside library opens on Sundays") })
        assertEquals("the real open question stays; the allowance remark does not", listOf("Sunday hours — searched the library's own site; no Sunday hours are listed."), unresolved(r))
        assertFalse(r.answer.lead.joinToString(" ") { it.text }, r.answer.lead.any { it.text.contains("one search") })
    }

    @Test fun aRealGapWithBudgetLeftIsStillReportedWhenAllStagesRan() {
        provider.enqueue(openClaimReply(""), openClaimReply("- Holiday hours — searched the city calendar; nothing listed.\n"))
        val (turn, r) = run("When does the Riverside library in Springfield open on Sundays?", searches = 3)
        assertEquals(2, provider.calls.size)
        assertEquals(TurnEnding.COMPLETE, r.ending)
        assertEquals("Used 2 of 3 authorized searches; every planned stage ran.", ResearchSummary.line(r, turn.authorization!!.config.searchBudget))
        assertTrue(r.answer.caveats.isNotEmpty())
        assertEquals(listOf("Sunday hours — searched the library's own site; no Sunday hours are listed.", "Holiday hours — searched the city calendar; nothing listed."), unresolved(r))
    }
}
