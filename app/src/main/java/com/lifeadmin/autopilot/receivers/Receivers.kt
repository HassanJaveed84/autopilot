package com.lifeadmin.autopilot.receivers

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lifeadmin.autopilot.appContainer
import com.lifeadmin.autopilot.domain.scheduling.RoutineCycle
import com.lifeadmin.autopilot.reminders.IntentContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "LifeAdminReceivers"

/** Runs [block] off the main thread while the broadcast is kept alive; never crashes the process. */
internal fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            // No timeout here: engine work is non-cancellable and short; cutting it off could
            // drop a reminder. goAsync allows the process to finish it.
            block()
        } catch (t: Throwable) {
            // Log only the type: never task contents.
            Log.w(TAG, "Receiver work failed: ${t.javaClass.simpleName}")
        } finally {
            pending.finish()
        }
    }
}

/** Fired by AlarmManager for one occurrence. Not exported; ids are validated and re-read from Room. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        // Capture the real receipt time first: engine work may queue behind other operations.
        val receivedAt = container.clock.now()
        when (intent.action) {
            IntentContract.ACTION_FIRE -> {
                val occurrenceId = IntentContract.occurrenceIdFrom(intent.data) ?: return
                val generation = intent.getIntExtra(IntentContract.EXTRA_GENERATION, -1)
                runAsync { container.reminderEngine.onAlarm(occurrenceId, generation, receivedAt) }
            }
            IntentContract.ACTION_TEST_FIRE -> {
                val testId = IntentContract.testIdFrom(intent.data) ?: return
                runAsync { container.selfTest.onTestAlarm(testId, receivedAt) }
            }
        }
    }
}

/** Done / Snooze buttons on reminder notifications. Not exported. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != IntentContract.ACTION_DONE && action != IntentContract.ACTION_SNOOZE && action != IntentContract.ACTION_DONE_FOR_NOW) return
        val taskId = IntentContract.taskIdFrom(intent.data) ?: return
        val container = context.appContainer
        runAsync {
            val task = container.taskRepository.getTask(taskId) ?: run {
                container.notificationPublisher.cancelForTask(taskId)
                return@runAsync
            }
            when (action) {
                IntentContract.ACTION_DONE -> if (!task.status.isTerminal) container.taskRepository.complete(taskId) else container.notificationPublisher.cancelForTask(taskId)
                IntentContract.ACTION_SNOOZE -> if (!task.status.isTerminal) container.taskActions.snoozeWithDefault(taskId) else container.notificationPublisher.cancelForTask(taskId)
                // Re-checked against Room: if the task is no longer an open routine, the tap only
                // clears the notification; it never falls back to completing the task.
                IntentContract.ACTION_DONE_FOR_NOW ->
                    if (RoutineCycle.canFinishCycle(task, container.taskRepository.getPolicy(taskId))) container.taskRepository.finishCycle(taskId, container.taskRepository.latestDeliveredSlot(taskId))
                    else container.notificationPublisher.cancelForTask(taskId)
            }
        }
    }
}

/**
 * Rebuilds the alarm projection after events that clear or invalidate alarms. Only protected
 * system broadcasts are listed in the manifest; the action is re-checked here and the work is
 * an idempotent reconcile, so a spoofed broadcast could at most trigger a harmless rebuild.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app_updated"
            Intent.ACTION_TIME_CHANGED -> "time_changed"
            Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> "exact_alarm_permission"
            else -> return
        }
        val engine = context.appContainer.reminderEngine
        runAsync { engine.reconcileAll(reason) }
    }
}
