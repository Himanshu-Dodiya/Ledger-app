package com.ledger.collector.data.repository

import android.util.Log
import com.ledger.collector.data.local.SmsMessageDao
import com.ledger.collector.data.remote.SmsSyncPayload
import com.ledger.collector.data.remote.SyncApi
import com.ledger.collector.data.remote.SyncOutcome

/**
 * Drains the local queue (unsynced, transactional messages) to the backend. Offline-safe:
 * anything that fails stays in Room with an incremented attempt count and is retried on the
 * next run. Nothing is deleted.
 */
class SyncRepository(
    private val dao: SmsMessageDao,
    private val api: SyncApi,
) {
    /**
     * Full breakdown of a sync run so the UI can show exactly what happened:
     * attempted = messages drained; created = transactions actually inserted;
     * duplicate/rejected = accepted by server but no transaction; failed = kept queued.
     */
    data class Summary(
        val attempted: Int = 0,
        val created: Int = 0,
        val duplicate: Int = 0,
        val rejected: Int = 0,
        val failed: Int = 0,
        val lastError: String? = null,
    ) {
        val succeeded: Int get() = created + duplicate + rejected
    }

    suspend fun syncPending(batch: Int = 100): Summary {
        val pending = dao.pendingForSync(batch)
        Log.d(TAG, "syncPending: ${pending.size} message(s) queued")

        var created = 0
        var duplicate = 0
        var rejected = 0
        var failed = 0
        var lastError: String? = null

        for (m in pending) {
            val now = System.currentTimeMillis()
            when (val outcome = api.upload(SmsSyncPayload(m.body, m.sender, m.receivedAt))) {
                is SyncOutcome.Success -> {
                    dao.markSynced(m.id, now)
                    when {
                        outcome.inserted -> created++
                        outcome.reason?.contains("duplicate", ignoreCase = true) == true -> duplicate++
                        else -> rejected++
                    }
                }
                is SyncOutcome.Failure -> {
                    dao.markFailed(m.id, outcome.message, now)
                    failed++
                    lastError = outcome.message
                }
            }
        }

        val summary = Summary(
            attempted = pending.size,
            created = created,
            duplicate = duplicate,
            rejected = rejected,
            failed = failed,
            lastError = lastError,
        )
        Log.d(TAG, "syncPending done: $summary")
        return summary
    }

    private companion object {
        const val TAG = "SyncRepository"
    }
}
