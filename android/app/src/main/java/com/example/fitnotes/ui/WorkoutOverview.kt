package com.example.fitnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel

@Composable
fun WorkoutOverview(session: NotebookSession?, day: String, sessions: List<NotebookSession>, data: AppData, model: FitNotesViewModel,
    busy: Boolean, editable: Boolean, state: LazyListState, onDay: (String) -> Unit, onSession: (String?) -> Unit,
    onExercise: (String) -> Unit, onStart: () -> Unit, onAdd: () -> Unit, onEdit: () -> Unit, onShare: (String) -> Unit,
    onImport: () -> Unit, onBackup: () -> Unit, onBack: (() -> Unit)? = null, onDeleted: () -> Unit, onFinished: () -> Unit, onRepeat: () -> Unit) {
    var choosingDay by remember { mutableStateOf(false) }
    var choosingSession by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var finish by remember { mutableStateOf(false) }
    var expandedNotes by remember(session?.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        NotebookBar(session?.title?.ifBlank { "Workout" } ?: "FitNotes", onBack) {
            if (session?.local != null && editable) IconButton(onClick = onAdd, enabled = !busy) { Icon(Icons.Filled.Add, "Add exercise") }
            if (session?.local != null && !session.completed) TextButton(onClick = { finish = true }, enabled = !busy) { Text("Finish") }
            OverflowMenu { close ->
                if (session != null) {
                    if (session.completed && !editable) MenuAction("Edit workout") { close(); onEdit() }
                    if (editable) MenuAction("Edit details") { close(); details = true }
                    MenuAction("Share workout") { close(); onShare(sessionContext(session, data)) }
                    if (session.completed && data.active == null) MenuAction("Repeat exercises") { close(); onRepeat() }
                    MenuAction("Delete workout") { close(); delete = true }
                }
                MenuAction("Import history") { close(); onImport() }
                MenuAction("Backup and restore") { close(); onBackup() }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDay(moveDay(day, -1)) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("‹", Modifier.semantics { contentDescription = "Previous day" }) }
            TextButton(onClick = { choosingDay = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(prettyDay(day, "EEE d MMM yyyy")) }
            TextButton(onClick = { onDay(moveDay(day, 1)) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("›", Modifier.semantics { contentDescription = "Next day" }) }
            if (day != todayDate()) TextButton(onClick = { onDay(todayDate()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Today") }
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val sameDay = sessions.filter { it.date == day }
            if (sameDay.size > 1 || (session == null && sameDay.isNotEmpty())) item {
                TextButton(onClick = { choosingSession = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (session == null) "${sameDay.size} workouts on this day ▾" else session.timeLabel + " · ${sameDay.size} workouts ▾")
                }
            }
            if (session == null) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (day == todayDate()) "Ready to train" else "No workout selected", style = MaterialTheme.typography.titleLarge)
                    Text(if (sameDay.isEmpty()) "No workouts on this day." else "Choose a workout above to review its sets.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (data.active != null) Button(shape = MaterialTheme.shapes.small, onClick = { onSession("local:" + data.active!!.workout.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Resume workout") }
                    else if (day == todayDate()) Button(shape = MaterialTheme.shapes.small, onClick = onStart, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start workout") }
                }
            }
            if (session != null) {
                if (session.completed || session.imported != null) item {
                    Text(buildList {
                        if (session.dateOnly) add("Date-only") else add(session.timeLabel)
                        add(if (session.imported != null) "Imported history" else "Completed")
                        if (editable) add("Editing")
                    }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (session.notes.isNotBlank()) item {
                    Text(session.notes, maxLines = if (expandedNotes) Int.MAX_VALUE else 1,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expandedNotes = !expandedNotes }.padding(vertical = 12.dp))
                }
                items(session.exercises, key = { it.key }) { exercise ->
                    Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onExercise(exercise.key) }) {
                        Column {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(exercise.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (exercise.sets.isEmpty()) Text("No sets yet.", Modifier.padding(horizontal = 12.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            exercise.sets.forEachIndexed { index, set ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                NotebookSetRow(set, index + 1)
                            }
                        }
                    }
                }
                if (session.exercises.isEmpty()) item { Text("Add your first exercise.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (session.completed && data.active == null && day == todayDate()) item {
                    TextButton(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start another workout") }
                }
            }
        }
    }
    if (choosingDay) DayDialog(day, { choosingDay = false }) { choosingDay = false; onDay(it) }
    if (choosingSession) AlertDialog(onDismissRequest = { choosingSession = false }, title = { Text("Workouts · " + prettyDay(day)) }, text = {
        LazyColumn { items(sessions.filter { it.date == day }, key = { it.key }) { item ->
            TextButton(onClick = { choosingSession = false; onSession(item.key) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(item.title.ifBlank { "Workout" } + " · " + item.timeLabel + if (!item.completed) " · In progress" else "")
            }
        } }
    }, confirmButton = { TextButton(onClick = { choosingSession = false }) { Text("Close") } })
    if (details && session != null) SessionDetailsDialog(session, busy, { details = false }) { title, notes ->
        if (session.local != null) model.saveDetails(session.local.workout.id, title, notes)
        else model.saveImportedNotes(session.imported!!.workout.id, notes)
        details = false
    }
    if (finish && session?.local != null) NotebookConfirm("Finish workout?", "Unsaved draft input will not be included.", "Finish", busy, { finish = false }) {
        model.finish(session.local.workout.id) { finish = false; onFinished() }
    }
    if (delete && session != null) NotebookConfirm("Delete workout?", "This deletes the workout and its saved sets.", "Delete", busy, { delete = false }) {
        if (session.local != null) model.deleteWorkout(session.local.workout.id) { delete = false; onDeleted() }
        else model.deleteImportedWorkout(session.imported!!.workout.id) { delete = false; onDeleted() }
    }
}
@Composable
private fun SessionDetailsDialog(session: NotebookSession, busy: Boolean, onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(session.title) }; var notes by remember { mutableStateOf(session.notes) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Workout details") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (session.local != null) OutlinedTextField(title, { title = it.take(200) }, label = { Text("Title") }, singleLine = true)
            OutlinedTextField(notes, { notes = it.take(10000) }, label = { Text("Notes") }, minLines = 2, maxLines = 5)
        }
    }, confirmButton = { TextButton(onClick = { onSave(title, notes) }, enabled = !busy) { Text("Save") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
