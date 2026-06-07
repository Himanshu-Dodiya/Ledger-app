package com.ledger.collector.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One raw SMS, stored permanently (Phase 1 never deletes). `smsProviderId` is the Android
 * SMS provider `_id` and is unique, so re-reading the inbox can't create duplicates.
 *
 * Phase 1 does NOT fully parse transactions — it only classifies likely-transactional SMS
 * (`isTransactional` + `confidenceScore`) and leaves real parsing to the backend (which is
 * mocked for now). `parsed` is reserved for when the backend confirms a parse.
 */
@Entity(
    tableName = "sms_messages",
    indices = [Index(value = ["smsProviderId"], unique = true)]
)
data class SmsMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsProviderId: Long,        // content://sms/inbox _id
    val sender: String,
    val body: String,
    val receivedAt: Long,           // provider DATE (epoch ms)

    // classification (Stage 1–3 confidence)
    val isTransactional: Boolean,
    val confidenceScore: Int,       // 0..100

    // lifecycle flags
    val processed: Boolean = true,  // classified locally
    val parsed: Boolean = false,    // backend-parsed (future)
    val synced: Boolean = false,
    val syncAttempts: Int = 0,
    val lastSyncError: String? = null,
    val uploadedAt: Long? = null,

    val createdAt: Long,
    val updatedAt: Long,
)
