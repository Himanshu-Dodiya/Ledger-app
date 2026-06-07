package com.ledger.collector.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ledger.collector.LedgerApp
import com.ledger.collector.hasSmsPermission

/**
 * The whole pipeline in one unit of work: read new SMS → classify → store → sync pending →
 * stamp last-sync. Idempotent and offline-safe (Room dedupes; failed uploads stay queued).
 * Used both for periodic background runs and one-shot live/manual triggers.
 */
class SmsSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // No permission yet → nothing to do; succeed so WorkManager doesn't thrash.
        if (!hasSmsPermission(applicationContext)) return Result.success()

        val graph = (applicationContext as LedgerApp).graph
        return try {
            // Always ingest into the local cache, even when logged out, so nothing is missed.
            graph.smsRepository.ingestNew()

            // Network steps need a session; skip them cleanly when signed out.
            if (graph.authRepository.accessToken() != null) {
                graph.syncRepository.syncPending()
                graph.transactionRepository.refresh()
                graph.settingsStore.setLastSyncAt(System.currentTimeMillis())
            }
            Result.success()
        } catch (e: Exception) {
            // Transient (e.g. DB busy / network) → let WorkManager back off and retry.
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_PERIODIC = "ledger_periodic_sync"
        const val UNIQUE_ONESHOT = "ledger_oneshot_sync"
    }
}
