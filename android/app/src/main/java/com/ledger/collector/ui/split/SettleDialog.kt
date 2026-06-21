package com.ledger.collector.ui.split

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ledger.collector.ui.components.formatAmount
import kotlin.math.abs

/**
 * Settle-up dialog. [net] > 0 means the person owes you (they pay you); [net] < 0 means you
 * owe them (you pay them). The amount is pre-filled to clear the balance.
 */
@Composable
fun SettleDialog(
    personName: String,
    net: Double,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, youPay: Boolean) -> Unit,
) {
    val youPay = net < 0
    var amount by remember { mutableStateOf(trim2(abs(net))) }
    val value = amount.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settle up with $personName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (youPay) "You pay $personName ${formatAmount(abs(net))}."
                    else "$personName pays you ${formatAmount(abs(net))}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { value?.let { onConfirm(it, youPay) } },
                enabled = value != null && value > 0,
            ) { Text("Mark settled") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun trim2(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
