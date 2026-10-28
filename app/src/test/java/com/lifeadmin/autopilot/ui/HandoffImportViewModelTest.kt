package com.lifeadmin.autopilot.ui

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.share.applink.HandoffDestination
import com.lifeadmin.autopilot.share.applink.HandoffImporter
import com.lifeadmin.autopilot.share.applink.ImportLinkParser
import com.lifeadmin.autopilot.share.applink.HandoffStepDetails
import com.lifeadmin.autopilot.share.applink.ImportRefusal
import com.lifeadmin.autopilot.share.applink.LinkHandoff
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.ui.handoff.HandoffImportViewModel
import com.lifeadmin.autopilot.ui.handoff.ImportStage
import com.lifeadmin.autopilot.ui.handoff.ImportUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** The import screen's state: warm links replace it, process death resumes it, and only validated fields are kept. */
@RunWith(AndroidJUnit4::class)
class HandoffImportViewModelTest {
    private val host = "links.example.org"
    private lateinit var db: AppDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var sets: ProposalSetRepository
    private lateinit var importer: HandoffImporter
    private lateinit var grinder: String
    private lateinit var kettle: String
    private val clock = FakeClock(Instant.parse("2026-10-27T15:00:00Z"))
    private val room = HoldingDispatcher()

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(room)
            .build()
        val ids = SequentialIds("id")
        tasks = TaskRepository(db, clock, ids, { CHICAGO }, ScheduleCoordinator.Direct)
        sets = ProposalSetRepository(db)
        importer = HandoffImporter(tasks, sets, ProposalApplier(db, tasks, sets, clock, ids), clock)
        grinder = tasks.createTask(NewTask("Choose a coffee grinder", status = TaskStatus.ACTIONABLE))
        kettle = tasks.createTask(NewTask("Buy a kettle", status = TaskStatus.ACTIONABLE))
    }

    @After fun tearDown() {
        db.close()
        room.shutdown()
    }

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = HandoffImportViewModel(saved, ImportLinkParser(host), importer, tasks.observeSummaries())
    private fun url(ref: String?, step: String = "Check warranty") = LinkHandoff("Choose a coffee grinder", emptyList(), listOf(step), emptyList(), ref).toUrl(host, ImportLinkParser.PATH)

    /** Room work runs off the main thread; the view model publishes on it. */
    private fun HandoffImportViewModel.awaitState(done: (ImportUi) -> Boolean): ImportUi = runBlocking {
        withTimeout(10_000) {
            while (!done(state.value)) {
                ShadowLooper.idleMainLooper()
                delay(10)
            }
        }
        state.value
    }

    private fun HandoffImportViewModel.await(stage: ImportStage) = awaitState { it.stage == stage }
    private fun HandoffImportViewModel.awaitReview(taskId: String) = awaitState { it.stage == ImportStage.REVIEW && it.taskId == taskId }

    @Test fun aWarmLinkReplacesTheScreenAndARepeatFindsTheSameSet() {
        val vm = vm()
        vm.onLink(url(grinder), "com.openai.chatgpt")
        val first = vm.await(ImportStage.REVIEW)
        assertEquals(grinder, first.taskId)
        assertEquals("com.openai.chatgpt", first.openedBy)
        assertFalse(first.existed)

        vm.onLink(url(kettle, step = "Descale it"), "com.openai.chatgpt")
        assertEquals(kettle, vm.awaitReview(kettle).taskId)

        vm.onLink(url(grinder), "com.openai.chatgpt")
        val again = vm.awaitReview(grinder)
        assertTrue(again.existed)
        assertEquals(first.setId, again.setId)
        assertEquals(1, runBlocking { sets.getForTarget(grinder) }.size)
    }

    @Test fun aDifferentLinkForTheSameTaskThenTheFirstAgainIsARepeat() {
        val vm = vm()
        vm.onLink(url(grinder, step = "A"), null)
        val a = vm.await(ImportStage.REVIEW)
        vm.onLink(url(grinder, step = "B"), null)
        val b = vm.awaitState { it.stage == ImportStage.REVIEW && it.setId != a.setId }
        assertFalse(b.existed)
        vm.onLink(url(grinder, step = "A"), null)
        val again = vm.awaitState { it.stage == ImportStage.REVIEW && it.setId == a.setId }
        assertTrue(again.existed)
    }

    @Test fun processDeathResumesFromTheValidatedFieldsAlone() {
        val saved = SavedStateHandle()
        val vm = vm(saved)
        vm.onLink(url(grinder), null)
        val review = vm.await(ImportStage.REVIEW)
        // Nothing raw is kept: no key holds the URL or its encoded fragment.
        saved.keys().forEach { k -> assertFalse(k, saved.get<Any>(k).toString().contains("https://") || saved.get<Any>(k).toString().contains("%20")) }

        val restored = vm(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertEquals(review, restored.state.value)
        // A screen that died mid-step redoes it and lands on the same set.
        val mid = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }).apply { set("handoff_import_stage", ImportStage.WORKING.name) }
        assertEquals(review.setId, vm(mid).await(ImportStage.REVIEW).setId)
    }

    @Test fun richStepDetailsSurviveProcessDeathAndReachTheSameSet() {
        val rich = LinkHandoff(
            "Choose a coffee grinder", emptyList(), listOf("Buy the Encore ESP"), emptyList(), grinder, version = 2,
            stepDetails = mapOf(0 to HandoffStepDetails(listOf("About \$200."), listOf(com.lifeadmin.autopilot.domain.model.StepLink("https://baratza.com/encore-esp/?finish=black", "Product page")))),
        )
        val saved = SavedStateHandle()
        val vm = vm(saved)
        vm.onLink(rich.toUrl(host, ImportLinkParser.PATH), null)
        val review = vm.await(ImportStage.REVIEW)
        assertEquals(rich, review.handoff)
        val restored = vm(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertEquals(review, restored.state.value)
        val mid = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }).apply { set("handoff_import_stage", ImportStage.WORKING.name) }
        assertEquals(review.setId, vm(mid).await(ImportStage.REVIEW).setId)
    }

    @Test fun refusalsAndUnresolvedReferencesKeepNoTaskAndNoSet() {
        val vm = vm()
        vm.onLink("https://$host/autopilot/import#ap=v1&t=T&s=a+b", null)
        assertEquals(ImportUi(ImportStage.REFUSED, refusal = ImportRefusal.MALFORMED), vm.state.value)
        vm.onLink(url("id-999"), null)
        val choose = vm.await(ImportStage.CHOOSE)
        assertEquals(HandoffDestination.Why.NOT_FOUND, choose.why)
        assertNull(choose.taskId)
        assertTrue(runBlocking { sets.getAll() }.isEmpty())
        vm.onLink(null, null)
        assertEquals(ImportStage.EMPTY, vm.state.value.stage)
    }

    @Test fun changeThenBackKeepsTheReviewAndChangeThenPickMovesIt() {
        val vm = vm()
        vm.onLink(url(grinder), null)
        val review = vm.await(ImportStage.REVIEW)
        vm.changeDestination()
        assertTrue(vm.state.value.changing && vm.state.value.stage == ImportStage.CHOOSE)
        vm.cancelChange()
        assertEquals(review, vm.state.value)

        vm.changeDestination()
        vm.choose(kettle)
        val moved = vm.awaitReview(kettle)
        assertEquals(kettle, moved.taskId)
        assertTrue(runBlocking { sets.getForTarget(grinder) }.isEmpty())
        assertEquals(1, runBlocking { sets.getForTarget(kettle) }.size)
    }

    /**
     * A link that arrives while the previous one is still being prepared replaces it. The replaced
     * work is cancelled; when its database call returns it must not publish anything, or the new
     * link's review turns into "Something went wrong" (CI run #188: the emulator's third delivery).
     */
    @Test fun aLinkArrivingMidImportKeepsItsReviewWhenTheReplacedWorkReturns() {
        val vm = vm()
        vm.onLink(url(grinder, step = "A"), null)
        val a = vm.await(ImportStage.REVIEW)

        val gate = room.holdNext()
        vm.onLink(url(grinder, step = "B"), null)
        vm.onLink(url(grinder, step = "A"), null)
        val again = vm.awaitState { it.stage == ImportStage.REVIEW && it.setId == a.setId }
        assertTrue(again.existed)

        gate.release()
        ShadowLooper.idleMainLooper()
        assertEquals(again, vm.state.value)
        assertEquals("the replaced link was stopped before it saved anything", 1, runBlocking { sets.getForTarget(grinder) }.size)
    }
}

/** Room's query context. [holdNext] parks the next database call made from the main thread (the view model's) until released. */
private class HoldingDispatcher : CoroutineDispatcher() {
    private val pool = Executors.newCachedThreadPool()

    @Volatile private var gate: Gate? = null

    class Gate {
        private val open = CountDownLatch(1)
        private val ran = CountDownLatch(1)
        fun await() = open.await()
        fun ran() = ran.countDown()

        /** Lets the parked call finish and waits until it has handed its result back. */
        fun release() {
            open.countDown()
            check(ran.await(10, TimeUnit.SECONDS)) { "the parked database call never finished" }
        }
    }

    fun holdNext(): Gate = Gate().also { gate = it }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val held = if (Looper.myLooper() == Looper.getMainLooper()) synchronized(this) { gate.also { gate = null } } else null
        pool.execute {
            held?.await()
            try {
                block.run()
            } finally {
                held?.ran()
            }
        }
    }

    fun shutdown() = pool.shutdownNow()
}
