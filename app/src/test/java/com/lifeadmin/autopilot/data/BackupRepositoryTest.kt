package com.lifeadmin.autopilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.BackupRepository
import com.lifeadmin.autopilot.data.repository.ImportException
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.testing.t
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private val clock = FakeClock(Instant.parse("2026-10-16T14:00:00Z"))
    private var reconciled = 0

    @Before fun setUp() { source = inMemoryDatabase(); target = inMemoryDatabase() }
    @After fun tearDown() { source.close(); target.close() }

    private fun backup(db: AppDatabase) = BackupRepository(db, clock, SequentialIds("imp"), "test", afterImport = { reconciled++ })

    private suspend fun seed(): String {
        val repo = TaskRepository(source, clock, SequentialIds(), { CHICAGO }, ScheduleCoordinator.Direct)
        val id = repo.createTask(NewTask("Book train ride", notes = "Window seat", priority = Priority.HIGH, status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
        repo.addStep(id, "Find dates")
        repo.createTask(NewTask("Renew registration"))
        return backup(source).exportJson()
    }

    @Test fun exportImportRoundTrip() = runTest {
        val json = seed()
        assertTrue(json.contains("\"format\": \"life-admin-autopilot-backup\""))
        val result = backup(target).importJson(json)
        assertEquals(2, result.created)
        assertEquals(1, reconciled)
        val tasks = target.taskDao().getAll().associateBy { it.title }
        assertEquals("Window seat", tasks["Book train ride"]!!.notes)
        val policy = target.policyDao().getForTask(tasks["Book train ride"]!!.id)!!
        assertEquals(listOf(t(9), t(18)), target.policyDao().getSlots(policy.id).map { it.localTime })
        assertEquals(1, target.stepDao().getAll().size)
        // Occurrences are never imported; the scheduler regenerates them.
        assertEquals(0, target.occurrenceDao().getAll().size)
    }

    @Test fun reimportIsIdempotentAndNewerWins() = runTest {
        val json = seed()
        backup(target).importJson(json)
        val again = backup(target).importJson(json)
        assertEquals(0, again.created)
        assertEquals(2, again.skipped)
        assertEquals(2, target.taskDao().count())

        // A newer version of one task replaces the local copy.
        val repo = TaskRepository(source, clock, SequentialIds("x"), { CHICAGO }, ScheduleCoordinator.Direct)
        val id = source.taskDao().getAll().first { it.title == "Renew registration" }.id
        clock.advance(Duration.ofMinutes(5))
        repo.updateDetails(id, "Renew car registration", null, Priority.NORMAL)
        val r = backup(target).importJson(backup(source).exportJson())
        assertEquals(1, r.updated)
        assertEquals("Renew car registration", target.taskDao().get(id)!!.title)
        assertEquals(2, target.taskDao().count())
    }

    @Test fun malformedJsonIsRejected() = runTest {
        expectImportFailure("{not json")
        expectImportFailure("""{"format":"something-else","schemaVersion":1,"exportedAt":"2026-01-01T00:00:00Z"}""")
        expectImportFailure("""{"format":"life-admin-autopilot-backup","schemaVersion":99,"exportedAt":"2026-01-01T00:00:00Z"}""")
    }

    @Test fun partiallyCorruptBackupChangesNothing() = runTest {
        val json = seed()
        // Corrupt the second task's status; the first task is valid but must not be imported either.
        val corrupted = json.replace("\"status\": \"INBOX\"", "\"status\": \"HACKED\"")
        expectImportFailure(corrupted)
        assertEquals(0, target.taskDao().count())
        assertEquals(0, target.stepDao().getAll().size)
    }

    @Test fun orphanReferencesAreRejected() = runTest {
        val json = seed()
        val firstTaskId = source.taskDao().getAll().first().id
        expectImportFailure(json.replace("\"taskId\": \"$firstTaskId\"", "\"taskId\": \"missing-task\""))
        assertEquals(0, target.taskDao().count())
    }

    @Test fun pathLikeOrHugeIdsAreRejected() = runTest {
        val json = seed()
        val id = source.taskDao().getAll().first().id
        expectImportFailure(json.replace(id, "../../etc/passwd"))
    }

    @Test fun oversizedInputIsRejectedBeforeParsing() = runTest {
        val big = ByteArray(BackupRepository.MAX_BYTES + 10) { 'a'.code.toByte() }
        try {
            backup(target).readBounded(big.inputStream())
            fail("expected size rejection")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    private suspend fun expectImportFailure(raw: String) {
        try {
            backup(target).importJson(raw)
            fail("expected import failure")
        } catch (e: ImportException) {
            // ok
        }
        assertEquals(0, reconciled)
    }
}
