package com.example.fitnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun WorkoutOverview(session: NotebookSession?, day: String, sessions: List<NotebookSession>, data: AppData, model: FitNotesViewModel,
    busy: Boolean, editable: Boolean, state: LazyListState, onDay: (String) -> Unit, onSession: (String?) -> Unit,
    onExercise: (String) -> Unit, onStart: () -> Unit, onAdd: () -> Unit, onEdit: () -> Unit, onShare: (String) -> Unit,
    onRecoverPlan: (Long) -> Unit, onImport: () -> Unit, onHealthImport: () -> Unit, onBackup: () -> Unit, onChatGPT: () -> Unit, onPastePlan: () -> Unit, onPlans: () -> Unit,
    onBack: (() -> Unit)? = null, onDeleted: () -> Unit, onFinished: () -> Unit, onRepeat: () -> Unit) {
    var choosingDay by remember { mutableStateOf(false) }
    var choosingSession by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var finish by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var provenance by remember { mutableStateOf(false) }
    val showShareIcon = session != null && (session.completed || LocalDensity.current.fontScale <= 1.35f)
    val plans by model.plans.collectAsState()
    val runs by model.planRuns.collectAsState()
    val run = session?.local?.workout?.id?.let { id -> runs.firstOrNull { it.run.workoutId == id } }
    val recoverable = session?.local?.let { eligibleEmptySuggestion(it, run != null) } == true
    val legacyEmpty = recoverable && session?.notes?.startsWith(SESSION_PLAN_PREFIX) == true
    Column(Modifier.fillMaxSize()) {
        NotebookBar("Workout", onBack) {
            if (showShareIcon && session != null) IconButton(onClick = { onShare(sessionContext(session, data)) }) { Icon(Icons.Filled.Share, "Copy or share workout") }
            if (session?.local != null && !session.completed && !legacyEmpty) TextButton(onClick = { finish = true }, enabled = !busy) { Text(if (session.activity == "strength") "Finish workout" else "Finish session") }
            OverflowMenu { close ->
                if (session != null) {
                    if (session.completed && !editable) MenuAction("Edit workout") { close(); onEdit() }
                    if (recoverable) MenuAction("Convert empty import to plan") { close(); onRecoverPlan(session.local!!.workout.id) }
                    MenuAction("Workout information") { close(); provenance = true }
                    if (editable) MenuAction("Edit details") { close(); details = true }
                    MenuAction("Share workout") { close(); onShare(sessionContext(session, data)) }
                    if (session.completed && session.activity == "strength" && data.active == null) MenuAction("Repeat exercises") { close(); onRepeat() }
                    MenuAction("Delete workout") { close(); delete = true }
                }
                MenuAction("Ask ChatGPT") { close(); onChatGPT() }
                MenuAction("Paste plan") { close(); onPastePlan() }
                MenuAction("Planned workouts") { close(); onPlans() }
                MenuAction("Import history") { close(); onImport() }
                MenuAction("Backup and restore") { close(); onBackup() }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDay(moveDay(day, -1)) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
            TextButton(onClick = { choosingDay = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(prettyDay(day, "EEE d MMM yyyy")) }
            TextButton(onClick = { onDay(moveDay(day, 1)) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day") }
            if (day != todayDate()) TextButton(onClick = { onDay(todayDate()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Today") }
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onChatGPT, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ask ChatGPT") }
                    TextButton(onClick = onPastePlan, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Paste plan") }
                    TextButton(onClick = onPlans, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Plans (${plans.size})") }
                }
            }
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
                if (session.title.isNotBlank()) item {
                    Text(session.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (session.notes.startsWith(SESSION_PLAN_PREFIX)) item { SessionPlanCard(session.notes, session.completed) }
                else if (session.notes.isNotBlank()) item {
                    Text(session.notes, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { showNotes = true }.padding(vertical = 4.dp))
                }
                if (legacyEmpty) item { OutlinedButton(onClick = { onRecoverPlan(session.local!!.workout.id) }, enabled = !busy) { Text("Convert empty import to plan") } }
                if (run != null) item { RunReference(run) }
                if (session.local != null && session.activity != "strength") item { ActivityPanel(session, editable, model, busy) }
                if (session.local != null && session.local.health.isNotEmpty()) item { ImportedActivityInfo(session.local.health) }
                if (session.imported != null && session.imported.health.isNotEmpty()) item { ImportedActivityInfo(session.imported.health) }
                if (!legacyEmpty && session.local?.health.isNullOrEmpty() && session.imported?.health.isNullOrEmpty()) item {
                    MissingHeartRateInfo(busy, onHealthImport)
                }
                items(session.exercises, key = { it.key }) { exercise ->
                    Surface(shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onExercise(exercise.key) }) {
                        Column {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(exercise.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                if (editable) TextButton(onClick = { onExercise(exercise.key) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(3.dp)); Text("Log set")
                                } else Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (exercise.sets.isEmpty()) Text("No sets yet.", Modifier.padding(horizontal = 12.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val columns = setColumns(exercise.sets)
                            if (exercise.sets.isNotEmpty()) NotebookSetHeader(columns)
                            exercise.sets.forEachIndexed { index, set ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                NotebookSetRow(set, index + 1, columns = columns, compact = true)
                            }
                        }
                    }
                }
                if (session.local != null && session.activity == "strength" && editable) item {
                    OutlinedButton(onClick = onAdd, enabled = !busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Add exercise")
                    }
                }
                if (session.activity == "strength" && session.exercises.isEmpty()) item { Text("Add your first exercise.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (session.completed && data.active == null && day == todayDate()) item {
                    TextButton(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start another workout") }
                }
            }
        }
    }
    if (showNotes && session != null) AlertDialog(onDismissRequest = { showNotes = false }, title = { Text("Workout notes") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { item { Text(session.notes) } } },
        confirmButton = { TextButton(onClick = { showNotes = false }) { Text("Close") } },
        dismissButton = { if (editable) TextButton(onClick = { showNotes = false; details = true }) { Text("Edit") } })
    if (provenance && session != null) AlertDialog(onDismissRequest = { provenance = false }, title = { Text("Workout information") },
        text = { Text(prettyDay(session.date, "EEE d MMM yyyy") + "\n" + (if (session.dateOnly) "Date-only · no session time was recorded" else session.timeLabel) + "\n" +
            (session.imported?.let { imported -> data.imported.imports.firstOrNull { it.id == imported.workout.importId }?.source ?: "Imported history" } ?: if (session.completed) "Completed workout" else "In progress")) },
        confirmButton = { TextButton(onClick = { provenance = false }) { Text("Close") } })
    if (choosingDay) DayDialog(day, { choosingDay = false }) { choosingDay = false; onDay(it) }
    if (choosingSession) AlertDialog(onDismissRequest = { choosingSession = false }, title = { Text("Workouts · " + prettyDay(day)) }, text = {
        LazyColumn { items(sessions.filter { it.date == day }, key = { it.key }) { item ->
            TextButton(onClick = { choosingSession = false; onSession(item.key) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(item.title.ifBlank { "Workout" } + " · " + item.timeLabel + if (!item.completed) " · In progress" else "")
            }
        } }
    }, confirmButton = { TextButton(onClick = { choosingSession = false }) { Text("Close") } })
    if (details && session?.local != null) ActivityDetailsDialog(session.local.workout, busy, { details = false }) { title, notes, effort, distance, unit ->
        model.saveActivityDetails(session.local.workout.id, title, notes, effort, distance, unit) { details = false }
    }
    if (details && session != null && session.local == null) SessionDetailsDialog(session, busy, { details = false }) { title, notes ->
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
