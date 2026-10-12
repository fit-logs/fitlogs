package com.example.fitnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FitNotesApp(model: FitNotesViewModel) {
    val data by model.data.collectAsState()
    val busy by model.busy.collectAsState()
    val pending by model.pendingRestore.collectAsState()
    val photoRestore by model.pendingPhotoRestore.collectAsState()
    var includePhotos by rememberSaveable { mutableStateOf(false) }
    val original by model.pendingImport.collectAsState()
    val already by model.previouslyImported.collectAsState()
    val localSessions = remember(data.workouts) { notebookSessions(AppData(workouts = data.workouts)) }
    val importedSessions = remember(data.imported.workouts) { notebookSessions(AppData(imported = data.imported)) }
    val sessions = remember(localSessions, importedSessions) { (localSessions + importedSessions).sortedWith(compareByDescending<NotebookSession> { it.date }.thenByDescending { it.local?.workout?.startedAt ?: 0L }.thenBy { it.key }) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var workoutDay by rememberSaveable { mutableStateOf(todayDate()) }
    var workoutKey by rememberSaveable { mutableStateOf<String?>(null) }
    var historyKey by rememberSaveable { mutableStateOf<String?>(null) }
    var historyDay by rememberSaveable { mutableStateOf<String?>(null) }
    var loggerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var exerciseKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editableKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreRevision by rememberSaveable { mutableIntStateOf(0) }
    var addingTo by rememberSaveable { mutableStateOf<String?>(null) }
    var activityEntry by remember { mutableStateOf(false) }
    var healthImport by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var native by rememberSaveable { mutableStateOf(true) }
    var backupMenu by remember { mutableStateOf(false) }
    var rangeMenu by remember { mutableStateOf(false) }
    var shareText by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val holder = rememberSaveableStateHolder()
    val historyState = rememberLazyListState()
    val bodyState = rememberLazyListState()
    fun openHistory(key: String) {
        val session = sessions.firstOrNull { it.key == key } ?: return
        loggerKey = null; exerciseKey = null; historyKey = key; historyDay = session.date; tab = 1; editableKey = null
    }
    fun startWorkout() { activityEntry = true }
    fun chooseSession(key: String?) {
        val session = sessions.firstOrNull { it.key == key }
        if (tab == 0) { workoutKey = key; session?.let { workoutDay = it.date } }
        else { historyKey = key; session?.let { historyDay = it.date } }
        editableKey = null
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::export) }
    val photoExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(model::exportPhotos) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::inspectBackup) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.inspectOriginal(uri, native) } }
    val analysis = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::exportAnalysis) }
    LaunchedEffect(backupMenu) { if (backupMenu) includePhotos = false }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(data.loading, data.active?.workout?.id, data.active?.workout?.startedAt) {
        if (!data.loading && data.active != null) {
            workoutKey = "local:" + data.active!!.workout.id; workoutDay = localSessions.firstOrNull { it.key == workoutKey }?.date ?: todayDate()
        }
    }
    BackHandler(enabled = addingTo != null || loggerKey != null || historyDay != null) {
        if (addingTo != null) addingTo = null
        else if (loggerKey != null) { loggerKey = null; exerciseKey = null }
        else { historyKey = null; historyDay = null; editableKey = null }
    }
    Scaffold(bottomBar = {
        if (loggerKey == null && addingTo == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            NavigationBarItem(colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer), selected = tab == 0, onClick = { tab = 0; historyDay = null; editableKey = null }, icon = { ExerciseSymbol() }, label = { Text("Workout") })
            NavigationBarItem(colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer), selected = tab == 1, onClick = { tab = 1; historyDay = null; historyKey = null; editableKey = null }, icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("History") })
            NavigationBarItem(colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer), selected = tab == 2, onClick = { tab = 2; historyDay = null; editableKey = null }, icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Body") })
        }
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (data.loading) {
                NotebookBar("FitNotes")
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            } else {
                val logSession = sessions.firstOrNull { it.key == loggerKey }
                val logExercise = logSession?.exercises?.firstOrNull { it.key == exerciseKey }
                val addingWorkout = sessions.firstOrNull { it.key == addingTo }?.local
                if (addingWorkout != null) {
                    val recent = sessions.flatMap { it.exercises }.map { it.name.lowercase(Locale.ROOT) }.distinct()
                    val categories = sessions.flatMap { it.exercises }.filter { it.category.isNotBlank() }.groupBy { it.name.lowercase(Locale.ROOT) }
                        .mapNotNull { (name, values) -> values.map { it.category }.distinct().singleOrNull()?.let { name to it } }.toMap()
                    ExercisePicker(data.exercises, addingWorkout.entries.map { it.exercise.id }.toSet(), recent, categories, busy, { addingTo = null },
                        { ids -> model.addExercises(addingWorkout.workout.id, ids) { addingTo = null } },
                        { name -> model.createExercise(addingWorkout.workout.id, name) { addingTo = null } })
                } else if (logSession != null && logExercise != null) holder.SaveableStateProvider("logger:$restoreRevision:${logSession.key}:${logExercise.key}") {
                    ExerciseLogger(logSession, logExercise, sessions, data, model, busy, !logSession.completed || editableKey == logSession.key,
                        onBack = { loggerKey = null; exerciseKey = null }, onSwitch = { exerciseKey = it }, onAdd = { addingTo = logSession.key },
                        onEditWorkout = { editableKey = logSession.key }, onHistory = ::openHistory)
                } else if (tab == 2) BodyScreen(data, model, busy, bodyState, { importMenu = true }, { backupMenu = true }, { shareText = it })
                else if (tab == 1 && historyDay == null) NotebookHistory(sessions, data, historyState, ::openHistory, { rangeMenu = true }, { importMenu = true }, { backupMenu = true })
                else {
                    val key = if (tab == 0) workoutKey else historyKey
                    val day = if (tab == 0) workoutDay else historyDay ?: todayDate()
                    val session = sessions.firstOrNull { it.key == key && it.date == day }
                    holder.SaveableStateProvider("overview:$restoreRevision:" + (session?.key ?: day)) {
                        val scroll = rememberLazyListState()
                        WorkoutOverview(session, day, sessions, data, model, busy, session != null && (!session.completed || editableKey == session.key), scroll,
                            onDay = { chosen ->
                                val found = sessions.firstOrNull { it.date == chosen }
                                if (tab == 0) { workoutDay = chosen; workoutKey = found?.key } else { historyDay = chosen; historyKey = found?.key }
                                editableKey = null
                            }, onSession = ::chooseSession, onExercise = { selected -> loggerKey = session?.key; exerciseKey = selected }, onStart = ::startWorkout,
                            onAdd = { addingTo = session?.key }, onEdit = { editableKey = session?.key }, onShare = { shareText = it },
                            onImport = { importMenu = true }, onBackup = { backupMenu = true },
                            onBack = if (tab == 1) ({ historyKey = null; historyDay = null; editableKey = null }) else null,
                            onDeleted = { workoutKey = null; historyKey = null; loggerKey = null; historyDay = null; editableKey = null },
                            onFinished = { editableKey = null; session?.let { openHistory(it.key) } },
                            onRepeat = {
                                if (session?.local != null) model.repeat(session.local.workout.id) { tab = 0; historyDay = null; workoutDay = todayDate(); workoutKey = null; editableKey = null }
                                else if (session?.imported != null) model.repeatImported(session.imported.workout.id) { tab = 0; historyDay = null; workoutDay = todayDate(); workoutKey = null; editableKey = null }
                            })
                    }
                }
            }
        }
    }
    if (activityEntry) ActivityEntryDialog(busy, model, { activityEntry = false }) { id ->
        activityEntry = false; workoutKey = "local:$id"; workoutDay = todayDate(); tab = 0; historyDay = null; loggerKey = null; editableKey = null
        // A manual past activity may have a different date.
        model.data.value.workouts.firstOrNull { it.workout.id == id }?.let { workoutDay = com.example.fitnotes.data.localWorkoutDate(it.workout) }
    }
    LaunchedEffect(data.workouts, workoutKey) { data.workouts.firstOrNull { "local:" + it.workout.id == workoutKey }?.let { workoutDay = com.example.fitnotes.data.localWorkoutDate(it.workout) } }
    if (healthImport) HealthImportDialog(model, data, busy) { healthImport = false }
    if (rangeMenu) ShareRangeDialog(data, { rangeMenu = false }) { shareText = it; rangeMenu = false }
    if (importMenu) AlertDialog(onDismissRequest = { importMenu = false }, title = { Text("Import history") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose an export, review the preview, then confirm.")
            TextButton(onClick = { importMenu = false; healthImport = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import activities (Health Connect)") }
            TextButton(onClick = { native = true; importMenu = false; importPicker.launch(arrayOf("*/*")) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Original FitNotes backup") }
            TextButton(onClick = { native = false; importMenu = false; importPicker.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel")) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Workout CSV") }
            Text("Body measurements can be imported from the original backup.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { importMenu = false }) { Text("Close") } })
    original?.let { document -> OriginalImportPreview(document, already, busy, model::cancelImport) { plan ->
        model.confirmImport(plan) { tab = 1; historyKey = null; historyDay = null; loggerKey = null }
    } }
    if (backupMenu) AlertDialog(onDismissRequest = { backupMenu = false }, title = { Text("Backup and restore") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(includePhotos, { includePhotos = it }, modifier = Modifier.semantics { contentDescription = "Include progress photos" })
                Text("Include progress photos", Modifier.weight(1f))
            }
            Text(if (includePhotos) "Includes original image files and photo details. Keep this unencrypted ZIP private." else "Progress photos are not in this backup. They remain on this device.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { backupMenu = false; if (includePhotos) photoExport.launch("fitnotes-photos-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".zip") else export.launch("fitnotes-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".json") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (includePhotos) "Export backup with photos" else "Export JSON backup") }
            TextButton(onClick = { backupMenu = false; restore.launch(arrayOf("application/json", "application/zip", "application/x-zip-compressed", "text/plain", "application/octet-stream")) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose backup to restore") }
            TextButton(onClick = { backupMenu = false; analysis.launch("fitnotes-analysis-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".json") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Export analysis JSON") }
        }
    }, confirmButton = { TextButton(onClick = { backupMenu = false }) { Text("Close") } })
    pending?.let { backup ->
        val dates = backup.workouts.map { com.example.fitnotes.data.localWorkoutDate(it) } + backup.importedWorkouts.map { it.date } + backup.bodyMeasurements.map { it.date }
        NotebookConfirm("Replace all training data?", "Validated backup v${backup.version}\nExported " +
            SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(backup.exportedAt)) + "\n${backup.exercises.size} exercises\n" +
            "${backup.workouts.count { it.isComplete }} completed workouts · ${backup.workouts.count { !it.isComplete }} unfinished\n" +
            "${backup.sets.size} local sets · ${backup.importedWorkouts.size} imported workouts · ${backup.importedSets.size} imported records\n" +
            "${backup.bodyMeasurements.size} body measurements\n" + (photoRestore?.let { "${it.photos.size} progress photos included; existing IDs will be updated.\n" } ?: "Photos are not included; existing photos will be kept.\n") + (if (dates.isEmpty()) "No dated records." else dates.min() + " to " + dates.max()) + "\nCurrent data will be replaced.",
            "Replace & restore", busy, model::cancelRestore) {
            model.confirmRestore {
                val active = backup.workouts.firstOrNull { !it.isComplete }
                restoreRevision++; workoutKey = active?.let { "local:" + it.id }
                workoutDay = active?.let { com.example.fitnotes.data.localWorkoutDate(it) } ?: todayDate()
                historyKey = null; historyDay = null; loggerKey = null; exerciseKey = null; editableKey = null; tab = 0
            }
        }
    }
    shareText?.let { text -> AlertDialog(onDismissRequest = { shareText = null }, title = { Text("Training context") },
        text = { LazyColumn(Modifier.heightIn(max = 430.dp)) { item { Text(text, style = MaterialTheme.typography.bodyMedium) } } },
        confirmButton = { TextButton(onClick = {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share training context")); shareText = null
        }) { Text("Share") } }, dismissButton = { Row {
            TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Training context", text)); shareText = null }) { Text("Copy") }
            TextButton(onClick = { shareText = null }) { Text("Close") }
        } }) }
}
