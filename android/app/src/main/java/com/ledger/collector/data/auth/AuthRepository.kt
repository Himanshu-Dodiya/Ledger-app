package com.ledger.collector.data.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin wrapper over the Supabase Auth plugin. Owns sign-up/in (email + Google ID token),
 * sign-out, and exposes the reactive [sessionStatus] used to gate the UI. Session
 * persistence and access-token refresh are handled by the plugin (see
 * [DataStoreSessionManager]); callers just read [accessToken] when they need the bearer.
 */
class AuthRepository(private val client: SupabaseClient) {

    val sessionStatus: StateFlow<SessionStatus> = client.auth.sessionStatus

    suspend fun signUpEmail(email: String, password: String) {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signInEmail(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /** [rawNonce] must be the un-hashed nonce; the Google ID token carries its SHA-256. */
    suspend fun signInGoogle(idToken: String, rawNonce: String) {
        client.auth.signInWith(IDToken) {
            this.idToken = idToken
            this.provider = Google
            this.nonce = rawNonce
        }
    }

    suspend fun signOut() = client.auth.signOut()

    /**
     * Sends a password-reset email with a link that deep-links back to the app via
     * `io.ledger.collector://reset-callback`. Add this URL to Supabase Auth → Redirect URLs.
     */
    suspend fun requestPasswordReset(email: String) {
        client.auth.resetPasswordForEmail(email, redirectUrl = "io.ledger.collector://reset-callback")
    }

/** Called after the user arrives via the deep link and enters a new password. */
    suspend fun updatePassword(newPassword: String) {
        client.auth.updateUser { password = newPassword }
    }

    fun currentUserId(): String? = client.auth.currentSessionOrNull()?.user?.id
    fun currentEmail(): String? = client.auth.currentSessionOrNull()?.user?.email

    /** Current access token (auto-refreshed by the plugin), or null when logged out. */
    fun accessToken(): String? = client.auth.currentSessionOrNull()?.accessToken

    /**
     * Returns a non-expired access token, refreshing it first if it expires within 60s.
     * This is what backend calls should use — a stale token causes the Go service to reject
     * the request with 401 "token expired". Refresh failures are swallowed (the caller then
     * gets whatever token exists, or null).
     */
    suspend fun validAccessToken(): String? {
        val session = client.auth.currentSessionOrNull() ?: return null
        val nowEpoch = System.currentTimeMillis() / 1000
        if (session.expiresAt.epochSeconds - nowEpoch < 60) {
            runCatching { client.auth.refreshCurrentSession() }
        }
        return client.auth.currentSessionOrNull()?.accessToken
    }
}
