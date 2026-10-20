package com.lifeadmin.autopilot.ai.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Hardening finding C: observed billable usage is never lost, unknown usage is never $0, and the dashboard reconciles with the ledger. */
class UsageAccountingTest {
    private val base = UsageRecord("c1", UsageFeature.RESEARCH, "claude-haiku-5-5", Instant.EPOCH, runId = "r1", sessionId = "s1")

    @Test fun observedSearchesWithUnknownTokensAreAKnownFloor() {
        val r = UsageRecord.priced(base, MeasuredUsage(null, null, null, null, null, 2), CallOutcome.FAILED_UNKNOWN, 3_000)
        assertEquals(UsageStatus.PARTIAL, r.usageStatus)
        assertEquals("two searches at $0.01", 20_000L, r.estimatedMicros)
        assertTrue(r.isFloor); assertTrue(r.tokensUnknown)
        val t = UsageTotals.of(listOf(r))
        assertEquals(20_000L, t.estimate.micros); assertTrue(t.partial); assertEquals(0, t.unknownCalls); assertEquals(2, t.searches)
    }

    @Test fun failureWithCompleteProviderUsageIsPricedInFull() {
        val r = UsageRecord.priced(base, MeasuredUsage(1_000, 500, 0, 0, 0, 1), CallOutcome.FAILED_UNKNOWN, 3_000, "msg_f")
        assertEquals("known counts are known whatever the outcome", UsageStatus.COMPLETE, r.usageStatus)
        assertEquals(100L + 250L + 10_000L, r.estimatedMicros)
        assertFalse(r.isFloor); assertFalse(r.tokensUnknown)
    }

    @Test fun rejectedRequestIsAssumedZeroAndSaysSo() {
        val r = UsageRecord.priced(base, null, CallOutcome.FAILED_NOT_BILLED, 300)
        assertEquals(0L, r.estimatedMicros)
        assertNull(r.usage)
        val t = UsageTotals.of(listOf(r))
        assertEquals(1, t.assumedZero); assertFalse(t.partial); assertEquals(0, t.unknownCalls)
        assertTrue(CallOutcome.FAILED_NOT_BILLED.label.contains("assumed"))
    }

    @Test fun nothingReportedIsUnknownNeverZero() {
        val failed = UsageRecord.priced(base, null, CallOutcome.FAILED_UNKNOWN, 60_000)
        val stopped = UsageRecord.priced(base.copy(id = "c2"), null, CallOutcome.STOPPED, null)
        val dead = base.copy(id = "c3", outcome = CallOutcome.INTERRUPTED, usageStatus = UsageStatus.UNKNOWN)
        for (r in listOf(failed, stopped, dead)) { assertNull(r.estimatedMicros); assertTrue(r.isFloor); assertFalse(r.tokensUnknown) }
        val t = UsageTotals.of(listOf(failed, stopped, dead))
        assertEquals(0L, t.estimate.micros); assertTrue(t.partial); assertEquals(3, t.unknownCalls)
    }

    @Test fun stoppedCallWithSearchesSeenKeepsThemAsAFloor() {
        val r = UsageRecord.priced(base, MeasuredUsage(null, null, webSearches = 3), CallOutcome.STOPPED, 9_000)
        assertEquals(UsageStatus.PARTIAL, r.usageStatus); assertEquals(30_000L, r.estimatedMicros)
    }

    @Test fun dashboardGroupsReconcileWithTheLedger() {
        val t0 = Instant.parse("2026-10-20T10:00:00Z")
        fun rec(id: String, run: String?, outcome: CallOutcome, usage: MeasuredUsage?, msg: String? = null, feature: UsageFeature = UsageFeature.RESEARCH, model: String = "claude-sonnet-5-5", minutes: Long = 0) =
            UsageRecord.priced(UsageRecord(id, feature, model, t0.plusSeconds(minutes * 60), runId = run, sessionId = run?.let { "s-$it" }), usage, outcome, 1_000, msg)
        val all = listOf(
            rec("a1", "run-a", CallOutcome.COMPLETE, MeasuredUsage(41_000, 2_600, 0, 0, 0, 5), "m1"),
            rec("a2", "run-a", CallOutcome.COMPLETE, MeasuredUsage(12_000, 300, 900, 0, 2_100, 2), "m2", minutes = 1),
            rec("b1", "run-b", CallOutcome.COMPLETE, MeasuredUsage(20_000, 500, 0, 0, 0, 2), "m3", minutes = 5),
            rec("b2", "run-b", CallOutcome.FAILED_UNKNOWN, MeasuredUsage(null, null, null, null, null, 2), minutes = 6),
            rec("c1", "run-c", CallOutcome.FAILED_NOT_BILLED, null, minutes = 9),
            rec("p1", null, CallOutcome.COMPLETE, MeasuredUsage(2_000, 400, 0, 0, 0, 0), "m4", feature = UsageFeature.PLANNING, model = "claude-haiku-5-5", minutes = 12),
            UsageRecord("d1", UsageFeature.RESEARCH_CONTINUATION, "claude-sonnet-5-5", t0.plusSeconds(900), runId = "run-d", sessionId = "s-run-d"),
        )
        val groups = UsageGrouping.groups(all)
        assertEquals(listOf("run-d", "p1", "run-c", "run-b", "run-a"), groups.map { it.key })
        assertEquals("every record lands in exactly one group", all.map { it.id }.sorted(), groups.flatMap { g -> g.records.map { it.id } }.sorted())
        val total = UsageTotals.of(all)
        assertEquals(total.estimate.micros, groups.sumOf { it.totals.estimate.micros })
        assertEquals(total.searches, groups.sumOf { it.totals.searches })
        assertEquals(total.calls, groups.sumOf { it.totals.calls })
        assertEquals(total.unknownCalls, groups.sumOf { it.totals.unknownCalls })
        assertEquals(total.assumedZero, groups.sumOf { it.totals.assumedZero })
        assertEquals(total.partial, groups.any { it.totals.partial })
        // Per-group honesty.
        assertFalse(groups.first { it.key == "run-a" }.totals.partial)
        assertTrue("run-b's second call is a floor (searches known, tokens not)", groups.first { it.key == "run-b" }.totals.partial)
        assertEquals(0, groups.first { it.key == "run-b" }.totals.unknownCalls)
        assertEquals(1, groups.first { it.key == "run-c" }.totals.assumedZero)
        assertTrue("an in-flight row makes its group a floor", groups.first { it.key == "run-d" }.totals.partial)
        assertTrue(groups.first { it.key == "run-d" }.continuation)
        // The cache tokens priced at their own rates, once.
        val a2 = all[1]
        assertEquals((12_000 * 2.0 + 300 * 10.0 + 900 * 2.5 + 2_100 * 0.10 + 2 * 10_000).toLong(), a2.estimatedMicros)
    }
}
