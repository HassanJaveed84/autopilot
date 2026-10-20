package com.lifeadmin.autopilot.domain.usecase

import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.core.ZoneProvider
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.SettingsRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.DeadlineSpec
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.SnoozePreset
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.scheduling.PlannedReminder
import com.lifeadmin.autopilot.domain.scheduling.ReminderPlanner
import com.lifeadmin.autopilot.domain.scheduling.SnoozeCalculator
import java.time.Instant

data class CaptureResult(val taskId: String, val firstNudge: PlannedReminder?)

/** Higher-level task commands shared by screens and notification actions. */
class TaskActions(
    private val db: AppDatabase,
    private val repository: TaskRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
    private val zoneProvider: ZoneProvider,
) {
    /** Default snooze target: per-policy minutes override, else the global preset. */
    suspend fun defaultSnoozeTarget(taskId: String): Instant {
        val now = clock.now()
        val policyMinutes = repository.getPolicy(taskId)?.snoozeDefaultMinutes
        return if (policyMinutes != null) SnoozeCalculator.targetMinutes(policyMinutes, now)
        else SnoozeCalculator.target(settings.current().defaultSnooze, now, zoneProvider.current())
    }

    fun presetTarget(preset: SnoozePreset): Instant = SnoozeCalculator.target(preset, clock.now(), zoneProvider.current())

    suspend fun snoozeWithDefault(taskId: String): Instant {
        val until = defaultSnoozeTarget(taskId)
        repository.snooze(taskId, until, remindAtEnd = true)
        return until
    }

    /**
     * The exact next reminder the engine would plan, optionally for a hypothetical snooze or
     * policy, so confirmations can show a precise date/time before the user commits.
     */
    suspend fun previewNext(
        taskId: String,
        snoozeUntil: Instant? = null,
        remindAtEnd: Boolean = true,
        policyOverride: ReminderPolicy? = null,
        taskOverride: ((Task) -> Task)? = null,
    ): PlannedReminder? {
        var task = repository.getTask(taskId) ?: return null
        if (snoozeUntil != null) task = task.copy(snoozedUntil = snoozeUntil, snoozeRemindAtEnd = remindAtEnd)
        if (taskOverride != null) task = taskOverride(task)
        val policy = policyOverride ?: repository.getPolicy(taskId)
        val now = clock.now()
        val settled = policy?.let { db.occurrenceDao().getSettledPlannedAfter(it.id, now).toSet() }.orEmpty()
        return ReminderPlanner.next(task, policy, settings.current(), now, zoneProvider.current()) { it in settled }
    }

    suspend fun quickCapture(title: String, withReminder: Boolean, notes: String? = null, priority: Priority = Priority.NORMAL): String {
        val preset: ReminderPreset? = if (withReminder) settings.current().defaultPreset else null
        return repository.createTask(
            NewTask(
                title = title,
                notes = notes,
                priority = priority,
                status = if (withReminder) TaskStatus.ACTIONABLE else TaskStatus.INBOX,
                preset = preset,
            ),
        )
    }

    /**
     * Capture from the Flight Plan sheet: the task, its follow-up (the custom [policy] chosen in
     * the sheet, else the default preset when [nudge] is on, else none) and its [deadline] are
     * written in one transaction by [TaskRepository.createCapturedTask], so a failed save leaves
     * no half-made task behind. [captureId] makes a retry of the same capture return the task it
     * already created instead of a duplicate. Returns the id and the first nudge the engine plans.
     */
    suspend fun capture(
        title: String,
        notes: String?,
        priority: Priority,
        nudge: Boolean,
        policy: ReminderPolicy?,
        deadline: DeadlineSpec?,
        captureId: String? = null,
    ): CaptureResult {
        val preset: ReminderPreset? = if (nudge && policy == null) settings.current().defaultPreset else null
        val draft = NewTask(title = title, notes = notes, priority = priority, status = if (nudge) TaskStatus.ACTIONABLE else TaskStatus.INBOX, preset = preset)
        val (id, _) = repository.createCapturedTask(draft, customPolicy = policy?.takeIf { nudge }, deadline = deadline, taskId = captureId)
        return CaptureResult(id, previewNext(id))
    }

    /** Explicit, user-initiated example matching the canonical spec use case. */
    suspend fun createExampleTask(): String = repository.createTask(
        NewTask(
            title = "Book train ride",
            notes = "Example task: reminds you at 09:00 and 18:00 every day until you mark it done.",
            status = TaskStatus.ACTIONABLE,
            preset = ReminderPreset.PERSISTENT,
        ),
    ).also { id ->
        repository.addStep(id, "Find dates and availability")
        repository.addStep(id, "Check boarding and parking")
        repository.addStep(id, "Purchase and save confirmation")
    }
}
