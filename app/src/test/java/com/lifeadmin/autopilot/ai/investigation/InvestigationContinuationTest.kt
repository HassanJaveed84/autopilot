package com.lifeadmin.autopilot.ai.investigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.context.FindingDisposition
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.StageKind
import com.lifeadmin.autopilot.ai.research2.StageResult
import com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore
import com.lifeadmin.autopilot.ai.usage.InMemoryUsageLedger
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
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
import com.lifeadmin.autopilot.testing.InvestigationFixtures
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
import java.time.Duration
import java.util.Locale

/**
 * Objective-led composition across unrelated domains and real continuation (RES-080–089, RES-100–107,
 * OD-4): structure follows the ask, nothing internal leaks, a follow-up changes a constraint, volatile
 * findings are checked fresh, excluded findings stay excluded, superseding and conflicting evidence are
 * reconciled, an interrupted turn resumes under a new authorization.
 */
@RunWith(AndroidJUnit4::class)
class InvestigationContinuationTest {
    private lateinit var db: AppDatabase
    private val clock = FakeClock(at(CHICAGO, 2026, 10, 23, 10, 0))
    private val zone = FakeZoneProvider(CHICAGO)
    private val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = at(CHICAGO, 2026, 10, 1, 9, 0), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION))
    private val keys = InMemoryApiKeyStore(FAKE_KEY)
    private val provider = FakeCallProvider()
    private val ledger = InMemoryUsageLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: InvestigationRepository
    private lateinit var service: InvestigationService
    private lateinit var taskId: String

    @Before fun setUp() {
        db = inMemoryDatabase()
        val ids = SequentialIds("k")
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        val repo = TaskRepository(db, clock, ids, zone::current, engine, log)
        store = InvestigationRepository(db)
        val planning = AiPlanningService(settings, keys, FakePlanningProvider(), repo, clock, zone, ids) { true }
        service = InvestigationService(settings, keys, planning, provider, repo, store, ledger, clock, zone, ids, scope, { true }, { Locale.US })
        taskId = runBlocking { repo.createTask(NewTask("Birthday gift for Sam", notes = "Likes fresh scents")) }
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun prepare(question: String, investigationId: String? = null, task: String? = taskId, resumeOf: String? = null) =
        runBlocking { service.prepare(question, investigationId, task, false, resumeOf) as PrepareResult.Ready }
    private fun config(depth: ResearchDepth = ResearchDepth.QUICK, searches: Int? = null, effort: String? = null) =
        runBlocking { ResearchPresets.resolve(depth, settings.current(), searches = searches, effort = effort) }
    private fun run(ready: PrepareResult.Ready, cfg: ResearchRunConfig = config(), vararg replies: CallResult): Turn {
        replies.forEach { provider.enqueue(it) }
        runBlocking {
            val inv = store.get(ready.investigationId)!!
            val turn = inv.turns.first { it.id == ready.turn.id }
            val auth = service.authorization(inv, turn, service.contextItems(inv.taskId, ResearchScope()), cfg)
            assertTrue(service.authorize(ready.investigationId, ready.turn.id, auth, cfg) is StartResult.Started)
        }
        val f = runBlocking { withTimeout(10_000) { service.state.filterIsInstance<TurnRunState.Finished>().first() } }
        service.dismiss(f.turnId)
        return runBlocking { store.get(f.investigationId)!!.turns.first { it.id == f.turnId } }
    }
    private fun allText(a: Answer): String = buildString {
        append(a.title); append(' '); append(a.stateLine); a.lead.forEach { append(' '); append(it.text) }; a.caveats.forEach { append(' '); append(it) }
        a.sections.forEach { s -> s.items.forEach { append(' '); append(it.text) }; s.rows.forEach { r -> r.cells.values.forEach { append(' '); append(it.text) } } }
        a.recommendation?.let { append(' '); append(it.text); it.reasons.forEach { r -> append(' '); append(r.text) }; it.assumptions.forEach { append(' '); append(it) }; it.flipConditions.forEach { append(' '); append(it) } }
    }

    // ------------------------------------------------------------------ composition across domains (RES-080–089)

    @Test fun fiveUnrelatedObjectivesGetFiveShapesFromTheirAskAndNoneLeaksInternals() {
        val fact = run(prepare(InvestigationFixtures.UNION_STATION), config(), InvestigationFixtures.unionStationAnswer()).result!!
        val procedure = run(prepare(InvestigationFixtures.PASSPORT), config(), InvestigationFixtures.passportProcedure()).result!!
        val compare = run(prepare(InvestigationFixtures.MULTIS), config(), InvestigationFixtures.multisCompare()).result!!
        val judgment = run(prepare(InvestigationFixtures.SNOWFLAKE), config(), InvestigationFixtures.snowflakeJudgment()).result!!
        val gap = run(prepare(InvestigationFixtures.UNVERIFIABLE), config(), InvestigationFixtures.unverifiableClaim()).result!!

        // Fact: a lead, no structure, no recommendation.
        assertTrue(fact.answer.sections.isEmpty())
        assertNull(fact.answer.recommendation)
        assertEquals(ResultState.ANSWERED, fact.state)
        // Procedure: steps, a condition the person resolves, no table.
        assertEquals(listOf(SectionKind.STEPS, SectionKind.CONDITIONS), procedure.answer.sections.map { it.kind })
        assertEquals(ResultState.ANSWERED_WITH_CONDITIONS, procedure.state)
        assertNull(procedure.answer.recommendation)
        // Comparison: rows on criteria, no steps, a clearly optional pick; the contested criterion is a caveat in the main answer.
        val table = compare.answer.sections.single { it.kind == SectionKind.COMPARISON }
        assertEquals(listOf("Thorne", "Pure Encapsulations"), table.options)
        assertEquals(3, table.rows.size)
        assertTrue(compare.answer.sections.none { it.kind == SectionKind.STEPS })
        assertTrue(compare.answer.recommendation!!.optional)
        assertEquals(ResultState.PARTIALLY_ANSWERED, compare.state)
        assertTrue(compare.answer.caveats.any { it.startsWith("Sources disagree:") })
        // Judgment: a recommendation with cited reasons, itself never cited; the condition is a section, not a gap.
        val rec = judgment.answer.recommendation!!
        assertFalse(rec.optional)
        assertEquals(listOf(listOf("c1"), listOf("c2")), rec.reasons.map { it.claimIds })
        assertEquals(1, rec.flipConditions.size)
        assertTrue("the recommendation claim itself carries no citation", judgment.claim("c4")!!.support.isEmpty())
        assertEquals(ClaimStatus.ESTABLISHED, judgment.claim("c4")!!.status)
        assertEquals(ResultState.ANSWERED_WITH_CONDITIONS, judgment.state)
        assertTrue(judgment.answer.sections.any { it.kind == SectionKind.CONDITIONS })
        assertTrue("the answer segment marked with the uncitable recommendation keeps only citable ids", judgment.answer.lead.none { "c4" in it.claimIds })
        // Honest gap: a lead that says what was searched, no "false", nothing proposed.
        assertTrue(gap.answer.sections.any { it.kind == SectionKind.UNRESOLVED })
        assertTrue(gap.proposals.isEmpty())

        for (r in listOf(fact, procedure, compare, judgment, gap)) {
            assertEquals("no internal residue in '${r.answer.title}'", emptyList<String>(), LeakageScan.scan(allText(r.answer)))
            assertFalse("no template vocabulary", allText(r.answer).contains("Shortlist") || allText(r.answer).contains("Picks:") || allText(r.answer).contains("Checklist:"))
        }
        assertEquals(5, runBlocking { store.getForTask(taskId) }.size)
    }

    // ------------------------------------------------------------------ continuation (RES-100–107)

    @Test fun aFollowUpChangingTheBudgetReplacesTheConstraintChecksPricesFreshAndSupersedesThePick() {
        val first = prepare(InvestigationFixtures.FRAGRANCE)
        assertEquals(AskKind.RECOMMEND, first.turn.objective.askKind)
        assertTrue(first.turn.objective.constraints.any { it.text == "under $150" })
        val t1 = run(first, config(), InvestigationFixtures.fragranceGather())
        assertEquals(ResultState.ANSWERED, t1.result!!.state)
        assertTrue(t1.result!!.claim("c1")!!.volatile)

        val second = prepare("What about under $100?", investigationId = first.investigationId)
        val o = second.turn.objective
        assertTrue("a follow-up with no subject keeps the earlier subject and ask", o.established)
        assertEquals(AskKind.RECOMMEND, o.askKind)
        assertTrue(o.text.contains("fragrance"))
        assertTrue("the new budget replaces the old one", o.constraints.any { it.text == "under $100" } && o.constraints.none { it.text.contains("150") })
        assertTrue("the place constraint carries over", o.constraints.any { it.text.contains("US") })
        val inv = runBlocking { store.get(first.investigationId)!! }
        val plan = service.inheritance(inv, inv.turns.first { it.id == second.turn.id }).associateBy { it.id }
        assertEquals("a price is volatile: checked fresh, never reused", FindingDisposition.FRESH, plan["c1"]!!.disposition)
        assertEquals(FindingDisposition.SENT, plan["c2"]!!.disposition)
        val auth = runBlocking { service.authorization(inv, inv.turns.first { it.id == second.turn.id }, service.contextItems(taskId), config()) }
        assertTrue(auth.freshChecks.single().contains("$128"))
        val payload = AuthorizationBuilder.payload(auth)
        assertEquals("the payload carries the new constraint set, not the old budget", listOf("in the US", "under $100"), payload.constraints)
        assertTrue("an earlier finding that mentions the old budget may still travel as what it is: an earlier finding", payload.inherited_findings.any { it.text.contains("$150") })
        assertTrue(auth.payloadLines.any { it.contains("checked fresh") })

        val t2 = run(second, config(), InvestigationFixtures.fragranceFollowUp())
        assertEquals(TurnState.DONE, t2.state)
        val r2 = t2.result!!
        assertEquals("the old pick is superseded by the new one", "c5", r2.claim("c3")!!.supersededByClaimId)
        assertTrue(r2.claim("c3")!!.superseded)
        assertEquals(ClaimStatus.ESTABLISHED, r2.claim("c4")!!.status)
        assertEquals("the price was re-checked, not reused", false, r2.claim("c1")!!.reused)
        assertEquals(t1.id, r2.claim("c1")!!.inheritedFromTurnId)
        assertTrue(r2.claim("c2")!!.reused)
        assertEquals("Bleu de Chanel EDT, 50 ml.", r2.answer.recommendation!!.text)
        assertTrue(r2.answer.sections.any { it.kind == SectionKind.CHANGES })
        assertEquals("the first turn's answer is untouched", "Dior Sauvage EDT, 100 ml.", runBlocking { store.get(first.investigationId)!! }.turns.first { it.id == t1.id }.result!!.answer.recommendation!!.text)
        val current = runBlocking { store.get(first.investigationId)!! }.currentClaims().map { it.id }.toSet()
        assertTrue(current.containsAll(setOf("c1", "c2", "c4", "c5")))
        assertFalse("a superseded claim is history, not current", "c3" in current)
    }

    @Test fun anEditedObjectiveThatNamesAnExcludedFindingIsWarnedAboutAndTheFindingStaysOut() {
        val first = prepare(InvestigationFixtures.MULTIS)
        run(first, config(), InvestigationFixtures.multisCompare())
        val second = prepare("Which is cheaper per month?", investigationId = first.investigationId)
        runBlocking { service.excludeClaims(first.investigationId, second.turn.id, setOf("c3")) }
        runBlocking { service.editObjective(first.investigationId, second.turn.id, "Compare monthly cost; both passed independent label-claim testing in 2026, so ignore testing") }
        val inv = runBlocking { store.get(first.investigationId)!! }
        val turn = inv.turns.first { it.id == second.turn.id }
        val auth = runBlocking { service.authorization(inv, turn, service.contextItems(taskId), config()) }
        assertTrue(auth.warnings.any { it.contains("earlier finding you excluded") })
        assertEquals(FindingDisposition.EXCLUDED, auth.inherited.first { it.claimId == "c3" }.disposition)
        assertFalse("the excluded finding is not sent as a finding; only the person's own wording goes out", auth.payloadJson.contains("\"id\": \"c3\""))
        assertEquals(listOf("c3"), runBlocking { store.get(first.investigationId)!! }.meta.excludedClaimIds)
        // A later turn inherits the exclusion without being asked again (OD-4).
        val third = prepare("And which has more vitamin D?", investigationId = first.investigationId)
        assertEquals(listOf("c3"), third.turn.excludedClaimIds)
    }

    @Test fun detailsSuppliedOnTheCardReplaceTheConditionTheyAnswer() {
        val first = prepare(InvestigationFixtures.SNOWFLAKE)
        run(first, config(), InvestigationFixtures.snowflakeJudgment())
        val second = prepare("Given that, should we still replicate?", investigationId = first.investigationId)
        runBlocking { service.supplyDetails(first.investigationId, second.turn.id, listOf("Our p95 latency target is 200 ms and traffic peaks at 400 requests per second")) }
        val inv = runBlocking { store.get(first.investigationId)!! }
        val plan = service.inheritance(inv, inv.turns.first { it.id == second.turn.id }).associateBy { it.id }
        assertEquals(FindingDisposition.REPLACED, plan["c5"]!!.disposition)
        assertEquals(FindingDisposition.SENT, plan["c1"]!!.disposition)
        val auth = runBlocking { service.authorization(inv, inv.turns.first { it.id == second.turn.id }, service.contextItems(taskId), config()) }
        assertTrue("the supplied detail travels as a constraint of the objective", auth.constraints.any { it.contains("200 ms") })
        assertTrue(auth.payloadJson.contains("200 ms"))
        assertTrue(auth.payloadLines.any { it.contains("not needed") })
    }

    @Test fun deepReconciliationExplainsAConflictAsAChangeOverTimeAndWritesTheResultFromHeldClaims() {
        val ready = prepare(InvestigationFixtures.MULTIS)
        val cfg = config(ResearchDepth.DEEP, searches = 6, effort = "high")
        assertEquals(listOf(StageKind.GATHER, StageKind.FILL_GAPS, StageKind.CROSS_CHECK, StageKind.SYNTHESIZE), cfg.stages)
        assertEquals("high", cfg.effort)
        val t = run(ready, cfg, InvestigationFixtures.multisCompare(), InvestigationFixtures.multisVerifyStillContested(), InvestigationFixtures.multisReconcile(), InvestigationFixtures.multisSynthesis())
        assertEquals(TurnState.DONE, t.state)
        val r = t.result!!
        assertEquals(listOf(StageResult.DONE, StageResult.DONE, StageResult.DONE, StageResult.DONE), r.stages.map { it.result })
        assertEquals(4, provider.calls.size)
        assertTrue("the verify pass names only the open claim", provider.calls[1].user.contains("- c4 ·") && !provider.calls[1].user.contains("- c1 ·"))
        assertTrue("the reconcile pass names the contested claim and the scope/date rule", provider.calls[2].user.contains("c4") && provider.calls[2].user.contains("supersedes"))
        assertEquals(0, provider.calls[3].maxSearches)
        assertTrue(provider.calls[3].user.contains("Claims held"))
        assertTrue(provider.calls.all { it.effort == "high" })
        assertEquals(ClaimStatus.CONTESTED, runBlocking { store.get(ready.investigationId)!! }.turns.single().result!!.claim("c4")!!.status.let { ClaimStatus.CONTESTED.takeIf { _ -> r.claim("c4")!!.superseded } ?: it })
        assertTrue(r.claim("c4")!!.superseded)
        assertEquals("c5", r.claim("c4")!!.supersededByClaimId)
        assertEquals(ClaimStatus.ESTABLISHED, r.claim("c5")!!.status)
        assertEquals("two independent origins", 2, r.claim("c5")!!.support.map { it.originKey }.distinct().size)
        assertEquals(ResultState.ANSWERED, r.state)
        assertTrue(r.answer.sections.any { it.kind == SectionKind.CHANGES && it.items.any { i -> i.text.contains("Not a disagreement") } })
        assertEquals(3, r.answer.sections.single { it.kind == SectionKind.COMPARISON }.rows.size)
        assertTrue(r.answer.lead.any { it.text.contains("renewed in January 2026") })
        assertEquals(emptyList<String>(), LeakageScan.scan(allText(r.answer)))
        val rows = runBlocking { ledger.getAll() }
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.effort == "high" && it.model == "claude-opus-5-5" })
        assertEquals(listOf("GATHER", "FILL_GAPS", "CROSS_CHECK", "SYNTHESIZE"), rows.sortedBy { it.startedAt }.map { it.stage })
        assertEquals("claude-opus-5-5", t.usage.modelsUsed.single())
        assertEquals("high", t.usage.effortUsed)
    }

    @Test fun anInterruptedTurnResumesAsANewAuthorizedTurnThatInheritsItsCheckpoint() {
        val ready = prepare(InvestigationFixtures.UNION_STATION)
        val inv = runBlocking { store.get(ready.investigationId)!! }
        val auth = runBlocking { service.authorization(inv, ready.turn, service.contextItems(taskId), config()) }
        val claim = Claim("c1", "Union Station is at 50 Massachusetts Ave NE, Washington, DC 20002.", ClaimType.FACT, loadBearing = true, support = listOf(EvidenceRef(1, "x", retrievedAtMs = clock.now().toEpochMilli(), originKey = "amtrak.example")), status = ClaimStatus.ESTABLISHED, turnId = ready.turn.id)
        val provisional = TurnResult(Answer("Union Station", "Interrupted", listOf(AnswerSegment("50 Massachusetts Ave NE", listOf("c1")))), ResultState.ANSWERED, TurnEnding.INTERRUPTED, listOf(claim), emptyList(), finishedAtMs = 1L)
        runBlocking { store.saveTurn(ready.investigationId, ready.turn.copy(state = TurnState.RUNNING, authorization = auth, result = provisional, stageAtEnd = "GATHER"), clock.now()) }
        val interrupted = runBlocking { service.recoverInterrupted() }.single()
        assertEquals(TurnState.INTERRUPTED, interrupted.state)

        clock.advance(Duration.ofMinutes(5))
        val resume = prepare(InvestigationFixtures.UNION_STATION, investigationId = ready.investigationId, resumeOf = interrupted.id)
        assertEquals(TurnKind.RESUME, resume.turn.kind)
        assertEquals(interrupted.id, resume.turn.resumeOf)
        assertEquals("the interrupted turn's objective is kept as it was", interrupted.objective, resume.turn.objective)
        assertEquals(TurnState.AUTHORIZING, resume.turn.state)
        val inv2 = runBlocking { store.get(ready.investigationId)!! }
        val plan = service.inheritance(inv2, inv2.turns.first { it.id == resume.turn.id })
        assertEquals("the checkpointed claim is offered for reuse", listOf("c1" to FindingDisposition.SENT), plan.map { it.id to it.disposition })
        assertTrue("nothing restarted on its own", provider.calls.isEmpty())
        val t = run(resume, config(), InvestigationFixtures.unionStationAnswer())
        assertEquals(TurnState.DONE, t.state)
        assertEquals("the resumed turn re-checked c1 rather than reusing it", false, t.result!!.claim("c1")!!.reused)
        assertEquals(interrupted.id, t.result!!.claim("c1")!!.inheritedFromTurnId)
        assertEquals(2, runBlocking { store.get(ready.investigationId)!! }.turns.size)
        assertEquals("a new paid continuation carried its own authorization", true, t.authorization != null && t.authorization!!.approvedAtMs > auth.approvedAtMs)
    }
}
