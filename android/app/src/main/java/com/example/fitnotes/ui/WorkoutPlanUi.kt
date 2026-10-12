package com.example.fitnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun PastePlanDialog(model: FitNotesViewModel, data: AppData, busy: Boolean, onClose: () -> Unit, onSaved: (String) -> Unit) {
    val raw by model.planImportText.collectAsState()
    val draft by model.planEdit.collectAsState()
    val error by model.planImportError.collectAsState()
    val duplicate by model.duplicatePlanId.collectAsState()
    val preferences by model.handoffPreferences.collectAsState()
    var fallbackDate by rememberSaveable { mutableStateOf(model.handoffPreferences.value.value.lastTargetDate.ifBlank { tomorrowDate() }) }
    var dateInitialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(preferences.loading) {
        if (!preferences.loading && !dateInitialized) {
            fallbackDate = preferences.value.lastTargetDate.ifBlank { tomorrowDate() }; dateInitialized = true
        }
    }
    var resolving by rememberSaveable { mutableStateOf<String?>(null) }
    var formatCopied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val incompatible = remember(data) { incompatiblePlanExercises(data.exercises, data.workouts.flatMap { it.entries.map { e -> e.entry } },
        data.workouts.flatMap { it.entries.flatMap { e -> e.sets } }, data.imported.workouts.flatMap { it.sets }) }
    fun modify(change: (EditablePlan) -> EditablePlan) { model.planEdit.value?.let { model.planEdit.value = change(it) }; model.planImportError.value = null }
    fun target(key: String, change: (EditablePlanExercise) -> EditablePlanExercise) = modify { p -> p.copy(exercises = p.exercises.map { if (it.key == key) change(it) else it }) }
    fun move(key: String, amount: Int) = modify { p ->
        val index = p.exercises.indexOfFirst { it.key == key }
        if (index < 0 || index + amount !in p.exercises.indices) p else p.copy(exercises = p.exercises.toMutableList().apply { add(index + amount, removeAt(index)) })
    }
    fun close() { model.cancelPlanImport(); onClose() }
    val resolved = draft?.exercises?.all { (it.createName != null) || (it.exerciseId != null && it.exerciseId !in incompatible && data.exercises.any { e -> e.id == it.exerciseId }) } == true
    HandoffScreen(if (draft == null) "Paste plan" else "Review planned workout", busy, ::close, "plan-import-list", attention = error, footer = {
        if (draft == null) Button(onClick = { model.previewPlan(fallbackDate); focus.clearFocus(); keyboard?.hide() }, enabled = !busy && raw.isNotBlank() && !data.imported.loading && !preferences.loading,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Preview plan") }
        else {
            Button(onClick = { model.savePlan(onSaved = onSaved); focus.clearFocus(); keyboard?.hide() }, enabled = !busy && resolved && draft!!.exercises.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Save planned workout") }
            TextButton(onClick = { model.planEdit.value = null; model.planImportError.value = null }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit pasted text") }
        }
        TextButton(onClick = ::close, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    }) {
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        val p = draft
        if (p == null) {
            item { Text("Paste one WORKOUT_PLAN_V1 strength plan. Surrounding explanation and a Markdown code fence are supported.") }
            item { OutlinedTextField(fallbackDate, { fallbackDate = it; dateInitialized = true }, label = { Text("Date when omitted (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clip = clipboard.primaryClip
                        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
                        when {
                            text.isNullOrBlank() -> model.planImportError.value = "Copy the plan reply first, or enter it below."
                            text.length > WorkoutPlanParser.MAX_INPUT -> model.planImportError.value = "Paste one plan of at most 64,000 characters."
                            else -> { model.planImportText.value = text; model.planImportError.value = null }
                        }
                    }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Paste") }
                    TextButton(onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Workout plan format", nextWorkoutInstructions(fallbackDate)))
                        formatCopied = true
                    }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy response format") }
                }
                if (formatCopied) Text("Response format copied.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(raw, {
                    if (it.length <= WorkoutPlanParser.MAX_INPUT) { model.planImportText.value = it; model.planImportError.value = null }
                    else model.planImportError.value = "Paste one plan of at most 64,000 characters."
                }, label = { Text("Plan reply") }, minLines = 7, maxLines = 14, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
            item { Text("Preview and Cancel leave training data unchanged. Saving creates a planned workout; starting and recording results are separate actions.", style = MaterialTheme.typography.bodySmall) }
        } else {
            item { Text("Planned · targets only", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            item { OutlinedTextField(p.title, { value -> modify { it.copy(title = value) } }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), enabled = !busy) }
            item { OutlinedTextField(p.date, { value -> modify { it.copy(date = value) } }, label = { Text("Scheduled date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy) }
            item { OutlinedTextField(p.minutes, { value -> modify { it.copy(minutes = value) } }, label = { Text("Time budget (minutes)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), enabled = !busy) }
            item { OutlinedTextField(p.note, { value -> modify { it.copy(note = value) } }, label = { Text("Session note") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(), enabled = !busy) }
            item { Text("Select the intended movement for unresolved names. The app does not guess abbreviations or combine movement variants.", style = MaterialTheme.typography.bodySmall) }
            items(p.exercises, key = { it.key }) { e ->
                val index = p.exercises.indexOf(e)
                val match = matchPlanExercise(e.sourceName, data.exercises, incompatible)
                val selected = data.exercises.firstOrNull { it.id == e.exerciseId }
                Surface(modifier = Modifier.testTag("plan-row:${e.key}"), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. ${e.sourceName}", style = MaterialTheme.typography.titleMedium)
                        val label = when {
                            e.createName != null -> "New strength exercise: ${e.createName} (created on save)"
                            selected != null && selected.id !in incompatible -> "Matched: ${selected.name}"
                            match.kind == ExerciseMatchKind.INCOMPATIBLE -> "Recorded as cardio only · select a compatible strength exercise"
                            match.kind == ExerciseMatchKind.AMBIGUOUS -> "Ambiguous name · choose the intended exercise"
                            else -> "Unknown name · select or explicitly create a strength exercise"
                        }
                        Text(label, color = if (e.createName != null || (selected != null && selected.id !in incompatible)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { resolving = e.key }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Resolve exercise") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(e.sets, { value -> target(e.key) { it.copy(sets = value) } }, label = { Text("Sets") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), enabled = !busy)
                            OutlinedTextField(e.reps, { value -> target(e.key) { it.copy(reps = value) } }, label = { Text("Reps / range") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                        }
                        OutlinedTextField(e.weight, { value -> target(e.key) { it.copy(weight = value) } }, label = { Text("Suggested weight (optional)") },
                            supportingText = { Text("kg or lb, bodyweight, or blank for unspecified") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        OutlinedTextField(e.note, { value -> target(e.key) { it.copy(note = value) } }, label = { Text("Exercise note") }, minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { move(e.key, -1) }, enabled = !busy && index > 0) { Text("Move up") }
                            TextButton(onClick = { move(e.key, 1) }, enabled = !busy && index < p.exercises.lastIndex) { Text("Move down") }
                            TextButton(onClick = { modify { it.copy(exercises = it.exercises.filter { row -> row.key != e.key }) } }, enabled = !busy) { Text("Remove") }
                        }
                    }
                }
            }
            if (!resolved) item { Text("Resolve all exercise names before saving.", color = MaterialTheme.colorScheme.error) }
        }
    }
    resolving?.let { key ->
        val p = draft
        if (p != null) Dialog(onDismissRequest = { if (!busy) resolving = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
                ExercisePicker(data.exercises.filter { it.id !in incompatible }, p.exercises.filter { it.key != key }.mapNotNull { it.exerciseId }.toSet(),
                    notebookSessions(data).flatMap { it.exercises }.map { normaliseExerciseName(it.name) }.distinct(), emptyMap(), busy, { resolving = null },
                    { ids -> ids.singleOrNull()?.let { id -> target(key) { it.copy(exerciseId = id, createName = null) }; resolving = null } },
                    { name ->
                        try { val valid = InputRules.exerciseName(name); target(key) { it.copy(exerciseId = null, createName = valid) }; resolving = null }
                        catch (failure: IllegalArgumentException) { model.planImportError.value = failure.message; resolving = null }
                    }, singleSelection = true)
            }
        }
    }
    duplicate?.let { id -> AlertDialog(onDismissRequest = { if (!busy) model.duplicatePlanId.value = null }, title = { Text("Plan already saved") },
        text = { Text("A plan from this pasted block is already saved. Open it, or deliberately save another copy.") },
        confirmButton = { TextButton(onClick = { model.cancelPlanImport(); onSaved(id) }, enabled = !busy) { Text("Open existing plan") } },
        dismissButton = { FlowRow {
            TextButton(onClick = { model.savePlan(allowDuplicate = true, onSaved = onSaved) }, enabled = !busy) { Text("Save another copy") }
            TextButton(onClick = { model.duplicatePlanId.value = null }, enabled = !busy) { Text("Cancel") }
        } }) }
}

@Composable
fun PlannedWorkoutsDialog(model: FitNotesViewModel, data: AppData, busy: Boolean, selectedId: String?, onSelect: (String?) -> Unit,
    onClose: () -> Unit, onPaste: () -> Unit, onStart: (String) -> Unit, onOpenWorkout: (Long) -> Unit) {
    val plans by model.plans.collectAsState()
    val selected = plans.firstOrNull { it.plan.id == selectedId }
    val error by model.planImportError.collectAsState()
    var startReady by remember(selected?.plan?.id) { mutableStateOf(false) }
    LaunchedEffect(selected?.plan?.id) {
        selected?.let { kotlinx.coroutines.delay((1500 - (System.currentTimeMillis() - it.plan.createdAt)).coerceAtLeast(0)); startReady = true }
    }
    var deleting by remember { mutableStateOf(false) }
    HandoffScreen(if (selectedId == null) "Planned workouts" else "Saved plan", busy, { if (selectedId == null) onClose() else onSelect(null) }, "saved-plans-list", attention = error, footer = {
        if (selected != null) {
            val started = selected.plan.startedWorkoutId
            if (started == null) Button(onClick = { onStart(selected.plan.id) }, enabled = startReady && !busy && data.active == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start workout from plan") }
            else Button(onClick = { onOpenWorkout(started) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open logged workout") }
            TextButton(onClick = { deleting = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete plan") }
        }
        TextButton(onClick = onPaste, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Paste plan") }
        TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
    }) {
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (selectedId == null) {
            item { Text("Plans hold suggested targets. Completed training stays in History.", style = MaterialTheme.typography.bodyMedium) }
            if (plans.isEmpty()) item { Text("No planned workouts yet. Ask ChatGPT, then paste and review a WORKOUT_PLAN_V1 reply.") }
            items(plans, key = { it.plan.id }) { plan ->
                Surface(onClick = { onSelect(plan.plan.id) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(plan.plan.title, style = MaterialTheme.typography.titleMedium)
                        Text("${plan.plan.scheduledDate} · ${plan.plan.durationMinutes} min · ${plan.exercises.size} exercises")
                        Text(if (plan.plan.startedWorkoutId == null) "Planned · targets only" else "Started · targets retained as reference", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } else if (selected == null) item { Text("Loading saved plan…") }
        else {
            item {
                Text(selected.plan.title, style = MaterialTheme.typography.headlineSmall)
                Text("Planned for ${selected.plan.scheduledDate} · ${selected.plan.durationMinutes} min", style = MaterialTheme.typography.bodyMedium)
                Text("Targets only · no completed sets", color = MaterialTheme.colorScheme.primary)
                if (selected.plan.notes.isNotBlank()) Text(selected.plan.notes)
            }
            items(selected.ordered, key = { it.target.id }) { exercise -> TargetReference(exercise.prescription) }
            if (data.active != null && selected.plan.startedWorkoutId == null) item { Text("Finish or delete the unfinished workout before starting this plan.", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (deleting && selected != null) NotebookConfirm("Delete planned workout?", "Plan targets will be removed. Any recorded workout and actual sets remain.", "Delete", busy, { deleting = false }) {
        model.deletePlan(selected.plan.id) { deleting = false; onSelect(null) }
    }
}

@Composable
fun TargetReference(target: PlanPrescription) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(target.name, style = MaterialTheme.typography.titleMedium)
            Text(target.targetText, style = MaterialTheme.typography.bodyLarge)
            if (target.note.isNotBlank()) Text(target.note)
        }
    }
}

@Composable
fun PlanReference(plan: PlannedWorkoutDetail, exerciseId: Long? = null) {
    var expanded by rememberSaveable(plan.plan.id, exerciseId) { mutableStateOf(exerciseId != null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Plan targets · ${plan.plan.scheduledDate} · ${plan.plan.durationMinutes} min", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide targets" else "Show targets") }
        }
        if (expanded) {
            Text("Reference targets; record actual results through the logger.", style = MaterialTheme.typography.bodySmall)
            plan.ordered.filter { exerciseId == null || it.exercise.id == exerciseId }.forEach { TargetReference(it.prescription) }
        }
    }
}
