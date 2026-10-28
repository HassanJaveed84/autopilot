package com.lifeadmin.autopilot.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.scheduling.PlannedReminder
import com.lifeadmin.autopilot.domain.usecase.HintDismissals
import com.lifeadmin.autopilot.domain.usecase.InMemoryHintDismissals
import com.lifeadmin.autopilot.domain.usecase.StallDetector
import com.lifeadmin.autopilot.domain.usecase.StallHint
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.ui.tickerFlow
import com.lifeadmin.autopilot.ui.userMessage
import kotlinx.coroutines.channels.Channel
import com.lifeadmin.autopilot.data.repository.ResearchBrief
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class TaskDetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val details: TaskDetails? = null,
    val settings: AppSettings = AppSettings(),
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Deterministic "this seems stuck" hint (never AI, never a background call). */
    val stallHint: StallHint? = null,
    /** Saved research briefs for this task, newest first (M3; read-only). */
    val briefs: List<ResearchBrief> = emptyList(),
    /** Saved research threads linked to this task, newest first (either engine; legacy M3A.2 sessions included; read-only). */
    val investigations: List<com.lifeadmin.autopilot.data.repository.ResearchThreadSummary> = emptyList(),
    /** Proposal sets for this task (M2); open ones show a "waiting for review" banner each. */
    val proposalSets: List<com.lifeadmin.autopilot.domain.proposal.ProposalSet> = emptyList(),
    /** Provenance records by id for the "where did this come from" marker on applied content. */
    val provenance: Map<String, com.lifeadmin.autopilot.domain.proposal.ProvenanceRecord> = emptyMap(),
) {
    val openSets: List<com.lifeadmin.autopilot.domain.proposal.ProposalSet>
        get() = proposalSets.filter { it.status == com.lifeadmin.autopilot.domain.proposal.SetStatus.OPEN && it.pending.isNotEmpty() }
}

sealed interface DetailEvent {
    data class Message(val text: String) : DetailEvent
    data object Deleted : DetailEvent
    data object ReminderEnabled : DetailEvent
}

class TaskDetailViewModel(
    val taskId: String,
    private val repository: TaskRepository,
    private val actions: TaskActions,
    settings: SettingsRepository,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val hintDismissals: HintDismissals = InMemoryHintDismissals(),
    researchBriefs: Flow<List<ResearchBrief>> = flowOf(emptyList()),
    investigations: Flow<List<com.lifeadmin.autopilot.data.repository.ResearchThreadSummary>> = flowOf(emptyList()),
    proposalSets: Flow<List<com.lifeadmin.autopilot.domain.proposal.ProposalSet>> = flowOf(emptyList()),
    provenance: Flow<List<com.lifeadmin.autopilot.domain.proposal.ProvenanceRecord>> = flowOf(emptyList()),
) : ViewModel() {
    private val dismissedTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val events = Channel<DetailEvent>(Channel.BUFFERED)
    val eventFlow: Flow<DetailEvent> = events.receiveAsFlow()

    private data class ResearchBits(
        val briefs: List<ResearchBrief>, val investigations: List<com.lifeadmin.autopilot.data.repository.ResearchThreadSummary>,
        val sets: List<com.lifeadmin.autopilot.domain.proposal.ProposalSet> = emptyList(), val provenance: List<com.lifeadmin.autopilot.domain.proposal.ProvenanceRecord> = emptyList(),
    )
    private val proposals = combine(proposalSets.catch { emit(emptyList()) }, provenance.catch { emit(emptyList()) }) { a, b -> a to b }
    private val research = combine(researchBriefs.catch { emit(emptyList()) }, investigations.catch { emit(emptyList()) }, proposals) { b, s2, (sets, prov) -> ResearchBits(b, s2, sets, prov) }

    val state: StateFlow<TaskDetailUiState> = combine(
        repository.observeDetails(taskId), settings.settings, tickerFlow(clock), dismissedTick, research,
    ) { d, s, now, _, bits ->
        val (briefs, threads) = bits
        val zone = zoneProvider.current()
        val hint = d?.let { StallDetector.detect(it, now, zone) }?.takeUnless { hintDismissals.isDismissed(taskId, it.signature) }
        TaskDetailUiState(
            loading = false, missing = d == null, details = d, settings = s, now = now, zone = zone, stallHint = hint,
            briefs = briefs, investigations = threads,
            proposalSets = bits.sets, provenance = bits.provenance.associateBy { it.id },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    fun dismissHint(hint: StallHint) {
        hintDismissals.dismiss(taskId, hint.signature)
        dismissedTick.value++
    }

    fun now(): Instant = clock.now()
    fun zone(): ZoneId = zoneProvider.current()

    private fun run(success: String? = null, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { success?.let { events.send(DetailEvent.Message(it)) } }
            .onFailure { events.send(DetailEvent.Message(it.userMessage())) }
    }

    fun saveDetails(title: String, notes: String?, priority: Priority) = run { repository.updateDetails(taskId, title, notes, priority) }
    fun setDeadline(type: DeadlineType, date: LocalDate?, instant: Instant?) = run { repository.setDeadline(taskId, type, date, instant) }
    fun changeStatus(to: TaskStatus) = run { repository.changeStatus(taskId, to) }
    fun setWaiting(reason: String?, checkIn: Instant?) = run("Marked as waiting") { repository.changeStatus(taskId, TaskStatus.WAITING, reason, checkIn) }
    fun complete() = run("Marked done — future reminders canceled") { repository.complete(taskId) }
    fun reopen() = run("Reopened") { repository.reopen(taskId) }

    /** "Done for tonight" on a routine: this cycle only; the task stays open. */
    fun finishCycle(onFinished: () -> Unit = {}) = viewModelScope.launch {
        runCatching { repository.finishCycle(taskId) }.onSuccess { onFinished() }.onFailure { events.send(DetailEvent.Message(it.userMessage())) }
    }
    fun undoFinishCycle() = run { repository.undoFinishCycle(taskId) }
    fun snooze(until: Instant, remindAtEnd: Boolean) = run { repository.snooze(taskId, until, remindAtEnd) }
    fun clearSnooze() = run("Snooze cleared") { repository.clearSnooze(taskId) }
    fun archive() = run("Archived") { repository.archive(taskId) }
    fun unarchive() = run("Restored") { repository.unarchive(taskId) }
    fun delete() = viewModelScope.launch {
        runCatching { repository.delete(taskId) }
            .onSuccess { events.send(DetailEvent.Deleted) }
            .onFailure { events.send(DetailEvent.Message(it.userMessage())) }
    }

    fun savePolicy(policy: ReminderPolicy, onSaved: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { repository.savePolicy(policy) }
            .onSuccess {
                onSaved()
                if (policy.enabled) events.send(DetailEvent.ReminderEnabled)
            }
            .onFailure { onError(it.userMessage()) }
    }

    fun addStep(title: String, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { repository.addStep(taskId, title) }.onFailure { onError(it.userMessage()) }
    }
    fun toggleStep(stepId: String, done: Boolean) = run { repository.setStepDone(stepId, done) }

    /** The step editor's save (Rich Steps). [onSaved] closes the editor; a validation problem shows as a message. */
    fun updateStep(stepId: String, title: String, description: String?, links: List<com.lifeadmin.autopilot.domain.model.StepLink>, onSaved: () -> Unit) = viewModelScope.launch {
        runCatching { repository.updateStep(stepId, title, description, links) }
            .onSuccess { onSaved() }
            .onFailure { events.send(DetailEvent.Message(it.userMessage())) }
    }

    /** Opening a link never touches the step; when no browser can take it, say so. */
    fun linkFailed() = viewModelScope.launch { events.send(DetailEvent.Message("No app on this phone could open that link")) }
    fun deleteStep(stepId: String) = run { repository.deleteStep(stepId) }
    fun moveStep(stepId: String, delta: Int) = run { repository.moveStep(stepId, delta) }

    suspend fun previewSnooze(until: Instant, remindAtEnd: Boolean): PlannedReminder? =
        actions.previewNext(taskId, snoozeUntil = until, remindAtEnd = remindAtEnd)

    suspend fun previewPolicy(policy: ReminderPolicy): PlannedReminder? = actions.previewNext(taskId, policyOverride = policy)
}
