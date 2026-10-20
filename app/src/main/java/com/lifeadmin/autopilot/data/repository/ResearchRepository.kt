package com.lifeadmin.autopilot.data.repository

import com.lifeadmin.autopilot.ai.research.AppliedMove
import com.lifeadmin.autopilot.ai.research.BriefContent
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.ResearchBriefEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/** A saved research brief: readable offline, deletable, never re-sent anywhere. */
data class ResearchBrief(
    val id: String,
    val taskId: String,
    val createdAt: Instant,
    val question: String,
    val model: String,
    val status: BriefStatus,
    val searchCount: Int,
    val sourceCount: Int,
    val content: BriefContent,
    val applied: List<AppliedMove>,
) {
    fun isStale(now: Instant): Boolean = createdAt.plus(ResearchConfig.STALE_AFTER).isBefore(now)
}

enum class BriefStatus { VERIFIED, UNVERIFIED, TRUNCATED }

/**
 * Local store for research briefs. Only the parsed brief is kept — never the raw transcript,
 * the request, or anything about the key. Oldest briefs beyond the per-task cap are pruned.
 */
class ResearchRepository(
    private val db: AppDatabase,
    private val clock: AppClock,
    private val ids: IdGenerator,
) {
    private val dao get() = db.researchDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }

    fun observeForTask(taskId: String): Flow<List<ResearchBrief>> = dao.observeForTask(taskId).map { list -> list.mapNotNull(::decode) }

    fun observe(briefId: String): Flow<ResearchBrief?> = dao.observe(briefId).map { it?.let(::decode) }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun get(briefId: String): ResearchBrief? = dao.get(briefId)?.let(::decode)

    suspend fun getForTask(taskId: String): List<ResearchBrief> = dao.getForTask(taskId).mapNotNull(::decode)

    suspend fun getAll(): List<ResearchBrief> = dao.getAll().mapNotNull(::decode)

    /** Saves a new brief and returns its id. Trims the payload if it is unreasonably large. */
    suspend fun save(taskId: String, question: String, content: BriefContent): String {
        val id = ids.newId()
        val trimmed = fit(content)
        val status = when {
            trimmed.truncated -> BriefStatus.TRUNCATED
            trimmed.verified -> BriefStatus.VERIFIED
            else -> BriefStatus.UNVERIFIED
        }
        dao.upsert(
            ResearchBriefEntity(
                id = id, taskId = taskId, createdAt = clock.now(), question = question.trim(), model = trimmed.usage.model,
                status = status.name, searchCount = trimmed.usage.webSearches, sourceCount = trimmed.sources.size,
                payload = json.encodeToString(BriefContent.serializer(), trimmed), applied = "[]",
            ),
        )
        prune(taskId)
        return id
    }

    /** Restores briefs from a backup; existing ids are overwritten, nothing is pruned. */
    suspend fun restore(brief: ResearchBrief) {
        dao.upsert(
            ResearchBriefEntity(
                id = brief.id, taskId = brief.taskId, createdAt = brief.createdAt, question = brief.question, model = brief.model,
                status = brief.status.name, searchCount = brief.searchCount, sourceCount = brief.sourceCount,
                payload = json.encodeToString(BriefContent.serializer(), brief.content),
                applied = json.encodeToString(ListSerializer(AppliedMove.serializer()), brief.applied),
            ),
        )
    }

    suspend fun recordApplied(briefId: String, applied: List<AppliedMove>) {
        val existing = get(briefId) ?: return
        val merged = (existing.applied + applied).distinctBy { it.moveId }
        dao.updateApplied(briefId, json.encodeToString(ListSerializer(AppliedMove.serializer()), merged))
    }

    suspend fun clearApplied(briefId: String, moveIds: Set<String>) {
        val existing = get(briefId) ?: return
        dao.updateApplied(briefId, json.encodeToString(ListSerializer(AppliedMove.serializer()), existing.applied.filterNot { it.moveId in moveIds }))
    }

    suspend fun delete(briefId: String) = dao.delete(briefId)

    suspend fun deleteForTask(taskId: String) = dao.deleteForTask(taskId)

    suspend fun deleteAll() = dao.deleteAll()

    private suspend fun prune(taskId: String) {
        val all = dao.getForTask(taskId) // newest first
        all.drop(ResearchConfig.MAX_BRIEFS_PER_TASK).forEach { dao.delete(it.id) }
    }

    /** Drops uncited sources, then findings, until the JSON fits the payload cap. */
    internal fun fit(content: BriefContent): BriefContent {
        var c = content
        if (size(c) <= ResearchConfig.MAX_PAYLOAD_BYTES) return c
        c = c.copy(sources = c.sources.map { it.copy(quotes = it.quotes.take(1).map { q -> q.take(ResearchConfig.MAX_QUOTE_CHARS) }) })
        if (size(c) <= ResearchConfig.MAX_PAYLOAD_BYTES) return c
        c = c.copy(sources = c.sources.filter { it.citedCount > 0 })
        while (size(c) > ResearchConfig.MAX_PAYLOAD_BYTES && c.findings.isNotEmpty()) c = c.copy(findings = c.findings.dropLast(1))
        while (size(c) > ResearchConfig.MAX_PAYLOAD_BYTES && c.quickAnswer.size > 1) c = c.copy(quickAnswer = c.quickAnswer.dropLast(1))
        return c
    }

    private fun size(c: BriefContent): Int = json.encodeToString(BriefContent.serializer(), c).toByteArray().size

    private fun decode(e: ResearchBriefEntity): ResearchBrief? = try {
        ResearchBrief(
            id = e.id, taskId = e.taskId, createdAt = e.createdAt, question = e.question, model = e.model,
            status = runCatching { BriefStatus.valueOf(e.status) }.getOrDefault(BriefStatus.UNVERIFIED),
            searchCount = e.searchCount, sourceCount = e.sourceCount,
            content = json.decodeFromString(BriefContent.serializer(), e.payload),
            applied = runCatching { json.decodeFromString(ListSerializer(AppliedMove.serializer()), e.applied) }.getOrDefault(emptyList()),
        )
    } catch (ex: SerializationException) {
        null // an unreadable row is hidden rather than crashing the task screen
    } catch (ex: IllegalArgumentException) {
        null
    }
}
