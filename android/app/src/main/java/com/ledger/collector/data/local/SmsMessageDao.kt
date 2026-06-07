package com.ledger.collector.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsMessageDao {

    // IGNORE on the unique smsProviderId index = idempotent inserts (re-reads are safe).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<SmsMessageEntity>): List<Long>

    @Query("SELECT MAX(smsProviderId) FROM sms_messages")
    suspend fun maxStoredProviderId(): Long?

    @Query("SELECT * FROM sms_messages WHERE synced = 0 AND isTransactional = 1 ORDER BY receivedAt ASC LIMIT :limit")
    suspend fun pendingForSync(limit: Int): List<SmsMessageEntity>

    @Query("UPDATE sms_messages SET synced = 1, uploadedAt = :ts, updatedAt = :ts, lastSyncError = NULL WHERE id = :id")
    suspend fun markSynced(id: Long, ts: Long)

    @Query("UPDATE sms_messages SET syncAttempts = syncAttempts + 1, lastSyncError = :error, updatedAt = :ts WHERE id = :id")
    suspend fun markFailed(id: Long, error: String, ts: Long)

    // ---- stats (reactive) ----
    @Query("SELECT COUNT(*) FROM sms_messages")
    fun totalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE isTransactional = 1")
    fun transactionalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE isTransactional = 1 AND synced = 0")
    fun pendingSyncCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE synced = 1")
    fun syncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE synced = 0 AND syncAttempts > 0")
    fun failedSyncCount(): Flow<Int>

    @Query("SELECT * FROM sms_messages ORDER BY receivedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SmsMessageEntity>>

    @Query("SELECT * FROM sms_messages ORDER BY receivedAt DESC LIMIT 1")
    fun lastProcessed(): Flow<SmsMessageEntity?>

    @Query("DELETE FROM sms_messages")
    suspend fun clearAll()
}
