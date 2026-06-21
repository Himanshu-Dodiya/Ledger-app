package com.ledger.collector.ui.split

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.collector.data.local.PersonWithTags
import com.ledger.collector.data.local.TagEntity
import com.ledger.collector.data.local.TransactionEntity
import com.ledger.collector.data.remote.BalancesDto
import com.ledger.collector.data.remote.GroupDto
import com.ledger.collector.data.remote.SettlementDto
import com.ledger.collector.data.remote.SplitRowDto
import com.ledger.collector.data.repository.GroupsRepository
import com.ledger.collector.data.repository.PeopleRepository
import com.ledger.collector.data.repository.SplitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the split editor (opened on a transaction) plus the balances/settlements surfaces.
 * People come from [PeopleRepository]'s Room cache; split + balance reads/writes go through
 * [SplitRepository]. Shared across the Activity (split sheet) and People (balances) tabs.
 */
class SplitViewModel(
    private val splitRepo: SplitRepository,
    peopleRepo: PeopleRepository,
    private val groupsRepo: GroupsRepository,
) : ViewModel() {

    val people: StateFlow<List<PersonWithTags>> =
        peopleRepo.people.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags: StateFlow<List<TagEntity>> =
        peopleRepo.tags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Groups, so a split can be seeded from a whole group in one tap. */
    private val _groups = MutableStateFlow<List<GroupDto>>(emptyList())
    val groups: StateFlow<List<GroupDto>> = _groups.asStateFlow()

    private val _selectedTxn = MutableStateFlow<TransactionEntity?>(null)
    val selectedTxn: StateFlow<TransactionEntity?> = _selectedTxn.asStateFlow()

    private val _existingSplit = MutableStateFlow<List<SplitRowDto>>(emptyList())
    val existingSplit: StateFlow<List<SplitRowDto>> = _existingSplit.asStateFlow()

    private val _balances = MutableStateFlow(BalancesDto())
    val balances: StateFlow<BalancesDto> = _balances.asStateFlow()

    private val _settlements = MutableStateFlow<List<SettlementDto>>(emptyList())
    val settlements: StateFlow<List<SettlementDto>> = _settlements.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshBalances()
        viewModelScope.launch { groupsRepo.list().onSuccess { _groups.value = it } }
    }

    fun openTxn(txn: TransactionEntity) {
        _selectedTxn.value = txn
        _existingSplit.value = emptyList()
        viewModelScope.launch {
            splitRepo.getSplit(txn.id).onSuccess { _existingSplit.value = it }
        }
    }

    fun closeTxn() { _selectedTxn.value = null }

    fun saveSplit(
        txnId: String,
        method: String,
        payerPersonId: String?,
        participants: List<SplitRepository.Participant>,
    ) {
        viewModelScope.launch {
            splitRepo.saveSplit(txnId, method, payerPersonId, participants)
                .onSuccess { _message.value = "Split saved."; refreshBalances(); closeTxn() }
                .onFailure { _message.value = "Couldn't save split." }
        }
    }

    fun clearSplit(txnId: String) {
        viewModelScope.launch {
            splitRepo.clearSplit(txnId)
                .onSuccess { _message.value = "Split removed."; refreshBalances(); closeTxn() }
                .onFailure { _message.value = "Couldn't remove split." }
        }
    }

    fun refreshBalances() {
        viewModelScope.launch {
            splitRepo.balances().onSuccess { _balances.value = it }
            splitRepo.settlements().onSuccess { _settlements.value = it }
        }
    }

    /** Person [personId] pays you [amount] to clear what they owe (or you pay them if youPay). */
    fun settle(personId: String, amount: Double, youPay: Boolean, upiRef: String? = null) {
        viewModelScope.launch {
            val result = if (youPay) splitRepo.settle(null, personId, amount, upiRef)
            else splitRepo.settle(personId, null, amount, upiRef)
            result
                .onSuccess { _message.value = "Settlement recorded."; refreshBalances() }
                .onFailure { _message.value = "Couldn't record settlement." }
        }
    }

    fun clearMessage() { _message.value = null }
}
