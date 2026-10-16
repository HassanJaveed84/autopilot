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
        val engine = applicationContext.appContainer.reminderEngine
        engine.reconcileAll("periodic")
        engine.pruneLedger()
        return Result.success()
    }

    companion object {
        private const val NAME = "reconcile-maintenance"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReconcileWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
