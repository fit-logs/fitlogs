package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.fitnotes.data.*
import java.math.BigDecimal

@Composable
fun BodyProfileDialog(profile: BodyProfile, busy: Boolean, onCancel: () -> Unit, onSave: (BodyProfile) -> Unit) {
    var heightUnit by remember { mutableStateOf(profile.heightUnit) }
    var weightUnit by remember { mutableStateOf(profile.weightUnit) }
    var canonical by remember { mutableStateOf(profile.heightCm) }
    fun precise(n: Double) = BigDecimal.valueOf(n).stripTrailingZeros().toPlainString()
    var cm by remember { mutableStateOf(profile.heightCm?.let(::precise) ?: "") }
    var feet by remember { mutableStateOf(profile.heightCm?.let { (it / 30.48).toInt().toString() } ?: "") }
    var inches by remember { mutableStateOf(profile.heightCm?.let { precise(it / 2.54 - (it / 30.48).toInt() * 12) } ?: "") }
    var birthDate by remember { mutableStateOf(profile.birthDate ?: "") }
    var confirm by remember { mutableStateOf(false) }
    fun number(raw: String) = raw.trim().replace(',', '.').toDoubleOrNull()
    val emptyHeight = if (heightUnit == "cm") cm.isBlank() else feet.isBlank() && inches.isBlank()
    val validHeight = emptyHeight || (canonical?.let { it.isFinite() && it > 0 } == true)
    val validBirth = birthDate.isBlank() || (validHistoryDate(birthDate.trim()) && birthDate.trim() <= bodyLocalDate())
    fun save() = onSave(profile.copy(heightCm = if (emptyHeight) null else canonical, birthDate = birthDate.trim().ifBlank { null }, heightUnit = heightUnit, weightUnit = weightUnit))
    AlertDialog(modifier = Modifier.imePadding(), properties = DialogProperties(decorFitsSystemWindows = false), onDismissRequest = { if (!busy) onCancel() }, title = { Text("Body profile") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Height", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("cm", "ft-in").forEach { unit -> FilterChip(heightUnit == unit, {
                        if (unit != heightUnit) {
                            cm = canonical?.let(::precise) ?: ""
                            feet = canonical?.let { (it / 30.48).toInt().toString() } ?: ""
                            inches = canonical?.let { precise(it / 2.54 - (it / 30.48).toInt() * 12) } ?: ""
                            heightUnit = unit
                        }
                    }, label = { Text(if (unit == "cm") "Centimetres" else "Feet / inches") }) }
                }
            }
            if (heightUnit == "cm") item {
                OutlinedTextField(cm, { cm = it.take(30); canonical = number(cm)?.takeIf { n -> n.isFinite() && n > 0 } }, label = { Text("Height (cm)") }, isError = !validHeight,
                    supportingText = { if (!validHeight) Text("Enter a positive, finite height.") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            } else item {
                // Stack fields to retain usable targets with large system text and the keyboard.
                OutlinedTextField(feet, { feet = it.take(12); canonical = heightFromFeet(number(feet) ?: Double.NaN, number(inches.ifBlank { "0" }) ?: Double.NaN) }, label = { Text("Feet") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(inches, { inches = it.take(30); canonical = heightFromFeet(number(feet.ifBlank { "0" }) ?: Double.NaN, number(inches) ?: Double.NaN) }, label = { Text("Inches") }, isError = !validHeight,
                    supportingText = { if (!validHeight) Text("Use whole feet and inches from 0 up to 12 (exclusive).") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
            item {
                Text("Weight unit", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("kg", "lb").forEach { unit -> FilterChip(weightUnit == unit, { weightUnit = unit }, label = { Text(unit) }) } }
            }
            item {
                OutlinedTextField(birthDate, { birthDate = it.take(10) }, label = { Text("Birth date (optional)") }, placeholder = { Text("YYYY-MM-DD") }, isError = !validBirth,
                    supportingText = { Text(if (validBirth) "Used only for age-appropriate BMI interpretation. YYYY-MM-DD." else "Use YYYY-MM-DD, on or before today.") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
            }
            item { Text("Saved locally and included in your backups. Not added to ChatGPT exports.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }, confirmButton = { TextButton(onClick = { if (canonical?.let(::unusualHeight) == true) confirm = true else save() }, enabled = validHeight && validBirth && !busy) { Text("Save profile") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } })
    if (confirm) NotebookConfirm("Check height", "You entered ${canonical?.let(::bodyDecimal)} cm. Please check the height and unit.", "Use this height", busy, { confirm = false }) { confirm = false; save() }
}
