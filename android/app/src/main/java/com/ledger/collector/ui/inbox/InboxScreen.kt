package com.ledger.collector.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledger.collector.data.local.TransactionEntity
import com.ledger.collector.ui.components.CATEGORIES
import com.ledger.collector.ui.components.SourceBadge
import com.ledger.collector.ui.components.formatAmount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(vm: InboxViewModel, modifier: Modifier = Modifier) {
    val items by vm.items.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val firstLoadDone by vm.firstLoadDone.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Uncategorized", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${items.size} to review", style = MaterialTheme.typography.labelMedium)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            when {
                !firstLoadDone && items.isEmpty() -> items(3) { SkeletonCard() }
                items.isEmpty() -> item { EmptyState() }
                else -> items(items, key = { it.id }) { txn ->
                    InboxCard(
                        txn = txn,
                        onCategory = { vm.categorize(txn.id, it) },
                        onSkip = { vm.skip(txn.id) },
                        onDelete = { vm.delete(txn.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxCard(
    txn: TransactionEntity,
    onCategory: (String) -> Unit,
    onSkip: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCredit = txn.direction.equals("credit", ignoreCase = true)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SourceBadge(txn.source)
                        Text(txn.txnDate, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        txn.merchantRaw?.takeIf { it.isNotBlank() } ?: "Unknown merchant",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    txn.paymentMethod?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    (if (isCredit) "+" else "") + formatAmount(txn.amount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isCredit) Color(0xFF047857) else MaterialTheme.colorScheme.onSurface,
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CATEGORIES.forEach { cat ->
                    FilterChip(
                        selected = txn.category == cat,
                        onClick = { onCategory(cat) },
                        label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSkip) { Text("Skip") }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
        Text("Nothing to review 🎉", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SkeletonCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .fillMaxWidth(if (i == 0) 0.5f else if (i == 1) 0.8f else 0.35f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}
