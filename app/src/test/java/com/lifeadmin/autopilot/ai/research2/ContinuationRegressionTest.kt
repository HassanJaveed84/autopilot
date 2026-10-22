package com.lifeadmin.autopilot.ai.research2

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.InMemoryUsageLedger
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
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
import com.lifeadmin.autopilot.testing.Research2Fixtures
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Real-device bug (Galaxy S24 Ultra): continuing a saved Deep fragrance session with the note
 * "Any chanel options" (Quick · Haiku 5.5 · 3 searches) came back after ~12 s as
 * "The answer couldn't be used / no usable blocks / MALFORMED RESPONSE".
 *
 * Root cause: the orchestrator treated every reply without a parsed ANSWER/REQ/CAND/PICK/CLAIM/STEP
 * block as the same generic malformed response — a reply cut off by `max_tokens` before any text
 * (Haiku 5.5 thinks by default, and thinking counts against Quick's 1,500 output tokens), a tool-only
 * `pause_turn` with no budget left, an empty reply, an UNRESOLVED-only reply, free-form text and text
 * whose headings deviated from the exact id format all landed there, with no diagnostics.
 *
 * Every test here seeds the saved session first, then continues it the way the person did.
 */
@RunWith(AndroidJUnit4::class)
class ContinuationRegressionTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 20, 10, 0))
    private val zone = FakeZoneProvider(CHICAGO)
    private val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION))
    private val keys = InMemoryApiKeyStore(FAKE_KEY)
    private val provider = FakeCallProvider()
    private val store = InMemorySessionStore()
    private val ledger = InMemoryUsageLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: TaskRepository
    private lateinit var service: DeepResearchService
    private lateinit var taskId: String

    private val note = "Any chanel options"
    private val chanelPage = RawCitation("https://www.bigretailer.example/bleu-de-chanel-edt-100", "Bleu de Chanel EDT 100 ml | Big Retailer", "Bleu de Chanel Eau de Toilette 3.4 oz $150.00")
    private val chanelSearch = RawSearch("Chanel men's fragrance under 150 USD", listOf(RawSearchResult(chanelPage.url, chanelPage.title!!, "October 2026")))

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds("d")
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        repo = TaskRepository(db, clock, ids, zone::current, engine, log)
        val planning = AiPlanningService(settings, keys, FakePlanningProvider(), repo, clock, zone, ids) { true }
        service = DeepResearchService(settings, keys, planning, provider, repo, store, ledger, clock, zone, ids, scope, { true }, { Locale.US })
        taskId = runBlocking { repo.createTask(NewTask("Gift", notes = "Birthday")) }
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun awaitFinished(): DeepRunState.Finished = runBlocking { withTimeout(10_000) { service.state.filterIsInstance<DeepRunState.Finished>().first() } }

    /** The saved Deep fragrance research the person continued. */
    private fun seedFragrance(): ResearchSession {
        provider.enqueue(Research2Fixtures.fragranceGather(), Research2Fixtures.fragranceFillGaps())
        val r = service.extract(Research2Fixtures.FRAGRANCE_REQUEST)
        runBlocking { service.start(taskId, SessionDraft(Research2Fixtures.FRAGRANCE_REQUEST, r.template, r.requirements, ResearchModes.config(ResearchMode.THOROUGH))) }
        val f = awaitFinished()
        service.dismiss(f.runId)
        val saved = runBlocking { store.get((f.result as RunResult.Saved).sessionId)!! }
        assertEquals(SessionOutcome.COMPLETE, saved.outcome)
        assertTrue(saved.content.answer.isNotEmpty())
        provider.calls.clear()
        clock.advance(java.time.Duration.ofMinutes(5))
        return saved
    }

    private fun continueQuick(session: ResearchSession, searches: Int = 3): DeepRunState.Finished {
        val started = runBlocking { service.continueSession(session.id, ResearchModes.config(ResearchMode.QUICK, searches = searches), emptyList(), note) }
        assertTrue(started.toString(), started is StartResult2.Started)
        return awaitFinished()
    }

    private fun stored(id: String) = runBlocking { store.get(id)!! }

    /** The findings the person already had: untouched by any failed or empty continuation. */
    private fun assertOriginalFindingsUnchanged(before: ResearchSession, after: ResearchSession) {
        assertEquals(before.content.answer, after.content.answer)
        assertEquals(before.content.title, after.content.title)
        assertEquals(before.content.body, after.content.body)
        assertEquals(before.content.claims, after.content.claims)
        assertEquals(before.content.sources, after.content.sources)
        assertEquals(before.content.requirements.map { it.id }, after.content.requirements.filter { it.origin == RequirementOrigin.EXTRACTED }.map { it.id })
        before.content.requirements.forEach { r -> assertEquals(r.id, before.content.coverageOf(r.id), after.content.coverageOf(r.id)) }
    }

    private fun failed(f: DeepRunState.Finished): ResearchError = (f.result as RunResult.Failed).error

    // ------------------------------------------------------------------ the real-device shape

    @Test fun replyCutOffBeforeAnyTextIsReportedAsTruncatedNotMalformedAndTheOriginalIsUnchanged() {
        val before = seedFragrance()
        // Haiku thought and searched once, then hit the 1,500-token cap before writing a single text block.
        provider.enqueue(CallResult.Done(emptyList(), listOf(chanelSearch), MeasuredUsage(9_800, 1_500, 0, 0, 0, 1), CallStop.MAX_TOKENS, 12_100, "msg_cut"))
        val f = continueQuick(before)
        val e = failed(f)
        assertFalse("the generic malformed error is exactly the bug: $e", e is ResearchError.Malformed)
        assertTrue("the message says the previous research is unchanged: ${e.message}", e.message.contains("No new findings were saved. Your previous research is unchanged."))
        assertTrue("the message says what actually happened: ${e.message}", e.message.contains("output limit", ignoreCase = true))
        val after = stored(before.id)
        assertOriginalFindingsUnchanged(before, after)
        // The attempt is on the record with its cost; the findings are not.
        assertEquals(2, after.content.runs.size)
        assertEquals(RunKind.CONTINUATION, after.content.latestRun!!.kind)
        assertEquals(SessionOutcome.FAILED, after.content.latestRun!!.outcome)
        assertEquals(1_500L, after.content.latestRun!!.usage.outputTokens)
        val recs = runBlocking { ledger.forSession(before.id) }
        assertEquals(3, recs.size)
        assertEquals(UsageFeature.RESEARCH_CONTINUATION, recs.last().feature)
        assertEquals(CallOutcome.TRUNCATED, recs.last().outcome)
        assertEquals(1, recs.last().usage!!.webSearches)
        // The record explains the reply without quoting it: stop reason, block kinds, text length, parsed blocks, tokens.
        assertTrue(e.toString(), e is ResearchError.NoFindings && (e as ResearchError.NoFindings).why == CallClass.TRUNCATED_BEFORE_TEXT && e.continuation)
        assertEquals("NO FINDINGS · TRUNCATED_BEFORE_TEXT", e.technical)
        val d = (f.result as RunResult.Failed).diagnostics.single()
        assertEquals(CallClass.TRUNCATED_BEFORE_TEXT, d.classification)
        assertEquals("max_tokens", d.stop); assertEquals(1_500L, d.outputTokens); assertEquals(0, d.textChars); assertEquals(emptyMap<String, Int>(), d.parsed)
        assertEquals(1, d.blocks["server_tool_use"]); assertEquals(1, d.webSearches); assertEquals(3, d.searchesAllowed); assertEquals(StageKind.GATHER, d.stage)
        assertEquals("the same record is kept on the session's run log", listOf(d), after.content.latestRun!!.stages.single().callLog)
    }

    @Test fun diagnosticsNeverCarryTheKeyThePromptTheTaskOrTheAnswer() {
        val before = seedFragrance()
        provider.enqueue(CallResult.Done(listOf(RawSegment("Bleu de Chanel is \$150.", listOf(chanelPage))), listOf(chanelSearch), MeasuredUsage(9_800, 1_500, 0, 0, 0, 1), CallStop.MAX_TOKENS, 12_100, "msg_cut2"))
        val f = continueQuick(before)
        val after = stored(before.id)
        val run = after.content.latestRun!!
        val text = CallDiagnostic.summary(run.id, run.kind, run.mode, run.searchBudget, run.stages.flatMap { it.callLog })
        for (secret in listOf(FAKE_KEY, note, "chanel", "Chanel", chanelPage.url, "bigretailer", Research2Fixtures.FRAGRANCE_REQUEST, "perfume", "Gift", "Birthday", "Bleu")) {
            assertFalse("the shareable summary must not contain “$secret”:\n$text", text.contains(secret))
        }
        assertTrue(text, text.contains("claude-haiku-5-5") && text.contains("stop=max_tokens") && text.contains("server_tool_use 1") && text.contains("text 23 chars (1 cited)") && text.contains("out 1500 of max"))
        assertTrue("a cut-off reply that still carried text is kept as text, not reported as nothing", f.result is RunResult.Saved)
        assertEquals(CallClass.FREE_FORM, run.stages.single().callLog.single().classification)
    }

    @Test fun emptyReplyIsReportedAsEmptyAndTheOriginalIsUnchanged() {
        val before = seedFragrance()
        provider.enqueue(CallResult.Done(emptyList(), emptyList(), MeasuredUsage(9_000, 0, 0, 0, 0, 0), CallStop.END, 900, "msg_empty"))
        val e = failed(continueQuick(before))
        assertFalse(e.toString(), e is ResearchError.Malformed)
        assertTrue(e.message, e.message.contains("empty", ignoreCase = true) && e.message.contains("Your previous research is unchanged."))
        assertOriginalFindingsUnchanged(before, stored(before.id))
        assertEquals(CallClass.EMPTY, (e as ResearchError.NoFindings).why)
        assertEquals(CallClass.EMPTY, stored(before.id).content.latestRun!!.stages.single().callLog.single().classification)
    }

    @Test fun toolOnlyPauseTurnWithNoBudgetLeftIsNotContinuedAndIsReportedAsPausedNotMalformed() {
        val before = seedFragrance()
        // All 3 authorized searches ran inside the paused turn; nothing was written yet.
        provider.enqueue(CallResult.Done(emptyList(), listOf(chanelSearch, chanelSearch.copy(query = "Bleu de Chanel EDT price"), chanelSearch.copy(query = "Chanel Allure Homme Sport price")), MeasuredUsage(9_800, 300, 0, 0, 0, 3), CallStop.PAUSE_TURN, 9_000, "msg_paused", paused = "prior"))
        val f = continueQuick(before)
        assertEquals("the paused turn is never continued past the authorized searches", 1, provider.calls.size)
        val e = failed(f)
        assertFalse(e.toString(), e is ResearchError.Malformed)
        assertTrue(e.message, e.message.contains("paused", ignoreCase = true) && e.message.contains("Your previous research is unchanged."))
        val after = stored(before.id)
        assertOriginalFindingsUnchanged(before, after)
        assertEquals(SessionOutcome.INCOMPLETE_BUDGET, after.content.latestRun!!.outcome)
        assertEquals(3, after.content.latestRun!!.usage.webSearches)
        assertEquals(CallClass.PAUSED_BEFORE_TEXT, (e as ResearchError.NoFindings).why)
        assertTrue(e.message, e.message.contains("3 of 3 searches") && e.message.contains("no searches left"))
        assertTrue(after.content.latestRun!!.stages.single().note!!.contains("not continued"))
    }

    @Test fun toolOnlyPauseTurnWithBudgetLeftIsContinuedWithinTheBudgetAndTheAnswerIsSaved() {
        val before = seedFragrance()
        provider.enqueue(CallResult.Done(emptyList(), listOf(chanelSearch), MeasuredUsage(9_800, 200, 0, 0, 0, 1), CallStop.PAUSE_TURN, 5_000, "msg_paused2", paused = "prior"))
        provider.enqueue(chanelAnswer(searches = 1, messageId = "msg_after_pause"))
        val f = continueQuick(before)
        assertEquals(2, provider.calls.size)
        assertEquals("prior", provider.calls[1].prior)
        assertEquals("the continuation re-offers what is left of the 3", 2, provider.calls[1].maxSearches)
        assertTrue(f.result.toString(), f.result is RunResult.Saved)
        val after = stored(before.id)
        assertEquals("the original recommendation is kept", before.content.answer, after.content.answer)
        assertTrue(after.content.body.candidates.any { it.name == "Bleu de Chanel" })
        assertEquals(2, after.content.searchesTotal - before.content.searchesTotal)
        val log = after.content.latestRun!!.stages.single().callLog
        assertEquals(listOf(CallClass.PAUSED_BEFORE_TEXT, CallClass.STRUCTURED), log.map { it.classification })
        assertEquals(listOf("pause_turn", "end_turn"), log.map { it.stop })
        val fu = after.content.followUps.single()
        assertEquals(note, fu.question); assertEquals(RequirementStatus.ANSWERED, fu.status); assertTrue(fu.answer.any { it.refs.isNotEmpty() })
    }

    // ------------------------------------------------------------------ honest partial content is kept

    @Test fun unresolvedOnlyReplyIsSavedAsAnHonestNotFoundFollowUp() {
        val before = seedFragrance()
        provider.enqueue(CallResult.Done(listOf(RawSegment("## UNRESOLVED\n- Chanel under \$150 — the Chanel men's lines found list at \$160 and above in the US; no page confirmed a bottle at or under \$150\n", emptyList())), listOf(chanelSearch), MeasuredUsage(9_800, 120, 0, 0, 0, 1), CallStop.END, 8_000, "msg_unres"))
        val f = continueQuick(before)
        assertTrue("an honest 'nothing verifiable' is a result, not a malformed reply: ${f.result}", f.result is RunResult.Saved)
        val after = stored(before.id)
        assertEquals(before.content.answer, after.content.answer)
        assertTrue(after.content.unresolved.any { it.topic.contains("Chanel") })
        assertEquals(before.content.body.candidates, after.content.body.candidates)
        assertTrue("the follow-up stays visibly open", after.outcome != SessionOutcome.COMPLETE)
        val fu = after.content.followUps.single()
        assertEquals(note, fu.question); assertEquals(RequirementStatus.NOT_FOUND, fu.status); assertTrue(fu.answer.isEmpty())
        assertTrue(fu.summary, fu.summary.contains("Chanel under \$150") && fu.summary.contains("no page confirmed"))
        assertEquals(RequirementStatus.NOT_FOUND, after.content.coverageOf("r-f1").status)
        assertEquals(CallClass.UNRESOLVED_ONLY, after.content.latestRun!!.stages.single().callLog.single().classification)
        assertTrue("an honest open point is offered for another continuation", SessionAssembler.gaps(after.content).any { it.id == "r-f1" })
    }

    @Test fun freeFormCitedTextIsKeptAsTheFollowUpAnswerAndNeverReplacesTheOriginalAnswer() {
        val before = seedFragrance()
        provider.enqueue(CallResult.Done(listOf(RawSegment("Bleu de Chanel Eau de Toilette 100 ml is listed at \$150 at Big Retailer, exactly at the budget.", listOf(chanelPage))), listOf(chanelSearch), MeasuredUsage(9_800, 90, 0, 0, 0, 1), CallStop.END, 7_000, "msg_free"))
        val f = continueQuick(before)
        assertTrue(f.result.toString(), f.result is RunResult.Saved)
        val after = stored(before.id)
        assertEquals("free-form text never overwrites the saved recommendation", before.content.answer, after.content.answer)
        assertTrue("the new page is a source now", after.content.sources.any { it.url == chanelPage.url && it.citedCount > 0 })
        val fu = after.content.followUps.single()
        assertEquals(note, fu.question)
        assertTrue(fu.answer.joinToString { it.text }, fu.answer.any { it.text.contains("Bleu de Chanel") && it.refs.isNotEmpty() })
        assertEquals("free text is shown but not counted as checked", RequirementStatus.NEEDS_CHECK, fu.status)
        assertEquals(CallClass.FREE_FORM, after.content.latestRun!!.stages.single().callLog.single().classification)
    }

    @Test fun textWhoseHeadingsDoNotCarryIdsIsKeptAsTextNotDiscarded() {
        val before = seedFragrance()
        provider.enqueue(
            CallResult.Done(
                listOf(
                    RawSegment("## REQ follow-up · status=answered\n", emptyList()),
                    RawSegment("Bleu de Chanel EDT 100 ml is \$150 at Big Retailer.", listOf(chanelPage)),
                    RawSegment("\n## CAND Bleu de Chanel (EDT 100 ml)\nFresh, woody; the safest Chanel for an unknown taste.\n", emptyList()),
                ),
                listOf(chanelSearch), MeasuredUsage(9_800, 140, 0, 0, 0, 1), CallStop.END, 7_000, "msg_misformat",
            ),
        )
        val f = continueQuick(before)
        assertTrue("cited text with unusable headings is kept, not thrown away: ${f.result}", f.result is RunResult.Saved)
        val after = stored(before.id)
        assertEquals(before.content.answer, after.content.answer)
        val fu = after.content.followUps.single()
        assertTrue(fu.answer.joinToString { it.text }, fu.answer.any { it.text.contains("\$150") && it.refs.isNotEmpty() } && fu.answer.none { it.text.contains("## ") })
        assertEquals(CallClass.TEXT_UNPARSED, after.content.latestRun!!.stages.single().callLog.single().classification)
    }

    // ------------------------------------------------------------------ a failed request

    @Test fun aFailedContinuationRequestLeavesTheOriginalIntactAndRecordsTheAttempt() {
        val before = seedFragrance()
        provider.enqueue(CallResult.Failed(ResearchError.ServerError(500), searchesSeen = 1, usage = null, elapsedMs = 3_000))
        val f = continueQuick(before)
        assertEquals(ResearchError.ServerError(500), failed(f))
        val after = stored(before.id)
        assertOriginalFindingsUnchanged(before, after)
        assertEquals("the attempt is recorded on the session", 2, after.content.runs.size)
        assertEquals(SessionOutcome.FAILED, after.content.latestRun!!.outcome)
        val recs = runBlocking { ledger.forSession(before.id) }
        assertEquals(CallOutcome.FAILED_UNKNOWN, recs.last().outcome)
        assertEquals("the search seen before the failure is a cost floor", 1, recs.last().usage!!.webSearches)
        val d = after.content.latestRun!!.stages.single().callLog.single()
        assertEquals(CallClass.HTTP_FAILED, d.classification); assertEquals("SERVER ERROR (500)", d.error); assertEquals("failed", d.stop)
        assertEquals(listOf(d), (f.result as RunResult.Failed).diagnostics)
    }

    // ------------------------------------------------------------------ the follow-up is the objective

    @Test fun theFollowUpQuestionIsThePrimaryObjectiveOfTheContinuation() {
        val before = seedFragrance()
        provider.enqueue(chanelAnswer(searches = 2, messageId = "msg_chanel"))
        val f = continueQuick(before)
        assertTrue(f.result.toString(), f.result is RunResult.Saved)
        val prompt = provider.calls.single().user
        assertTrue("the exact question, verbatim", prompt.contains("\"$note\""))
        assertTrue("named as the objective", prompt.contains("main objective"))
        assertTrue("the original constraints travel with it", prompt.contains("\$150") && prompt.contains("USA"))
        assertTrue("existing candidates are named so new ones can be compared and merged", prompt.contains("Fresh Aqua") && prompt.contains("c1"))
        assertTrue("the request is still there verbatim", prompt.contains(Research2Fixtures.FRAGRANCE_REQUEST))
        val sourceLines = prompt.lines().count { it.startsWith("- s") }
        assertTrue("sources are referred to compactly, not re-sent wholesale: $sourceLines lines", sourceLines in 1..12)
        assertTrue(prompt.contains("Searches allowed on this call: 3"))
        val after = stored(before.id)
        assertEquals("the original recommendation stays", before.content.answer, after.content.answer)
        assertEquals(before.content.title, after.content.title)
        assertTrue(after.content.body.candidates.any { it.name == "Bleu de Chanel" })
        assertEquals("new candidates continue the numbering next to the old ones", listOf("c1", "c2", "c3", "c5"), after.content.body.candidates.map { it.id })
        assertEquals(2, after.content.runs.size)
        assertTrue("the follow-up is tracked as a requirement", prompt.contains("REQ r-f1") && after.content.requirements.any { it.id == "r-f1" && it.followUp && it.label == note })
        assertEquals(RequirementStatus.ANSWERED, after.content.coverageOf("r-f1").status)
        val fu = after.content.followUps.single()
        assertEquals(note, fu.question); assertEquals(RequirementStatus.ANSWERED, fu.status); assertEquals("r-f1", fu.requirementId)
        assertTrue(fu.answer.joinToString { it.text }, fu.answer.any { it.text.contains("Bleu de Chanel Eau de Toilette") && it.refs.isNotEmpty() })
        assertTrue("a follow-up never gets a generated proposal of its own", after.content.proposals.none { it.requirementId == "r-f1" })
        assertEquals(SessionOutcome.COMPLETE, after.outcome)
        assertEquals(CallClass.STRUCTURED, after.content.latestRun!!.stages.single().callLog.single().classification)
    }

    // ------------------------------------------------------------------ the same shapes on a first run

    @Test fun aFirstRunCutOffBeforeAnyTextSavesNothingAndSaysSo() {
        provider.enqueue(CallResult.Done(emptyList(), listOf(chanelSearch), MeasuredUsage(5_000, 1_500, 0, 0, 0, 1), CallStop.MAX_TOKENS, 11_000, "msg_cut_first"))
        val r = service.extract(Research2Fixtures.FRAGRANCE_REQUEST)
        runBlocking { service.start(taskId, SessionDraft(Research2Fixtures.FRAGRANCE_REQUEST, r.template, r.requirements, ResearchModes.config(ResearchMode.QUICK))) }
        val e = failed(awaitFinished())
        assertFalse(e.toString(), e is ResearchError.Malformed)
        assertTrue(e.message, e.message.contains("Nothing was saved.") && !e.message.contains("previous research"))
        assertTrue(runBlocking { store.getAll() }.isEmpty())
        assertEquals(1, provider.calls.size)
        assertEquals(CallClass.TRUNCATED_BEFORE_TEXT, (e as ResearchError.NoFindings).why)
        assertFalse(e.continuation)
    }

    @Test fun aFocusOnlyContinuationWithoutANoteKeepsTheOldPromptShapeAndNoFollowUpRequirement() {
        val before = seedFragrance()
        provider.enqueue(Research2Fixtures.fragranceFillGaps().copy(providerMessageId = "msg_focus_only"))
        runBlocking { service.continueSession(before.id, ResearchModes.config(ResearchMode.QUICK), listOf("r-c2"), null) }
        val f = awaitFinished()
        assertTrue(f.result.toString(), f.result is RunResult.Saved)
        val prompt = provider.calls.single().user
        assertTrue(prompt.startsWith("Continue earlier research.") && prompt.contains("Focus on: r-c2") && !prompt.contains("main objective"))
        val after = stored(before.id)
        assertEquals(before.content.requirements.map { it.id }, after.content.requirements.map { it.id })
        assertTrue("no answer text came back, so nothing is added as a follow-up card", after.content.followUps.isEmpty())
    }

    private fun chanelAnswer(searches: Int, messageId: String) = CallResult.Done(
        listOf(
            RawSegment("## REQ r-f1 · status=answered\n", emptyList()),
            RawSegment("One Chanel men's fragrance fits the budget exactly: Bleu de Chanel EDT 100 ml at \$150.", listOf(chanelPage)),
            RawSegment("\n## ANSWER\n", emptyList()),
            RawSegment("Bleu de Chanel Eau de Toilette 100 ml is the one Chanel option at or under \$150 found at a US retailer.", listOf(chanelPage)),
            RawSegment("\n## CAND c5 · name=Bleu de Chanel · variant=EDT 100 ml · style=fresh woody\nThe house's broadly liked men's scent; at the budget limit.\n## ATTR c5.price · tag=Fact\n", emptyList()),
            RawSegment("150.00 USD · 100 ml · Big Retailer", listOf(chanelPage)),
            RawSegment("\n## ATTR c5.gift · tag=Judgment\nWidely recognised name; safe for most tastes.\n", emptyList()),
        ),
        listOf(chanelSearch), MeasuredUsage(9_800, 700, 0, 0, 0, searches), CallStop.END, 15_000, messageId,
    )
}
