package com.lifeadmin.autopilot.ai.usage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

enum class UsageFeature(val label: String) { PLANNING("Planning"), RESEARCH("Research"), RESEARCH_CONTINUATION("Research · continued"), ASSIST("Assist") }

enum class CallOutcome(val label: String) {
    IN_FLIGHT("In flight"),
    COMPLETE("Complete"),
    TRUNCATED("Cut short"),
    STOPPED("Stopped"),
    /**
     * Rejected before processing (authentication, permission, credit, rate limit, validation): the
     * provider reported no usage and the prompt never ran. Counted as $0 because nothing was reported,
     * not because Anthropic confirmed it; the dashboard says so.
     */
    FAILED_NOT_BILLED("Rejected · $0 assumed"),
    /** Failed after the request may have been processed: usage unknown, except any searches seen (a floor). */
    FAILED_UNKNOWN("Failed · usage unknown"),
    /** The app died while the call was in flight: usage unknown. */
    INTERRUPTED("Interrupted · usage unknown"),
}

enum class UsageStatus { COMPLETE, PARTIAL, UNKNOWN }

/**
 * One billable API call. Never holds prompts, task text, queries or keys — only ids, counts and the
 * local price estimate. Every field the provider did not report stays null.
 */
data class UsageRecord(
    val id: String,
    val feature: UsageFeature,
    val model: String,
    val startedAt: Instant,
    val runId: String? = null,
    val sessionId: String? = null,
    val taskId: String? = null,
    val stage: String? = null,
    val durationMs: Long? = null,
    val usage: MeasuredUsage? = null,
    val outcome: CallOutcome = CallOutcome.IN_FLIGHT,
    val usageStatus: UsageStatus = UsageStatus.UNKNOWN,
    val estimatedMicros: Long? = null,
    val priceVersion: String = PricingCatalog.VERSION,
    val providerMessageId: String? = null,
) {
    val estimate: UsdEstimate? get() = estimatedMicros?.let { UsdEstimate(it, priceVersion = priceVersion) }

    /** Floor estimate for unknown usage: the searches seen are certainly billed. */
    val isFloor: Boolean get() = usageStatus != UsageStatus.COMPLETE

    /** Searches are known but token counts are not: the estimate is a floor with unknown extra token cost. */
    val tokensUnknown: Boolean get() = usage != null && (usage.inputTokens == null || usage.outputTokens == null)

    companion object {
        /** Finalises a record from measured usage with the catalog price. */
        fun priced(base: UsageRecord, usage: MeasuredUsage?, outcome: CallOutcome, durationMs: Long?, providerMessageId: String? = null): UsageRecord {
            // Known counts are known whatever the outcome: a failed call whose usage the provider did report
            // is priced in full; one with only its searches reported is a floor; one with nothing is unknown.
            val status = when {
                outcome == CallOutcome.FAILED_NOT_BILLED -> UsageStatus.COMPLETE
                usage == null || usage.allUnknown -> UsageStatus.UNKNOWN
                usage.anyUnknown -> UsageStatus.PARTIAL
                outcome == CallOutcome.STOPPED || outcome == CallOutcome.INTERRUPTED -> UsageStatus.PARTIAL
                else -> UsageStatus.COMPLETE
            }
            val micros = when {
                outcome == CallOutcome.FAILED_NOT_BILLED -> 0L
                usage == null -> null
                else -> CostCalculator.price(base.model, usage).estimate?.micros
            }
            return base.copy(usage = usage, outcome = outcome, usageStatus = status, durationMs = durationMs, estimatedMicros = micros, providerMessageId = providerMessageId ?: base.providerMessageId)
        }
    }
}

/** Local usage ledger. Implemented by Room (`usage_records`); in tests by [InMemoryUsageLedger]. */
interface UsageLedger {
    /** Inserts or replaces by id. A record whose providerMessageId already exists updates that row instead (no double count). */
    suspend fun record(record: UsageRecord)
    suspend fun get(id: String): UsageRecord?
    fun observeAll(): Flow<List<UsageRecord>>
    suspend fun getAll(): List<UsageRecord>
    suspend fun forSession(sessionId: String): List<UsageRecord>

    /** App start: calls still marked in flight were interrupted by process death; usage is unknown, never $0. */
    suspend fun settleInterrupted(): Int
    suspend fun deleteAll()
    suspend fun deleteOlderThan(cutoff: Instant): Int
}

class InMemoryUsageLedger : UsageLedger {
    private val rows = kotlinx.coroutines.flow.MutableStateFlow<Map<String, UsageRecord>>(emptyMap())
    override suspend fun record(record: UsageRecord) {
        rows.value = rows.value.toMutableMap().also { m ->
            val dup = record.providerMessageId?.let { pid -> m.values.firstOrNull { it.providerMessageId == pid && it.id != record.id } }
            if (dup != null) { m.remove(dup.id); m[record.id] = record } else m[record.id] = record
        }
    }
    override suspend fun get(id: String) = rows.value[id]
    override fun observeAll(): Flow<List<UsageRecord>> = rows.map { it.values.sortedByDescending { r -> r.startedAt } }
    override suspend fun getAll() = rows.value.values.sortedByDescending { it.startedAt }
    override suspend fun forSession(sessionId: String) = rows.value.values.filter { it.sessionId == sessionId }.sortedBy { it.startedAt }
    override suspend fun settleInterrupted(): Int {
        val open = rows.value.values.filter { it.outcome == CallOutcome.IN_FLIGHT }
        rows.value = rows.value.toMutableMap().also { m -> open.forEach { m[it.id] = it.copy(outcome = CallOutcome.INTERRUPTED, usageStatus = UsageStatus.UNKNOWN) } }
        return open.size
    }
    override suspend fun deleteAll() { rows.value = emptyMap() }
    override suspend fun deleteOlderThan(cutoff: Instant): Int {
        val old = rows.value.values.filter { it.startedAt.isBefore(cutoff) }
        rows.value = rows.value.filterValues { !it.startedAt.isBefore(cutoff) }
        return old.size
    }
}

/**
 * Sums for the dashboard; every total says whether some of it is a floor. [assumedZero] counts calls
 * rejected before processing (no usage reported, $0 assumed), so a total can say so.
 */
data class UsageTotals(val estimate: UsdEstimate, val calls: Int, val searches: Int, val partial: Boolean, val unknownCalls: Int, val assumedZero: Int = 0) {
    companion object {
        fun of(records: List<UsageRecord>): UsageTotals {
            var micros = 0L; var searches = 0; var partial = false; var unknown = 0; var assumed = 0
            for (r in records) {
                if (r.outcome == CallOutcome.IN_FLIGHT) { partial = true; continue }
                micros += r.estimatedMicros ?: 0L
                searches += r.usage?.webSearches ?: 0
                if (r.outcome == CallOutcome.FAILED_NOT_BILLED) assumed++
                if (r.isFloor && r.outcome != CallOutcome.FAILED_NOT_BILLED) { partial = true; if (r.usageStatus == UsageStatus.UNKNOWN) unknown++ }
            }
            return UsageTotals(UsdEstimate(micros), records.size, searches, partial, unknown, assumed)
        }
    }
}

/** One dashboard row: all calls of one run (or one planning request). Pure, so the totals can be reconciled in tests. */
data class RunTotals(val key: String, val sessionId: String?, val feature: UsageFeature, val continuation: Boolean, val records: List<UsageRecord>, val totals: UsageTotals) {
    val startedAt: Instant get() = records.first().startedAt
}

object UsageGrouping {
    /** Groups by run id (a call without one is its own group), newest first; every record lands in exactly one group. */
    fun groups(records: List<UsageRecord>): List<RunTotals> = records.groupBy { it.runId ?: it.id }.map { (k, rows) ->
        val sorted = rows.sortedBy { it.startedAt }
        val first = sorted.first()
        RunTotals(k, first.sessionId, first.feature, first.feature == UsageFeature.RESEARCH_CONTINUATION, sorted, UsageTotals.of(sorted))
    }.sortedByDescending { it.startedAt }
}
