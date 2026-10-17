package com.lifeadmin.autopilot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Hand-written, tested migrations. Never use destructive fallback in release builds. */
object Migrations {
    /** v1 → v2: snooze-end reminder choice on tasks; exact flag + note on occurrences. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN snoozeRemindAtEnd INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE reminder_occurrences ADD COLUMN exact INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE reminder_occurrences ADD COLUMN note TEXT")
        }
    }

    /** v2 → v3: delivery-lifecycle diagnostics ledger (no foreign keys by design). */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reminder_diagnostics` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                    "`occurrenceId` TEXT, `taskId` TEXT, `plannedAt` INTEGER, `triggerAt` INTEGER, `source` TEXT, `exact` INTEGER, " +
                    "`latenessMs` INTEGER, `outcome` TEXT, `detail` TEXT, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_diagnostics_at` ON `reminder_diagnostics` (`at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_diagnostics_occurrenceId` ON `reminder_diagnostics` (`occurrenceId`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
