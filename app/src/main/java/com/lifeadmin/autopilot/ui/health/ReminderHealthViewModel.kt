package com.lifeadmin.autopilot.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeadmin.autopilot.di.AppContainer
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.domain.model.DiagnosticEntry
import com.lifeadmin.autopilot.domain.model.DiagnosticType
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.usecase.AlertAssessment
import com.lifeadmin.autopilot.domain.usecase.AlertAssessor
import com.lifeadmin.autopilot.domain.usecase.AlertInputs
import com.lifeadmin.autopilot.notifications.NotificationHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

data class LateStats(val deliveries: Int, val lateOver5Min: Int, val worst: Duration?, val approximate: Int)

data class HealthUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val health: NotificationHealth = NotificationHealth(true, true, true, true),
    val environment: DeliveryEnvironment = DeliveryEnvironment(),
    val assessment: AlertAssessment? = null,
    val activeReminders: Int = 0,
    val approximateReminders: Int = 0,
    val nextTitle: String? = null,
    val nextAt: Instant? = null,
    val nextExact: Boolean = false,
    val lateStats: LateStats = LateStats(0, 0, null, 0),
    val lastTest: List<DiagnosticEntry> = emptyList(),
    val pendingTestAt: Instant? = null,
)

/** Reads live device state on demand (on resume and after actions); nothing is polled in the background. */
class ReminderHealthViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HealthUiState())
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    val recent: StateFlow<List<DiagnosticEntry>> = container.diagnosticsLog.observeRecent(40)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow: Flow<String> = messages.receiveAsFlow()

    /** Exact-alarm permission state we already upgraded for (prevents refresh → reconcile loops). */
    private var upgradedForExact: Boolean? = null

    fun refresh() = viewModelScope.launch {
        val loaded = withContext(Dispatchers.IO) { load() }
        _state.value = loaded
        // Returning from the exact-alarm settings page: upgrade approximate alarms right away (once).
        val exact = loaded.environment.canScheduleExact
        if (loaded.settings.precisionMode && exact && loaded.approximateReminders > 0 && upgradedForExact != exact) {
            upgradedForExact = exact
            container.reconcileAll("exact_alarm_permission")
            _state.value = withContext(Dispatchers.IO) { load() }
        }
    }

    private suspend fun load(): HealthUiState {
        val settings = container.settingsRepository.current()
        val health = container.notificationPublisher.health()
        val env = container.environmentProbe.snapshot()
        val active = container.database.occurrenceDao().getWithStatus(listOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.PLANNED))
        val next = active.minByOrNull { it.plannedAt }
        val title = next?.let { container.database.taskDao().get(it.taskId)?.title }
        val posts = container.diagnosticsLog.recentOfTypes(setOf(DiagnosticType.POST_REQUESTED), 20)
        val late = posts.filter { (it.lateness ?: Duration.ZERO) > Duration.ofMinutes(5) }
        val tests = container.diagnosticsLog.recentOfTypes(
            setOf(DiagnosticType.TEST_SCHEDULED, DiagnosticType.TEST_ALARM_RECEIVED, DiagnosticType.TEST_NOTIFICATION, DiagnosticType.POST_FAILED, DiagnosticType.SCHEDULE_FAILED), 12,
        ).filter { it.source?.startsWith("test") == true }
        val lastTestId = tests.firstOrNull()?.occurrenceId
        val lastTest = tests.filter { it.occurrenceId == lastTestId }.reversed()
        val pending = lastTest.takeIf { l -> l.any { it.type == DiagnosticType.TEST_SCHEDULED } && l.none { it.type == DiagnosticType.TEST_ALARM_RECEIVED } }
            ?.firstOrNull { it.type == DiagnosticType.TEST_SCHEDULED }?.triggerAt
        return HealthUiState(
            loading = false, settings = settings, health = health, environment = env,
            assessment = AlertAssessor.assess(AlertInputs(health.permissionGranted, health.appNotificationsEnabled, settings.allowReminderNotifications, env)),
            activeReminders = active.size, approximateReminders = active.count { !it.exact },
            nextTitle = title, nextAt = next?.plannedAt, nextExact = next?.exact ?: false,
            lateStats = LateStats(posts.size, late.size, late.mapNotNull { it.lateness }.maxOrNull(), posts.count { it.exact == false }),
            lastTest = lastTest, pendingTestAt = pending,
        )
    }

    fun sendTestNow() = viewModelScope.launch {
        val e = container.selfTest.sendTestNotificationNow()
        messages.send(if (e.type == DiagnosticType.TEST_NOTIFICATION) "Test notification posted — did you hear or feel it?" else "Test notification could not be posted: ${e.outcome}")
        refresh()
    }

    fun scheduleTest() = viewModelScope.launch {
        val s = container.selfTest.scheduleTestReminder(Duration.ofMinutes(2))
        messages.send(if (s.scheduled) "Test reminder scheduled (${if (s.exact) "precise" else "approximate"}). Lock your phone and wait about 2 minutes." else "Android refused the test alarm: ${s.error}")
        refresh()
    }

    fun recheck() = viewModelScope.launch {
        container.reconcileAll("manual")
        messages.send("Schedule re-checked")
        refresh()
    }

    fun setPrecision(on: Boolean) = viewModelScope.launch {
        container.settingsRepository.update { it.copy(precisionMode = on) }
        container.reconcileAll("precision_mode")
        refresh()
    }

    fun setNotificationsEnabled(on: Boolean) = viewModelScope.launch {
        container.settingsRepository.update { it.copy(allowReminderNotifications = on) }
        refresh()
    }

    /** Privacy-safe report: counts, settings, device state and the lifecycle ledger — no task text. */
    suspend fun diagnosticsText(): String = withContext(Dispatchers.IO) {
        val s = _state.value
        buildString {
            appendLine(container.diagnostics.report())
            appendLine("environment ${container.environmentProbe.snapshot().compact()}")
            s.assessment?.let { appendLine("alert_assessment level=${it.level} popup=${it.popUp} issues=${it.issues.joinToString(",")}") }
            append(container.diagnosticsLog.exportText(container.zoneProvider.current()))
        }
    }
}
