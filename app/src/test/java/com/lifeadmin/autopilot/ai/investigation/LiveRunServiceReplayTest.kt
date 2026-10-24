package com.lifeadmin.autopilot.ai.investigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.StageResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset
import java.util.Locale

/**
 * Document F R-N(a) through the real [InvestigationService] with the live run's own replies queued in
 * order (`docs/live-eval/R-N-a-2026-10-23.md`, defect E). The live run spent a second billed call and
 * search on a fact the first call had already settled on the issuer's own page.
 */
@RunWith(AndroidJUnit4::class)
class LiveRunServiceReplayTest {
    private val replay = LiveReplay.load("fixtures/live/R-N-a-2026-10-23.replies.json")
    private val clock = FakeClock(replay.ranAt)
    private val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = replay.ranAt.minusSeconds(3600), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION))
    private val provider = FakeCallProvider()
    private val ledger = InMemoryUsageLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var store: InvestigationRepository
    private lateinit var service: InvestigationService

    @Before fun setUp() {
        db = inMemoryDatabase()
        val zone = FakeZoneProvider(ZoneOffset.UTC)
        val ids = SequentialIds("live")
        val keys = InMemoryApiKeyStore(FAKE_KEY)
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        val tasks = TaskRepository(db, clock, ids, zone::current, engine, log)
        store = InvestigationRepository(db)
        val planning = AiPlanningService(settings, keys, FakePlanningProvider(), tasks, clock, zone, ids) { true }
        service = InvestigationService(settings, keys, planning, provider, tasks, store, ledger, clock, zone, ids, scope, { true }, { Locale.US })
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    @Test fun theLiveRunStopsAfterGatherOnceTheIssuersPageSettlesTheFact() = runBlocking {
        replay.exchanges.forEach { provider.enqueue(it.reply) }
        val ready = service.prepare(replay.question, null, null, false) as PrepareResult.Ready
        val cfg = ResearchPresets.resolve(ResearchDepth.STANDARD, settings.current(), searches = 3, limitCents = 300)
        val inv = store.get(ready.investigationId)!!
        val auth = service.authorization(inv, inv.turns.first { it.id == ready.turn.id }, service.contextItems(null, ResearchScope()), cfg)
        assertTrue(service.authorize(ready.investigationId, ready.turn.id, auth, cfg) is StartResult.Started)
        val finished = withTimeout(10_000) { service.state.filterIsInstance<TurnRunState.Finished>().first() }
        val turn = store.get(finished.investigationId)!!.turns.first { it.id == finished.turnId }
        val result = turn.result!!

        assertEquals("one billed call, as R-N(a) expects", 1, provider.calls.size)
        assertEquals(1, ledger.getAll().size)
        assertEquals(1, turn.usage.webSearches)
        val fill = result.stages.first { it.kind == com.lifeadmin.autopilot.ai.research2.StageKind.FILL_GAPS }
        assertEquals(StageResult.SKIPPED, fill.result)
        assertEquals("every load-bearing claim is established", fill.note)
        assertEquals(ResultState.ANSWERED, result.state)
        val c1 = result.claim("c1")!!
        assertEquals(ClaimStatus.ESTABLISHED, c1.status)
        assertTrue(c1.statusReason, c1.statusReason.startsWith("The issuing source"))
    }
}
