package com.lifeadmin.autopilot.reminders

import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.IdGenerator
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.domain.model.DiagnosticEntry
import com.lifeadmin.autopilot.domain.model.DiagnosticType
import com.lifeadmin.autopilot.notifications.NotificationPublisher
import com.lifeadmin.autopilot.notifications.PostResult
import com.lifeadmin.autopilot.notifications.TestNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

data class ScheduledTest(val testId: String, val triggerAt: Instant, val exact: Boolean, val scheduled: Boolean, val error: String?)

/**
 * User-initiated delivery checks from the Reminder health screen. Uses the same channel and the
 * same AlarmManager mode as real reminders, and records every step in the diagnostics ledger.
 * A posted test proves Android accepted the notification — whether it was *noticed* depends on
 * the phone's sound/vibration settings, which the screen reports separately.
 */
class ReminderSelfTest(
    private val diagnostics: DiagnosticsRecorder,
    private val alarms: AlarmDriver,
    private val notifications: NotificationPublisher,
    private val environment: EnvironmentProbe,
    private val settings: SettingsRepository,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
    private val ids: IdGenerator,
    /** notify() only enqueues; how long to wait for the test to appear in the active list. */
    private val listingTimeout: Duration = Duration.ofMillis(2_000),
    /** Lookup of earlier ledger entries for one test id (Room-backed in the app). */
    private val history: suspend (testId: String) -> List<DiagnosticEntry> = { emptyList() },
) {
    private val mutex = Mutex()

    suspend fun sendTestNotificationNow(): DiagnosticEntry = withContext(Dispatchers.Default + NonCancellable) {
        val now = clock.now()
        val testId = ids.newId()
        post(testId, now, source = "test:now", plannedAt = now, lateness = Duration.ZERO, exact = null)
    }

    suspend fun scheduleTestReminder(delay: Duration = Duration.ofMinutes(2)): ScheduledTest = withContext(Dispatchers.Default + NonCancellable) {
        val now = clock.now()
        val testId = ids.newId()
        val triggerAt = now.plus(delay)
        val result = alarms.scheduleTest(testId, triggerAt, preferExact = settings.current().precisionMode)
        diagnostics.record(
            DiagnosticEntry(
                id = ids.newId(), at = now, type = if (result.scheduled) DiagnosticType.TEST_SCHEDULED else DiagnosticType.SCHEDULE_FAILED,
                occurrenceId = testId, plannedAt = triggerAt, triggerAt = triggerAt, source = "test:alarm",
                exact = result.exact, outcome = result.error ?: if (result.scheduled) "scheduled" else "refused",
                detail = runCatching { environment.snapshot().compact() }.getOrNull(),
            ),
        )
        ScheduledTest(testId, triggerAt, result.exact, result.scheduled, result.error)
    }

    /** Test alarm broadcast. Duplicate broadcasts are recorded but post only once. */
    suspend fun onTestAlarm(testId: String, receivedAt: Instant = clock.now()) = withContext(Dispatchers.Default + NonCancellable) {
        mutex.withLock {
            val past = history(testId)
            val scheduled = past.firstOrNull { it.type == DiagnosticType.TEST_SCHEDULED }
            val planned = scheduled?.plannedAt ?: receivedAt
            val duplicate = past.any { it.type == DiagnosticType.TEST_ALARM_RECEIVED }
            diagnostics.record(
                DiagnosticEntry(
                    id = ids.newId(), at = receivedAt, type = if (duplicate) DiagnosticType.ALARM_IGNORED else DiagnosticType.TEST_ALARM_RECEIVED,
                    occurrenceId = testId, plannedAt = planned, triggerAt = scheduled?.triggerAt, source = "test:alarm",
                    exact = scheduled?.exact, lateness = Duration.between(planned, receivedAt),
                    outcome = if (duplicate) "duplicate" else if (scheduled == null) "unknown_test" else "received",
                ),
            )
            if (!duplicate && scheduled != null) {
                post(testId, clock.now(), "test:alarm", planned, Duration.between(planned, clock.now()), scheduled.exact)
            }
        }
    }

    private suspend fun post(testId: String, now: Instant, source: String, plannedAt: Instant, lateness: Duration, exact: Boolean?): DiagnosticEntry {
        val env = runCatching { environment.snapshot() }.getOrNull()
        val zone = zoneProvider.current()
        val due = plannedAt.atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val body = if (source == "test:alarm") {
            "Test reminder due $due arrived ${RoomDiagnosticsLog.formatLateness(lateness)} (${if (exact == true) "precise" else "approximate"} timing). " +
                "Did you hear or feel it? If not, open Reminder health."
        } else {
            "If you did not hear or feel this, open Reminder health to see why."
        }
        val result = notifications.postTest(TestNotification(testId, "Life Admin test reminder", body, now))
        val active = if (result == PostResult.POSTED) awaitListed(testId) else null
        val entry = DiagnosticEntry(
            id = ids.newId(), at = now, type = if (result == PostResult.POSTED) DiagnosticType.TEST_NOTIFICATION else DiagnosticType.POST_FAILED,
            occurrenceId = testId, plannedAt = plannedAt, source = source, exact = exact, lateness = lateness,
            outcome = if (result != PostResult.POSTED) result.name.lowercase() else when (active) { true -> "listed_active"; false -> "not_listed_active"; null -> "notify_returned" },
            detail = env?.compact(),
        )
        diagnostics.record(entry)
        return entry
    }

    /** Polls the active list (the test tag is unique, unlike a task's reminder tag). */
    private suspend fun awaitListed(testId: String): Boolean? {
        val step = 250L
        var waited = 0L
        while (true) {
            val listed = runCatching { notifications.isTestShowing(testId) }.getOrNull() ?: return null
            if (listed || waited >= listingTimeout.toMillis()) return listed
            delay(step)
            waited += step
        }
    }
}
