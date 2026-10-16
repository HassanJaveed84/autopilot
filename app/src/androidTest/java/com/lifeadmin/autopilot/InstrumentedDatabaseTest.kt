package com.lifeadmin.autopilot

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.local.Migrations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

/** On-device Room checks: real SQLite, exported-schema migration via MigrationTestHelper. */
@RunWith(AndroidJUnit4::class)
class InstrumentedDatabaseTest {
    private val dbName = "instrumented-migration.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate1To2PreservesTaskTextAndPolicyTimes() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO tasks (id,title,notes,status,priority,createdAt,updatedAt,completedAt,archivedAt,deadlineType,deadlineLocalDate,deadlineInstant,deadlineZoneId,snoozedUntil,waitingReason,waitingCheckInAt) " +
                    "VALUES ('t1','Book train ride','Window seat','ACTIONABLE','NORMAL',1,1,NULL,NULL,'NONE',NULL,NULL,NULL,NULL,NULL,NULL)",
            )
            db.execSQL(
                "INSERT INTO reminder_policies (id,taskId,enabled,kind,startLocalDate,zoneMode,zoneId,dayInterval,quietHoursBehavior,snoozeDefaultMinutes,atInstant,lastEditedAt,generation) " +
                    "VALUES ('p1','t1',1,'DAILY_TIMES',NULL,'FOLLOW_DEVICE',NULL,1,NULL,NULL,NULL,1,1)",
            )
            db.execSQL("INSERT INTO reminder_time_slots (id,policyId,localTime,sortOrder) VALUES ('a','p1','09:00',0),('b','p1','18:00',1)")
        }
        helper.runMigrationsAndValidate(dbName, 2, true, *Migrations.ALL).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(*Migrations.ALL).build()
        runBlocking {
            assertEquals("Book train ride", room.taskDao().get("t1")!!.title)
            assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), room.policyDao().getSlots("p1").map { it.localTime })
        }
        room.close()
    }
}
