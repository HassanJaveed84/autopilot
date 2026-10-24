package com.lifeadmin.autopilot.liveeval

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.aifirst.AfConfig
import com.lifeadmin.autopilot.ai.aifirst.AfJson
import com.lifeadmin.autopilot.ai.aifirst.AfRender
import com.lifeadmin.autopilot.ai.aifirst.AiFirstResearch
import com.lifeadmin.autopilot.ai.aifirst.FileAfThreadStore
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchCalls
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchProvider
import com.lifeadmin.autopilot.ai.investigation.InvestigationService
import com.lifeadmin.autopilot.ai.investigation.PrepareResult
import com.lifeadmin.autopilot.ai.investigation.ResearchDepth
import com.lifeadmin.autopilot.ai.investigation.ResearchPresets
import com.lifeadmin.autopilot.ai.investigation.StartResult
import com.lifeadmin.autopilot.ai.investigation.TurnResult
import com.lifeadmin.autopilot.ai.investigation.TurnRunState
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.usage.UsageRecord
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * EXPERIMENT: paid A/B runs of the frozen M4 engine against the AI-first engine on the same question, model,
 * search budget and spending limit. Development only (excluded unless -PliveEval=true).
 *
 *   ./gradlew --no-daemon :app:testDebugUnitTest -PliveEval=true -PliveEvalAb=<scenario> [-PliveEvalEngine=m4|aifirst] \
 *       --tests 'com.lifeadmin.autopilot.liveeval.LiveAbEval'
 *
 * Guards: key from the environment only, free Models API preflight, one run per scenario and engine unless
 * -PliveEvalRerun=true, a per-turn spending limit enforced by each engine, and an experiment-wide ceiling
 * ([EXPERIMENT_CAP_CENTS]) checked against the measured cost of every earlier run before anything is sent.
 * Raw reports (requests, replies, ledger) go to build/live-eval-ab/ and are never committed.
 */
@RunWith(AndroidJUnit4::class)
class LiveAbEval {
    private data class Scenario(val id: String, val turns: List<String>, val searches: Int, val capCents: Int)

    private val scenarios = listOf(
        Scenario("A1-union-station", listOf("What is the address of Union Station in Washington, DC?"), 3, 300),
        Scenario("A2-british-library", listOf("What is the street address of the British Library in London?"), 3, 300),
        Scenario("A3-rijksmuseum-hours", listOf("What are the opening hours of the Rijksmuseum in Amsterdam?"), 3, 300),
        Scenario("B-supplements", listOf("Which supplements have good scientific evidence of benefit for generally healthy men around age 40?"), 6, 500),
        Scenario("C-thorne-price", listOf("Where can I buy Thorne Basic Nutrients 2/Day at the best price from a reputable US retailer?"), 6, 500),
        Scenario("D-snowflake-postgres", listOf("Should a customer-facing reporting API query Snowflake directly or serve precomputed reporting data from Postgres?"), 6, 500),
        // Document F R-N(b): no city named. M4 clarifies locally before any authorization; the AI-first engine has no such step.
        Scenario("Nb-union-station-ambiguous", listOf("What is the address of Union Station?"), 3, 300),
        Scenario("E-multivitamins", listOf("Compare Thorne and Pure Encapsulations multivitamins", "Which would you pick for someone who doesn't want iron?"), 6, 500),
    ).associateBy { it.id }

    private val model = ResearchPresets.STANDARD_MODEL
    private val enabled get() = System.getProperty("liveEval.enabled") == "true"
    private val root get() = File(File(System.getProperty("liveEval.outDir") ?: "build/live-eval").parentFile, "live-eval-ab")

    @Test fun runAb() {
        assumeTrue("live evaluation runs only with -PliveEval=true", enabled)
        val sc = System.getProperty("liveEval.ab")?.let { scenarios[it] }
        assumeTrue("pick a scenario with -PliveEvalAb=<id>: ${scenarios.keys}", sc != null)
        val key = EnvApiKeyStore().get() ?: error("$LIVE_EVAL_KEY_VARIABLE is not set; nothing was sent.")
        val engines = System.getProperty("liveEval.engine")?.let { listOf(it) } ?: listOf("m4", "aifirst")
        check(LiveEvalSafety.modelStatus(model, key) == 200) { "The key cannot use $model; nothing paid was sent." }
        for (engine in engines) {
            val dir = File(root, "${sc!!.id}/$engine")
            check(System.getProperty("liveEval.rerun") == "true" || dir.listFiles()?.any { File(it, "result.json").exists() } != true) { "$dir already has a run; a rerun needs -PliveEvalRerun=true. Nothing was sent." }
            val spent = spentCents()
            check(spent + sc.capCents * sc.turns.size <= EXPERIMENT_CAP_CENTS) { "Experiment ceiling: $spent¢ measured so far; this run could add ${sc.capCents * sc.turns.size}¢. Nothing was sent." }
            val out = File(dir, "run-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()))
            check(out.mkdirs()) { "$out exists" }
            val report = when (engine) {
                "m4" -> runM4(sc, key, out)
                "aifirst" -> runAiFirst(sc, key, out)
                else -> error("unknown engine $engine")
            }
            val text = LiveEvalSafety.redact(AfJson.json.encodeToString(JsonElement.serializer(), report), key)
            check(key !in text)
            File(out, "result.json").writeText(text)
            File(out, "answer.txt").writeText(LiveEvalSafety.redact(report["turns"]!!.let { it as JsonArray }.joinToString("\n\n======== next turn ========\n\n") { t -> t.jsonObject["shown"]!!.jsonPrimitive.content }, key))
        }
    }

    /** Sum of the measured (ledger-priced) cost of every earlier A/B run, in cents, rounded up. */
    private fun spentCents(): Int = root.walkTopDown().filter { it.name == "result.json" }.sumOf { f ->
        runCatching { AfJson.json.parseToJsonElement(f.readText()).jsonObject["totalMicros"]!!.jsonPrimitive.long }.getOrDefault(0L)
    }.let { ((it + 9_999) / 10_000).toInt() }

    // ------------------------------------------------------------------ M4 (frozen engine, unchanged)

    private fun runM4(sc: Scenario, key: String, out: File): JsonObject {
        val db = fileDatabase("ab-m4-${sc.id}-${out.name}.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = AppClock.system()
        val zone = ZoneProvider { ZoneOffset.UTC }
        val ids = SequentialIds("m4")
        val keys = EnvApiKeyStore()
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
        val turns = ArrayList<JsonObject>()
        try {
            runBlocking {
                var invId: String? = null
                for (q in sc.turns) {
                    val ready = service.prepare(q, invId, null, false) as? PrepareResult.Ready ?: error("prepare rejected")
                    invId = ready.investigationId
                    val inv = store.get(invId)!!
                    val turn = inv.turns.first { it.id == ready.turn.id }
                    if (turn.state == TurnState.CLARIFYING) {
                        turns += buildJsonObject { put("question", q); put("shown", "M4 asked for clarification before authorization: ${turn.objective.clarification?.question}"); put("status", "CLARIFYING") }
                        break
                    }
                    val cfg = ResearchPresets.resolve(ResearchDepth.STANDARD, settings.current(), searches = sc.searches, limitCents = sc.capCents)
                    val context = service.contextItems(null, ResearchScope())
                    val auth = service.authorization(inv, turn, context, cfg)
                    check((auth.estimateHighMicros ?: Long.MAX_VALUE) <= sc.capCents * 10_000L) { "M4 estimate above the cap; nothing sent" }
                    val callsBefore = provider.exchanges.size
                    val t0 = System.currentTimeMillis()
                    check(service.authorize(invId, turn.id, auth, cfg) is StartResult.Started)
                    val finished = withTimeout(20 * 60_000L) { service.state.filterIsInstance<TurnRunState.Finished>().first { it.turnId == turn.id } }
                    val elapsed = System.currentTimeMillis() - t0
                    val done = store.get(invId)!!.turns.first { it.id == turn.id }
                    val rows = ledger.getAll().filter { it.runId == turn.id }
                    turns += buildJsonObject {
                        put("question", q)
                        put("objective", turn.objective.text)
                        put("outcome", finished.outcome.toString())
                        put("status", done.result?.state?.name ?: done.state.name)
                        put("elapsedMs", elapsed)
                        put("shown", done.result?.let { m4Text(it) } ?: "(no result) ${finished.outcome}")
                        put("context", JsonArray(context.filter { it.included }.map { JsonPrimitive("${it.label}: ${it.value}") }))
                        put("config", "searchBudget=${cfg.searchBudget} spendLimit=${cfg.spendLimitCents}¢ stages=${cfg.stages} outputTokens=${cfg.outputTokens} wordingCheck=${auth.wordingCheck}")
                        put("ledger", ledgerJson(rows))
                        put("exchanges", exchangesJson(provider.exchanges.drop(callsBefore)))
                        done.result?.let { put("claims", JsonArray(it.claims.map { c -> JsonPrimitive("${c.id} ${c.status} load=${c.loadBearing} :: ${c.text} :: sources=${c.support.map { e -> e.sourceN }}") })) }
                    }
                }
            }
        } finally {
            scope.cancel(); adapter.reset(); db.close()
        }
        return report(sc, "m4", turns)
    }

    /** The answer as the thread screen presents it, flattened to text: state line, lead, sections, recommendation, sources. */
    private fun m4Text(r: TurnResult): String = buildString {
        fun marks(claimIds: List<String>) = claimIds.flatMap { id -> r.claim(id)?.support.orEmpty().map { it.sourceN } }.distinct().joinToString("") { " [$it]" }
        appendLine("${r.answer.title} — ${r.answer.stateLine}")
        appendLine(r.answer.lead.joinToString(" ") { it.text.trim() + marks(it.claimIds) })
        r.answer.caveats.forEach { appendLine("Caveat: $it") }
        r.answer.sections.forEach { s ->
            appendLine("\n${s.kind.title}")
            s.items.forEach { appendLine("- ${it.text.trim()}${marks(it.claimIds)}") }
            s.rows.forEach { row -> appendLine("- ${row.criterion}: " + row.cells.entries.joinToString(" | ") { (k, v) -> "$k: ${v.text.trim()}${marks(v.claimIds)}" }) }
            s.options.forEach { appendLine("- $it") }
        }
        r.answer.recommendation?.let { rec ->
            appendLine("\nRecommendation${if (rec.optional) " (optional)" else ""}: ${rec.text}")
            rec.reasons.forEach { appendLine("Because: ${it.text.trim()}${marks(it.claimIds)}") }
            rec.assumptions.forEach { appendLine("Assumes: $it") }
            rec.flipConditions.forEach { appendLine("Changes if: $it") }
        }
        // The thread screen lists only sources a finding cites (citedCount > 0); the rest of the registry is not shown.
        if (r.sources.any { it.citedCount > 0 }) {
            appendLine("\nSources")
            r.sources.filter { it.citedCount > 0 }.forEach { appendLine("[${it.n}] ${it.title} — ${it.url}" + (it.pageAge?.let { a -> " (search result age: $a)" } ?: "")) }
        }
        r.failure?.let { appendLine("\nFailure: $it") }
    }.trimEnd()

    // ------------------------------------------------------------------ AI-first (experiment)

    private fun runAiFirst(sc: Scenario, key: String, out: File): JsonObject {
        val db = fileDatabase("ab-af-${sc.id}-${out.name}.db")
        val ledger = RoomUsageLedger(db)
        val provider = RecordingCallProvider(AnthropicResearchCalls(AnthropicResearchProvider()))
        val store = FileAfThreadStore(File(out, "threads"))
        val clock = AppClock.system()
        val engine = AiFirstResearch(provider, ledger, store, clock, SequentialIds("af"))
        // The same context line M4's card shares by default: today's date and language.
        val date = clock.now().atZone(ZoneOffset.UTC).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.ENGLISH))
        val context = listOf("Today's date and language: $date · ${Locale.US.getDisplayName(Locale.ENGLISH)}")
        val turns = ArrayList<JsonObject>()
        try {
            runBlocking {
                for (q in sc.turns) {
                    val auth = engine.authorize(store.get("thread"), q, context, AfConfig(model, sc.searches, sc.capCents))
                    check((auth.estimateHighMicros ?: Long.MAX_VALUE) <= sc.capCents * 10_000L) { "AI-first estimate above the cap; nothing sent" }
                    val callsBefore = provider.exchanges.size
                    val t0 = System.currentTimeMillis()
                    val t = engine.run("thread", auth, key)
                    val elapsed = System.currentTimeMillis() - t0
                    turns += buildJsonObject {
                        put("question", q)
                        put("status", t.status.name)
                        put("elapsedMs", elapsed)
                        put("shown", AfRender.text(t))
                        put("context", JsonArray(context.map { JsonPrimitive(it) }))
                        put("config", "searchBudget=${sc.searches} spendLimit=${sc.capCents}¢ maxOutputTokens=${auth.config.maxOutputTokens} prompt=v${com.lifeadmin.autopilot.ai.aifirst.AfPrompt.VERSION}")
                        put("ledger", ledgerJson(ledger.getAll().filter { it.runId == t.id }))
                        put("exchanges", exchangesJson(provider.exchanges.drop(callsBefore)))
                        put("turn", AfJson.json.encodeToJsonElement(com.lifeadmin.autopilot.ai.aifirst.AfTurn.serializer(), t))
                    }
                }
            }
        } finally {
            db.close()
        }
        return report(sc, "aifirst", turns)
    }

    // ------------------------------------------------------------------ report

    private fun report(sc: Scenario, engine: String, turns: List<JsonObject>): JsonObject {
        val micros = turns.sumOf { t -> (t["ledger"] as? JsonArray).orEmpty().sumOf { it.jsonObject["estimatedMicros"]!!.jsonPrimitive.long } }
        return buildJsonObject {
            put("scenario", sc.id); put("engine", engine); put("model", model); put("ranAtUtc", Instant.now().toString())
            put("searchBudget", sc.searches); put("spendLimitCents", sc.capCents)
            put("totalMicros", micros)
            put("turns", JsonArray(turns))
        }
    }

    private fun ledgerJson(rows: List<UsageRecord>) = buildJsonArray {
        rows.sortedBy { it.startedAt }.forEach { r ->
            add(buildJsonObject {
                put("stage", r.stage ?: ""); put("model", r.model); put("outcome", r.outcome.name); put("usageStatus", r.usageStatus.name)
                put("inputTokens", r.usage?.inputTokens ?: -1); put("outputTokens", r.usage?.outputTokens ?: -1); put("cacheReadTokens", r.usage?.cacheReadTokens ?: -1)
                put("cacheWriteTokens", (r.usage?.cacheWrite5mTokens ?: 0) + (r.usage?.cacheWrite1hTokens ?: 0)); put("webSearches", r.usage?.webSearches ?: -1)
                put("estimatedMicros", r.estimatedMicros ?: 0); put("durationMs", r.durationMs ?: -1)
            })
        }
    }

    private fun exchangesJson(xs: List<Exchange>) = buildJsonArray {
        xs.forEach { x ->
            add(buildJsonObject {
                put("maxSearches", x.call.maxSearches); put("maxTokens", x.call.maxTokens); put("continuesPausedTurn", x.call.prior != null)
                put("systemSha256", LiveEvalSafety.sha256(x.call.system)); put("user", x.call.user)
                when (val r = x.result) {
                    is CallResult.Done -> {
                        put("stop", r.stop.name); put("elapsedMs", r.elapsedMs); put("shape", r.shape?.toString() ?: "")
                        put("segments", buildJsonArray { r.segments.forEach { s -> add(buildJsonObject { put("text", s.text); put("citations", buildJsonArray { s.citations.forEach { c -> add(buildJsonObject { put("url", c.url); put("title", c.title ?: ""); put("citedText", c.citedText) }) } }) }) } })
                        put("searches", buildJsonArray { r.searches.forEach { q -> add(buildJsonObject { put("query", q.query); put("error", q.errorCode ?: ""); put("results", buildJsonArray { q.results.forEach { res -> add(buildJsonObject { put("url", res.url); put("title", res.title); put("pageAge", res.pageAge ?: "") }) } }) }) } })
                    }
                    is CallResult.Failed -> { put("stop", "FAILED"); put("error", r.error.technical); put("searchesSeen", r.searchesSeen); put("elapsedMs", r.elapsedMs) }
                }
            })
        }
    }

    companion object {
        /** The owner's ceiling for the whole experiment: $25 of estimated usage. */
        const val EXPERIMENT_CAP_CENTS = 2_500
    }
}
