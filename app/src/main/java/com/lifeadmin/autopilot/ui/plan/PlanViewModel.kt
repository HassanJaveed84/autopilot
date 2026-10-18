package com.lifeadmin.autopilot.ui.plan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.ai.AiAvailability
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.PlanningError
import com.lifeadmin.autopilot.ai.PlanningMode
import com.lifeadmin.autopilot.ai.PlanningUsage
import com.lifeadmin.autopilot.ai.ProposalDraft
import com.lifeadmin.autopilot.ai.ProposalMapper
import com.lifeadmin.autopilot.ai.ProposalOutcome
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.ui.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlanPhase { INPUT, WORKING, REVIEW, SAVED }

data class PlanUiState(
    val mode: PlanningMode = PlanningMode.CAPTURE,
    val phase: PlanPhase = PlanPhase.INPUT,
    val text: String = "",
    val availability: AiAvailability? = null,
    val draft: ProposalDraft? = null,
    val error: PlanningError? = null,
    /** Validation message from mapping the user's edits; shown above the save button. */
    val saveError: String? = null,
    val saving: Boolean = false,
    val savedTaskId: String? = null,
    /** The approved plan turned on reminders (so the notification permission may be needed). */
    val savedWithReminders: Boolean = false,
    val usage: PlanningUsage? = null,
    val modelLabel: String = AiConfig.label(AiConfig.DEFAULT_MODEL),
    /** FOLLOW_THROUGH: the task as it is now, for the diff. */
    val current: TaskDetails? = null,
    val interrupted: Boolean = false,
)

/**
 * Drives "Plan with AI" (new task) and "Help me move this forward" (existing task). The text,
 * phase and draft live in [SavedStateHandle], so rotation and process death keep the user's
 * words and any proposal under review. Nothing is written until [approve].
 */
class PlanViewModel(
    private val handle: SavedStateHandle,
    private val service: AiPlanningService,
    private val repository: TaskRepository,
    private val actions: TaskActions,
    private val settings: SettingsRepository,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    /** Non-null for FOLLOW_THROUGH. */
    private val targetTaskId: String?,
    initialText: String,
) : ViewModel() {
    private val mode = if (targetTaskId != null) PlanningMode.FOLLOW_THROUGH else PlanningMode.CAPTURE

    private val _state = MutableStateFlow(restore(initialText))
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refreshAvailability()
        if (targetTaskId != null) viewModelScope.launch {
            _state.update { it.copy(current = repository.observeDetails(targetTaskId).first()) }
        }
    }

    private fun restore(initialText: String): PlanUiState {
        val text = handle.get<String>(KEY_TEXT) ?: initialText.also { handle[KEY_TEXT] = it }
        val draft = handle.get<String>(KEY_DRAFT)?.let(ProposalDraft::decode)
        val savedPhase = handle.get<String>(KEY_PHASE)?.let { p -> PlanPhase.entries.firstOrNull { it.name == p } }
        // A request in flight when the process died is gone; return to the text, nothing saved.
        val interrupted = savedPhase == PlanPhase.WORKING
        val phase = when {
            savedPhase == PlanPhase.SAVED -> PlanPhase.SAVED
            draft != null && savedPhase == PlanPhase.REVIEW -> PlanPhase.REVIEW
            else -> PlanPhase.INPUT
        }
        return PlanUiState(
            mode = mode, phase = phase, text = text, draft = draft.takeIf { phase == PlanPhase.REVIEW },
            savedTaskId = handle.get<String>(KEY_SAVED), interrupted = interrupted,
        )
    }

    fun refreshAvailability() = viewModelScope.launch {
        val s = settings.current()
        _state.update { it.copy(availability = service.availability(s), modelLabel = AiConfig.label(AiConfig.modelOrDefault(s.aiModel))) }
    }

    fun setText(text: String) {
        val clipped = text.take(AiConfig.MAX_INPUT_CHARS)
        handle[KEY_TEXT] = clipped
        _state.update { it.copy(text = clipped, error = null, interrupted = false) }
    }

    /** User-triggered network request. */
    fun requestPlan() {
        if (_state.value.phase == PlanPhase.WORKING) return
        setPhase(PlanPhase.WORKING)
        _state.update { it.copy(error = null, interrupted = false, saveError = null) }
        job = viewModelScope.launch {
            val text = _state.value.text
            val outcome = try {
                if (targetTaskId != null) service.proposeFollowThrough(targetTaskId, text) else service.proposeNewTask(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ProposalOutcome.Failed(PlanningError.Unexpected(e.javaClass.simpleName))
            }
            when (outcome) {
                is ProposalOutcome.Ready -> {
                    handle[KEY_DRAFT] = outcome.draft.encode()
                    setPhase(PlanPhase.REVIEW)
                    _state.update { it.copy(draft = outcome.draft, usage = outcome.usage) }
                }
                is ProposalOutcome.Failed -> {
                    setPhase(PlanPhase.INPUT)
                    _state.update { it.copy(error = outcome.error) }
                    refreshAvailability()
                }
            }
        }
    }

    /** Cancels the in-flight request; the text is kept and nothing is saved. */
    fun cancel() {
        job?.cancel()
        job = null
        setPhase(PlanPhase.INPUT)
        _state.update { it.copy(error = PlanningError.Cancelled) }
    }

    fun retry() = requestPlan()

    fun updateDraft(transform: (ProposalDraft) -> ProposalDraft) {
        val d = _state.value.draft ?: return
        val next = transform(d)
        handle[KEY_DRAFT] = next.encode()
        _state.update { it.copy(draft = next, saveError = null) }
    }

    /** Reject the proposal: back to the original text, which is untouched. */
    fun reject() {
        handle.remove<String>(KEY_DRAFT)
        setPhase(PlanPhase.INPUT)
        _state.update { it.copy(draft = null, saveError = null, error = null) }
    }

    /** Approve and write — exactly once (re-entrant taps and retries are idempotent). */
    fun approve() {
        val s = _state.value
        val draft = s.draft ?: return
        if (s.saving || s.phase == PlanPhase.SAVED) return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val now = clock.now()
            val zone = zoneProvider.current()
            runCatching {
                if (draft.mode == PlanningMode.FOLLOW_THROUGH) {
                    repository.applyTaskEdit(ProposalMapper.toEdit(draft, now, zone))
                    draft.targetTaskId!!
                } else {
                    repository.createPlannedTask(ProposalMapper.toCreate(draft, now, zone)).first
                }
            }.onSuccess { id ->
                handle[KEY_SAVED] = id
                handle.remove<String>(KEY_DRAFT)
                setPhase(PlanPhase.SAVED)
                _state.update { it.copy(saving = false, savedTaskId = id, savedWithReminders = draft.reminder.apply || draft.waiting.checkIn != null) }
            }.onFailure { e ->
                _state.update { it.copy(saving = false, saveError = e.userMessage()) }
            }
        }
    }

    /** "Save without AI": the normal offline capture path, never blocked by AI state. */
    fun saveWithoutAi() = viewModelScope.launch {
        val text = _state.value.text.trim()
        if (text.isEmpty()) return@launch
        val title = text.lineSequence().first().take(300)
        val notes = text.takeIf { it.length > title.length }
        runCatching { actions.quickCapture(title, withReminder = false, notes = notes) }
            .onSuccess { id -> handle[KEY_SAVED] = id; setPhase(PlanPhase.SAVED); _state.update { it.copy(savedTaskId = id) } }
            .onFailure { e -> _state.update { it.copy(saveError = e.userMessage()) } }
    }

    private fun setPhase(p: PlanPhase) {
        handle[KEY_PHASE] = p.name
        _state.update { it.copy(phase = p) }
    }

    companion object {
        const val KEY_TEXT = "plan_text"
        const val KEY_DRAFT = "plan_draft"
        const val KEY_PHASE = "plan_phase"
        const val KEY_SAVED = "plan_saved_task"
    }
}
