package com.ledger.collector.ui.imports

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.collector.data.repository.ImportRepository
import com.ledger.collector.domain.imports.pdf.ImportResult
import com.ledger.collector.domain.imports.pdf.ParsedRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the Import Center. Owns the new import paths (PDF statements, manual entry); the
 * SMS/Gmail sync actions reuse the existing flows and are passed in as callbacks by the host.
 */
class ImportViewModel(
    private val importRepository: ImportRepository,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Last user-facing message (success summary or error). Cleared when a new action starts. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Structured result of the most recent PDF import, for a richer success card. */
    private val _lastResult = MutableStateFlow<ImportResult?>(null)
    val lastResult: StateFlow<ImportResult?> = _lastResult.asStateFlow()

    fun importPdf(uri: Uri, fileName: String) {
        if (_busy.value) return
        _busy.value = true
        _message.value = "Reading $fileName…"
        _lastResult.value = null
        viewModelScope.launch {
            importRepository.importPdf(uri, fileName)
                .onSuccess { r ->
                    _lastResult.value = r
                    _message.value = when {
                        r.parsed == 0 -> "Couldn't find any transactions in this file."
                        else -> "Imported ${r.inserted} new · ${r.duplicates} already had · " +
                            "${r.parsed} found in ${r.fileName}"
                    }
                }
                .onFailure { _message.value = it.message ?: "Import failed." }
            _busy.value = false
        }
    }

    fun createManual(
        amount: Double,
        direction: ParsedRow.Direction,
        merchant: String,
        category: String,
        txnDate: String,
    ) {
        if (_busy.value) return
        _busy.value = true
        _message.value = "Saving…"
        viewModelScope.launch {
            importRepository.createManual(amount, direction, merchant, category, txnDate)
                .onSuccess { created ->
                    _message.value = if (created) "Transaction added." else "Looks like a duplicate — not added."
                }
                .onFailure { _message.value = it.message ?: "Couldn't save." }
            _busy.value = false
        }
    }

    fun setMessage(msg: String?) { _message.value = msg }
}
