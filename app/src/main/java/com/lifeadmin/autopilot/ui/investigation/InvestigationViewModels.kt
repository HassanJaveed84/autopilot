package com.lifeadmin.autopilot.ui.investigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.ai.AiAvailability
import com.lifeadmin.autopilot.ai.NetworkStatus
import com.lifeadmin.autopilot.ai.context.ContextItem
import com.lifeadmin.autopilot.ai.context.InheritedFinding
import com.lifeadmin.autopilot.ai.investigation.Authorization
import com.lifeadmin.autopilot.ai.investigation.EffortSupport
import com.lifeadmin.autopilot.ai.investigation.Investigation
import com.lifeadmin.autopilot.ai.investigation.InvestigationProposalSets
import com.lifeadmin.autopilot.ai.investigation.InvestigationService
import com.lifeadmin.autopilot.ai.investigation.PrepareResult
import com.lifeadmin.autopilot.ai.investigation.ResearchDepth
import com.lifeadmin.autopilot.ai.investigation.ResearchPresets
import com.lifeadmin.autopilot.ai.investigation.ResearchRunConfig
import com.lifeadmin.autopilot.ai.investigation.StartResult
import com.lifeadmin.autopilot.ai.investigation.Turn
import com.lifeadmin.autopilot.ai.investigation.TurnRunState
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchGate
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.usage.UsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageTotals
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.AppSettings
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** What the person has chosen on the authorization card; persisted across recreation. */
data class ThreadChoices(
    val depth: ResearchDepth? = null,
    val model: String? = null,
    val searches: Int? = null,
    val limitCents: Int? = null,
    val noLimit: Boolean = false,
    val effort: String? = null,
    val scope: ResearchScope = ResearchScope(),
    val adjustOpen: Boolean = false,
)

data class ThreadUi(
    val loading: Boolean = true,
    val investigation: Investigation? = null,
    val taskId: String? = null,
    val taskTitle: String? = null,
    val question: String = "",
    val useProvider: Boolean = false,
    val aiReady: Boolean = false,
    val preparing: Boolean = false,
    /** The turn being set up (clarifying or authorizing); null when there is none. */
    val pending: Turn? = null,
    val choices: ThreadChoices = ThreadChoices(),
    val config: ResearchRunConfig? = null,
    val context: List<ContextItem> = emptyList(),
    val inheritance: List<InheritedFinding> = emptyList(),
    val authorization: Authorization? = null,
    val gate: ResearchGate? = null,
    val run: TurnRunState = TurnRunState.Idle,
    val pendingProposals: Map<String, Int> = emptyMap(),
    val monthSoFar: UsageTotals? = null,
    val monthlyBudgetCents: Int = 0,
    val settings: AppSettings = AppSettings(),
    val error: ResearchError? = null,
    val message: String? = null,
    val showExplainer: Boolean = false,
    val stopSheet: Boolean = false,
    val payloadSheet: Boolean = false,
    val evidenceClaim: Pair<String, String>? = null,
    val sourceSheet: Pair<String, Int>? = null,
    val linkSheet: Boolean = false,
    val tasks: List<TaskSummary> = emptyList(),
    val online: Boolean? = null,
    val now: Instant = Instant.EPOCH,
) {
    val running: TurnRunState.Running? get() = (run as? TurnRunState.Running)?.takeIf { it.investigationId == investigation?.id }
    val finished: TurnRunState.Finished? get() = (run as? TurnRunState.Finished)?.takeIf { it.investigationId == investigation?.id }
    val busyElsewhere: Boolean get() = run is TurnRunState.Running && running == null
    val canStart: Boolean get() = pending?.state == TurnState.AUTHORIZING && authorization != null && running == null && !busyElsewhere && gate?.blocker.let { it == null || it == ResearchError.NoResearchConsent }
    val completedTurns: List<Turn> get() = investigation?.turns.orEmpty().filter { it.state != TurnState.CLARIFYING && it.state != TurnState.AUTHORIZING && it.state != TurnState.PREPARING && it.state != TurnState.RUNNING }
    val overMonthlyBudget: Boolean get() = monthlyBudgetCents > 0 && (monthSoFar?.estimate?.micros ?: 0L) >= monthlyBudgetCents * 10_000L
}

/**
 * One Investigation Thread: objective, authorization, the run, results turn by turn, follow-ups,
 * resume and association. Opening it never calls a provider (RES-021); preparing is local unless the
 * person ticks the small, ledgered objective call; every paid turn starts only from [start].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvestigationThreadViewModel(
    private val saved: SavedStateHandle,
    initialInvestigationId: String?,
    initialTaskId: String?,
    initialQuestion: String,
    private val service: InvestigationService,
    private val store: InvestigationRepository,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val ledger: UsageLedger,
    private val proposals: InvestigationProposalSets,
    private val network: NetworkStatus,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
) : ViewModel() {
    private val investigationId = MutableStateFlow(saved.get<String>("inv") ?: initialInvestigationId)
    private val local = MutableStateFlow(
        ThreadUi(
            question = saved.get<String>("q") ?: initialQuestion,
            useProvider = saved.get<Boolean>("prep") ?: false,
            choices = ThreadChoices(
                depth = saved.get<String>("depth")?.let(ResearchDepth::of), model = saved.get("model"), searches = saved.get("searches"),
                limitCents = saved.get("limit"), noLimit = saved.get<Boolean>("nolimit") ?: false, effort = saved.get("effort"),
                scope = ResearchScope(saved.get("s_title") ?: true, saved.get("s_notes") ?: true, saved.get("s_steps") ?: false, saved.get("s_locale") ?: true),
                adjustOpen = saved.get<Boolean>("adjust") ?: false,
            ),
            taskId = initialTaskId,
        ),
    )
    private var excluded: Set<String> = (saved.get<Array<String>>("excl") ?: emptyArray()).toSet()
    /** Declared before init: the collectors started there take it immediately. */
    private val rebuildLock = Mutex()

    private val invFlow = investigationId.flatMapLatest { id -> if (id == null) flowOf(null) else store.observe(id) }

    val state: StateFlow<ThreadUi> = combine(invFlow, local, service.state, settings.settings, tasks.observeSummaries()) { inv, l, run, s, summaries ->
        val taskId = inv?.taskId ?: l.taskId.takeIf { inv == null }
        l.copy(
            loading = investigationId.value != null && inv == null && l.loading,
            investigation = inv, taskId = taskId, taskTitle = summaries.firstOrNull { it.task.id == taskId }?.task?.title,
            pending = inv?.turns?.lastOrNull { it.state == TurnState.CLARIFYING || it.state == TurnState.AUTHORIZING },
            run = run, settings = s, monthlyBudgetCents = s.aiMonthlyBudgetCents, tasks = summaries.filter { !it.task.status.isTerminal }, now = clock.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        refresh()
        viewModelScope.launch {
            // Keep the authorization preview in step with the pending turn and the person's choices.
            combine(invFlow, local.map { it.choices }.distinctUntilChanged()) { inv, ch -> inv to ch }.distinctUntilChanged().collect { (inv, ch) -> rebuild(inv, ch) }
        }
        viewModelScope.launch { invFlow.collect { inv -> if (inv != null) { local.update { it.copy(loading = false) }; counts(inv) } } }
    }

    fun refresh() = viewModelScope.launch {
        val s = settings.current()
        val gate = service.gate(s)
        val monthStart = clock.now().atZone(zoneProvider.current()).toLocalDate().withDayOfMonth(1).atStartOfDay(zoneProvider.current()).toInstant()
        local.update { it.copy(gate = gate, aiReady = gate.availability is AiAvailability.Ready, online = network.isOnline(), monthSoFar = UsageTotals.of(ledger.getAll().filter { r -> !r.startedAt.isBefore(monthStart) }), loading = investigationId.value != null) }
        rebuild(investigationId.value?.let { store.get(it) }, local.value.choices)
    }

    private suspend fun counts(inv: Investigation) {
        val m = inv.turns.filter { it.result?.proposals?.isNotEmpty() == true }.associate { it.id to proposals.pendingCount(inv, it) }
        local.update { it.copy(pendingProposals = m) }
    }

    /** The configuration the person's current choices resolve to for [turn]: the card and Start both use this. */
    private fun configFor(ch: ThreadChoices, turn: Turn?, s: AppSettings): ResearchRunConfig {
        val depth = ch.depth ?: turn?.objective?.recommendedDepth?.takeIf { it != ResearchDepth.CUSTOM } ?: ResearchDepth.of(s.researchDefaultDepth) ?: ResearchDepth.STANDARD
        return ResearchPresets.resolve(depth, s, modelOverride = ch.model, searches = ch.searches, limitCents = if (ch.noLimit) null else ch.limitCents, effort = ch.effort).let { if (ch.noLimit) it.copy(spendLimitCents = null) else it }
    }

    /**
     * Rebuilds run one at a time and always from the latest choices: a slower rebuild that began
     * before a tap must never overwrite the configuration the tap produced.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun rebuild(inv: Investigation?, requested: ThreadChoices) = rebuildLock.withLock { rebuildLocked(inv) }

    private suspend fun rebuildLocked(inv: Investigation?) {
        val ch = local.value.choices
        val turn = inv?.turns?.lastOrNull { it.state == TurnState.AUTHORIZING }
        val s = settings.current()
        val cfg = configFor(ch, turn, s)
        val taskId = inv?.taskId ?: local.value.taskId
        val ctx = service.contextItems(taskId, ch.scope)
        if (inv == null || turn == null) { local.update { if (it.choices != ch || (it.config == cfg && it.context == ctx && it.authorization == null)) it else it.copy(config = cfg, context = ctx, authorization = null, inheritance = emptyList()) }; return }
        val withExcl = if (excluded.isEmpty()) turn else turn.copy(excludedClaimIds = (turn.excludedClaimIds + excluded).distinct())
        val auth = service.authorization(inv, withExcl, ctx, cfg)
        val inh = service.inheritance(inv, withExcl)
        // approvedAtMs is "now": compare without it so an unchanged card is not re-emitted.
        // Choices changed while this ran: the collector rebuilds again from them, so write nothing stale.
        local.update { if (it.choices != ch || (it.config == cfg && it.context == ctx && it.inheritance == inh && it.authorization?.copy(approvedAtMs = 0) == auth.copy(approvedAtMs = 0))) it else it.copy(config = cfg, context = ctx, inheritance = inh, authorization = auth) }
    }

    // ------------------------------------------------------------------ objective

    fun setQuestion(q: String) { saved["q"] = q; local.update { it.copy(question = q, error = null) } }
    fun setUseProvider(on: Boolean) { saved["prep"] = on; local.update { it.copy(useProvider = on) } }

    /** Local by default; nothing paid. A follow-up on an existing thread becomes its next turn. */
    fun prepare(resumeOf: String? = null) = viewModelScope.launch {
        val l = local.value
        val q = if (resumeOf != null) store.get(investigationId.value ?: return@launch)?.turns?.firstOrNull { it.id == resumeOf }?.objective?.question.orEmpty() else l.question
        local.update { it.copy(preparing = true, error = null) }
        when (val r = service.prepare(q, investigationId.value, l.taskId, useProvider = l.useProvider && l.aiReady && resumeOf == null, resumeOf = resumeOf)) {
            is PrepareResult.Ready -> {
                investigationId.value = r.investigationId; saved["inv"] = r.investigationId
                excluded = emptySet(); saved["excl"] = emptyArray<String>()
                saved["q"] = ""
                local.update { it.copy(preparing = false, question = "") }
                rebuild(store.get(r.investigationId), local.value.choices)
            }
            is PrepareResult.Rejected -> local.update { it.copy(preparing = false, error = r.error) }
        }
    }

    fun clarify(answer: String) = viewModelScope.launch {
        val inv = investigationId.value ?: return@launch
        val t = state.value.pending ?: return@launch
        service.clarify(inv, t.id, answer)
    }

    fun editObjective(text: String) = viewModelScope.launch {
        val inv = investigationId.value ?: return@launch
        val t = state.value.pending ?: return@launch
        service.editObjective(inv, t.id, text)
    }

    fun supplyDetails(text: String) = viewModelScope.launch {
        val inv = investigationId.value ?: return@launch
        val t = state.value.pending ?: return@launch
        service.supplyDetails(inv, t.id, text.split('\n').map { it.trim() }.filter { it.isNotEmpty() })
    }

    // ------------------------------------------------------------------ authorization card

    fun setDepth(d: ResearchDepth) { saved["depth"] = d.name; saved.remove<String>("model"); saved.remove<Int>("searches"); updateChoices { it.copy(depth = d, model = null, searches = null) } }
    fun setModel(id: String) { saved["model"] = id; updateChoices { it.copy(model = id, effort = it.effort?.takeIf { _ -> EffortSupport.supports(id) }) } }
    fun setSearches(n: Int) { val v = n.coerceIn(Research2Config.MIN_SEARCHES, Research2Config.MAX_SEARCHES); saved["searches"] = v; updateChoices { it.copy(searches = v) } }
    fun setLimit(cents: Int?) { saved["limit"] = cents; saved["nolimit"] = cents == null; updateChoices { it.copy(limitCents = cents, noLimit = cents == null) } }
    fun setEffort(e: String?) { saved["effort"] = e; updateChoices { it.copy(effort = e) } }
    fun toggleAdjust() { updateChoices { it.copy(adjustOpen = !it.adjustOpen) }; saved["adjust"] = local.value.choices.adjustOpen }
    fun setScope(scope: ResearchScope) {
        saved["s_title"] = scope.shareTitle; saved["s_notes"] = scope.shareNotes; saved["s_steps"] = scope.shareSteps; saved["s_locale"] = scope.shareLocale
        updateChoices { it.copy(scope = scope) }
    }
    private fun updateChoices(f: (ThreadChoices) -> ThreadChoices) = local.update { it.copy(choices = f(it.choices)) }

    /** Unticking an earlier finding excludes it now and for every later turn (OD-4); ticking it again before Start undoes only this turn's choice. */
    fun toggleExclude(claimId: String) = viewModelScope.launch {
        excluded = if (claimId in excluded) excluded - claimId else excluded + claimId
        saved["excl"] = excluded.toTypedArray()
        rebuild(investigationId.value?.let { store.get(it) }, local.value.choices)
    }

    fun openPayload() = local.update { it.copy(payloadSheet = true) }
    fun closePayload() = local.update { it.copy(payloadSheet = false) }

    /** Writes the authorization shown on the card, then starts the paid turn. */
    fun start() = viewModelScope.launch {
        val s = state.value
        val inv = investigationId.value ?: return@launch
        val turn = s.pending ?: return@launch
        // From the choices as they are now, not a preview that may still be catching up with a tap.
        val cfg = configFor(local.value.choices, turn, settings.current())
        val gate = service.gate()
        if (gate.blocker == ResearchError.NoResearchConsent) { local.update { it.copy(showExplainer = true) }; return@launch }
        gate.blocker?.let { b -> local.update { it.copy(error = b, gate = gate) }; return@launch }
        if (excluded.isNotEmpty()) service.excludeClaims(inv, turn.id, excluded)
        val fresh = store.get(inv) ?: return@launch
        val t = fresh.turns.first { it.id == turn.id }
        // The record written is rebuilt from the stored turn at this moment: exactly what the card shows.
        val auth = service.authorization(fresh, t, service.contextItems(fresh.taskId, s.choices.scope), cfg)
        when (val r = service.authorize(inv, turn.id, auth, cfg)) {
            is StartResult.Started -> { excluded = emptySet(); saved["excl"] = emptyArray<String>(); local.update { it.copy(error = null) } }
            is StartResult.Rejected -> local.update { it.copy(error = r.error) }
        }
    }

    fun acceptExplainer() = viewModelScope.launch {
        service.acceptExplainer()
        local.update { it.copy(showExplainer = false) }
        refresh().join()
        start()
    }

    fun dismissExplainer() = local.update { it.copy(showExplainer = false) }

    fun decline() = viewModelScope.launch {
        val inv = investigationId.value ?: return@launch
        val t = state.value.pending ?: return@launch
        service.decline(inv, t.id)
    }

    // ------------------------------------------------------------------ run

    fun openStop() = local.update { it.copy(stopSheet = true) }
    fun closeStop() = local.update { it.copy(stopSheet = false) }
    fun stop(keep: Boolean) { val r = state.value.running ?: return; local.update { it.copy(stopSheet = false) }; service.stop(r.turnId, keep) }
    fun dismissFinished() { state.value.finished?.let { service.dismiss(it.turnId) } }

    // ------------------------------------------------------------------ results

    fun openEvidence(turnId: String, claimId: String) = local.update { it.copy(evidenceClaim = turnId to claimId) }
    fun closeEvidence() = local.update { it.copy(evidenceClaim = null) }
    fun openSource(turnId: String, n: Int) = local.update { it.copy(sourceSheet = turnId to n) }
    fun closeSource() = local.update { it.copy(sourceSheet = null) }

    fun openReview(turnId: String, onReady: (taskId: String, setId: String) -> Unit) = viewModelScope.launch {
        val inv = investigationId.value ?: return@launch
        val set = proposals.ensure(inv, turnId) ?: return@launch
        onReady(set.targetId, set.id)
    }

    // ------------------------------------------------------------------ association (RES-130 · N)

    fun openLink() = local.update { it.copy(linkSheet = true) }
    fun closeLink() = local.update { it.copy(linkSheet = false) }
    fun link(taskId: String, title: String) = viewModelScope.launch {
        val inv = investigationId.value ?: return@launch
        service.associate(inv, taskId, "Linked to “${title.take(60)}”")
        local.update { it.copy(linkSheet = false, message = "Linked to “${title.take(60)}”. Proposals from new turns can now go to this task.") }
    }
    fun unlink() = viewModelScope.launch {
        val inv = investigationId.value ?: return@launch
        service.associate(inv, null, "Unlinked from its task")
        local.update { it.copy(message = "Unlinked. The research stays here; the task is unchanged.") }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val inv = investigationId.value ?: return@launch
        service.delete(inv)
        onDone()
    }

    fun messageShown() = local.update { it.copy(message = null) }
}

data class HomeUi(val loading: Boolean = true, val investigations: List<Investigation> = emptyList(), val taskTitles: Map<String, String> = emptyMap(), val run: TurnRunState = TurnRunState.Idle, val now: Instant = Instant.EPOCH)

/** Every investigation on this phone, newest first; standalone and task-linked alike. Readable offline. */
class InvestigationHomeViewModel(store: InvestigationRepository, tasks: TaskRepository, service: InvestigationService, clock: AppClock, taskFilter: String?) : ViewModel() {
    val state: StateFlow<HomeUi> = combine(if (taskFilter != null) store.observeForTask(taskFilter) else store.observeAll(), tasks.observeSummaries(), service.state) { list, summaries, run ->
        HomeUi(false, list, summaries.associate { it.task.id to it.task.title }, run, clock.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())
}
