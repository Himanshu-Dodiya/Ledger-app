package com.ledger.collector.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.ledger.collector.data.local.PersonWithTags
import com.ledger.collector.data.local.TagEntity
import kotlinx.coroutines.launch

private val TAG_COLORS = listOf(
    "#6366F1", "#EF4444", "#F59E0B", "#10B981", "#3B82F6",
    "#8B5CF6", "#EC4899", "#14B8A6", "#F97316", "#64748B",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonEditDialog(
    existing: PersonWithTags?,
    allTags: List<TagEntity>,
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String?, upi: String?, tagIds: List<String>) -> Unit,
    onDelete: (() -> Unit)?,
    // Create a tag inline and return it so it can be selected immediately. Null on failure.
    onCreateTag: (suspend (name: String, color: String) -> TagEntity?)? = null,
) {
    var name by remember { mutableStateOf(existing?.person?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.person?.phone ?: "") }
    var upi by remember { mutableStateOf(existing?.person?.upiId ?: "") }
    val selected = remember { existing?.tags?.map { it.id }?.toMutableStateList() ?: mutableListOf<String>().toMutableStateList() }
    var newTag by remember { mutableStateOf("") }
    var creatingTag by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Commit the typed tag name as a real tag and select it. Reused by the inline "+" and Save,
    // so a tag the user typed but didn't explicitly add is never silently dropped.
    fun commitNewTag(after: () -> Unit = {}) {
        val n = newTag.trim()
        if (n.isBlank() || onCreateTag == null) { after(); return }
        creatingTag = true
        scope.launch {
            val tag = onCreateTag(n, TAG_COLORS[(allTags.size) % TAG_COLORS.size])
            if (tag != null && tag.id !in selected) selected.add(tag.id)
            newTag = ""
            creatingTag = false
            after()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add person" else "Edit person") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(upi, { upi = it }, label = { Text("UPI ID (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())

                Text("Tags", style = MaterialTheme.typography.labelMedium)
                if (allTags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        allTags.forEach { tag ->
                            FilterChip(
                                selected = tag.id in selected,
                                onClick = { if (tag.id in selected) selected.remove(tag.id) else selected.add(tag.id) },
                                label = { Text(tag.name) },
                            )
                        }
                    }
                }
                if (onCreateTag != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newTag,
                            onValueChange = { newTag = it },
                            label = { Text("New tag") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { commitNewTag() },
                            enabled = newTag.isNotBlank() && !creatingTag,
                        ) { Text("Add") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // Commit any pending typed tag first, then save — so pressing Save/Done always
                // persists the tags the user picked or typed without an extra "Add" tap.
                onClick = {
                    commitNewTag {
                        onSave(name.trim(), phone.trim().ifBlank { null }, upi.trim().ifBlank { null }, selected.toList())
                    }
                },
                enabled = name.isNotBlank() && !creatingTag,
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagManagerDialog(
    tags: List<TagEntity>,
    onDismiss: () -> Unit,
    onSave: (id: String?, name: String, color: String) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(TAG_COLORS.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tags.isNotEmpty()) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(tags, key = { it.id }) { tag ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.size(14.dp).background(parseColor(tag.color), CircleShape))
                                Text(tag.name, Modifier.weight(1f))
                                IconButton(onClick = { onDelete(tag.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${tag.name}",
                                        tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                Text("New tag", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(newName, { newName = it }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TAG_COLORS.forEach { c ->
                        ColorSwatch(color = parseColor(c), selected = c == newColor) { newColor = c }
                    }
                }
                OutlinedButton(
                    onClick = { if (newName.isNotBlank()) { onSave(null, newName.trim(), newColor); newName = "" } },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add tag") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).background(color, CircleShape)
            .border(if (selected) 2.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
    }
}
