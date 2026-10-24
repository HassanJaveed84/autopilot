package com.lifeadmin.autopilot.ui.investigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.ai.AiAvailability
import com.lifeadmin.autopilot.ai.NetworkStatus
import com.lifeadmin.autopilot.ai.aifirst.AfAuthorization
import com.lifeadmin.autopilot.ai.aifirst.AfConfig
import com.lifeadmin.autopilot.ai.aifirst.AfRunState
import com.lifeadmin.autopilot.ai.aifirst.AfStatus
import com.lifeadmin.autopilot.ai.aifirst.AfThread
import com.lifeadmin.autopilot.ai.aifirst.AfTurn
import com.lifeadmin.autopilot.ai.aifirst.AskResult
import com.lifeadmin.autopilot.ai.aifirst.EffortSupport
import com.lifeadmin.autopilot.ai.aifirst.ResearchOnlineService
import com.lifeadmin.autopilot.ai.aifirst.StartResult
import com.lifeadmin.autopilot.ai.aifirst.SuggestResult
import com.lifeadmin.autopilot.ai.context.ContextItem
import com.lifeadmin.autopilot.ai.investigation.Investigation
import com.lifeadmin.autopilot.ai.investigation.InvestigationProposalSets
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchGate
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.usage.UsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageTotals
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.AfThreadRepository
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.data.repository.ResearchThreadSummary
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.TaskSummary
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** What the person has chosen on the authorization card; null = the saved default. Kept across recreation. */
data class ThreadChoices(
    val model: String? = null,
    val searches: Int? = null,
    val limitCents: Int? = null,
    /** True once the person picked an effort (null then means "model default"). */
    val effortChosen: Boolean = false,
    val effort: String? = null,
    val scope: ResearchScope = ResearchScope(),
    val adjustOpen: Boolean = false,
)

data class ThreadUi(
    val loading: Boolean = true,
    val thread: AfThread? = null,
    /** Set instead of [thread] for research saved by the earlier engine: shown read-only. */
    val legacy: Investigation? = null,
    val taskId: String? = null,
    val taskTitle: String? = null,
    val question: String = "",
    val aiReady: Boolean = false,
    val asking: Boolean = false,
    val choices: ThreadChoices = ThreadChoices(),
    val config: AfConfig? = null,
    val context: List<ContextItem> = emptyList(),
    val authorization: AfAuthorization? = null,
    val gate: ResearchGate? = null,
    val run: AfRunState = AfRunState.Idle,
    val monthSoFar: UsageTotals? = null,
    val monthlyBudgetCents: Int = 0,
    val error: ResearchError? = null,
    val message: String? = null,
    val showExplainer: Boolean = false,
    val stopSheet: Boolean = false,
    /** Turn id whose "what was sent" sheet is open, or [PENDING] for the card's preview. */
    val payloadFor: String? = null,
    val source: Pair<String, Int>? = null,
    val linkSheet: Boolean = false,
    /** The suggestion confirmation: turn id and exactly what would be sent. */
    val suggestFor: Pair<String, String>? = null,
    val suggesting: Boolean = false,
    /** Turn id → pending items of its existing suggestion set (null: none made yet). */
    val suggestions: Map<String, Int> = emptyMap(),
    val tasks: List<TaskSummary> = emptyList(),
    val online: Boolean? = null,
    val now: Instant = Instant.EPOCH,
) {
    val pending: AfTurn? get() = thread?.turns?.lastOrNull { it.status.pending }
    val running: AfRunState.Running? get() = (run as? AfRunState.Running)?.takeIf { it.threadId == thread?.id }
    val finished: AfRunState.Finished? get() = (run as? AfRunState.Finished)?.takeIf { it.threadId == thread?.id }
    val busyElsewhere: Boolean get() = run is AfRunState.Running && running == null
    val shownTurns: List<AfTurn> get() = thread?.turns.orEmpty().filter { !it.status.pending && it.status != AfStatus.RUNNING }
    val overMonthlyBudget: Boolean get() = monthlyBudgetCents > 0 && (monthSoFar?.estimate?.micros ?: 0L) >= monthlyBudgetCents * 10_000L
    /** The approved spend can't even cover one call's high estimate: research would send nothing. */
    val limitTooLow: Boolean get() = authorization?.let { a -> a.estimateHighMicros != null && a.estimateHighMicros > a.config.spendLimitCents * 10_000L } ?: false
    val canStart: Boolean get() = pending?.status == AfStatus.READY && authorization != null && running == null && !busyElsewhere && !limitTooLow &&
        gate?.blocker.let { it == null || it == ResearchError.NoResearchConsent }

    companion object {
        const val PENDING = "pending"
    }
}

/**
 * One research thread: question, clarification, authorization, the run, answers turn by turn,
 * follow-ups, task suggestions and association. Opening it sends nothing; asking sends only the
 * question for the small clarification check; research starts only from [start].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvestigationThreadViewModel(
    private val saved: SavedStateHandle,
    initialThreadId: String?,
    initialTaskId: String?,
    initialQuestion: String,
    private val service: ResearchOnlineService,
    private val store: AfThreadRepository,
    private val legacyStore: InvestigationRepository,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val ledger: UsageLedger,
    private val legacyProposals: InvestigationProposalSets,
    private val network: NetworkStatus,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
) : ViewModel() {
    private val threadId = MutableStateFlow(saved.get<String>("inv") ?: initialThreadId)
    private val local = MutableStateFlow(
        ThreadUi(
            question = saved.get<String>("q") ?: initialQuestion,
            taskId = initialTaskId,
            choices = ThreadChoices(
                model = saved.get("model"), searches = saved.get("searches"), limitCents = saved.get("limit"),
                effortChosen = saved.get<Boolean>("effort_set") ?: false, effort = saved.get("effort"),
                scope = ResearchScope(saved.get("s_title") ?: true, saved.get("s_notes") ?: true, saved.get("s_steps") ?: false, saved.get("s_locale") ?: true),
                adjustOpen = saved.get<Boolean>("adjust") ?: false,
            ),
        ),
    )
    private val previewLock = Mutex()
    private val legacyFlag = MutableStateFlow<Boolean?>(null)

    private val threadFlow = threadId.flatMapLatest { id -> if (id == null) flowOf(null) else store.observe(id) }
    private val legacyFlow = combine(threadId, legacyFlag) { id, l -> id to l }.flatMapLatest { (id, l) -> if (id == null || l != true) flowOf(null) else legacyStore.observe(id) }

    val state: StateFlow<ThreadUi> = combine(combine(threadFlow, legacyFlow) { a, b -> a to b }, local, service.state, settings.settings, tasks.observeSummaries()) { (t, legacy), l, run, s, summaries ->
        val taskId = t?.taskId ?: legacy?.taskId ?: l.taskId.takeIf { threadId.value == null }
        l.copy(
            loading = threadId.value != null && t == null && legacy == null && l.loading,
            thread = t, legacy = legacy, taskId = taskId, taskTitle = summaries.firstOrNull { it.task.id == taskId }?.task?.title,
            run = run, monthlyBudgetCents = s.aiMonthlyBudgetCents, tasks = summaries.filter { !it.task.status.isTerminal }, now = clock.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch { threadId.collect { id -> legacyFlag.value = id?.let { store.isLegacy(it) } } }
        refresh()
        viewModelScope.launch {
            combine(threadFlow, local.map { it.choices }.distinctUntilChanged()) { t, ch -> t to ch }.distinctUntilChanged().collect { (t, _) -> rebuild(t) }
        }
        viewModelScope.launch { threadFlow.collect { t -> if (t != null) { local.update { it.copy(loading = false) }; counts(t) } } }
        viewModelScope.launch { legacyFlow.collect { if (it != null) local.update { s -> s.copy(loading = false) } } }
    }

    fun refresh() = viewModelScope.launch {
        val s = settings.current()
        val gate = service.gate(s)
        val zone = zoneProvider.current()
        val monthStart = clock.now().atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant()
        local.update { it.copy(gate = gate, aiReady = gate.availability is AiAvailability.Ready, online = network.isOnline(), monthSoFar = UsageTotals.of(ledger.getAll().filter { r -> !r.startedAt.isBefore(monthStart) })) }
        rebuild(threadId.value?.let { store.get(it) })
    }

    private suspend fun counts(t: AfThread) {
        val m = HashMap<String, Int>()
        for (turn in t.turns) service.existingSuggestions(t.id, turn.id)?.let { m[turn.id] = it.pending.size }
        local.update { it.copy(suggestions = m) }
    }

    /** The configuration the current choices resolve to: the card and Start both use this. */
    private fun configFor(ch: ThreadChoices, defaults: AfConfig): AfConfig {
        val model = ch.model ?: defaults.model
        val effort = (if (ch.effortChosen) ch.effort else defaults.effort)?.takeIf { EffortSupport.supports(model) }
        return defaults.copy(model = model, searchBudget = ch.searches ?: defaults.searchBudget, spendLimitCents = ch.limitCents ?: defaults.spendLimitCents, effort = effort)
    }

    private suspend fun authorizationFor(t: AfThread?, ch: ThreadChoices): Triple<AfConfig, List<ContextItem>, AfAuthorization?> {
        val cfg = configFor(ch, service.defaults(settings.current()))
        val ctx = service.contextItems(t?.taskId ?: local.value.taskId, ch.scope)
        val pending = t?.turns?.lastOrNull { it.status == AfStatus.READY }
        val auth = if (t != null && pending != null) service.preview(t.id, pending.id, ctx, cfg) else null
        return Triple(cfg, ctx, auth)
    }

    /** One rebuild at a time, always from the latest choices: a slow rebuild never overwrites what a later tap produced. */
    private suspend fun rebuild(t: AfThread?) = previewLock.withLock {
        val ch = local.value.choices
        val (cfg, ctx, auth) = authorizationFor(t, ch)
        local.update { if (it.choices != ch) it else it.copy(config = cfg, context = ctx, authorization = auth) }
    }

    // ------------------------------------------------------------------ asking

    fun setQuestion(q: String) { saved["q"] = q; local.update { it.copy(question = q, error = null) } }

    /** Saves the question as a turn and runs the clarification check (no web search). */
    fun ask() = viewModelScope.launch {
        val l = local.value
        if (l.asking) return@launch
        local.update { it.copy(asking = true, error = null) }
        when (val r = service.ask(l.question, threadId.value, l.taskId)) {
            is AskResult.Ready -> {
                threadId.value = r.threadId; saved["inv"] = r.threadId; saved["q"] = ""
                local.update { it.copy(asking = false, question = "") }
                rebuild(store.get(r.threadId))
                refresh()
            }
            is AskResult.Rejected -> local.update { it.copy(asking = false, error = r.error) }
        }
    }

    fun answerClarification(answer: String) = viewModelScope.launch {
        val id = threadId.value ?: return@launch
        val t = state.value.pending ?: return@launch
        service.answerClarification(id, t.id, answer)
    }

    fun skipClarification() = viewModelScope.launch {
        val id = threadId.value ?: return@launch
        val t = state.value.pending ?: return@launch
        service.skipClarification(id, t.id)
    }

    // ------------------------------------------------------------------ authorization card

    fun setModel(id: String) { saved["model"] = id; updateChoices { it.copy(model = id) } }
    fun setSearches(n: Int) { val v = n.coerceIn(Research2Config.MIN_SEARCHES, Research2Config.MAX_SEARCHES); saved["searches"] = v; updateChoices { it.copy(searches = v) } }
    fun setLimit(cents: Int) { saved["limit"] = cents; updateChoices { it.copy(limitCents = cents) } }
    fun setEffort(e: String?) { saved["effort"] = e; saved["effort_set"] = true; updateChoices { it.copy(effort = e, effortChosen = true) } }
    fun toggleAdjust() { updateChoices { it.copy(adjustOpen = !it.adjustOpen) }; saved["adjust"] = local.value.choices.adjustOpen }
    fun setScope(scope: ResearchScope) {
        saved["s_title"] = scope.shareTitle; saved["s_notes"] = scope.shareNotes; saved["s_steps"] = scope.shareSteps; saved["s_locale"] = scope.shareLocale
        updateChoices { it.copy(scope = scope) }
    }
    private fun updateChoices(f: (ThreadChoices) -> ThreadChoices) = local.update { it.copy(choices = f(it.choices)) }

    fun openPayload(turnId: String) = local.update { it.copy(payloadFor = turnId) }
    fun closePayload() = local.update { it.copy(payloadFor = null) }

    /**
     * Starts from the card exactly as shown. The authorization is rebuilt from the stored turn and the
     * current choices; if that differs from the card on screen (it was still catching up with a tap),
     * nothing starts and the refreshed card is shown instead.
     */
    fun start() = viewModelScope.launch {
        val id = threadId.value ?: return@launch
        val shown = state.value.authorization ?: return@launch
        val gate = service.gate()
        if (gate.blocker == ResearchError.NoResearchConsent) { local.update { it.copy(showExplainer = true) }; return@launch }
        gate.blocker?.let { b -> local.update { it.copy(error = b, gate = gate) }; return@launch }
        val (_, _, fresh) = authorizationFor(store.get(id), local.value.choices)
        if (fresh == null || fresh.copy(authorizedAtMs = 0) != shown.copy(authorizedAtMs = 0)) {
            rebuild(store.get(id))
            local.update { it.copy(message = "The card just changed. Check it, then start again.") }
            return@launch
        }
        val turnId = state.value.pending?.id ?: return@launch
        when (val r = service.start(id, turnId, fresh)) {
            is StartResult.Started -> local.update { it.copy(error = null) }
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
        val id = threadId.value ?: return@launch
        val t = state.value.pending ?: return@launch
        service.decline(id, t.id)
    }

    // ------------------------------------------------------------------ run

    fun openStop() = local.update { it.copy(stopSheet = true) }
    fun closeStop() = local.update { it.copy(stopSheet = false) }
    fun stop() { val r = state.value.running ?: return; local.update { it.copy(stopSheet = false) }; service.stop(r.turnId) }
    fun dismissFinished() { state.value.finished?.let { service.dismiss(it.turnId); refresh() } }

    // ------------------------------------------------------------------ results

    fun openSource(turnId: String, n: Int) = local.update { it.copy(source = turnId to n) }
    fun closeSource() = local.update { it.copy(source = null) }

    /** Opens the existing suggestions, or shows exactly what a new suggestion call would send. */
    fun openSuggest(turnId: String, onReady: (taskId: String, setId: String) -> Unit) = viewModelScope.launch {
        val id = threadId.value ?: return@launch
        service.existingSuggestions(id, turnId)?.let { onReady(it.targetId, it.id); return@launch }
        val payload = service.suggestionPayload(id, turnId) ?: return@launch
        local.update { it.copy(suggestFor = turnId to payload) }
    }

    fun closeSuggest() = local.update { it.copy(suggestFor = null) }

    fun confirmSuggest(onReady: (taskId: String, setId: String) -> Unit) = viewModelScope.launch {
        val id = threadId.value ?: return@launch
        val turnId = state.value.suggestFor?.first ?: return@launch
        local.update { it.copy(suggesting = true, suggestFor = null) }
        when (val r = service.suggest(id, turnId)) {
            is SuggestResult.Ready -> {
                local.update { it.copy(suggesting = false) }
                store.get(id)?.let { counts(it) }
                if (r.set.items.isEmpty()) local.update { it.copy(message = "Claude found nothing in this answer worth adding to the task.") } else onReady(r.set.targetId, r.set.id)
            }
            is SuggestResult.Failed -> local.update { it.copy(suggesting = false, message = r.message) }
        }
    }

    /** Legacy (M4) turns keep their own proposals, reviewed through the same Proposal Review. */
    fun openLegacyReview(turnId: String, onReady: (taskId: String, setId: String) -> Unit) = viewModelScope.launch {
        val id = threadId.value ?: return@launch
        val set = legacyProposals.ensure(id, turnId) ?: return@launch
        onReady(set.targetId, set.id)
    }

    // ------------------------------------------------------------------ association

    fun openLink() = local.update { it.copy(linkSheet = true) }
    fun closeLink() = local.update { it.copy(linkSheet = false) }
    fun link(taskId: String, title: String) = viewModelScope.launch {
        val id = threadId.value ?: return@launch
        service.associate(id, taskId, "Linked to “${title.take(60)}”")
        local.update { it.copy(linkSheet = false, message = "Linked to “${title.take(60)}”. You can now ask for task suggestions from its answers.") }
        refresh()
    }
    fun unlink() = viewModelScope.launch {
        val id = threadId.value ?: return@launch
        service.associate(id, null, "Unlinked from its task")
        local.update { it.copy(message = "Unlinked. The research stays here; the task is unchanged.") }
        refresh()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val id = threadId.value ?: return@launch
        service.delete(id)
        onDone()
    }

    fun messageShown() = local.update { it.copy(message = null) }
}

data class HomeUi(val loading: Boolean = true, val threads: List<ResearchThreadSummary> = emptyList(), val taskTitles: Map<String, String> = emptyMap(), val run: AfRunState = AfRunState.Idle, val now: Instant = Instant.EPOCH)

/** Every research thread on this phone, newest first; standalone and task-linked, either engine. Readable offline. */
class InvestigationHomeViewModel(store: AfThreadRepository, tasks: TaskRepository, service: ResearchOnlineService, clock: AppClock, taskFilter: String?) : ViewModel() {
    val state: StateFlow<HomeUi> = combine(store.observeSummaries(taskFilter), tasks.observeSummaries(), service.state) { list, summaries, run ->
        HomeUi(false, list, summaries.associate { it.task.id to it.task.title }, run, clock.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())
}
