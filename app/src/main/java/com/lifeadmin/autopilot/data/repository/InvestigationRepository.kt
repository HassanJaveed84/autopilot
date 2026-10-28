package com.lifeadmin.autopilot.data.repository

import androidx.room.withTransaction
import com.lifeadmin.autopilot.ai.investigation.AssociationEvent
import com.lifeadmin.autopilot.ai.investigation.Investigation
import com.lifeadmin.autopilot.ai.investigation.InvestigationJson
import com.lifeadmin.autopilot.ai.investigation.InvestigationMeta
import com.lifeadmin.autopilot.ai.investigation.InvestigationOrigin
import com.lifeadmin.autopilot.ai.investigation.Turn
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.InvestigationEntity
import com.lifeadmin.autopilot.data.local.InvestigationTurnEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * LEGACY read path. Room-backed store for Investigation Threads written by the retired M4 engine
 * (and M3A.2 sessions migrated into them). New research is stored by [AfThreadRepository] in the
 * same tables; [decode] returns null for those rows, so the two never misread each other.
 *
 * Room-backed store for Investigation Threads (M4). Turns are written whole at every state change
 * (checkpoints included) so a process death loses at most the in-flight call. Final turns are
 * never rewritten except for their applied-proposal history (RES-092). Unreadable rows are hidden
 * rather than crashing the screen, and never deleted.
 */
open class InvestigationRepository(private val db: AppDatabase) {
    private val dao get() = db.investigationDao()
    private val turns get() = db.investigationTurnDao()

    fun observe(id: String): Flow<Investigation?> = combine(dao.observe(id), turns.observeForInvestigation(id)) { e, t -> e?.let { decode(it, t) } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeForTask(taskId: String): Flow<List<Investigation>> = dao.observeForTask(taskId).flatMapLatest { list -> withTurns(list) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<Investigation>> = dao.observeAll().flatMapLatest { list -> withTurns(list) }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun get(id: String): Investigation? = dao.get(id)?.let { decode(it, turns.forInvestigation(id)) }
    suspend fun getForTask(taskId: String): List<Investigation> = dao.getForTask(taskId).mapNotNull { decode(it, turns.forInvestigation(it.id)) }
    suspend fun getAll(): List<Investigation> = dao.getAll().mapNotNull { decode(it, turns.forInvestigation(it.id)) }
    suspend fun getTurn(id: String): Turn? = turns.get(id)?.let { InvestigationJson.decodeTurn(it.payload) }

    /** Investigations with a turn still marked running: candidates for the interruption surface after a process death. */
    suspend fun running(): List<Investigation> = dao.running().mapNotNull { decode(it, turns.forInvestigation(it.id)) }

    /**
     * Writes the investigation row and every given turn atomically. A legacy investigation is a
     * whole-session snapshot, so turns it no longer lists are replaced; a native one only ever gains turns.
     */
    suspend fun save(inv: Investigation) = db.withTransaction {
        dao.upsert(entity(inv))
        if (inv.meta.legacy) {
            val keep = inv.turns.map { it.id }.toSet()
            for (old in turns.forInvestigation(inv.id)) if (old.id !in keep) turns.delete(old.id)
        }
        for (t in inv.turns) turns.upsert(turnEntity(inv.id, t))
    }

    /** Writes one turn and refreshes the investigation's list columns in the same transaction. */
    suspend fun saveTurn(investigationId: String, turn: Turn, updatedAt: Instant) = db.withTransaction {
        turns.upsert(turnEntity(investigationId, turn))
        val e = dao.get(investigationId) ?: return@withTransaction
        val all = turns.forInvestigation(investigationId)
        val latest = all.maxByOrNull { it.turnIndex }
        dao.upsert(e.copy(updatedAt = updatedAt, state = latest?.state ?: e.state, turnCount = all.size))
    }

    suspend fun updateMeta(investigationId: String, meta: InvestigationMeta, updatedAt: Instant) {
        val e = dao.get(investigationId) ?: return
        dao.upsert(e.copy(meta = InvestigationJson.encodeMeta(meta), title = meta.title, updatedAt = updatedAt))
    }

    /** Explicit association (RES-130/N): recorded as an event; never implied by where the thread was opened. */
    suspend fun associate(investigationId: String, taskId: String?, note: String, at: Instant) = db.withTransaction {
        val e = dao.get(investigationId) ?: return@withTransaction
        val meta = InvestigationJson.decodeMeta(e.meta) ?: InvestigationMeta(e.title, e.title)
        val updated = meta.copy(associations = meta.associations + AssociationEvent(at.toEpochMilli(), taskId, note))
        dao.associate(investigationId, taskId, InvestigationJson.encodeMeta(updated), at)
    }

    /**
     * App start: a legacy (M4) turn still marked running belongs to a process that died before the
     * engine was replaced; it becomes Interrupted with what its last checkpoint held. Returns the count.
     */
    suspend fun settleRunning(at: Instant): Int {
        var n = 0
        for (inv in running()) for (t in inv.turns.filter { it.state == TurnState.RUNNING }) {
            saveTurn(inv.id, t.copy(state = TurnState.INTERRUPTED, result = t.result?.copy(ending = com.lifeadmin.autopilot.ai.investigation.TurnEnding.INTERRUPTED), usage = t.usage.copy(floor = true, inFlightUnknown = true), endingNote = t.endingNote ?: "Interrupted; what was saved before is kept"), at)
            n++
        }
        return n
    }

    suspend fun delete(id: String) = dao.delete(id)
    suspend fun deleteForTask(taskId: String) = dao.deleteForTask(taskId)
    suspend fun deleteAll() = dao.deleteAll()

    fun entity(inv: Investigation): InvestigationEntity {
        val latest = inv.turns.maxByOrNull { it.index }
        return InvestigationEntity(
            id = inv.id, origin = inv.origin.name, taskId = inv.taskId, draftId = inv.draftId, createdAt = Instant.ofEpochMilli(inv.createdAtMs), updatedAt = Instant.ofEpochMilli(inv.updatedAtMs),
            title = inv.meta.title.take(MAX_TITLE), state = (latest?.state ?: TurnState.PREPARING).name, turnCount = inv.turns.size, meta = InvestigationJson.encodeMeta(inv.meta),
        )
    }

    fun turnEntity(investigationId: String, t: Turn) = InvestigationTurnEntity(t.id, investigationId, t.index, t.kind.name, t.state.name, Instant.ofEpochMilli(t.createdAtMs), InvestigationJson.encodeTurn(t))

    fun decode(e: InvestigationEntity, rows: List<InvestigationTurnEntity>): Investigation? {
        if (com.lifeadmin.autopilot.ai.aifirst.AfJson.decodeMeta(e.meta) != null) return null
        val meta = InvestigationJson.decodeMeta(e.meta) ?: return null
        val origin = InvestigationOrigin.entries.firstOrNull { it.name == e.origin } ?: return null
        val turns = rows.mapNotNull { InvestigationJson.decodeTurn(it.payload) }.sortedBy { it.index }
        return Investigation(e.id, origin, e.taskId, e.draftId, e.createdAt.toEpochMilli(), e.updatedAt.toEpochMilli(), meta, turns)
    }

    private fun withTurns(list: List<InvestigationEntity>): Flow<List<Investigation>> {
        if (list.isEmpty()) return flowOf(emptyList())
        val flows = list.map { e -> turns.observeForInvestigation(e.id).map { t -> decode(e, t) } }
        return combine(flows) { arr -> arr.filterNotNull() }
    }

    companion object {
        const val MAX_TITLE = 120
    }
}
