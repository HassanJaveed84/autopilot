package com.lifeadmin.autopilot.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.PlanningError
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.reminders.ReminderEngine
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FAKE_KEY
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakePlanningProvider
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.daily
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.testing.rawPlan
import com.lifeadmin.autopilot.ui.plan.PlanPhase
import com.lifeadmin.autopilot.ui.plan.PlanViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class PlanViewModelTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 14, 10, 0))
    private val zone = FakeZoneProvider(CHICAGO)
    private val alarms = FakeAlarmDriver()
    private val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0)))
    private val provider = FakePlanningProvider()
    private lateinit var repo: TaskRepository
    private lateinit var actions: TaskActions
    private lateinit var service: AiPlanningService
    private val text = "Need to book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done."

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds("vm")
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, alarms, FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        repo = TaskRepository(db, clock, ids, zone::current, engine, log)
        actions = TaskActions(db, repo, settings, clock, zone)
        service = AiPlanningService(settings, InMemoryApiKeyStore(FAKE_KEY), provider, repo, clock, zone, ids)
        provider.respond(rawPlan(
            "Book train ride for daughter", steps = listOf("Check train times", "Buy tickets", "Book parking"),
            reminder = daily("09:00", "18:00", phrase = "remind me at 9 and 6 every day until done"),
        ))
    }

    @After fun tearDown() = db.close()

    private fun vm(handle: SavedStateHandle = SavedStateHandle(), initial: String = text, target: String? = null) =
        PlanViewModel(handle, service, repo, actions, settings, clock, zone, target, initial)

    private fun settle(condition: () -> Boolean = { false }) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private fun taskCount() = runBlocking { db.taskDao().getAll().size }

    @Test fun successfulRequestShowsReviewWithoutWritingAnything() {
        val v = vm()
        settle()
        v.requestPlan()
        settle { v.state.value.phase == PlanPhase.REVIEW }
        assertEquals(PlanPhase.REVIEW, v.state.value.phase)
        assertEquals(3, v.state.value.draft!!.steps.size)
        assertEquals(0, taskCount())
        assertTrue(alarms.alarms.isEmpty())
    }

    @Test fun failureKeepsTheUsersTextAndOffersRetry() {
        provider.fail(PlanningError.ServerError(503))
        val v = vm()
        settle()
        v.requestPlan()
        settle { v.state.value.error != null }
        assertEquals(PlanPhase.INPUT, v.state.value.phase)
        assertEquals(text, v.state.value.text)
        assertTrue(v.state.value.error!!.retryable)
        provider.respond(rawPlan("Book train ride"))
        v.retry()
        settle { v.state.value.phase == PlanPhase.REVIEW }
        assertEquals(PlanPhase.REVIEW, v.state.value.phase)
    }

    @Test fun cancelStopsTheRequestKeepsTextAndSavesNothing() {
        provider.gate = CompletableDeferred()
        val v = vm()
        settle()
        v.requestPlan()
        settle { provider.requests.isNotEmpty() }
        assertEquals(PlanPhase.WORKING, v.state.value.phase)
        v.cancel()
        settle()
        provider.gate!!.complete(Unit) // a late answer must not resurrect the proposal
        settle()
        assertEquals(PlanPhase.INPUT, v.state.value.phase)
        assertEquals(PlanningError.Cancelled, v.state.value.error)
        assertEquals(text, v.state.value.text)
        assertNull(v.state.value.draft)
        assertEquals(0, taskCount())
    }

    @Test fun processDeathDuringRequestRestoresTextAndExplains() {
        val handle = SavedStateHandle(mapOf(PlanViewModel.KEY_TEXT to "my words", PlanViewModel.KEY_PHASE to PlanPhase.WORKING.name))
        val v = vm(handle, initial = "ignored")
        assertEquals("my words", v.state.value.text)
        assertEquals(PlanPhase.INPUT, v.state.value.phase)
        assertTrue(v.state.value.interrupted)
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun reviewSurvivesRecreationWithEdits() {
        val handle = SavedStateHandle()
        val v1 = vm(handle)
        settle()
        v1.requestPlan()
        settle { v1.state.value.phase == PlanPhase.REVIEW }
        v1.updateDraft { it.copy(title = "Edited title") }
        // Simulate rotation / process recreation: a new ViewModel on the same saved state.
        val v2 = vm(handle, initial = "ignored")
        assertEquals(PlanPhase.REVIEW, v2.state.value.phase)
        assertEquals("Edited title", v2.state.value.draft!!.title)
        assertEquals(text, v2.state.value.draft!!.originalText)
        assertEquals(1, provider.requests.size)
    }

    @Test fun doubleTapApproveCreatesOneTask() {
        val v = vm()
        settle()
        v.requestPlan()
        settle { v.state.value.phase == PlanPhase.REVIEW }
        v.approve(); v.approve(); v.approve()
        settle { v.state.value.phase == PlanPhase.SAVED }
        assertEquals(PlanPhase.SAVED, v.state.value.phase)
        assertEquals(1, taskCount())
        assertTrue(v.state.value.savedWithReminders)
        assertEquals(1, alarms.alarms.size)
    }

    @Test fun rejectReturnsToTheOriginalTextAndWritesNothing() {
        val v = vm()
        settle()
        v.requestPlan()
        settle { v.state.value.phase == PlanPhase.REVIEW }
        v.reject()
        assertEquals(PlanPhase.INPUT, v.state.value.phase)
        assertEquals(text, v.state.value.text)
        assertEquals(0, taskCount())
    }

    @Test fun saveWithoutAiWorksWhenAiIsOff() {
        runBlocking { settings.update { it.copy(aiEnabled = false) } }
        val v = vm()
        settle()
        v.saveWithoutAi()
        settle { v.state.value.savedTaskId != null }
        assertNotNull(v.state.value.savedTaskId)
        assertEquals(PlanPhase.SAVED, v.state.value.phase)
        assertEquals(1, taskCount())
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun validationErrorFromAnEditIsShownAndNothingIsSaved() {
        val v = vm()
        settle()
        v.requestPlan()
        settle { v.state.value.phase == PlanPhase.REVIEW }
        v.updateDraft { it.copy(title = "  ") }
        v.approve()
        settle { v.state.value.saveError != null }
        assertNotNull(v.state.value.saveError)
        assertEquals(PlanPhase.REVIEW, v.state.value.phase)
        assertEquals(0, taskCount())
    }
}
