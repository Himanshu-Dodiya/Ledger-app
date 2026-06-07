package com.ledger.collector.data.remote

import android.util.Log
import org.json.JSONObject

/**
 * Real backend transport: forwards each queued transactional SMS to `POST /v1/ingest/sms`,
 * which parses, dedupes, categorizes, and stores it as a transaction (`source: "sms"`).
 *
 * The backend returns `{"data": {"inserted": bool, "reason": string?, "id": string?}}`.
 * A 2xx with inserted=false (not a transaction / duplicate) still drains the message from
 * the queue — but we surface inserted/reason so the UI can show what actually happened.
 * Network / 5xx / auth errors keep it queued for the next run.
 */
class HttpSyncApi(private val backend: BackendClient) : SyncApi {
    override suspend fun upload(payload: SmsSyncPayload): SyncOutcome {
        val json = JSONObject().apply {
            put("text", payload.text)
            if (payload.sender != null) put("sender", payload.sender)
            put("timestamp", payload.timestamp)
        }.toString()

        val resp = backend.post("/v1/ingest/sms", json)
        return when {
            resp.isSuccess -> {
                // Parse {"data":{"inserted":...,"reason":...}}
                val data = runCatching { JSONObject(resp.body).optJSONObject("data") }.getOrNull()
                val inserted = data?.optBoolean("inserted", false) ?: false
                val reason = data?.optString("reason")?.takeIf { it.isNotBlank() }
                Log.d(TAG, "ingest ok: inserted=$inserted reason=$reason sender=${payload.sender}")
                SyncOutcome.Success(inserted = inserted, reason = reason)
            }
            resp.isAuthError -> {
                Log.w(TAG, "ingest auth error ${resp.code}: ${resp.body}")
                SyncOutcome.Failure("unauthorized (${resp.code}): ${resp.body}", retryable = true)
            }
            resp.code == -1 || resp.code >= 500 -> {
                Log.w(TAG, "ingest transient error ${resp.code}: ${resp.body}")
                SyncOutcome.Failure(resp.body, retryable = true)
            }
            else -> {
                Log.w(TAG, "ingest rejected ${resp.code}: ${resp.body}")
                SyncOutcome.Failure("http ${resp.code}: ${resp.body}", retryable = false)
            }
        }
    }

    private companion object {
        const val TAG = "HttpSyncApi"
    }
}
