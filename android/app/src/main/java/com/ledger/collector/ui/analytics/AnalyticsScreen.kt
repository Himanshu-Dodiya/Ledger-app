package com.ledger.collector.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledger.collector.data.remote.BucketDto
import com.ledger.collector.data.remote.InsightDto
import com.ledger.collector.data.remote.MonthPointDto
import com.ledger.collector.ui.components.formatAmount

@Composable
fun AnalyticsScreen(vm: AnalyticsViewModel, modifier: Modifier = Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Analytics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        if (insights.isNotEmpty()) {
            item { Text("Insights", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(insights) { InsightCard(it) }
        }

        val d = data
        if (d == null) {
            item { Text("Loading…", style = MaterialTheme.typography.bodyMedium) }
            return@LazyColumn
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Spent", formatAmount(d.totalSpend), MaterialTheme.colorScheme.error, Modifier.weight(1f))
                StatCard("Income", formatAmount(d.totalIncome), Color(0xFF047857), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Savings", formatAmount(d.savings),
                    if (d.savings >= 0) Color(0xFF047857) else MaterialTheme.colorScheme.error, Modifier.weight(1f))
                StatCard("Owed to you", formatAmount(balances.theyOweMe), Color(0xFF047857), Modifier.weight(1f))
            }
        }
        if (balances.iOwe > 0.0) {
            item { StatCard("You owe", formatAmount(balances.iOwe), MaterialTheme.colorScheme.error, Modifier.fillMaxWidth()) }
        }

        if (d.monthlyTrends.isNotEmpty()) {
            item { SectionCard("Monthly trend") { MonthlyTrend(d.monthlyTrends) } }
        }
        bucketSection("Spending by category", d.byCategory)
        bucketSection("By payment method", d.byPaymentMethod)
        bucketSection("By source", d.bySource)
        bucketSection("Top merchants", d.topMerchants)
        bucketSection("Top people you spend with", d.topPeople)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.bucketSection(title: String, buckets: List<BucketDto>) {
    if (buckets.isEmpty()) return
    item {
        SectionCard(title) {
            val max = buckets.maxOf { it.total }.coerceAtLeast(1.0)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                buckets.forEach { BarRow(it.label, it.total, it.total / max) }
            }
        }
    }
}

@Composable
private fun InsightCard(insight: InsightDto) {
    val warn = insight.severity == "warn"
    Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (warn) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(insight.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(insight.body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun BarRow(label: String, value: Double, fraction: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(formatAmount(value), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0.02f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun MonthlyTrend(points: List<MonthPointDto>) {
    val max = points.maxOf { maxOf(it.spend, it.income) }.coerceAtLeast(1.0)
    Row(
        Modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        points.forEach { p ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom) {
                Row(Modifier.height(90.dp), verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Bar((p.spend / max).toFloat(), MaterialTheme.colorScheme.error)
                    Bar((p.income / max).toFloat(), Color(0xFF047857))
                }
                Text(p.month.takeLast(2), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(
        Modifier.width(10.dp).fillMaxHeight(fraction.coerceIn(0.02f, 1f))
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(color),
    )
}
