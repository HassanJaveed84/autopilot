package com.lifeadmin.autopilot.ai.research2

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** A saved research session: readable offline, deletable, never re-sent anywhere. */
data class ResearchSession(
    val id: String,
    val taskId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val content: SessionContent,
    val applied: List<AppliedProposal> = emptyList(),
) {
    val outcome: SessionOutcome get() = content.outcome
    fun isStale(now: Instant): Boolean = updatedAt.plus(java.time.Duration.ofHours(72)).isBefore(now)
}

/** Persistence seam for sessions (Room in production, in-memory in tests). Only parsed content is stored. */
interface ResearchSessionStore {
    suspend fun save(session: ResearchSession)
    suspend fun get(id: String): ResearchSession?
    fun observe(id: String): Flow<ResearchSession?>
    fun observeForTask(taskId: String): Flow<List<ResearchSession>>
    suspend fun getForTask(taskId: String): List<ResearchSession>
    suspend fun getAll(): List<ResearchSession>
    fun observeCount(): Flow<Int>

    /** Call inside the task edit's transaction so both commit or neither does. */
    suspend fun recordApplied(id: String, applied: List<AppliedProposal>)
    suspend fun clearApplied(id: String, proposalIds: Set<String>)
    suspend fun delete(id: String)
    suspend fun deleteForTask(taskId: String)
    suspend fun deleteAll()
}

class InMemorySessionStore : ResearchSessionStore {
    private val rows = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ResearchSession>>(emptyMap())
    override suspend fun save(session: ResearchSession) { rows.value = rows.value + (session.id to session) }
    override suspend fun get(id: String) = rows.value[id]
    override fun observe(id: String): Flow<ResearchSession?> = rows.map { it[id] }
    override fun observeForTask(taskId: String): Flow<List<ResearchSession>> = rows.map { m -> m.values.filter { it.taskId == taskId }.sortedByDescending { it.updatedAt } }
    override suspend fun getForTask(taskId: String) = rows.value.values.filter { it.taskId == taskId }.sortedByDescending { it.updatedAt }
    override suspend fun getAll() = rows.value.values.sortedByDescending { it.updatedAt }
    override fun observeCount(): Flow<Int> = rows.map { it.size }
    override suspend fun recordApplied(id: String, applied: List<AppliedProposal>) { rows.value[id]?.let { s -> rows.value = rows.value + (id to s.copy(applied = (s.applied + applied).distinctBy { it.proposalId })) } }
    override suspend fun clearApplied(id: String, proposalIds: Set<String>) { rows.value[id]?.let { s -> rows.value = rows.value + (id to s.copy(applied = s.applied.filterNot { it.proposalId in proposalIds })) } }
    override suspend fun delete(id: String) { rows.value = rows.value - id }
    override suspend fun deleteForTask(taskId: String) { rows.value = rows.value.filterValues { it.taskId != taskId } }
    override suspend fun deleteAll() { rows.value = emptyMap() }
}
