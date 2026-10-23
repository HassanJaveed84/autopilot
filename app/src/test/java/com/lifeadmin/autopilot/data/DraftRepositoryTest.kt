package com.lifeadmin.autopilot.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.DraftRepository
import com.lifeadmin.autopilot.domain.draft.DraftStatus
import com.lifeadmin.autopilot.domain.draft.DraftStep
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** PLAN-010/013/014: drafts are durable, never tasks, and keep paid results across a restart. */
@RunWith(AndroidJUnit4::class)
class DraftRepositoryTest {
    private val clock = FakeClock(Instant.parse("2026-10-23T09:00:00Z"))

    @Test fun draftLifecycleNeverCreatesATask() = runTest {
        val db = inMemoryDatabase()
        val repo = DraftRepository(db, clock, SequentialIds("d"))
        val d = repo.create("Cancel the gym before we move")
        assertEquals("Cancel the gym before we move", repo.observeOpen().first().single().text)
        repo.update(d.id) { it.copy(steps = listOf(DraftStep("s1", "Find the contract", ContentOrigin.PLANNING))) }
        repo.attachPlanningResult(d.id, """{"proposalId":"p1"}""", "claude-haiku-5-5")
        val stored = repo.get(d.id)!!
        assertEquals(1, stored.content.steps.size)
        assertEquals("""{"proposalId":"p1"}""", stored.content.planningResult)
        assertEquals("Planned with claude-haiku-5-5", stored.content.history.single().summary)
        assertEquals("a draft is not a task", 0, db.taskDao().count())
        assertEquals(0, db.policyDao().getAll().size)
        repo.discard(d.id)
        assertTrue(repo.observeOpen().first().isEmpty())
        assertEquals(DraftStatus.DISCARDED, repo.get(d.id)!!.status)
        assertNull("discarded drafts stop changing", repo.update(d.id) { it.copy(title = "x") }?.content?.title)
        db.close()
    }

    @Test fun draftsAndPaidResultsSurviveReopeningTheDatabase() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "draft-restart.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).setQueryCoroutineContext(Dispatchers.IO).build()
        val first = open()
        val id = DraftRepository(first, clock, SequentialIds("d")).let { r ->
            val d = r.create("Book the dentist")
            r.attachPlanningResult(d.id, "{\"proposalId\":\"p2\"}", "claude-haiku-5-5")
            d.id
        }
        first.close()
        val second = open()
        val back = DraftRepository(second, clock, SequentialIds("e")).get(id)
        assertNotNull(back)
        assertEquals("Book the dentist", back!!.text)
        assertEquals("the paid result is there without re-running planning", "{\"proposalId\":\"p2\"}", back.content.planningResult)
        assertEquals(DraftStatus.DRAFT, back.status)
        second.close()
        context.deleteDatabase(name)
    }

    @Test fun markSavedLinksTheTaskAndLeavesTheOpenList() = runTest {
        val db = inMemoryDatabase()
        val repo = DraftRepository(db, clock, SequentialIds("d"))
        val d = repo.create("Renew passport")
        repo.markSaved(d.id, "task-9")
        assertTrue(repo.observeOpen().first().isEmpty())
        assertEquals("task-9", repo.get(d.id)!!.savedTaskId)
        assertEquals(DraftStatus.SAVED, repo.get(d.id)!!.status)
        db.close()
    }
}
