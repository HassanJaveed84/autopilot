package com.lifeadmin.autopilot.ai.research

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.BriefStatus
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ResearchRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.reminders.ReminderEngine
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FAKE_KEY
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakePlanningProvider
import com.lifeadmin.autopilot.testing.FakeResearchProvider
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import java.time.Duration
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ResearchServiceTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 20, 10, 0))
    private val zone = FakeZoneProvider(CHICAGO)
    private val settings = InMemorySettingsRepository(
        AppSettings(aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION),
    )
    private val keys = InMemoryApiKeyStore(FAKE_KEY)
    private val provider = FakeResearchProvider()
    private var online: Boolean? = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: TaskRepository
    private lateinit var briefs: ResearchRepository
    private lateinit var service: ResearchService
    private lateinit var taskId: String

    private val rail = RawCitation("https://www.northlinerail.example/tickets", "Tickets", "released 12 weeks ahead")
    private val goodResponse = ResearchResponse.Success(
        segments = listOf(
            RawSegment("## QUICK ANSWER\n", emptyList()),
            RawSegment("Advance tickets go on sale 12 weeks ahead.", listOf(rail)),
            RawSegment("\n\n## NEXT MOVES\n- STEP: Check the release date — ", emptyList()),
            RawSegment("12 weeks out.", listOf(rail)),
            RawSegment("\n\n## FINDINGS\n- ", emptyList()),
            RawSegment("Fares are released 12 weeks ahead.", listOf(rail)),
            RawSegment("\n\n## UNCONFIRMED\n- none", emptyList()),
        ),
        searches = listOf(RawSearch("northline advance tickets", listOf(RawSearchResult(rail.url, "Tickets", "September 2026")))),
        usage = ResearchUsage(1000, 300, 1, "claude-haiku-5-5", 3000),
        truncated = false,
    )

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds("r")
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        repo = TaskRepository(db, clock, ids, zone::current, engine, log)
        briefs = ResearchRepository(db, clock, ids)
        val planning = AiPlanningService(settings, keys, FakePlanningProvider(), repo, clock, zone, ids) { online }
        service = ResearchService(settings, keys, planning, provider, repo, briefs, clock, zone, ids, scope, { online }, { Locale.UK })
        taskId = runBlocking { repo.createTask(NewTask("Book train ride", notes = "Window seat")) }
        runBlocking { repo.addStep(taskId, "Find dates") }
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun start(question: String = "When do advance tickets go on sale?", scope: ResearchScope = ResearchScope()) =
        runBlocking { service.start(taskId, question, setOf(ResearchFocus.DEADLINES), mapOf(DetailField.OPERATOR to "Northline Rail"), scope) }

    private fun awaitFinished(): ResearchRunState.Finished = runBlocking {
        withTimeout(5_000) { service.state.filterIsInstance<ResearchRunState.Finished>().first() }
    }

    @Test fun gateReflectsEveryPrecondition() = runBlocking {
        assertNull(service.gate().blocker)
        assertEquals(ResearchConfig.MAX_REQUESTS_PER_DAY, service.gate().remaining)

        settings.update { it.copy(researchExplainerVersion = 0) }
        assertEquals(ResearchError.NoResearchConsent, service.gate().blocker)
        service.acceptExplainer()
        assertNull(service.gate().blocker)

        online = false
        assertEquals(ResearchError.Offline, service.gate().blocker)
        online = null
        assertNull("unknown connectivity attempts the request", service.gate().blocker)
        online = true

        keys.delete()
        assertEquals(ResearchError.NoApiKey, service.gate().blocker)
        keys.save(FAKE_KEY)
        settings.update { it.copy(aiConsentAt = null) }
        assertEquals(ResearchError.NoConsent, service.gate().blocker)
        settings.update { it.copy(aiEnabled = false) }
        assertEquals(ResearchError.Disabled, service.gate().blocker)
    }

    @Test fun rejectedStartsSendNothing() {
        assertEquals(StartResult.Rejected(ResearchError.EmptyQuestion), start("   "))
        assertEquals(StartResult.Rejected(ResearchError.QuestionTooLong), start("x".repeat(ResearchConfig.MAX_QUESTION_CHARS + 1)))
        online = false
        assertEquals(StartResult.Rejected(ResearchError.Offline), start())
        online = true
        runBlocking { settings.update { it.copy(aiEnabled = false) } }
        assertEquals(StartResult.Rejected(ResearchError.Disabled), start())
        assertTrue(provider.requests.isEmpty())
        assertEquals(0, runBlocking { service.requestsToday() })
        assertEquals(ResearchRunState.Idle, service.state.value)
    }

    @Test fun successfulRunSavesABriefAndCountsOneRequest() {
        provider.next = { goodResponse }
        val started = start() as StartResult.Started
        val finished = awaitFinished()
        assertEquals(started.runId, finished.runId)
        val saved = finished.outcome as ResearchOutcome.Saved
        val brief = runBlocking { briefs.get(saved.briefId) }!!
        assertEquals(taskId, brief.taskId)
        assertEquals(BriefStatus.VERIFIED, brief.status)
        assertEquals("When do advance tickets go on sale?", brief.question)
        assertEquals(1, brief.searchCount)
        assertEquals(1, brief.sourceCount)
        assertEquals(1, brief.content.moves.size)
        assertEquals(listOf("northline advance tickets"), brief.content.queries)
        assertEquals(listOf("Task title", "Your question and details", "Notes", "Date and language"), brief.content.shared)
        assertEquals(1, runBlocking { service.requestsToday() })
        assertEquals("planning counter is separate", 0, runBlocking { settings.current().aiUsageCount })
        // The request carried exactly the chosen scope.
        val req = provider.requests.single()
        assertEquals("Book train ride", req.title)
        assertEquals("Window seat", req.notes)
        assertNull("steps off by default", req.steps)
        assertEquals("Tue 20 Oct 2026 · English (United Kingdom)", req.localeLine)
        assertEquals(mapOf(DetailField.OPERATOR to "Northline Rail"), req.details)
        assertEquals(ResearchConfig.MAX_SEARCHES_PER_REQUEST, req.maxSearches)
        assertEquals(listOf(FAKE_KEY), provider.keysSeen)
        assertEquals(listOf("claude-haiku-5-5"), provider.modelsSeen)
        // Task itself is untouched.
        assertEquals(listOf("Find dates"), runBlocking { db.stepDao().getForTask(taskId).map { it.title } })
        service.dismiss(started.runId)
        assertEquals(ResearchRunState.Idle, service.state.value)
    }

    @Test fun scopeTogglesChangeWhatIsSent() {
        provider.next = { goodResponse }
        start(scope = ResearchScope(shareNotes = false, shareSteps = true, shareLocale = false))
        awaitFinished()
        val req = provider.requests.single()
        assertNull(req.notes)
        assertEquals(listOf("Find dates"), req.steps)
        assertNull(req.localeLine)
        assertEquals(listOf("Task title", "Your question and details", "Steps (1)"), req.sharedSummary())
    }

    @Test fun providerFailureIsSurfacedAndNothingSaved() {
        provider.next = { ResearchResponse.Failure(ResearchError.WebSearchNotEnabled, 0) }
        start()
        val finished = awaitFinished()
        assertEquals(ResearchOutcome.Failed(ResearchError.WebSearchNotEnabled), finished.outcome)
        assertTrue(runBlocking { briefs.getForTask(taskId) }.isEmpty())
        assertEquals("the attempt still counts against today's cap", 1, runBlocking { service.requestsToday() })
    }

    @Test fun unverifiedResponseIsSavedAsUnverified() {
        provider.next = { goodResponse.copy(segments = goodResponse.segments.map { it.copy(citations = emptyList()) }) }
        start()
        val saved = awaitFinished().outcome as ResearchOutcome.Saved
        val brief = runBlocking { briefs.get(saved.briefId) }!!
        assertEquals(BriefStatus.UNVERIFIED, brief.status)
        assertFalse(brief.content.verified)
        assertTrue(brief.content.findings.all { it is Finding.Suggestion })
    }

    @Test fun stopCancelsTheRunAndReportsSearchesSeen() {
        provider.gate = CompletableDeferred()
        provider.progress = listOf(ResearchProgress.Searching(2))
        provider.next = { goodResponse }
        val started = start() as StartResult.Started
        runBlocking { withTimeout(5_000) { service.state.first { it is ResearchRunState.Running && it.searchesSeen == 2 } } }
        assertEquals(StartResult.Rejected(ResearchError.Busy), start())
        service.stop(started.runId)
        val finished = awaitFinished()
        assertEquals(ResearchOutcome.Failed(ResearchError.Stopped(2)), finished.outcome)
        assertTrue(runBlocking { briefs.getForTask(taskId) }.isEmpty())
        provider.gate!!.complete(Unit)
    }

    @Test fun dailyCapIsEnforcedAndResetsNextDay() {
        provider.next = { goodResponse }
        repeat(ResearchConfig.MAX_REQUESTS_PER_DAY) { i ->
            val r = start("q$i")
            assertTrue("run $i should start: $r", r is StartResult.Started)
            service.dismiss(awaitFinished().runId)
        }
        assertEquals(StartResult.Rejected(ResearchError.DailyLimit), start("one more"))
        assertEquals(0, runBlocking { service.gate().remaining })
        assertEquals(ResearchConfig.MAX_REQUESTS_PER_DAY, provider.requests.size)
        clock.instant = clock.instant.plus(Duration.ofDays(1))
        assertTrue(start("tomorrow") is StartResult.Started)
        service.dismiss(awaitFinished().runId)
        assertEquals(1, runBlocking { service.requestsToday() })
    }

    @Test fun deleteAllResearchRemovesBriefsAndExplainerOnly() {
        provider.next = { goodResponse }
        start(); awaitFinished()
        assertEquals(1, runBlocking { briefs.getAll() }.size)
        runBlocking { service.deleteAllResearch() }
        assertTrue(runBlocking { briefs.getAll() }.isEmpty())
        assertEquals(ResearchError.NoResearchConsent, runBlocking { service.gate().blocker })
        assertTrue("the key and AI planning are untouched", keys.hasKey())
        assertTrue(runBlocking { settings.current().aiEnabled })
        assertEquals("Book train ride", runBlocking { repo.getTask(taskId) }!!.title)
    }

    @Test fun deletingTheTaskCascadesToItsBriefs() {
        provider.next = { goodResponse }
        start(); awaitFinished()
        runBlocking { repo.delete(taskId) }
        assertTrue(runBlocking { briefs.getAll() }.isEmpty())
    }

    // ---------------------------------------------------------------- finding D: delete vs running request

    private fun startAndWaitUntilSearching(): StartResult.Started {
        provider.gate = CompletableDeferred()
        provider.progress = listOf(ResearchProgress.Searching(1))
        provider.next = { goodResponse }
        val started = start() as StartResult.Started
        runBlocking { withTimeout(5_000) { service.state.first { it is ResearchRunState.Running && it.searchesSeen == 1 } } }
        return started
    }

    @Test fun deleteAllWhileSearchingStopsTheRunAndNothingComesBack() {
        startAndWaitUntilSearching()
        runBlocking { service.deleteAllResearch() }
        val finished = awaitFinished()
        assertEquals(ResearchOutcome.Failed(ResearchError.Stopped(1)), finished.outcome)
        assertTrue(runBlocking { briefs.getAll() }.isEmpty())
        assertEquals(ResearchError.NoResearchConsent, runBlocking { service.gate().blocker })
        // The provider finishing late must not repopulate the deleted history.
        provider.gate!!.complete(Unit)
        Thread.sleep(200)
        assertTrue(runBlocking { briefs.getAll() }.isEmpty())
        assertEquals("the attempt still counts; searches may have been billed", 1, runBlocking { service.requestsToday() })
        assertEquals(1, provider.requests.size)
    }

    @Test fun deleteForAnotherTaskLeavesTheRunAlone() {
        val other = runBlocking { repo.createTask(NewTask("Other task")) }
        startAndWaitUntilSearching()
        runBlocking { service.deleteResearchForTask(other) }
        assertTrue(service.state.value is ResearchRunState.Running)
        provider.gate!!.complete(Unit)
        assertTrue(awaitFinished().outcome is ResearchOutcome.Saved)
        assertEquals(1, runBlocking { briefs.getForTask(taskId) }.size)
    }

    @Test fun deleteForThisTaskWhileRunningCancelsAndSavesNothing() {
        startAndWaitUntilSearching()
        runBlocking { service.deleteResearchForTask(taskId) }
        assertTrue(awaitFinished().outcome is ResearchOutcome.Failed)
        provider.gate!!.complete(Unit)
        Thread.sleep(200)
        assertTrue(runBlocking { briefs.getForTask(taskId) }.isEmpty())
        assertTrue("explainer acceptance is kept for a per-task delete", runBlocking { service.gate().blocker } == null)
    }

    @Test fun deleteDuringTheSaveItselfWinsOverTheLateResult() {
        // A repository whose save blocks lets the test delete while the answer is being written.
        val saveGate = CompletableDeferred<Unit>()
        val saving = CompletableDeferred<Unit>()
        val slow = object : ResearchRepository(db, clock, SequentialIds("s")) {
            override suspend fun save(taskId: String, question: String, content: BriefContent): String {
                saving.complete(Unit)
                saveGate.await()
                return super.save(taskId, question, content)
            }
        }
        val slowService = ResearchService(settings, keys, AiPlanningService(settings, keys, FakePlanningProvider(), repo, clock, zone, SequentialIds("p")) { online }, provider, repo, slow, clock, zone, SequentialIds("q"), scope, { online }, { Locale.UK })
        provider.next = { goodResponse }
        runBlocking { slowService.start(taskId, "q", emptySet(), emptyMap(), ResearchScope()) as StartResult.Started }
        runBlocking { withTimeout(5_000) { saving.await() } }
        // Delete while the save is in progress: the cancellable part of the save is aborted (or, for a
        // write already inside Room, awaited) and the delete runs after it — either way nothing survives.
        val deletion = scope.launch { slowService.deleteAllResearch() }
        runBlocking { withTimeout(5_000) { deletion.join() } }
        saveGate.complete(Unit)
        Thread.sleep(200)
        assertTrue("nothing from the late save survives", runBlocking { slow.getAll() }.isEmpty())
        val st = runBlocking { withTimeout(5_000) { slowService.state.filterIsInstance<ResearchRunState.Finished>().first() } }
        assertTrue(st.outcome.toString(), st.outcome is ResearchOutcome.Failed)
    }
}
