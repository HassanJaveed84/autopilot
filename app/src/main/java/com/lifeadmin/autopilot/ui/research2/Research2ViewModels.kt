package com.lifeadmin.autopilot.ui.research2

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.ai.research2.ResearchSession
import com.lifeadmin.autopilot.ai.research2.ResearchSessionStore
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

// ------------------------------------------------------------------ legacy M3A.2 result, read-only since M4

/**
 * A saved M3A.2 session, shown exactly as it was stored (RES-092). Since M4 it can no longer be
 * continued here: follow-ups happen in its Investigation Thread. Proposals it already made can still
 * go through the canonical Proposal Review.
 */
data class Session2Ui(
    val loading: Boolean = true,
    val session: ResearchSession? = null,
    val details: TaskDetails? = null,
    val now: Instant = Instant.EPOCH,
    val sourceSheet: Int? = null,
    val candidateSheet: String? = null,
    /** Local, free re-rank of already-found candidates by style ("fresh", "woody"); null = model order. */
    val styleFilter: String? = null,
    val showAllSources: Boolean = false,
    val showLog: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /** Proposals still waiting for a decision in the canonical review (M2). */
    val pendingProposals: Int = 0,
) {
    val isStale: Boolean get() = session?.isStale(now) == true
}

class Session2ViewModel(
    private val saved: SavedStateHandle,
    val taskId: String,
    val sessionId: String,
    store: ResearchSessionStore,
    tasks: TaskRepository,
    settings: SettingsRepository,
    private val clock: AppClock,
    private val proposalSets: com.lifeadmin.autopilot.ai.research2.ResearchProposalSets,
    sets: com.lifeadmin.autopilot.data.repository.ProposalSetRepository,
) : ViewModel() {
    private val local = MutableStateFlow(
        Session2Ui(
            sourceSheet = null, styleFilter = saved["style"], showAllSources = saved["all_sources"] ?: false, showLog = saved["log"] ?: false,
        ),
    )

    private val sessionAndSets = combine(store.observe(sessionId), sets.observeForTarget(taskId)) { s, all ->
        val set = s?.let { ss -> all.firstOrNull { it.id == com.lifeadmin.autopilot.ai.research2.ResearchProposalBridge.setId(sessionId, ss.content.latestRun?.id ?: "run") } }
        s to (s?.let { proposalSets.pendingCount(it, set) } ?: 0)
    }

    val state: StateFlow<Session2Ui> = combine(sessionAndSets, tasks.observeDetails(taskId), local, settings.settings) { (s, pending), d, l, st ->
        l.copy(loading = false, session = s, details = d, now = clock.now(), settings = st, pendingProposals = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Session2Ui())

    /** Finds or creates the canonical proposal set for this session's latest run, then hands off to Proposal Review. */
    fun openReview(onReady: (setId: String) -> Unit) = viewModelScope.launch {
        proposalSets.ensure(sessionId)?.let { onReady(it.id) }
    }

    fun openSource(n: Int) = local.update { it.copy(sourceSheet = n) }
    fun closeSource() = local.update { it.copy(sourceSheet = null) }
    fun openCandidate(id: String) = local.update { it.copy(candidateSheet = id) }
    fun closeCandidate() = local.update { it.copy(candidateSheet = null) }
    fun setStyle(style: String?) { saved["style"] = style; local.update { it.copy(styleFilter = style) } }
    fun toggleAllSources() { local.update { it.copy(showAllSources = !it.showAllSources) }; saved["all_sources"] = local.value.showAllSources }
    fun toggleLog() { local.update { it.copy(showLog = !it.showLog) }; saved["log"] = local.value.showLog }
}
