package com.ledger.collector.ui.qr

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledger.collector.data.repository.SplitRepository
import com.ledger.collector.domain.upi.UpiUri
import com.ledger.collector.ui.components.CATEGORIES
import com.ledger.collector.ui.components.formatAmount

private const val YOU = "__you__"
private const val UPI_TAG = "UpiPay"

/**
 * Payment preview shown after a UPI QR is scanned: confirm amount, pick a category, optionally
 * split equally, then hand off to a UPI app. The transaction is recorded optimistically on
 * hand-off (no reliable UPI callback exists). Advanced split methods can be applied later from
 * the transaction's split editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentPreviewSheet(vm: QrPayViewModel, upi: UpiUri, onClose: () -> Unit) {
    val context = LocalContext.current
    val people by vm.people.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var amount by remember { mutableStateOf(upi.amount?.let { trim2(it) } ?: "") }
    var category by remember { mutableStateOf("Uncategorized") }
    var splitOn by remember { mutableStateOf(false) }
    val included = remember { mutableListOf(YOU).toMutableStateList() }

    val amountValue = amount.toDoubleOrNull()

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pay", style = MaterialTheme.typography.labelMedium)
            Text(upi.payeeName ?: upi.payeeVpa, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text(upi.payeeVpa, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Amount (₹)") },
                singleLine = true,
                // When the QR already pins an amount (dynamic/merchant QR), it's part of the
                // signed request — don't let the user change it.
                readOnly = upi.hasFixedAmount,
                enabled = !upi.hasFixedAmount,
                supportingText = if (upi.hasFixedAmount) {
                    { Text("Amount set by the QR code") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Category", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORIES.forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Split this payment", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = splitOn, onCheckedChange = { splitOn = it })
            }

            if (splitOn) {
                if (tags.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { tag ->
                            AssistChip(
                                onClick = {
                                    people.filter { p -> p.tags.any { it.id == tag.id } }.forEach {
                                        if (it.person.id !in included) included.add(it.person.id)
                                    }
                                },
                                label = { Text(tag.name) },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = true, onClick = {}, label = { Text("You") })
                    people.forEach { p ->
                        FilterChip(
                            selected = p.person.id in included,
                            onClick = {
                                if (p.person.id in included) included.remove(p.person.id) else included.add(p.person.id)
                            },
                            label = { Text(p.person.name) },
                        )
                    }
                }
                amountValue?.let { amt ->
                    val n = included.size.coerceAtLeast(1)
                    Text("Equal split: ${formatAmount(amt / n)} each (${n} people)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(
                onClick = {
                    val payUri = upi.toIntentUri(amountValue, null)
                    // TEMPORARY debug logging — compare these against a manual Google Pay payment.
                    Log.d(UPI_TAG, "scanned raw QR   : ${upi.raw}")
                    Log.d(UPI_TAG, "all QR params    : ${upi.params}")
                    Log.d(UPI_TAG, "launch URI       : $payUri")
                    Log.d(UPI_TAG, "pa=${upi.payeeVpa} pn=${upi.payeeName} am=$amountValue " +
                        "cu=${upi.currency} tr=${upi.txnRef} mc=${upi.params["mc"]} " +
                        "mode=${upi.params["mode"]} orgid=${upi.params["orgid"]} " +
                        "sign=${if (upi.params["sign"] != null) "present" else "none"}")

                    val launched = try {
                        val intent = Intent(Intent.ACTION_VIEW, payUri)
                        context.startActivity(Intent.createChooser(intent, "Pay with"))
                        true
                    } catch (e: ActivityNotFoundException) {
                        Log.w(UPI_TAG, "no UPI app available", e)
                        false
                    }
                    val participants = if (splitOn && included.size >= 2) {
                        included.map { SplitRepository.Participant(it.takeIf { id -> id != YOU }, null) }
                    } else emptyList()
                    if (launched) {
                        vm.recordPayment(
                            upi = upi,
                            amount = amountValue ?: 0.0,
                            category = category,
                            method = "equal",
                            payerPersonId = null, // you pay
                            participants = participants,
                            onDone = { onClose() },
                        )
                    } else {
                        // Don't record a payment we never launched, and tell the user why.
                        vm.setLaunchError("No UPI app (Google Pay / PhonePe…) is installed to pay with.")
                    }
                },
                enabled = amountValue != null && amountValue > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pay with UPI app") }
        }
    }
}

private fun trim2(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
