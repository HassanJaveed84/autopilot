package com.lifeadmin.autopilot.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        TaskEntity::class,
        TaskStepEntity::class,
        ReminderPolicyEntity::class,
        ReminderTimeSlotEntity::class,
        ReminderOccurrenceEntity::class,
        TaskEventEntity::class,
        ReminderDiagnosticEntity::class,
        ResearchBriefEntity::class,
        InvestigationEntity::class,
        InvestigationTurnEntity::class,
        UsageRecordEntity::class,
        DraftEntity::class,
        ProposalSetEntity::class,
        ProvenanceRecordEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun stepDao(): TaskStepDao
    abstract fun policyDao(): ReminderPolicyDao
    abstract fun occurrenceDao(): ReminderOccurrenceDao
    abstract fun eventDao(): TaskEventDao
    abstract fun diagnosticDao(): DiagnosticDao
    abstract fun researchDao(): ResearchBriefDao
    abstract fun investigationDao(): InvestigationDao
    abstract fun investigationTurnDao(): InvestigationTurnDao
    abstract fun usageDao(): UsageRecordDao
    abstract fun draftDao(): DraftDao
    abstract fun proposalSetDao(): ProposalSetDao
    abstract fun provenanceDao(): ProvenanceDao

    companion object {
        const val VERSION = 9
        const val NAME = "life_admin.db"

        /** No destructive fallback: a missing migration must fail loudly rather than drop tasks. */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
