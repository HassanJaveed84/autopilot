package com.lifeadmin.autopilot.data.repository

import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.ai.usage.UsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageRecord
import com.lifeadmin.autopilot.ai.usage.UsageStatus
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.UsageRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Room-backed usage ledger. A provider message id seen twice updates the first row (no double count). */
class RoomUsageLedger(private val db: AppDatabase) : UsageLedger {
    private val dao get() = db.usageDao()

    override suspend fun record(record: UsageRecord) {
        val pid = record.providerMessageId
        if (pid != null) {
            val dup = dao.byProviderMessage(pid).firstOrNull { it.id != record.id }
            if (dup != null) { dao.delete(dup.id) }
        }
        dao.upsert(record.toEntity())
    }

    override suspend fun get(id: String): UsageRecord? = dao.get(id)?.toRecord()
    override fun observeAll(): Flow<List<UsageRecord>> = dao.observeAll().map { l -> l.mapNotNull { it.toRecord() } }
    override suspend fun getAll(): List<UsageRecord> = dao.getAll().mapNotNull { it.toRecord() }
    override suspend fun forSession(sessionId: String): List<UsageRecord> = dao.forSession(sessionId).mapNotNull { it.toRecord() }
    override suspend fun settleInterrupted(): Int = dao.settleInFlight(CallOutcome.INTERRUPTED.name, UsageStatus.UNKNOWN.name)
    override suspend fun deleteAll() = dao.deleteAll()
    override suspend fun deleteOlderThan(cutoff: Instant): Int = dao.deleteOlderThan(cutoff.toEpochMilli())

    /** Restores a row from a backup only when absent (the ledger is append-only). */
    suspend fun restoreIfAbsent(record: UsageRecord) { if (dao.get(record.id) == null) dao.upsert(record.toEntity()) }

    companion object {
        fun UsageRecord.toEntity() = UsageRecordEntity(
            id, feature.name, model, startedAt, runId, sessionId, taskId, stage, durationMs,
            usage?.inputTokens, usage?.outputTokens, usage?.cacheWrite5mTokens, usage?.cacheWrite1hTokens, usage?.cacheReadTokens, usage?.webSearches,
            outcome.name, usageStatus.name, estimatedMicros, priceVersion, providerMessageId,
        )

        fun UsageRecordEntity.toRecord(): UsageRecord? {
            val feature = UsageFeature.entries.firstOrNull { it.name == feature } ?: return null
            val outcome = CallOutcome.entries.firstOrNull { it.name == outcome } ?: CallOutcome.FAILED_UNKNOWN
            val status = UsageStatus.entries.firstOrNull { it.name == usageStatus } ?: UsageStatus.UNKNOWN
            val usage = if (inputTokens == null && outputTokens == null && webSearches == null && cacheReadTokens == null) null
            else MeasuredUsage(inputTokens, outputTokens, cacheWrite5mTokens, cacheWrite1hTokens, cacheReadTokens, webSearches)
            return UsageRecord(id, feature, model, startedAt, runId, sessionId, taskId, stage, durationMs, usage, outcome, status, estimatedMicros, priceVersion, providerMessageId)
        }
    }
}
