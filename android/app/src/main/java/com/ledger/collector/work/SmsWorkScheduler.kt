package com.ledger.collector.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Centralises WorkManager scheduling so the worker stays a pure pipeline. */
object SmsWorkScheduler {

    private val networkConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Apply the user's chosen cadence (MANUAL cancels the periodic job). */
    fun apply(context: Context, interval: SyncInterval) {
        val wm = WorkManager.getInstance(context)
        if (!interval.isPeriodic) {
            wm.cancelUniqueWork(SmsSyncWorker.UNIQUE_PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SmsSyncWorker>(interval.minutes, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // UPDATE keeps a single job and adopts the new interval without dropping queued work.
        wm.enqueueUniquePeriodicWork(
            SmsSyncWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Fire a one-shot run now (manual "Sync Now" and live-SMS capture). */
    fun runOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<SmsSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SmsSyncWorker.UNIQUE_ONESHOT,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}
