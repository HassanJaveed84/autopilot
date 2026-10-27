package com.lifeadmin.autopilot.ui.investigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.ai.aifirst.AfStatus
import com.lifeadmin.autopilot.ai.aifirst.AfThread
import com.lifeadmin.autopilot.ai.aifirst.AfTurn
import com.lifeadmin.autopilot.ai.investigation.Investigation
import com.lifeadmin.autopilot.ai.investigation.InvestigationProposalSets
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.data.repository.AfThreadRepository
import com.lifeadmin.autopilot.data.repository.InvestigationRepository
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.ResearchThreadSummary
import com.lifeadmin.autopilot.data.repository.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class ThreadUi(
    val loading: Boolean = true,
    val thread: AfThread? = null,
    /** Set instead of [thread] for research saved by the earlier engine. */
    val legacy: Investigation? = null,
    val taskId: String? = null,
    val taskTitle: String? = null,
    /** Turn id whose "what was sent" sheet is open. */
    val payloadFor: String? = null,
    val source: Pair<String, Int>? = null,
    /** Turn id → pending items of the suggestion set saved for it (absent: none was made). */
    val suggestions: Map<String, Int> = emptyMap(),
    val now: Instant = Instant.EPOCH,
) {
    /** Every turn except one still marked running (settled to Interrupted at app start). */
    val shownTurns: List<AfTurn> get() = thread?.turns.orEmpty().filter { it.status != AfStatus.RUNNING }
}

/**
 * One saved research thread, read-only: built-in research is retired, so nothing here asks, runs
 * or sends anything. Its answers, sources and what was sent stay readable, and task suggestions
 * that were already made still open in the one Proposal Review. Deleting removes the thread only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvestigationThreadViewModel(
    private val threadId: String,
    private val store: AfThreadRepository,
    legacyStore: InvestigationRepository,
    tasks: TaskRepository,
    private val proposalSets: ProposalSetRepository,
    private val legacyProposals: InvestigationProposalSets,
    private val clock: AppClock,
) : ViewModel() {
    private val local = MutableStateFlow(ThreadUi())
    private val legacyFlag = MutableStateFlow<Boolean?>(null)

    private val threadFlow = store.observe(threadId)
    private val legacyFlow = legacyFlag.flatMapLatest { l -> if (l != true) flowOf(null) else legacyStore.observe(threadId) }

    val state: StateFlow<ThreadUi> = combine(threadFlow, legacyFlow, local, tasks.observeSummaries()) { t, legacy, l, summaries ->
        val taskId = t?.taskId ?: legacy?.taskId
        l.copy(
            loading = t == null && legacy == null && l.loading,
            thread = t, legacy = legacy, taskId = taskId, taskTitle = summaries.firstOrNull { it.task.id == taskId }?.task?.title, now = clock.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            val legacy = store.isLegacy(threadId)
            legacyFlag.value = legacy
            // Neither format: the thread is gone (deleted elsewhere); stop showing the loading state.
            if (!legacy && store.get(threadId) == null) local.update { it.copy(loading = false) }
        }
        viewModelScope.launch { threadFlow.collect { t -> if (t != null) { local.update { it.copy(loading = false) }; counts(t) } } }
        viewModelScope.launch { legacyFlow.collect { if (it != null) local.update { s -> s.copy(loading = false) } } }
    }

    private suspend fun counts(t: AfThread) {
        val m = HashMap<String, Int>()
        for (turn in t.turns) proposalSets.get(suggestionSetId(t.id, turn.id))?.let { m[turn.id] = it.pending.size }
        local.update { it.copy(suggestions = m) }
    }

    fun openSource(turnId: String, n: Int) = local.update { it.copy(source = turnId to n) }
    fun closeSource() = local.update { it.copy(source = null) }
    fun openPayload(turnId: String) = local.update { it.copy(payloadFor = turnId) }
    fun closePayload() = local.update { it.copy(payloadFor = null) }

    /** Opens the suggestions saved for this turn in the Proposal Review. */
    fun openSuggestions(turnId: String, onReady: (taskId: String, setId: String) -> Unit) = viewModelScope.launch {
        val set = proposalSets.get(suggestionSetId(threadId, turnId)) ?: return@launch
        onReady(set.targetId, set.id)
    }

    /** Legacy (M4) turns keep their own proposals, reviewed through the same Proposal Review. */
    fun openLegacyReview(turnId: String, onReady: (taskId: String, setId: String) -> Unit) = viewModelScope.launch {
        val set = legacyProposals.ensure(threadId, turnId) ?: return@launch
        onReady(set.targetId, set.id)
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        store.delete(threadId)
        onDone()
    }

    companion object {
        /** Id of the task-suggestion set the retired research saved for one answer. */
        fun suggestionSetId(threadId: String, turnId: String) = "afs-$threadId-$turnId"
    }
}

data class HomeUi(val loading: Boolean = true, val threads: List<ResearchThreadSummary> = emptyList(), val taskTitles: Map<String, String> = emptyMap(), val now: Instant = Instant.EPOCH)

/** Every saved research thread on this phone, newest first; standalone and task-linked, either engine. Readable offline. */
class InvestigationHomeViewModel(store: AfThreadRepository, tasks: TaskRepository, clock: AppClock, taskFilter: String?) : ViewModel() {
    val state: StateFlow<HomeUi> = combine(store.observeSummaries(taskFilter), tasks.observeSummaries()) { list, summaries ->
        HomeUi(false, list, summaries.associate { it.task.id to it.task.title }, clock.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())
}
