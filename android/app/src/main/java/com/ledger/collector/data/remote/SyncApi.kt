package com.ledger.collector.data.remote

/**
 * Sync transport. Phase 1 uses [MockSyncApi]; the real implementation will POST to the
 * backend `/api/sms` with `Authorization: Bearer <device token>` and body
 * `{ text, sender, timestamp }`, returning `{ ok, inserted }`. Keeping this an interface
 * means the worker/repository never change when the real client is added.
 */
interface SyncApi {
    suspend fun upload(payload: SmsSyncPayload): SyncOutcome
}

/** Mirrors the backend `/api/sms` request body exactly. */
data class SmsSyncPayload(
    val text: String,
    val sender: String?,
    val timestamp: Long, // epoch ms
)

sealed interface SyncOutcome {
    /**
     * The backend accepted the message (HTTP 2xx). [inserted] tells whether a transaction was
     * actually created; if false, [reason] explains why (e.g. "not a transaction", "duplicate").
     * Either way the message is drained from the queue.
     */
    data class Success(val inserted: Boolean, val reason: String?) : SyncOutcome
    /** retryable = transient (network/5xx/auth) → keep queued; non-retryable = drop/accept. */
    data class Failure(val message: String, val retryable: Boolean) : SyncOutcome
}
