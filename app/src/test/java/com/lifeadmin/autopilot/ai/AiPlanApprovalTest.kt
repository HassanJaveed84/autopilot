package com.lifeadmin.autopilot.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.ValidationException
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
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
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class AiPlanApprovalTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 14, 10, 0))
    private val zone = FakeZoneProvider(CHICAGO)
    private val alarms = FakeAlarmDriver()
    private val notifications = FakeNotificationPublisher()
    private val settings = InMemorySettingsRepository(
        AppSettings(quietHours = AppSettings().quietHours.copy(enabled = false), aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0)),
    )
    private val keys = InMemoryApiKeyStore(FAKE_KEY)
    private val provider = FakePlanningProvider()
    private var online: Boolean? = true
    private lateinit var repo: TaskRepository
    private lateinit var engine: ReminderEngine
    private lateinit var service: AiPlanningService

    private val trainText = "Need to book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done."
    private val trainPlan = rawPlan(
        "Book train ride for daughter",
        steps = listOf("Check train times and prices", "Buy the tickets", "Find and book parking"),
        nextStep = "Check train times and prices",
        reminder = daily("09:00", "18:00", phrase = "remind me at 9 and 6 every day until done"),
    )

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds("ai")
        val log = RoomDiagnosticsLog(db, clock, ids)
        engine = ReminderEngine(db, settings, alarms, notifications, clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        repo = TaskRepository(db, clock, ids, zone::current, engine, log)
        service = AiPlanningService(settings, keys, provider, repo, clock, zone, ids) { online }
    }

    @After fun tearDown() = db.close()

    private fun taskCount() = runBlocking { db.taskDao().getAll().size }
    private fun propose(text: String = trainText): ProposalDraft {
        provider.respond(trainPlan)
        val o = runBlocking { service.proposeNewTask(text) }
        return (o as? ProposalOutcome.Ready)?.draft ?: throw AssertionError("expected a draft, got $o")
    }
    private fun approve(d: ProposalDraft) = runBlocking { repo.createPlannedTask(ProposalMapper.toCreate(d, clock.now(), zone.current())) }

    @Test fun proposingWritesNothingAndSchedulesNothing() {
        val d = propose()
        assertEquals(0, taskCount())
        assertTrue(alarms.alarms.isEmpty())
        assertTrue(runBlocking { db.policyDao().getAll() }.isEmpty())
        assertEquals(trainText, d.originalText)
    }

    @Test fun onlyTheTextAndMinimalContextAreSentWithTheStoredKey() {
        runBlocking { repo.createTask(com.lifeadmin.autopilot.data.repository.NewTask("Private unrelated task", notes = "secret notes")) }
        propose()
        val req = provider.requests.single()
        assertEquals(PlanningMode.CAPTURE, req.mode)
        assertEquals(trainText, req.text)
        assertEquals(null, req.task)
        assertEquals("America/Chicago", req.context.zoneId)
        assertEquals(clock.now().atZone(CHICAGO).toLocalDateTime().withSecond(0).withNano(0), req.context.nowLocal)
        val wire = PlanningPrompt.userMessage(req)
        assertFalse("other tasks are never sent", wire.contains("Private unrelated task") || wire.contains("secret notes"))
        assertEquals(listOf(FAKE_KEY), provider.keysSeen.toList())
    }

    @Test fun approvalCreatesARealTaskWithStepsAndBothDailyReminders() {
        val (id, created) = approve(propose())
        assertTrue(created)
        val t = runBlocking { repo.getTask(id) }!!
        assertEquals("Book train ride for daughter", t.title)
        assertEquals(TaskStatus.ACTIONABLE, t.status)
        assertEquals(listOf("Check train times and prices", "Buy the tickets", "Find and book parking"), runBlocking { repo.getSteps(id) }.map { it.title })
        val p = runBlocking { repo.getPolicy(id) }!!
        assertTrue(p.enabled)
        assertEquals(ReminderKind.DAILY_TIMES, p.kind)
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), p.times)
        // The real engine scheduled the next occurrence: today 18:00 local (it's 10:00 now).
        assertEquals(at(CHICAGO, 2026, 10, 14, 18, 0), alarms.single().triggerAt)
    }

    @Test fun theCreatedTaskKeepsRemindingUntilDone() {
        val (id, _) = approve(propose())
        val first = alarms.single()
        clock.instant = first.triggerAt.plusSeconds(1)
        alarms.fired(first.occurrenceId)
        runBlocking { engine.onAlarm(first.occurrenceId, first.generation) }
        assertEquals(1, notifications.posted.size)
        assertEquals(at(CHICAGO, 2026, 10, 15, 9, 0), alarms.single().triggerAt)
        runBlocking { repo.complete(id) }
        assertTrue("Done cancels future alarms", alarms.alarms.isEmpty())
    }

    @Test fun repeatedApprovalIsIdempotent() {
        val d = propose()
        val (id1, c1) = approve(d)
        val (id2, c2) = approve(d)
        assertEquals(id1, id2)
        assertTrue(c1); assertFalse(c2)
        assertEquals(1, taskCount())
        assertEquals(3, runBlocking { repo.getSteps(id1) }.size)
        assertEquals(1, alarms.alarms.size)
    }

    @Test fun concurrentApprovalsCreateOneTaskAndOneAlarm() {
        val d = propose()
        val results = runBlocking { (1..5).map { async { approve(d) } }.awaitAll() }
        assertEquals(1, results.count { it.second })
        assertEquals(1, taskCount())
        assertEquals(1, alarms.alarms.size)
    }

    @Test fun rejectingAProposalWritesNothing() {
        propose()
        // Reject = simply not calling the repository.
        assertEquals(0, taskCount())
        assertTrue(alarms.alarms.isEmpty())
    }

    @Test fun invalidUserEditIsRejectedBeforeAnyWrite() {
        val d = propose().let { it.copy(reminder = it.reminder.copy(times = emptyList())) }
        try { approve(d); fail("expected validation error") } catch (e: ValidationException) { assertTrue(e.message!!.contains("reminder time")) }
        assertEquals(0, taskCount())
    }

    @Test fun waitingPlanCreatesAWaitingTaskWithItsCheckIn() {
        provider.respond(rawPlan("Get the report from Sam", waiting = RawWaiting(true, "Sam", "2026-10-15", "15:00")))
        val d = (runBlocking { service.proposeNewTask("Sam is sending the report; check in Thursday at 3pm") } as ProposalOutcome.Ready).draft
        val (id, _) = approve(d)
        val t = runBlocking { repo.getTask(id) }!!
        assertEquals(TaskStatus.WAITING, t.status)
        assertEquals(at(CHICAGO, 2026, 10, 15, 15, 0), t.waitingCheckInAt)
        assertEquals(at(CHICAGO, 2026, 10, 15, 15, 0), alarms.single().triggerAt)
    }

    @Test fun followThroughSendsOnlyTheSelectedTaskAndAppliesTheConfirmedDiffOnce() {
        val (id, _) = approve(propose())
        provider.respond(rawPlan(
            "Book train ride for daughter",
            steps = listOf("Buy the tickets", "Ask about a child discount"),
            nextStep = "Ask about a child discount",
            advice = "Start with the discount question; it decides which ticket to buy.",
        ))
        val d = (runBlocking { service.proposeFollowThrough(id, "I keep putting this off") } as ProposalOutcome.Ready).draft
        val req = provider.requests.last()
        assertEquals(PlanningMode.FOLLOW_THROUGH, req.mode)
        assertEquals("Book train ride for daughter", req.task!!.title)
        assertEquals("existing step is not suggested again", listOf("Ask about a child discount"), d.steps.map { it.text })
        assertFalse("suggested reminders are never pre-applied to an existing task", d.reminder.apply)
        val edit = ProposalMapper.toEdit(d, clock.now(), zone.current())
        runBlocking { repo.applyTaskEdit(edit) }
        assertEquals(4, runBlocking { repo.getSteps(id) }.size)
        // Applying the same suggestion again is refused (the task changed), so no duplicate steps.
        clock.instant = clock.instant.plusSeconds(5)
        try { runBlocking { repo.applyTaskEdit(edit) }; fail("expected duplicate apply rejection") } catch (e: ValidationException) { assertTrue(e.message!!.contains("already been applied")) }
        assertEquals(4, runBlocking { repo.getSteps(id) }.size)
        assertEquals("reminders untouched", listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), runBlocking { repo.getPolicy(id) }!!.times)
    }

    @Test fun staleSuggestionIsRejectedIfTheUserEditedTheTaskMeanwhile() {
        val (id, _) = approve(propose())
        provider.respond(rawPlan("Book train ride", steps = listOf("New step")))
        val d = (runBlocking { service.proposeFollowThrough(id, "") } as ProposalOutcome.Ready).draft
        clock.instant = clock.instant.plusSeconds(60)
        runBlocking { repo.addStep(id, "User added this meanwhile") }
        try { runBlocking { repo.applyTaskEdit(ProposalMapper.toEdit(d, clock.now(), zone.current())) }; fail() } catch (e: ValidationException) { }
        assertFalse(runBlocking { repo.getSteps(id) }.any { it.title == "New step" })
    }

    @Test fun disabledAiMakesNoCallAndReportsDisabled() {
        runBlocking { settings.update { it.copy(aiEnabled = false) } }
        assertEquals(ProposalOutcome.Failed(PlanningError.Disabled), runBlocking { service.proposeNewTask(trainText) })
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun missingConsentOrKeyMakesNoCall() {
        runBlocking { settings.update { it.copy(aiConsentAt = null) } }
        assertEquals(ProposalOutcome.Failed(PlanningError.NoConsent), runBlocking { service.proposeNewTask(trainText) })
        runBlocking { settings.update { it.copy(aiConsentAt = clock.now()) } }
        keys.delete()
        assertEquals(ProposalOutcome.Failed(PlanningError.NoApiKey), runBlocking { service.proposeNewTask(trainText) })
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun offlineMakesNoCall() {
        online = false
        assertEquals(ProposalOutcome.Failed(PlanningError.Offline), runBlocking { service.proposeNewTask(trainText) })
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun overlongInputIsRejectedLocallyNotTruncated() {
        val long = "x".repeat(AiConfig.MAX_INPUT_CHARS + 1)
        assertEquals(ProposalOutcome.Failed(PlanningError.InputTooLong), runBlocking { service.proposeNewTask(long) })
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun dailyRequestCapBoundsSpendAndResetsNextDay() {
        provider.respond(trainPlan)
        repeat(AiConfig.MAX_REQUESTS_PER_DAY) { runBlocking { service.proposeNewTask("Task $it") } }
        assertEquals(ProposalOutcome.Failed(PlanningError.DailyLimit), runBlocking { service.proposeNewTask("one more") })
        assertEquals(AiConfig.MAX_REQUESTS_PER_DAY, provider.requests.size)
        clock.instant = clock.instant.plus(Duration.ofDays(1))
        assertTrue(runBlocking { service.proposeNewTask("next day") } is ProposalOutcome.Ready)
    }

    @Test fun providerErrorsAreSurfacedAndNothingIsWritten() {
        listOf(PlanningError.Unauthorized, PlanningError.RateLimited(null), PlanningError.ServerError(503), PlanningError.Timeout, PlanningError.Refused("cyber"), PlanningError.Malformed("x"), PlanningError.Network).forEach { e ->
            provider.fail(e)
            assertEquals(ProposalOutcome.Failed(e), runBlocking { service.proposeNewTask(trainText) })
        }
        assertEquals(0, taskCount())
        assertTrue(alarms.alarms.isEmpty())
    }

    @Test fun deletingAiDataRemovesKeyConsentAndSettingsButNotTasks() {
        approve(propose())
        runBlocking { service.deleteAllAiData() }
        assertFalse(keys.hasKey())
        val s = runBlocking { settings.settings.first() }
        assertFalse(s.aiEnabled)
        assertEquals(null, s.aiConsentAt)
        assertEquals(1, taskCount())
    }

    @Test fun manualCaptureStillWorksWithAiOffOrOffline() {
        online = false
        runBlocking { settings.update { it.copy(aiEnabled = false) } }
        val id = runBlocking { repo.createTask(com.lifeadmin.autopilot.data.repository.NewTask("Plain offline task")) }
        assertEquals("Plain offline task", runBlocking { repo.getTask(id) }!!.title)
    }
}
