package com.ledger.collector.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.collector.data.local.TransactionEntity
import com.ledger.collector.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InboxViewModel(private val repo: TransactionRepository) : ViewModel() {

    val items: StateFlow<List<TransactionEntity>> =
        repo.inbox.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _firstLoadDone = MutableStateFlow(false)
    val firstLoadDone: StateFlow<Boolean> = _firstLoadDone.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            repo.refresh()
                .onFailure { _error.value = it.message ?: "Couldn't reach the server" }
                .onSuccess { _error.value = null }
            _refreshing.value = false
            _firstLoadDone.value = true
        }
    }

    fun categorize(id: String, category: String) = viewModelScope.launch {
        repo.markReviewed(id, category).onFailure { _error.value = "Update failed — try again" }
    }

    fun skip(id: String) = viewModelScope.launch {
        repo.skip(id).onFailure { _error.value = "Update failed — try again" }
    }

    fun delete(id: String) = viewModelScope.launch {
        repo.delete(id).onFailure { _error.value = "Delete failed — try again" }
    }

    fun clearError() { _error.value = null }
}
