package com.lifeadmin.autopilot.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.QuietHours
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.tickerFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

data class TodayUiState(
    val loading: Boolean = true,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.systemDefault(),
    val dateText: String = "",
    val plan: TodayPlan = TodayPlan(emptyList(), 0, 0, "", "", emptyList(), emptyList(), emptyList(), emptyList()),
    val quietHours: QuietHours = QuietHours(),
    val showOnboarding: Boolean = false,
    val hasAnyTask: Boolean = false,
) {
    val inboxCount: Int get() = plan.inbox.size
    /** No open task and nothing in the Inbox: the "A clear day" state. */
    val isEmpty: Boolean get() = !loading && plan.rail.isEmpty() && plan.waiting.isEmpty() && plan.later.isEmpty() && plan.inbox.isEmpty()
}

class TodayViewModel(
    private val repository: TaskRepository,
    private val settings: SettingsRepository,
    private val actions: TaskActions,
    clock: AppClock,
    private val zoneProvider: ZoneProvider,
) : ViewModel() {

    val state: StateFlow<TodayUiState> = combine(
        repository.observeSummaries(),
        settings.settings,
        tickerFlow(clock),
    ) { all, s, now ->
        val zone = zoneProvider.current()
        TodayUiState(
            loading = false,
            now = now,
            zone = zone,
            dateText = Format.longDate(now.atZone(zone).toLocalDate()),
            plan = TodayPlanner.plan(all, s, now, zone),
            quietHours = s.quietHours,
            showOnboarding = !s.onboardingCompleted,
            hasAnyTask = all.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun complete(taskId: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        runCatching { repository.complete(taskId) }.onSuccess { onDone() }
    }

    /** "Done for tonight" on a routine; [onDone] gets the label shown ("Done for tonight"). */
    fun finishCycle(taskId: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        runCatching { repository.finishCycle(taskId) }.onSuccess { onDone() }
    }
    fun undoFinishCycle(taskId: String) = viewModelScope.launch { runCatching { repository.undoFinishCycle(taskId) } }

    /** Real inverse of [complete]: reopens the task and reschedules its reminders. */
    fun undoComplete(taskId: String) = viewModelScope.launch { runCatching { repository.reopen(taskId) } }

    fun snoozeDefault(taskId: String, onSnoozed: (Instant) -> Unit) = viewModelScope.launch {
        runCatching { actions.snoozeWithDefault(taskId) }.onSuccess(onSnoozed)
    }

    /** Real inverse of a snooze. */
    fun clearSnooze(taskId: String) = viewModelScope.launch { runCatching { repository.clearSnooze(taskId) } }

    fun createExample(onCreated: (String) -> Unit) = viewModelScope.launch {
        val id = actions.createExampleTask()
        settings.update { it.copy(onboardingCompleted = true) }
        onCreated(id)
    }

    fun dismissOnboarding() = viewModelScope.launch { settings.update { it.copy(onboardingCompleted = true) } }

    companion object {
        val attentionOrder: Comparator<TaskSummary> = compareByDescending<TaskSummary> { it.task.priority == Priority.HIGH }
            .thenBy { it.task.deadlineInstant ?: it.task.deadlineLocalDate?.atStartOfDay()?.toInstant(java.time.ZoneOffset.UTC) ?: Instant.MAX }
            .thenBy { it.nextReminder?.plannedAt ?: Instant.MAX }
            .thenByDescending { it.task.updatedAt }
    }
}
