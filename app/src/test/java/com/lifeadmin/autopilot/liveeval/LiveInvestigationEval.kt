package com.lifeadmin.autopilot.liveeval

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchCalls
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchProvider
import com.lifeadmin.autopilot.ai.investigation.InvestigationJson
import com.lifeadmin.autopilot.ai.investigation.InvestigationPrompt
import com.lifeadmin.autopilot.ai.investigation.InvestigationService
import com.lifeadmin.autopilot.ai.investigation.PrepareResult
import com.lifeadmin.autopilot.ai.investigation.ResearchDepth
import com.lifeadmin.autopilot.ai.investigation.ResearchPresets
import com.lifeadmin.autopilot.ai.investigation.StartResult
import com.lifeadmin.autopilot.ai.investigation.TurnEvent
import com.lifeadmin.autopilot.ai.investigation.TurnRunState
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.ai.investigation.WordingCheck
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * One paid, end-to-end investigation through the production engine: the real [InvestigationService],
 * the real Anthropic adapter ([AnthropicResearchCalls]), the parser, the claim assessor, the result
 * deriver and the composer, with a Room database and ledger. Nothing is mocked except a planning
 * provider that this standalone, locally-prepared turn never calls.
 *
 * Run only on purpose, from a session whose environment has the key:
 *   ./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true --tests 'com.lifeadmin.autopilot.liveeval.LiveInvestigationEval'
 *
 * The scenario is chosen with -PliveEvalScenario: R-N-a (the default), R-A, or R-N-b, which records the
 * clarification flow before any authorization and makes no paid call (it needs no key).
 *
 * Guards: refuses without the key, without the model, when a report for the scenario already exists
 * (pass -PliveEvalRerun=true to override), or when the estimate exceeds the cap; sets the per-turn
 * spending limit; never retries. Each run writes its report to its own new directory,
 * app/build/live-eval/<scenario>/run-<UTC time>/, with its own database, and refuses before any call if
 * that directory exists, so no earlier report is ever overwritten. The report holds no credential.
 */
@RunWith(AndroidJUnit4::class)
class LiveInvestigationEval {
    /** A paid scenario: Document F §5.2 question, search budget at the Standard level, and the per-turn spending cap. */
    private data class Paid(val id: String, val question: String, val searches: Int, val capCents: Int)

    private val paid = mapOf(
        "R-N-a" to Paid("R-N-a", "What is the address of Union Station in Washington, DC?", searches = 3, capCents = 300),
        // Health evidence over several candidates: the Standard level's own budget, never a one-search shortcut.
        "R-A" to Paid("R-A", "Best supplements for men aged 40", searches = ResearchPresets.STANDARD.searches, capCents = 100),
    )
    private val selected = System.getProperty("liveEval.scenario") ?: "R-N-a"
    private val enabled get() = System.getProperty("liveEval.enabled") == "true"

    @Test fun runOneInvestigation() {
        assumeTrue("live evaluation runs only with -PliveEval=true", enabled)
        val sc = paid[selected]
        assumeTrue("$selected is not a paid investigation scenario", sc != null)
        val scenario = sc!!.id
        val question = sc.question
        val searches = sc.searches
        val capCents = sc.capCents
        val scenarioDir = File(System.getProperty("liveEval.outDir") ?: "build/live-eval", scenario).apply { mkdirs() }
        val keys = EnvApiKeyStore()
        val key = keys.get()
        check(key != null) { "$LIVE_EVAL_KEY_VARIABLE is not set in this process's environment; nothing was sent." }
        // The first run wrote scenarioDir/result.json; later runs write scenarioDir/run-*/result.json.
        val earlier = File(scenarioDir, "result.json").exists() || scenarioDir.listFiles()?.any { it.isDirectory && File(it, "result.json").exists() } == true
        check(System.getProperty("liveEval.rerun") == "true" || !earlier) {
            "A report for $scenario already exists in $scenarioDir; a second paid run needs -PliveEvalRerun=true. Nothing was sent."
        }
        val runId = "run-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        val out = File(scenarioDir, runId)
        check(!out.exists()) { "$out already exists; a run never overwrites an earlier report. Nothing was sent." }
        out.mkdirs()

        val db = fileDatabase("live-eval-$scenario-$runId.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = AppClock.system()
        val zone = ZoneProvider { ZoneOffset.UTC }
        val ids = SequentialIds("live")
        val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = clock.now(), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION))
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        val tasks = TaskRepository(db, clock, ids, zone::current, engine, log)
        val store = InvestigationRepository(db)
        val ledger = RoomUsageLedger(db)
        val adapter = AnthropicResearchProvider()
        val provider = RecordingCallProvider(AnthropicResearchCalls(adapter))
        val planning = AiPlanningService(settings, keys, FakePlanningProvider(), tasks, clock, zone, ids) { true }
        val service = InvestigationService(settings, keys, planning, provider, tasks, store, ledger, clock, zone, ids, scope, { true }, { Locale.US })
        val events = java.util.Collections.synchronizedList(ArrayList<TurnEvent>())
        val notes = ArrayList<String>()
        try {
            runBlocking {
                // 1. Objective: local preparation (free; general consent covers the optional provider pass, which is not used here).
                val ready = service.prepare(question, null, null, false) as? PrepareResult.Ready ?: error("The objective was not established; nothing was sent.")
                val cfg = ResearchPresets.resolve(ResearchDepth.STANDARD, settings.current(), searches = searches, limitCents = capCents)

                // 2. Free preflight: is the configured model available to this key?
                val modelStatus = LiveEvalSafety.modelStatus(cfg.model, key)
                notes += "Models API preflight for ${cfg.model}: HTTP $modelStatus"
                check(modelStatus == 200) { "The key cannot use ${cfg.model} (Models API HTTP $modelStatus); nothing paid was sent." }

                // 3. Authorization through the real service, exactly as the card builds it.
                val inv = store.get(ready.investigationId)!!
                val turn = inv.turns.first { it.id == ready.turn.id }
                val auth = service.authorization(inv, turn, service.contextItems(null, ResearchScope()), cfg)
                val high = auth.estimateHighMicros ?: Long.MAX_VALUE
                check(high <= capCents * 10_000L) { "Estimate high ${high / 1e6} USD is above the $capCents-cent cap; nothing was sent." }
                val collector = launch {
                    service.state.collect { s -> if (s is TurnRunState.Running) s.events.forEach { e -> if (events.none { it.atEpochMs == e.atEpochMs && it.text == e.text }) events += e } }
                }
                val started = service.authorize(ready.investigationId, ready.turn.id, auth, cfg)
                check(started is StartResult.Started) { "The service refused to start: $started" }
                val finished = withTimeout(15 * 60_000L) { service.state.filterIsInstance<TurnRunState.Finished>().first() }
                collector.cancel()

                // 4. Everything the engine stored, plus the exchanges, into a sanitized report.
                val stored = store.get(finished.investigationId)!!
                val done = stored.turns.first { it.id == finished.turnId }
                val rows = ledger.getAll().filter { it.sessionId == finished.investigationId || it.runId == finished.turnId }
                val report = buildJsonObject {
                    put("scenario", scenario)
                    put("runId", runId)
                    put("question", question)
                    put("ranAtUtc", Instant.ofEpochMilli(provider.exchanges.firstOrNull()?.startedAtMs ?: clock.now().toEpochMilli()).toString())
                    put("promptContractVersion", InvestigationPrompt.VERSION)
                    put("systemPromptSha256", LiveEvalSafety.sha256(InvestigationPrompt.SYSTEM))
                    put("wordingCheckVersion", WordingCheck.VERSION)
                    put("wordingCheckSystemSha256", LiveEvalSafety.sha256(WordingCheck.SYSTEM))
                    put("wordingCheckAuthorized", auth.wordingCheck)
                    put("requested", buildJsonObject {
                        put("preset", cfg.presetName); put("depth", cfg.depth.name); put("model", cfg.model); put("searchBudget", cfg.searchBudget)
                        put("outputTokens", cfg.outputTokens); put("stages", JsonArray(cfg.stages.map { JsonPrimitive(it.name) })); put("spendLimitCents", cfg.spendLimitCents ?: -1)
                        put("effort", cfg.effort?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("estimateLowUsd", (auth.estimateLowMicros ?: 0) / 1e6); put("estimateHighUsd", (auth.estimateHighMicros ?: 0) / 1e6)
                    })
                    put("outcome", finished.outcome.toString())
                    put("preflight", JsonArray(notes.map { JsonPrimitive(it) }))
                    put("events", buildJsonArray { events.forEach { e -> add(buildJsonObject { put("at", Instant.ofEpochMilli(e.atEpochMs).toString()); put("kind", e.kind.name); put("text", e.text) }) } })
                    put("exchanges", buildJsonArray { provider.exchanges.forEach { add(exchange(it)) } })
                    put("ledger", buildJsonArray {
                        rows.forEach { r ->
                            add(buildJsonObject {
                                put("feature", r.feature.name); put("model", r.model); put("stage", r.stage ?: ""); put("outcome", r.outcome.name); put("usageStatus", r.usageStatus.name)
                                put("inputTokens", r.usage?.inputTokens ?: -1); put("outputTokens", r.usage?.outputTokens ?: -1); put("cacheReadTokens", r.usage?.cacheReadTokens ?: -1)
                                put("cacheWriteTokens", (r.usage?.cacheWrite5mTokens ?: 0) + (r.usage?.cacheWrite1hTokens ?: 0)); put("webSearches", r.usage?.webSearches ?: -1)
                                put("estimatedUsd", (r.estimatedMicros ?: 0) / 1e6); put("priceVersion", r.priceVersion); put("durationMs", r.durationMs ?: -1)
                                put("providerMessageId", r.providerMessageId ?: ""); put("effort", r.effort ?: "")
                            })
                        }
                    })
                    put("turn", InvestigationJson.json.parseToJsonElement(InvestigationJson.encodeTurn(done)))
                }
                val text = LiveEvalSafety.redact(InvestigationJson.json.encodeToString(JsonElement.serializer(), report), key)
                File(out, "result.json").writeText(text)
                assertTrue("no credential in the report", key !in text)
            }
        } finally {
            scope.cancel()
            adapter.reset()
            db.close()
        }
    }

    /**
     * Document F R-N(b) and §7 "clarification before authorization": "address of Union Station" names no city. The real
     * service prepares the objective locally; the report records that it asks which place, that no authorization
     * surface exists before the answer, that no provider call or ledger row exists, and that supplying the place
     * establishes the objective so an authorization can be built. Nothing is authorized or sent; no key is needed.
     */
    @Test fun recordClarificationFlow() {
        assumeTrue("live evaluation runs only with -PliveEval=true", enabled)
        assumeTrue("the clarification flow is recorded only for -PliveEvalScenario=R-N-b", selected == "R-N-b")
        val scenario = "R-N-b"
        val question = "What is the address of Union Station?"
        val place = "Washington, DC"
        val scenarioDir = File(System.getProperty("liveEval.outDir") ?: "build/live-eval", scenario).apply { mkdirs() }
        val runId = "run-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        val out = File(scenarioDir, runId)
        check(!out.exists()) { "$out already exists; a run never overwrites an earlier report." }
        out.mkdirs()
        val db = fileDatabase("live-eval-$scenario-$runId.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = AppClock.system()
        val zone = ZoneProvider { ZoneOffset.UTC }
        val ids = SequentialIds("live")
        val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = clock.now(), researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION))
        val log = RoomDiagnosticsLog(db, clock, ids)
        val engine = ReminderEngine(db, settings, FakeAlarmDriver(), FakeNotificationPublisher(), clock, zone, ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        val tasks = TaskRepository(db, clock, ids, zone::current, engine, log)
        val store = InvestigationRepository(db)
        val ledger = RoomUsageLedger(db)
        // A provider that refuses to send anything: this flow must never reach it.
        val provider = RecordingCallProvider(object : com.lifeadmin.autopilot.ai.research2.ResearchCallProvider {
            override val id = "no-calls"
            override suspend fun call(call: com.lifeadmin.autopilot.ai.research2.ModelCall, apiKey: String): CallResult = error("R-N(b) must not send a research call")
        })
        // Local preparation and clarification never use a key; a placeholder keeps the gate open, and nothing is sent.
        val noKey = com.lifeadmin.autopilot.ai.security.InMemoryApiKeyStore("not-a-key")
        val planning = AiPlanningService(settings, noKey, FakePlanningProvider(), tasks, clock, zone, ids) { true }
        val service = InvestigationService(settings, noKey, planning, provider, tasks, store, ledger, clock, zone, ids, scope, { true }, { Locale.US })
        try {
            runBlocking {
                val ready = service.prepare(question, null, null, false) as? PrepareResult.Ready ?: error("prepare was rejected")
                val before = store.get(ready.investigationId)!!.turns.first { it.id == ready.turn.id }
                val o = before.objective
                val cfg = ResearchPresets.resolve(ResearchDepth.STANDARD, settings.current(), searches = 3, limitCents = 300)
                val clarified = service.clarify(ready.investigationId, ready.turn.id, place) ?: error("the clarification was not accepted")
                val after = store.get(ready.investigationId)!!.turns.first { it.id == ready.turn.id }
                val auth = if (after.objective.established) service.authorization(store.get(ready.investigationId)!!, after, service.contextItems(null, ResearchScope()), cfg) else null
                val report = buildJsonObject {
                    put("scenario", scenario); put("runId", runId); put("question", question); put("ranAtUtc", clock.now().toString())
                    put("before", buildJsonObject {
                        put("turnState", before.state.name); put("objectiveEstablished", o.established); put("askKind", o.askKind.name)
                        put("clarificationQuestion", o.clarification?.question ?: ""); put("clarificationOptions", JsonArray(o.clarification?.options.orEmpty().map { JsonPrimitive(it) }))
                        put("authorizationExists", before.authorization != null); put("preparedBy", o.preparedBy.name)
                    })
                    put("answer", place)
                    put("after", buildJsonObject {
                        put("turnState", after.state.name); put("objectiveEstablished", after.objective.established); put("objective", after.objective.text)
                        put("authorizationBuildable", auth != null); put("authorized", after.authorization != null)
                        put("estimateHighUsd", (auth?.estimateHighMicros ?: 0) / 1e6)
                    })
                    put("providerCalls", provider.exchanges.size)
                    put("ledgerRows", ledger.getAll().size)
                    put("clarifiedTurnState", clarified.state.name)
                }
                File(out, "result.json").writeText(InvestigationJson.json.encodeToString(JsonElement.serializer(), report))
                check(provider.exchanges.isEmpty() && ledger.getAll().isEmpty()) { "a call or ledger row exists" }
                check(before.state != TurnState.AUTHORIZING || before.authorization == null)
            }
        } finally {
            scope.cancel()
            db.close()
        }
    }

    private fun exchange(x: Exchange): JsonElement = buildJsonObject {
        put("index", x.index)
        put("startedAtUtc", Instant.ofEpochMilli(x.startedAtMs).toString())
        put("request", buildJsonObject {
            put("model", x.call.model); put("maxTokens", x.call.maxTokens); put("maxSearches", x.call.maxSearches)
            put("effort", x.call.effort?.let { JsonPrimitive(it) } ?: JsonNull); put("cacheSystem", x.call.cacheSystem)
            put("timeoutSeconds", x.call.timeout?.seconds ?: -1); put("continuesPausedTurn", x.call.prior != null)
            put("systemSha256", LiveEvalSafety.sha256(x.call.system)); put("user", x.call.user)
        })
        when (val r = x.result) {
            is CallResult.Done -> put("reply", buildJsonObject {
                put("status", "done"); put("stop", r.stop.name); put("elapsedMs", r.elapsedMs); put("providerMessageId", r.providerMessageId ?: "")
                put("refusalCategory", r.refusalCategory ?: ""); put("shape", r.shape?.toString() ?: "")
                put("usage", r.usage.toString())
                put("segments", buildJsonArray {
                    r.segments.forEach { s ->
                        add(buildJsonObject {
                            put("text", s.text)
                            put("citations", buildJsonArray { s.citations.forEach { c -> add(buildJsonObject { put("url", c.url); put("title", c.title ?: ""); put("citedText", c.citedText) }) } })
                        })
                    }
                })
                put("searches", buildJsonArray {
                    r.searches.forEach { q ->
                        add(buildJsonObject {
                            put("query", q.query); put("errorCode", q.errorCode ?: "")
                            put("results", buildJsonArray { q.results.forEach { res -> add(buildJsonObject { put("url", res.url); put("title", res.title); put("pageAge", res.pageAge ?: "") }) } })
                        })
                    }
                })
            })
            is CallResult.Failed -> put("reply", buildJsonObject {
                put("status", "failed"); put("error", r.error.technical); put("headline", r.error.headline); put("searchesSeen", r.searchesSeen)
                put("usage", r.usage?.toString() ?: ""); put("elapsedMs", r.elapsedMs)
            })
        }
    }
}
