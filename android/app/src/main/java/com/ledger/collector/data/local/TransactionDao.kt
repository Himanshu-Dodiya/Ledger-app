package com.ledger.collector.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Upsert
    suspend fun upsertAll(items: List<TransactionEntity>)

    // Inbox = unreviewed, newest first. txnDate/createdAt are ISO strings → lexicographic sort is correct.
    @Query("SELECT * FROM transactions WHERE reviewed = 0 ORDER BY txnDate DESC, createdAt DESC")
    fun inbox(): Flow<List<TransactionEntity>>

    // Transactions screen = reviewed, newest first.
    @Query("SELECT * FROM transactions WHERE reviewed = 1 ORDER BY txnDate DESC, createdAt DESC")
    fun reviewed(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE reviewed = 0")
    fun unreviewedCount(): Flow<Int>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: String): TransactionEntity?

    @Query("UPDATE transactions SET reviewed = :reviewed, category = :category WHERE id = :id")
    suspend fun updateReview(id: String, reviewed: Boolean, category: String)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM transactions")
    suspend fun clearAll()
}
