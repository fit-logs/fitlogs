package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun BodyScreen(data: AppData, model: FitNotesViewModel, busy: Boolean, state: LazyListState, onImport: () -> Unit, onBackup: () -> Unit, onShare: (String) -> Unit) {
    var photosTab by rememberSaveable { mutableStateOf(false) }
    var typeKey by rememberSaveable { mutableStateOf<String?>(null) }
    val types = data.imported.body.map { it.name to it.unit }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    val chosen = types.firstOrNull { it.first + "\u001f" + it.second == typeKey } ?: types.firstOrNull()
    val sharedValues = data.imported.body.filter { it.name == chosen?.first && it.unit == chosen?.second }
    Column(Modifier.fillMaxSize()) {
        NotebookBar("Body") {
            OverflowMenu { close ->
                MenuAction("Import history") { close(); onImport() }
                MenuAction("Backup and restore") { close(); onBackup() }
                if (!photosTab && sharedValues.isNotEmpty()) MenuAction("Share measurements") { close(); onShare(bodyContext(sharedValues)) }
            }
        }
        TabRow(selectedTabIndex = if (photosTab) 1 else 0) {
            Tab(selected = !photosTab, onClick = { photosTab = false }, text = { Text("Measurements") })
            Tab(selected = photosTab, onClick = { photosTab = true }, text = { Text("Photos") })
        }
        if (photosTab) PhotosScreen(model, busy)
        else MeasurementsScreen(data, model, busy, state, onImport, typeKey) { typeKey = it }
    }
}

@Composable
private fun MeasurementsScreen(data: AppData, model: FitNotesViewModel, busy: Boolean, state: LazyListState, onImport: () -> Unit, typeKey: String?, onTypeKey: (String?) -> Unit) {
    val types = remember(data.imported.body) { data.imported.body.map { it.name to it.unit }.distinct().sortedWith(compareBy({ it.first }, { it.second })) }
    val chosen = types.firstOrNull { it.first + "\u001f" + it.second == typeKey } ?: types.firstOrNull()
    val values = data.imported.body.filter { it.name == chosen?.first && it.unit == chosen?.second }
    var choosing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var contextRecord by remember { mutableStateOf<BodyMeasurement?>(null) }
    var editing by remember { mutableStateOf<BodyMeasurement?>(null) }
    var deleting by remember { mutableStateOf<BodyMeasurement?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (chosen != null) item {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(chosen.first + " ▾") }
            }
            if (values.isNotEmpty()) item {
                val latest = values.first()
                Surface(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Latest measurement", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(weightText(latest.value) + " " + displayUnit(latest.unit), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
                        Text(prettyDay(latest.date), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (data.imported.loading) item { Text("Loading measurements…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!data.imported.loading && types.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No measurements yet", style = MaterialTheme.typography.titleMedium)
                    Text("Import your history or record a measurement.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import history") }
                    TextButton(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add measurement") }
                }
            }
            if (values.isNotEmpty()) item {
                Button(onClick = { adding = true }, enabled = !busy, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Add measurement")
                }
                Text("Measurements", Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.titleMedium)
            }
            items(values, key = { it.id }) { measurement ->
                Surface(onClick = { contextRecord = measurement }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(prettyDay(measurement.date, "d MMM yyyy"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(weightText(measurement.value) + " " + displayUnit(measurement.unit), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
                        Text(if (measurement.comment.isNotBlank()) "• ›" else "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Measurement type") }, text = {
        LazyColumn { items(types) { type -> TextButton(onClick = { onTypeKey(type.first + "\u001f" + type.second); choosing = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(type.first + " · " + type.second)
        } } }
    }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } })
    contextRecord?.let { record -> AlertDialog(onDismissRequest = { contextRecord = null }, title = { Text(record.name + " · " + prettyDay(record.date)) }, text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { item {
        Text(weightText(record.value) + " " + displayUnit(record.unit), style = MaterialTheme.typography.titleLarge)
        Text(if (record.time.isEmpty()) "Date-only · no time was recorded" else record.time, Modifier.padding(top = 12.dp))
        Text(data.imported.imports.firstOrNull { it.id == record.importId }?.source ?: "Imported history", style = MaterialTheme.typography.bodySmall)
        if (record.comment.isNotBlank()) Text(record.comment, Modifier.padding(top = 12.dp))
        if (record.rawValues.isNotBlank()) Text("Original values: " + record.rawValues, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
    } } },
        confirmButton = { TextButton(onClick = { contextRecord = null; editing = record }) { Text("Edit") } },
        dismissButton = { Row { TextButton(onClick = { contextRecord = null; deleting = record }) { Text("Delete") }; TextButton(onClick = { contextRecord = null }) { Text("Close") } } }) }
    if (adding || editing != null) MeasurementDialog(editing, data.imported.body, chosen, busy, { adding = false; editing = null }) { id, name, value, unit, unitName, date, time, note ->
        model.saveMeasurement(id, name, value, unit, unitName, date, time, note) {
            onTypeKey(name + "\u001f" + unit); adding = false; editing = null
        }
    }
    deleting?.let { record -> NotebookConfirm("Delete measurement?", "This removes the saved value from " + prettyDay(record.date) + ".", "Delete", busy, { deleting = null }) {
        model.deleteMeasurement(record.id) { deleting = null }
    } }
}

@Composable
private fun MeasurementDialog(record: BodyMeasurement?, all: List<BodyMeasurement>, initial: Pair<String, String>?, busy: Boolean, onCancel: () -> Unit,
    onSave: (String?, String, String, String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf(record?.name ?: initial?.first ?: "") }
    var unit by remember { mutableStateOf(record?.unit ?: initial?.second ?: "") }
    var unitName by remember { mutableStateOf(record?.unitName ?: all.firstOrNull { it.name == name && it.unit == unit }?.unitName ?: "") }
    var value by remember { mutableStateOf(record?.value?.let(::weightText) ?: "") }
    var date by remember { mutableStateOf(record?.date ?: todayDate()) }
    var time by remember { mutableStateOf(record?.time ?: "") }
    var note by remember { mutableStateOf(record?.comment ?: "") }
    var datePicker by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && value.trim().replace(',', '.').toDoubleOrNull()?.isFinite() == true && validHistoryDate(date) && (time.isEmpty() || validHistoryTime(time))
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text(if (record == null) "Add measurement" else "Edit measurement") }, text = {
        LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (record == null) {
                    if (all.isNotEmpty()) Box {
                        TextButton(onClick = { typeMenu = true }) { Text("Choose existing type ▾") }
                        DropdownMenu(typeMenu, { typeMenu = false }) {
                            all.map { Triple(it.name, it.unit, it.unitName) }.distinct().forEach { type -> MenuAction(type.first + " · " + type.second) {
                                name = type.first; unit = type.second; unitName = type.third; typeMenu = false
                            } }
                        }
                    }
                    OutlinedTextField(name, { name = it.take(500) }, label = { Text("Measurement name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(unit, { unit = it.take(50); unitName = unit }, label = { Text("Unit (blank for unitless)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else Text(record.name + " · " + record.unit)
                OutlinedTextField(value, { value = it.take(30) }, label = { Text("Value") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { datePicker = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(prettyDay(date, "EEE d MMM yyyy")) }
                OutlinedTextField(time, { time = it.take(8) }, label = { Text("Time (HH:mm:ss), optional") }, supportingText = { Text("Leave blank for date-only.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it.take(10000) }, label = { Text("Comment") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(record?.id, name, value, unit, unitName, date, time, note) }, enabled = valid && !busy) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } })
    if (datePicker) DayDialog(date, { datePicker = false }) { date = it; datePicker = false }
}

// Display aliases only. Stored units and original precision are left untouched.
fun displayUnit(unit: String): String = when (unit.trim().lowercase(java.util.Locale.ROOT)) {
    "kilogram", "kilograms", "kgs" -> "kg"
    "pound", "pounds", "lbs" -> "lb"
    "centimetre", "centimetres", "centimeter", "centimeters" -> "cm"
    else -> unit
}
