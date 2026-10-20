package com.lifeadmin.autopilot.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.InMemoryUsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.ai.usage.UsageStatus
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FAKE_KEY
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakePlanningProvider
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.testing.rawPlan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** M2 planning calls land in the same local ledger as research, with honest outcomes. */
@RunWith(AndroidJUnit4::class)
class PlanningUsageLedgerTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 20, 10, 0))
    private val provider = FakePlanningProvider()
    private val ledger = InMemoryUsageLedger()
    private lateinit var service: AiPlanningService

    @Before fun setUp() {
        db = inMemoryDatabase()
        val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0)))
        val repo = TaskRepository(db, clock, SequentialIds(), { CHICAGO }, ScheduleCoordinator.Direct)
        service = AiPlanningService(settings, InMemoryApiKeyStore(FAKE_KEY), provider, repo, clock, FakeZoneProvider(CHICAGO), SequentialIds("p"), ledger = ledger, network = { true })
    }

    @After fun tearDown() { db.close() }

    @Test fun successFailureAndUnknownOutcomesAreRecordedSeparately() = runBlocking {
        provider.next = { PlanningResult.Success(rawPlan("Renew passport"), PlanningUsage(800, 300, "claude-haiku-5-5", cacheWriteTokens = 100, cacheReadTokens = 50, messageId = "msg_p1")) }
        service.proposeNewTask("Renew passport")
        provider.fail(PlanningError.Unauthorized)
        service.proposeNewTask("Book dentist")
        provider.fail(PlanningError.Timeout)
        service.proposeNewTask("Pay council tax")
        val rows = ledger.getAll().sortedBy { it.id }
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.feature == UsageFeature.PLANNING && it.stage == "CAPTURE" && it.model == "claude-haiku-5-5" })
        val ok = rows.first { it.outcome == CallOutcome.COMPLETE }
        assertEquals(UsageStatus.COMPLETE, ok.usageStatus)
        assertEquals(800L, ok.usage!!.inputTokens)
        assertEquals(100L, ok.usage!!.cacheWrite5mTokens)
        assertEquals(50L, ok.usage!!.cacheReadTokens)
        assertEquals("msg_p1", ok.providerMessageId)
        // 800 × $0.10 + 300 × $0.50 + 100 × $0.125 + 50 × $0.01 per MTok = 80 + 150 + 12.5 + 0.5 micro-dollars
        assertEquals(243L, ok.estimatedMicros)
        val unauthorized = rows.first { it.outcome == CallOutcome.FAILED_NOT_BILLED }
        assertEquals(0L, unauthorized.estimatedMicros)
        val timeout = rows.first { it.outcome == CallOutcome.FAILED_UNKNOWN }
        assertNull(timeout.estimatedMicros)
        assertEquals(UsageStatus.UNKNOWN, timeout.usageStatus)
    }
}
