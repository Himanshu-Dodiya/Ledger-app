package com.ledger.collector.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.sessionDataStore by preferencesDataStore(name = "ledger_session")

/**
 * Persists the Supabase [UserSession] in a dedicated DataStore so the user stays logged in
 * across app restarts (auto-login) and the library can auto-refresh the access token. We
 * provide our own manager rather than rely on the multiplatform-settings default, keeping
 * persistence self-contained and Android-native.
 */
class DataStoreSessionManager(private val context: Context) : SessionManager {

    private val key = stringPreferencesKey("supabase_session")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun saveSession(session: UserSession) {
        context.sessionDataStore.edit { it[key] = json.encodeToString(UserSession.serializer(), session) }
    }

    override suspend fun loadSession(): UserSession? {
        val raw = context.sessionDataStore.data.first()[key] ?: return null
        return runCatching { json.decodeFromString(UserSession.serializer(), raw) }.getOrNull()
    }

    override suspend fun deleteSession() {
        context.sessionDataStore.edit { it.remove(key) }
    }
}
