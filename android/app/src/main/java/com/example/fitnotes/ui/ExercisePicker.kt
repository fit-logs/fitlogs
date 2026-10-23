package com.example.fitnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.theme.LocalAppearance

@Composable
fun ExercisePicker(exercises: List<Exercise>, added: Set<Long>, recent: List<String>, categories: Map<String, String>, busy: Boolean,
    onCancel: () -> Unit, onSelect: (List<Long>) -> Unit, onCreate: (String) -> Unit, singleSelection: Boolean = false, aliases: List<ExerciseAlias> = emptyList(), allowCreation: Boolean = true) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(if (singleSelection) "All" else "Recent") }
    var selected by rememberSaveable(stateSaver = listSaver(save = { it }, restore = { it.toList() })) { mutableStateOf(emptyList<Long>()) }
    var creating by remember { mutableStateOf(false) }
    var customName by rememberSaveable { mutableStateOf("") }
    val appearance = LocalAppearance.current
    val available = exercises.filter { it.id !in added }
    fun keys(exercise:Exercise)=listOf(exercise.nameKey)+aliases.filter{it.exerciseId==exercise.id}.map{it.nameKey}
    fun favourite(exercise:Exercise)=keys(exercise).any{it in appearance.favourites}
    val q=query.trim()
    fun score(exercise:Exercise)= (keys(exercise)+exercise.name+categories[exercise.nameKey].orEmpty()).mapNotNull{fuzzyMatchScore(q,it)}.maxOrNull()
    val filtered = available.filter { exercise ->
        (q.isBlank() || score(exercise)!=null) && when(filter) {
            "Recent" -> exercise.nameKey in recent
            "Favourites" -> favourite(exercise)
            else -> true
        }
    }.let { values->if(q.isNotBlank())values.sortedWith(compareByDescending<Exercise>{score(it)}.thenBy{it.nameKey}) else if(filter=="Recent")values.sortedBy{recent.indexOf(it.nameKey)} else values }
    Column(Modifier.fillMaxSize()) {
        NotebookBar(if (singleSelection) "Resolve exercise" else "Add exercise", onCancel)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it.take(100) }, placeholder = { Text("Search exercises") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Recent", "Favourites", "All").forEach { choice -> FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(choice) }) }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp)) {
            if (filtered.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (query.isNotBlank()) "No matching exercises" else if (filter == "Favourites") "No favourites yet" else if (filter == "Recent") "No recent exercises to add" else "All exercises are already added", style = MaterialTheme.typography.titleMedium)
                    Text(if (filter == "Favourites") "Tap a star in All to keep an exercise here." else "Try All, change your search, or create a custom exercise.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(filtered, key = { it.id }) { exercise ->
                val checked = exercise.id in selected
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(enabled = !busy, role = Role.Checkbox) {
                    selected = if (checked) selected - exercise.id else if (singleSelection) listOf(exercise.id) else selected + exercise.id
                }, verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { ExerciseSymbol() } }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(exercise.name, style = MaterialTheme.typography.titleSmall)
                        categories[exercise.nameKey]?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    IconButton(onClick = { appearance.setFavourite(keys(exercise),exercise.nameKey,!favourite(exercise)) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Star, (if (favourite(exercise)) "Unfavourite " else "Favourite ") + exercise.name,
                            tint = if (favourite(exercise)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                    Checkbox(checked, onCheckedChange = null, modifier = Modifier.semantics { contentDescription = exercise.name + if (checked) ", selected" else ", unselected" })
                }
                HorizontalDivider()
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if(allowCreation) TextButton(onClick = { customName = query; creating = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Create custom exercise") }
                Button(onClick = { onSelect(selected.toList()) }, enabled = selected.isNotEmpty() && !busy, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (singleSelection) "Use for strength plan" else if (selected.size == 1) "Add 1 exercise" else "Add ${selected.size} exercises")
                }
            }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("Custom exercise") }, text = {
        Column {
            if (singleSelection) Text("Create a distinct strength exercise. It will enter the catalogue only when you save the plan.")
            OutlinedTextField(customName, { customName = it.take(100) }, label = { Text("Exercise name") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { onCreate(customName) }, enabled = customName.isNotBlank() && !busy) { Text(if (singleSelection) "Use new exercise" else "Create and add") } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
}
