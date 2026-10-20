package com.lifeadmin.autopilot.ai.research

import com.lifeadmin.autopilot.ai.AiAvailability
import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.AiPlanningService
import com.lifeadmin.autopilot.ai.NetworkStatus
import com.lifeadmin.autopilot.ai.security.ApiKeyStore
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.ResearchRepository
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything the preflight needs to decide whether research can start, computed without any request. */
data class ResearchGate(
    val availability: AiAvailability,
    val explainerAccepted: Boolean,
    val requestsToday: Int,
    /** null = unknown; the request is then attempted. */
    val online: Boolean?,
) {
    val model: String? get() = (availability as? AiAvailability.Ready)?.model
    val remaining: Int get() = (ResearchConfig.MAX_REQUESTS_PER_DAY - requestsToday).coerceAtLeast(0)

    /** The first reason research cannot start right now, or null. */
    val blocker: ResearchError?
        get() = when (val a = availability) {
            AiAvailability.Off -> ResearchError.Disabled
            is AiAvailability.NeedsSetup -> if (a.needsConsent) ResearchError.NoConsent else ResearchError.NoApiKey
            is AiAvailability.Ready -> when {
                !explainerAccepted -> ResearchError.NoResearchConsent
                remaining == 0 -> ResearchError.DailyLimit
                online == false -> ResearchError.Offline
                else -> null
            }
        }
}

sealed interface ResearchOutcome {
    data class Saved(val briefId: String) : ResearchOutcome
    data class Failed(val error: ResearchError) : ResearchOutcome
}

/** The single research run the app may have in flight, observable from any screen. */
sealed interface ResearchRunState {
    data object Idle : ResearchRunState

    data class Running(
        val runId: String,
        val taskId: String,
        val question: String,
        val progress: ResearchProgress,
        val startedAt: Instant,
        /** Searches known to have run so far (never more than reported by the provider). */
        val searchesSeen: Int,
    ) : ResearchRunState

    data class Finished(val runId: String, val taskId: String, val question: String, val outcome: ResearchOutcome) : ResearchRunState
}

sealed interface StartResult {
    data class Started(val runId: String) : StartResult
    data class Rejected(val error: ResearchError) : StartResult
}

/**
 * Orchestrates consented web research: gating (AI on, consent, key, explainer, online, daily cap),
 * building the minimal request from the user's scope choices, the provider call in an app-scoped
 * job that survives navigation, parsing, and saving the brief. It never writes tasks — proposed
 * moves are applied later through [ResearchActions] after the user selects them.
 */
class ResearchService(
    private val settings: SettingsRepository,
    private val keyStore: ApiKeyStore,
    private val planning: AiPlanningService,
    private val provider: ResearchProvider,
    private val tasks: TaskRepository,
    private val briefs: ResearchRepository,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val ids: IdGenerator,
    private val scope: CoroutineScope,
    private val network: NetworkStatus = NetworkStatus.UNKNOWN,
    private val locale: () -> Locale = { Locale.getDefault() },
) {
    private val _state = MutableStateFlow<ResearchRunState>(ResearchRunState.Idle)
    val state: StateFlow<ResearchRunState> = _state.asStateFlow()

    private var job: Job? = null

    suspend fun gate(s: AppSettings? = null): ResearchGate {
        val st = s ?: settings.current()
        return ResearchGate(
            availability = planning.availability(st),
            explainerAccepted = st.researchExplainerVersion >= ResearchConfig.EXPLAINER_VERSION,
            requestsToday = requestsToday(st),
            online = network.isOnline(),
        )
    }

    suspend fun acceptExplainer() = settings.update { it.copy(researchExplainerVersion = ResearchConfig.EXPLAINER_VERSION) }

    suspend fun requestsToday(s: AppSettings? = null): Int {
        val st = s ?: settings.current()
        return if (st.researchUsageDay == today()) st.researchUsageCount else 0
    }

    /** "Tue 20 Oct 2026 · English (United States)" — only when the user shares date and language. */
    fun localeLine(): String {
        val loc = locale()
        val date = clock.now().atZone(zoneProvider.current()).toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.ENGLISH))
        return "$date · ${loc.getDisplayName(Locale.ENGLISH)}"
    }

    fun buildRequest(
        details: TaskDetails,
        question: String,
        focus: Set<ResearchFocus>,
        fields: Map<DetailField, String>,
        scope: ResearchScope,
    ): ResearchRequest = ResearchRequest(
        taskId = details.task.id,
        question = question.trim(),
        focus = focus,
        details = fields.mapValues { it.value.trim().take(ResearchConfig.MAX_DETAIL_CHARS) }.filterValues { it.isNotBlank() },
        title = if (scope.shareTitle) details.task.title else null,
        notes = if (scope.shareNotes) details.task.notes?.takeIf { it.isNotBlank() } else null,
        steps = if (scope.shareSteps) details.steps.map { it.title } else null,
        localeLine = if (scope.shareLocale) localeLine() else null,
        maxSearches = ResearchConfig.MAX_SEARCHES_PER_REQUEST,
    )

    /** Starts one research run; the result arrives in [state]. Nothing leaves the device if this rejects. */
    suspend fun start(
        taskId: String,
        question: String,
        focus: Set<ResearchFocus>,
        fields: Map<DetailField, String>,
        researchScope: ResearchScope,
    ): StartResult {
        val clean = question.trim()
        if (clean.isEmpty()) return StartResult.Rejected(ResearchError.EmptyQuestion)
        if (clean.length > ResearchConfig.MAX_QUESTION_CHARS) return StartResult.Rejected(ResearchError.QuestionTooLong)
        if (_state.value is ResearchRunState.Running) return StartResult.Rejected(ResearchError.Busy)
        val gate = gate()
        gate.blocker?.let { return StartResult.Rejected(it) }
        val model = gate.model ?: return StartResult.Rejected(ResearchError.NoApiKey)
        val key = keyStore.get() ?: return StartResult.Rejected(ResearchError.NoApiKey)
        val details = tasks.observeDetails(taskId).first() ?: return StartResult.Rejected(ResearchError.Unexpected("task missing"))
        val request = buildRequest(details, clean, focus, fields, researchScope)
        if (!reserve()) return StartResult.Rejected(ResearchError.DailyLimit)

        val runId = ids.newId()
        _state.value = ResearchRunState.Running(runId, taskId, clean, ResearchProgress.Sent, clock.now(), 0)
        job = scope.launch {
            val outcome: ResearchOutcome = try {
                val response = provider.research(request, key, model) { p ->
                    _state.update { s ->
                        if (s is ResearchRunState.Running && s.runId == runId) {
                            s.copy(progress = p, searchesSeen = (p as? ResearchProgress.Searching)?.count?.coerceAtLeast(s.searchesSeen) ?: s.searchesSeen)
                        } else s
                    }
                }
                when (response) {
                    is ResearchResponse.Failure -> ResearchOutcome.Failed(response.error)
                    is ResearchResponse.Success -> {
                        ensureActive() // cancelled while the answer was in hand: save nothing
                        val content = BriefParser.parse(
                            response.segments, response.searches, response.usage, response.truncated, request.sharedSummary(), ids::newId,
                        )
                        ResearchOutcome.Saved(briefs.save(taskId, clean, content))
                    }
                }
            } catch (e: CancellationException) {
                val seen = (_state.value as? ResearchRunState.Running)?.takeIf { it.runId == runId }?.searchesSeen ?: 0
                ResearchOutcome.Failed(ResearchError.Stopped(seen))
            } catch (e: Exception) {
                ResearchOutcome.Failed(ResearchError.Unexpected(e.javaClass.simpleName))
            }
            _state.update { s -> if (s is ResearchRunState.Running && s.runId == runId) ResearchRunState.Finished(runId, taskId, clean, outcome) else s }
        }
        return StartResult.Started(runId)
    }

    /** Cancels the run; the provider's HTTP call is aborted. Searches already run may still be billed. */
    fun stop(runId: String) {
        val s = _state.value as? ResearchRunState.Running ?: return
        if (s.runId != runId) return
        job?.cancel(CancellationException("stopped by user"))
    }

    /**
     * Cancels any run that is in flight (optionally only one task's) and waits until its job has
     * finished — including a save that may already be under way — so a delete that follows cannot
     * be undone by a late result. Searches already run may still be billed; the run ends as Stopped.
     */
    private suspend fun cancelActive(taskId: String? = null, reason: String) {
        val running = _state.value as? ResearchRunState.Running ?: return
        if (taskId != null && running.taskId != taskId) return
        val j = job ?: return
        j.cancel(CancellationException(reason))
        j.join()
    }

    /** Deletes one task's research; a request running for that task is stopped first and never saves. */
    suspend fun deleteResearchForTask(taskId: String) {
        cancelActive(taskId, "research deleted")
        briefs.deleteForTask(taskId)
    }

    /** Clears a finished result once a screen has shown it. */
    fun dismiss(runId: String) {
        _state.update { s -> if (s is ResearchRunState.Finished && s.runId == runId) ResearchRunState.Idle else s }
    }

    /**
     * Settings → "Delete all research": stops a running request, then removes every brief and the
     * explainer acceptance. Tasks are untouched; a late result from the stopped request is dropped.
     */
    suspend fun deleteAllResearch() {
        cancelActive(reason = "research deleted")
        briefs.deleteAll()
        settings.update { it.copy(researchExplainerVersion = 0) }
    }

    fun modelLabel(model: String): String = AiConfig.label(model)

    /** After the API key is deleted or replaced: drop the cached HTTP client that still holds the old key. */
    fun forgetCredentials() {
        (provider as? com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchProvider)?.reset()
    }

    // ------------------------------------------------------------------ internals

    /** Reserves one request against the research daily cap; false when it is reached. */
    private suspend fun reserve(): Boolean {
        var allowed = false
        val day = today()
        settings.update { s ->
            val used = if (s.researchUsageDay == day) s.researchUsageCount else 0
            if (used >= ResearchConfig.MAX_REQUESTS_PER_DAY) s else { allowed = true; s.copy(researchUsageDay = day, researchUsageCount = used + 1) }
        }
        return allowed
    }

    private fun today(): String = clock.now().atZone(zoneProvider.current()).toLocalDate().toString()
}
