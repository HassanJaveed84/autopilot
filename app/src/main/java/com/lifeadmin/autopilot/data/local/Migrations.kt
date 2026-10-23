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

    /** v3 → v4: saved research briefs (M3), owned by their task. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `research_briefs` (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`question` TEXT NOT NULL, `model` TEXT NOT NULL, `status` TEXT NOT NULL, `searchCount` INTEGER NOT NULL, " +
                    "`sourceCount` INTEGER NOT NULL, `payload` TEXT NOT NULL, `applied` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_research_briefs_taskId` ON `research_briefs` (`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_research_briefs_createdAt` ON `research_briefs` (`createdAt`)")
        }
    }

    /** v4 → v5: M3A.2 research sessions (owned by their task) and the usage ledger (no foreign keys by design). */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `research_sessions` (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "`title` TEXT NOT NULL, `template` TEXT NOT NULL, `outcome` TEXT NOT NULL, `model` TEXT NOT NULL, `searchCount` INTEGER NOT NULL, " +
                    "`sourceCount` INTEGER NOT NULL, `payload` TEXT NOT NULL, `applied` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_research_sessions_taskId` ON `research_sessions` (`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_research_sessions_updatedAt` ON `research_sessions` (`updatedAt`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `usage_records` (`id` TEXT NOT NULL, `feature` TEXT NOT NULL, `model` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                    "`runId` TEXT, `sessionId` TEXT, `taskId` TEXT, `stage` TEXT, `durationMs` INTEGER, `inputTokens` INTEGER, `outputTokens` INTEGER, " +
                    "`cacheWrite5mTokens` INTEGER, `cacheWrite1hTokens` INTEGER, `cacheReadTokens` INTEGER, `webSearches` INTEGER, `outcome` TEXT NOT NULL, " +
                    "`usageStatus` TEXT NOT NULL, `estimatedMicros` INTEGER, `priceVersion` TEXT NOT NULL, `providerMessageId` TEXT, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_records_startedAt` ON `usage_records` (`startedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_records_sessionId` ON `usage_records` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_records_providerMessageId` ON `usage_records` (`providerMessageId`)")
        }
    }

    /**
     * v5 → v6 (production kickoff M1): task revision + timing roles (event date, intended date,
     * planning target), step origin/provenance, and three new tables — drafts, proposal sets and
     * provenance records. Additive only; every existing row keeps every value.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN rev INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tasks ADD COLUMN eventLocalDate TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN eventLabel TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN intendedLocalDate TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN targetLocalDate TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN targetBasis TEXT")
            db.execSQL("ALTER TABLE task_steps ADD COLUMN origin TEXT NOT NULL DEFAULT 'USER'")
            db.execSQL("ALTER TABLE task_steps ADD COLUMN provenanceId TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `drafts` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                    "`text` TEXT NOT NULL, `savedTaskId` TEXT, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drafts_status` ON `drafts` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drafts_updatedAt` ON `drafts` (`updatedAt`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `proposal_sets` (`id` TEXT NOT NULL, `source` TEXT NOT NULL, `targetKind` TEXT NOT NULL, `targetId` TEXT NOT NULL, " +
                    "`investigationId` TEXT, `madeAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_proposal_sets_targetId` ON `proposal_sets` (`targetId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_proposal_sets_status` ON `proposal_sets` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_proposal_sets_madeAt` ON `proposal_sets` (`madeAt`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `provenance_records` (`id` TEXT NOT NULL, `taskId` TEXT, `draftId` TEXT, `elementKind` TEXT NOT NULL, `elementId` TEXT, " +
                    "`proposalSetId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `appliedAt` INTEGER NOT NULL, `editedByUser` INTEGER NOT NULL DEFAULT 0, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_provenance_records_taskId` ON `provenance_records` (`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_provenance_records_draftId` ON `provenance_records` (`draftId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_provenance_records_elementId` ON `provenance_records` (`elementId`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
}
