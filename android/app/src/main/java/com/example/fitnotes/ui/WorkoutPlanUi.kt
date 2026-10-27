package com.example.fitnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

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
    var expandedExercise by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(draft?.editingId) { model.planFocusPosition?.let{position->expandedExercise=draft?.exercises?.getOrNull(position)?.key};model.planFocusPosition=null }
    var planDetails by rememberSaveable { mutableStateOf(false) }
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
        else Button(onClick = { model.savePlan(onSaved = onSaved); focus.clearFocus(); keyboard?.hide() }, enabled = !busy && resolved && draft!!.exercises.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save-plan")) { Text(if(draft!!.editingId!=null)"Save changes" else "Save plan") }
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
            item { Text(p.title,style=MaterialTheme.typography.headlineSmall);Text(planSubtitle(p.exercises.size,p.minutes.toIntOrNull() ?: 0));if(p.date.isNotBlank())Text(prettyDay(p.date)) }
            item { TextButton(onClick={planDetails=!planDetails}){Text("Plan details")};if(planDetails)Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(p.title,{value->modify{it.copy(title=value)}},label={Text("Title")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(p.date,{value->modify{it.copy(date=value)}},label={Text("Scheduled date (optional YYYY-MM-DD)")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(p.minutes,{value->modify{it.copy(minutes=value)}},label={Text("About how many minutes?")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
            } }
            item { var notesOpen by rememberSaveable{mutableStateOf(false)};TextButton(onClick={notesOpen=!notesOpen}){Text("Workout notes"+if(notesOpen)" ▴" else " ▾")};if(notesOpen)Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(p.note,{value->modify{it.copy(note=value)}},label={Text("General notes")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(p.warmUp,{value->modify{it.copy(warmUp=value)}},label={Text("Warm-up")},modifier=Modifier.fillMaxWidth())
                if(p.legacyGuidance.isNotBlank())OutlinedTextField(p.legacyGuidance,{value->modify{it.copy(legacyGuidance=value)}},label={Text("Guidance")},modifier=Modifier.fillMaxWidth())
            } }
            items(p.exercises, key = { it.key }) { e ->
                val index = p.exercises.indexOf(e)
                val match = matchPlanExercise(e.sourceName, data.exercises, incompatible)
                val selected = data.exercises.firstOrNull { it.id == e.exerciseId }
                Surface(onClick={expandedExercise=if(expandedExercise==e.key)null else e.key},modifier = Modifier.testTag("plan-row:${e.key}"),color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. ${e.sourceName}", style = MaterialTheme.typography.titleMedium)
                        Text(editablePrescription(e.targets),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(expandedExercise==e.key) {
                        val label = when {
                            e.createName != null -> "New strength exercise: ${e.createName} (created on save)"
                            selected != null && selected.id !in incompatible -> "Matched: ${selected.name}"
                            match.kind == ExerciseMatchKind.INCOMPATIBLE -> "Recorded as cardio only · select a compatible strength exercise"
                            match.kind == ExerciseMatchKind.AMBIGUOUS -> "Ambiguous name · choose the intended exercise"
                            else -> "Unknown name · select or explicitly create a strength exercise"
                        }
                        if(selected==null || e.createName!=null) Text(label, color = if (e.createName != null || (selected != null && selected.id !in incompatible)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
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
    val plans by model.plans.collectAsState()
    var exportingId by rememberSaveable{mutableStateOf<String?>(null)}
    val exportPlan=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.fitnotes.plan")){uri->if(uri!=null)exportingId?.let{model.exportPlan(uri,it)}}
    val selected=plans.firstOrNull{it.plan.id==selectedId}
    val error by model.planImportError.collectAsState()
    var deleting by remember{mutableStateOf(false)}
    HandoffScreen(selected?.plan?.title ?: "Saved plans",busy,{if(selectedId==null)onClose()else onSelect(null)},"saved-plans-list",attention=error,
        actions={if(selected!=null)OverflowMenu{close->
            MenuAction("Edit plan"){close();onEdit(selected.plan.id)}
            MenuAction("Rename / reschedule"){close();onEdit(selected.plan.id)}
            MenuAction("Export plan"){close();exportingId=selected.plan.id;exportPlan.launch("FitNotes plan.fitnotesplan")}
            MenuAction("Duplicate"){close();model.duplicatePlan(selected.plan.id){onSelect(it)}}
            MenuAction("Apply progression suggestions"){close();model.applyPlanProgression(selected.plan.id)}
            MenuAction("Delete"){close();deleting=true}
        }},footer={
            if(selected!=null)Button(onClick={onStart(selected.plan.id)},enabled=!busy&&selected.exercises.isNotEmpty()&&(data.active==null||data.active?.workout?.id==selected.plan.startedWorkoutId),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("start-plan")){Text(if(selected.plan.startedWorkoutId!=null&&data.active?.workout?.id==selected.plan.startedWorkoutId)"Resume workout" else "Start workout")}
            else TextButton(onClick=onPaste){Text("Import plan")}
        }) {
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        if(selectedId==null)items(plans,key={it.plan.id}){plan->Surface(onClick={onSelect(plan.plan.id)}){Column(Modifier.fillMaxWidth().padding(vertical=12.dp)){Text(plan.plan.title,style=MaterialTheme.typography.titleMedium);Text(planSubtitle(plan.exercises.size,plan.plan.durationMinutes))}};HorizontalDivider()}
        else if(selected!=null) {
            item{Text(planSubtitle(selected.exercises.size,selected.plan.durationMinutes));if(selected.plan.scheduledDate.isNotBlank())Text(prettyDay(selected.plan.scheduledDate))}
            items(selected.ordered,key={it.target.id}) { exercise ->
                Surface(onClick={model.planFocusPosition=exercise.target.position;onEdit(selected.plan.id)}){Column(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(vertical=10.dp)){Text(exercise.exercise.name,style=MaterialTheme.typography.titleMedium);Text(compactPrescription(exercise.prescription.setTargets),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}};HorizontalDivider()
            }
            val notes=listOf(selected.plan.notes,selected.plan.warmUp,selected.plan.legacyGuidance).filter{it.isNotBlank()}.joinToString("\n\n")
            if(notes.isNotBlank())item{CollapsedGuidance("Workout notes",notes)}
        }
    }
    if(deleting&&selected!=null)NotebookConfirm("Delete plan?","Recorded workouts and sets are kept.","Delete",busy,{deleting=false}){model.deletePlan(selected.plan.id){deleting=false;onSelect(null)}}
}

fun compactPrescription(targets:List<PlanSetTarget>):String {
    if(targets.isEmpty())return "View targets"
    val uniform=targets.map{Triple(it.repsMin to it.repsMax,it.weight,it.loadMode)}.distinct().size==1
    if(!uniform)return "${targets.size} sets · View targets"
    val first=targets.first()
    val reps=first.repsMin?.let{if(it==first.repsMax)it.toString()else "$it–${first.repsMax}"}
    val load=when(first.loadMode){LoadMode.BODYWEIGHT->if(first.weight.unit==null||first.weight.unit=="bodyweight")"Bodyweight"else "Bodyweight + ${first.weight.text}";LoadMode.ASSISTANCE->first.weight.unit?.let{"${first.weight.text} assistance"};else->first.weight.unit?.let{first.weight.text}}
    return "${targets.size}"+(reps?.let{" × $it"} ?: " sets")+load?.let{" · $it"}.orEmpty()
}
fun editablePrescription(targets:List<EditableTarget>):String=runCatching{compactPrescription(targets.map{it.validated("Exercise")})}.getOrElse{"${targets.size} sets · View targets"}

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
