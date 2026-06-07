package com.ledger.collector.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.collector.data.auth.AuthRepository
import com.ledger.collector.data.local.SmsMessageEntity
import com.ledger.collector.data.local.TransactionEntity
import com.ledger.collector.data.prefs.SettingsStore
import com.ledger.collector.data.repository.SmsRepository
import com.ledger.collector.data.repository.SyncRepository
import com.ledger.collector.data.repository.TransactionRepository
import com.ledger.collector.work.SyncInterval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val smsRepository: SmsRepository,
    private val syncRepository: SyncRepository,
    private val settings: SettingsStore,
    private val transactionRepository: TransactionRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private fun <T> StateFlowOf(source: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
        source.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    // Overview: real transactions synced from the backend.
    val unreviewedCount = StateFlowOf(transactionRepository.unreviewedCount, 0)
    val recentTransactions = StateFlowOf(transactionRepository.recent(10), emptyList<TransactionEntity>())

    // Debug: raw local SMS pipeline stats.
    val totalCount = StateFlowOf(smsRepository.totalCount, 0)
    val transactionalCount = StateFlowOf(smsRepository.transactionalCount, 0)
    val pendingSyncCount = StateFlowOf(smsRepository.pendingSyncCount, 0)
    val syncedCount = StateFlowOf(smsRepository.syncedCount, 0)
    val failedSyncCount = StateFlowOf(smsRepository.failedSyncCount, 0)
    val recent = StateFlowOf(smsRepository.recent(20), emptyList<SmsMessageEntity>())
    val lastProcessed = StateFlowOf(smsRepository.lastProcessed, null as SmsMessageEntity?)

    val syncInterval = StateFlowOf(settings.syncInterval, SyncInterval.MIN_30)
    val lastSyncAt = StateFlowOf(settings.lastSyncAt, 0L)
    val importDone = StateFlowOf(settings.importDone, true)

    val email: String? get() = authRepository.currentEmail()

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /** Human-readable result of the last sync, shown on the Overview screen. */
    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    fun onPermissionChanged(granted: Boolean) {
        _permissionGranted.value = granted
    }

    /**
     * Manual "Sync Now": ingest new SMS → push them → pull fresh transactions down.
     * Each stage runs independently so a failure in one (e.g. SMS read) doesn't silently
     * block the others, and the outcome is surfaced via [syncStatus].
     */
    fun syncNow() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncStatus.value = "Syncing…"
        viewModelScope.launch {
            // Stage 1: read new SMS into Room.
            val ingested = runCatching { smsRepository.ingestNew() }
                .onFailure { Log.w(TAG, "ingestNew failed", it) }
                .getOrDefault(0)
            Log.d(TAG, "ingestNew: $ingested new message(s) read")

            // Stage 2: drain the queue to the backend (runs even if stage 1 failed).
            val summary = runCatching { syncRepository.syncPending() }
                .onFailure { Log.w(TAG, "syncPending failed", it) }
                .getOrNull()

            // Stage 3: pull fresh transactions into the local cache.
            val refreshed = runCatching { transactionRepository.refresh() }
                .onFailure { Log.w(TAG, "refresh failed", it) }

            settings.setLastSyncAt(System.currentTimeMillis())
            _syncStatus.value = buildStatus(summary, refreshed.isSuccess)
            _isSyncing.value = false
        }
    }

    private fun buildStatus(
        summary: com.ledger.collector.data.repository.SyncRepository.Summary?,
        refreshOk: Boolean,
    ): String {
        if (summary == null) return "Sync failed — couldn't reach the server."
        val parts = mutableListOf<String>()
        parts += "${summary.attempted} uploaded"
        if (summary.created > 0) parts += "${summary.created} new"
        if (summary.duplicate > 0) parts += "${summary.duplicate} dup"
        if (summary.rejected > 0) parts += "${summary.rejected} not-a-txn"
        if (summary.failed > 0) parts += "${summary.failed} failed"
        var msg = parts.joinToString(", ")
        if (summary.failed > 0 && summary.lastError != null) msg += " · ${summary.lastError}"
        if (!refreshOk) msg += " · pull failed"
        return msg
    }

    fun importHistory(days: Int) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncStatus.value = "Importing last $days days…"
        viewModelScope.launch {
            val imported = runCatching { smsRepository.importHistory(days) }
                .onFailure { Log.w(TAG, "importHistory failed", it) }
                .getOrDefault(0)
            Log.d(TAG, "importHistory: $imported message(s) read")
            val summary = runCatching { syncRepository.syncPending() }
                .onFailure { Log.w(TAG, "syncPending failed", it) }
                .getOrNull()
            val refreshed = runCatching { transactionRepository.refresh() }
                .onFailure { Log.w(TAG, "refresh failed", it) }
            settings.setLastSyncAt(System.currentTimeMillis())
            _syncStatus.value = buildStatus(summary, refreshed.isSuccess)
            _isSyncing.value = false
        }
    }

    fun skipImport() {
        viewModelScope.launch { smsRepository.skipImport() }
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
