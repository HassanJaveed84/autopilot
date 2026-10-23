package com.lifeadmin.autopilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.proposal.ApplyChoice
import com.lifeadmin.autopilot.domain.proposal.ApplyResult
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.SetStatus
import com.lifeadmin.autopilot.domain.proposal.TargetKind
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two Apply requests for the same proposal set, both issued while the set is still OPEN with the
 * item PENDING. Whatever the interleaving, the item must be applied once: one note line, one
 * provenance record, one history event, one `lastApply`. Deterministic: a gating coordinator
 * holds both callers until each has read the set, then lets them through one at a time, so the
 * second always works from a snapshot taken before the first wrote.
 */
@RunWith(AndroidJUnit4::class)
class ProposalApplierConcurrencyTest {
    /** Per-task mutual exclusion like production, plus a barrier so both callers read the set before either writes. */
    private class GatingCoordinator : ScheduleCoordinator {
        private val lock = Mutex()
        private val arrivals = AtomicInteger()
        private val bothArrived = CompletableDeferred<Unit>()
        /** Off during fixture setup (createTask also mutates); on for the two racing applies. */
        @Volatile var armed = false
        override suspend fun <T> mutateTask(taskId: String, dismissNotification: Boolean, block: suspend () -> T): T {
            if (armed) {
                if (arrivals.incrementAndGet() >= 2) bothArrived.complete(Unit)
                bothArrived.await()
            }
            return lock.withLock { block() }
        }
    }

    private lateinit var db: AppDatabase
    private val clock = FakeClock(Instant.parse("2026-10-23T09:00:00Z"))

    @Before fun setUp() { db = inMemoryDatabase() }
    @After fun tearDown() = db.close()

    @Test fun simultaneousAppliesOfTheSameItemApplyItExactlyOnce() = runBlocking {
        val coordinator = GatingCoordinator()
        val tasks = TaskRepository(db, clock, SequentialIds("t"), { CHICAGO }, coordinator)
        val sets = ProposalSetRepository(db)
        val applier = ProposalApplier(db, tasks, sets, clock, SequentialIds("a"))
        val taskId = tasks.createTask(NewTask("Renew passport", notes = "Expires in March", status = TaskStatus.ACTIONABLE))
        val snap = applier.snapshot(taskId)!!
        sets.save(
            ProposalSet(
                "set1", ProposalSource.RESEARCH, TargetKind.TASK, taskId, investigationId = "inv1", madeAtMs = clock.now().toEpochMilli(),
                baseOriginal = snap, base = snap,
                items = listOf(
                    ProposalItem("n", ItemKind.APPEND_NOTE, value = ItemValue.Text("Processing takes about 3 weeks")),
                    ProposalItem("s", ItemKind.ADD_STEP, value = ItemValue.Step("Book appointment")),
                ),
            ),
        )

        coordinator.armed = true
        val results = withContext(Dispatchers.Default) {
            listOf(
                // Only the note: nothing about a second append conflicts with the first, so stale state alone decides.
                async { applier.apply("set1", listOf(ApplyChoice("n"))) },
                async { applier.apply("set1", listOf(ApplyChoice("n"))) },
            ).awaitAll()
        }

        assertEquals("exactly one apply succeeds", 1, results.count { it is ApplyResult.Applied })
        assertTrue("the other is refused, never silently merged", results.any { it is ApplyResult.Rejected })
        val task = tasks.getTask(taskId)!!
        assertEquals("the note line appears once", "Expires in March\nProcessing takes about 3 weeks", task.notes)
        assertEquals(1, sets.provenanceForTask(taskId).size)
        assertEquals(1, db.eventDao().getForTask(taskId).count { it.eventType == EventType.EDITED && it.summary.startsWith("Applied") })
        val set = sets.get("set1")!!
        assertEquals(ItemStatus.APPLIED, set.item("n")!!.status)
        assertEquals("research set stays open: the step is still pending", SetStatus.OPEN, set.status)
        assertEquals(ItemStatus.PENDING, set.item("s")!!.status)
        assertEquals(listOf("n"), set.lastApply!!.appliedItemIds)
        assertEquals("one apply → one rev bump", set.lastApply!!.revBefore + 1, task.rev)
    }
}
