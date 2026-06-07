package com.ledger.collector.data.repository

import com.ledger.collector.data.local.SmsMessageDao
import com.ledger.collector.data.local.SmsMessageEntity
import com.ledger.collector.data.prefs.SettingsStore
import com.ledger.collector.data.sms.SmsReader
import com.ledger.collector.domain.filter.TransactionClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Owns SMS ingestion: read from the provider → classify → store in Room, and advance the
 * `lastProcessedSmsId` cursor. Room is the source of truth; reads are idempotent (unique
 * provider id), so re-running can never duplicate or lose messages.
 */
class SmsRepository(
    private val dao: SmsMessageDao,
    private val reader: SmsReader,
    private val classifier: TransactionClassifier,
    private val settings: SettingsStore,
) {
    // Reactive stats for the dashboard.
    val totalCount: Flow<Int> = dao.totalCount()
    val transactionalCount: Flow<Int> = dao.transactionalCount()
    val pendingSyncCount: Flow<Int> = dao.pendingSyncCount()
    val syncedCount: Flow<Int> = dao.syncedCount()
    val failedSyncCount: Flow<Int> = dao.failedSyncCount()
    fun recent(limit: Int = 20): Flow<List<SmsMessageEntity>> = dao.recent(limit)
    val lastProcessed: Flow<SmsMessageEntity?> = dao.lastProcessed()

    /** One-time historical import within a window (days). null/0 windowDays handled by caller. */
    suspend fun importHistory(windowDays: Int): Int {
        val since = System.currentTimeMillis() - windowDays.toLong() * DAY_MS
        val inserted = ingest(minExclusiveId = 0L, sinceDateMillis = since)
        settings.setImportDone(true)
        return inserted
    }

    /** Skip import: don't read history, just park the cursor at the newest message. */
    suspend fun skipImport() {
        settings.setLastProcessedSmsId(reader.currentMaxProviderId())
        settings.setImportDone(true)
    }

    /** Incremental: everything with `_id > lastProcessedSmsId`. */
    suspend fun ingestNew(): Int =
        ingest(minExclusiveId = settings.lastProcessedSmsId.first(), sinceDateMillis = null)

    private suspend fun ingest(minExclusiveId: Long, sinceDateMillis: Long?): Int {
        val raws = reader.read(minExclusiveId, sinceDateMillis)
        if (raws.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val entities = raws.map { r ->
            val c = classifier.classify(r.sender, r.body)
            SmsMessageEntity(
                smsProviderId = r.providerId,
                sender = r.sender,
                body = r.body,
                receivedAt = r.receivedAt,
                isTransactional = c.isTransactional,
                confidenceScore = c.score,
                createdAt = now,
                updatedAt = now,
            )
        }
        dao.insertAll(entities)

        // Advance the cursor to the highest id we've now seen.
        val maxId = raws.maxOf { it.providerId }
        if (maxId > minExclusiveId) settings.setLastProcessedSmsId(maxId)
        return raws.size
    }

    suspend fun clearLocalData() {
        dao.clearAll()
        settings.setLastProcessedSmsId(0L)
        settings.setImportDone(false)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
