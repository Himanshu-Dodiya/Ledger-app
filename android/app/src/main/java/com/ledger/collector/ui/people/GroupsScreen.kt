package com.ledger.collector.ui.people

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledger.collector.data.local.PersonWithTags
import com.ledger.collector.data.remote.GroupDto

private val GROUP_TYPES = listOf("trip", "flat", "office", "family", "other")

@Composable
fun GroupsScreen(vm: PeopleViewModel, modifier: Modifier = Modifier) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<GroupDto?>(null) }
    var addingNew by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { addingNew = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add group")
            }
        },
    ) { padding ->
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No groups yet. Tap + to create one (Goa Trip, Flat Expenses…).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = { it.id }) { g -> GroupCard(g, onClick = { editing = g }) }
            }
        }
    }

    if (addingNew) {
        GroupEditDialog(null, people,
            onDismiss = { addingNew = false },
            onSave = { _, name, type, ids -> vm.saveGroup(null, name, type, ids); addingNew = false },
            onDelete = null)
    }
    editing?.let { g ->
        GroupEditDialog(g, people,
            onDismiss = { editing = null },
            onSave = { id, name, type, ids -> vm.saveGroup(id, name, type, ids); editing = null },
            onDelete = { vm.deleteGroup(g.id); editing = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(g: GroupDto, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(g.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                g.type?.let { Text(it.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary) }
            }
            if (g.members.isEmpty()) {
                Text("No members", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    g.members.forEach { Text(it.name, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun GroupEditDialog(
    existing: GroupDto?,
    allPeople: List<PersonWithTags>,
    onDismiss: () -> Unit,
    onSave: (id: String?, name: String, type: String?, memberIds: List<String>) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: "trip") }
    val members = remember {
        existing?.members?.map { it.id }?.toMutableStateList() ?: mutableListOf<String>().toMutableStateList()
    }
    var search by remember { mutableStateOf("") }

    // Searchable, scrollable member list scales to large rosters (faster than a flat chip wall).
    val filtered = remember(allPeople, search) {
        if (search.isBlank()) allPeople
        else allPeople.filter {
            it.person.name.contains(search, ignoreCase = true) ||
                it.person.upiId?.contains(search, ignoreCase = true) == true
        }
    }
    val allSelected = allPeople.isNotEmpty() && members.size == allPeople.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New group" else "Edit group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GROUP_TYPES.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t },
                            label = { Text(t.replaceFirstChar(Char::uppercase)) })
                    }
                }
                if (allPeople.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Members (${members.size}/${allPeople.size})",
                            style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = {
                            if (allSelected) members.clear()
                            else { members.clear(); members.addAll(allPeople.map { it.person.id }) }
                        }) { Text(if (allSelected) "Deselect all" else "Select all") }
                    }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search people") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filtered, key = { it.person.id }) { p ->
                            val checked = p.person.id in members
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        if (checked) members.remove(p.person.id) else members.add(p.person.id)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    if (checked) members.remove(p.person.id) else members.add(p.person.id)
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(p.person.name, style = MaterialTheme.typography.bodyMedium)
                                    val sub = p.person.upiId ?: p.person.phone
                                    if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(existing?.id, name.trim(), type, members.toList()) },
                enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
