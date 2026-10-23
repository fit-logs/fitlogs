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
fun PastePlanDialog(model: FitNotesViewModel, data: AppData, busy: Boolean, onClose: () -> Unit, onStart: (String) -> Unit, onSaved: (String) -> Unit) {
    val raw by model.planImportText.collectAsState()
    val draft by model.planEdit.collectAsState()
    val error by model.planImportError.collectAsState()
    val duplicate by model.duplicatePlanId.collectAsState()
    val preferences by model.handoffPreferences.collectAsState()
    val savedPlans by model.plans.collectAsState()
    var fallbackDate by rememberSaveable { mutableStateOf(model.handoffPreferences.value.value.lastTargetDate) }
    var dateInitialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(preferences.loading) {
        if (!preferences.loading && !dateInitialized) {
            fallbackDate = preferences.value.lastTargetDate; dateInitialized = true
        }
    }
    var resolving by rememberSaveable { mutableStateOf<String?>(null) }
    var formatCopied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val incompatible = remember(data) { incompatiblePlanExercises(data.exercises, data.workouts.flatMap { it.entries.map { e -> e.entry } },
        data.workouts.flatMap { it.entries.flatMap { e -> e.sets } }, data.imported.workouts.flatMap { it.sets },canonicalImportedSetIds(data)) }
    fun modify(change: (EditablePlan) -> EditablePlan) { model.planEdit.value?.let { model.planEdit.value = change(it) }; model.planImportError.value = null }
    fun target(key: String, change: (EditablePlanExercise) -> EditablePlanExercise) = modify { p -> p.copy(exercises = p.exercises.map { if (it.key == key) change(it) else it }) }
    fun move(key: String, amount: Int) = modify { p ->
        val index = p.exercises.indexOfFirst { it.key == key }
        if (index < 0 || index + amount !in p.exercises.indices) p else p.copy(exercises = p.exercises.toMutableList().apply { add(index + amount, removeAt(index)) })
    }
    fun close() { model.cancelPlanImport(); onClose() }
    val resolved = draft?.exercises?.all { (it.createName != null) || (it.exerciseId != null && it.exerciseId !in incompatible && data.exercises.any { e -> e.id == it.exerciseId }) } == true
    val saved = savedPlans.firstOrNull { it.plan.id == draft?.editingId }
    val savedUnchanged = saved != null && draft?.let { edit -> runCatching {
        val preview = edit.validated()
        preview.spec == saved.spec && preview.resolutions.map { it.exerciseId } == saved.ordered.map { it.exercise.id }
    }.getOrDefault(false) } == true
    HandoffScreen(if (draft == null) "Paste plan" else "Edit planned workout", busy, ::close, "plan-import-list", attention = error, footer = {
        if (draft == null) Button(onClick = { model.previewPlan(fallbackDate); focus.clearFocus(); keyboard?.hide() }, enabled = !busy && raw.isNotBlank() && !data.imported.loading && !preferences.loading,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Preview plan") }
        else {
            Button(onClick = { model.savePlan(onSaved = onSaved); focus.clearFocus(); keyboard?.hide() }, enabled = !busy && resolved && (draft!!.exercises.isNotEmpty() || draft!!.legacyGuidance.isNotBlank()),
                modifier = Modifier.heightIn(min = 48.dp).testTag("save-plan")) { Text("Save plan") }
            OutlinedButton(onClick = { saved?.let { onStart(it.plan.id) } }, enabled = savedUnchanged && resolved && !busy && saved?.exercises?.isNotEmpty() == true, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-start-plan")) { Text("Start workout") }
            TextButton(onClick = { if (draft?.editingId != null || draft?.recoveryWorkoutId != null) close() else { model.planEdit.value = null; model.planImportError.value = null } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (draft?.editingId != null || draft?.recoveryWorkoutId != null) "Close without saving" else "Edit pasted text") }
        }
        TextButton(onClick = ::close, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    }) {
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        val p = draft
        if (p == null) {
            item { Text("Paste one WORKOUT_PLAN_V2 strength plan. Surrounding explanation and a Markdown code fence are supported.") }
            item { OutlinedTextField(fallbackDate, { fallbackDate = it; dateInitialized = true }, label = { Text("Optional date when omitted (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
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
            if (!savedUnchanged) item { Text("Save the plan before starting. Planning changes never start a session.", style = MaterialTheme.typography.bodySmall) }
            if (p.recoveryWorkoutId != null) item { Text("Save plan will move this empty import out of training and remove its accidental start timestamp. Cancel leaves it unchanged.", color = MaterialTheme.colorScheme.primary) }
            item { OutlinedTextField(p.title, { value -> modify { it.copy(title = value) } }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), enabled = !busy) }
            item { OutlinedTextField(p.date, { value -> modify { it.copy(date = value) } }, label = { Text("Scheduled date (optional YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy) }
            item { OutlinedTextField(p.minutes, { value -> modify { it.copy(minutes = value) } }, label = { Text("Time budget (optional minutes)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), enabled = !busy) }
            item { OutlinedTextField(p.note, { value -> modify { it.copy(note = value) } }, label = { Text("Session note") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(), enabled = !busy) }
            item { EditableGuidance("Warm-up", p.warmUp, busy) { value -> modify { it.copy(warmUp = value) } } }
            if (p.legacyGuidance.isNotBlank()) item { EditableGuidance("Additional instructions · review before starting", p.legacyGuidance, busy, initiallyExpanded = true) { value -> modify { it.copy(legacyGuidance = value) } } }
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
                        OutlinedTextField(e.note, { value -> target(e.key) { it.copy(note = value) } }, label = { Text("Exercise guidance") }, minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        e.targets.forEachIndexed { position, set ->
                            SetTargetEditor(set, position + 1, busy, { edited -> target(e.key) { row -> row.copy(targets = row.targets.map { if (it.key == set.key) edited else it }) } },
                                onRemove = { target(e.key) { row -> row.copy(targets = row.targets.filter { it.key != set.key }) } })
                        }
                        if (e.targets.isEmpty()) Text("Targets unspecified. Review the instructions and add sets explicitly.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(e.supersetGroup,{value->target(e.key){it.copy(supersetGroup=value)}},label={Text("Superset group (optional)")},supportingText={Text("Same group alternates exercises by set. Leave blank for ordinary order.")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
                        TextButton(onClick = { target(e.key) { it.copy(targets = it.targets + EditableTarget()) } }, enabled = !busy && e.targets.size < WorkoutPlanParser.MAX_SETS) { Text("Add target set") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { move(e.key, -1) }, enabled = !busy && index > 0) { Text("Move up") }
                            TextButton(onClick = { move(e.key, 1) }, enabled = !busy && index < p.exercises.lastIndex) { Text("Move down") }
                            TextButton(onClick = { modify { it.copy(exercises = it.exercises.filter { row -> row.key != e.key }) } }, enabled = !busy) { Text("Remove") }
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = { resolving = "new-exercise" }, enabled = !busy && p.exercises.size < WorkoutPlanParser.MAX_EXERCISES) { Text("Add exercise to plan") } }
            if (!resolved) item { Text("Resolve all exercise names before saving.", color = MaterialTheme.colorScheme.error) }
        }
    }
    resolving?.let { key ->
        val p = draft
        if (p != null) Dialog(onDismissRequest = { if (!busy) resolving = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
                ExercisePicker(data.exercises.filter { it.id !in incompatible }, p.exercises.filter { it.key != key }.mapNotNull { it.exerciseId }.toSet(),
                    notebookSessions(data).flatMap { it.exercises }.map { normaliseExerciseName(it.name) }.distinct(), emptyMap(), busy, { resolving = null },
                    { ids -> ids.singleOrNull()?.let { id ->
                        if (key == "new-exercise") {
                            val name = data.exercises.first { it.id == id }.name
                            modify { it.copy(exercises = it.exercises + EditablePlanExercise(java.util.UUID.randomUUID().toString(), name, emptyList(), "", id)) }
                        } else target(key) { it.copy(exerciseId = id, createName = null) }
                        resolving = null
                    } },
                    { name ->
                        try {
                            val valid = InputRules.exerciseName(name)
                            if (key == "new-exercise") modify { it.copy(exercises = it.exercises + EditablePlanExercise(java.util.UUID.randomUUID().toString(), valid, emptyList(), "", null, valid)) }
                            else target(key) { it.copy(exerciseId = null, createName = valid) }
                            resolving = null
                        } catch (failure: IllegalArgumentException) { model.planImportError.value = failure.message; resolving = null }
                    }, singleSelection = true, aliases=data.exerciseAliases)
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
    onClose: () -> Unit, onPaste: () -> Unit, onEdit: (String) -> Unit, onStart: (String) -> Unit, onOpenWorkout: (Long) -> Unit) {
    val allPlans by model.plans.collectAsState()
    val programs by model.programs.collectAsState()
    val owned = programs.flatMap { it.days }.map { it.day.planId }.toSet()
    val plans = allPlans.filter { it.plan.id !in owned && !it.plan.archived }
    val selected = allPlans.firstOrNull { it.plan.id == selectedId }
    val programDay = selectedId in owned
    val error by model.planImportError.collectAsState()
    var startReady by remember(selected?.plan?.id) { mutableStateOf(false) }
    LaunchedEffect(selected?.plan?.id) {
        selected?.let { kotlinx.coroutines.delay(1500); startReady = true }
    }
    var deleting by remember { mutableStateOf(false) }
    HandoffScreen(if (selectedId == null) "My templates" else if(programDay) "Program day" else "Saved template", busy, { if (selectedId == null) onClose() else onSelect(null) }, "saved-plans-list", attention = error, footer = {
        if (selected != null) {
            val started = selected.plan.startedWorkoutId
            if(!programDay) Button(onClick = { onStart(selected.plan.id) }, enabled = startReady && selected.exercises.isNotEmpty() && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("start-plan")) { Text(if (started != null && data.active?.workout?.id == started) "Resume workout" else "Start workout") }
            TextButton(onClick = { model.applyPlanProgression(selected.plan.id) },enabled=!busy) { Text("Apply progression suggestions") }
            TextButton(onClick = { onEdit(selected.plan.id) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit plan") }
            if (started != null) TextButton(onClick = { onOpenWorkout(started) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open logged workout") }
            if(!programDay) TextButton(onClick = { deleting = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete template") }
        }
        TextButton(onClick = onPaste, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Paste plan") }
        TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
    }) {
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (selectedId == null) {
            item { Text("Plans hold suggested targets. Completed training stays in History.", style = MaterialTheme.typography.bodyMedium) }
            if (plans.isEmpty()) item { Text("No planned workouts yet. Ask ChatGPT, then paste and review a WORKOUT_PLAN_V2 reply.") }
            items(plans, key = { it.plan.id }) { plan ->
                Surface(onClick = { onSelect(plan.plan.id) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(plan.plan.title, style = MaterialTheme.typography.titleMedium)
                        Text("${plan.plan.scheduledDate.ifBlank { "Unscheduled" }} · ${plan.plan.durationMinutes.takeIf { it > 0 }?.let { "$it min" } ?: "No time budget"} · ${plan.exercises.size} exercises")
                        Text(if (plan.plan.startedWorkoutId == null) "Planned · targets only" else "Started · targets retained as reference", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } else if (selected == null) item { Text("Loading saved plan…") }
        else {
            item {
                Text(selected.plan.title, style = MaterialTheme.typography.headlineSmall)
                Text("${selected.plan.scheduledDate.ifBlank { "Unscheduled" }} · ${selected.plan.durationMinutes.takeIf { it > 0 }?.let { "$it min" } ?: "No time budget"}", style = MaterialTheme.typography.bodyMedium)
                if(programDay)Text("Program prescription. Start the next day from Train to associate performed results with this program.")
                Text("Targets only · no completed sets", color = MaterialTheme.colorScheme.primary)
                if (selected.plan.notes.isNotBlank()) Text(selected.plan.notes)
            }
            if (selected.plan.warmUp.isNotBlank()) item { CollapsedGuidance("Warm-up", selected.plan.warmUp) }
            if (selected.plan.legacyGuidance.isNotBlank()) item { CollapsedGuidance("Additional instructions · edit to clarify", selected.plan.legacyGuidance) }
            items(selected.ordered, key = { it.target.id }) { exercise -> if(exercise.target.supersetGroup.isNotBlank())Text("Superset ${exercise.target.supersetGroup}");TargetReference(exercise.prescription) }
            if (selected.exercises.isEmpty()) item { Text("Edit plan to add explicit strength exercises before starting.") }
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
            if (target.note.isNotBlank()) Text(target.note)
            target.setTargets.forEachIndexed { index, set -> Text("Set ${index + 1}: ${set.text}", style = MaterialTheme.typography.bodyLarge) }
            if (target.setTargets.isEmpty()) Text("Targets unspecified")
        }
    }
}

@Composable
fun PlanReference(plan: PlannedWorkoutDetail, exerciseId: Long? = null) {
    var expanded by rememberSaveable(plan.plan.id, exerciseId) { mutableStateOf(exerciseId != null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Plan targets · ${plan.plan.scheduledDate.ifBlank { "Unscheduled" }} · ${plan.plan.durationMinutes.takeIf { it > 0 }?.let { "$it min" } ?: "No time budget"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide targets" else "Show targets") }
        }
        if (expanded) {
            Text("Reference targets; record actual results through the logger.", style = MaterialTheme.typography.bodySmall)
            plan.ordered.filter { exerciseId == null || it.exercise.id == exerciseId }.forEach { TargetReference(it.prescription) }
        }
    }
}

@Composable
fun EditableGuidance(label: String, value: String, busy: Boolean, initiallyExpanded: Boolean = false, onChange: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(label + if (expanded) " ▴" else " ▾") }
        if (expanded) OutlinedTextField(value, onChange, label = { Text(label) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(), enabled = !busy)
    }
}
@Composable
fun CollapsedGuidance(label: String, value: String) {
    var expanded by rememberSaveable(value) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(label + if (expanded) " ▴" else " ▾") }
        if (expanded) Text(value)
    }
}
@Composable
fun SetTargetEditor(value: EditableTarget, number: Int, busy: Boolean, onChange: (EditableTarget) -> Unit, onRemove: (() -> Unit)? = null) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("target-row:${value.key}")) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Text("Target set $number", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); if (onRemove != null) TextButton(onClick = onRemove, enabled = !busy) { Text("Remove set") } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value.repsMin, { onChange(value.copy(repsMin = it)) }, label = { Text("Rep min") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), enabled = !busy)
                OutlinedTextField(value.repsMax, { onChange(value.copy(repsMax = it)) }, label = { Text("Rep max") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), enabled = !busy)
            }
            OutlinedTextField(value.weight, { onChange(value.copy(weight = it)) }, label = { Text("Weight + unit (optional)") }, singleLine = true, supportingText = { Text("kg / lb / bodyweight; blank means unspecified") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            LoadModePicker(value.loadMode,{onChange(value.copy(loadMode=it))},!busy)
            Row { Checkbox(value.isWarmUp,{onChange(value.copy(isWarmUp=it))},enabled=!busy);Text("Warm-up set",Modifier.padding(top=12.dp)) }
            OutlinedTextField(value.rest, { onChange(value.copy(rest = it)) }, label = { Text("Rest seconds (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}
@Composable
fun RunReference(run: PlanRunDetail) {
    var expanded by rememberSaveable(run.run.workoutId) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide workout targets" else "Show workout targets") }
        if (run.run.warmUp.isNotBlank()) CollapsedGuidance("Warm-up", run.run.warmUp)
        if (run.run.legacyGuidance.isNotBlank()) CollapsedGuidance("Additional instructions", run.run.legacyGuidance)
        if (expanded) run.exercises.sortedBy { it.entry.position }.forEach { e ->
            Text(e.exercise.name, style = MaterialTheme.typography.titleMedium)
            if (e.entry.planNote.isNotBlank()) Text(e.entry.planNote)
            e.ordered.forEachIndexed { index, set -> Text("Set ${index + 1}: ${set.target.text}") }
        }
    }
}
