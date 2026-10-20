package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.NetworkStatus
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchGate
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.security.ApiKeyStore
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.CostCalculator
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.ai.usage.UsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageRecord
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the preflight assembles before Start. Nothing leaves the device until [DeepResearchService.start]. */
data class SessionDraft(
    val request: String,
    val template: DeliverableTemplate,
    val requirements: List<Requirement>,
    val config: RunConfig,
    val scope: ResearchScope = ResearchScope(),
)

enum class RunEventKind { SENT, STAGE_DONE, STAGE_SKIPPED, CONTINUED, BUDGET, STOPPED, FAILED }

data class RunEvent(val atEpochMs: Long, val kind: RunEventKind, val text: String)

/** The single run the app may have in flight, observable from any screen. Counts are only ever what the provider reported. */
sealed interface DeepRunState {
    data object Idle : DeepRunState

    data class Running(
        val runId: String,
        val sessionId: String,
        val taskId: String,
        val title: String,
        val config: RunConfig,
        val startedAt: Instant,
        val stage: StageKind?,
        val stageIndex: Int,
        val stageCount: Int,
        val events: List<RunEvent>,
        val searchesRun: Int,
        val sourcesSeen: Int,
        /** Recorded cost of calls that already finished, plus searches seen so far (estimate). */
        val spentMicros: Long,
        val spentIsFloor: Boolean,
        val stopRequested: Boolean = false,
        val continuation: Boolean = false,
    ) : DeepRunState

    data class Finished(val runId: String, val sessionId: String, val taskId: String, val result: RunResult) : DeepRunState
}

sealed interface RunResult {
    data class Saved(val sessionId: String, val outcome: SessionOutcome) : RunResult
    data class Failed(val error: ResearchError) : RunResult
    data object Discarded : RunResult
}

sealed interface StartResult2 {
    data class Started(val runId: String, val sessionId: String) : StartResult2
    data class Rejected(val error: ResearchError) : StartResult2
}

/**
 * M3A.2 orchestrator: gates the request, runs the planned stages one HTTP call at a time with the
 * budget checked before each, merges structured output while preserving provider citations, records
 * every call in the usage ledger, and saves the session. It never writes tasks; proposals are applied
 * later through [Research2Actions] after review.
 */
class DeepResearchService(
    private val settings: SettingsRepository,
    private val keyStore: ApiKeyStore,
    private val planning: AiPlanningService,
    private val provider: ResearchCallProvider,
    private val tasks: TaskRepository,
    private val store: ResearchSessionStore,
    private val ledger: UsageLedger,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val ids: IdGenerator,
    private val scope: CoroutineScope,
    private val network: NetworkStatus = NetworkStatus.UNKNOWN,
    private val locale: () -> Locale = { Locale.getDefault() },
) {
    private val _state = MutableStateFlow<DeepRunState>(DeepRunState.Idle)
    val state: StateFlow<DeepRunState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var keepPartialOnStop = false

    // ------------------------------------------------------------------ gating & local prep

    suspend fun gate(s: AppSettings? = null): ResearchGate {
        val st = s ?: settings.current()
        return ResearchGate(planning.availability(st), st.researchExplainerVersion >= ResearchConfig.EXPLAINER_VERSION, requestsToday(st), network.isOnline(), cap = Research2Config.MAX_SESSIONS_PER_DAY)
    }

    suspend fun acceptExplainer() = settings.update { it.copy(researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION) }

    suspend fun requestsToday(s: AppSettings? = null): Int {
        val st = s ?: settings.current()
        return if (st.researchUsageDay == today()) st.researchUsageCount else 0
    }

    fun localeLine(): String {
        val date = clock.now().atZone(zoneProvider.current()).toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.ENGLISH))
        return "$date · ${locale().getDisplayName(Locale.ENGLISH)}"
    }

    /** Local, free: what the request asks for. */
    fun extract(request: String): RequirementExtractor.Result = RequirementExtractor.extract(request)

    fun shared(details: TaskDetails, scope: ResearchScope): SharedContext = SharedContext(
        title = if (scope.shareTitle) details.task.title else null,
        notes = if (scope.shareNotes) details.task.notes?.takeIf { it.isNotBlank() } else null,
        steps = if (scope.shareSteps) details.steps.map { it.title } else null,
        localeLine = if (scope.shareLocale) localeLine() else null,
    )

    // ------------------------------------------------------------------ start / continue

    suspend fun start(taskId: String, draft: SessionDraft): StartResult2 {
        val request = draft.request.trim()
        if (request.isEmpty()) return StartResult2.Rejected(ResearchError.EmptyQuestion)
        if (request.length > Research2Config.MAX_REQUEST_CHARS) return StartResult2.Rejected(ResearchError.QuestionTooLong)
        if (draft.requirements.isEmpty()) return StartResult2.Rejected(ResearchError.Unexpected("no requirements"))
        if (_state.value is DeepRunState.Running) return StartResult2.Rejected(ResearchError.Busy)
        val gate = gate()
        gate.blocker?.let { return StartResult2.Rejected(it) }
        val key = keyStore.get() ?: return StartResult2.Rejected(ResearchError.NoApiKey)
        val details = tasks.observeDetails(taskId).first() ?: return StartResult2.Rejected(ResearchError.Unexpected("task missing"))
        if (!reserve()) return StartResult2.Rejected(ResearchError.DailyLimit)
        val shared = shared(details, draft.scope)
        val sessionId = ids.newId()
        val runId = ids.newId()
        val now = clock.now()
        val content = SessionContent(
            title = fallbackTitle(draft.requirements, draft.template), request = request, template = draft.template,
            requirements = draft.requirements, coverage = draft.requirements.map { Coverage(it.id, RequirementStatus.PENDING) },
            outcome = SessionOutcome.INTERRUPTED, shared = shared.summary(),
        )
        val session = ResearchSession(sessionId, taskId, now, now, content)
        launch(session, runId, draft.config, key, RunKind.INITIAL, shared, focus = emptyList(), note = null)
        return StartResult2.Started(runId, sessionId)
    }

    /** New run on a saved session with a new approved budget; requirements, findings and source ids are kept. */
    suspend fun continueSession(sessionId: String, config: RunConfig, focus: List<String>, note: String?): StartResult2 {
        if (_state.value is DeepRunState.Running) return StartResult2.Rejected(ResearchError.Busy)
        val gate = gate()
        gate.blocker?.let { return StartResult2.Rejected(it) }
        val key = keyStore.get() ?: return StartResult2.Rejected(ResearchError.NoApiKey)
        val session = store.get(sessionId) ?: return StartResult2.Rejected(ResearchError.Unexpected("session missing"))
        if (!reserve()) return StartResult2.Rejected(ResearchError.DailyLimit)
        val runId = ids.newId()
        val focusReqs = session.content.requirements.filter { it.id in focus }
        launch(session, runId, config, key, RunKind.CONTINUATION, shared = null, focus = focusReqs, note = note?.take(300))
        return StartResult2.Started(runId, sessionId)
    }

    /** Stop sheet: keep what was gathered (saved as Stopped) or discard it (nothing saved; usage is still recorded). */
    fun stop(runId: String, keepPartial: Boolean) {
        val s = _state.value as? DeepRunState.Running ?: return
        if (s.runId != runId) return
        keepPartialOnStop = keepPartial
        _state.update { st -> if (st is DeepRunState.Running && st.runId == runId) st.copy(stopRequested = true) else st }
        job?.cancel(CancellationException("stopped by user"))
    }

    fun dismiss(runId: String) { _state.update { s -> if (s is DeepRunState.Finished && s.runId == runId) DeepRunState.Idle else s } }

    private suspend fun cancelActive(taskId: String? = null, reason: String) {
        val running = _state.value as? DeepRunState.Running ?: return
        if (taskId != null && running.taskId != taskId) return
        keepPartialOnStop = false
        val j = job ?: return
        j.cancel(CancellationException(reason))
        j.join()
    }

    suspend fun deleteSession(id: String) = store.delete(id)
    suspend fun deleteForTask(taskId: String) { cancelActive(taskId, "research deleted"); store.deleteForTask(taskId) }
    suspend fun deleteAll() { cancelActive(reason = "research deleted"); store.deleteAll() }

    fun modelLabel(model: String): String = AiConfig.label(model)

    /** App start: in-flight ledger rows from a previous process are interrupted; nothing is re-sent. */
    suspend fun onAppStart(): Int = ledger.settleInterrupted()

    // ------------------------------------------------------------------ the run

    private class RunCtx(
        var session: ResearchSession,
        val runId: String,
        val config: RunConfig,
        val key: String,
        val kind: RunKind,
        val shared: SharedContext?,
        val focus: List<Requirement>,
        val note: String?,
        val registry: SourceRegistry2,
    ) {
        var remaining = config.searchBudget
        var searchesRun = 0
        val stages = ArrayList<StageRecord>()
        var usage = RunUsage()
        var merged = false
        var spentMicros = 0L
        var spentFloor = false
        var truncated = false
        var ending: SessionAssembler.Ending = SessionAssembler.Ending.NORMAL
        var technical: String? = null
        var failure: ResearchError? = null
        val queriesThisRun = ArrayList<String>()
    }

    private fun launch(session: ResearchSession, runId: String, config: RunConfig, key: String, kind: RunKind, shared: SharedContext?, focus: List<Requirement>, note: String?) {
        val startedAt = clock.now()
        _state.value = DeepRunState.Running(
            runId, session.id, session.taskId, session.content.title, config, startedAt, null, 0, config.stages.size, emptyList(), 0, session.content.sources.size, 0L, false,
            continuation = kind == RunKind.CONTINUATION,
        )
        val ctx = RunCtx(session, runId, config, key, kind, shared, focus, note, SourceRegistry2(session.content.sources, runId))
        keepPartialOnStop = false
        job = scope.launch {
            val result: RunResult = try {
                runStages(ctx)
                finish(ctx)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    ctx.ending = SessionAssembler.Ending.STOPPED
                    ctx.technical = "STOPPED BY YOU"
                    event(runId, RunEventKind.STOPPED, "Stopped. Searches that already ran may be billed.")
                    if (keepPartialOnStop && ctx.merged) finish(ctx) else RunResult.Failed(ResearchError.Stopped(ctx.searchesRun))
                }
            } catch (e: Exception) {
                RunResult.Failed(ResearchError.Unexpected(e.javaClass.simpleName))
            }
            _state.update { s -> if (s is DeepRunState.Running && s.runId == runId) DeepRunState.Finished(runId, session.id, session.taskId, result) else s }
        }
    }

    private suspend fun runStages(ctx: RunCtx) {
        val total = ctx.config.searchBudget
        val stages = ctx.config.stages
        for ((index, stage) in stages.withIndex()) {
            _state.update { s -> if (s is DeepRunState.Running && s.runId == ctx.runId) s.copy(stage = stage, stageIndex = index) else s }
            val content = ctx.session.content
            // Decide locally whether the stage can help at all.
            val gaps = SessionAssembler.gaps(content)
            val priceGaps = SessionAssembler.priceGaps(content)
            val skip: String? = when (stage) {
                StageKind.GATHER -> null
                StageKind.FILL_GAPS -> if (gaps.isEmpty() && priceGaps.isEmpty()) "nothing left open" else if (ctx.remaining <= 0) "no searches left" else null
                StageKind.CROSS_CHECK -> if (content.body.candidates.none { it.included } && content.conflicts.isEmpty()) "nothing to cross-check" else if (ctx.remaining <= 0) "no searches left" else null
                StageKind.SYNTHESIZE -> if (!ctx.merged) "nothing gathered to write up" else null
            }
            if (skip != null) {
                ctx.stages += StageRecord(stage, StageResult.SKIPPED, 0, 0, note = skip)
                event(ctx.runId, RunEventKind.STAGE_SKIPPED, "${stage.label}: skipped — $skip")
                if (skip == "no searches left" && gaps.isNotEmpty()) ctx.ending = SessionAssembler.Ending.BUDGET_SEARCHES
                continue
            }
            var allowance = if (stage == StageKind.SYNTHESIZE) 0 else ResearchModes.stageShare(stage, ctx.remaining, total, stages.size).coerceAtMost(ctx.remaining)
            if (stage != StageKind.SYNTHESIZE && allowance <= 0) {
                ctx.stages += StageRecord(stage, StageResult.BUDGET, 0, 0, note = "no searches left")
                ctx.ending = SessionAssembler.Ending.BUDGET_SEARCHES
                event(ctx.runId, RunEventKind.BUDGET, "${stage.label}: no searches left in the budget")
                continue
            }
            // Soft dollar limit: estimate before dispatch, trim the allowance, or stop launching paid calls.
            ctx.config.spendLimitCents?.let { limitCents ->
                val limitMicros = limitCents * 10_000L
                fun low(n: Int) = CostCalculator.estimateRun(ctx.config.model, n, if (stage == StageKind.SYNTHESIZE) ctx.config.outputTokens / 2 else ctx.config.outputTokens)?.lowMicros ?: 0L
                if (ctx.spentMicros >= limitMicros) { budgetStop(ctx, stage, "spending limit reached"); return }
                while (allowance > 0 && ctx.spentMicros + low(allowance) > limitMicros) allowance--
                if (stage != StageKind.SYNTHESIZE && allowance == 0) { budgetStop(ctx, stage, "the next search would pass the spending limit"); return }
                if (stage == StageKind.SYNTHESIZE && ctx.spentMicros + low(0) > limitMicros) { budgetStop(ctx, stage, "the write-up would pass the spending limit"); return }
            }
            val user = when (stage) {
                StageKind.GATHER -> if (ctx.kind == RunKind.CONTINUATION) Research2Prompt.continuation(content, ctx.focus, allowance, ctx.note) else Research2Prompt.gather(content, ctx.config, ctx.shared ?: SharedContext(), allowance)
                StageKind.FILL_GAPS -> Research2Prompt.fillGaps(content, gaps, priceGaps, allowance)
                StageKind.CROSS_CHECK -> Research2Prompt.crossCheck(content, allowance)
                StageKind.SYNTHESIZE -> Research2Prompt.synthesize(content)
            }
            val ok = runStage(ctx, stage, allowance, user, cache = stages.size > 1)
            if (!ok) return
            if (stage == StageKind.GATHER && !ctx.merged) {
                // Nothing usable came back from the first pass: don't spend more on follow-ups.
                ctx.failure = ResearchError.Malformed("no usable blocks"); ctx.technical = ctx.failure?.technical; ctx.ending = SessionAssembler.Ending.FAILED
                event(ctx.runId, RunEventKind.FAILED, "${stage.label}: the answer had nothing usable; stopping here")
                return
            }
            if (ctx.remaining <= 0 && index < stages.lastIndex && SessionAssembler.gaps(ctx.session.content).isNotEmpty()) ctx.ending = SessionAssembler.Ending.BUDGET_SEARCHES
        }
    }

    private suspend fun budgetStop(ctx: RunCtx, stage: StageKind, why: String) {
        ctx.stages += StageRecord(stage, StageResult.BUDGET, 0, 0, note = why)
        ctx.ending = SessionAssembler.Ending.BUDGET_SPEND
        event(ctx.runId, RunEventKind.BUDGET, "${stage.label}: not started — $why. One in-flight call can still exceed a limit; nothing else is launched.")
    }

    /** One stage = one or two HTTP calls (pause_turn). Returns false when the run must end here. */
    private suspend fun runStage(ctx: RunCtx, stage: StageKind, allowance: Int, user: String, cache: Boolean): Boolean {
        var prior: Any? = null
        var calls = 0
        var searchesThisStage = 0
        var left = allowance
        val queries = ArrayList<String>()
        while (true) {
            val call = ModelCall(ctx.config.model, Research2Prompt.SYSTEM, user, ctx.config.outputTokens, left, cacheSystem = cache, prior = prior)
            event(ctx.runId, RunEventKind.SENT, if (prior == null) "${stage.label}: request sent (up to $left ${plural(left, "search", "searches")})" else "${stage.label}: continued within the same budget")
            val record = UsageRecord(ids.newId(), if (ctx.kind == RunKind.CONTINUATION) UsageFeature.RESEARCH_CONTINUATION else UsageFeature.RESEARCH, ctx.config.model, clock.now(), ctx.runId, ctx.session.id, ctx.session.taskId, stage.name)
            ledger.record(record)
            calls++
            val result = try {
                provider.call(call, ctx.key)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { ledger.record(UsageRecord.priced(record, null, CallOutcome.STOPPED, null)) }
                ctx.spentFloor = true
                throw e
            }
            when (result) {
                is CallResult.Failed -> {
                    val outcome = if (notBilled(result.error)) CallOutcome.FAILED_NOT_BILLED else CallOutcome.FAILED_UNKNOWN
                    ledger.record(UsageRecord.priced(record, result.usage, outcome, result.elapsedMs))
                    if (outcome == CallOutcome.FAILED_UNKNOWN) ctx.spentFloor = true
                    ctx.stages += StageRecord(stage, StageResult.FAILED, allowance, searchesThisStage, queries, result.error.technical, calls)
                    ctx.failure = result.error
                    ctx.technical = result.error.technical
                    ctx.ending = if (ctx.merged) SessionAssembler.Ending.FAILED_AFTER_PARTIAL else SessionAssembler.Ending.FAILED
                    event(ctx.runId, RunEventKind.FAILED, "${stage.label}: ${result.error.headline}")
                    return false
                }
                is CallResult.Done -> {
                    val outcome = when (result.stop) { CallStop.MAX_TOKENS -> CallOutcome.TRUNCATED; else -> CallOutcome.COMPLETE }
                    ledger.record(UsageRecord.priced(record, result.usage, outcome, result.elapsedMs, result.providerMessageId))
                    val priced = CostCalculator.price(ctx.config.model, result.usage)
                    ctx.spentMicros += priced.estimate?.micros ?: 0L
                    if (priced.partial) ctx.spentFloor = true
                    ctx.usage = ctx.usage.copy(
                        inputTokens = ctx.usage.inputTokens + (result.usage.inputTokens ?: 0), outputTokens = ctx.usage.outputTokens + (result.usage.outputTokens ?: 0),
                        cacheWriteTokens = ctx.usage.cacheWriteTokens + (result.usage.cacheWrite5mTokens ?: 0) + (result.usage.cacheWrite1hTokens ?: 0),
                        cacheReadTokens = ctx.usage.cacheReadTokens + (result.usage.cacheReadTokens ?: 0),
                        webSearches = ctx.usage.webSearches + (result.usage.webSearches ?: 0), elapsedMs = ctx.usage.elapsedMs + result.elapsedMs,
                        complete = ctx.usage.complete && !result.usage.anyUnknown,
                    )
                    val n = result.usage.webSearches ?: result.searches.size
                    searchesThisStage += n; ctx.searchesRun += n; ctx.remaining = (ctx.remaining - n).coerceAtLeast(0); left = (left - n).coerceAtLeast(0)
                    if (result.stop == CallStop.REFUSAL) {
                        ctx.stages += StageRecord(stage, StageResult.FAILED, allowance, searchesThisStage, queries, "STOP REASON: REFUSAL", calls)
                        ctx.failure = ResearchError.Refused(result.refusalCategory); ctx.technical = ctx.failure?.technical
                        ctx.ending = if (ctx.merged) SessionAssembler.Ending.FAILED_AFTER_PARTIAL else SessionAssembler.Ending.FAILED
                        event(ctx.runId, RunEventKind.FAILED, "${stage.label}: Claude declined this request")
                        return false
                    }
                    val out = ContractParser.parse(result.segments, result.searches, ctx.registry, ctx.session.content.requirements, clock.now().toEpochMilli(), ctx.session.content.body.candidates)
                    queries += out.queries
                    ctx.queriesThisRun += out.queries
                    if (out.answer.isNotEmpty() || out.coverage.isNotEmpty() || out.candidates.isNotEmpty() || out.picks.isNotEmpty() || out.claims.isNotEmpty() || out.steps.isNotEmpty()) ctx.merged = true
                    ctx.session = ctx.session.copy(content = SessionAssembler.merge(ctx.session.content, out, ctx.registry.sources()))
                    updateRunning(ctx)
                    if (result.stop == CallStop.MAX_TOKENS) { ctx.truncated = true; ctx.ending = SessionAssembler.Ending.TRUNCATED }
                    if (result.stop == CallStop.PAUSE_TURN && prior == null && left > 0 && calls <= Research2Config.MAX_PAUSE_CONTINUATIONS) {
                        prior = result.paused
                        event(ctx.runId, RunEventKind.CONTINUED, "${stage.label}: Anthropic paused the turn; continuing with $left ${plural(left, "search", "searches")} left")
                        continue
                    }
                    ctx.stages += StageRecord(stage, StageResult.DONE, allowance, searchesThisStage, queries, if (result.stop == CallStop.PAUSE_TURN) "paused by Anthropic; not continued again" else null, calls)
                    event(ctx.runId, RunEventKind.STAGE_DONE, "${stage.label}: done · $searchesThisStage ${plural(searchesThisStage, "search", "searches")} · ${ctx.registry.sources().size} sources so far")
                    return true
                }
            }
        }
    }

    private fun updateRunning(ctx: RunCtx) {
        _state.update { s ->
            if (s is DeepRunState.Running && s.runId == ctx.runId) s.copy(searchesRun = ctx.searchesRun, sourcesSeen = ctx.registry.sources().size, spentMicros = ctx.spentMicros, spentIsFloor = ctx.spentFloor, title = ctx.session.content.title) else s
        }
    }

    private suspend fun finish(ctx: RunCtx): RunResult {
        if (!ctx.merged && ctx.failure != null) return RunResult.Failed(ctx.failure!!)
        if (!ctx.merged && ctx.ending == SessionAssembler.Ending.STOPPED) return RunResult.Failed(ResearchError.Stopped(ctx.searchesRun))
        var content = SessionAssembler.settlePending(ctx.session.content)
        val outcome = SessionAssembler.outcome(content, ctx.ending)
        val now = clock.now()
        content = content.copy(
            proposals = ProposalBuilder.complete(content, now.toEpochMilli()),
            outcome = outcome,
            runs = content.runs + RunRecord(ctx.runId, ctx.kind, now.toEpochMilli(), ctx.config.mode, ctx.config.model, ctx.config.searchBudget, ctx.config.spendLimitCents, ctx.stages.toList(), ctx.usage, outcome, ctx.technical),
        )
        if (!ctx.merged) {
            // Nothing usable came back although the call "succeeded": say so rather than save an empty result.
            return RunResult.Failed(ResearchError.Malformed("no usable blocks"))
        }
        val saved = ctx.session.copy(content = content, updatedAt = now)
        store.save(saved)
        return RunResult.Saved(saved.id, outcome)
    }

    private fun event(runId: String, kind: RunEventKind, text: String) {
        _state.update { s -> if (s is DeepRunState.Running && s.runId == runId) s.copy(events = (s.events + RunEvent(clock.now().toEpochMilli(), kind, text)).takeLast(40)) else s }
    }

    private fun notBilled(e: ResearchError): Boolean = when (e) {
        ResearchError.Unauthorized, ResearchError.Forbidden, ResearchError.WebSearchNotEnabled, ResearchError.OutOfCredit, is ResearchError.BadRequest, is ResearchError.RateLimited, ResearchError.Offline -> true
        else -> false
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    private fun fallbackTitle(reqs: List<Requirement>, template: DeliverableTemplate): String {
        val items = reqs.filter { it.type == RequirementType.ITEM || it.type == RequirementType.USER_ADDED }.map { it.label }
        val base = when {
            items.isEmpty() -> "Research"
            items.size == 1 -> items[0].replaceFirstChar { it.uppercase() }
            else -> items.take(3).joinToString(", ") + if (items.size > 3) " + ${items.size - 3} more" else ""
        }
        val prefix = when (template) { DeliverableTemplate.SHORTLIST -> "Shortlist: "; DeliverableTemplate.PICK_PER_ITEM -> "Picks: "; DeliverableTemplate.HOW_TO -> "How to: "; DeliverableTemplate.CHECKLIST -> "Checklist: "; DeliverableTemplate.QUICK_ANSWER -> "" }
        return (prefix + base).take(Research2Config.MAX_TITLE_CHARS)
    }

    private suspend fun reserve(): Boolean {
        var allowed = false
        val day = today()
        settings.update { s ->
            val used = if (s.researchUsageDay == day) s.researchUsageCount else 0
            if (used >= Research2Config.MAX_SESSIONS_PER_DAY) s else { allowed = true; s.copy(researchUsageDay = day, researchUsageCount = used + 1) }
        }
        return allowed
    }

    private fun today(): String = clock.now().atZone(zoneProvider.current()).toLocalDate().toString()

    companion object {
        /** Estimate for the preflight / continue sheet. */
        fun estimate(config: RunConfig): UsdEstimate? = config.estimate()
    }
}
