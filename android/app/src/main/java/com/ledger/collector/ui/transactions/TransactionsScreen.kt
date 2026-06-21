package com.ledger.collector.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun TransactionsScreen(
    vm: TransactionsViewModel,
    modifier: Modifier = Modifier,
    onRowClick: (TransactionEntity) -> Unit = {},
) {
    val allItems by vm.items.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }

    val sources = remember(allItems) { allItems.map { it.source }.distinct().sorted() }
    val categories = remember(allItems) { allItems.map { it.category }.distinct().sorted() }
    val items = remember(allItems, query, source, category) {
        allItems.filter { t ->
            (source == null || t.source == source) &&
                (category == null || t.category == category) &&
                (query.isBlank() || t.merchantRaw?.contains(query, ignoreCase = true) == true)
        }
    }

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
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search merchant") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sources.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(source == null, { source = null }, label = { Text("All sources") })
                        sources.forEach { s ->
                            FilterChip(source == s, { source = if (source == s) null else s }, label = { Text(s) })
                        }
                    }
                }
                if (categories.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(category == null, { category = null }, label = { Text("All") })
                        categories.forEach { c ->
                            FilterChip(category == c, { category = if (category == c) null else c }, label = { Text(c) })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
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
                            SwipeToDeleteRow(txn = txn, onDelete = { vm.delete(txn.id) }, onClick = { onRowClick(txn) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(txn: TransactionEntity, onDelete: () -> Unit, onClick: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
        }
    )
    // Only tint the background while the row is actively being swiped, so the History
    // list never shows a resting red panel behind cards.
    val swiping = state.targetValue == SwipeToDismissBoxValue.EndToStart ||
        state.dismissDirection == SwipeToDismissBoxValue.EndToStart
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (swiping) MaterialTheme.colorScheme.errorContainer else Color.Transparent
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (swiping) {
                    Row(
                        Modifier.padding(end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        },
    ) {
        TxnRow(txn, onClick)
    }
}

@Composable
private fun TxnRow(txn: TransactionEntity, onClick: () -> Unit) {
    val isCredit = txn.direction.equals("credit", ignoreCase = true)
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick),
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
                        if (txn.isSplit) SourceBadge("split")
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
