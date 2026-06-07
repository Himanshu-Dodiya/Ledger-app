package com.ledger.collector.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ledger.collector.work.SyncInterval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ledger_settings")

/**
 * Local app settings + sync cursor. The cursor we persist is the SMS provider `_id`
 * (`lastProcessedSmsId`), not a timestamp, so new-message discovery is `_id > lastId`.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val SYNC_INTERVAL = stringPreferencesKey("sync_interval")
        val IMPORT_WINDOW_DAYS = intPreferencesKey("import_window_days") // 0 = skip
        val IMPORT_DONE = booleanPreferencesKey("import_done")
        val LAST_PROCESSED_SMS_ID = longPreferencesKey("last_processed_sms_id")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    }

    val syncInterval: Flow<SyncInterval> =
        context.dataStore.data.map { SyncInterval.fromName(it[Keys.SYNC_INTERVAL]) }

    val importWindowDays: Flow<Int> =
        context.dataStore.data.map { it[Keys.IMPORT_WINDOW_DAYS] ?: 30 }

    val importDone: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.IMPORT_DONE] ?: false }

    val lastProcessedSmsId: Flow<Long> =
        context.dataStore.data.map { it[Keys.LAST_PROCESSED_SMS_ID] ?: 0L }

    val lastSyncAt: Flow<Long> =
        context.dataStore.data.map { it[Keys.LAST_SYNC_AT] ?: 0L }

    suspend fun setSyncInterval(interval: SyncInterval) =
        context.dataStore.edit { it[Keys.SYNC_INTERVAL] = interval.name }.let { }

    suspend fun setImportWindowDays(days: Int) =
        context.dataStore.edit { it[Keys.IMPORT_WINDOW_DAYS] = days }.let { }

    suspend fun setImportDone(done: Boolean) =
        context.dataStore.edit { it[Keys.IMPORT_DONE] = done }.let { }

    suspend fun setLastProcessedSmsId(id: Long) =
        context.dataStore.edit { it[Keys.LAST_PROCESSED_SMS_ID] = id }.let { }

    suspend fun setLastSyncAt(ts: Long) =
        context.dataStore.edit { it[Keys.LAST_SYNC_AT] = ts }.let { }

    suspend fun currentSyncInterval(): SyncInterval = syncInterval.first()

    suspend fun reset() = context.dataStore.edit { it.clear() }.let { }
}
