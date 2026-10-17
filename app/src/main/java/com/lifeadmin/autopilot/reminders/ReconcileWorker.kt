package com.lifeadmin.autopilot.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lifeadmin.autopilot.appContainer
import java.util.concurrent.TimeUnit

/**
 * Deferrable safety net only: re-derives alarms (e.g. retries occurrences the OS refused) and
 * prunes the old delivery ledger. It is NOT the reminder clock — AlarmManager is.
 */
class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        container.reminderEngine.reconcileAll("periodic")
        container.reminderEngine.pruneLedger()
        container.diagnosticsLog.prune()
        return Result.success()
    }

    companion object {
        private const val NAME = "reconcile-maintenance"

        fun schedule(context: Context) {
            // Hourly (deferrable) catch-up keeps a dropped/long-deferred inexact alarm well inside the
            // 4 h grace window even if the app is never opened. It is a cheap DB read; Android still
            // batches it with other work. UPDATE replaces the older 6 h schedule on existing installs.
            val request = PeriodicWorkRequestBuilder<ReconcileWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
