package com.lifeadmin.autopilot.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.ai.AiAvailability
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.security.ApiKeyStore
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineSpec
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.scheduling.ReminderPresets
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything typed into the capture sheet. Lives in the ViewModel so it survives rotation and leaving the sheet. */
data class CaptureDraft(
    val text: String = "",
    val notes: String = "",
    val priority: Priority = Priority.NORMAL,
    val deadline: DeadlineSpec? = null,
    /** Follow-up switch: on = Save & nudge (actionable), off = Save to Inbox. */
    val nudge: Boolean = true,
    /** Custom schedule chosen in the Follow-up sheet; null = the default preset from Settings. */
    val policy: ReminderPolicy? = null,
    val detailsOpen: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isBlank()
}

data class CaptureUiState(
    val draft: CaptureDraft = CaptureDraft(),
    val settings: AppSettings = AppSettings(),
    val saving: Boolean = false,
    val error: String? = null,
    /** AI availability, refreshed when the sheet opens and after setup. */
    val availability: AiAvailability? = null,
    /** The in-place "Turn on AI planning?" sheet. */
    val aiSetup: Boolean = false,
    val aiSetupBusy: Boolean = false,
    val aiSetupError: String? = null,
    val hasKey: Boolean = false,
    val keyHint: String? = null,
) {
    /** Effective follow-up for the "Nudge me …" row. */
    fun effectivePolicy(now: java.time.Instant, zone: java.time.ZoneId): ReminderPolicy =
        draft.policy ?: ReminderPresets.applyTo(ReminderPolicy(id = "", taskId = "", lastEditedAt = now), settings.defaultPreset, now.atZone(zone).toLocalDate()).copy(enabled = true)
}

class CaptureViewModel(
    private val actions: TaskActions,
    private val repository: TaskRepository,
    private val settingsRepository: SettingsRepository,
    private val keyStore: ApiKeyStore,
    private val aiPlanning: AiPlanningService,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
) : ViewModel() {
    private val draft = MutableStateFlow(CaptureDraft())
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    init {
        viewModelScope.launch { settingsRepository.settings.collect { s -> _state.update { it.copy(settings = s) } } }
        viewModelScope.launch { draft.collect { d -> _state.update { it.copy(draft = d) } } }
        refreshAvailability()
    }

    fun now() = clock.now()
    fun zone() = zoneProvider.current()

    fun refreshAvailability() = viewModelScope.launch {
        val s = settingsRepository.current()
        val (has, hint) = withContext(Dispatchers.IO) { keyStore.hasKey() to keyStore.hint() }
        _state.update { it.copy(availability = aiPlanning.availability(s), hasKey = has, keyHint = hint) }
    }

    fun update(transform: (CaptureDraft) -> CaptureDraft) {
        draft.update(transform)
        _state.update { it.copy(error = null) }
    }

    fun setText(text: String) = update { it.copy(text = text.take(AiConfig.MAX_INPUT_CHARS)) }
    fun setNotes(notes: String) = update { it.copy(notes = notes.take(TaskRepository.MAX_NOTES)) }
    fun setPriority(p: Priority) = update { it.copy(priority = p) }
    fun setDeadline(d: DeadlineSpec?) = update { it.copy(deadline = d?.takeIf { s -> s.type != DeadlineType.NONE }) }
    fun setNudge(on: Boolean) = update { it.copy(nudge = on) }
    fun setPolicy(p: ReminderPolicy?) = update { it.copy(policy = p, nudge = p?.enabled ?: it.nudge) }
    fun toggleDetails() = update { it.copy(detailsOpen = !it.detailsOpen) }

    /** Pre-fills the field from an example prompt (only when nothing has been typed yet). */
    fun prefill(text: String) = update { if (it.text.isBlank()) it.copy(text = text.take(AiConfig.MAX_INPUT_CHARS)) else it }

    /** Full text sent to "Plan with AI": the sentence plus any notes typed in the disclosure. */
    fun planText(): String = listOf(draft.value.text, draft.value.notes).filter { it.isNotBlank() }.joinToString("\n")

    /** Title = first line (≤ 300 chars); the rest of the text joins the notes. */
    private fun split(d: CaptureDraft): Pair<String, String?> {
        val text = d.text.trim()
        val title = text.lineSequence().first().take(TaskRepository.MAX_TITLE).trim()
        val remainder = text.removePrefix(text.lineSequence().first()).trim()
        val notes = listOf(remainder, d.notes.trim()).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }?.take(TaskRepository.MAX_NOTES)
        return title to notes
    }

    /**
     * Commits exactly once per tap (re-entrant taps are ignored while saving) and resets the
     * draft on success so the sheet opens empty next time.
     */
    fun save(onSaved: (taskId: String, withReminder: Boolean, firstNudge: String?) -> Unit) {
        val d = draft.value
        if (d.isEmpty || _state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val (title, notes) = split(d)
            runCatching { actions.capture(title, notes, d.priority, d.nudge, d.policy, d.deadline) }
                .onSuccess { r ->
                    draft.value = CaptureDraft()
                    _state.update { it.copy(saving = false) }
                    val first = r.firstNudge?.let { Format.relativeLower(it.plannedAt, clock.now(), zoneProvider.current()) }
                    onSaved(r.taskId, d.nudge, first)
                }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.userMessage()) } }
        }
    }

    /** "Plan with AI": continue when ready, otherwise open the in-place setup sheet. */
    fun planWithAi(onReady: (String) -> Unit) = viewModelScope.launch {
        if (draft.value.isEmpty) return@launch
        val s = settingsRepository.current()
        when (aiPlanning.availability(s)) {
            is AiAvailability.Ready -> onReady(planText())
            else -> { refreshAvailability(); _state.update { it.copy(aiSetup = true, aiSetupError = null) } }
        }
    }

    fun closeAiSetup() = _state.update { it.copy(aiSetup = false, aiSetupError = null) }

    /**
     * "Turn on & plan": records the privacy/billing consent, stores the key the user pasted (if
     * any), turns AI planning on, then continues to the plan only when the service reports Ready.
     * The key never enters UI state; it goes straight to the encrypted store.
     */
    fun turnOnAndPlan(rawKey: String, onReady: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(aiSetupBusy = true, aiSetupError = null) }
        val keyError = withContext(Dispatchers.IO) {
            if (rawKey.isBlank()) null
            else if (ApiKeyStore.normalize(rawKey) == null) "That doesn't look like an Anthropic API key (it usually starts with “sk-ant-”)."
            else runCatching { keyStore.save(rawKey) }.exceptionOrNull()?.let { "Couldn't store the key securely on this device." }
        }
        if (keyError != null) {
            _state.update { it.copy(aiSetupBusy = false, aiSetupError = keyError) }
            return@launch
        }
        settingsRepository.update { it.copy(aiEnabled = true, aiConsentAt = it.aiConsentAt ?: clock.now()) }
        val s = settingsRepository.current()
        val availability = aiPlanning.availability(s)
        val (has, hint) = withContext(Dispatchers.IO) { keyStore.hasKey() to keyStore.hint() }
        _state.update { it.copy(aiSetupBusy = false, availability = availability, hasKey = has, keyHint = hint) }
        when (availability) {
            is AiAvailability.Ready -> { _state.update { it.copy(aiSetup = false) }; onReady(planText()) }
            is AiAvailability.NeedsSetup -> _state.update { it.copy(aiSetupError = if (availability.needsKey) "Add your Anthropic API key to continue." else "AI planning still needs setting up.") }
            AiAvailability.Off -> _state.update { it.copy(aiSetupError = "AI planning could not be turned on.") }
        }
    }
}
