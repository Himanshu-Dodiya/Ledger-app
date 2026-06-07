package com.ledger.collector.services

import android.os.Build
import com.ledger.collector.data.remote.BackendClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object DeviceRegistration {

    /** Register (or refresh) a FCM token with the Go service. Silently ignores failures. */
    suspend fun register(token: String, backend: BackendClient) {
        try {
            val json = JSONObject()
                .put("fcm_token", token)
                .put("platform", "android")
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .toString()
            backend.post("/v1/devices", json)
        } catch (_: Exception) {
            // Push notifications are optional — never crash the app over this.
        }
    }

    /** Fetch the current FCM token and register it. Call once after login. */
    suspend fun registerCurrent(backend: BackendClient) {
        try {
            val token = getToken()
            register(token, backend)
        } catch (_: Exception) { /* no-op */ }
    }

    private suspend fun getToken(): String = suspendCancellableCoroutine { cont ->
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
