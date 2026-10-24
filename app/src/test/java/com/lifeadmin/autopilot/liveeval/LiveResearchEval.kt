package com.lifeadmin.autopilot.liveeval

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.aifirst.AfJson
import com.lifeadmin.autopilot.ai.aifirst.AfRender
import com.lifeadmin.autopilot.ai.aifirst.AfRunState
import com.lifeadmin.autopilot.ai.aifirst.AfStatus
import com.lifeadmin.autopilot.ai.aifirst.AfTurn
import com.lifeadmin.autopilot.ai.aifirst.AskResult
import com.lifeadmin.autopilot.ai.aifirst.ResearchOnlineService
import com.lifeadmin.autopilot.ai.aifirst.StartResult
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchCalls
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchProvider
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.AfThreadRepository
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.RoomUsageLedger
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.reminders.ReminderEngine
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakePlanningProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.fileDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * PAID live validation of the production Research Online path (clarification check, authorization,
 * research, follow-up) against Anthropic. Development only: excluded from every run unless
 * `-PliveEval=true`, and only for an explicitly named scenario. Not run in CI.
 *
 *   LIVE_EVAL_ANTHROPIC_API_KEY=… ./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true -PliveEvalScenario=V1 \
 *       --tests 'com.lifeadmin.autopilot.liveeval.LiveResearchEval'
 *
 * Guards: key from the environment only; free Models API preflight; one run per scenario unless
 * `-PliveEvalRerun=true`; every turn's approved estimate must sit under the scenario's limit; and the
 * measured cost of every earlier run plus this run's limits must stay under [TOTAL_CAP_CENTS]. A
 * clarification ends the scenario before anything is searched unless the scenario supplies an answer.
 * Raw reports go to build/live-eval-research/ and are never committed.
 */
@RunWith(AndroidJUnit4::class)
class LiveResearchEval {
    private data class Step(val question: String, val clarifyAnswer: String? = null)
    private data class Scenario(val id: String, val steps: List<Step>, val searches: Int, val limitCents: Int, val expectClarification: Boolean = false)

    private val scenarios = listOf(
        Scenario("V1-fact", listOf(Step("What is the street address of the British Library in London?")), 3, 100),
        Scenario("V2-ambiguous", listOf(Step("What's the address of Union Station?")), 3, 100, expectClarification = true),
        Scenario("V3-health-evidence", listOf(Step("Is there good evidence that vitamin D supplements prevent fractures in healthy adults over 50?")), 6, 200),
        Scenario("V4-technical", listOf(Step("For a small team, should we use SQLite or Postgres as the database for a new internal web app with about 50 users?")), 6, 200),
        Scenario(
            "V5-follow-up",
            listOf(Step("What's a good mid-range robot vacuum for a small flat with hard floors?"), Step("Actually we have two cats and a long-pile rug in the living room, and the budget is now under \$300. Does that change your pick?")),
            6, 200,
        ),
    ).associateBy { it.id }

    private val enabled get() = System.getProperty("liveEval.enabled") == "true"
    private val root get() = File(File(System.getProperty("liveEval.outDir") ?: "build/live-eval").parentFile, "live-eval-research")

    @Test fun run() {
        assumeTrue("live evaluation runs only with -PliveEval=true", enabled)
        val sc = System.getProperty("liveEval.scenario")?.let { scenarios[it] }
        assumeTrue("pick a scenario with -PliveEvalScenario=<id>: ${scenarios.keys}", sc != null)
        sc!!
        val key = EnvApiKeyStore().get() ?: error("$LIVE_EVAL_KEY_VARIABLE is not set; nothing was sent.")
        // Free preflight for both models the path uses: the research model and the clarification/suggestion model.
        for (model in listOf(ResearchOnlineService.DEFAULT_MODEL, com.lifeadmin.autopilot.ai.aifirst.SmallCall.MODEL)) {
            check(LiveEvalSafety.modelStatus(model, key) == 200) { "The key cannot use $model; nothing paid was sent." }
        }
        val dir = File(root, sc.id)
        check(System.getProperty("liveEval.rerun") == "true" || dir.listFiles()?.any { File(it, "result.json").exists() } != true) { "$dir already has a run; a rerun needs -PliveEvalRerun=true. Nothing was sent." }
        val spent = spentCents()
        check(spent + sc.limitCents * sc.steps.size <= TOTAL_CAP_CENTS) { "Validation ceiling: $spent¢ measured so far; this run could add ${sc.limitCents * sc.steps.size}¢. Nothing was sent." }
        val out = File(dir, "run-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()))
        check(out.mkdirs()) { "$out exists" }
        val report = runScenario(sc, out)
        val text = LiveEvalSafety.redact(AfJson.json.encodeToString(JsonElement.serializer(), report), key)
        check(key !in text)
        File(out, "result.json").writeText(text)
        File(out, "answer.txt").writeText(LiveEvalSafety.redact((report["turns"] as JsonArray).joinToString("\n\n======== next turn ========\n\n") { it.jsonObject["shown"]!!.jsonPrimitive.content }, key))
    }

    private fun spentCents(): Int = root.walkTopDown().filter { it.name == "result.json" }.sumOf { f ->
        runCatching { AfJson.json.parseToJsonElement(f.readText()).jsonObject["totalMicros"]!!.jsonPrimitive.long }.getOrDefault(0L)
    }.let { ((it + 9_999) / 10_000).toInt() }

    private fun runScenario(sc: Scenario, out: File): JsonObject {
        val db = fileDatabase("live-research-${sc.id}-${out.name}.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = AppClock.system()
        val zone = ZoneProvider { ZoneOffset.UTC }
        val ids = SequentialIds("live")
        val keys = EnvApiKeyStore()
        val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = clock.now(), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION, researchDefaultSearches = sc.searches, researchSpendLimitCents = sc.limitCents))
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        val tasks = TaskRepository(db, clock, ids, zone::current, engine, log)
        val sets = ProposalSetRepository(db)
        val ledger = RoomUsageLedger(db)
        val adapter = AnthropicResearchProvider()
        val provider = RecordingCallProvider(AnthropicResearchCalls(adapter))
        val planning = AiPlanningService(settings, keys, FakePlanningProvider(), tasks, clock, zone, ids) { true }
        val store = AfThreadRepository(db)
        val service = ResearchOnlineService(settings, keys, planning, provider, tasks, store, InvestigationRepository(db), ledger, sets, ProposalApplier(db, tasks, sets, clock, ids), clock, zone, ids, scope, { true }, { Locale.US })
        val turns = ArrayList<JsonObject>()
        try {
            runBlocking {
                var threadId: String? = null
                for (step in sc.steps) {
                    val before = provider.exchanges.size
                    val asked = service.ask(step.question, threadId, null) as? AskResult.Ready ?: error("the question was rejected; nothing was sent")
                    threadId = asked.threadId
                    var turn = asked.turn
                    if (turn.status == AfStatus.CLARIFYING) {
                        if (step.clarifyAnswer == null) { turns += report(step.question, turn, provider.exchanges.drop(before), ledger.getAll().filter { it.runId == turn.id }, "Clarification asked before any search: ${turn.clarification?.question} ${turn.clarification?.options}"); break }
                        turn = service.answerClarification(threadId, turn.id, step.clarifyAnswer)!!
                    }
                    val cfg = service.defaults(settings.current())
                    val auth = service.preview(threadId, turn.id, service.contextItems(null, ResearchScope()), cfg) ?: error("no card")
                    check((auth.estimateHighMicros ?: Long.MAX_VALUE) <= sc.limitCents * 10_000L) { "estimate above the limit; nothing sent" }
                    check(service.start(threadId, turn.id, auth) is StartResult.Started)
                    withTimeout(20 * 60_000L) { service.state.filterIsInstance<AfRunState.Finished>().first { it.turnId == turn.id } }
                    val done = store.get(threadId)!!.turns.first { it.id == turn.id }
                    turns += report(step.question, done, provider.exchanges.drop(before), ledger.getAll().filter { it.runId == turn.id }, AfRender.text(done))
                }
            }
        } finally {
            scope.cancel(); adapter.reset(); db.close()
        }
        val micros = turns.sumOf { t -> (t["ledger"] as JsonArray).sumOf { it.jsonObject["estimatedMicros"]!!.jsonPrimitive.long } }
        return buildJsonObject {
            put("scenario", sc.id); put("ranAtUtc", Instant.now().toString()); put("expectClarification", sc.expectClarification)
            put("searchBudget", sc.searches); put("spendLimitCents", sc.limitCents); put("totalMicros", micros); put("turns", JsonArray(turns))
        }
    }

    private fun report(question: String, t: AfTurn, xs: List<Exchange>, rows: List<com.lifeadmin.autopilot.ai.usage.UsageRecord>, shown: String) = buildJsonObject {
        put("question", question); put("status", t.status.name); put("shown", shown)
        put("turn", AfJson.json.encodeToJsonElement(AfTurn.serializer(), t))
        put("ledger", buildJsonArray {
            rows.sortedBy { it.startedAt }.forEach { r ->
                add(buildJsonObject {
                    put("feature", r.feature.name); put("stage", r.stage ?: ""); put("model", r.model); put("outcome", r.outcome.name)
                    put("inputTokens", r.usage?.inputTokens ?: -1); put("outputTokens", r.usage?.outputTokens ?: -1); put("webSearches", r.usage?.webSearches ?: -1)
                    put("estimatedMicros", r.estimatedMicros ?: 0); put("durationMs", r.durationMs ?: -1)
                })
            }
        })
        put("exchanges", buildJsonArray {
            xs.forEach { x ->
                add(buildJsonObject {
                    put("model", x.call.model); put("maxSearches", x.call.maxSearches); put("continuesPausedTurn", x.call.prior != null); put("user", x.call.user)
                    when (val r = x.result) {
                        is CallResult.Done -> { put("stop", r.stop.name); put("elapsedMs", r.elapsedMs) }
                        is CallResult.Failed -> { put("stop", "FAILED"); put("error", r.error.technical) }
                    }
                })
            }
        })
    }

    companion object {
        /** Ceiling for all validation runs together, checked against measured cost before anything is sent. */
        const val TOTAL_CAP_CENTS = 1_500
    }
}
