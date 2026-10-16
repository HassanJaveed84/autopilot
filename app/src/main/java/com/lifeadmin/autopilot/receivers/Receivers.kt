package com.lifeadmin.autopilot.receivers

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lifeadmin.autopilot.appContainer
import com.lifeadmin.autopilot.reminders.IntentContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "LifeAdminReceivers"

/** Runs [block] off the main thread within the broadcast time budget; never crashes the process. */
internal fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            withTimeoutOrNull(9_000) { block() }
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
        if (intent.action != IntentContract.ACTION_FIRE) return
        val occurrenceId = IntentContract.occurrenceIdFrom(intent.data) ?: return
        val generation = intent.getIntExtra(IntentContract.EXTRA_GENERATION, -1)
        val engine = context.appContainer.reminderEngine
        runAsync { engine.onAlarm(occurrenceId, generation) }
    }
}

/** Done / Snooze buttons on reminder notifications. Not exported. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != IntentContract.ACTION_DONE && action != IntentContract.ACTION_SNOOZE) return
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
