package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.fitnotes.FitNotesViewModel

@Composable
internal fun ReorderExercisesDialog(session: NotebookSession, busy: Boolean, model: FitNotesViewModel, onClose: () -> Unit) {
    var order by rememberSaveable(session.key) { mutableStateOf(session.exercises.mapNotNull { it.entryId }) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val exercises = session.exercises.associateBy { it.entryId }
    val changed = order != session.exercises.mapNotNull { it.entryId }
    fun move(index: Int, offset: Int) {
        order = order.toMutableList().apply { add(index + offset, removeAt(index)) }
        error = null
    }
    HandoffScreen("Reorder exercises", busy, onClose, "exercise-order-list", attention = error, footer = {
        Button(onClick = {
            model.reorderExercises(session.local!!.workout.id, order, onClose) { error = it }
        }, enabled = !busy && changed, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save order") }
        TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    }) {
        item {
            Text("Move exercises up or down, then save the order for this workout.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        itemsIndexed(order, key = { _, id -> id }) { index, id ->
            val name = exercises[id]?.name ?: "Removed exercise"
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("exercise-order:$id")) {
                Column(Modifier.padding(12.dp)) {
                    Text("${index + 1}. $name", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { move(index, -1) }, enabled = !busy && index > 0,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("exercise-up:$id")) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Move $name up")
                        }
                        IconButton(onClick = { move(index, 1) }, enabled = !busy && index < order.lastIndex,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("exercise-down:$id")) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Move $name down")
                        }
                    }
                }
            }
        }
    }
}
