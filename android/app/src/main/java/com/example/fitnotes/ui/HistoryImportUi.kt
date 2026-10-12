package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OriginalImportPreview(document: HistoryDocument, already: HistoryImport?, busy: Boolean, onCancel: () -> Unit, onImport: (ImportPlan) -> Unit) {
    var options by remember(document.id) { mutableStateOf(ImportOptions()) }
    val plan by produceState<ImportPlan?>(null, document, options) {
        value = null
        value = withContext(Dispatchers.Default) { HistoryImportReader.plan(document, options) }
    }
    val current = plan?.takeIf { it.options == options }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Preview original history") },
        text = {
            LazyColumn(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(document.source, style = MaterialTheme.typography.titleMedium)
                    Text(document.fileName)
                    Text("Import adds history alongside your current workouts. It does not overwrite local data.")
                }
                if (already != null) item {
                    Text("This exact export has already been imported. No additional records will be added.", color = MaterialTheme.colorScheme.primary)
                    Text("Original choices: " + already.units)
                }
                if (document.hasAmbiguousWeight) item {
                    Text("Workout CSV does not specify weight units. Choose the original unit.")
                    ChoiceRow(listOf("kg", "lb"), options.weightUnit, !busy) { options = options.copy(weightUnit = it) }
                    Text("Weights are stored in kg. Original values and source fields are retained.", style = MaterialTheme.typography.bodySmall)
                }
                document.ambiguousDistanceCodes.forEach { code ->
                    item {
                        Text(if (code == "CSV") "Confirm CSV distance units" else "Confirm distances marked with original unit code " + code)
                        ChoiceRow(listOf("m", "km", "ft", "mi"), options.distanceUnits[code], !busy) {
                            options = options.copy(distanceUnits = options.distanceUnits + (code to it))
                        }
                    }
                }
                if (document.hasCsvTime) item {
                    Text("Confirm the CSV Time format")
                    ChoiceRow(listOf("seconds", "minutes"), options.csvTimeFormat, !busy) { options = options.copy(csvTimeFormat = it) }
                    ChoiceRow(listOf("h:m:s", "m:s", "h:m"), options.csvTimeFormat, !busy) { options = options.copy(csvTimeFormat = it) }
                }
                if (current == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else {
                    item {
                        Text("Date range: " + (current.dates.firstOrNull() ?: "none") + " to " + (current.dates.lastOrNull() ?: "none"))
                        Text(current.workouts.size.toString() + " workouts · " + current.sets.size + " set/records\n" +
                            current.cardioCount + " cardio records · " + current.body.size + " body measurements")
                        Text(current.workouts.count { it.recordedStart == null }.toString() + " date-only workouts. One imported workout per source date; no session times are invented.")
                        if (current.zeroRepCount > 0) Text(current.zeroRepCount.toString() + " original zero-rep records are retained as source records.")
                        if (current.body.isNotEmpty()) Text("Body units from MeasurementUnit: " + current.body.map { it.unitName.ifBlank { "unitless" } }.distinct().joinToString(", "))
                        if (document.source.contains("backup") && current.cardioCount > 0) Text("Native duration_seconds values are seconds.")
                        if (!document.hasAmbiguousWeight && document.hasWeight) Text("Detected native workout weight: kilograms (metric_weight).")
                        if (document.source.contains("backup") && current.cardioCount > 0) Text("Detected native distance units: " + current.sets.mapNotNull { it.distanceUnit }.distinct().joinToString(", "))
                        Text("Invalid/skipped records: " + current.issues.size)
                    }
                    items(current.issues) { issue ->
                        Text(issue.row + ": " + issue.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (current.requiredChoices.isNotEmpty()) item {
                        Text(current.requiredChoices.joinToString("\n"), color = MaterialTheme.colorScheme.primary)
                    }
                    if (current.requiredChoices.isEmpty()) {
                        item { Text("Confirmed units: " + current.units) }
                        items(current.sets.take(3)) { set -> Text(set.exercise + " · " + importedSetText(set), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (document.unsupported.isNotEmpty()) {
                    item { Text("Other source records", style = MaterialTheme.typography.titleSmall) }
                    items(document.unsupported) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                item { Text("Identical repeated sets are retained. Re-importing this export is a no-op, even after renaming it. Different overlapping exports are separate imports.", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(shape = MaterialTheme.shapes.small, onClick = { current?.let(onImport) },
                enabled = !busy && already == null && current != null && current.requiredChoices.isEmpty() &&
                    (current.sets.isNotEmpty() || current.body.isNotEmpty())) { Text("Confirm import") }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(if (already == null) "Cancel" else "Close") } },
    )
}

@Composable
private fun ChoiceRow(values: List<String>, selected: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(value) },
                enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}
