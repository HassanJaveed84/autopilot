package com.lifeadmin.autopilot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.Migrations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

/**
 * Builds a real schema-v1 database from the exported `1.json` (the schema shipped in M0),
 * fills it with data, then opens it with the current Room database + migrations. Room validates
 * the migrated schema against the v2 entities on open, so a broken migration fails this test.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    private fun schemaV1Statements(): List<String> = schemaStatements(1)

    private fun schemaStatements(version: Int): List<String> {
        val raw = javaClass.classLoader!!.getResource("com.lifeadmin.autopilot.data.local.AppDatabase/$version.json")!!.readText()
        val db = Json.parseToJsonElement(raw).jsonObject["database"]!!.jsonObject
        val hash = db["identityHash"]!!.jsonPrimitive.content
        val statements = mutableListOf<String>()
        db["entities"]!!.jsonArray.forEach { e ->
            val o = e.jsonObject
            val table = o["tableName"]!!.jsonPrimitive.content
            statements += o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            o["indices"]?.jsonArray?.forEach { i -> statements += i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table) }
        }
        db["setupQueries"]!!.jsonArray.forEach { statements += it.jsonPrimitive.content }
        statements += "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$hash')"
        return statements
    }

    @Test fun migrateV1ToV2KeepsTasksStepsAndPolicyTimes() = runTest {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schemaV1Statements().forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO tasks (id,title,notes,status,priority,createdAt,updatedAt,completedAt,archivedAt,deadlineType,deadlineLocalDate,deadlineInstant,deadlineZoneId,snoozedUntil,waitingReason,waitingCheckInAt) " +
                    "VALUES ('t1','Book train ride','Window seat','ACTIONABLE','HIGH',1760000000000,1760000000000,NULL,NULL,'DATE_ONLY','2026-10-30',NULL,NULL,NULL,NULL,NULL)",
            )
            db.execSQL("INSERT INTO task_steps (id,taskId,sortOrder,title,isDone,completedAt) VALUES ('s1','t1',0,'Find dates',0,NULL)")
            db.execSQL(
                "INSERT INTO reminder_policies (id,taskId,enabled,kind,startLocalDate,zoneMode,zoneId,dayInterval,quietHoursBehavior,snoozeDefaultMinutes,atInstant,lastEditedAt,generation) " +
                    "VALUES ('p1','t1',1,'DAILY_TIMES','2026-10-16','FOLLOW_DEVICE',NULL,1,NULL,NULL,NULL,1760000000000,3)",
            )
            db.execSQL("INSERT INTO reminder_time_slots (id,policyId,localTime,sortOrder) VALUES ('ts1','p1','09:00',0),('ts2','p1','18:00',1)")
            db.execSQL(
                "INSERT INTO reminder_occurrences (id,policyId,taskId,kind,plannedAt,slotAt,scheduledAt,deliveredAt,status,generation) " +
                    "VALUES ('o1','p1','t1','POLICY',1760100000000,1760100000000,NULL,NULL,'SCHEDULED',3)",
            )
            db.version = 1
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val task = room.taskDao().get("t1")!!
        assertEquals("Book train ride", task.title)
        assertEquals("Window seat", task.notes)
        assertTrue("new column defaults to remind-at-end", task.snoozeRemindAtEnd)
        assertEquals(listOf("Find dates"), room.stepDao().getForTask("t1").map { it.title })
        val policy = room.policyDao().getForTask("t1")!!
        assertEquals(3, policy.generation)
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), room.policyDao().getSlots(policy.id).map { it.localTime })
        val occ = room.occurrenceDao().get("o1")!!
        assertEquals(false, occ.exact)
        assertEquals(null, occ.note)
        room.close()
        context.deleteDatabase(name)
    }

    @Test fun migrateV2ToV3AddsDiagnosticsAndKeepsData() = runTest {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schemaStatements(2).forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO tasks (id,title,notes,status,priority,createdAt,updatedAt,completedAt,archivedAt,deadlineType,deadlineLocalDate,deadlineInstant,deadlineZoneId,snoozedUntil,snoozeRemindAtEnd,waitingReason,waitingCheckInAt) " +
                    "VALUES ('t1','Test',NULL,'ACTIONABLE','NORMAL',1,1,NULL,NULL,'NONE',NULL,NULL,NULL,NULL,1,NULL,NULL)",
            )
            db.execSQL(
                "INSERT INTO reminder_policies (id,taskId,enabled,kind,startLocalDate,zoneMode,zoneId,dayInterval,quietHoursBehavior,snoozeDefaultMinutes,atInstant,lastEditedAt,generation) " +
                    "VALUES ('p1','t1',1,'ONCE',NULL,'FOLLOW_DEVICE',NULL,1,NULL,NULL,1760653500000,1,2)",
            )
            db.execSQL(
                "INSERT INTO reminder_occurrences (id,policyId,taskId,kind,plannedAt,slotAt,scheduledAt,deliveredAt,status,generation,exact,note) " +
                    "VALUES ('o1','p1','t1','POLICY',1760653500000,1760653500000,1,NULL,'SCHEDULED',2,0,NULL)",
            )
            db.version = 2
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        assertEquals("Test", room.taskDao().get("t1")!!.title)
        assertEquals(com.lifeadmin.autopilot.domain.model.OccurrenceStatus.SCHEDULED, room.occurrenceDao().get("o1")!!.status)
        assertEquals(0, room.diagnosticDao().count())
        room.diagnosticDao().insert(com.lifeadmin.autopilot.data.local.ReminderDiagnosticEntity("d1", java.time.Instant.EPOCH, "SCHEDULED", "o1", "t1", null, null, "test", false, null, null, null))
        assertEquals(1, room.diagnosticDao().count())
        room.close()
        context.deleteDatabase(name)
    }

    @Test fun migrateV3ToV4AddsResearchBriefsAndKeepsData() = runTest {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schemaStatements(3).forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO tasks (id,title,notes,status,priority,createdAt,updatedAt,completedAt,archivedAt,deadlineType,deadlineLocalDate,deadlineInstant,deadlineZoneId,snoozedUntil,snoozeRemindAtEnd,waitingReason,waitingCheckInAt) " +
                    "VALUES ('t1','Book train ride',NULL,'ACTIONABLE','NORMAL',1,1,NULL,NULL,'NONE',NULL,NULL,NULL,NULL,1,NULL,NULL)",
            )
            db.execSQL("INSERT INTO task_steps (id,taskId,sortOrder,title,isDone,completedAt) VALUES ('s1','t1',0,'Find dates',0,NULL)")
            db.version = 3
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        assertEquals("Book train ride", room.taskDao().get("t1")!!.title)
        assertEquals(listOf("Find dates"), room.stepDao().getForTask("t1").map { it.title })
        assertEquals(0, room.researchDao().observeCount().first())
        room.researchDao().upsert(
            com.lifeadmin.autopilot.data.local.ResearchBriefEntity("b1", "t1", java.time.Instant.EPOCH, "q", "claude-haiku-5-5", "VERIFIED", 1, 1, "{}", "[]"),
        )
        assertEquals(1, room.researchDao().getForTask("t1").size)
        // Cascade from the new foreign key.
        room.taskDao().delete("t1")
        assertEquals(0, room.researchDao().observeCount().first())
        room.close()
        context.deleteDatabase(name)
    }

    @Test fun migrateV4ToV5AddsSessionsAndUsageLedgerAndKeepsBriefs() = runTest {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schemaStatements(4).forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO tasks (id,title,notes,status,priority,createdAt,updatedAt,completedAt,archivedAt,deadlineType,deadlineLocalDate,deadlineInstant,deadlineZoneId,snoozedUntil,snoozeRemindAtEnd,waitingReason,waitingCheckInAt) " +
                    "VALUES ('t1','Supplements',NULL,'ACTIONABLE','NORMAL',1,1,NULL,NULL,'NONE',NULL,NULL,NULL,NULL,1,NULL,NULL)",
            )
            db.execSQL("INSERT INTO research_briefs (id,taskId,createdAt,question,model,status,searchCount,sourceCount,payload,applied) VALUES ('b1','t1',5,'q','claude-haiku-5-5','VERIFIED',1,1,'{}','[]')")
            db.version = 4
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        assertEquals("Supplements", room.taskDao().get("t1")!!.title)
        assertEquals("legacy briefs are untouched", 1, room.researchDao().getForTask("t1").size)
        assertEquals(0, room.investigationDao().observeCount().first())
        room.investigationDao().upsert(
            com.lifeadmin.autopilot.data.local.InvestigationEntity("s1", "TASK", "t1", null, java.time.Instant.EPOCH, java.time.Instant.EPOCH, "Picks", "DONE", 0, "{}"),
        )
        room.usageDao().upsert(
            com.lifeadmin.autopilot.data.local.UsageRecordEntity("u1", "RESEARCH", "claude-sonnet-5-5", java.time.Instant.EPOCH, "r1", "s1", "t1", "GATHER", 1000, 41000, 2600, 0, 0, 0, 5, "COMPLETE", "COMPLETE", 158_050, "2026-10", "msg_1"),
        )
        assertEquals(1, room.investigationDao().getForTask("t1").size)
        assertEquals(1, room.usageDao().forSession("s1").size)
        // Investigations outlive their task as standalone history; the ledger has no foreign key by design.
        room.taskDao().delete("t1")
        assertEquals(1, room.investigationDao().observeCount().first())
        assertEquals(null, room.investigationDao().get("s1")!!.taskId)
        assertEquals(1, room.usageDao().getAll().size)
        room.close()
        context.deleteDatabase(name)
    }

    @Test fun migrateV5ToV6AddsRevTimingRolesDraftsProposalsProvenanceAndKeepsEverything() = runTest {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schemaStatements(5).forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO tasks (id,title,notes,status,priority,createdAt,updatedAt,completedAt,archivedAt,deadlineType,deadlineLocalDate,deadlineInstant,deadlineZoneId,snoozedUntil,snoozeRemindAtEnd,waitingReason,waitingCheckInAt) " +
                    "VALUES ('t1','Renew passport','Expires in March','ACTIONABLE','HIGH',1,2,NULL,NULL,'DATE_ONLY','2026-11-01',NULL,NULL,NULL,1,NULL,NULL)",
            )
            db.execSQL("INSERT INTO task_steps (id,taskId,sortOrder,title,isDone,completedAt) VALUES ('s1','t1',0,'Find old passport',0,NULL),('s2','t1',1,'Take photo',1,5)")
            db.execSQL(
                "INSERT INTO research_sessions (id,taskId,createdAt,updatedAt,title,template,outcome,model,searchCount,sourceCount,payload,applied) " +
                    "VALUES ('s1','t1',1,1,'Picks','PICK_PER_ITEM','COMPLETE','claude-sonnet-5-5',7,5,'{}','[]')",
            )
            db.execSQL(
                "INSERT INTO usage_records (id,feature,model,startedAt,runId,sessionId,taskId,stage,durationMs,inputTokens,outputTokens,cacheWrite5mTokens,cacheWrite1hTokens,cacheReadTokens,webSearches,outcome,usageStatus,estimatedMicros,priceVersion,providerMessageId) " +
                    "VALUES ('u1','RESEARCH','claude-sonnet-5-5',1,'r1','s1','t1','GATHER',1000,41000,2600,0,0,0,5,'COMPLETE','COMPLETE',158050,'2026-10','msg_1')",
            )
            db.version = 5
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val task = room.taskDao().get("t1")!!
        assertEquals("Renew passport", task.title)
        assertEquals("Expires in March", task.notes)
        assertEquals("existing deadline untouched", java.time.LocalDate.of(2026, 11, 1), task.deadlineLocalDate)
        assertEquals("rev starts at 0", 0, task.rev)
        assertEquals(null, task.eventLocalDate)
        assertEquals(null, task.targetLocalDate)
        val steps = room.stepDao().getForTask("t1")
        assertEquals(listOf("Find old passport", "Take photo"), steps.map { it.title })
        assertEquals("existing steps are the user's", listOf("USER", "USER"), steps.map { it.origin })
        assertTrue(steps[1].isDone)
        assertEquals("the v5 session arrives as an investigation", 1, room.investigationDao().getForTask("t1").size)
        assertEquals(1, room.usageDao().getAll().size)
        assertEquals(0, room.draftDao().getAll().size)
        assertEquals(0, room.proposalSetDao().getAll().size)
        assertEquals(0, room.provenanceDao().getAll().size)
        // The new tables accept rows and have no foreign keys: deleting the task leaves them.
        room.draftDao().upsert(com.lifeadmin.autopilot.data.local.DraftEntity("d1", java.time.Instant.EPOCH, java.time.Instant.EPOCH, "DRAFT", "Book the dentist", null, "{\"text\":\"Book the dentist\"}"))
        room.provenanceDao().upsert(com.lifeadmin.autopilot.data.local.ProvenanceRecordEntity("p1", "t1", null, "STEP", "s1", "set1", "a", java.time.Instant.EPOCH, false, "{}"))
        room.taskDao().delete("t1")
        assertEquals(1, room.draftDao().getAll().size)
        assertEquals(1, room.provenanceDao().getAll().size)
        room.close()
        context.deleteDatabase(name)
    }

    @Test fun migrateV6ToV7CarriesEverySessionIntoAnInvestigationWithOneTurnPerRunAndDropsNothing() = runTest {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        // A real M3A.2 session payload: parsed content with two recorded runs (initial + stopped continuation) and one applied proposal.
        var content = com.lifeadmin.autopilot.testing.SavedResearchFixtures.m3a2Session().copy(title = "Picks")
        val run1 = com.lifeadmin.autopilot.ai.research2.RunRecord("r1", com.lifeadmin.autopilot.ai.research2.RunKind.INITIAL, 1_000L, com.lifeadmin.autopilot.ai.research2.ResearchMode.THOROUGH, "claude-sonnet-5-5", 10, usage = com.lifeadmin.autopilot.ai.research2.RunUsage(41_000, 2_600, 0, 0, 5, 42_000), outcome = com.lifeadmin.autopilot.ai.research2.SessionOutcome.COMPLETE)
        val run2 = run1.copy(id = "r2", kind = com.lifeadmin.autopilot.ai.research2.RunKind.CONTINUATION, startedAtEpochMs = 2_000L, usage = com.lifeadmin.autopilot.ai.research2.RunUsage(10_000, 500, 0, 0, 2, 9_000, complete = false), outcome = com.lifeadmin.autopilot.ai.research2.SessionOutcome.STOPPED)
        content = content.copy(runs = listOf(run1, run2), outcome = com.lifeadmin.autopilot.ai.research2.SessionOutcome.STOPPED)
        val codec = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }
        val payload = codec.encodeToString(com.lifeadmin.autopilot.ai.research2.SessionContent.serializer(), content)
        val applied = codec.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.lifeadmin.autopilot.ai.research2.AppliedProposal.serializer()), listOf(com.lifeadmin.autopilot.ai.research2.AppliedProposal("p-1", 5L, "Buy multivitamin", "st1")))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schemaStatements(6).forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO tasks (id,title,notes,status,priority,createdAt,updatedAt,completedAt,archivedAt,deadlineType,deadlineLocalDate,deadlineInstant,deadlineZoneId,snoozedUntil,snoozeRemindAtEnd,waitingReason,waitingCheckInAt,rev,eventLocalDate,eventLabel,intendedLocalDate,targetLocalDate,targetBasis) " +
                    "VALUES ('t1','Supplements',NULL,'ACTIONABLE','NORMAL',1,2,NULL,NULL,'NONE',NULL,NULL,NULL,NULL,1,NULL,NULL,3,NULL,NULL,NULL,NULL,NULL)",
            )
            db.execSQL(
                "INSERT INTO research_sessions (id,taskId,createdAt,updatedAt,title,template,outcome,model,searchCount,sourceCount,payload,applied) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf("s1", "t1", 1_000L, 2_000L, "Picks", "PICK_PER_ITEM", "STOPPED", "claude-sonnet-5-5", 7, 4, payload, applied),
            )
            db.execSQL(
                "INSERT INTO research_sessions (id,taskId,createdAt,updatedAt,title,template,outcome,model,searchCount,sourceCount,payload,applied) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf("s-broken", "t1", 3_000L, 3_000L, "Unreadable", "QUICK_ANSWER", "COMPLETE", "claude-haiku-5-5", 0, 0, "{not json", "[]"),
            )
            db.execSQL(
                "INSERT INTO usage_records (id,feature,model,startedAt,runId,sessionId,taskId,stage,durationMs,inputTokens,outputTokens,cacheWrite5mTokens,cacheWrite1hTokens,cacheReadTokens,webSearches,outcome,usageStatus,estimatedMicros,priceVersion,providerMessageId) " +
                    "VALUES ('u1','RESEARCH','claude-sonnet-5-5',1,'r1','s1','t1','GATHER',1000,41000,2600,0,0,0,5,'COMPLETE','COMPLETE',158050,'2026-10','msg_1')",
            )
            db.version = 6
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        assertEquals("task data untouched", 3, room.taskDao().get("t1")!!.rev)
        val inv = room.investigationDao().get("s1")!!
        assertEquals("t1", inv.taskId)
        assertEquals("TASK", inv.origin)
        assertEquals("Picks", inv.title)
        assertEquals("the latest run's ending is the thread's state", "STOPPED", inv.state)
        assertEquals(2, inv.turnCount)
        assertEquals(1_000L, inv.createdAt.toEpochMilli())
        val turns = room.investigationTurnDao().forInvestigation("s1")
        assertEquals(listOf(0, 1), turns.map { it.turnIndex })
        assertEquals(listOf("INITIAL", "CONTINUATION"), turns.map { it.kind })
        assertEquals(listOf("DONE", "STOPPED"), turns.map { it.state })
        assertEquals(listOf(1_000L, 2_000L), turns.map { it.createdAt.toEpochMilli() })
        val decoded = turns.map { com.lifeadmin.autopilot.ai.investigation.InvestigationJson.decodeTurn(it.payload)!! }
        assertEquals("usage per run is carried over", 41_000L, decoded[0].usage.inputTokens)
        assertEquals("a stopped run's usage is a floor", true, decoded[1].usage.floor)
        assertEquals(listOf("r1", "r2"), decoded.map { it.legacy!!.runId })
        assertEquals("the question is the person's words", com.lifeadmin.autopilot.testing.SavedResearchFixtures.SUPPLEMENTS_REQUEST, decoded[0].objective.question)
        assertEquals(com.lifeadmin.autopilot.ai.investigation.AskKind.RECOMMEND, decoded[0].objective.askKind)
        // The legacy content and applied history read back byte-for-byte through the legacy view.
        val session = com.lifeadmin.autopilot.data.repository.ResearchSessionRepository(room).get("s1")!!
        assertEquals(content, session.content)
        assertEquals(listOf("p-1"), session.applied.map { it.proposalId })
        assertEquals(4, session.content.body.picks.size)
        // An unreadable payload is still carried (never dropped) and simply has no legacy view.
        val broken = room.investigationDao().get("s-broken")!!
        assertEquals(1, broken.turnCount)
        assertTrue(room.investigationTurnDao().forInvestigation("s-broken").single().payload.contains("{not json"))
        assertEquals(null, com.lifeadmin.autopilot.data.repository.ResearchSessionRepository(room).get("s-broken"))
        // Ledger rows keep every value; the new effort column is empty for old calls.
        val u = room.usageDao().get("u1")!!
        assertEquals(158_050L, u.estimatedMicros)
        assertEquals("msg_1", u.providerMessageId)
        assertEquals(null, u.effort)
        assertEquals(1, room.usageDao().forSession("s1").size)
        // The old table is gone; nothing else changed.
        room.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='research_sessions'").use { assertEquals(0, it.count) }
        room.close()
        context.deleteDatabase(name)
    }

    @Test fun migrateV7ToV8AddsRoutineFieldsAndKeepsEveryScheduleAsItWas() = runTest {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schemaStatements(7).forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO tasks (id,title,notes,status,priority,createdAt,updatedAt,completedAt,archivedAt,deadlineType,deadlineLocalDate,deadlineInstant,deadlineZoneId,snoozedUntil,snoozeRemindAtEnd,waitingReason,waitingCheckInAt,rev,eventLocalDate,eventLabel,intendedLocalDate,targetLocalDate,targetBasis) " +
                    "VALUES ('t1','Book train ride',NULL,'ACTIONABLE','NORMAL',1,2,NULL,NULL,'NONE',NULL,NULL,NULL,5000,0,NULL,NULL,4,NULL,NULL,NULL,NULL,NULL)",
            )
            db.execSQL(
                "INSERT INTO reminder_policies (id,taskId,enabled,kind,startLocalDate,zoneMode,zoneId,dayInterval,quietHoursBehavior,snoozeDefaultMinutes,atInstant,lastEditedAt,generation) " +
                    "VALUES ('p1','t1',1,'DAILY_TIMES',NULL,'FOLLOW_DEVICE',NULL,1,'SKIP',30,NULL,1,3)",
            )
            db.execSQL("INSERT INTO reminder_time_slots (id,policyId,localTime,sortOrder) VALUES ('s1','p1','09:00',0)")
            db.execSQL("INSERT INTO reminder_time_slots (id,policyId,localTime,sortOrder) VALUES ('s2','p1','18:00',1)")
            db.version = 7
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val task = room.taskDao().get("t1")!!
        assertEquals(4, task.rev)
        assertEquals(5000L, task.snoozedUntil!!.toEpochMilli())
        assertEquals(false, task.snoozeRemindAtEnd)
        assertEquals("no finished cycle on existing tasks", null, task.cycleDoneUntil)
        val p = room.policyDao().getForTask("t1")!!
        assertEquals("existing schedules are not routines", false, p.routine)
        assertEquals("quiet hours still apply to existing schedules", false, p.ringInQuietHours)
        assertEquals(com.lifeadmin.autopilot.domain.model.QuietHoursBehavior.SKIP, p.quietHoursBehavior)
        assertEquals(30, p.snoozeDefaultMinutes)
        assertEquals(3, p.generation)
        assertEquals(listOf("09:00", "18:00"), room.policyDao().getSlots("p1").sortedBy { it.sortOrder }.map { it.localTime.toString() })
        room.close()
        context.deleteDatabase(name)
    }
}
