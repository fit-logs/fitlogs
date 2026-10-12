package com.example.fitnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import com.example.fitnotes.ui.theme.LocalAppearance
import com.example.fitnotes.ui.theme.ThemeMode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
            Text(title, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            actions()
        }
    }
}
@Composable
fun OverflowMenu(content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val appearance = LocalAppearance.current
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
            HorizontalDivider()
            MenuAction("Settings") { open = false; settings = true }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Settings") }, text = {
        Column {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            ThemeMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { appearance.choose(mode) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = appearance.mode == mode, onClick = { appearance.choose(mode) })
                    Text(mode.name)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
}
@Composable
fun MenuAction(text: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = onClick)

// One column definition is shared by the overview, logger and exercise history.
fun setColumns(sets: List<NotebookSet>): List<String> = when {
    sets.isEmpty() || sets.all { it.weight != null && it.reps != null && it.distance == null && it.seconds == null } -> listOf("kg", "Reps")
    sets.all { it.cardio } -> listOf("Distance", "Seconds")
    else -> listOf("Details")
}
@Composable
fun NotebookSetHeader(columns: List<String>) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Set", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        columns.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.width(16.dp))
    }
}
@Composable
fun NotebookSetRow(set: NotebookSet, number: Int, selected: Boolean = false, onClick: (() -> Unit)? = null, columns: List<String> = setColumns(listOf(set)), compact: Boolean = false) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().testTag("saved-set:" + set.key).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { if (selected) contentDescription = "Editing set " + number + ", " + set.text }
            .padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 30.dp else 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val numericStyle = (if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge).copy(fontFeatureSettings = "tnum")
                Text(if (selected) "✓ $number" else number.toString(), Modifier.width(44.dp), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = numericStyle)
                val values = when (columns) {
                    listOf("kg", "Reps") -> listOf(set.weight?.let(::weightText) ?: "—", set.reps?.toString() ?: "—")
                    listOf("Distance", "Seconds") -> listOf(set.distance?.let { weightText(it) + " " + set.unit.orEmpty() } ?: "—", set.seconds?.let(::weightText) ?: "—")
                    else -> listOf(set.text)
                }
                values.forEach { Text(it, Modifier.weight(1f).padding(vertical = 3.dp), textAlign = TextAlign.End, style = numericStyle) }
                Box(Modifier.width(16.dp), contentAlignment = Alignment.CenterEnd) {
                    if (set.comment.isNotBlank()) Text("•", Modifier.semantics { contentDescription = "Has note" }, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (selected && set.comment.isNotBlank()) Text(set.comment, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
@Composable
fun ExerciseSymbol(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val y = size.height / 2
        drawLine(color, Offset(size.width * .1f, y), Offset(size.width * .9f, y), 2.dp.toPx(), StrokeCap.Round)
        listOf(.2f to .3f, .32f to .2f, .68f to .2f, .8f to .3f).forEach { (x, inset) ->
            drawLine(color, Offset(size.width * x, size.height * inset), Offset(size.width * x, size.height * (1 - inset)), 2.5.dp.toPx(), StrokeCap.Round)
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
fun NumericControl(label: String, value: String, onChange: (String) -> Unit, step: Double, whole: Boolean, busy: Boolean, modifier: Modifier = Modifier, maximum: Double = 10000.0) {
    fun adjusted(amount: Double) {
        val numeric = value.trim().replace(',', '.').toDoubleOrNull() ?: if (value.isBlank()) 0.0 else return
        if (!numeric.isFinite() || numeric < 0) return
        val next = (numeric + amount).coerceIn(if (whole) 1.0 else 0.0, maximum)
        onChange(if (whole) next.toInt().toString() else weightText(next))
    }
    val largeText = LocalDensity.current.fontScale >= 1.3f
    Surface(modifier, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
            Text(label.replace(" (", " · ").replace(")", ""), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            @Composable fun field(modifier: Modifier) {
                BasicTextField(value, { if (it.length <= 18) onChange(it) }, singleLine = true, enabled = !busy,
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, textAlign = TextAlign.Center, fontFeatureSettings = "tnum", color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), keyboardOptions = KeyboardOptions(keyboardType = if (whole) KeyboardType.Number else KeyboardType.Decimal),
                    modifier = modifier.testTag("field:" + label).semantics { contentDescription = label },
                    decorationBox = { inner -> Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) { inner() } })
            }
            @Composable fun decrease() { IconButton(onClick = { adjusted(-step) }, enabled = !busy) { Text("−", Modifier.semantics { contentDescription = "Decrease $label by " + weightText(step) }, fontSize = 22.sp) } }
            @Composable fun increase() { IconButton(onClick = { adjusted(step) }, enabled = !busy) { Text("+", Modifier.semantics { contentDescription = "Increase $label by " + weightText(step) }, fontSize = 22.sp) } }
            if (largeText) {
                field(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { decrease(); increase() }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { decrease(); field(Modifier.weight(1f)); increase() }
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
