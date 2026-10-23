package com.lifeadmin.autopilot.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.security.ApiKeyStore
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class AiSettingsState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val consentAt: Instant? = null,
    val hasKey: Boolean = false,
    val keyHint: String? = null,
    val model: String = AiConfig.DEFAULT_MODEL,
    val requestsToday: Int = 0,
    val testing: Boolean = false,
    /** Result of the last "Test connection": null = not run. */
    val testResult: String? = null,
    val testOk: Boolean = false,
    val message: String? = null,
    /** Web research (M3): today's count, saved briefs, whether the one-time note was accepted. */
    val researchToday: Int = 0,
    val briefCount: Int = 0,
    val researchExplainerAccepted: Boolean = false,
)

class AiSettingsViewModel(
    private val settings: SettingsRepository,
    private val keyStore: ApiKeyStore,
    private val service: AiPlanningService,
    private val clock: AppClock,
    private val research: com.lifeadmin.autopilot.ai.investigation.InvestigationService? = null,
    private val briefs: com.lifeadmin.autopilot.data.repository.ResearchRepository? = null,
    private val investigations: com.lifeadmin.autopilot.data.repository.InvestigationRepository? = null,
    /** Drops the cached research HTTP client that still holds an old key. */
    private val forgetResearchClient: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(AiSettingsState())
    val state: StateFlow<AiSettingsState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val s = settings.current()
        val (has, hint) = withContext(Dispatchers.IO) { keyStore.hasKey() to keyStore.hint() }
        _state.update {
            it.copy(
                loading = false, enabled = s.aiEnabled, consentAt = s.aiConsentAt, hasKey = has, keyHint = hint,
                model = AiConfig.modelOrDefault(s.aiModel), requestsToday = service.requestsToday(),
                researchToday = research?.requestsToday(s) ?: 0,
                briefCount = (briefs?.getAll()?.size ?: 0) + (investigations?.getAll()?.size ?: 0),
                researchExplainerAccepted = s.researchExplainerVersion >= com.lifeadmin.autopilot.ai.research.ResearchConfig.EXPLAINER_VERSION,
            )
        }
    }

    /** "Delete all research": every saved brief plus the one-time note acceptance. Tasks are untouched. */
    fun deleteAllResearch() = viewModelScope.launch {
        research?.deleteAll()
        briefs?.deleteAll()
        settings.update { it.copy(researchExplainerVersion = 0) }
        _state.update { it.copy(message = "All research results were deleted. Your tasks are unchanged.") }
        refresh()
    }

    /** Turning on requires the privacy disclosure to have been accepted (handled by the screen). */
    fun setEnabled(on: Boolean) = viewModelScope.launch {
        settings.update { it.copy(aiEnabled = on) }
        refresh()
    }

    fun acceptConsentAndEnable() = viewModelScope.launch {
        settings.update { it.copy(aiEnabled = true, aiConsentAt = it.aiConsentAt ?: clock.now()) }
        refresh()
    }

    fun setModel(model: String) = viewModelScope.launch {
        if (AiConfig.MODELS.none { it.id == model }) return@launch
        settings.update { it.copy(aiModel = model) }
        _state.update { it.copy(testResult = null) }
        refresh()
    }

    /** @return an error message, or null when saved. The raw key is never kept in state. */
    fun saveKey(raw: String, onDone: (String?) -> Unit) = viewModelScope.launch {
        val error = withContext(Dispatchers.IO) {
            if (ApiKeyStore.normalize(raw) == null) "That doesn't look like an Anthropic API key (it usually starts with “sk-ant-”)."
            else runCatching { keyStore.save(raw) }.exceptionOrNull()?.let { "Couldn't store the key securely on this device." }
        }
        _state.update { it.copy(testResult = null, message = if (error == null) "API key saved (encrypted on this device)" else null) }
        refresh()
        onDone(error)
    }

    fun deleteKey() = viewModelScope.launch {
        withContext(Dispatchers.IO) { keyStore.delete() }
        forgetResearchClient()
        _state.update { it.copy(testResult = null, message = "API key deleted from this device") }
        refresh()
    }

    fun testConnection() = viewModelScope.launch {
        _state.update { it.copy(testing = true, testResult = null) }
        val error = service.testConnection()
        _state.update {
            it.copy(
                testing = false, testOk = error == null,
                testResult = error?.message ?: "Connected. The key works with ${AiConfig.label(it.model)} (no tokens were used).",
            )
        }
    }

    fun deleteAllAiData() = viewModelScope.launch {
        service.deleteAllAiData()
        forgetResearchClient()
        _state.update { it.copy(testResult = null, message = "AI settings and the API key were deleted. Your tasks are unchanged.") }
        refresh()
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
