package com.lifeadmin.autopilot.ui.research2

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.ai.NetworkStatus
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchGate
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research2.DeepResearchService
import com.lifeadmin.autopilot.ai.research2.DeepRunState
import com.lifeadmin.autopilot.ai.research2.DeliverableTemplate
import com.lifeadmin.autopilot.ai.research2.Requirement
import com.lifeadmin.autopilot.ai.research2.RequirementExtractor
import com.lifeadmin.autopilot.ai.research2.RequirementOrigin
import com.lifeadmin.autopilot.ai.research2.RequirementType
import com.lifeadmin.autopilot.ai.research2.Research2Config
import com.lifeadmin.autopilot.ai.research2.ResearchMode
import com.lifeadmin.autopilot.ai.research2.ResearchModes
import com.lifeadmin.autopilot.ai.research2.ResearchSession
import com.lifeadmin.autopilot.ai.research2.ResearchSessionStore
import com.lifeadmin.autopilot.ai.research2.RunConfig
import com.lifeadmin.autopilot.ai.research2.SessionAssembler
import com.lifeadmin.autopilot.ai.research2.SessionDraft
import com.lifeadmin.autopilot.ai.research2.StartResult2
import com.lifeadmin.autopilot.ai.usage.UsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageTotals
import com.lifeadmin.autopilot.ai.usage.UsdEstimate
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth

// ------------------------------------------------------------------ preflight

data class Preflight2Ui(
    val loading: Boolean = true,
    val taskTitle: String = "",
    val notesPreview: String? = null,
    val stepsCount: Int = 0,
    val request: String = "",
    val template: DeliverableTemplate = DeliverableTemplate.QUICK_ANSWER,
    /** True when the template was chosen by the user rather than the extractor. */
    val templatePinned: Boolean = false,
    val requirements: List<Requirement> = emptyList(),
    val extractorUnsure: Boolean = false,
    val mode: ResearchMode = ResearchMode.THOROUGH,
    /** Adjust values; null model = the mode's preset (after any settings override). */
    val model: String? = null,
    val searches: Int? = null,
    val limitCents: Int? = null,
    val adjustOpen: Boolean = false,
    val scope: ResearchScope = ResearchScope(),
    val gate: ResearchGate? = null,
    val localeLine: String = "",
    val monthSoFar: UsdEstimate? = null,
    val monthlyBudgetCents: Int = 0,
    val error: ResearchError? = null,
    val showExplainer: Boolean = false,
    val starting: Boolean = false,
    val newRequirement: String = "",
    val settings: AppSettings = AppSettings(),
) {
    val config: RunConfig get() = ResearchModes.config(mode, modelOverride = model ?: settingsModelFor(mode, settings), searches = searches, spendLimitCents = limitCents)
    val canStart: Boolean get() = !starting && request.isNotBlank() && request.length <= Research2Config.MAX_REQUEST_CHARS && requirements.isNotEmpty() && gate?.blocker.let { it == null || it == ResearchError.NoResearchConsent }
    val monthWarning: String? get() {
        val b = monthlyBudgetCents; val m = monthSoFar ?: return null
        if (b <= 0) return null
        val ratio = m.usd / (b / 100.0)
        return when {
            ratio >= 1.0 -> "You're past your monthly AI budget (${UsdEstimate.format(m.usd)} of ${UsdEstimate.format(b / 100.0)}). Starting asks you to confirm."
            ratio >= 0.8 -> "You've used ${(ratio * 100).toInt()}% of your monthly AI budget (${UsdEstimate.format(m.usd)} of ${UsdEstimate.format(b / 100.0)})."
            else -> null
        }
    }
    val overMonthlyBudget: Boolean get() = monthlyBudgetCents > 0 && (monthSoFar?.usd ?: 0.0) >= monthlyBudgetCents / 100.0

    companion object {
        fun settingsModelFor(mode: ResearchMode, s: AppSettings): String? = when (mode) {
            ResearchMode.QUICK -> s.researchQuickModel
            ResearchMode.THOROUGH -> s.researchThoroughModel
            ResearchMode.DEEP -> s.researchDeepModel
            ResearchMode.CUSTOM -> null
        }?.takeIf { it.isNotBlank() }
    }
}

/** Builds one authorized run from the request, the edited requirements and the depth/budget choices. Draft survives rotation. */
class Preflight2ViewModel(
    private val saved: SavedStateHandle,
    val taskId: String,
    initialRequest: String,
    private val service: DeepResearchService,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val ledger: UsageLedger,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(
        Preflight2Ui(
            request = saved["q"] ?: initialRequest,
            scope = ResearchScope(saved["s_title"] ?: true, saved["s_notes"] ?: true, saved["s_steps"] ?: false, saved["s_locale"] ?: true),
            adjustOpen = saved["adjust"] ?: false,
        ),
    )
    val state: StateFlow<Preflight2Ui> = _state.asStateFlow()
    private var confirmedOverBudget = false

    init {
        viewModelScope.launch {
            tasks.observeDetails(taskId).collect { d ->
                _state.update { it.copy(loading = false, taskTitle = d?.task?.title.orEmpty(), notesPreview = d?.task?.notes?.takeIf { n -> n.isNotBlank() }, stepsCount = d?.steps?.size ?: 0) }
            }
        }
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.update { st ->
                    st.copy(settings = s, monthlyBudgetCents = s.aiMonthlyBudgetCents, limitCents = if (saved.contains("limit_set")) st.limitCents else s.researchSpendLimitCents.takeIf { it > 0 })
                }
            }
        }
        viewModelScope.launch {
            ledger.observeAll().collect { all ->
                val zone = zoneProvider.current()
                val month = YearMonth.from(clock.now().atZone(zone))
                val rows = all.filter { YearMonth.from(it.startedAt.atZone(zone)) == month }
                _state.update { it.copy(monthSoFar = UsageTotals.of(rows).estimate) }
            }
        }
        restoreOrExtract()
        refresh()
    }

    private fun restoreOrExtract() {
        val savedReqs = saved.get<Array<String>>("reqs")
        if (savedReqs != null) {
            val reqs = savedReqs.mapNotNull { decodeReq(it) }
            _state.update { it.copy(requirements = reqs, template = saved.get<String>("template")?.let { t -> DeliverableTemplate.entries.firstOrNull { e -> e.name == t } } ?: it.template, templatePinned = saved["pinned"] ?: false, mode = saved.get<String>("mode")?.let { m -> ResearchMode.entries.firstOrNull { e -> e.name == m } } ?: it.mode, model = saved["model"], searches = saved["searches"], limitCents = saved["limit"]) }
        } else {
            reextract(_state.value.request)
        }
    }

    fun refresh() = viewModelScope.launch { _state.update { it.copy(gate = service.gate(), localeLine = service.localeLine()) } }

    fun setRequest(q: String) {
        saved["q"] = q
        _state.update { it.copy(request = q, error = null) }
        reextract(q)
    }

    /** Local, free. User-added requirements and a pinned template survive re-extraction. */
    private fun reextract(q: String) {
        val r = service.extract(q)
        _state.update { s ->
            val userAdded = s.requirements.filter { it.origin == RequirementOrigin.USER }
            val template = if (s.templatePinned) s.template else r.template
            val mode = if (saved.contains("mode_set")) s.mode else ResearchModes.defaultFor(template)
            s.copy(requirements = r.requirements + userAdded, extractorUnsure = !r.confident, template = template, mode = mode)
        }
        persistReqs()
    }

    fun setTemplate(t: DeliverableTemplate) {
        saved["template"] = t.name; saved["pinned"] = true
        _state.update { s -> s.copy(template = t, templatePinned = true, mode = if (saved.contains("mode_set")) s.mode else ResearchModes.defaultFor(t)) }
    }

    fun removeRequirement(id: String) { _state.update { it.copy(requirements = it.requirements.filterNot { r -> r.id == id }) }; persistReqs() }

    fun setNewRequirement(text: String) = _state.update { it.copy(newRequirement = text) }

    fun addRequirement() {
        val text = _state.value.newRequirement.trim().take(Research2Config.MAX_REQUIREMENT_CHARS)
        if (text.isEmpty()) return
        _state.update { s ->
            if (s.requirements.size >= Research2Config.MAX_REQUIREMENTS) s
            else s.copy(requirements = s.requirements + Requirement(RequirementExtractor.nextUserId(s.requirements), RequirementType.USER_ADDED, text, RequirementOrigin.USER), newRequirement = "")
        }
        persistReqs()
    }

    fun setMode(m: ResearchMode) { saved["mode"] = m.name; saved["mode_set"] = true; _state.update { it.copy(mode = m, model = null, searches = null) }; saved.remove<String>("model"); saved.remove<Int>("searches") }
    fun setModel(id: String) { saved["model"] = id; _state.update { it.copy(model = id) } }
    fun setSearches(n: Int) { val v = n.coerceIn(Research2Config.MIN_SEARCHES, Research2Config.MAX_SEARCHES); saved["searches"] = v; _state.update { it.copy(searches = v) } }
    fun setLimit(cents: Int?) {
        saved["limit"] = cents; saved["limit_set"] = true
        _state.update { it.copy(limitCents = cents) }
        viewModelScope.launch { settings.update { it.copy(researchSpendLimitCents = cents ?: 0) } }
    }
    fun toggleAdjust() { _state.update { it.copy(adjustOpen = !it.adjustOpen) }; saved["adjust"] = _state.value.adjustOpen }
    fun setScope(scope: ResearchScope) {
        saved["s_title"] = scope.shareTitle; saved["s_notes"] = scope.shareNotes; saved["s_steps"] = scope.shareSteps; saved["s_locale"] = scope.shareLocale
        _state.update { it.copy(scope = scope) }
    }

    /** Over the monthly budget: the first tap asks, the second confirms. */
    fun needsBudgetConfirmation(): Boolean = _state.value.overMonthlyBudget && !confirmedOverBudget
    fun confirmOverBudget() { confirmedOverBudget = true }

    fun start(onStarted: (runId: String, sessionId: String) -> Unit) = viewModelScope.launch {
        val gate = service.gate()
        if (gate.blocker == ResearchError.NoResearchConsent) { _state.update { it.copy(gate = gate, showExplainer = true) }; return@launch }
        launchRun(onStarted)
    }

    fun acceptExplainer(onStarted: (String, String) -> Unit) = viewModelScope.launch {
        service.acceptExplainer()
        _state.update { it.copy(showExplainer = false) }
        launchRun(onStarted)
    }

    fun dismissExplainer() = _state.update { it.copy(showExplainer = false, gate = null) }.also { refresh() }

    private suspend fun launchRun(onStarted: (String, String) -> Unit) {
        val s = _state.value
        _state.update { it.copy(starting = true, error = null) }
        val draft = SessionDraft(s.request, s.template, s.requirements, s.config, s.scope)
        when (val r = service.start(taskId, draft)) {
            is StartResult2.Started -> { _state.update { it.copy(starting = false) }; onStarted(r.runId, r.sessionId) }
            is StartResult2.Rejected -> _state.update { it.copy(starting = false, error = r.error, gate = null) }.also { refresh() }
        }
    }

    private fun persistReqs() { saved["reqs"] = _state.value.requirements.map { encodeReq(it) }.toTypedArray() }
    private fun encodeReq(r: Requirement) = listOf(r.id, r.type.name, r.origin.name, if (r.confident) "1" else "0", r.label).joinToString("\u0001")
    private fun decodeReq(s: String): Requirement? {
        val p = s.split("\u0001", limit = 5)
        if (p.size < 5) return null
        return Requirement(p[0], RequirementType.entries.firstOrNull { it.name == p[1] } ?: return null, p[4], RequirementOrigin.entries.firstOrNull { it.name == p[2] } ?: RequirementOrigin.EXTRACTED, p[3] == "1")
    }
}

// ------------------------------------------------------------------ investigation

data class InvestigationUi(val run: DeepRunState = DeepRunState.Idle, val stopSheet: Boolean = false)

/** Observes one run; a run id unknown after process death shows Interrupted (never re-sent). */
class InvestigationViewModel(val runId: String, val sessionId: String, val taskId: String, private val service: DeepResearchService) : ViewModel() {
    private val sheet = MutableStateFlow(false)
    val state: StateFlow<InvestigationUi> = combine(service.state, sheet) { r, s -> InvestigationUi(r, s) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvestigationUi(service.state.value))

    fun matches(s: DeepRunState): Boolean = when (s) {
        is DeepRunState.Running -> s.runId == runId
        is DeepRunState.Finished -> s.runId == runId
        DeepRunState.Idle -> false
    }

    fun openStop() { sheet.value = true }
    fun closeStop() { sheet.value = false }
    fun stop(keep: Boolean) { sheet.value = false; service.stop(runId, keep) }
    fun dismiss() = service.dismiss(runId)
}

// ------------------------------------------------------------------ result

data class Session2Ui(
    val loading: Boolean = true,
    val session: ResearchSession? = null,
    val details: TaskDetails? = null,
    val online: Boolean? = null,
    val now: Instant = Instant.EPOCH,
    val sourceSheet: Int? = null,
    val candidateSheet: String? = null,
    val continueSheet: Boolean = false,
    /** Local, free re-rank of already-found candidates by style ("fresh", "woody"); null = model order. */
    val styleFilter: String? = null,
    val showAllSources: Boolean = false,
    val showLog: Boolean = false,
    val running: Boolean = false,
    val runId: String? = null,
    val continueMode: ResearchMode = ResearchMode.QUICK,
    val continueSearches: Int? = null,
    val continueFocus: Set<String> = emptySet(),
    val continueNote: String = "",
    val continueError: ResearchError? = null,
    val settings: AppSettings = AppSettings(),
) {
    val isStale: Boolean get() = session?.isStale(now) == true
    val continueConfig: RunConfig get() = ResearchModes.config(continueMode, modelOverride = Preflight2Ui.settingsModelFor(continueMode, settings), searches = continueSearches, spendLimitCents = settings.researchSpendLimitCents.takeIf { it > 0 })
}

class Session2ViewModel(
    private val saved: SavedStateHandle,
    val taskId: String,
    val sessionId: String,
    store: ResearchSessionStore,
    tasks: TaskRepository,
    private val service: DeepResearchService,
    settings: SettingsRepository,
    private val network: NetworkStatus,
    private val clock: AppClock,
) : ViewModel() {
    private val local = MutableStateFlow(
        Session2Ui(
            sourceSheet = null, styleFilter = saved["style"], showAllSources = saved["all_sources"] ?: false, showLog = saved["log"] ?: false,
            continueFocus = (saved.get<Array<String>>("c_focus") ?: emptyArray()).toSet(), continueNote = saved["c_note"] ?: "",
            continueMode = saved.get<String>("c_mode")?.let { m -> ResearchMode.entries.firstOrNull { it.name == m } } ?: ResearchMode.QUICK, continueSearches = saved["c_searches"],
        ),
    )

    val state: StateFlow<Session2Ui> = combine(store.observe(sessionId), tasks.observeDetails(taskId), local, service.state, settings.settings) { s, d, l, run, st ->
        val running = (run as? DeepRunState.Running)?.takeIf { it.sessionId == sessionId }
        l.copy(loading = false, session = s, details = d, online = network.isOnline(), now = clock.now(), running = running != null, runId = running?.runId, settings = st)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Session2Ui())

    fun openSource(n: Int) = local.update { it.copy(sourceSheet = n) }
    fun closeSource() = local.update { it.copy(sourceSheet = null) }
    fun openCandidate(id: String) = local.update { it.copy(candidateSheet = id) }
    fun closeCandidate() = local.update { it.copy(candidateSheet = null) }
    fun setStyle(style: String?) { saved["style"] = style; local.update { it.copy(styleFilter = style) } }
    fun toggleAllSources() { local.update { it.copy(showAllSources = !it.showAllSources) }; saved["all_sources"] = local.value.showAllSources }
    fun toggleLog() { local.update { it.copy(showLog = !it.showLog) }; saved["log"] = local.value.showLog }

    fun openContinue() {
        val s = state.value.session
        val gaps = s?.let { SessionAssembler.gaps(it.content) }?.map { it.id }?.toSet().orEmpty()
        local.update { it.copy(continueSheet = true, continueError = null, continueFocus = if (it.continueFocus.isEmpty()) gaps else it.continueFocus) }
    }
    fun closeContinue() = local.update { it.copy(continueSheet = false) }
    fun toggleFocus(id: String) { local.update { it.copy(continueFocus = it.continueFocus.toMutableSet().also { f -> if (!f.add(id)) f.remove(id) }) }; saved["c_focus"] = local.value.continueFocus.toTypedArray() }
    fun setContinueNote(n: String) { saved["c_note"] = n; local.update { it.copy(continueNote = n) } }
    fun setContinueMode(m: ResearchMode) { saved["c_mode"] = m.name; local.update { it.copy(continueMode = m, continueSearches = null) } }
    fun setContinueSearches(n: Int) { val v = n.coerceIn(Research2Config.MIN_SEARCHES, Research2Config.MAX_SEARCHES); saved["c_searches"] = v; local.update { it.copy(continueSearches = v) } }

    fun continueResearch(onStarted: (runId: String) -> Unit) = viewModelScope.launch {
        val l = local.value
        when (val r = service.continueSession(sessionId, l.continueConfig, l.continueFocus.toList(), l.continueNote.ifBlank { null })) {
            is StartResult2.Started -> { local.update { it.copy(continueSheet = false) }; onStarted(r.runId) }
            is StartResult2.Rejected -> local.update { it.copy(continueError = r.error) }
        }
    }
}
