package com.ledger.collector.ui.split

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledger.collector.data.local.TransactionEntity
import com.ledger.collector.data.repository.SplitRepository
import com.ledger.collector.ui.components.formatAmount
import kotlin.math.round

private const val YOU = "__you__"
private val METHODS = listOf("equal" to "Equal", "percent" to "%", "exact" to "Exact", "shares" to "Shares")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(vm: SplitViewModel, txn: TransactionEntity, onDismiss: () -> Unit) {
    val people by vm.people.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val existing by vm.existingSplit.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Identity helpers: YOU sentinel ↔ null person id.
    val roster = remember(people) { listOf(YOU to "You") + people.map { it.person.id to it.person.name } }

    // Editable state, seeded from any existing split. A zero-share payer row (the "I paid for
    // someone else" case) is the payer, not a sharer, so it's excluded from the participant set.
    val included: SnapshotStateList<String> = remember(existing, people) {
        if (existing.isNotEmpty())
            existing.filterNot { it.isPayer && it.shareAmount == 0.0 }
                .map { it.personId ?: YOU }.toMutableStateList()
        else mutableListOf(YOU).toMutableStateList()
    }
    var method by remember(existing) { mutableStateOf(existing.firstOrNull()?.shareType ?: "equal") }
    var payer by remember(existing) { mutableStateOf(existing.firstOrNull { it.isPayer }?.let { it.personId ?: YOU } ?: YOU) }
    val values = remember(existing) {
        mutableStateMapOf<String, String>().apply {
            existing.forEach { row -> row.shareValue?.let { put(row.personId ?: YOU, trimNum(it)) } }
        }
    }

    val total = txn.amount
    val preview = computeShares(method, total, included.toList(), values)
    val previewSum = round2(preview.values.sum())

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(txn.merchantRaw?.ifBlank { null } ?: "Transaction",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(formatAmount(total), style = MaterialTheme.typography.headlineSmall)

            // Method
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                METHODS.forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = method == key,
                        onClick = { method = key },
                        shape = SegmentedButtonDefaults.itemShape(i, METHODS.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            // Select by group — adds every member of the group in one tap; the list stays
            // editable afterwards so individuals can be removed.
            if (groups.isNotEmpty()) {
                Text("Quick add by group", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { group ->
                        AssistChip(
                            onClick = {
                                group.members.forEach { m ->
                                    if (m.id !in included) included.add(m.id)
                                }
                            },
                            label = { Text(group.name) },
                        )
                    }
                }
            }

            // Select by tag — adds all people carrying that tag.
            if (tags.isNotEmpty()) {
                Text("Quick add by tag", style = MaterialTheme.typography.labelMedium)
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

            Text("Participants", style = MaterialTheme.typography.labelMedium)
            roster.forEach { (id, name) ->
                ParticipantRow(
                    id = id, name = name,
                    checked = id in included,
                    isPayer = payer == id,
                    method = method,
                    value = values[id] ?: "",
                    owed = preview[id],
                    onToggle = {
                        if (id in included) { included.remove(id); if (payer == id) payer = YOU }
                        else included.add(id)
                    },
                    onPayer = { payer = id },
                    onValue = { values[id] = it },
                )
            }

            HorizontalDivider()
            val mismatch = (method == "percent" || method == "exact") &&
                included.isNotEmpty() && kotlin.math.abs(previewSum - total) > 0.05
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Allocated", style = MaterialTheme.typography.bodyMedium)
                Text(formatAmount(previewSum) + " / " + formatAmount(total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            // Live remaining for the custom-amount methods so the user can always see what's left
            // to allocate and is prevented from saving an invalid total.
            if (method == "exact" || method == "percent") {
                val remaining = round2(total - previewSum)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Remaining", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatAmount(remaining),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (kotlin.math.abs(remaining) > 0.005) MaterialTheme.colorScheme.error
                        else Color(0xFF047857))
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing.isNotEmpty()) {
                    OutlinedButton(onClick = { vm.clearSplit(txn.id) }, modifier = Modifier.weight(1f)) {
                        Text("Remove split")
                    }
                }
                // Valid when there are at least two distinct parties: either ≥2 sharers, or
                // ≥1 sharer plus a payer who isn't sharing (paying for someone else).
                val payerExcluded = payer !in included
                val canSave = !mismatch && included.isNotEmpty() && (included.size >= 2 || payerExcluded)
                TextButton(
                    onClick = {
                        val parts = included.map { id ->
                            SplitRepository.Participant(
                                personId = id.takeIf { it != YOU },
                                value = values[id]?.toDoubleOrNull(),
                            )
                        }
                        vm.saveSplit(txn.id, method, payer.takeIf { it != YOU }, parts)
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                ) { Text("Save split") }
            }
        }
    }
}

@Composable
private fun ParticipantRow(
    id: String,
    name: String,
    checked: Boolean,
    isPayer: Boolean,
    method: String,
    value: String,
    owed: Double?,
    onToggle: () -> Unit,
    onPayer: () -> Unit,
    onValue: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (checked && owed != null) {
                Text("owes " + formatAmount(owed), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (checked && (method == "percent" || method == "exact" || method == "shares")) {
            OutlinedTextField(
                value = value,
                onValueChange = { onValue(it.filter { c -> c.isDigit() || c == '.' }) },
                modifier = Modifier.width(84.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                label = { Text(if (method == "percent") "%" else if (method == "shares") "sh" else "₹") },
            )
        }
        if (checked) {
            FilterChip(selected = isPayer, onClick = onPayer, label = { Text("Payer") })
        }
    }
}

/**
 * Mirror the backend share resolution for a live preview. Computed in integer paise with the
 * largest-remainder method so the preview matches the server exactly and always sums to total
 * (e.g. ₹100 ÷ 3 → 33.33, 33.33, 33.34). Keep this in lockstep with splits.resolveShares.
 */
private fun computeShares(method: String, total: Double, ids: List<String>, values: Map<String, String>): Map<String, Double> {
    if (ids.isEmpty()) return emptyMap()
    val totalP = toPaise(total)
    val paise: LongArray = when (method) {
        "exact" -> LongArray(ids.size) { toPaise(values[ids[it]]?.toDoubleOrNull() ?: 0.0) }
        "percent" -> distributeWeighted(totalP, ids.map { values[it]?.toDoubleOrNull() ?: 0.0 })
        "shares" -> distributeWeighted(totalP, ids.map { values[it]?.toDoubleOrNull() ?: 0.0 })
        else -> distributeEqual(totalP, ids.size) // equal
    }
    return ids.indices.associate { ids[it] to paise[it] / 100.0 }
}

private fun toPaise(rupees: Double): Long = Math.round(rupees * 100)

/** Split totalP paise into n near-equal parts; leftover paise go to the last participants. */
private fun distributeEqual(totalP: Long, n: Int): LongArray {
    if (n <= 0) return LongArray(0)
    val base = totalP / n
    val rem = (totalP - base * n).toInt() // 0..n-1
    val out = LongArray(n) { base }
    for (i in 0 until rem) out[n - 1 - i]++
    return out
}

/** Allocate totalP paise proportional to weights via largest-remainder; sums to exactly totalP. */
private fun distributeWeighted(totalP: Long, weights: List<Double>): LongArray {
    val n = weights.size
    val out = LongArray(n)
    val wsum = weights.sum()
    if (wsum == 0.0) return distributeEqual(totalP, n)
    val fracs = ArrayList<Pair<Int, Double>>(n)
    var allocated = 0L
    for (i in 0 until n) {
        val exact = totalP * weights[i] / wsum
        val floor = kotlin.math.floor(exact).toLong()
        out[i] = floor
        allocated += floor
        fracs.add(i to (exact - floor))
    }
    val rem = (totalP - allocated).toInt()
    fracs.sortByDescending { it.second }
    for (i in 0 until rem) if (i < n) out[fracs[i].first]++
    return out
}

private fun round2(v: Double): Double = round(v * 100) / 100
private fun trimNum(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
