package com.lifeadmin.autopilot.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.TaskSummary
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.ui.components.Format
import com.lifeadmin.autopilot.ui.components.isSnoozed
import com.lifeadmin.autopilot.ui.tickerFlow
import com.lifeadmin.autopilot.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Status filters on the Tasks tab. The labels use the honest status vocabulary; counts come from the same predicate. */
enum class TaskFilter(val label: String) {
    ALL("All"),
    ACTIONABLE("Needs you"),
    WAITING("Waiting"),
    SNOOZED("Snoozed"),
    PAUSED("Paused"),
    COMPLETED("Done"),
    ARCHIVED("Archived"),
}

enum class TaskSort(val label: String) { NEXT_NUDGE("Next nudge"), DEADLINE("Deadline"), NEWEST("Newest") }

data class TaskListUiState(
    val loading: Boolean = true,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.systemDefault(),
    val dateText: String = "",
    val filter: TaskFilter = TaskFilter.ALL,
    val sort: TaskSort = TaskSort.NEXT_NUDGE,
    val query: String = "",
    val tasks: List<TaskSummary> = emptyList(),
    val counts: Map<TaskFilter, Int> = emptyMap(),
    val settings: AppSettings = AppSettings(),
)

/** Shared by the Tasks tab (filters + search + sort) and the Inbox tab (inboxOnly = true). */
class TaskListViewModel(
    private val repository: TaskRepository,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val inboxOnly: Boolean,
    private val settings: SettingsRepository? = null,
) : ViewModel() {
    private val filter = MutableStateFlow(TaskFilter.ALL)
    private val sort = MutableStateFlow(TaskSort.NEXT_NUDGE)
    private val query = MutableStateFlow("")
    private val messages = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = messages

    val state: StateFlow<TaskListUiState> = combine(
        repository.observeSummaries(), filter, sort, query, tickerFlow(clock, 60_000),
    ) { all, f, so, q, now ->
        val zone = zoneProvider.current()
        val needle = q.trim()
        val visible = if (inboxOnly) {
            all.filter { it.task.status == TaskStatus.INBOX && it.task.archivedAt == null }.sortedByDescending { it.task.createdAt }
        } else {
            all.filter { matches(it, f, now) }
                .filter { needle.isEmpty() || it.task.title.contains(needle, true) || (it.task.notes?.contains(needle, true) ?: false) }
                .sortedWith(comparator(f, so, now))
        }
        TaskListUiState(
            loading = false, now = now, zone = zone, dateText = Format.longDate(now.atZone(zone).toLocalDate()),
            filter = f, sort = so, query = q, tasks = visible,
            counts = TaskFilter.entries.associateWith { tf -> all.count { matches(it, tf, now) } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListUiState())

    val appSettings: StateFlow<AppSettings> = (settings?.settings ?: kotlinx.coroutines.flow.flowOf(AppSettings()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun now(): Instant = clock.now()
    fun zone(): ZoneId = zoneProvider.current()

    fun setFilter(f: TaskFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q.take(200) }
    fun cycleSort() { sort.value = TaskSort.entries[(sort.value.ordinal + 1) % TaskSort.entries.size] }
    fun messageShown() { messages.value = null }

    fun complete(taskId: String, onDone: () -> Unit = {}) = viewModelScope.launch { runCatching { repository.complete(taskId) }.onSuccess { onDone() }.onFailure { messages.value = it.userMessage() } }
    fun reopen(taskId: String) = viewModelScope.launch { runCatching { repository.reopen(taskId) }.onFailure { messages.value = it.userMessage() } }
    fun makeActionable(taskId: String) = viewModelScope.launch { runCatching { repository.changeStatus(taskId, TaskStatus.ACTIONABLE) }.onFailure { messages.value = it.userMessage() } }

    /** Inbox "Nudge me": saves the chosen follow-up, then moves the note to Actionable. */
    fun nudge(taskId: String, policy: ReminderPolicy, onDone: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching {
            repository.savePolicy(policy.copy(taskId = taskId))
            repository.changeStatus(taskId, TaskStatus.ACTIONABLE)
        }.onSuccess { onDone() }.onFailure { onError(it.userMessage()) }
    }

    /** Inbox "Waiting on…": INBOX → WAITING is allowed by the state machine. */
    fun wait(taskId: String, reason: String?, checkIn: Instant?) = viewModelScope.launch {
        runCatching { repository.changeStatus(taskId, TaskStatus.WAITING, reason, checkIn) }.onFailure { messages.value = it.userMessage() }
    }

    fun delete(taskId: String) = viewModelScope.launch { runCatching { repository.delete(taskId) }.onFailure { messages.value = it.userMessage() } }

    companion object {
        fun matches(s: TaskSummary, f: TaskFilter, now: Instant): Boolean {
            val t = s.task
            val archived = t.archivedAt != null
            return when (f) {
                TaskFilter.ALL -> !archived && !t.status.isTerminal
                TaskFilter.ACTIONABLE -> !archived && (t.status == TaskStatus.ACTIONABLE || t.status == TaskStatus.INBOX) && !t.isSnoozed(now)
                TaskFilter.WAITING -> !archived && t.status == TaskStatus.WAITING
                TaskFilter.SNOOZED -> !archived && !t.status.isTerminal && t.status != TaskStatus.PAUSED && t.isSnoozed(now)
                TaskFilter.PAUSED -> !archived && t.status == TaskStatus.PAUSED
                TaskFilter.COMPLETED -> !archived && t.status.isTerminal
                TaskFilter.ARCHIVED -> archived
            }
        }

        private fun deadlineInstant(s: TaskSummary): Instant = when (s.task.deadlineType) {
            DeadlineType.EXACT -> s.task.deadlineInstant ?: Instant.MAX
            DeadlineType.DATE_ONLY -> s.task.deadlineLocalDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC) ?: Instant.MAX
            DeadlineType.NONE -> Instant.MAX
        }

        fun comparator(f: TaskFilter, sort: TaskSort, now: Instant): Comparator<TaskSummary> = when {
            f == TaskFilter.COMPLETED -> compareByDescending { it.task.completedAt ?: it.task.updatedAt }
            f == TaskFilter.ARCHIVED -> compareByDescending { it.task.archivedAt }
            sort == TaskSort.DEADLINE -> compareBy<TaskSummary> { deadlineInstant(it) }.thenBy { it.nextReminder?.plannedAt ?: Instant.MAX }.thenByDescending { it.task.updatedAt }
            sort == TaskSort.NEWEST -> compareByDescending { it.task.createdAt }
            else -> compareBy<TaskSummary> { it.nextReminder?.plannedAt ?: it.task.snoozedUntil?.takeIf { s -> s > now } ?: Instant.MAX }.thenBy { deadlineInstant(it) }.thenByDescending { it.task.updatedAt }
        }
    }
}
