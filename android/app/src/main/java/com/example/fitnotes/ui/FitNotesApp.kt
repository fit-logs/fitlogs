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
import com.example.fitnotes.ImportedData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FitNotesApp(model: FitNotesViewModel) {
    val data by model.data.collectAsState()
    val busy by model.busy.collectAsState()
    val loadAdviceOpen by model.loadAdviceOpen.collectAsState()
    val healthSyncing by model.healthSyncing.collectAsState()
    val pending by model.pendingRestore.collectAsState()
    val photoRestore by model.pendingPhotoRestore.collectAsState()
    var includePhotos by rememberSaveable { mutableStateOf(false) }
    val original by model.pendingImport.collectAsState()
    val already by model.previouslyImported.collectAsState()
    val localSessions=remember(data.workouts){notebookSessions(AppData(exercises=data.exercises,workouts=data.workouts))}
    val importedSessions=remember(data.imported.workouts,data.exercises,data.exerciseAliases,data.exerciseMappings){notebookSessions(data.copy(workouts=emptyList()))}
    val sessions=remember(localSessions,importedSessions){sortNotebookSessions(localSessions+importedSessions)}
    val pastDrafts by model.pastDrafts.collectAsState()
    val draftSessions = remember(pastDrafts, data.exercises) { notebookSessions(data.copy(workouts = pastDrafts, imported = ImportedData()), includeDrafts = true) }
    val editingSessions = remember(sessions, draftSessions) { (sessions + draftSessions).distinctBy { it.key } }
    var pastSetup by rememberSaveable { mutableStateOf(false) }
    var pastSetupDate by rememberSaveable { mutableStateOf(moveDay(todayDate(), -1)) }
    var timingWorkoutId by rememberSaveable { mutableStateOf<Long?>(null) }
    var discardPastId by rememberSaveable { mutableStateOf<Long?>(null) }
    var library by rememberSaveable { mutableStateOf(false) }
    var progress by rememberSaveable { mutableStateOf(false) }
    var trainingSettings by rememberSaveable { mutableStateOf(false) }
    val summary by model.completionSummary.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var workoutDay by rememberSaveable { mutableStateOf(todayDate()) }
    var workoutKey by rememberSaveable { mutableStateOf<String?>(null) }
    var browsingWorkoutDay by rememberSaveable { mutableStateOf(false) }
    var historyKey by rememberSaveable { mutableStateOf<String?>(null) }
    var historyDay by rememberSaveable { mutableStateOf<String?>(null) }
    var loggerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var exerciseKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editableKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreRevision by rememberSaveable { mutableIntStateOf(0) }
    var addingTo by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var resolvingStart by rememberSaveable { mutableStateOf(false) }
    var requestedPlanId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingActiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var discardActiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var discardForStart by rememberSaveable { mutableStateOf(false) }
    var finishActiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var activityEntry by remember { mutableStateOf(false) }
    var healthImport by remember { mutableStateOf(false) }
    var stravaExport by rememberSaveable { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var native by rememberSaveable { mutableStateOf(true) }
    var backupMenu by remember { mutableStateOf(false) }
    var rangeMenu by remember { mutableStateOf(false) }
    var shareText by remember { mutableStateOf<String?>(null) }
    var askChatGpt by rememberSaveable { mutableStateOf(false) }
    var programImport by rememberSaveable { mutableStateOf(false) }
    var programsMenu by rememberSaveable { mutableStateOf(false) }
    val programRequestVersion by model.programImportRequestVersion.collectAsState()
    LaunchedEffect(model,programRequestVersion) { if(model.consumeProgramImportRequest(programRequestVersion))programImport=true }
    var pastePlan by rememberSaveable { mutableStateOf(false) }
    var plansMenu by rememberSaveable { mutableStateOf(false) }
    var selectedPlan by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val holder = rememberSaveableStateHolder()
    val appearance=com.example.fitnotes.ui.theme.LocalAppearance.current
    val historyState = rememberLazyListState()
    val bodyState = rememberLazyListState()
    fun openHistory(key: String) {
        val session = sessions.firstOrNull { it.key == key } ?: return
        loggerKey = null; exerciseKey = null; historyKey = key; historyDay = session.date; tab = 1; editableKey = null
    }
    fun enterWorkout(id: Long, openPicker: Boolean = false, dateOverride: String? = null) {
        browsingWorkoutDay = false
        workoutKey = "local:$id"; workoutDay = dateOverride ?: (model.data.value.workouts + model.pastDrafts.value).firstOrNull { it.workout.id == id }?.let { com.example.fitnotes.data.activitySummary(it).date } ?: todayDate()
        tab = 0; historyDay = null; loggerKey = null; exerciseKey = null; editableKey = null
        plansMenu = false; pastePlan = false; selectedPlan = null
        addingTo = if (openPicker) "local:$id" else null
    }
    fun savePast(id: Long) {
        val date = pastDrafts.firstOrNull { it.workout.id == id }?.workout?.let(::localWorkoutDate) ?: workoutDay
        model.savePastWorkout(id) { tab = 1; historyKey = "local:$id"; historyDay = date; workoutKey = null; loggerKey = null; exerciseKey = null; addingTo = null; editableKey = "local:$id" }
    }
    fun editTime(id: Long) { model.workoutTimeError.value = null; timingWorkoutId = id }
    fun openPastOn(date: String) { model.workoutTimeError.value = null; pastSetupDate = date; pastSetup = true }
    fun openPast() { openPastOn(moveDay(todayDate(), -1)) }
    fun requestStart(planId: String? = null) {
        if (busy) return
        val active = data.active
        if (active != null) {
            if (planId != null && model.planRuns.value.any { it.run.workoutId == active.workout.id && it.run.planId == planId }) {
                enterWorkout(active.workout.id); return
            }
            requestedPlanId = planId; pendingActiveId = active.workout.id; resolvingStart = true
            plansMenu = false; pastePlan = false
        } else if (planId == null) model.startEmpty { enterWorkout(it, true) }
        else model.startPlan(planId) { enterWorkout(it) }
    }
    fun startWorkout() { requestStart() }
    fun chooseSession(key: String?) {
        val session = editingSessions.firstOrNull { it.key == key }
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
    BackHandler(enabled = addingTo != null || loggerKey != null || historyDay != null || (tab == 0 && (workoutKey != null || browsingWorkoutDay))) {
        if (addingTo != null) addingTo = null
        else if (loggerKey != null) { loggerKey = null; exerciseKey = null }
        else if (tab == 0 && (workoutKey != null || browsingWorkoutDay)) { workoutKey = null; browsingWorkoutDay = false; editableKey = null }
        else { historyKey = null; historyDay = null; editableKey = null }
    }
    Scaffold(bottomBar = {
        if (loggerKey == null && addingTo == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            NavigationBarItem(colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer), selected = tab == 0, onClick = { tab = 0; workoutKey = null; browsingWorkoutDay = false; historyDay = null; editableKey = null }, icon = { ExerciseSymbol() }, label = { Text("Train") })
            NavigationBarItem(colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer), selected = tab == 1, onClick = { tab = 1; historyDay = null; historyKey = null; editableKey = null }, icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("History") })
            NavigationBarItem(colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer), selected = tab == 2, onClick = { tab = 2; historyDay = null; editableKey = null }, icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Body") })
        }
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (busy || healthSyncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (data.loading) {
                NotebookBar("FitNotes")
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            } else if (data.storageError != null) {
                NotebookBar("FitNotes")
                Text(data.storageError!!, Modifier.padding(24.dp))
            } else {
                val logSession = editingSessions.firstOrNull { it.key == loggerKey }
                val logExercise = logSession?.exercises?.firstOrNull { it.key == exerciseKey }
                val addingWorkout = editingSessions.firstOrNull { it.key == addingTo }?.local
                if (addingWorkout != null) {
                    val recent = sessions.flatMap { it.exercises }.map { it.name.lowercase(Locale.ROOT) }.distinct()
                    val categories = sessions.flatMap { it.exercises }.filter { it.category.isNotBlank() }.groupBy { it.name.lowercase(Locale.ROOT) }
                        .mapNotNull { (name, values) -> values.map { it.category }.distinct().singleOrNull()?.let { name to it } }.toMap()
                    ExercisePicker(data.exercises, addingWorkout.entries.map { it.exercise.id }.toSet(), recent, categories, busy, { addingTo = null },
                        { ids -> model.addExercisesAndOpen(addingWorkout.workout.id, ids) { entry ->
                            addingTo = null
                            if (entry != null) { loggerKey = "local:${addingWorkout.workout.id}"; exerciseKey = "entry:$entry" }
                        } },
                        { name -> model.createExercise(addingWorkout.workout.id, name) { addingTo = null } }, aliases=data.exerciseAliases)
                } else if (logSession != null && logExercise != null) holder.SaveableStateProvider("logger:$restoreRevision:${logSession.key}:${logExercise.key}") {
                    ExerciseLogger(logSession, logExercise, sessions, data, model, busy, !logSession.completed || editableKey == logSession.key,
                        onBack = { loggerKey = null; exerciseKey = null }, onSwitch = { exerciseKey = it }, onAdd = { addingTo = logSession.key },
                        onEditWorkout = { editableKey = logSession.key }, onHistory = ::openHistory, onSavePast = { logSession.local?.let { savePast(it.workout.id) } })
                } else if (tab == 2) BodyScreen(data, model, busy, bodyState, { importMenu = true }, { backupMenu = true }, { shareText = it })
                else if (tab == 1 && historyDay == null) NotebookHistory(sessions, data, historyState, ::openHistory, { rangeMenu = true }, { importMenu = true }, { backupMenu = true },
                    onAsk = { askChatGpt = true }, onPastePlan = { pastePlan = true }, onPlans = { selectedPlan = null; plansMenu = true },onProgress={progress=true},onLibrary={library=true},onTrainingSettings={trainingSettings=true},onPast=::openPast)
                else if (tab == 0 && workoutKey == null && !browsingWorkoutDay) CompositionLocalProvider(LocalSettingsAction provides { settings = true }) {
                    TrainScreen(data, model, busy,
                        onResume = { data.active?.let { enterWorkout(it.workout.id) } }, onStart = ::startWorkout,
                        onFinish = { finishActiveId = data.active?.workout?.id },
                        onDiscard = { discardActiveId = data.active?.workout?.id; discardForStart = false },
                        onPast = ::openPast, onDraft = { id -> enterWorkout(id) }, onEditTime = ::editTime, onResumeFinished = { id -> model.resumeFinishedWorkout(id) { enterWorkout(id) } }, onDiscardDraft = { discardPastId = it },
                        onProgramStart = { id -> model.startProgramDay(id) { enterWorkout(it) } },
                        onImportProgram = { model.cancelProgramImport(); programImport = true },
                        onPlan = { selectedPlan = it; plansMenu = true }, onNew = { model.newPlan(); pastePlan = true },
                        onAsk = { askChatGpt = true }, onImport = { model.cancelPlanImport(); pastePlan = true })
                }
                else {
                    val key = if (tab == 0) workoutKey else historyKey
                    val day = if (tab == 0) workoutDay else historyDay ?: todayDate()
                    val visibleSessions = if(tab == 0) editingSessions else sessions
                    val session = visibleSessions.firstOrNull { it.key == key && it.date == day }
                    holder.SaveableStateProvider("overview:$restoreRevision:" + (session?.key ?: day)) {
                        val scroll = rememberLazyListState()
                        WorkoutOverview(session, day, visibleSessions, data, model, busy, session != null && (!session.completed || editableKey == session.key), scroll,
                            onDay = { chosen ->
                                val found = visibleSessions.firstOrNull { it.date == chosen }
                                if (tab == 0) { workoutDay = chosen; workoutKey = found?.key; browsingWorkoutDay = true } else { historyDay = chosen; historyKey = found?.key }
                                editableKey = null
                            }, onSession = ::chooseSession, onExercise = { selected -> loggerKey = session?.key; exerciseKey = selected }, onStart = ::startWorkout,
                            onAdd = { addingTo = session?.key }, onEdit = { editableKey = session?.key }, onShare = { shareText = it },
                            onImport = { importMenu = true }, onHealthImport = { healthImport = true }, onBackup = { backupMenu = true },
                            onSavePast = { session?.local?.let { savePast(it.workout.id) } }, onEditTime = { session?.local?.let { editTime(it.workout.id) } },
                            onLogPast = { openPastOn(day) },
                            onUseAsPlan = { session?.let { model.planFromSession(it); pastePlan = true } },
                            onProgress={progress=true},onLibrary={library=true},onTrainingSettings={trainingSettings=true},
                            onRecoverPlan = { id -> model.recoverEmptySuggestion(id) { pastePlan = true } }, onChatGPT = { askChatGpt = true }, onPastePlan = { pastePlan = true }, onPlans = { selectedPlan = null; plansMenu = true },
                            onBack = if (tab == 1) ({ historyKey = null; historyDay = null; editableKey = null }) else ({ workoutKey = null; browsingWorkoutDay = false; editableKey = null }),
                            onDeleted = { workoutKey = null; browsingWorkoutDay = false; historyKey = null; loggerKey = null; historyDay = null; editableKey = null },
                            onFinished = { editableKey = null; session?.let { openHistory(it.key) } },
                            onRepeat = {
                                if (session?.local != null) model.repeat(session.local.workout.id) { tab = 0; historyDay = null; workoutDay = todayDate(); workoutKey = null; browsingWorkoutDay = false; editableKey = null }
                                else if (session?.imported != null) model.repeatImported(session.imported.workout.id) { tab = 0; historyDay = null; workoutDay = todayDate(); workoutKey = null; browsingWorkoutDay = false; editableKey = null }
                            })
                    }
                }
            }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton(onClick = { settings = false; trainingSettings = true }) { Text("Training") }
            TextButton(onClick = { settings = false; healthImport = true }) { Text("Garmin via Health Connect") }
            TextButton(onClick = { settings = false; stravaExport = true }) { Text("Strava activity files") }
            TextButton(onClick = { settings = false; importMenu = true }) { Text("Import history") }
            TextButton(onClick = { settings = false; backupMenu = true }) { Text("Backup and restore") }
            TextButton(onClick = { settings = false; library = true }) { Text("Exercise library") }
            TextButton(onClick = { settings = false; progress = true }) { Text("Strength progress") }
            TextButton(onClick = { settings = false; activityEntry = true }) { Text("Log another activity") }
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    val pendingActive = data.workouts.firstOrNull { it.workout.id == pendingActiveId }
    if (resolvingStart) AlertDialog(onDismissRequest = { if (!busy) resolvingStart = false }, title = { Text("Workout already in progress") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Choose what to do with your current workout before starting another.")
            TextButton(onClick = { resolvingStart = false; pendingActive?.let { enterWorkout(it.workout.id) } }, enabled = !busy && pendingActive != null) { Text("Resume") }
            TextButton(onClick = {
                pendingActiveId?.let { id -> model.resolveActiveAndStart(id, ActiveWorkoutAction.FINISH, requestedPlanId) { next -> resolvingStart = false; enterWorkout(next, requestedPlanId == null) } }
            }, enabled = !busy && pendingActive != null && (pendingActive.workout.activityType != "strength" || pendingActive.entries.any { it.sets.isNotEmpty() })) { Text("Finish and start") }
            TextButton(onClick = { discardActiveId = pendingActiveId; discardForStart = true; resolvingStart = false }, enabled = !busy && pendingActive != null) { Text("Discard and start") }
            if (pendingActive?.workout?.activityType == "strength" && pendingActive.entries.all { it.sets.isEmpty() }) Text("There are no saved sets to finish. Resume or discard this empty workout.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { resolvingStart = false }, enabled = !busy) { Text("Cancel") } })
    finishActiveId?.let { id -> NotebookConfirm("Finish workout?", "Only saved sets will be included.", "Finish", busy, { finishActiveId = null }) {
        model.finish(id) { finishActiveId = null; workoutKey = null; browsingWorkoutDay = false }
    } }
    discardActiveId?.let { id -> NotebookConfirm("Discard workout?", "This deletes this workout and its saved sets. Saved plans are kept.", "Discard", busy, { discardActiveId = null }) {
        if (discardForStart) model.resolveActiveAndStart(id, ActiveWorkoutAction.DISCARD, requestedPlanId) { next -> discardActiveId = null; enterWorkout(next, requestedPlanId == null) }
        else model.deleteWorkout(id) { discardActiveId = null; workoutKey = null; browsingWorkoutDay = false }
    } }
    if(trainingSettings)TrainingSettingsDialog(model,busy){trainingSettings=false}
    if(library)ExerciseLibraryScreen(model,data,busy){library=false}
    if(progress)StrengthProgressScreen(data,model,busy,{progress=false;library=true}){progress=false}
    summary?.let { text->AlertDialog(onDismissRequest={model.completionSummary.value=null},title={Text("Workout complete")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text(text)}},confirmButton={TextButton(onClick={model.completionSummary.value=null}){Text("Done")}}) }
    if (activityEntry) ActivityEntryDialog(busy, model, { activityEntry = false }) { id ->
        activityEntry = false; workoutKey = "local:$id"; browsingWorkoutDay = false; workoutDay = todayDate(); tab = 0; historyDay = null; loggerKey = null; editableKey = null
        // A manual past activity may have a different date.
        model.data.value.workouts.firstOrNull { it.workout.id == id }?.let { workoutDay = com.example.fitnotes.data.activitySummary(it).date }
    }
    LaunchedEffect(data.workouts, pastDrafts, workoutKey) { (data.workouts + pastDrafts).firstOrNull { "local:" + it.workout.id == workoutKey }?.let { workoutDay = com.example.fitnotes.data.activitySummary(it).date } }
    LaunchedEffect(sessions, historyKey) { sessions.firstOrNull { it.key == historyKey }?.let { historyDay = it.date } }
    if (pastSetup) PastWorkoutSetupDialog(data, model, busy, { pastSetup = false }, initialDate = pastSetupDate) { id, date, picker -> pastSetup = false; enterWorkout(id, picker, date) }
    timingWorkoutId?.let { id -> (data.workouts + pastDrafts).firstOrNull { it.workout.id == id }?.let { detail -> WorkoutTimeDialog(detail.workout, model, busy) { timingWorkoutId = null } } }
    discardPastId?.let { id -> NotebookConfirm("Discard past workout draft?", "This removes the unsaved draft and its draft sets.", "Discard", busy, { discardPastId = null }) {
        model.deleteWorkout(id) { discardPastId = null; if(workoutKey == "local:$id"){workoutKey = null;browsingWorkoutDay = false;loggerKey = null;exerciseKey = null} }
    } }
    if (stravaExport) StravaExportDialog(model, data) { stravaExport = false }
    if (healthImport) HealthImportDialog(model, data, busy) { healthImport = false }
    if (askChatGpt) AskChatGptDialog(model, data, busy) { askChatGpt = false }
    if(loadAdviceOpen) LoadAdviceDialog(model,data,busy)
    if(programImport) ProgramImportDialog(model,data,busy,{programImport=false}) { programImport=false;programsMenu=true }
    if(programsMenu) ProgramsDialog(model,data,busy,{programsMenu=false},{id->programsMenu=false;model.startProgramDay(id){enterWorkout(it)}},{id->programsMenu=false;selectedPlan=id;plansMenu=true},importProgram={programsMenu=false;programImport=true})
    if (pastePlan) PastePlanDialog(model, data, busy, { pastePlan = false }, onStart = { requestStart(it) }) { id ->
        pastePlan = false; selectedPlan = id; plansMenu = true
    }
    if (plansMenu) PlannedWorkoutsDialog(model, data, busy, selectedPlan, { selectedPlan = it }, { plansMenu = false; selectedPlan = null },
        onPaste = { model.cancelPlanImport(); plansMenu = false; pastePlan = true },
        onEdit = { id -> model.editSavedPlan(id) { plansMenu = false; pastePlan = true } },
        onStart = { requestStart(it) }, onOpenWorkout = { id ->
            plansMenu = false; selectedPlan = null
            if (model.data.value.workouts.firstOrNull { it.workout.id == id }?.workout?.isComplete == true) openHistory("local:$id")
            else enterWorkout(id)
        })
    if (rangeMenu) ShareRangeDialog(data, { rangeMenu = false }) { shareText = it; rangeMenu = false }
    if (importMenu) AlertDialog(onDismissRequest = { importMenu = false }, title = { Text("Import history") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose an export, review the preview, then confirm.")
            TextButton(onClick = { importMenu = false; stravaExport = true }) { Text("Strava activity files") }
            TextButton(onClick = { importMenu = false; pastePlan = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Paste plan") }
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
        val dates = backup.workouts.map { com.example.fitnotes.data.localWorkoutDate(it) } + backup.importedWorkouts.map { it.date } + backup.bodyMeasurements.map { it.date } + (photoRestore?.photos?.map { it.date } ?: emptyList())
        NotebookConfirm("Replace all training data?", "Validated backup v${backup.version}\nExported " +
            SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(backup.exportedAt)) + "\n${backup.exercises.size} exercises\n" +
            "${backup.workouts.count { it.isComplete }} completed workouts · ${backup.workouts.count { !it.isComplete }} unfinished\n" +
            "${backup.sets.size} local sets · ${backup.importedWorkouts.size} imported workouts · ${backup.importedSets.size} imported records\n" +
            "${backup.plannedWorkouts.size} plans · targets separate from training\n" +
            "${backup.bodyMeasurements.size} body measurements\n" + (photoRestore?.let { "${it.photos.size} progress photos included; existing IDs will be updated.\n" } ?: "Photos are not included; existing photos will be kept.\n") + (if (dates.isEmpty()) "No dated records." else dates.min() + " to " + dates.max()) + "\nCurrent data will be replaced.",
            "Replace & restore", busy, model::cancelRestore) {
            model.confirmRestore {
                val active = backup.workouts.firstOrNull { !it.isComplete }
                restoreRevision++;appearance.refreshFavourites(); workoutKey = active?.let { "local:" + it.id }
                browsingWorkoutDay = false
                workoutDay = active?.let { com.example.fitnotes.data.localWorkoutDate(it) } ?: todayDate()
                historyKey = null; historyDay = null; loggerKey = null; exerciseKey = null; editableKey = null; tab = 0
            }
        }
    }
    shareText?.let { text ->
        AlertDialog(onDismissRequest = { shareText = null }, title = { Text("Training context") },
        text = { LazyColumn(Modifier.heightIn(max = 430.dp)) {
            item { Text(text, style = MaterialTheme.typography.bodyMedium) }
        } },
        confirmButton = { TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Training context", text))
            shareText = null; scope.launch { snackbar.showSnackbar("Training context copied.") }
        }) { Text("Copy") } }, dismissButton = { FlowRow {
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share training context")); shareText = null
            }) { Text("Share") }
            TextButton(onClick = { shareText = null }) { Text("Close") }
        } }) }
}
