package com.ledger.collector.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.collector.data.remote.AnalyticsDto
import com.ledger.collector.data.remote.BalancesDto
import com.ledger.collector.data.remote.InsightDto
import com.ledger.collector.data.repository.AnalyticsRepository
import com.ledger.collector.data.repository.SplitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Backs the analytics dashboard: spend/income breakdowns plus pending settlement totals. */
class AnalyticsViewModel(
    private val analyticsRepo: AnalyticsRepository,
    private val splitRepo: SplitRepository,
) : ViewModel() {

    private val _data = MutableStateFlow<AnalyticsDto?>(null)
    val data: StateFlow<AnalyticsDto?> = _data.asStateFlow()

    private val _balances = MutableStateFlow(BalancesDto())
    val balances: StateFlow<BalancesDto> = _balances.asStateFlow()

    private val _insights = MutableStateFlow<List<InsightDto>>(emptyList())
    val insights: StateFlow<List<InsightDto>> = _insights.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init { refresh() }

    fun refresh() {
        _loading.value = true
        viewModelScope.launch {
            analyticsRepo.analytics().onSuccess { _data.value = it }
            splitRepo.balances().onSuccess { _balances.value = it }
            analyticsRepo.insights().onSuccess { _insights.value = it }
            _loading.value = false
        }
    }
}
