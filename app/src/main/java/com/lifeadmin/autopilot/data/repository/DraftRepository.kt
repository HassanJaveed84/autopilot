package com.lifeadmin.autopilot.data.repository

import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.DraftEntity
import com.lifeadmin.autopilot.domain.draft.Draft
import com.lifeadmin.autopilot.domain.draft.DraftContent
import com.lifeadmin.autopilot.domain.draft.DraftHistoryEntry
import com.lifeadmin.autopilot.domain.draft.DraftJson
import com.lifeadmin.autopilot.domain.draft.DraftStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable drafts (PLAN-010/013/014). A draft is written to Room as soon as it exists and on every
 * change, so process death, navigation or a failed AI call never loses the input or a paid result.
 * Nothing here creates a task or a reminder: Save goes through TaskRepository, and only then.
 */
open class DraftRepository(private val db: AppDatabase, private val clock: AppClock, private val ids: IdGenerator) {
    private val dao = db.draftDao()

    fun observeOpen(): Flow<List<Draft>> = dao.observeOpen().map { l -> l.mapNotNull(::decode) }
    fun observeOpenCount(): Flow<Int> = dao.observeOpenCount()
    fun observe(id: String): Flow<Draft?> = dao.observe(id).map { it?.let(::decode) }
    suspend fun get(id: String): Draft? = dao.get(id)?.let(::decode)
    suspend fun getAll(): List<Draft> = dao.getAll().mapNotNull(::decode)

    /** Creates a draft from the first input. The text is stored verbatim. */
    suspend fun create(text: String, id: String = ids.newId()): Draft {
        val now = clock.now()
        val draft = Draft(id, now, now, DraftStatus.DRAFT, null, DraftContent(text = text.take(MAX_TEXT)))
        dao.upsert(entity(draft))
        return draft
    }

    /** Replaces the content; the raw text column follows the content so lists never show stale input. */
    suspend fun update(id: String, transform: (DraftContent) -> DraftContent): Draft? {
        val current = get(id) ?: return null
        if (current.status != DraftStatus.DRAFT) return current
        val next = current.copy(updatedAt = clock.now(), content = transform(current.content).let { it.copy(text = it.text.take(MAX_TEXT)) })
        dao.upsert(entity(next))
        return next
    }

    /** Keeps a paid planning result with the draft so recovery never re-runs planning (PLAN-014). */
    suspend fun attachPlanningResult(id: String, encoded: String, model: String): Draft? = update(id) {
        val now = clock.now().toEpochMilli()
        it.copy(planningResult = encoded, planningModel = model, planningAtMs = now, history = (it.history + DraftHistoryEntry(now, "Planned with $model")).takeLast(MAX_HISTORY))
    }

    /** The draft became a task. The row stays as the record of what was saved; it leaves the open list. */
    suspend fun markSaved(id: String, taskId: String): Draft? {
        val current = get(id) ?: return null
        val next = current.copy(status = DraftStatus.SAVED, savedTaskId = taskId, updatedAt = clock.now())
        dao.upsert(entity(next))
        return next
    }

    /** User-initiated only (PLAN-013). */
    suspend fun discard(id: String) {
        val current = get(id) ?: return
        dao.upsert(entity(current.copy(status = DraftStatus.DISCARDED, updatedAt = clock.now())))
    }

    suspend fun delete(id: String) = dao.delete(id)
    suspend fun deleteAll() { dao.deleteAll() }

    /** Used by the backup importer: restores a row as it was. */
    suspend fun restore(draft: Draft) = dao.upsert(entity(draft))

    fun decode(e: DraftEntity): Draft? {
        val content = DraftJson.decode(e.payload) ?: DraftContent(text = e.text)
        val status = DraftStatus.entries.firstOrNull { it.name == e.status } ?: DraftStatus.DRAFT
        return Draft(e.id, e.createdAt, e.updatedAt, status, e.savedTaskId, content)
    }

    fun entity(d: Draft) = DraftEntity(d.id, d.createdAt, d.updatedAt, d.status.name, d.content.text, d.savedTaskId, DraftJson.encode(d.content))

    companion object {
        const val MAX_TEXT = 20_000
        const val MAX_HISTORY = 50
    }
}
