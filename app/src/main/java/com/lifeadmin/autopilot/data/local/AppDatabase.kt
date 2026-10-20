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

    companion object {
        const val VERSION = 4
        const val NAME = "life_admin.db"

        /** No destructive fallback: a missing migration must fail loudly rather than drop tasks. */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
