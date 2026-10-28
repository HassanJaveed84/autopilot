package com.lifeadmin.autopilot.data.repository

import com.lifeadmin.autopilot.ai.investigation.Investigation
import com.lifeadmin.autopilot.ai.investigation.InvestigationOrigin
import com.lifeadmin.autopilot.ai.investigation.LegacyInvestigations
import com.lifeadmin.autopilot.ai.research2.AppliedProposal
import com.lifeadmin.autopilot.ai.research2.ResearchSession
import com.lifeadmin.autopilot.ai.research2.ResearchSessionStore
import com.lifeadmin.autopilot.ai.research2.SessionContent
import com.lifeadmin.autopilot.data.local.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Legacy view of M3A.2 research sessions over the v7 `investigations` tables (RES-092). Sessions
 * written through this store become legacy investigations (one turn per run); the applied history
 * is updated in place. Kept so the remaining M3A.2 code paths read and write the same rows the
 * Investigation Thread shows, until they are retired.
 */
open class ResearchSessionRepository(private val db: AppDatabase) : ResearchSessionStore {
    private val investigations = InvestigationRepository(db)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }

    override fun observeForTask(taskId: String): Flow<List<ResearchSession>> = investigations.observeForTask(taskId).map { l -> l.mapNotNull(LegacyInvestigations::toSession) }
    override fun observe(id: String): Flow<ResearchSession?> = investigations.observe(id).map { it?.let(LegacyInvestigations::toSession) }
    override fun observeCount(): Flow<Int> = investigations.observeCount()
    override suspend fun get(id: String): ResearchSession? = investigations.get(id)?.let(LegacyInvestigations::toSession)
    override suspend fun getForTask(taskId: String): List<ResearchSession> = investigations.getForTask(taskId).mapNotNull(LegacyInvestigations::toSession)
    override suspend fun getAll(): List<ResearchSession> = investigations.getAll().mapNotNull(LegacyInvestigations::toSession)

    override suspend fun save(session: ResearchSession) {
        val c = LegacyInvestigations.fromSession(session)
        investigations.save(Investigation(session.id, InvestigationOrigin.TASK, session.taskId, null, session.createdAt.toEpochMilli(), session.updatedAt.toEpochMilli(), c.meta, c.turns))
    }

    /** Restores from a backup: ids and applied state kept. */
    suspend fun restore(session: ResearchSession) = save(session)

    override suspend fun recordApplied(id: String, applied: List<AppliedProposal>) {
        val existing = get(id) ?: return
        save(existing.copy(applied = (existing.applied + applied).distinctBy { it.proposalId }))
    }

    override suspend fun clearApplied(id: String, proposalIds: Set<String>) {
        val existing = get(id) ?: return
        save(existing.copy(applied = existing.applied.filterNot { it.proposalId in proposalIds }))
    }

    override suspend fun delete(id: String) = investigations.delete(id)
    override suspend fun deleteForTask(taskId: String) = investigations.deleteForTask(taskId)
    override suspend fun deleteAll() = investigations.deleteAll()

    fun encode(content: SessionContent): String = json.encodeToString(SessionContent.serializer(), content)
    fun encodeApplied(applied: List<AppliedProposal>): String = json.encodeToString(ListSerializer(AppliedProposal.serializer()), applied)
    fun decodeContent(payload: String): SessionContent? = runCatching { json.decodeFromString(SessionContent.serializer(), payload) }.getOrNull()
    fun decodeApplied(raw: String): List<AppliedProposal> = runCatching { json.decodeFromString(ListSerializer(AppliedProposal.serializer()), raw) }.getOrDefault(emptyList())

    @Suppress("unused")
    private fun instant(ms: Long): Instant = Instant.ofEpochMilli(ms)
}
