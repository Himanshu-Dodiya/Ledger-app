package com.ledger.collector.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledger.collector.data.local.PersonWithTags
import com.ledger.collector.data.local.TagEntity
import com.ledger.collector.ui.components.formatAmount
import com.ledger.collector.ui.split.SettleDialog
import com.ledger.collector.ui.split.SplitViewModel

@Composable
fun PeopleScreen(vm: PeopleViewModel, split: SplitViewModel, modifier: Modifier = Modifier) {
    val people by vm.people.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tagFilter by vm.tagFilter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val balances by split.balances.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    val netByPerson = remember(balances) { balances.balances.associate { it.personId to it.net } }

    var editing by remember { mutableStateOf<PersonWithTags?>(null) }
    var detail by remember { mutableStateOf<PersonWithTags?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf<Pair<String, Double>?>(null) } // personId, net

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { addingNew = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add person")
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("People", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showTags = true }) {
                    Icon(Icons.Filled.Label, contentDescription = "Manage tags")
                }
            }

            if (balances.theyOweMe > 0.0 || balances.iOwe > 0.0) {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("You are owed", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatAmount(balances.theyOweMe), style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF047857), fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("You owe", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatAmount(balances.iOwe), style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search name or UPI") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (tags.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = tagFilter == null,
                        onClick = { vm.setTagFilter(null) },
                        label = { Text("All") },
                    )
                    tags.forEach { tag ->
                        FilterChip(
                            selected = tagFilter == tag.id,
                            onClick = { vm.setTagFilter(if (tagFilter == tag.id) null else tag.id) },
                            label = { Text(tag.name) },
                        )
                    }
                }
            }

            if (people.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No people yet. Tap + to add someone you split or settle with.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(people, key = { it.person.id }) { pwt ->
                        PersonCard(
                            pwt,
                            net = netByPerson[pwt.person.id],
                            onClick = { detail = pwt; vm.loadTimeline(pwt.person.id) },
                            onSettle = { net -> settling = pwt.person.id to net },
                        )
                    }
                }
            }
        }
    }

    if (addingNew) {
        PersonEditDialog(
            existing = null,
            allTags = tags,
            onDismiss = { addingNew = false },
            onSave = { name, phone, upi, tagIds ->
                vm.savePerson(null, name, phone, upi, tagIds); addingNew = false
            },
            onDelete = null,
            onCreateTag = { name, color -> vm.addTag(name, color) },
        )
    }
    editing?.let { pwt ->
        PersonEditDialog(
            existing = pwt,
            allTags = tags,
            onDismiss = { editing = null },
            onSave = { name, phone, upi, tagIds ->
                vm.savePerson(pwt.person.id, name, phone, upi, tagIds); editing = null
            },
            onDelete = { vm.deletePerson(pwt.person.id); editing = null },
            onCreateTag = { name, color -> vm.addTag(name, color) },
        )
    }
    if (showTags) {
        TagManagerDialog(
            tags = tags,
            onDismiss = { showTags = false },
            onSave = { id, name, color -> vm.saveTag(id, name, color) },
            onDelete = { vm.deleteTag(it) },
        )
    }
    detail?.let { pwt ->
        PersonDetailSheet(
            person = pwt,
            timeline = timeline,
            onEdit = { editing = pwt; detail = null; vm.clearTimeline() },
            onDelete = { vm.deletePerson(pwt.person.id); detail = null; vm.clearTimeline() },
            onSettle = { net -> settling = pwt.person.id to net; detail = null; vm.clearTimeline() },
            onDismiss = { detail = null; vm.clearTimeline() },
        )
    }
    settling?.let { (personId, net) ->
        val person = people.firstOrNull { it.person.id == personId }
        SettleDialog(
            personName = person?.person?.name ?: "Person",
            net = net,
            onDismiss = { settling = null },
            onConfirm = { amount, youPay ->
                split.settle(personId, amount, youPay)
                settling = null
            },
        )
    }
}

@Composable
private fun PersonCard(
    pwt: PersonWithTags,
    net: Double?,
    onClick: () -> Unit,
    onSettle: (Double) -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(pwt.person.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pwt.person.name, style = MaterialTheme.typography.titleSmall)
                val sub = pwt.person.upiId ?: pwt.person.phone
                if (sub != null) {
                    Text(sub, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pwt.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pwt.tags.take(4).forEach { TagDot(it) }
                    }
                }
            }
            if (net != null && net != 0.0) {
                Column(horizontalAlignment = Alignment.End) {
                    val owesYou = net > 0
                    Text(
                        (if (owesYou) "owes you " else "you owe ") + formatAmount(kotlin.math.abs(net)),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (owesYou) Color(0xFF047857) else MaterialTheme.colorScheme.error,
                    )
                    androidx.compose.material3.TextButton(onClick = { onSettle(net) }) { Text("Settle up") }
                }
            }
        }
    }
}

@Composable
private fun Avatar(name: String) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Text(initial, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun TagDot(tag: TagEntity) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(parseColor(tag.color), CircleShape))
        Text(tag.name, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Parse "#RRGGBB" → Compose Color, falling back to indigo. */
fun parseColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF6366F1))
