package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData

@Composable
fun NotebookHistory(sessions: List<NotebookSession>, data: AppData, state: LazyListState, onOpen: (String) -> Unit, onShare: () -> Unit, onImport: () -> Unit, onBackup: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        NotebookBar("History") {
            if (!data.imported.loading) TextButton(onClick = onShare, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share") }
            OverflowMenu { close ->
                MenuAction("Import history") { close(); onImport() }
                MenuAction("Backup and restore") { close(); onBackup() }
            }
        }
        LazyColumn(state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (data.imported.loading) item { Text("Loading imported history…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val completed = sessions.filter { it.completed }
            if (completed.isEmpty() && !data.imported.loading) item { Text("No completed workouts yet.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            completed.groupBy { it.date.take(7) }.forEach { (month, rows) ->
                item(key = "month:$month") { Text(prettyDay(month + "-01", "MMMM yyyy"), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rows, key = { it.key }) { session ->
                    Surface(onClick = { onOpen(session.key) }, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(prettyDay(session.date) + if (session.title.isNotBlank()) " — " + session.title else "", style = MaterialTheme.typography.titleMedium)
                            Text(session.exercises.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text("${session.exercises.size} exercises · ${session.count} sets" + if (session.dateOnly) " · Date-only" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareRangeDialog(data: AppData, onCancel: () -> Unit, onPreview: (String) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var empty by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    fun prepare(first: String, last: String) {
        val text = rangeContext(data, first, last)
        if (text == null) empty = true else onPreview("Training context · $first to $last\n\n" + text)
    }
    if (!custom) AlertDialog(onDismissRequest = onCancel, title = { Text("Share training context") }, text = {
        Column {
            TextButton(onClick = { prepare(moveDay(todayDate(), -13), todayDate()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Last 14 days") }
            TextButton(onClick = { prepare(moveDay(todayDate(), -29), todayDate()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Last 30 days") }
            TextButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Custom date range") }
        }
    }, confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
    else {
        val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = pickerMillis(moveDay(todayDate(), -13)), initialSelectedEndDateMillis = pickerMillis(todayDate()), yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = onCancel, confirmButton = { TextButton(onClick = {
            val start = picker.selectedStartDateMillis; val end = picker.selectedEndDateMillis
            if (start != null && end != null && start <= end) prepare(pickerDay(start), pickerDay(end)) else invalid = true
        }) { Text("Preview") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }) {
            DateRangePicker(picker, modifier = Modifier.heightIn(max = 460.dp))
        }
    }
    if (empty || invalid) AlertDialog(onDismissRequest = { empty = false; invalid = false }, title = { Text(if (empty) "No activity in this range" else "Choose both dates") },
        text = { Text(if (empty) "There are no completed workouts or body measurements in the selected range." else "Choose a start and end date to preview your context.") },
        confirmButton = { TextButton(onClick = { empty = false; invalid = false }) { Text("OK") } })
}
