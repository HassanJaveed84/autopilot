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
    /** Rejected before processing (4xx): nothing was billed. */
    FAILED_NOT_BILLED("Failed · not billed"),
    /** Failed after the request may have been processed: usage unknown. */
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

    companion object {
        /** Finalises a record from measured usage with the catalog price. */
        fun priced(base: UsageRecord, usage: MeasuredUsage?, outcome: CallOutcome, durationMs: Long?, providerMessageId: String? = null): UsageRecord {
            val status = when {
                outcome == CallOutcome.FAILED_NOT_BILLED -> UsageStatus.COMPLETE
                usage == null || usage.allUnknown -> UsageStatus.UNKNOWN
                usage.anyUnknown || outcome == CallOutcome.STOPPED || outcome == CallOutcome.FAILED_UNKNOWN || outcome == CallOutcome.INTERRUPTED -> UsageStatus.PARTIAL
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

/** Sums for the dashboard; every total says whether some of it is a floor. */
data class UsageTotals(val estimate: UsdEstimate, val calls: Int, val searches: Int, val partial: Boolean, val unknownCalls: Int) {
    companion object {
        fun of(records: List<UsageRecord>): UsageTotals {
            var micros = 0L; var searches = 0; var partial = false; var unknown = 0
            for (r in records) {
                if (r.outcome == CallOutcome.IN_FLIGHT) { partial = true; continue }
                micros += r.estimatedMicros ?: 0L
                searches += r.usage?.webSearches ?: 0
                if (r.isFloor && r.outcome != CallOutcome.FAILED_NOT_BILLED) { partial = true; if (r.usageStatus == UsageStatus.UNKNOWN) unknown++ }
            }
            return UsageTotals(UsdEstimate(micros), records.size, searches, partial, unknown)
        }
    }
}
