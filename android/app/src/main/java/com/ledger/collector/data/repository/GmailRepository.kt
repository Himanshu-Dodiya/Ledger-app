package com.ledger.collector.data.repository

import com.ledger.collector.data.remote.BackendClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Drives server-side Gmail sync. The user connects once (providing a serverAuthCode from
 * the Android Google Authorization API); the Go service stores the encrypted refresh token
 * and polls Gmail on a schedule. The app can also trigger an immediate sync.
 */
class GmailRepository(private val backend: BackendClient) {

    /** Exchange the serverAuthCode for a stored refresh token on the Go service. */
    suspend fun connect(serverAuthCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject().put("serverAuthCode", serverAuthCode).toString()
            val resp = backend.post("/v1/gmail/connect", json)
            if (!resp.isSuccess) throw IOException("gmail connect failed: ${resp.code} ${resp.body}")
        }
    }

    /** Trigger an immediate Gmail sync for the current user on the Go service. */
    suspend fun sync(): Result<SyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.post("/v1/gmail/sync", "{}")
            if (!resp.isSuccess) throw IOException("gmail sync failed: ${resp.code} ${resp.body}")
            val data = JSONObject(resp.body).getJSONObject("data")
            SyncResult(
                fetched  = data.optInt("fetched"),
                inserted = data.optInt("inserted"),
                skipped  = data.optInt("skipped"),
                errors   = data.optInt("errors"),
                llm      = data.optInt("llm"),
            )
        }
    }

    /** Returns true if this user has an active Gmail connection on the Go service. */
    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        try {
            val resp = backend.get("/v1/gmail/status")
            if (!resp.isSuccess) return@withContext false
            JSONObject(resp.body).getJSONObject("data").optBoolean("connected", false)
        } catch (_: Exception) { false }
    }

    /** Remove the Gmail connection (user can re-connect later). */
    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.delete("/v1/gmail")
            if (!resp.isSuccess) throw IOException("disconnect failed: ${resp.code}")
        }
    }

    data class SyncResult(
        val fetched: Int,
        val inserted: Int,
        val skipped: Int,
        val errors: Int,
        val llm: Int,
    )
}
