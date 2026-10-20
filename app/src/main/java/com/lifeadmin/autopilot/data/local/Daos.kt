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
