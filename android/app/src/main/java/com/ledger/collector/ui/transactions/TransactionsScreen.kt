package com.ledger.collector.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledger.collector.data.local.TransactionEntity
import com.ledger.collector.ui.components.SourceBadge
import com.ledger.collector.ui.components.formatAmount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(vm: TransactionsViewModel, modifier: Modifier = Modifier) {
    val items by vm.items.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Transactions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${items.size}", style = MaterialTheme.typography.labelMedium)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
            }

            when {
                items.isEmpty() && !refreshing -> item { EmptyState() }
                items.isEmpty() -> item { SkeletonList() }
                else -> {
                    // Group by month for a timeline feel
                    val grouped = items.groupBy { it.txnDate.take(7) } // YYYY-MM
                    grouped.forEach { (month, txns) ->
                        item(key = "hdr-$month") {
                            Text(
                                formatMonth(month),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(txns, key = { it.id }) { txn ->
                            SwipeToDeleteRow(txn = txn, onDelete = { vm.delete(txn.id) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(txn: TransactionEntity, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Delete", Modifier.padding(end = 16.dp), color = MaterialTheme.colorScheme.error)
            }
        },
    ) {
        TxnRow(txn)
    }
}

@Composable
private fun TxnRow(txn: TransactionEntity) {
    val isCredit = txn.direction.equals("credit", ignoreCase = true)
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SourceBadge(txn.source)
                        Text(txn.txnDate, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        txn.merchantRaw?.takeIf { it.isNotBlank() } ?: "Unknown merchant",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        txn.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    (if (isCredit) "+" else "") + formatAmount(txn.amount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCredit) Color(0xFF047857) else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalDivider()
    }
}

private fun formatMonth(ym: String): String {
    // "2025-06" → "June 2025"
    val months = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )
    val parts = ym.split("-")
    if (parts.size != 2) return ym
    val m = parts[1].toIntOrNull() ?: return ym
    return "${months.getOrElse(m - 1) { ym }}, ${parts[0]}"
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
        Text("No categorized transactions yet.\nCategorize some from the Uncategorized tab.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SkeletonList() {
    repeat(5) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
