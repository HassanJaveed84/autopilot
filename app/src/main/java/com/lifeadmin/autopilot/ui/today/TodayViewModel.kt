package com.lifeadmin.autopilot.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.TaskActions
import com.lifeadmin.autopilot.ui.components.isOverdue
import com.lifeadmin.autopilot.ui.components.isSnoozed
import com.lifeadmin.autopilot.ui.tickerFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class TodayUiState(
    val loading: Boolean = true,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.systemDefault(),
    val overdue: List<TaskSummary> = emptyList(),
    val needsAction: List<TaskSummary> = emptyList(),
    val waiting: List<TaskSummary> = emptyList(),
    val upcoming: List<TaskSummary> = emptyList(),
    val snoozed: List<TaskSummary> = emptyList(),
    val inboxCount: Int = 0,
    val upcomingCount24h: Int = 0,
    val showOnboarding: Boolean = false,
    val hasAnyTask: Boolean = false,
)

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
        val open = all.filter { !it.task.status.isTerminal && it.task.archivedAt == null }
        val overdue = open.filter { it.task.isOverdue(now, zone) && it.task.status != TaskStatus.PAUSED }
        val overdueIds = overdue.map { it.task.id }.toSet()
        val actionable = open.filter { it.task.status == TaskStatus.ACTIONABLE }
        val needsAction = actionable.filter { !it.task.isSnoozed(now) && it.task.id !in overdueIds }.sortedWith(attentionOrder)
        val upcoming = open.filter { it.nextReminder != null }.sortedBy { it.nextReminder!!.plannedAt }
        TodayUiState(
            loading = false,
            now = now,
            zone = zone,
            overdue = overdue.sortedWith(attentionOrder),
            needsAction = needsAction,
            waiting = open.filter { it.task.status == TaskStatus.WAITING }.sortedBy { it.task.waitingCheckInAt ?: Instant.MAX },
            upcoming = upcoming.take(5),
            snoozed = actionable.filter { it.task.isSnoozed(now) }.sortedBy { it.task.snoozedUntil },
            inboxCount = open.count { it.task.status == TaskStatus.INBOX },
            upcomingCount24h = upcoming.count { it.nextReminder!!.plannedAt <= now.plus(Duration.ofHours(24)) },
            showOnboarding = !s.onboardingCompleted,
            hasAnyTask = all.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun complete(taskId: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        runCatching { repository.complete(taskId) }.onSuccess { onDone() }
    }

    fun undoComplete(taskId: String) = viewModelScope.launch { runCatching { repository.reopen(taskId) } }

    fun snoozeDefault(taskId: String, onSnoozed: (Instant) -> Unit) = viewModelScope.launch {
        runCatching { actions.snoozeWithDefault(taskId) }.onSuccess(onSnoozed)
    }

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
