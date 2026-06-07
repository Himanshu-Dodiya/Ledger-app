package com.ledger.collector.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of a parsed transaction as stored in the Supabase `transactions` table.
 * Pulled down via PostgREST and used to render the inbox/overview instantly and offline.
 * `source` is "sms" | "gmail" | "manual"; the inbox filters on `reviewed = false`.
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val amount: Double,
    val currency: String,
    val direction: String,
    val merchantRaw: String?,
    val merchantNormalized: String?,
    val category: String,
    val paymentMethod: String?,
    val txnDate: String,    // YYYY-MM-DD
    val referenceId: String?,
    val source: String,
    val reviewed: Boolean,
    val createdAt: String,  // ISO timestamp
)
