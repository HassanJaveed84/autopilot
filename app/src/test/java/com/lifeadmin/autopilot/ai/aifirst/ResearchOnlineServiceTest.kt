package com.lifeadmin.autopilot.ai.aifirst

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.InMemoryUsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.ai.usage.UsageRecord
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.AfThreadRepository
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.reminders.ReminderEngine
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FAKE_KEY
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeCallProvider
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakePlanningProvider
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.LegacySeed
import com.lifeadmin.autopilot.testing.ResearchFixtures
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
import java.util.Locale

/**
 * Research Online orchestration against a scripted provider and real Room storage: the no-search
 * check before any card, clarification before research, nothing paid without [ResearchOnlineService.start],
 * the approval matching the stored turn and offered context, stop and recovery keeping what ran,
 * thread isolation, and task changes only through the review.
 */
@RunWith(AndroidJUnit4::class)
class ResearchOnlineServiceTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 23, 10, 0))
    private val zone = FakeZoneProvider(CHICAGO)
    private val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION))
    private val keys = InMemoryApiKeyStore(FAKE_KEY)
    private val provider = FakeCallProvider()
    private val ledger = InMemoryUsageLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: AfThreadRepository
    private lateinit var legacy: InvestigationRepository
    private lateinit var tasks: TaskRepository
    private lateinit var sets: ProposalSetRepository
    private lateinit var service: ResearchOnlineService
    private lateinit var taskId: String

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds("r")
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        tasks = TaskRepository(db, clock, ids, zone::current, engine, log)
        store = AfThreadRepository(db)
        legacy = InvestigationRepository(db)
        sets = ProposalSetRepository(db)
        val applier = ProposalApplier(db, tasks, sets, clock, ids)
        val planning = AiPlanningService(settings, keys, FakePlanningProvider(), tasks, clock, zone, ids) { true }
        service = ResearchOnlineService(settings, keys, planning, provider, tasks, store, legacy, ledger, sets, applier, clock, zone, ids, scope, { true }, { Locale.US })
        taskId = runBlocking {
            tasks.createTask(NewTask("Renew passport", notes = "Passport no. X1234")).also { tasks.addStep(it, "Gather old passport") }
        }
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun ask(q: String, thread: String? = null, task: String? = taskId) = runBlocking { service.ask(q, thread, task) as AskResult.Ready }
    private fun card(r: AskResult.Ready, scope: ResearchScope = ResearchScope(), cfg: AfConfig = runBlocking { service.defaults(settings.current()) }) = runBlocking {
        service.preview(r.threadId, r.turn.id, service.contextItems(store.get(r.threadId)!!.taskId, scope), cfg)!!
    }
    private fun finished(turnId: String) = runBlocking { withTimeout(10_000) { service.state.filterIsInstance<AfRunState.Finished>().first { it.turnId == turnId } } }
    private fun turn(threadId: String, turnId: String) = runBlocking { store.get(threadId)!!.turns.first { it.id == turnId } }

    @Test fun askingRunsOnlyTheNoSearchCheckAndTheCardSendsNothing() {
        provider.enqueue(ResearchFixtures.clear())
        val r = ask(ResearchFixtures.PASSPORT)
        assertEquals(AfStatus.READY, r.turn.status)
        val check = provider.calls.single()
        assertEquals(0, check.maxSearches)
        assertEquals(SmallCall.MODEL, check.model)
        assertFalse("task details are not part of the check", check.user.contains("Renew passport"))
        repeat(3) { card(r) }
        assertEquals("building the card any number of times sends nothing", 1, provider.calls.size)
        assertTrue(service.state.value is AfRunState.Idle)
        assertEquals(listOf(UsageFeature.OBJECTIVE_PREP), runBlocking { ledger.getAll() }.map { it.feature })
        assertTrue("the question is saved even before research", runBlocking { store.get(r.threadId) }!!.turns.single().clarification!!.checked)
    }

    @Test fun anAmbiguousQuestionIsClarifiedBeforeAnyResearch() {
        provider.enqueue(ResearchFixtures.askWhichCity())
        val r = ask(ResearchFixtures.UNION_STATION, task = null)
        assertEquals(AfStatus.CLARIFYING, r.turn.status)
        assertNull("no card while the question is ambiguous", runBlocking { service.preview(r.threadId, r.turn.id, emptyList(), service.defaults(settings.current())) })
        val forged = AiFirstResearch(provider, ledger, store, clock, SequentialIds("x")).authorize(null, ResearchFixtures.UNION_STATION, null, emptyList(), runBlocking { service.defaults(settings.current()) })
        assertTrue(runBlocking { service.start(r.threadId, r.turn.id, forged) } is StartResult.Rejected)
        runBlocking { service.answerClarification(r.threadId, r.turn.id, "Washington, DC") }
        val auth = card(r)
        assertEquals(AfClarified("Which city's Union Station do you mean?", "Washington, DC"), auth.clarified)
        provider.enqueue(ResearchFixtures.passportAnswer())
        assertTrue(runBlocking { service.start(r.threadId, r.turn.id, auth) } is StartResult.Started)
        finished(r.turn.id)
        assertEquals(2, provider.calls.size)
        assertTrue(provider.calls[1].user.contains("Clarified: Which city's Union Station do you mean? — Washington, DC"))
    }

    @Test fun startRefusesAnApprovalThatNoLongerMatchesTheQuestionOrTheTask() {
        provider.enqueue(ResearchFixtures.clear())
        val r = ask(ResearchFixtures.PASSPORT)
        val auth = card(r, ResearchScope(shareNotes = true))
        assertTrue(runBlocking { service.start(r.threadId, r.turn.id, auth.copy(question = "Something else")) } is StartResult.Rejected)
        val injected = auth.copy(context = auth.context + AfContextLine("other", "Other task", "Secret"))
        assertTrue(runBlocking { service.start(r.threadId, r.turn.id, injected) } is StartResult.Rejected)
        runBlocking { tasks.updateDetails(taskId, "Renew passport", "Changed notes", com.lifeadmin.autopilot.domain.model.Priority.NORMAL) }
        val stale = runBlocking { service.start(r.threadId, r.turn.id, auth) }
        assertTrue("the task changed after the card was built", stale is StartResult.Rejected)
        assertEquals("nothing but the check was sent", 1, provider.calls.size)
    }

    @Test fun onlySwitchedOnContextIsSentAndTheTurnRecordsTheApproval() {
        provider.enqueue(ResearchFixtures.clear(), ResearchFixtures.passportAnswer())
        val r = ask(ResearchFixtures.PASSPORT)
        val auth = card(r, ResearchScope(shareTitle = true, shareNotes = false, shareSteps = false, shareLocale = true))
        assertTrue(runBlocking { service.start(r.threadId, r.turn.id, auth) } is StartResult.Started)
        finished(r.turn.id)
        val sent = provider.calls[1].user
        assertTrue(sent.contains("Task title: Renew passport"))
        assertFalse(sent.contains("X1234"))
        assertFalse(sent.contains("Gather old passport"))
        val t = turn(r.threadId, r.turn.id)
        assertEquals(AfStatus.COMPLETE, t.status)
        assertEquals(sent, t.authorization!!.payload)
        assertEquals(listOf("title", "today"), t.authorization!!.context.map { it.id })
        assertEquals(2, t.sources.size)
    }

    @Test fun stopKeepsThePartialAnswerAndRecordsTheStoppedCall() {
        provider.enqueue(ResearchFixtures.clear(), ResearchFixtures.pausedPartial())
        provider.gate = CompletableDeferred(); provider.gateCall = 3
        val r = ask("Does the AquaPure X9 remove 99.99% of microplastics?")
        assertTrue(runBlocking { service.start(r.threadId, r.turn.id, card(r)) } is StartResult.Started)
        runBlocking { withTimeout(10_000) { while (provider.calls.size < 3) delay(10) } }
        service.stop(r.turn.id)
        assertEquals(AfStatus.STOPPED, finished(r.turn.id).status)
        val t = turn(r.threadId, r.turn.id)
        assertEquals(AfStatus.STOPPED, t.status)
        assertTrue(t.answer.contains("99% reduction"))
        assertTrue(t.costIsFloor)
        assertTrue(runBlocking { ledger.getAll() }.any { it.outcome == CallOutcome.STOPPED })
    }

    @Test fun aRunInterruptedByProcessDeathIsRecoveredWithoutResending() = runBlocking {
        provider.enqueue(ResearchFixtures.clear())
        val r = ask(ResearchFixtures.PASSPORT)
        val auth = card(r)
        // As if the process died mid-call: the turn is RUNNING with a checkpointed partial answer, a call in flight.
        store.saveTurn(r.threadId, turn(r.threadId, r.turn.id).copy(status = AfStatus.RUNNING, authorization = auth, answer = "Partial.", segments = listOf(AfSegment("Partial."))))
        ledger.record(UsageRecord("inflight", UsageFeature.RESEARCH, "claude-sonnet-5-5", clock.now(), r.turn.id, r.threadId))
        LegacySeed.m4Thread(legacy, null, running = true)
        assertEquals(2, service.recoverInterrupted())
        val t = turn(r.threadId, r.turn.id)
        assertEquals(AfStatus.INTERRUPTED, t.status)
        assertEquals("Partial.", t.answer)
        assertTrue(t.costIsFloor)
        assertEquals(CallOutcome.INTERRUPTED, ledger.get("inflight")!!.outcome)
        assertEquals(com.lifeadmin.autopilot.ai.investigation.TurnState.INTERRUPTED, legacy.get(LegacySeed.ID)!!.turns.single().state)
        assertEquals("nothing was re-sent", 1, provider.calls.size)
    }

    @Test fun aFollowUpCarriesThisThreadOnly() {
        provider.enqueue(ResearchFixtures.clear(), ResearchFixtures.multisAnswer(), ResearchFixtures.clear(), ResearchFixtures.passportAnswer())
        val a = ask(ResearchFixtures.MULTIS)
        runBlocking { service.start(a.threadId, a.turn.id, card(a)) }; finished(a.turn.id)
        val b = ask(ResearchFixtures.PASSPORT)
        runBlocking { service.start(b.threadId, b.turn.id, card(b)) }; finished(b.turn.id)
        provider.enqueue(ResearchFixtures.clear(), ResearchFixtures.ironFollowUp())
        val f = ask("Which for someone avoiding iron?", thread = a.threadId)
        assertTrue("the check sees this thread's earlier question", provider.calls[4].user.contains(ResearchFixtures.MULTIS))
        val auth = card(f)
        assertEquals(listOf(a.turn.id), auth.historyTurnIds)
        runBlocking { service.start(f.threadId, f.turn.id, auth) }; finished(f.turn.id)
        val sent = provider.calls[5].user
        assertTrue(sent.contains("One capsule daily [2]") || sent.contains("One capsule daily"))
        assertFalse("another thread's question never travels", sent.contains(ResearchFixtures.PASSPORT))
        assertEquals(2, runBlocking { store.get(a.threadId) }!!.turns.size)
    }

    @Test fun taskSuggestionsAreReviewedNeverApplied() {
        provider.enqueue(ResearchFixtures.clear(), ResearchFixtures.passportAnswer(), ResearchFixtures.suggestions())
        val r = ask(ResearchFixtures.PASSPORT)
        runBlocking { service.start(r.threadId, r.turn.id, card(r)) }; finished(r.turn.id)
        assertTrue(runBlocking { service.suggestionPayload(r.threadId, r.turn.id) }!!.contains("Task: Renew passport"))
        assertEquals("previewing the suggestion request sends nothing", 2, provider.calls.size)
        val set = (runBlocking { service.suggest(r.threadId, r.turn.id) } as SuggestResult.Ready).set
        assertEquals(2, set.pending.size)
        assertEquals(set, runBlocking { sets.get(set.id) })
        val again = runBlocking { service.suggest(r.threadId, r.turn.id) } as SuggestResult.Ready
        assertEquals("asking again reopens the same set, no new call", 3, provider.calls.size)
        assertEquals(set.id, again.set.id)
        val steps = runBlocking { tasks.observeDetails(taskId).first()!!.steps.map { it.title } }
        assertEquals("the task is untouched until items are applied in the review", listOf("Gather old passport"), steps)
    }

    @Test fun legacyResearchIsReadOnlyAndUnknownToTheNewStore() = runBlocking {
        LegacySeed.m4Thread(legacy, taskId)
        assertNull(store.get(LegacySeed.ID))
        assertTrue(store.isLegacy(LegacySeed.ID))
        val rejected = service.ask("Follow-up?", LegacySeed.ID, null)
        assertTrue(rejected is AskResult.Rejected)
        assertTrue(provider.calls.isEmpty())
    }

    @Test fun nothingStartsWithoutTheGate() {
        provider.enqueue(ResearchFixtures.clear())
        val r = ask(ResearchFixtures.PASSPORT)
        val auth = card(r)
        runBlocking { settings.update { it.copy(researchExplainerVersion = 0) } }
        assertEquals(StartResult.Rejected(ResearchError.NoResearchConsent), runBlocking { service.start(r.threadId, r.turn.id, auth) })
        assertEquals(AfStatus.READY, turn(r.threadId, r.turn.id).status)
        assertEquals(1, provider.calls.size)
    }
}
