package com.example.fitnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun ExerciseLogger(session: NotebookSession, exercise: NotebookExercise, all: List<NotebookSession>, data: AppData,
    model: FitNotesViewModel, busy: Boolean, editable: Boolean, onBack: () -> Unit, onSwitch: (String) -> Unit,
    onAdd: () -> Unit, onEditWorkout: () -> Unit, onHistory: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var switcher by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val previous = remember(all, session.key, exercise.key) { previousExerciseSessions(all, session, exercise) }
    val runs by model.planRuns.collectAsState()
    val run = session.local?.workout?.id?.let { id -> runs.firstOrNull { it.run.workoutId == id } }
    val runExercise = run?.exercises?.firstOrNull { it.entry.id == exercise.entryId }
    val firstTarget = runExercise?.ordered?.firstOrNull()?.target
    val seed = exercise.sets.lastOrNull() ?: previous.firstOrNull()?.second?.sets?.lastOrNull()
    var weight by rememberSaveable { mutableStateOf(if (runExercise != null) firstTarget?.let(::suggestedActualWeight).orEmpty() else seed?.weight?.let(::weightText) ?: "") }
    var reps by rememberSaveable { mutableStateOf(if (runExercise != null) firstTarget?.repsMin?.toString().orEmpty() else seed?.reps?.toString() ?: "") }
    var distance by rememberSaveable { mutableStateOf(if (runExercise != null) "" else seed?.distance?.let(::weightText) ?: "") }
    var seconds by rememberSaveable { mutableStateOf(if (runExercise != null) "" else seed?.seconds?.let(::weightText) ?: "") }
    var unit by rememberSaveable { mutableStateOf(seed?.unit ?: "km") }
    var cardio by rememberSaveable { mutableStateOf(runExercise == null && seed?.cardio == true) }
    var mixed by rememberSaveable { mutableStateOf(runExercise == null && seed?.weight != null && (seed.distance != null || seed.seconds != null)) }
    var comment by rememberSaveable { mutableStateOf("") }
    var showComment by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var suggested by rememberSaveable { mutableStateOf(runExercise != null || seed != null) }
    var planSuggested by rememberSaveable { mutableStateOf(runExercise != null) }
    var userEdited by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val selectedRecord = exercise.sets.firstOrNull { it.key == selected }
    fun storeDraft() { draft = arrayListOf(weight, reps, distance, seconds, unit, comment, cardio.toString(), mixed.toString()) }
    fun cancelEdit() {
        selected = null
        if (draft.size == 8) {
            weight = draft[0]; reps = draft[1]; distance = draft[2]; seconds = draft[3]; unit = draft[4]; comment = draft[5]
            cardio = draft[6].toBoolean(); mixed = draft[7].toBoolean()
        }
        draft = arrayListOf()
    }
    fun choose(set: NotebookSet) {
        if (selected == null) storeDraft()
        selected = set.key; weight = set.weight?.let(::weightText) ?: ""; reps = set.reps?.toString() ?: ""
        distance = set.distance?.let(::weightText) ?: ""; seconds = set.seconds?.let(::weightText) ?: ""; unit = set.unit ?: "km"
        comment = set.comment; showComment = comment.isNotBlank(); cardio = set.cardio
        mixed = set.weight != null && (set.distance != null || set.seconds != null)
    }
    // History may arrive after the local workout. Fill an untouched draft once,
    // without overwriting a user's typing or an explicitly selected record.
    LaunchedEffect(runExercise?.entry?.id) {
        if (!planSuggested && runExercise != null && selected == null && !userEdited) {
            weight = firstTarget?.let(::suggestedActualWeight).orEmpty(); reps = firstTarget?.repsMin?.toString().orEmpty()
            planSuggested = true; suggested = true; cardio = false; mixed = false; distance = ""; seconds = ""
        }
    }
    LaunchedEffect(seed?.key) {
        if (runExercise == null && !suggested && selected == null && listOf(weight, reps, distance, seconds).all { it.isBlank() } && seed != null) {
            weight = seed.weight?.let(::weightText) ?: ""; reps = seed.reps?.toString() ?: ""
            distance = seed.distance?.let(::weightText) ?: ""; seconds = seed.seconds?.let(::weightText) ?: ""; unit = seed.unit ?: "km"
            cardio = seed.cardio; mixed = seed.weight != null && (seed.distance != null || seed.seconds != null); suggested = true
        }
    }
    val error = runCatching {
        if (!cardio) {
            if (session.imported != null && reps.trim() == "0") { require(optionalNonNegative(weight) != null) { "Enter a weight." } }
            else InputRules.parseSet(weight, reps)
        }
        if (cardio || mixed) {
            require(optionalNonNegative(distance) != null || optionalNonNegative(seconds) != null) { "Enter distance or time." }
            optionalNonNegative(distance); optionalNonNegative(seconds)
        }
    }.exceptionOrNull()?.message
    val untouched = listOf(weight, reps, distance, seconds).all { it.isBlank() }
    Column(Modifier.fillMaxSize()) {
        NotebookBar(exercise.name, onBack, "Back to workout") {
            IconButton(onClick = { switcher = true }) { Icon(Icons.AutoMirrored.Filled.List, "Switch") }
            OverflowMenu { close ->
                if (!editable) MenuAction("Edit workout") { close(); onEditWorkout() }
                if (editable && selected == null) MenuAction(if (cardio) "Log strength sets" else "Log cardio") {
                    close(); userEdited = true; cardio = !cardio; mixed = false; weight = ""; reps = ""; distance = ""; seconds = ""; suggested = true
                }
                if (editable) MenuAction(if (showComment) "Hide set note" else "Add set note") { close(); showComment = !showComment }
                if (editable && exercise.entryId != null) MenuAction("Remove exercise") { close(); remove = true }
            }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Log") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
        }
        if (tab == 1) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (data.imported.loading) item { Text("Loading history…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (previous.isEmpty() && !data.imported.loading) item { Text("No previous session.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(previous, key = { it.first.key }) { (workout, e) ->
                    Surface(onClick = { onHistory(workout.key) }, shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column {
                            Text(prettyDay(workout.date), Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                            val columns = setColumns(e.sets)
                            NotebookSetHeader(columns)
                            e.sets.forEachIndexed { index, set ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); NotebookSetRow(set, index + 1, columns = columns)
                                if (set.comment.isNotBlank()) Text(set.comment, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } else LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            if (run != null && run.run.warmUp.isNotBlank()) item { CollapsedGuidance("Warm-up", run.run.warmUp) }
            if (run != null && run.run.legacyGuidance.isNotBlank()) item { CollapsedGuidance("Legacy instructions", run.run.legacyGuidance) }
            if (runExercise != null) item { Box(Modifier.padding(bottom = 12.dp)) {
                RunSuggestions(runExercise, model, busy, editable) { target ->
                    if (selected != null) cancelEdit()
                    weight = suggestedActualWeight(target); reps = target.repsMin?.toString().orEmpty(); cardio = false; mixed = false; suggested = true; planSuggested = true
                }
            } }
            if (session.notes.startsWith(SESSION_PLAN_PREFIX)) item {
                Box(Modifier.padding(bottom = 12.dp)) { SessionPlanCard(session.notes, session.completed) }
            }
            if (exercise.name.length > 45) item {
                Text(exercise.name, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            item {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val last = previous.firstOrNull()
                    Text(if (last != null) "Previous · " + prettyDay(last.first.date) else if (data.imported.loading) "Loading previous session…" else "No previous session.",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (last != null) Text(compactResults(last.second.sets), style = MaterialTheme.typography.bodyMedium)
                }
                }
            }
            if (editable) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selectedRecord == null) "New set" else "Editing set " + (exercise.sets.indexOf(selectedRecord) + 1), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { showComment = !showComment }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(if (comment.isNotBlank()) "Note •" else "Add note") }
                    }
                    if (!cardio) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumericControl("Weight (kg)", weight, { weight = it; userEdited = true }, 2.5, false, busy, Modifier.weight(1f))
                        NumericControl("Reps", reps, { reps = it; userEdited = true }, 1.0, true, busy, Modifier.weight(1f))
                    }
                    if (cardio || mixed) {
                        UnitPicker(unit, { unit = it }, !busy)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NumericControl("Distance ($unit)", distance, { distance = it }, if (unit in listOf("m", "ft")) 10.0 else 0.1, false, busy, Modifier.weight(1f), maximum = Double.MAX_VALUE)
                            NumericControl("Time (seconds)", seconds, { seconds = it }, 30.0, false, busy, Modifier.weight(1f), maximum = Double.MAX_VALUE)
                        }
                    }
                    if (showComment) OutlinedTextField(comment, { comment = it.take(10000) }, label = { Text("Set note") },
                        modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !busy)
                    if (!untouched && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(shape = MaterialTheme.shapes.small, onClick = {
                        val done = { selected = null; draft = arrayListOf<String>(); focus.clearFocus(); keyboard?.hide(); Unit }
                        if (exercise.entryId != null) {
                            if (cardio) model.logCardio(exercise.entryId, distance, unit, seconds, comment, selectedRecord?.local?.id, done)
                            else model.logStrength(exercise.entryId, weight, reps, selectedRecord?.local?.id, comment, done,
                                if (mixed) distance else null, unit, if (mixed) seconds else null)
                        } else model.logImported(session.imported!!.workout.id, exercise.name, exercise.category, selectedRecord?.imported?.id,
                            if (cardio) "" else weight, if (cardio) "" else reps, if (cardio || mixed) distance else "", unit,
                            if (cardio || mixed) seconds else "", comment, done)
                    }, enabled = !busy && error == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (selectedRecord == null) "Save set" else "Update set")
                    }
                    if (selectedRecord != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { cancelEdit(); focus.clearFocus(); keyboard?.hide() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel edit") }
                        TextButton(onClick = {
                            if (selectedRecord.local != null) model.duplicateSet(selectedRecord.local.id) { selected = null; draft = arrayListOf() }
                            else { model.duplicateImportedRecord(selectedRecord.imported!!.id); selected = null; draft = arrayListOf() }
                        }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Duplicate") }
                        TextButton(onClick = { delete = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                    }
                }
            }
            val columns = setColumns(exercise.sets)
            item { Text("Recorded sets · ${exercise.sets.size}", Modifier.padding(top = 20.dp, bottom = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (exercise.sets.isNotEmpty()) item { NotebookSetHeader(columns) }
            if (exercise.sets.isEmpty()) item { Text("No sets yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(exercise.sets, key = { it.key }) { set ->
                NotebookSetRow(set, exercise.sets.indexOf(set) + 1, selected == set.key, if (editable && !busy) ({ choose(set) }) else null, columns = columns)
                if (!editable && set.comment.isNotBlank()) Text(set.comment, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (switcher) AlertDialog(onDismissRequest = { switcher = false }, title = { Text("Exercises") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) { items(session.exercises, key = { it.key }) { e ->
            TextButton(onClick = { switcher = false; onSwitch(e.key) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text((if (e.key == exercise.key) "✓ " else "") + e.name + " · " + e.sets.size + " sets")
            }
        } }
    }, confirmButton = { if (editable && session.local != null) TextButton(onClick = { switcher = false; onAdd() }) { Text("Add exercise") } },
        dismissButton = { TextButton(onClick = { switcher = false }) { Text("Close") } })
    if (delete && selectedRecord != null) NotebookConfirm("Delete set?", "The saved record will be removed.", "Delete", busy, { delete = false }) {
        if (selectedRecord.local != null) model.deleteSet(selectedRecord.local.id) { delete = false; cancelEdit() }
        else { model.deleteImportedRecord(selectedRecord.imported!!.id); delete = false; cancelEdit() }
    }
    if (remove && exercise.entryId != null) NotebookConfirm("Remove exercise?", "This also deletes its saved sets.", "Remove", busy, { remove = false }) {
        model.removeExercise(exercise.entryId) { remove = false; onBack() }
    }
}
