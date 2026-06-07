package com.ledger.collector.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.collector.data.auth.AuthRepository
import com.ledger.collector.data.prefs.SettingsStore
import com.ledger.collector.data.repository.DeviceRepository
import com.ledger.collector.data.repository.GmailRepository
import com.ledger.collector.data.repository.SmsRepository
import com.ledger.collector.data.repository.TransactionRepository
import com.ledger.collector.work.SmsWorkScheduler
import com.ledger.collector.work.SyncInterval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val smsRepository: SmsRepository,
    private val authRepository: AuthRepository,
    private val transactionRepository: TransactionRepository,
    private val gmailRepository: GmailRepository,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {

    val syncInterval = settings.syncInterval
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncInterval.MIN_30)
    val importWindowDays = settings.importWindowDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)
    val lastSyncAt = settings.lastSyncAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val email: String? get() = authRepository.currentEmail()

    // ---- Gmail ----
    private val _gmailConnected = MutableStateFlow(false)
    val gmailConnected: StateFlow<Boolean> = _gmailConnected.asStateFlow()

    private val _gmailStatus = MutableStateFlow<String?>(null)
    val gmailStatus: StateFlow<String?> = _gmailStatus.asStateFlow()

    private val _gmailSyncing = MutableStateFlow(false)
    val gmailSyncing: StateFlow<Boolean> = _gmailSyncing.asStateFlow()

    // ---- Devices ----
    private val _devices = MutableStateFlow<List<DeviceRepository.Device>>(emptyList())
    val devices: StateFlow<List<DeviceRepository.Device>> = _devices.asStateFlow()

    private val _devicesLoading = MutableStateFlow(false)
    val devicesLoading: StateFlow<Boolean> = _devicesLoading.asStateFlow()

    init {
        viewModelScope.launch {
            _gmailConnected.value = gmailRepository.isConnected()
        }
        loadDevices()
    }

    // ---- Gmail actions ----

    fun connectGmail(serverAuthCode: String) {
        viewModelScope.launch {
            _gmailStatus.value = "Connecting…"
            gmailRepository.connect(serverAuthCode)
                .onSuccess {
                    _gmailConnected.value = true
                    _gmailStatus.value = "Gmail connected. Syncing now…"
                    syncGmail()
                }
                .onFailure { _gmailStatus.value = "Connection failed: ${it.message}" }
        }
    }

    fun syncGmail() {
        if (_gmailSyncing.value) return
        _gmailSyncing.value = true
        viewModelScope.launch {
            gmailRepository.sync()
                .onSuccess { result ->
                    _gmailStatus.value = "Synced: ${result.inserted} new, ${result.skipped} skipped"
                    if (result.inserted > 0) transactionRepository.refresh()
                }
                .onFailure { _gmailStatus.value = "Sync failed: ${it.message}" }
            _gmailSyncing.value = false
        }
    }

    fun disconnectGmail() {
        viewModelScope.launch {
            gmailRepository.disconnect()
                .onSuccess {
                    _gmailConnected.value = false
                    _gmailStatus.value = "Gmail disconnected."
                }
                .onFailure { _gmailStatus.value = "Disconnect failed: ${it.message}" }
        }
    }

    // ---- Device actions ----

    fun loadDevices() {
        _devicesLoading.value = true
        viewModelScope.launch {
            deviceRepository.list()
                .onSuccess { _devices.value = it }
            _devicesLoading.value = false
        }
    }

    fun revokeDevice(id: String) {
        viewModelScope.launch {
            deviceRepository.revoke(id).onSuccess {
                _devices.value = _devices.value.filter { it.id != id }
            }
        }
    }

    // ---- SMS / sync ----

    fun setSyncInterval(interval: SyncInterval) {
        viewModelScope.launch {
            settings.setSyncInterval(interval)
            SmsWorkScheduler.apply(appContext, interval)
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.signOut()
            transactionRepository.clear()
        }
    }

    fun clearLocalData() {
        viewModelScope.launch { smsRepository.clearLocalData() }
    }
}
