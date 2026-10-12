package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import java.math.BigDecimal

// Targets keep their original unit. Only the actual kg logger needs conversion,
// which is stated next to the suggestion and on its explicit Use action.
fun suggestedActualWeight(target: PlanSetTarget): String = when (target.weight.unit) {
    "bodyweight" -> "0"
    "kg" -> target.weight.value.orEmpty()
    "lb" -> target.weight.value?.let { BigDecimal(it).multiply(BigDecimal("0.45359237")).stripTrailingZeros().toPlainString() }.orEmpty()
    else -> ""
}

@Composable
fun RunSuggestions(exercise: RunExerciseDetail, model: FitNotesViewModel, busy: Boolean, editable: Boolean, onUse: (PlanSetTarget) -> Unit) {
    var showEditor by rememberSaveable(exercise.entry.id) { mutableStateOf(false) }
    val error by model.planImportError.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Workout suggestions · not completed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (exercise.entry.planNote.isNotBlank()) Text(exercise.entry.planNote)
        exercise.ordered.forEachIndexed { index, set ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Set ${index + 1}: ${set.target.text}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (editable) TextButton(onClick = { onUse(set.target) }, enabled = !busy) { Text(if (set.weightUnit == "lb") "Use in kg" else "Use") }
            }
        }
        if (exercise.ordered.any { it.weightUnit == "lb" }) Text("Targets retain lb. The actual logger uses kg; copied lb loads are converted at 0.45359237 kg/lb and remain editable.", style = MaterialTheme.typography.bodySmall)
        if (exercise.ordered.isEmpty()) Text("Targets unspecified. Enter actual results only when performed.")
        if (editable && exercise.ordered.isNotEmpty()) TextButton(onClick = { showEditor = !showEditor }) { Text(if (showEditor) "Close suggestion editor" else "Edit workout suggestions") }
        if (showEditor) exercise.ordered.forEachIndexed { index, set ->
            var draft by remember(set.id, set.target) { mutableStateOf(set.target.editable()) }
            SetTargetEditor(draft, index + 1, busy, { draft = it })
            TextButton(onClick = { model.saveRunSuggestion(set.id, draft, exercise.exercise.name) {} }, enabled = !busy) { Text("Save suggestion ${index + 1}") }
        }
        if (showEditor && error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Text("The reusable plan stays unchanged. A suggestion counts as training only after Save set.", style = MaterialTheme.typography.bodySmall)
    }
}
