package com.lifeadmin.autopilot.ui.research

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.ai.NetworkStatus
import com.lifeadmin.autopilot.ai.research.ApplyOutcome
import com.lifeadmin.autopilot.ai.research.ApplyPlan
import com.lifeadmin.autopilot.ai.research.DetailField
import com.lifeadmin.autopilot.ai.research.ResearchActions
import com.lifeadmin.autopilot.ai.research.ResearchConfig
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchFocus
import com.lifeadmin.autopilot.ai.research.ResearchGate
import com.lifeadmin.autopilot.ai.research.ResearchPrompt
import com.lifeadmin.autopilot.ai.research.ResearchRunState
import com.lifeadmin.autopilot.ai.research.ResearchScope
import com.lifeadmin.autopilot.ai.research.ResearchService
import com.lifeadmin.autopilot.ai.research.StartResult
import com.lifeadmin.autopilot.ai.research.UndoToken
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.data.repository.ResearchBrief
import com.lifeadmin.autopilot.data.repository.ResearchRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

// ------------------------------------------------------------------ preflight

data class PreflightUi(
    val loading: Boolean = true,
    val taskTitle: String = "",
    val notesPreview: String? = null,
    val stepsCount: Int = 0,
    val question: String = "",
    val focus: Set<ResearchFocus> = emptySet(),
    val details: Map<DetailField, String> = emptyMap(),
    val scope: ResearchScope = ResearchScope(),
    val gate: ResearchGate? = null,
    val localeLine: String = "",
    /** Local heuristic only (no API call): the question depends on a country that wasn't given. */
    val jurisdictionGap: Boolean = false,
    /** Rejection from the service for the last Start tap. */
    val error: ResearchError? = null,
    val showExplainer: Boolean = false,
    val starting: Boolean = false,
) {
    val canStart: Boolean get() = !starting && question.isNotBlank() && question.length <= ResearchConfig.MAX_QUESTION_CHARS && gate?.blocker.let { it == null || it == ResearchError.NoResearchConsent }
}

/** Builds one request from the user's words and scope choices; the draft survives rotation. */
class ResearchPreflightViewModel(
    private val saved: SavedStateHandle,
    val taskId: String,
    initialQuestion: String,
    private val service: ResearchService,
    private val tasks: TaskRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(
        PreflightUi(
            question = saved["q"] ?: initialQuestion,
            focus = (saved.get<Array<String>>("focus") ?: emptyArray()).mapNotNull { f -> ResearchFocus.entries.firstOrNull { it.name == f } }.toSet(),
            details = DetailField.entries.mapNotNull { f -> saved.get<String>("d_${f.name}")?.let { f to it } }.toMap(),
            scope = ResearchScope(saved["s_title"] ?: true, saved["s_notes"] ?: true, saved["s_steps"] ?: false, saved["s_locale"] ?: true),
        ),
    )
    val state: StateFlow<PreflightUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            tasks.observeDetails(taskId).collect { d ->
                _state.update { it.copy(loading = false, taskTitle = d?.task?.title.orEmpty(), notesPreview = d?.task?.notes?.takeIf { n -> n.isNotBlank() }, stepsCount = d?.steps?.size ?: 0) }
                recomputeGap()
            }
        }
        refresh()
    }

    fun refresh() = viewModelScope.launch { _state.update { it.copy(gate = service.gate(), localeLine = service.localeLine()) } }

    fun setQuestion(q: String) { saved["q"] = q; _state.update { it.copy(question = q, error = null) }; recomputeGap() }
    fun toggleFocus(f: ResearchFocus) {
        val next = _state.value.focus.toMutableSet().also { if (!it.add(f)) it.remove(f) }
        saved["focus"] = next.map { it.name }.toTypedArray()
        _state.update { it.copy(focus = next) }
    }
    fun setDetail(f: DetailField, v: String) {
        val clean = v.take(ResearchConfig.MAX_DETAIL_CHARS)
        saved["d_${f.name}"] = clean
        _state.update { it.copy(details = it.details + (f to clean)) }
        recomputeGap()
    }
    fun setScope(scope: ResearchScope) {
        saved["s_title"] = scope.shareTitle; saved["s_notes"] = scope.shareNotes; saved["s_steps"] = scope.shareSteps; saved["s_locale"] = scope.shareLocale
        _state.update { it.copy(scope = scope) }
    }

    private fun recomputeGap() = _state.update { s ->
        s.copy(jurisdictionGap = s.details[DetailField.JURISDICTION].isNullOrBlank() && ResearchPrompt.looksJurisdictionBound(s.question + " " + s.taskTitle))
    }

    /** Start tap: show the one-time explainer first when it hasn't been accepted. */
    fun start(onStarted: (runId: String, question: String) -> Unit) = viewModelScope.launch {
        val gate = service.gate()
        if (gate.blocker == ResearchError.NoResearchConsent) { _state.update { it.copy(gate = gate, showExplainer = true) }; return@launch }
        launchRun(onStarted)
    }

    fun acceptExplainer(onStarted: (runId: String, question: String) -> Unit) = viewModelScope.launch {
        service.acceptExplainer()
        _state.update { it.copy(showExplainer = false) }
        launchRun(onStarted)
    }

    fun dismissExplainer() = _state.update { it.copy(showExplainer = false, gate = null) }.also { refresh() }

    private suspend fun launchRun(onStarted: (String, String) -> Unit) {
        val s = _state.value
        _state.update { it.copy(starting = true, error = null) }
        when (val r = service.start(taskId, s.question, s.focus, s.details, s.scope)) {
            is StartResult.Started -> { _state.update { it.copy(starting = false) }; onStarted(r.runId, s.question.trim()) }
            is StartResult.Rejected -> _state.update { it.copy(starting = false, error = r.error, gate = null) }.also { refresh() }
        }
    }
}

// ------------------------------------------------------------------ run

/** Observes one run; never resumes after process death (then the run id is unknown → Interrupted). */
class ResearchRunViewModel(val runId: String, val taskId: String, val question: String, private val service: ResearchService, clock: AppClock) : ViewModel() {
    val startedAt: Instant = clock.now()
    val state: StateFlow<ResearchRunState> = service.state

    fun matches(s: ResearchRunState): Boolean = when (s) {
        is ResearchRunState.Running -> s.runId == runId
        is ResearchRunState.Finished -> s.runId == runId
        ResearchRunState.Idle -> false
    }

    fun stop() = service.stop(runId)
    fun dismiss() = service.dismiss(runId)
}

// ------------------------------------------------------------------ brief

data class BriefUi(
    val loading: Boolean = true,
    val brief: ResearchBrief? = null,
    val details: TaskDetails? = null,
    val selected: Set<String> = emptySet(),
    val online: Boolean? = null,
    val now: Instant = Instant.EPOCH,
    val sourceSheet: Int? = null,
) {
    val isStale: Boolean get() = brief?.isStale(now) == true
}

class ResearchBriefViewModel(
    private val saved: SavedStateHandle,
    val taskId: String,
    val briefId: String,
    briefs: ResearchRepository,
    tasks: TaskRepository,
    private val network: NetworkStatus,
    private val clock: AppClock,
) : ViewModel() {
    private val selection = MutableStateFlow<Set<String>>((saved.get<Array<String>>("sel") ?: emptyArray()).toSet())
    private val sheet = MutableStateFlow<Int?>(null)
    private var selectedByDefault = false

    val state: StateFlow<BriefUi> = combine(briefs.observe(briefId), tasks.observeDetails(taskId), selection, sheet) { b, d, sel, sh ->
        // Pre-select sourced steps once; never anything without a source, never deadlines (none exist in M3A).
        if (b != null && !selectedByDefault && saved.get<Array<String>>("sel") == null) {
            selectedByDefault = true
            val applied = b.applied.map { it.moveId }.toSet()
            val defaults = b.content.moves.filter { it.refs.isNotEmpty() && it.kind != com.lifeadmin.autopilot.ai.research.MoveKind.CHECK && it.id !in applied }.map { it.id }.toSet()
            if (defaults.isNotEmpty()) { selection.value = defaults; saved["sel"] = defaults.toTypedArray() }
        }
        BriefUi(loading = false, brief = b, details = d, selected = sel, online = network.isOnline(), now = clock.now(), sourceSheet = sh)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BriefUi())

    fun toggle(moveId: String, on: Boolean) {
        val next = selection.value.toMutableSet().also { if (on) it.add(moveId) else it.remove(moveId) }
        selection.value = next
        saved["sel"] = next.toTypedArray()
    }

    fun openSource(n: Int) { sheet.value = n }
    fun closeSource() { sheet.value = null }
}

// ------------------------------------------------------------------ apply

sealed interface ApplyUi {
    data object Loading : ApplyUi
    data class Review(val plan: ApplyPlan, val brief: ResearchBrief, val details: TaskDetails, val conflict: String?) : ApplyUi
    data class Applied(val count: Int, val undo: UndoToken?, val undone: Boolean, val undoRefused: Boolean) : ApplyUi
    data class Failed(val message: String) : ApplyUi
}

class ApplyResearchViewModel(
    val taskId: String,
    val briefId: String,
    private val selected: Set<String>,
    private val briefs: ResearchRepository,
    private val tasks: TaskRepository,
    private val actions: ResearchActions,
) : ViewModel() {
    private val _state = MutableStateFlow<ApplyUi>(ApplyUi.Loading)
    val state: StateFlow<ApplyUi> = _state.asStateFlow()
    private var planned: ApplyPlan? = null
    private var observing = true

    init {
        viewModelScope.launch {
            combine(briefs.observe(briefId), tasks.observeDetails(taskId)) { b, d -> b to d }.collect { (b, d) ->
                if (!observing) return@collect
                if (b == null || d == null) { _state.value = ApplyUi.Failed("This brief or task no longer exists."); return@collect }
                val previous = planned
                val plan = actions.plan(b, d, selected)
                // The task changed while the review was open: the list below is refreshed; say so.
                val conflict = if (previous != null && previous.expectedUpdatedAt != plan.expectedUpdatedAt) "This task changed since you opened the brief. The list below is refreshed — check it again." else (_state.value as? ApplyUi.Review)?.conflict
                planned = plan
                _state.value = ApplyUi.Review(plan, b, d, conflict)
            }
        }
    }

    fun apply() = viewModelScope.launch {
        val plan = planned ?: return@launch
        observing = false
        when (val outcome = actions.apply(plan)) {
            is ApplyOutcome.Applied -> _state.value = ApplyUi.Applied(outcome.applied.size, outcome.undo, undone = false, undoRefused = false)
            is ApplyOutcome.Conflict -> { observing = true; _state.update { s -> (s as? ApplyUi.Review)?.copy(conflict = outcome.message) ?: s } }
            is ApplyOutcome.Rejected -> _state.value = ApplyUi.Failed(outcome.message)
        }
    }

    fun undo() = viewModelScope.launch {
        val s = _state.value as? ApplyUi.Applied ?: return@launch
        val token = s.undo ?: return@launch
        val ok = actions.undo(token)
        _state.value = s.copy(undone = ok, undoRefused = !ok)
    }
}

// ------------------------------------------------------------------ history

data class HistoryUi(val loading: Boolean = true, val taskTitle: String = "", val briefs: List<ResearchBrief> = emptyList(), val now: Instant = Instant.EPOCH, val running: Boolean = false)

class ResearchHistoryViewModel(val taskId: String, private val briefs: ResearchRepository, tasks: TaskRepository, service: ResearchService, clock: AppClock) : ViewModel() {
    val state: StateFlow<HistoryUi> = combine(briefs.observeForTask(taskId), tasks.observeDetails(taskId), service.state) { b, d, run ->
        HistoryUi(false, d?.task?.title.orEmpty(), b, clock.now(), running = run is ResearchRunState.Running && run.taskId == taskId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())

    fun delete(briefId: String) = viewModelScope.launch { briefs.delete(briefId) }
    fun deleteAll() = viewModelScope.launch { briefs.deleteForTask(taskId) }
}
