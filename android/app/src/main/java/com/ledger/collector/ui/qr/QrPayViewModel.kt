package com.ledger.collector.ui.qr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.collector.data.local.PersonWithTags
import com.ledger.collector.data.local.TagEntity
import com.ledger.collector.data.repository.PeopleRepository
import com.ledger.collector.data.repository.SplitRepository
import com.ledger.collector.data.repository.TransactionRepository
import com.ledger.collector.domain.upi.UpiUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the Scan → preview → split → pay flow. Because UPI apps don't reliably report back to
 * a non-PSP caller, [recordPayment] creates the transaction optimistically when the user hands
 * off to their UPI app; a later SMS/statement with the same ref dedupes against it.
 */
class QrPayViewModel(
    private val transactions: TransactionRepository,
    private val splits: SplitRepository,
    peopleRepo: PeopleRepository,
) : ViewModel() {

    val people: StateFlow<List<PersonWithTags>> =
        peopleRepo.people.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags: StateFlow<List<TagEntity>> =
        peopleRepo.tags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _scanned = MutableStateFlow<UpiUri?>(null)
    val scanned: StateFlow<UpiUri?> = _scanned.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onScanned(upi: UpiUri) { if (_scanned.value == null) _scanned.value = upi }

    fun reset() { _scanned.value = null }

    /**
     * Record the (optimistic) payment and any split. [participants] empty/size<2 → no split.
     * Calls [onDone] once the transaction (and split) are persisted.
     */
    fun recordPayment(
        upi: UpiUri,
        amount: Double,
        category: String,
        method: String,
        payerPersonId: String?,
        participants: List<SplitRepository.Participant>,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val merchant = upi.payeeName ?: upi.payeeVpa
            val result = transactions.create(
                amount = amount,
                direction = "debit",
                merchant = merchant,
                category = category,
                txnDate = today,
                source = "qr",
                referenceId = upi.txnRef,
            )
            result.onSuccess { id ->
                if (id != null && participants.size >= 2) {
                    splits.saveSplit(id, method, payerPersonId, participants)
                }
                _message.value = if (id == null) "Already recorded." else "Payment recorded."
                onDone()
            }.onFailure { e ->
                // Surface the real backend/network error rather than a misleading generic message.
                _message.value = "Couldn't record payment: ${e.message ?: "unknown error"}"
                onDone()
            }
        }
    }

    /** Show a message without recording a payment (e.g. no UPI app to hand off to). */
    fun setLaunchError(msg: String) { _message.value = msg }

    fun clearMessage() { _message.value = null }
}
