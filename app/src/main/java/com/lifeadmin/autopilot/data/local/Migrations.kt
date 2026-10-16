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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
