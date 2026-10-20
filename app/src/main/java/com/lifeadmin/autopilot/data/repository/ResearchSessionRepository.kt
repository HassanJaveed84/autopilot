package com.lifeadmin.autopilot.data.repository

import com.lifeadmin.autopilot.ai.research2.AppliedProposal
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.ResearchSession
import com.lifeadmin.autopilot.ai.research2.ResearchSessionStore
import com.lifeadmin.autopilot.ai.research2.SessionContent
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.ResearchSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Room-backed store for M3A.2 research sessions. Only parsed content is kept — never the raw
 * transcript, the prompts or anything about the key. Oldest sessions beyond the per-task cap are
 * pruned; oversized payloads are trimmed (uncited sources, then claims) rather than rejected.
 */
open class ResearchSessionRepository(private val db: AppDatabase) : ResearchSessionStore {
    private val dao get() = db.researchSessionDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }

    override fun observeForTask(taskId: String): Flow<List<ResearchSession>> = dao.observeForTask(taskId).map { l -> l.mapNotNull(::decode) }
    override fun observe(id: String): Flow<ResearchSession?> = dao.observe(id).map { it?.let(::decode) }
    override fun observeCount(): Flow<Int> = dao.observeCount()
    override suspend fun get(id: String): ResearchSession? = dao.get(id)?.let(::decode)
    override suspend fun getForTask(taskId: String): List<ResearchSession> = dao.getForTask(taskId).mapNotNull(::decode)
    override suspend fun getAll(): List<ResearchSession> = dao.getAll().mapNotNull(::decode)

    override suspend fun save(session: ResearchSession) {
        val content = fit(session.content)
        dao.upsert(entity(session.copy(content = content)))
        prune(session.taskId)
    }

    /** Restores from a backup: ids and applied state kept, nothing pruned. */
    suspend fun restore(session: ResearchSession) = dao.upsert(entity(session))

    override suspend fun recordApplied(id: String, applied: List<AppliedProposal>) {
        val existing = get(id) ?: return
        dao.updateApplied(id, json.encodeToString(ListSerializer(AppliedProposal.serializer()), (existing.applied + applied).distinctBy { it.proposalId }))
    }

    override suspend fun clearApplied(id: String, proposalIds: Set<String>) {
        val existing = get(id) ?: return
        dao.updateApplied(id, json.encodeToString(ListSerializer(AppliedProposal.serializer()), existing.applied.filterNot { it.proposalId in proposalIds }))
    }

    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun deleteForTask(taskId: String) = dao.deleteForTask(taskId)
    override suspend fun deleteAll() = dao.deleteAll()

    fun encode(content: SessionContent): String = json.encodeToString(SessionContent.serializer(), content)
    fun encodeApplied(applied: List<AppliedProposal>): String = json.encodeToString(ListSerializer(AppliedProposal.serializer()), applied)
    fun decodeContent(payload: String): SessionContent? = runCatching { json.decodeFromString(SessionContent.serializer(), payload) }.getOrNull()
    fun decodeApplied(raw: String): List<AppliedProposal> = runCatching { json.decodeFromString(ListSerializer(AppliedProposal.serializer()), raw) }.getOrDefault(emptyList())

    private fun entity(s: ResearchSession) = ResearchSessionEntity(
        id = s.id, taskId = s.taskId, createdAt = s.createdAt, updatedAt = s.updatedAt, title = s.content.title, template = s.content.template.name,
        outcome = s.content.outcome.name, model = s.content.latestRun?.model ?: "", searchCount = s.content.searchesTotal, sourceCount = s.content.sources.size,
        payload = encode(s.content), applied = encodeApplied(s.applied),
    )

    private suspend fun prune(taskId: String) {
        dao.getForTask(taskId).drop(Research2Config.MAX_SESSIONS_PER_TASK).forEach { dao.delete(it.id) }
    }

    internal fun fit(content: SessionContent): SessionContent {
        var c = content
        if (size(c) <= Research2Config.MAX_PAYLOAD_BYTES) return c
        c = c.copy(sources = c.sources.map { it.copy(quotes = it.quotes.take(1)) })
        if (size(c) <= Research2Config.MAX_PAYLOAD_BYTES) return c
        c = c.copy(sources = c.sources.filter { it.citedCount > 0 })
        while (size(c) > Research2Config.MAX_PAYLOAD_BYTES && c.claims.isNotEmpty()) c = c.copy(claims = c.claims.dropLast(1))
        while (size(c) > Research2Config.MAX_PAYLOAD_BYTES && c.body.excluded.isNotEmpty()) c = c.copy(body = c.body.copy(excluded = c.body.excluded.dropLast(1)))
        return c
    }

    private fun size(c: SessionContent): Int = encode(c).toByteArray().size

    private fun decode(e: ResearchSessionEntity): ResearchSession? = try {
        ResearchSession(e.id, e.taskId, e.createdAt, e.updatedAt, json.decodeFromString(SessionContent.serializer(), e.payload), decodeApplied(e.applied))
    } catch (ex: SerializationException) {
        null // an unreadable row is hidden rather than crashing the task screen
    } catch (ex: IllegalArgumentException) {
        null
    }
}
