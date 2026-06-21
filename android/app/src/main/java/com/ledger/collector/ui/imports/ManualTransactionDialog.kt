package com.ledger.collector.ui.imports

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.ledger.collector.domain.imports.pdf.ParsedRow
import com.ledger.collector.ui.components.CATEGORIES
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact manual transaction entry. Posts to the same create endpoint as other sources. */
@Composable
fun ManualTransactionDialog(
    onDismiss: () -> Unit,
    onSubmit: (amount: Double, direction: ParsedRow.Direction, merchant: String, category: String, txnDate: String) -> Unit,
) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(today) }
    var isCredit by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("Uncategorized") }

    val amountValue = amount.toDoubleOrNull()
    val canSubmit = amountValue != null && amountValue > 0 && merchant.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isCredit,
                        onClick = { isCredit = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Expense") }
                    SegmentedButton(
                        selected = isCredit,
                        onClick = { isCredit = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Income") }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Amount (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text(if (isCredit) "From" else "Merchant / payee") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(
                        amountValue ?: 0.0,
                        if (isCredit) ParsedRow.Direction.CREDIT else ParsedRow.Direction.DEBIT,
                        merchant.trim(),
                        category,
                        date.trim(),
                    )
                },
                enabled = canSubmit,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
