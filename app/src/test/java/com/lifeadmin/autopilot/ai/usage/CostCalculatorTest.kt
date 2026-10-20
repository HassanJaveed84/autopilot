package com.lifeadmin.autopilot.ai.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CostCalculatorTest {
    @Test fun pricesEveryDocumentedModelFromTheCatalog() {
        fun usd(model: String, u: MeasuredUsage) = CostCalculator.price(model, u).estimate!!.usd
        val mil = 1_000_000L
        // 100k input (Haiku's standard tier) + 1M output.
        assertEquals(0.51, usd("claude-haiku-5-5", MeasuredUsage(100_000, mil, webSearches = 0)), 1e-6)
        assertEquals(10.20, usd("claude-sonnet-5-5", MeasuredUsage(100_000, mil, webSearches = 0)), 1e-6)
        assertEquals(20.40, usd("claude-opus-5-5", MeasuredUsage(100_000, mil, webSearches = 0)), 1e-6)
        assertEquals(51.00, usd("claude-fable-5-1", MeasuredUsage(100_000, mil, webSearches = 0)), 1e-6)
        assertEquals(0.05, usd("claude-haiku-5-5", MeasuredUsage(0, 0, webSearches = 5)), 1e-6)
        assertEquals(0.125 + 0.20 + 0.01, usd("claude-haiku-5-5", MeasuredUsage(0, 0, mil, mil, mil, 0)), 1e-6)
        assertEquals(0.10, usd("claude-sonnet-5-5", MeasuredUsage(0, 0, 0, 0, mil, 0)), 1e-6)
        assertEquals(0.25, usd("claude-fable-5-1", MeasuredUsage(0, 0, 0, 0, mil, 0)), 1e-6)
    }

    @Test fun haikuLongContextTierAppliesAbove100kInputTokens() {
        val under = CostCalculator.price("claude-haiku-5-5", MeasuredUsage(100_000, 0, webSearches = 0)).estimate!!.usd
        val over = CostCalculator.price("claude-haiku-5-5", MeasuredUsage(100_001, 0, webSearches = 0)).estimate!!.usd
        assertEquals(0.01, under, 1e-6)
        assertEquals(0.0500005, over, 1e-6)
    }

    @Test fun unknownCountsArePartialAndUnknownModelsHaveNoPrice() {
        val p = CostCalculator.price("claude-haiku-5-5", MeasuredUsage(null, null, webSearches = 2))
        assertTrue(p.partial)
        assertEquals(0.02, p.estimate!!.usd, 1e-9)
        assertNull(CostCalculator.price("claude-mystery-9", MeasuredUsage(10, 10, webSearches = 0)).estimate)
        assertEquals(PricingCatalog.VERSION, p.priceVersion)
    }

    @Test fun preRunEstimateFollowsTheDesignFormulaWithARange() {
        val e = CostCalculator.estimateRun("claude-sonnet-5-5", 10, 6_000)!!
        assertEquals(0.392, e.usd, 1e-6)
        assertEquals(0.392 * 0.7, e.lowUsd, 1e-6)
        assertEquals(0.392 * 1.3, e.highUsd, 1e-6)
        assertEquals("~$0.27–$0.51", UsdEstimate.formatRange(e.lowUsd, e.highUsd))
        val q = CostCalculator.estimateRun("claude-haiku-5-5", 3, 1_500)!!
        assertEquals(0.03465, q.usd, 1e-6)
        assertNull(CostCalculator.estimateRun("nope", 3, 1_500))
        assertEquals("$0.00", UsdEstimate.format(0.0))
        assertEquals("<$0.01", UsdEstimate.format(0.004))
        assertEquals("~$1.10", UsdEstimate.formatRange(1.10, 1.104))
        assertEquals("~20×", PricingCatalog.relativeCost("claude-sonnet-5-5"))
        assertEquals("1×", PricingCatalog.relativeCost("claude-haiku-5-5"))
    }

    @Test fun usageRecordStatusesAreHonest() {
        val base = UsageRecord("c1", UsageFeature.RESEARCH, "claude-haiku-5-5", Instant.EPOCH, runId = "r1", sessionId = "s1")
        val complete = UsageRecord.priced(base, MeasuredUsage(1000, 500, 0, 0, 0, 2), CallOutcome.COMPLETE, 1200, "msg_1")
        assertEquals(UsageStatus.COMPLETE, complete.usageStatus)
        assertFalse(complete.isFloor)
        assertEquals(20_000L + 100L + 250L, complete.estimatedMicros)
        val notBilled = UsageRecord.priced(base, null, CallOutcome.FAILED_NOT_BILLED, 300)
        assertEquals(0L, notBilled.estimatedMicros)
        assertEquals(UsageStatus.COMPLETE, notBilled.usageStatus)
        val unknown = UsageRecord.priced(base, null, CallOutcome.FAILED_UNKNOWN, 60_000)
        assertEquals(UsageStatus.UNKNOWN, unknown.usageStatus)
        assertNull(unknown.estimatedMicros)
        assertTrue(unknown.isFloor)
        val stopped = UsageRecord.priced(base, MeasuredUsage(null, null, webSearches = 3), CallOutcome.STOPPED, 9_000)
        assertEquals(UsageStatus.PARTIAL, stopped.usageStatus)
        assertEquals(30_000L, stopped.estimatedMicros)
        val totals = UsageTotals.of(listOf(complete, notBilled, unknown, stopped, base))
        assertEquals(20_350L + 30_000L, totals.estimate.micros)
        assertTrue(totals.partial)
        assertEquals(1, totals.unknownCalls)
        assertEquals(5, totals.searches)
    }

    @Test fun inMemoryLedgerDeduplicatesProviderMessageIds() = kotlinx.coroutines.runBlocking {
        val ledger = InMemoryUsageLedger()
        val base = UsageRecord("c1", UsageFeature.RESEARCH, "claude-haiku-5-5", Instant.EPOCH, runId = "r1", sessionId = "s1")
        ledger.record(UsageRecord.priced(base, MeasuredUsage(1000, 500, 0, 0, 0, 2), CallOutcome.COMPLETE, 1200, "msg_dup"))
        ledger.record(UsageRecord.priced(base.copy(id = "c2"), MeasuredUsage(1000, 500, 0, 0, 0, 2), CallOutcome.COMPLETE, 1200, "msg_dup"))
        assertEquals(1, ledger.getAll().size)
        ledger.record(UsageRecord("c3", UsageFeature.PLANNING, "claude-haiku-5-5", Instant.EPOCH.plusSeconds(5)))
        assertEquals(1, ledger.settleInterrupted())
        assertEquals(CallOutcome.INTERRUPTED, ledger.get("c3")!!.outcome)
        assertEquals(UsageStatus.UNKNOWN, ledger.get("c3")!!.usageStatus)
        assertEquals(1, ledger.deleteOlderThan(Instant.EPOCH.plusSeconds(1)))
        assertEquals(1, ledger.getAll().size)
    }
}
