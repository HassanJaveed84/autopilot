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
 * Guards: refuses without the key, without the model, when a report for the scenario already exists
 * (pass -PliveEvalRerun=true to override), or when the estimate exceeds the cap; sets the per-turn
 * spending limit; never retries. Each run writes its report to its own new directory,
 * app/build/live-eval/<scenario>/run-<UTC time>/, with its own database, and refuses before any call if
 * that directory exists, so no earlier report is ever overwritten. The report holds no credential.
 */
@RunWith(AndroidJUnit4::class)
class LiveInvestigationEval {
    private val scenario = "R-N-a"
    private val question = "What is the address of Union Station in Washington, DC?"
    private val searches = 3
    private val capCents = 300

    @Test fun runOneInvestigation() {
        assumeTrue("live evaluation runs only with -PliveEval=true", System.getProperty("liveEval.enabled") == "true")
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
