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
}
