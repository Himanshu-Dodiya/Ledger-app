package com.ledger.collector.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledger.collector.data.local.PersonWithTags
import com.ledger.collector.data.remote.TimelineDto
import com.ledger.collector.ui.components.formatAmount
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailSheet(
    person: PersonWithTags,
    timeline: TimelineDto?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSettle: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(person.person.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            (person.person.upiId ?: person.person.phone)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (person.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { person.tags.forEach { TagDot(it) } }
            }

            val net = timeline?.net ?: 0.0
            if (net != 0.0) {
                val owesYou = net > 0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        (if (owesYou) "Owes you " else "You owe ") + formatAmount(abs(net)),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (owesYou) Color(0xFF047857) else MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { onSettle(net) }) { Text("Settle up") }
                }
            }
            timeline?.let {
                Text("Total shared: ${formatAmount(it.totalShared)}",
                    style = MaterialTheme.typography.bodyMedium)
            }

            if (timeline == null) {
                Text("Loading…", style = MaterialTheme.typography.bodySmall)
            }

            timeline?.sharedExpenses?.takeIf { it.isNotEmpty() }?.let { expenses ->
                HorizontalDivider()
                Text("Shared expenses", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    expenses.forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(e.merchant?.ifBlank { null } ?: "Expense",
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(e.txnDate, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("share " + formatAmount(e.theirShare),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            timeline?.settlements?.takeIf { it.isNotEmpty() }?.let { settlements ->
                HorizontalDivider()
                Text("Settlements", style = MaterialTheme.typography.titleSmall)
                settlements.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${s.fromName} → ${s.toName}", style = MaterialTheme.typography.bodySmall)
                        Text(formatAmount(s.amount), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit") }
            }
        }
    }
}
