package com.lifeadmin.autopilot.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status IN (:statuses) AND archivedAt IS NULL")
    suspend fun getByStatuses(statuses: List<TaskStatus>): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TaskStepDao {
    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY sortOrder, id")
    fun observeForTask(taskId: String): Flow<List<TaskStepEntity>>

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY sortOrder, id")
    suspend fun getForTask(taskId: String): List<TaskStepEntity>

    @Query("SELECT * FROM task_steps ORDER BY taskId, sortOrder")
    suspend fun getAll(): List<TaskStepEntity>

    @Query("SELECT * FROM task_steps ORDER BY taskId, sortOrder")
    fun observeAll(): Flow<List<TaskStepEntity>>

    @Query("SELECT * FROM task_steps WHERE id = :id")
    suspend fun get(id: String): TaskStepEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM task_steps WHERE taskId = :taskId")
    suspend fun maxSortOrder(taskId: String): Int

    @Upsert
    suspend fun upsert(step: TaskStepEntity)

    @Upsert
    suspend fun upsertAll(steps: List<TaskStepEntity>)

    @Delete
    suspend fun delete(step: TaskStepEntity)

    @Query("DELETE FROM task_steps WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)
}

@Dao
interface ReminderPolicyDao {
    @Query("SELECT * FROM reminder_policies WHERE taskId = :taskId")
    suspend fun getForTask(taskId: String): ReminderPolicyEntity?

    @Query("SELECT * FROM reminder_policies WHERE taskId = :taskId")
    fun observeForTask(taskId: String): Flow<ReminderPolicyEntity?>

    @Query("SELECT * FROM reminder_policies WHERE id = :id")
    suspend fun get(id: String): ReminderPolicyEntity?

    @Query("SELECT * FROM reminder_policies")
    suspend fun getAll(): List<ReminderPolicyEntity>

    @Query("SELECT * FROM reminder_policies")
    fun observeAll(): Flow<List<ReminderPolicyEntity>>

    @Upsert
    suspend fun upsert(policy: ReminderPolicyEntity)

    @Query("UPDATE reminder_policies SET generation = generation + 1 WHERE id = :policyId")
    suspend fun bumpGeneration(policyId: String)

    @Query("SELECT * FROM reminder_time_slots WHERE policyId = :policyId ORDER BY sortOrder, localTime")
    suspend fun getSlots(policyId: String): List<ReminderTimeSlotEntity>

    @Query("SELECT * FROM reminder_time_slots ORDER BY policyId, sortOrder")
    suspend fun getAllSlots(): List<ReminderTimeSlotEntity>

    @Query("SELECT * FROM reminder_time_slots ORDER BY policyId, sortOrder")
    fun observeAllSlots(): Flow<List<ReminderTimeSlotEntity>>

    @Query("SELECT * FROM reminder_time_slots WHERE policyId = :policyId ORDER BY sortOrder, localTime")
    fun observeSlots(policyId: String): Flow<List<ReminderTimeSlotEntity>>

    @Query("DELETE FROM reminder_time_slots WHERE policyId = :policyId")
    suspend fun deleteSlots(policyId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSlots(slots: List<ReminderTimeSlotEntity>)
}

@Dao
interface ReminderOccurrenceDao {
    @Query("SELECT * FROM reminder_occurrences WHERE id = :id")
    suspend fun get(id: String): ReminderOccurrenceEntity?

    @Query("SELECT * FROM reminder_occurrences WHERE policyId = :policyId AND plannedAt = :plannedAt")
    suspend fun findByPlanned(policyId: String, plannedAt: Instant): ReminderOccurrenceEntity?

    @Query("SELECT * FROM reminder_occurrences WHERE taskId = :taskId AND status IN (:statuses) ORDER BY plannedAt")
    suspend fun getForTaskWithStatus(taskId: String, statuses: List<OccurrenceStatus>): List<ReminderOccurrenceEntity>

    @Query("SELECT * FROM reminder_occurrences WHERE status IN (:statuses) ORDER BY plannedAt")
    suspend fun getWithStatus(statuses: List<OccurrenceStatus>): List<ReminderOccurrenceEntity>

    @Query("SELECT * FROM reminder_occurrences WHERE status IN (:statuses) ORDER BY plannedAt")
    fun observeWithStatus(statuses: List<OccurrenceStatus>): Flow<List<ReminderOccurrenceEntity>>

    @Query("SELECT * FROM reminder_occurrences WHERE taskId = :taskId ORDER BY plannedAt DESC LIMIT :limit")
    fun observeRecentForTask(taskId: String, limit: Int): Flow<List<ReminderOccurrenceEntity>>

    @Query("SELECT * FROM reminder_occurrences WHERE taskId = :taskId ORDER BY plannedAt")
    suspend fun getAllForTask(taskId: String): List<ReminderOccurrenceEntity>

    @Query("SELECT plannedAt FROM reminder_occurrences WHERE policyId = :policyId AND plannedAt > :after AND status IN ('DELIVERED','SUPPRESSED','MISSED')")
    suspend fun getSettledPlannedAfter(policyId: String, after: Instant): List<Instant>

    @Query("SELECT COUNT(*) FROM reminder_occurrences WHERE status = 'DELIVERED' AND deliveredAt >= :since")
    suspend fun countDeliveredSince(since: Instant): Int

    @Query("SELECT COUNT(*) FROM reminder_occurrences WHERE status = :status")
    suspend fun countWithStatus(status: OccurrenceStatus): Int

    @Query("SELECT * FROM reminder_occurrences ORDER BY plannedAt")
    suspend fun getAll(): List<ReminderOccurrenceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(occurrence: ReminderOccurrenceEntity)

    @Update
    suspend fun update(occurrence: ReminderOccurrenceEntity)

    /** Compare-and-set claim; returns 1 when this caller won the transition. */
    @Query(
        "UPDATE reminder_occurrences SET status = :to, deliveredAt = :at " +
            "WHERE id = :id AND status = :from AND generation = :generation",
    )
    suspend fun compareAndSetStatus(id: String, from: OccurrenceStatus, to: OccurrenceStatus, generation: Int, at: Instant?): Int

    @Query("DELETE FROM reminder_occurrences WHERE status IN ('DELIVERED','SUPPRESSED','MISSED','CANCELED') AND plannedAt < :before")
    suspend fun pruneSettledBefore(before: Instant): Int
}

@Dao
interface TaskEventDao {
    @Query("SELECT * FROM task_events WHERE taskId = :taskId ORDER BY at DESC, id DESC")
    fun observeForTask(taskId: String): Flow<List<TaskEventEntity>>

    @Query("SELECT * FROM task_events WHERE taskId = :taskId ORDER BY at DESC, id DESC")
    suspend fun getForTask(taskId: String): List<TaskEventEntity>

    @Query("SELECT * FROM task_events ORDER BY at")
    suspend fun getAll(): List<TaskEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: TaskEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<TaskEventEntity>)
}

@Dao
interface DiagnosticDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ReminderDiagnosticEntity)

    @Query("SELECT * FROM reminder_diagnostics ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ReminderDiagnosticEntity>

    @Query("SELECT * FROM reminder_diagnostics ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReminderDiagnosticEntity>>

    @Query("SELECT * FROM reminder_diagnostics WHERE occurrenceId = :occurrenceId ORDER BY at, id")
    suspend fun forOccurrence(occurrenceId: String): List<ReminderDiagnosticEntity>

    @Query("SELECT * FROM reminder_diagnostics WHERE type IN (:types) ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recentOfTypes(types: List<String>, limit: Int): List<ReminderDiagnosticEntity>

    @Query("SELECT COUNT(*) FROM reminder_diagnostics")
    suspend fun count(): Int

    @Query("DELETE FROM reminder_diagnostics WHERE at < :before")
    suspend fun deleteBefore(before: Instant): Int

    /** Keeps only the newest [keep] rows. */
    @Query("DELETE FROM reminder_diagnostics WHERE id NOT IN (SELECT id FROM reminder_diagnostics ORDER BY at DESC, id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int): Int
}

@Dao
interface ResearchBriefDao {
    @Query("SELECT * FROM research_briefs WHERE taskId = :taskId ORDER BY createdAt DESC")
    fun observeForTask(taskId: String): Flow<List<ResearchBriefEntity>>

    @Query("SELECT * FROM research_briefs WHERE taskId = :taskId ORDER BY createdAt DESC")
    suspend fun getForTask(taskId: String): List<ResearchBriefEntity>

    @Query("SELECT * FROM research_briefs WHERE id = :id")
    suspend fun get(id: String): ResearchBriefEntity?

    @Query("SELECT * FROM research_briefs WHERE id = :id")
    fun observe(id: String): Flow<ResearchBriefEntity?>

    @Query("SELECT * FROM research_briefs ORDER BY createdAt DESC")
    suspend fun getAll(): List<ResearchBriefEntity>

    @Query("SELECT COUNT(*) FROM research_briefs")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(brief: ResearchBriefEntity)

    @Query("UPDATE research_briefs SET applied = :applied WHERE id = :id")
    suspend fun updateApplied(id: String, applied: String)

    @Query("DELETE FROM research_briefs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM research_briefs WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM research_briefs")
    suspend fun deleteAll()
}

@Dao
interface InvestigationDao {
    @Query("SELECT * FROM investigations WHERE taskId = :taskId ORDER BY updatedAt DESC")
    fun observeForTask(taskId: String): Flow<List<InvestigationEntity>>

    @Query("SELECT * FROM investigations WHERE taskId = :taskId ORDER BY updatedAt DESC")
    suspend fun getForTask(taskId: String): List<InvestigationEntity>

    @Query("SELECT * FROM investigations WHERE id = :id")
    suspend fun get(id: String): InvestigationEntity?

    @Query("SELECT * FROM investigations WHERE id = :id")
    fun observe(id: String): Flow<InvestigationEntity?>

    @Query("SELECT * FROM investigations ORDER BY updatedAt DESC")
    suspend fun getAll(): List<InvestigationEntity>

    @Query("SELECT * FROM investigations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<InvestigationEntity>>

    @Query("SELECT COUNT(*) FROM investigations")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM investigations WHERE state = 'RUNNING'")
    suspend fun running(): List<InvestigationEntity>

    @Upsert
    suspend fun upsert(investigation: InvestigationEntity)

    @Query("UPDATE investigations SET taskId = :taskId, updatedAt = :at, meta = :meta WHERE id = :id")
    suspend fun associate(id: String, taskId: String?, meta: String, at: Instant)

    @Query("DELETE FROM investigations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM investigations WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM investigations")
    suspend fun deleteAll()
}

@Dao
interface InvestigationTurnDao {
    @Query("SELECT * FROM investigation_turns WHERE investigationId = :investigationId ORDER BY turnIndex ASC")
    suspend fun forInvestigation(investigationId: String): List<InvestigationTurnEntity>

    @Query("SELECT * FROM investigation_turns WHERE investigationId = :investigationId ORDER BY turnIndex ASC")
    fun observeForInvestigation(investigationId: String): Flow<List<InvestigationTurnEntity>>

    @Query("SELECT * FROM investigation_turns WHERE id = :id")
    suspend fun get(id: String): InvestigationTurnEntity?

    @Query("SELECT * FROM investigation_turns ORDER BY investigationId, turnIndex")
    suspend fun getAll(): List<InvestigationTurnEntity>

    @Query("SELECT * FROM investigation_turns WHERE state = 'RUNNING'")
    suspend fun running(): List<InvestigationTurnEntity>

    @Upsert
    suspend fun upsert(turn: InvestigationTurnEntity)

    @Query("DELETE FROM investigation_turns WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface UsageRecordDao {
    @Query("SELECT * FROM usage_records ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<UsageRecordEntity>>

    @Query("SELECT * FROM usage_records ORDER BY startedAt DESC")
    suspend fun getAll(): List<UsageRecordEntity>

    @Query("SELECT * FROM usage_records WHERE id = :id")
    suspend fun get(id: String): UsageRecordEntity?

    @Query("SELECT * FROM usage_records WHERE providerMessageId = :pid")
    suspend fun byProviderMessage(pid: String): List<UsageRecordEntity>

    @Query("SELECT * FROM usage_records WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    suspend fun forSession(sessionId: String): List<UsageRecordEntity>

    @Upsert
    suspend fun upsert(record: UsageRecordEntity)

    @Query("UPDATE usage_records SET outcome = :outcome, usageStatus = :status WHERE outcome = 'IN_FLIGHT'")
    suspend fun settleInFlight(outcome: String, status: String): Int

    @Query("DELETE FROM usage_records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM usage_records WHERE startedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM usage_records")
    suspend fun deleteAll()
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE status = 'DRAFT' ORDER BY updatedAt DESC")
    fun observeOpen(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE id = :id")
    fun observe(id: String): Flow<DraftEntity?>

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun get(id: String): DraftEntity?

    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    suspend fun getAll(): List<DraftEntity>

    @Query("SELECT COUNT(*) FROM drafts WHERE status = 'DRAFT'")
    fun observeOpenCount(): Flow<Int>

    @Upsert
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM drafts")
    suspend fun deleteAll()
}

@Dao
interface ProposalSetDao {
    @Query("SELECT * FROM proposal_sets WHERE targetId = :targetId ORDER BY madeAt DESC")
    fun observeForTarget(targetId: String): Flow<List<ProposalSetEntity>>

    @Query("SELECT * FROM proposal_sets WHERE targetId = :targetId ORDER BY madeAt DESC")
    suspend fun getForTarget(targetId: String): List<ProposalSetEntity>

    @Query("SELECT * FROM proposal_sets WHERE id = :id")
    fun observe(id: String): Flow<ProposalSetEntity?>

    @Query("SELECT * FROM proposal_sets WHERE id = :id")
    suspend fun get(id: String): ProposalSetEntity?

    @Query("SELECT * FROM proposal_sets ORDER BY madeAt")
    suspend fun getAll(): List<ProposalSetEntity>

    @Upsert
    suspend fun upsert(set: ProposalSetEntity)

    @Query("DELETE FROM proposal_sets WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM proposal_sets")
    suspend fun deleteAll()
}

@Dao
interface ProvenanceDao {
    @Query("SELECT * FROM provenance_records WHERE taskId = :taskId ORDER BY appliedAt")
    fun observeForTask(taskId: String): Flow<List<ProvenanceRecordEntity>>

    @Query("SELECT * FROM provenance_records WHERE taskId = :taskId ORDER BY appliedAt")
    suspend fun getForTask(taskId: String): List<ProvenanceRecordEntity>

    @Query("SELECT * FROM provenance_records WHERE draftId = :draftId ORDER BY appliedAt")
    suspend fun getForDraft(draftId: String): List<ProvenanceRecordEntity>

    @Query("SELECT * FROM provenance_records WHERE id = :id")
    suspend fun get(id: String): ProvenanceRecordEntity?

    @Query("SELECT * FROM provenance_records ORDER BY appliedAt")
    suspend fun getAll(): List<ProvenanceRecordEntity>

    @Upsert
    suspend fun upsert(record: ProvenanceRecordEntity)

    @Upsert
    suspend fun upsertAll(records: List<ProvenanceRecordEntity>)

    @Query("DELETE FROM provenance_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM provenance_records")
    suspend fun deleteAll()
}
