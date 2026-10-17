package com.lifeadmin.autopilot.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lifeadmin.autopilot.receivers.ReminderAlarmReceiver

/**
 * AlarmManager adapter.
 *
 * Default delivery is best-effort `setAndAllowWhileIdle` (inexact, may be batched/delayed by
 * Doze but still fires while idle). Exact `setExactAndAllowWhileIdle` is used only when the user
 * enabled precision mode AND granted the SCHEDULE_EXACT_ALARM special access; if access is
 * missing or revoked we fall back to inexact automatically and report it.
 */
class AndroidAlarmDriver(private val context: Context) : AlarmDriver {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun schedule(request: AlarmRequest): AlarmScheduleResult {
        val pi = pendingIntent(request.occurrenceId, request.generation, create = true)!!
        val at = request.triggerAt.toEpochMilli()
        val wantExact = request.preferExact && canScheduleExact()
        return try {
            if (wantExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            AlarmScheduleResult(scheduled = true, exact = wantExact, error = if (request.preferExact && !wantExact) "exact_unavailable" else null)
        } catch (e: SecurityException) {
            // Exact access revoked between check and call: degrade to inexact.
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                .fold({ AlarmScheduleResult(true, false, "exact_denied") }, { AlarmScheduleResult(false, false, "security_exception") })
        } catch (e: RuntimeException) {
            // e.g. the per-app alarm limit; occurrence stays PLANNED and is retried on next reconcile.
            AlarmScheduleResult(scheduled = false, exact = false, error = e.javaClass.simpleName)
        }
    }

    override fun scheduleTest(testId: String, triggerAt: java.time.Instant, preferExact: Boolean): AlarmScheduleResult {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(IntentContract.ACTION_TEST_FIRE)
            .setData(IntentContract.testUri(testId))
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val wantExact = preferExact && canScheduleExact()
        return try {
            if (wantExact) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pi)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pi)
            AlarmScheduleResult(true, wantExact, if (preferExact && !wantExact) "exact_unavailable" else null)
        } catch (e: RuntimeException) {
            AlarmScheduleResult(false, false, e.javaClass.simpleName)
        }
    }

    override fun cancel(occurrenceId: String) {
        pendingIntent(occurrenceId, 0, create = false)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private fun pendingIntent(occurrenceId: String, generation: Int, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(IntentContract.ACTION_FIRE)
            .setData(IntentContract.occurrenceUri(occurrenceId))
            .putExtra(IntentContract.EXTRA_GENERATION, generation)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}
