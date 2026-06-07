package com.ledger.collector.ui.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID

/**
 * Drives the Credential Manager "Sign in with Google" flow and returns the Google ID token
 * plus the raw nonce. Supabase verifies the token's hashed nonce against [rawNonce], so both
 * must come from the same request.
 */
object GoogleSignIn {

    data class Result(val idToken: String, val rawNonce: String)

    suspend fun request(context: Context, webClientId: String): Result {
        require(webClientId.isNotBlank()) { "GOOGLE_WEB_CLIENT_ID is not configured" }

        val rawNonce = UUID.randomUUID().toString()
        val hashedNonce = sha256Hex(rawNonce)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setNonce(hashedNonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val response = CredentialManager.create(context).getCredential(context, request)
        val cred = response.credential
        if (cred is CustomCredential &&
            cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val token = GoogleIdTokenCredential.createFrom(cred.data)
            return Result(token.idToken, rawNonce)
        }
        throw IllegalStateException("Unexpected credential type: ${cred.type}")
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
