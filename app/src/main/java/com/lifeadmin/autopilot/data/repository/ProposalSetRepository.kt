package com.lifeadmin.autopilot.data.repository

import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.ProposalSetEntity
import com.lifeadmin.autopilot.data.local.ProvenanceRecordEntity
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ProposalJson
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProvenanceRecord
import com.lifeadmin.autopilot.domain.proposal.SetStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Durable store for proposal sets and provenance records. Saving a set never touches its target;
 * only [ProposalApplier] writes tasks, and only from an explicit accept.
 */
open class ProposalSetRepository(private val db: AppDatabase) {
    private val dao = db.proposalSetDao()
    private val provenance = db.provenanceDao()

    fun observeForTarget(targetId: String): Flow<List<ProposalSet>> = dao.observeForTarget(targetId).map { l -> l.mapNotNull { decode(it) } }
    fun observe(id: String): Flow<ProposalSet?> = dao.observe(id).map { it?.let(::decode) }
    suspend fun get(id: String): ProposalSet? = dao.get(id)?.let(::decode)
    suspend fun getForTarget(targetId: String): List<ProposalSet> = dao.getForTarget(targetId).mapNotNull(::decode)
    suspend fun getAll(): List<ProposalSet> = dao.getAll().mapNotNull(::decode)

    suspend fun save(set: ProposalSet) = dao.upsert(entity(set))

    /** Marks a set obsolete with a reason on every pending item; applied/declined items keep their status (INT-044). */
    suspend fun markObsolete(id: String, reason: String) {
        val set = get(id) ?: return
        save(
            set.copy(
                status = SetStatus.OBSOLETE,
                items = set.items.map { if (it.status == ItemStatus.PENDING) it.copy(status = ItemStatus.OBSOLETE, statusReason = reason) else it },
            ),
        )
    }

    /** Re-points every open set from a draft to the task it became (OD-7). Items stay pending; nothing is applied. */
    suspend fun retarget(fromTargetId: String, toTargetId: String, toKind: com.lifeadmin.autopilot.domain.proposal.TargetKind) {
        getForTarget(fromTargetId).filter { it.status == SetStatus.OPEN }.forEach { save(it.copy(targetId = toTargetId, targetKind = toKind)) }
    }

    suspend fun delete(id: String) = dao.delete(id)
    suspend fun deleteAll() { dao.deleteAll() }

    fun observeProvenance(taskId: String): Flow<List<ProvenanceRecord>> = provenance.observeForTask(taskId).map { l -> l.mapNotNull { ProposalJson.decodeRecord(it.payload) } }
    suspend fun provenanceForTask(taskId: String): List<ProvenanceRecord> = provenance.getForTask(taskId).mapNotNull { ProposalJson.decodeRecord(it.payload) }
    suspend fun provenance(id: String): ProvenanceRecord? = provenance.get(id)?.let { ProposalJson.decodeRecord(it.payload) }
    suspend fun allProvenance(): List<ProvenanceRecord> = provenance.getAll().mapNotNull { ProposalJson.decodeRecord(it.payload) }
    suspend fun saveProvenance(records: List<ProvenanceRecord>) = provenance.upsertAll(records.map(::entity))
    suspend fun deleteProvenance(ids: List<String>) { if (ids.isNotEmpty()) provenance.deleteByIds(ids) }
    suspend fun deleteAllProvenance() { provenance.deleteAll() }

    fun decode(e: ProposalSetEntity): ProposalSet? = ProposalJson.decodeSet(e.payload)

    fun entity(set: ProposalSet) = ProposalSetEntity(
        id = set.id, source = set.source.name, targetKind = set.targetKind.name, targetId = set.targetId, investigationId = set.investigationId,
        madeAt = Instant.ofEpochMilli(set.madeAtMs), status = set.status.name, payload = ProposalJson.encodeSet(set),
    )

    fun entity(r: ProvenanceRecord) = ProvenanceRecordEntity(
        id = r.id, taskId = r.taskId, draftId = r.draftId, elementKind = r.elementKind.name, elementId = r.elementId, proposalSetId = r.proposalSetId,
        itemId = r.itemId, appliedAt = Instant.ofEpochMilli(r.appliedAtMs), editedByUser = r.editedByUser, payload = ProposalJson.encodeRecord(r),
    )
}
