package com.lifeadmin.autopilot.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.ui.tickerFlow
import com.lifeadmin.autopilot.ui.today.TodayViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

enum class TaskFilter(val label: String) {
    ALL("All"),
    ACTIONABLE("Actionable"),
    WAITING("Waiting"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    ARCHIVED("Archived"),
}

data class TaskListUiState(
    val loading: Boolean = true,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.systemDefault(),
    val filter: TaskFilter = TaskFilter.ALL,
    val query: String = "",
    val tasks: List<TaskSummary> = emptyList(),
    val counts: Map<TaskFilter, Int> = emptyMap(),
)

/** Shared by the Tasks tab (filters + search) and the Inbox tab (inboxOnly = true). */
class TaskListViewModel(
    private val repository: TaskRepository,
    clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val inboxOnly: Boolean,
) : ViewModel() {
    private val filter = MutableStateFlow(TaskFilter.ALL)
    private val query = MutableStateFlow("")

    val state: StateFlow<TaskListUiState> = combine(
        repository.observeSummaries(), filter, query, tickerFlow(clock, 60_000),
    ) { all, f, q, now ->
        val needle = q.trim()
        val visible = if (inboxOnly) {
            all.filter { it.task.status == TaskStatus.INBOX && it.task.archivedAt == null }.sortedByDescending { it.task.createdAt }
        } else {
            all.filter { matches(it, f) }
                .filter { needle.isEmpty() || it.task.title.contains(needle, true) || (it.task.notes?.contains(needle, true) ?: false) }
                .sortedWith(if (f == TaskFilter.COMPLETED) compareByDescending { it.task.completedAt ?: it.task.updatedAt } else TodayViewModel.attentionOrder)
        }
        TaskListUiState(
            loading = false, now = now, zone = zoneProvider.current(), filter = f, query = q, tasks = visible,
            counts = TaskFilter.entries.associateWith { tf -> all.count { matches(it, tf) } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListUiState())

    fun setFilter(f: TaskFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q.take(200) }

    fun complete(taskId: String, onDone: () -> Unit = {}) = viewModelScope.launch { runCatching { repository.complete(taskId) }.onSuccess { onDone() } }
    fun reopen(taskId: String) = viewModelScope.launch { runCatching { repository.reopen(taskId) } }
    fun makeActionable(taskId: String) = viewModelScope.launch { runCatching { repository.changeStatus(taskId, TaskStatus.ACTIONABLE) } }

    companion object {
        fun matches(s: TaskSummary, f: TaskFilter): Boolean {
            val t = s.task
            val archived = t.archivedAt != null
            return when (f) {
                TaskFilter.ALL -> !archived
                TaskFilter.ACTIONABLE -> !archived && (t.status == TaskStatus.ACTIONABLE || t.status == TaskStatus.INBOX)
                TaskFilter.WAITING -> !archived && t.status == TaskStatus.WAITING
                TaskFilter.PAUSED -> !archived && t.status == TaskStatus.PAUSED
                TaskFilter.COMPLETED -> !archived && t.status.isTerminal
                TaskFilter.ARCHIVED -> archived
            }
        }
    }
}
