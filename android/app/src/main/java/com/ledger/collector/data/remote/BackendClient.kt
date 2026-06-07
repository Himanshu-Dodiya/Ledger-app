package com.ledger.collector.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Authenticated client for the Go ledger-api service. Every request carries the logged-in
 * user's Supabase JWT as `Authorization: Bearer <token>`. The token provider is suspend so
 * it can refresh an expired token before the call (avoids 401 "token expired").
 *
 * Calls are blocking OkHttp under the hood, dispatched on IO.
 */
class BackendClient(
    private val baseUrl: String,
    private val tokenProvider: suspend () -> String?,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** code == -1 means the request never completed (network/IO); body holds the message. */
    data class Resp(val code: Int, val body: String) {
        val isSuccess: Boolean get() = code in 200..299
        val isAuthError: Boolean get() = code == 401 || code == 403
    }

    suspend fun get(path: String): Resp = execute("GET", path, null)
    suspend fun post(path: String, json: String): Resp = execute("POST", path, json.toRequestBody(jsonMedia))
    suspend fun patch(path: String, json: String): Resp = execute("PATCH", path, json.toRequestBody(jsonMedia))
    suspend fun delete(path: String): Resp = execute("DELETE", path, null)

    private suspend fun execute(method: String, path: String, body: RequestBody?): Resp =
        withContext(Dispatchers.IO) {
            val token = tokenProvider()
            if (token == null) {
                Log.w(TAG, "$method $path → no session (token null)")
                return@withContext Resp(401, "no session")
            }
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .method(method, body)
                .header("Authorization", "Bearer $token")
                // Bypass ngrok free-tier's HTML interstitial so API calls reach the backend.
                .header("ngrok-skip-browser-warning", "true")
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    Log.d(TAG, "$method $path → ${resp.code}" +
                        if (resp.code !in 200..299) " | $respBody" else "")
                    Resp(resp.code, respBody)
                }
            } catch (e: Exception) {
                Log.w(TAG, "$method $path → network error: ${e.message}")
                Resp(-1, e.message ?: "network error")
            }
        }

    private companion object {
        const val TAG = "BackendClient"
    }
}
