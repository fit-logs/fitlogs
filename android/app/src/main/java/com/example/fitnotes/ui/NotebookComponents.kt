package com.example.fitnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.example.fitnotes.data.*

@Composable
fun NotebookBar(title: String, onBack: (() -> Unit)? = null, backLabel: String = "Back", actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, backLabel) }
            Text(title, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            actions()
        }
    }
}
@Composable
fun OverflowMenu(content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}
@Composable
fun MenuAction(text: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = onClick)

@Composable
fun NotebookSetRow(set: NotebookSet, number: Int, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.fillMaxWidth().testTag("saved-set:" + set.key).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { if (selected) contentDescription = "Editing set " + number + ", " + set.text }
            .padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected) "✓ $number" else number.toString(), Modifier.widthIn(min = 36.dp), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                if (set.weight != null && set.reps != null) {
                    Text(weightText(set.weight) + " kg", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
                    Text(set.reps.toString() + " reps", style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
                } else Text(set.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
                if (set.comment.isNotBlank()) Text("  •", Modifier.semantics { contentDescription = "Has note" }, color = MaterialTheme.colorScheme.primary)
            }
            if (set.weight != null && (set.distance != null || set.seconds != null)) Text(buildList {
                set.distance?.let { add(weightText(it) + " " + set.unit) }; set.seconds?.let { add(weightText(it) + " seconds") }
            }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            if (selected && set.comment.isNotBlank()) Text(set.comment, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
@Composable
fun NotebookConfirm(title: String, message: String, action: String, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(action) } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } })
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDialog(day: String, onCancel: () -> Unit, onChoose: (String) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = pickerMillis(day), yearRange = 1..9999)
    DatePickerDialog(onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onChoose(pickerDay(it)) } }, enabled = state.selectedDateMillis != null) { Text("Choose") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }) { DatePicker(state) }
}
@Composable
fun ExercisePicker(exercises: List<Exercise>, added: Set<Long>, busy: Boolean, onCancel: () -> Unit, onSelect: (Long) -> Unit, onCreate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Add exercise") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it.take(100) }, label = { Text("Search exercises") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(exercises.filter { it.name.contains(query.trim(), true) && it.id !in added }, key = { it.id }) { exercise ->
                    TextButton(onClick = { onSelect(exercise.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(exercise.name) }
                }
            }
            if (query.isNotBlank()) TextButton(onClick = { onCreate(query) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Create “$query”") }
        }
    }, confirmButton = { TextButton(onClick = onCancel) { Text("Close") } })
}
@Composable
fun NumericControl(label: String, value: String, onChange: (String) -> Unit, step: Double, whole: Boolean, busy: Boolean, modifier: Modifier = Modifier, maximum: Double = 10000.0) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value, { if (it.length <= 18) onChange(it) }, label = { Text(label) }, singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, fontFeatureSettings = "tnum"),
            keyboardOptions = KeyboardOptions(keyboardType = if (whole) KeyboardType.Number else KeyboardType.Decimal), enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("field:" + label))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            fun adjusted(amount: Double) {
                val numeric = value.trim().replace(',', '.').toDoubleOrNull() ?: if (value.isBlank()) 0.0 else return
                if (!numeric.isFinite() || numeric < 0) return
                val next = (numeric + amount).coerceIn(if (whole) 1.0 else 0.0, maximum)
                onChange(if (whole) next.toInt().toString() else weightText(next))
            }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { adjusted(-step) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp)) {
                Text("−", Modifier.semantics { contentDescription = "Decrease $label by " + weightText(step) })
            }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { adjusted(step) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp)) {
                Text("+", Modifier.semantics { contentDescription = "Increase $label by " + weightText(step) })
            }
        }
    }
}
@Composable
fun UnitPicker(unit: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Distance unit: $unit ▾") }
        DropdownMenu(open, { open = false }) {
            listOf("m", "km", "ft", "mi").forEach { value -> MenuAction(value) { onChange(value); open = false } }
        }
    }
}
