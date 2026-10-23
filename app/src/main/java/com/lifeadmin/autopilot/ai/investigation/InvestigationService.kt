package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.AiAvailability
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.NetworkStatus
import com.lifeadmin.autopilot.ai.context.ContextItem
import com.lifeadmin.autopilot.ai.context.FrozenPayload
import com.lifeadmin.autopilot.ai.context.InheritedFinding
import com.lifeadmin.autopilot.ai.context.TurnPayload
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchGate
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.CallClass
import com.lifeadmin.autopilot.ai.research2.CallDiagnostic
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.CallStop
import com.lifeadmin.autopilot.ai.research2.ModelCall
import com.lifeadmin.autopilot.ai.research2.ReplyShape
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.ResearchCallProvider
import com.lifeadmin.autopilot.ai.research2.StageKind
import com.lifeadmin.autopilot.ai.research2.StageRecord
import com.lifeadmin.autopilot.ai.research2.StageResult
import com.lifeadmin.autopilot.ai.security.ApiKeyStore
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.CostCalculator
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.ai.usage.UsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageRecord
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class TurnEventKind { SENT, STAGE_DONE, STAGE_SKIPPED, CONTINUED, BUDGET, STOPPED, FAILED, CHECKPOINT }

data class TurnEvent(val atEpochMs: Long, val kind: TurnEventKind, val text: String)

/** The one turn the app may have in flight, observable from any screen. Counts are only what the provider reported. */
sealed interface TurnRunState {
    data object Idle : TurnRunState

    data class Running(
        val turnId: String,
        val investigationId: String,
        val taskId: String?,
        val title: String,
        val config: ResearchRunConfig,
        val startedAt: Instant,
        val stage: StageKind?,
        val stageIndex: Int,
        val stageCount: Int,
        val events: List<TurnEvent>,
        val searchesRun: Int,
        val sourcesSeen: Int,
        val claimsHeld: Int,
        val establishedClaims: Int,
        val spentMicros: Long,
        val spentIsFloor: Boolean,
        val stopRequested: Boolean = false,
        val continuation: Boolean = false,
        val callStartedAtEpochMs: Long? = null,
        val callTimeoutSeconds: Int? = null,
        val callsSent: Int = 0,
        /** Evidence from completed calls has been written to the turn; a later failure keeps it. */
        val checkpointed: Boolean = false,
    ) : TurnRunState

    data class Finished(val turnId: String, val investigationId: String, val taskId: String?, val outcome: TurnOutcome, val continuation: Boolean = false) : TurnRunState
}

sealed interface TurnOutcome {
    /** The turn was written with a result (complete, partial, stopped-with-findings, failed-after-partial). */
    data class Saved(val turnState: TurnState, val resultState: ResultState, val ending: TurnEnding) : TurnOutcome

    /** Nothing usable came back; the turn record says what did. */
    data class Failed(val error: ResearchError, val diagnostics: List<CallDiagnostic> = emptyList()) : TurnOutcome

    /** Stopped and discarded by the person; usage is still recorded. */
    data object Discarded : TurnOutcome
}

sealed interface PrepareResult {
    data class Ready(val investigationId: String, val turn: Turn) : PrepareResult
    data class Rejected(val error: ResearchError) : PrepareResult
}

sealed interface StartResult {
    data class Started(val turnId: String, val investigationId: String) : StartResult
    data class Rejected(val error: ResearchError) : StartResult
}

/**
 * The Investigation Thread orchestrator (M4). Objective first; authorization written before any paid
 * call; one turn at a time; bounded batches with a checkpoint after every call; every call in the
 * usage ledger; stop, failure and interruption keep what was gathered. It never writes tasks:
 * proposals go through the canonical Proposal Review after the person chooses them.
 */
class InvestigationService(
    private val settings: SettingsRepository,
    private val keyStore: ApiKeyStore,
    private val planning: AiPlanningService,
    private val provider: ResearchCallProvider,
    private val tasks: TaskRepository,
    private val store: InvestigationRepository,
    private val ledger: UsageLedger,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val ids: IdGenerator,
    private val scope: CoroutineScope,
    private val network: NetworkStatus = NetworkStatus.UNKNOWN,
    private val locale: () -> Locale = { Locale.getDefault() },
) {
    private val _state = MutableStateFlow<TurnRunState>(TurnRunState.Idle)
    val state: StateFlow<TurnRunState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var keepPartialOnStop = false
    private val launching = AtomicBoolean(false)

    // ------------------------------------------------------------------ gating & context

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

    /**
     * The context items the authorization card offers (RES-161): for a task-linked thread the task's
     * title, notes and steps, each with its own switch; for any thread the date line. Nothing else exists
     * as an input, so nothing else can be sent.
     */
    suspend fun contextItems(taskId: String?, scope: ResearchScope = ResearchScope()): List<ContextItem> = buildList {
        val details: TaskDetails? = taskId?.let { tasks.observeDetails(it).first() }
        if (details != null) {
            add(ContextItem("title", "Task title", details.task.title, included = scope.shareTitle))
            details.task.notes?.takeIf { it.isNotBlank() }?.let { add(ContextItem("notes", "Task notes", it, included = scope.shareNotes)) }
            if (details.steps.isNotEmpty()) add(ContextItem("steps", "Current steps", details.steps.joinToString("; ") { it.title }, included = scope.shareSteps))
        }
        add(ContextItem("today", "Today's date and language", localeLine(), included = scope.shareLocale))
    }

    // ------------------------------------------------------------------ objective (RES-010–025)

    /**
     * Establishes the objective before any authorization exists. The local path is free and offline.
     * With [useProvider] and general AI consent in place, a small no-search call refines it; that call is
     * ledgered as OBJECTIVE_PREP and never counts as investigation spend (RES-023). A clarification
     * question leaves the turn in CLARIFYING; nothing paid can start from there.
     */
    suspend fun prepare(question: String, investigationId: String? = null, taskId: String? = null, useProvider: Boolean = false, resumeOf: String? = null): PrepareResult {
        val q = question.trim()
        if (q.isEmpty()) return PrepareResult.Rejected(ResearchError.EmptyQuestion)
        if (q.length > Research2Config.MAX_REQUEST_CHARS) return PrepareResult.Rejected(ResearchError.QuestionTooLong)
        val existing = investigationId?.let { store.get(it) ?: return PrepareResult.Rejected(ResearchError.Unexpected("investigation missing")) }
        if (existing?.running == true) return PrepareResult.Rejected(ResearchError.Busy)
        val now = clock.now()
        val turnId = ids.newId()
        val invId = existing?.id ?: ids.newId()
        val linkedTask = existing?.taskId ?: taskId
        val earlier = existing?.latestTurn?.objective
        val kind = when { resumeOf != null -> TurnKind.RESUME; existing != null -> TurnKind.CONTINUATION; else -> TurnKind.INITIAL }
        var objective = if (earlier != null && resumeOf == null) ObjectivePreparer.followUp(q, earlier) else ObjectivePreparer.local(q)
        if (resumeOf != null) existing?.turns?.firstOrNull { it.id == resumeOf }?.let { objective = it.objective }
        if (useProvider) {
            val st = settings.current()
            val availability = planning.availability(st)
            val key = keyStore.get()
            if (availability is AiAvailability.Ready && key != null && network.isOnline() != false) {
                objective = prepareWithProvider(q, earlier, invId, turnId, linkedTask, key, st) ?: objective
            }
        }
        val turnState = if (objective.established) TurnState.AUTHORIZING else TurnState.CLARIFYING
        val index = existing?.turns?.maxOfOrNull { it.index }?.plus(1) ?: 0
        val turn = Turn(turnId, index, kind, turnState, now.toEpochMilli(), objective, resumeOf = resumeOf, excludedClaimIds = existing?.meta?.excludedClaimIds.orEmpty())
        if (existing == null) {
            val title = AnswerComposer.fallbackTitle(objective).ifBlank { q.take(80) }
            store.save(Investigation(invId, if (linkedTask != null) InvestigationOrigin.TASK else InvestigationOrigin.STANDALONE, linkedTask, null, now.toEpochMilli(), now.toEpochMilli(), InvestigationMeta(title, q), listOf(turn)))
        } else {
            store.saveTurn(invId, turn, now)
        }
        return PrepareResult.Ready(invId, turn)
    }

    private suspend fun prepareWithProvider(question: String, earlier: Objective?, investigationId: String, turnId: String, taskId: String?, key: String, st: AppSettings): Objective? {
        val model = ResearchPresets.QUICK.model
        val contextLines = emptyList<String>() // objective preparation sends the question alone (RES-023); task context waits for authorization
        val record = UsageRecord(ids.newId(), UsageFeature.OBJECTIVE_PREP, model, clock.now(), turnId, investigationId, taskId, "OBJECTIVE")
        ledger.record(record)
        val call = ModelCall(model, InvestigationPrompt.OBJECTIVE_SYSTEM, InvestigationPrompt.objective(question, contextLines, earlier), 700, 0, timeout = Research2Config.MIN_CALL_TIMEOUT)
        val result = try { provider.call(call, key) } catch (e: CancellationException) { ledger.record(UsageRecord.priced(record, null, CallOutcome.STOPPED, null)); throw e } catch (e: Exception) { ledger.record(UsageRecord.priced(record, null, CallOutcome.FAILED_UNKNOWN, null)); return null }
        return when (result) {
            is CallResult.Failed -> { ledger.record(UsageRecord.priced(record, result.usage, if (notBilled(result.error)) CallOutcome.FAILED_NOT_BILLED else CallOutcome.FAILED_UNKNOWN, result.elapsedMs)); null }
            is CallResult.Done -> {
                ledger.record(UsageRecord.priced(record, result.usage, if (result.stop == CallStop.MAX_TOKENS) CallOutcome.TRUNCATED else CallOutcome.COMPLETE, result.elapsedMs, result.providerMessageId))
                if (result.stop == CallStop.REFUSAL) null else ObjectivePreparer.fromProvider(question, result.segments, model).takeIf { it.preparedBy == ObjectiveSource.PROVIDER }
            }
        }
    }

    /** The person answered the clarification: the objective is established from their choice (RES-020). */
    suspend fun clarify(investigationId: String, turnId: String, answer: String): Turn? = editTurnBeforeRun(investigationId, turnId) { t ->
        val o = ObjectivePreparer.clarified(t.objective, answer)
        t.copy(objective = o, state = if (o.established) TurnState.AUTHORIZING else TurnState.CLARIFYING)
    }

    /** A corrected objective before authorization (RES-013): the person's words stay; the objective text changes. */
    suspend fun editObjective(investigationId: String, turnId: String, text: String): Turn? = editTurnBeforeRun(investigationId, turnId) { t ->
        val o = ObjectivePreparer.edited(t.objective, text)
        t.copy(objective = o, state = if (o.established) TurnState.AUTHORIZING else TurnState.CLARIFYING)
    }

    /** Details the person added on the card to fill a disclosed gap (RES-025); recorded on the turn, sent only as part of the authorized payload. */
    suspend fun supplyDetails(investigationId: String, turnId: String, details: List<String>): Turn? = editTurnBeforeRun(investigationId, turnId) { it.copy(suppliedDetails = details.map { d -> d.trim() }.filter { d -> d.isNotEmpty() }) }

    /** Findings the person unticks stay excluded for every later turn (OD-4). */
    suspend fun excludeClaims(investigationId: String, turnId: String, claimIds: Set<String>): Turn? {
        val inv = store.get(investigationId) ?: return null
        val now = clock.now()
        store.updateMeta(investigationId, inv.meta.copy(excludedClaimIds = (inv.meta.excludedClaimIds + claimIds).distinct()), now)
        return editTurnBeforeRun(investigationId, turnId) { it.copy(excludedClaimIds = (it.excludedClaimIds + claimIds).distinct()) }
    }

    private suspend fun editTurnBeforeRun(investigationId: String, turnId: String, edit: (Turn) -> Turn): Turn? {
        val inv = store.get(investigationId) ?: return null
        val t = inv.turns.firstOrNull { it.id == turnId } ?: return null
        if (t.state != TurnState.CLARIFYING && t.state != TurnState.AUTHORIZING && t.state != TurnState.PREPARING) return t
        val edited = edit(t)
        store.saveTurn(investigationId, edited, clock.now())
        return edited
    }

    /** What a continuation does with each earlier claim; shown on the card, frozen into the payload. */
    fun inheritance(inv: Investigation, turn: Turn): List<InheritedFinding> =
        if (turn.kind == TurnKind.INITIAL) emptyList() else InheritancePlanner.plan(turn.objective, inv.currentClaims(), (inv.meta.excludedClaimIds + turn.excludedClaimIds).toSet(), turn.suppliedDetails)

    /** The record the card shows before the person approves; the same record is what [authorize] writes. */
    fun authorization(inv: Investigation, turn: Turn, context: List<ContextItem>, config: ResearchRunConfig): Authorization {
        val objective = if (turn.suppliedDetails.isEmpty()) turn.objective else turn.objective.copy(constraints = turn.objective.constraints + turn.suppliedDetails.map { Constraint(it) })
        return AuthorizationBuilder.build(objective, context, inheritance(inv, turn), config, clock.now().toEpochMilli())
    }

    /** Nothing paid happened; the turn is kept as declined so the thread shows the person's decision. */
    suspend fun decline(investigationId: String, turnId: String) {
        editTurnBeforeRun(investigationId, turnId) { it.copy(state = TurnState.DECLINED, endingNote = "Not started") }
    }

    // ------------------------------------------------------------------ authorize & run (RES-021/030)

    /**
     * Writes the authorization to the turn, then launches the paid run. Refuses without an established
     * objective, without the gate, without a key, over the daily cap, or while another turn runs.
     */
    suspend fun authorize(investigationId: String, turnId: String, authorization: Authorization, config: ResearchRunConfig): StartResult = claimed {
        val inv = store.get(investigationId) ?: return@claimed StartResult.Rejected(ResearchError.Unexpected("investigation missing"))
        val turn = inv.turns.firstOrNull { it.id == turnId } ?: return@claimed StartResult.Rejected(ResearchError.Unexpected("turn missing"))
        if (turn.state != TurnState.AUTHORIZING) return@claimed StartResult.Rejected(if (turn.state == TurnState.CLARIFYING) ResearchError.Malformed("the objective still needs your answer") else ResearchError.Busy)
        if (!turn.objective.established) return@claimed StartResult.Rejected(ResearchError.Malformed("the objective is not established"))
        val gate = gate()
        gate.blocker?.let { return@claimed StartResult.Rejected(it) }
        val key = keyStore.get() ?: return@claimed StartResult.Rejected(ResearchError.NoApiKey)
        if (!reserve()) return@claimed StartResult.Rejected(ResearchError.DailyLimit)
        val now = clock.now()
        val authorized = turn.copy(authorization = authorization, state = TurnState.RUNNING, usage = TurnUsage(modelsUsed = listOf(config.model), effortUsed = config.effort))
        // The record exists before the first byte leaves the device.
        store.saveTurn(investigationId, authorized, now)
        launch(inv, authorized, config, key)
        StartResult.Started(turnId, investigationId)
    }

    private suspend inline fun claimed(block: () -> StartResult): StartResult {
        if (!launching.compareAndSet(false, true)) return StartResult.Rejected(ResearchError.Busy)
        try {
            if (_state.value is TurnRunState.Running) return StartResult.Rejected(ResearchError.Busy)
            return block()
        } finally {
            launching.set(false)
        }
    }

    /** Stop sheet: keep what was gathered (saved as Stopped, findings kept) or discard it (findings dropped; usage stays). */
    fun stop(turnId: String, keepPartial: Boolean) {
        val s = _state.value as? TurnRunState.Running ?: return
        if (s.turnId != turnId) return
        keepPartialOnStop = keepPartial
        _state.update { st -> if (st is TurnRunState.Running && st.turnId == turnId) st.copy(stopRequested = true) else st }
        job?.cancel(CancellationException("stopped by user"))
    }

    fun dismiss(turnId: String) { _state.update { s -> if (s is TurnRunState.Finished && s.turnId == turnId) TurnRunState.Idle else s } }

    private suspend fun cancelActive(investigationId: String? = null, taskId: String? = null, reason: String) {
        val running = _state.value as? TurnRunState.Running ?: return
        if (investigationId != null && running.investigationId != investigationId) return
        if (taskId != null && running.taskId != taskId) return
        keepPartialOnStop = true
        val j = job ?: return
        j.cancel(CancellationException(reason))
        j.join()
    }

    /**
     * App start after a process death (RES-150–154): a turn still marked running can never finish; it
     * becomes Interrupted with everything its last checkpoint held, and its usage says a call was in
     * flight whose cost is unknown. Nothing is re-sent; a continuation needs a new authorization.
     */
    suspend fun recoverInterrupted(): List<Turn> {
        val settled = ledger.settleInterrupted()
        val out = ArrayList<Turn>()
        for (inv in store.running()) {
            for (t in inv.turns.filter { it.state == TurnState.RUNNING }) {
                val now = clock.now()
                val result = t.result?.copy(ending = TurnEnding.INTERRUPTED, state = ResultDeriver.derive(t.result.claims, TurnEnding.INTERRUPTED).state)
                val fixed = t.copy(
                    state = TurnState.INTERRUPTED, result = result,
                    usage = t.usage.copy(inFlightUnknown = settled > 0 || t.usage.inFlightUnknown, floor = true),
                    endingNote = if (result != null) "Interrupted; findings saved up to the last completed call are kept" else "Interrupted before any evidence was saved",
                    stageAtEnd = t.stageAtEnd,
                )
                store.saveTurn(inv.id, fixed, now)
                out += fixed
            }
        }
        return out
    }

    suspend fun associate(investigationId: String, taskId: String?, note: String) = store.associate(investigationId, taskId, note, clock.now())
    suspend fun delete(investigationId: String) { cancelActive(investigationId = investigationId, reason = "investigation deleted"); store.delete(investigationId) }
    suspend fun deleteForTask(taskId: String) { cancelActive(taskId = taskId, reason = "research deleted"); store.deleteForTask(taskId) }
    suspend fun deleteAll() { cancelActive(reason = "research deleted"); store.deleteAll() }

    // ------------------------------------------------------------------ the run

    private class RunCtx(val inv: Investigation, var turn: Turn, val config: ResearchRunConfig, val key: String, val payload: TurnPayload, val registry: SourceRegistry, val work: TurnWork, val startedAt: Instant) {
        val objective: Objective get() = turn.objective
        var remaining = config.searchBudget
        val diagnostics = ArrayList<CallDiagnostic>()
        var lastClass: CallClass? = null
        var nothingUsableDetail: String? = null
        var searchesRun = 0
        val stages = ArrayList<StageRecord>()
        var usage = TurnUsage(modelsUsed = listOf(config.model), effortUsed = config.effort)
        var spentMicros = 0L
        var spentFloor = false
        var ending = TurnEnding.COMPLETE
        var technical: String? = null
        var failure: ResearchError? = null
        var checkpointed = false
        var callsSent = 0
        var stageAtEnd: StageKind? = null
    }

    private fun launch(inv: Investigation, turn: Turn, config: ResearchRunConfig, key: String) {
        val startedAt = clock.now()
        val auth = turn.authorization!!
        val payload = AuthorizationBuilder.payload(auth)
        val excluded = (inv.meta.excludedClaimIds + turn.excludedClaimIds).toSet()
        val sentIds = auth.inherited.filter { it.disposition == com.lifeadmin.autopilot.ai.context.FindingDisposition.SENT }.map { it.claimId }.toSet()
        val inherited = inv.currentClaims().filter { it.id in sentIds && it.id !in excluded }
        val seed = inv.turns.sortedBy { it.index }.flatMap { it.result?.sources.orEmpty() }.distinctBy { it.n }
        val lineage = inv.currentClaims().associate { it.id to (it.inheritedFromTurnId ?: it.turnId) }
        val ctx = RunCtx(inv, turn, config, key, payload, SourceRegistry(seed, turn.id), TurnWork(turn.id, inherited, inv.taskId != null, lineage, excluded), startedAt)
        _state.value = TurnRunState.Running(
            turn.id, inv.id, inv.taskId, inv.title, config, startedAt, null, 0, config.stages.size, emptyList(), 0, seed.size, inherited.size, inherited.count { it.status == ClaimStatus.ESTABLISHED }, 0L, false,
            continuation = turn.kind != TurnKind.INITIAL,
        )
        keepPartialOnStop = false
        job = scope.launch {
            var cancelled = false
            var crash: Exception? = null
            try {
                runStages(ctx)
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                crash = e
            }
            // Everything after the run is written under NonCancellable, the state update included: a Stop that
            // lands here waits for the save, and the screen always learns how the turn ended.
            withContext(NonCancellable) {
                val outcome: TurnOutcome = when {
                    cancelled -> {
                        ctx.ending = TurnEnding.STOPPED
                        ctx.technical = "STOPPED BY YOU"
                        event(ctx.turn.id, TurnEventKind.STOPPED, "Stopped. Searches that already ran may be billed.")
                        if (keepPartialOnStop || !ctx.work.merged) finish(ctx) else discard(ctx)
                    }
                    crash != null -> {
                        ctx.failure = ResearchError.Unexpected(crash.javaClass.simpleName); ctx.technical = ctx.failure?.technical; ctx.ending = TurnEnding.FAILED
                        finish(ctx)
                    }
                    else -> finish(ctx)
                }
                _state.update { s -> if (s is TurnRunState.Running && s.turnId == turn.id) TurnRunState.Finished(turn.id, inv.id, inv.taskId, outcome, continuation = turn.kind != TurnKind.INITIAL) else s }
            }
        }
    }

    private suspend fun runStages(ctx: RunCtx) {
        val total = ctx.config.searchBudget
        val stages = ctx.config.stages
        for ((index, stage) in stages.withIndex()) {
            ctx.stageAtEnd = stage
            _state.update { s -> if (s is TurnRunState.Running && s.turnId == ctx.turn.id) s.copy(stage = stage, stageIndex = index) else s }
            val open = ctx.work.openClaims()
            val contested = ctx.work.contested()
            val skip: String? = when (stage) {
                StageKind.GATHER -> null
                StageKind.FILL_GAPS -> if (!ctx.work.merged) "nothing gathered" else if (open.isEmpty()) "every load-bearing claim is established" else if (ctx.remaining <= 0) "no searches left" else null
                StageKind.CROSS_CHECK -> if (contested.isEmpty()) "no sources disagree" else if (ctx.remaining <= 0) "no searches left" else null
                StageKind.SYNTHESIZE -> if (!ctx.work.merged) "nothing gathered to write up" else null
            }
            if (skip != null) {
                if (stage == StageKind.FILL_GAPS && open.isEmpty() && ctx.work.merged) ctx.work.verifyRan = true
                ctx.stages += StageRecord(stage, StageResult.SKIPPED, 0, 0, note = skip)
                event(ctx.turn.id, TurnEventKind.STAGE_SKIPPED, "${stage.label}: skipped — $skip")
                if (skip == "no searches left" && open.isNotEmpty()) ctx.ending = TurnEnding.BUDGET_SEARCHES
                continue
            }
            var allowance = if (stage == StageKind.SYNTHESIZE) 0 else ResearchPresets.stageShare(stage, ctx.remaining, total, stages.size).coerceAtMost(ctx.remaining)
            if (stage != StageKind.SYNTHESIZE && allowance <= 0) {
                ctx.stages += StageRecord(stage, StageResult.BUDGET, 0, 0, note = "no searches left")
                ctx.ending = TurnEnding.BUDGET_SEARCHES
                event(ctx.turn.id, TurnEventKind.BUDGET, "${stage.label}: no searches left in the budget")
                continue
            }
            ctx.config.spendLimitCents?.let { limitCents ->
                val limitMicros = limitCents * 10_000L
                fun low(n: Int) = CostCalculator.estimateRun(ctx.config.model, n, if (stage == StageKind.SYNTHESIZE) ctx.config.outputTokens / 2 else ctx.config.outputTokens)?.lowMicros ?: 0L
                if (ctx.spentMicros >= limitMicros) { budgetStop(ctx, stage, "spending limit reached"); return }
                while (allowance > 0 && ctx.spentMicros + low(allowance) > limitMicros) allowance--
                if (stage != StageKind.SYNTHESIZE && allowance == 0) { budgetStop(ctx, stage, "the next search would pass the spending limit"); return }
                if (stage == StageKind.SYNTHESIZE && ctx.spentMicros + low(0) > limitMicros) { budgetStop(ctx, stage, "the write-up would pass the spending limit"); return }
            }
            val user = prompt(ctx, stage, allowance)
            val ok = runStage(ctx, stage, allowance, user, cache = stages.size > 1)
            if (!ok) return
            if (stage == StageKind.FILL_GAPS) ctx.work.verifyRan = true
            if (stage == StageKind.GATHER && !ctx.work.merged) {
                val why = noFindings(ctx)
                ctx.failure = why; ctx.technical = why.technical
                if (ctx.ending != TurnEnding.BUDGET_SEARCHES) ctx.ending = TurnEnding.FAILED
                event(ctx.turn.id, TurnEventKind.FAILED, "${stage.label}: ${why.headline.lowercase(Locale.ROOT)} — stopping here")
                return
            }
            if (ctx.remaining <= 0 && index < stages.lastIndex && ctx.work.openClaims().isNotEmpty()) ctx.ending = TurnEnding.BUDGET_SEARCHES
        }
        if (stages.none { it == StageKind.FILL_GAPS }) ctx.work.verifyRan = true
    }

    private fun prompt(ctx: RunCtx, stage: StageKind, allowance: Int): String {
        val claims = ctx.work.current()
        val next = InvestigationParser.nextClaimNumber(claims)
        return when (stage) {
            StageKind.GATHER -> InvestigationPrompt.gather(ctx.payload, ctx.objective, allowance, next, ctx.inv.taskId != null)
            StageKind.FILL_GAPS -> InvestigationPrompt.verify(ctx.payload, ctx.work.openClaims(), ctx.registry.sources(), ctx.work.queries, allowance, next)
            StageKind.CROSS_CHECK -> InvestigationPrompt.reconcile(ctx.payload, ctx.work.contested(), ctx.registry.sources(), ctx.work.queries, allowance)
            StageKind.SYNTHESIZE -> InvestigationPrompt.synthesize(ctx.payload, ctx.objective, claims, ctx.registry.sources())
        }
    }

    private fun budgetStop(ctx: RunCtx, stage: StageKind, why: String) {
        ctx.stages += StageRecord(stage, StageResult.BUDGET, 0, 0, note = why)
        ctx.ending = TurnEnding.BUDGET_SPEND
        event(ctx.turn.id, TurnEventKind.BUDGET, "${stage.label}: not started — $why. One in-flight call can still exceed a limit; nothing else is launched.")
    }

    /**
     * One stage = one or more bounded HTTP calls (at most [Research2Config.MAX_SEARCHES_PER_CALL] searches
     * each). Every call is its own billed request with its own timeout and ledger row; its evidence is
     * merged and checkpointed before the next; the next batch goes only while load-bearing claims stay
     * open and the model kept searching. Nothing is ever re-sent on its own. Returns false to end the turn.
     */
    private suspend fun runStage(ctx: RunCtx, stage: StageKind, allowance: Int, user: String, cache: Boolean): Boolean {
        var prior: Any? = null
        var calls = 0
        var continuations = 0
        var searchesThisStage = 0
        var left = allowance
        var promptText = user
        val queries = ArrayList<String>()
        val stageLog = ArrayList<CallDiagnostic>()
        val batched = stage != StageKind.SYNTHESIZE && allowance > Research2Config.MAX_SEARCHES_PER_CALL
        fun log(d: CallDiagnostic) { stageLog += d; ctx.diagnostics += d; ctx.lastClass = d.classification }
        while (true) {
            val ask = if (stage == StageKind.SYNTHESIZE) 0 else minOf(left, Research2Config.MAX_SEARCHES_PER_CALL)
            val limit = Research2Config.callTimeout(ask, ctx.config.outputTokens)
            val call = ModelCall(ctx.config.model, InvestigationPrompt.SYSTEM, promptText, ctx.config.outputTokens, ask, cacheSystem = cache, prior = prior, timeout = limit, effort = ctx.config.effort)
            calls++; ctx.callsSent++
            val which = if (batched || prior != null) " · call $calls" else ""
            event(
                ctx.turn.id, TurnEventKind.SENT,
                if (prior == null) "${stage.label}$which: request sent (up to $ask ${plural(ask, "search", "searches")}; waiting up to ${ResearchError.describe(limit.seconds.toInt())})"
                else "${stage.label}$which: continued within the same budget (waiting up to ${ResearchError.describe(limit.seconds.toInt())})",
            )
            val sentAt = clock.now()
            _state.update { st -> if (st is TurnRunState.Running && st.turnId == ctx.turn.id) st.copy(callStartedAtEpochMs = sentAt.toEpochMilli(), callTimeoutSeconds = limit.seconds.toInt(), callsSent = ctx.callsSent) else st }
            val record = UsageRecord(ids.newId(), if (ctx.turn.kind == TurnKind.INITIAL) UsageFeature.RESEARCH else UsageFeature.RESEARCH_CONTINUATION, ctx.config.model, sentAt, ctx.turn.id, ctx.inv.id, ctx.inv.taskId, stage.name, effort = ctx.config.effort)
            ledger.record(record)
            val result = try {
                provider.call(call, ctx.key)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { ledger.record(UsageRecord.priced(record, null, CallOutcome.STOPPED, null)) }
                ctx.spentFloor = true
                ctx.usage = ctx.usage.copy(floor = true)
                throw e
            }
            _state.update { st -> if (st is TurnRunState.Running && st.turnId == ctx.turn.id) st.copy(callStartedAtEpochMs = null) else st }
            when (result) {
                is CallResult.Failed -> {
                    val outcome = if (notBilled(result.error)) CallOutcome.FAILED_NOT_BILLED else CallOutcome.FAILED_UNKNOWN
                    val observed = when {
                        outcome == CallOutcome.FAILED_NOT_BILLED -> null
                        result.usage != null -> result.usage
                        result.searchesSeen > 0 -> MeasuredUsage(null, null, null, null, null, result.searchesSeen)
                        else -> null
                    }
                    ledger.record(UsageRecord.priced(record, observed, outcome, result.elapsedMs))
                    if (outcome == CallOutcome.FAILED_UNKNOWN) { ctx.spentFloor = true; ctx.usage = ctx.usage.copy(floor = true) }
                    if (observed != null) {
                        val priced = CostCalculator.price(ctx.config.model, observed)
                        ctx.spentMicros += priced.estimate?.micros ?: 0L
                        val n = observed.webSearches ?: 0
                        searchesThisStage += n; ctx.searchesRun += n; ctx.remaining = (ctx.remaining - n).coerceAtLeast(0)
                        addUsage(ctx, observed, result.elapsedMs, floor = true)
                        updateRunning(ctx)
                    }
                    log(CallDiagnostic(calls, stage, ctx.config.model, "failed", inputTokens = observed?.inputTokens, outputTokens = observed?.outputTokens, maxTokens = ctx.config.outputTokens, webSearches = observed?.webSearches, searchesAllowed = ask, durationMs = result.elapsedMs, classification = CallClass.HTTP_FAILED, error = result.error.technical))
                    ctx.stages += StageRecord(stage, StageResult.FAILED, allowance, searchesThisStage, queries, result.error.technical, calls, stageLog.toList())
                    ctx.failure = result.error
                    ctx.technical = result.error.technical
                    ctx.ending = TurnEnding.FAILED
                    event(ctx.turn.id, TurnEventKind.FAILED, "${stage.label}$which: ${result.error.headline.lowercase(Locale.ROOT)} — ${result.error.technical.lowercase(Locale.ROOT)}" + if (ctx.work.merged) ". Evidence from earlier calls is kept." else ". Nothing was re-sent.")
                    return false
                }
                is CallResult.Done -> {
                    val outcome = when (result.stop) { CallStop.MAX_TOKENS -> CallOutcome.TRUNCATED; else -> CallOutcome.COMPLETE }
                    ledger.record(UsageRecord.priced(record, result.usage, outcome, result.elapsedMs, result.providerMessageId))
                    val priced = CostCalculator.price(ctx.config.model, result.usage)
                    ctx.spentMicros += priced.estimate?.micros ?: 0L
                    if (priced.partial) ctx.spentFloor = true
                    addUsage(ctx, result.usage, result.elapsedMs, floor = result.usage.anyUnknown)
                    val n = result.usage.webSearches ?: result.searches.size
                    searchesThisStage += n; ctx.searchesRun += n; ctx.remaining = (ctx.remaining - n).coerceAtLeast(0); left = (left - n).coerceAtLeast(0)
                    val shape = result.shape ?: ReplyShape.of(result.segments, result.searches)
                    val stopName = when (result.stop) { CallStop.END -> "end_turn"; CallStop.PAUSE_TURN -> "pause_turn"; CallStop.MAX_TOKENS -> "max_tokens"; CallStop.REFUSAL -> "refusal" }
                    fun diagnostic(parsed: Map<String, Int>, cls: CallClass) = CallDiagnostic(
                        calls, stage, ctx.config.model, stopName, shape.blocks, shape.textChars, shape.citedBlocks, parsed,
                        result.usage.inputTokens, result.usage.outputTokens, ctx.config.outputTokens, result.usage.webSearches, ask, result.elapsedMs, cls,
                    )
                    if (result.stop == CallStop.REFUSAL) {
                        log(diagnostic(emptyMap(), CallClass.REFUSED))
                        ctx.stages += StageRecord(stage, StageResult.FAILED, allowance, searchesThisStage, queries, "STOP REASON: REFUSAL", calls, stageLog.toList())
                        ctx.failure = ResearchError.Refused(result.refusalCategory); ctx.technical = ctx.failure?.technical
                        ctx.ending = TurnEnding.FAILED
                        event(ctx.turn.id, TurnEventKind.FAILED, "${stage.label}: Claude declined this request")
                        return false
                    }
                    val now = clock.now().toEpochMilli()
                    val known = ctx.work.current()
                    val out = InvestigationParser.parse(result.segments, result.searches, ctx.registry, ctx.turn.id, now, known, InvestigationParser.evidenceLines(known))
                    queries += out.queries
                    val usable = out.hasFindings || out.unresolved.isNotEmpty()
                    val changed = usable && ctx.work.merge(out, now)
                    val cls = when {
                        !usable && result.stop == CallStop.PAUSE_TURN -> CallClass.PAUSED_BEFORE_TEXT
                        !usable && result.stop == CallStop.MAX_TOKENS -> CallClass.TRUNCATED_BEFORE_TEXT
                        !usable && (n > 0 || (shape.blocks["server_tool_use"] ?: 0) > 0) -> CallClass.TOOL_ONLY
                        !usable -> CallClass.EMPTY
                        out.freeForm -> CallClass.FREE_FORM
                        !out.hasFindings -> CallClass.UNRESOLVED_ONLY
                        !changed -> CallClass.NO_NEW_EVIDENCE
                        else -> CallClass.STRUCTURED
                    }
                    log(diagnostic(InvestigationParser.counts(out), cls))
                    if (usable) ctx.work.merged = true
                    if (ctx.work.merged) checkpoint(ctx, stage)
                    updateRunning(ctx)
                    if (result.stop == CallStop.MAX_TOKENS) { ctx.technical = "OUTPUT LIMIT REACHED"; }
                    if (result.stop == CallStop.PAUSE_TURN) {
                        if (continuations < Research2Config.MAX_PAUSE_CONTINUATIONS && left > 0 && calls < Research2Config.MAX_CALLS_PER_STAGE) {
                            prior = result.paused; continuations++
                            event(ctx.turn.id, TurnEventKind.CONTINUED, "${stage.label}: Anthropic paused the turn; continuing with $left ${plural(left, "search", "searches")} left")
                            continue
                        }
                        val reason = if (left <= 0) "no searches left in the budget" else if (continuations >= Research2Config.MAX_PAUSE_CONTINUATIONS) "already continued once" else "call limit for this stage reached"
                        if (left <= 0 && ctx.work.openClaims().isNotEmpty()) ctx.ending = TurnEnding.BUDGET_SEARCHES
                        if (!usable) ctx.nothingUsableDetail = "Anthropic paused the turn after $searchesThisStage of $allowance ${plural(allowance, "search", "searches")} and before any text was written; it was not continued ($reason)"
                        ctx.stages += StageRecord(stage, StageResult.DONE, allowance, searchesThisStage, queries, "paused by Anthropic; not continued — $reason", calls, stageLog.toList())
                        event(ctx.turn.id, TurnEventKind.STAGE_DONE, "${stage.label}: paused by Anthropic, not continued ($reason) · $searchesThisStage ${plural(searchesThisStage, "search", "searches")}")
                        return true
                    }
                    if (!usable) ctx.nothingUsableDetail = when (cls) {
                        CallClass.TRUNCATED_BEFORE_TEXT -> "The reply reached its ${ctx.config.outputTokens}-token output limit before writing any text (${describeBlocks(shape, n)})"
                        CallClass.TOOL_ONLY -> "$n ${plural(n, "search", "searches")} ran but the reply ended without any text (${describeBlocks(shape, n)})"
                        else -> "The reply was empty: no content blocks at all"
                    }
                    if (batched && left > 0 && n > 0 && calls < Research2Config.MAX_CALLS_PER_STAGE && result.stop != CallStop.MAX_TOKENS && stillOpen(ctx, stage) && spendAllows(ctx, stage, minOf(left, Research2Config.MAX_SEARCHES_PER_CALL))) {
                        prior = null
                        val next = minOf(left, Research2Config.MAX_SEARCHES_PER_CALL)
                        promptText = if (stage == StageKind.CROSS_CHECK) InvestigationPrompt.reconcile(ctx.payload, ctx.work.contested(), ctx.registry.sources(), ctx.work.queries, next)
                        else InvestigationPrompt.verify(ctx.payload, ctx.work.openClaims(), ctx.registry.sources(), ctx.work.queries, next, InvestigationParser.nextClaimNumber(ctx.work.current()))
                        val open = ctx.work.openClaims()
                        event(ctx.turn.id, TurnEventKind.CONTINUED, "${stage.label}: $searchesThisStage ${plural(searchesThisStage, "search", "searches")} so far, evidence saved; still open: ${open.take(3).joinToString(", ") { it.text.take(40) }}${if (open.size > 3) " +${open.size - 3}" else ""} — next batch of up to $next")
                        continue
                    }
                    ctx.stages += StageRecord(stage, StageResult.DONE, allowance, searchesThisStage, queries, if (batched) "$calls ${plural(calls, "call", "calls")}" else null, calls, stageLog.toList())
                    event(ctx.turn.id, TurnEventKind.STAGE_DONE, "${stage.label}: done · $searchesThisStage ${plural(searchesThisStage, "search", "searches")} · ${ctx.registry.sources().size} sources so far")
                    return true
                }
            }
        }
    }

    private fun addUsage(ctx: RunCtx, u: MeasuredUsage, elapsedMs: Long, floor: Boolean) {
        ctx.usage = ctx.usage.copy(
            inputTokens = ctx.usage.inputTokens + (u.inputTokens ?: 0), outputTokens = ctx.usage.outputTokens + (u.outputTokens ?: 0),
            cacheWriteTokens = ctx.usage.cacheWriteTokens + (u.cacheWrite5mTokens ?: 0) + (u.cacheWrite1hTokens ?: 0), cacheReadTokens = ctx.usage.cacheReadTokens + (u.cacheReadTokens ?: 0),
            webSearches = ctx.usage.webSearches + (u.webSearches ?: 0), elapsedMs = ctx.usage.elapsedMs + elapsedMs, spentMicros = ctx.spentMicros, floor = ctx.usage.floor || floor,
        )
    }

    private fun stillOpen(ctx: RunCtx, stage: StageKind): Boolean = when (stage) {
        StageKind.GATHER, StageKind.FILL_GAPS -> ctx.work.openClaims().isNotEmpty()
        StageKind.CROSS_CHECK -> ctx.work.contested().isNotEmpty()
        StageKind.SYNTHESIZE -> false
    }

    private fun spendAllows(ctx: RunCtx, stage: StageKind, searches: Int): Boolean {
        val limitCents = ctx.config.spendLimitCents ?: return true
        val low = CostCalculator.estimateRun(ctx.config.model, searches, ctx.config.outputTokens)?.lowMicros ?: 0L
        val ok = ctx.spentMicros + low <= limitCents * 10_000L
        if (!ok) { ctx.ending = TurnEnding.BUDGET_SPEND; event(ctx.turn.id, TurnEventKind.BUDGET, "${stage.label}: next batch not sent — it would pass the spending limit") }
        return ok
    }

    private fun describeBlocks(shape: ReplyShape, searches: Int): String {
        val parts = ArrayList<String>()
        (shape.blocks["thinking"] ?: 0).plus(shape.blocks["redacted_thinking"] ?: 0).takeIf { it > 0 }?.let { parts += "$it thinking ${plural(it, "block", "blocks")}" }
        if (searches > 0) parts += "$searches ${plural(searches, "search", "searches")}"
        parts += if (shape.textChars > 0) "${shape.textChars} characters of text" else "no text"
        return parts.joinToString(", ")
    }

    private fun noFindings(ctx: RunCtx): ResearchError {
        val cls = ctx.lastClass?.takeIf { it.nothingUsable } ?: CallClass.EMPTY
        return ResearchError.NoFindings(cls, ctx.nothingUsableDetail ?: "The reply was empty: no content blocks at all", ctx.turn.kind != TurnKind.INITIAL)
    }

    /** Writes the evidence merged so far to the turn (still Running) so a later failure or process death keeps everything paid for. */
    private suspend fun checkpoint(ctx: RunCtx, stage: StageKind) = withContext(NonCancellable) {
        val now = clock.now()
        val provisional = ctx.work.result(ctx.objective, TurnEnding.INTERRUPTED, ctx.registry.sources(), ctx.stages.toList() + StageRecord(stage, StageResult.DONE, 0, 0, note = "in progress"), ctx.diagnostics.toList(), null, now.toEpochMilli())
        ctx.turn = ctx.turn.copy(result = provisional, usage = ctx.usage.copy(spentMicros = ctx.spentMicros, floor = ctx.usage.floor || ctx.spentFloor), stageAtEnd = stage.name)
        store.saveTurn(ctx.inv.id, ctx.turn, now)
        ctx.checkpointed = true
        _state.update { st -> if (st is TurnRunState.Running && st.turnId == ctx.turn.id) st.copy(checkpointed = true) else st }
        event(ctx.turn.id, TurnEventKind.CHECKPOINT, "Evidence saved: ${provisional.claims.size} ${plural(provisional.claims.size, "claim", "claims")}, ${provisional.claims.count { it.status == ClaimStatus.ESTABLISHED }} established")
    }

    private fun updateRunning(ctx: RunCtx) {
        val claims = ctx.work.current()
        _state.update { s ->
            if (s is TurnRunState.Running && s.turnId == ctx.turn.id) s.copy(searchesRun = ctx.searchesRun, sourcesSeen = ctx.registry.sources().size, claimsHeld = claims.size, establishedClaims = claims.count { it.status == ClaimStatus.ESTABLISHED }, spentMicros = ctx.spentMicros, spentIsFloor = ctx.spentFloor, title = ctx.work.title ?: s.title) else s
        }
    }

    /** Stop → discard: the findings of this turn are dropped; the turn record and its usage stay (RES-143). */
    private suspend fun discard(ctx: RunCtx): TurnOutcome {
        val now = clock.now()
        ctx.turn = ctx.turn.copy(state = TurnState.STOPPED, result = null, usage = ctx.usage.copy(spentMicros = ctx.spentMicros, floor = true), endingNote = "Stopped; you chose to discard what was gathered", stageAtEnd = ctx.stageAtEnd?.name)
        store.saveTurn(ctx.inv.id, ctx.turn, now)
        return TurnOutcome.Discarded
    }

    private suspend fun finish(ctx: RunCtx): TurnOutcome {
        val now = clock.now()
        val state = when (ctx.ending) {
            TurnEnding.STOPPED -> TurnState.STOPPED
            TurnEnding.FAILED -> TurnState.FAILED
            TurnEnding.INTERRUPTED -> TurnState.INTERRUPTED
            // A ceiling that left nothing gathered is not a finished answer.
            TurnEnding.BUDGET_SPEND, TurnEnding.BUDGET_SEARCHES -> if (ctx.work.merged) TurnState.DONE else TurnState.FAILED
            else -> TurnState.DONE
        }
        val usage = ctx.usage.copy(spentMicros = ctx.spentMicros, floor = ctx.usage.floor || ctx.spentFloor)
        if (!ctx.work.merged) {
            val error = ctx.failure ?: when {
                ctx.ending == TurnEnding.STOPPED -> ResearchError.Stopped(ctx.searchesRun)
                // The ceiling stopped the turn before anything was sent: say so, never "the reply was empty".
                ctx.ending == TurnEnding.BUDGET_SPEND && ctx.callsSent == 0 -> ResearchError.LimitBelowFirstCall
                else -> noFindings(ctx)
            }
            val result = ctx.work.result(ctx.objective, ctx.ending, ctx.registry.sources(), ctx.stages.toList(), ctx.diagnostics.toList(), ctx.technical ?: error.technical, now.toEpochMilli())
            ctx.turn = ctx.turn.copy(state = state, result = result, usage = usage, endingNote = error.headline, stageAtEnd = ctx.stageAtEnd?.name)
            store.saveTurn(ctx.inv.id, ctx.turn, now)
            return TurnOutcome.Failed(error, ctx.diagnostics.toList())
        }
        val result = ctx.work.result(ctx.objective, ctx.ending, ctx.registry.sources(), ctx.stages.toList(), ctx.diagnostics.toList(), ctx.technical, now.toEpochMilli())
        val note = when (ctx.ending) {
            TurnEnding.STOPPED -> "Stopped by you; what was gathered is kept"
            TurnEnding.FAILED -> (ctx.failure?.headline ?: "Failed") + "; evidence from earlier calls is kept"
            TurnEnding.BUDGET_SEARCHES, TurnEnding.BUDGET_SPEND -> ctx.ending.label
            else -> null
        }
        ctx.turn = ctx.turn.copy(state = state, result = result, usage = usage, endingNote = note, stageAtEnd = ctx.stageAtEnd?.name)
        store.saveTurn(ctx.inv.id, ctx.turn, now)
        if (ctx.work.title != null) {
            val inv = store.get(ctx.inv.id)
            if (inv != null && inv.meta.legacy.not() && inv.turns.size == 1) store.updateMeta(inv.id, inv.meta.copy(title = result.answer.title.take(InvestigationRepository.MAX_TITLE)), now)
        }
        return TurnOutcome.Saved(state, result.state, ctx.ending)
    }

    private fun event(turnId: String, kind: TurnEventKind, text: String) {
        _state.update { s -> if (s is TurnRunState.Running && s.turnId == turnId) s.copy(events = (s.events + TurnEvent(clock.now().toEpochMilli(), kind, text)).takeLast(40)) else s }
    }

    private fun notBilled(e: ResearchError): Boolean = when (e) {
        ResearchError.Unauthorized, ResearchError.Forbidden, ResearchError.WebSearchNotEnabled, ResearchError.OutOfCredit, is ResearchError.BadRequest, is ResearchError.RateLimited, ResearchError.Offline -> true
        else -> false
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

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
        /** The payload fingerprint shown beside "What was sent", for tests and the ledger line. */
        fun fingerprint(json: String): String = FrozenPayload.sha256(json)
    }
}
