package com.example.fitnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.platform.testTag
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun ExerciseLogger(session: NotebookSession, exercise: NotebookExercise, all: List<NotebookSession>, data: AppData,
    model: FitNotesViewModel, busy: Boolean, editable: Boolean, onBack: () -> Unit, onSwitch: (String) -> Unit,
    onAdd: () -> Unit, onEditWorkout: () -> Unit, onHistory: (String) -> Unit, onSavePast: () -> Unit = {}) {
    val completionReady by model.completionReady.collectAsState()
    var manualRest by remember { mutableStateOf(false) }
    var manualRestSeconds by rememberSaveable { mutableStateOf("90") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var switcher by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val pastDraft = session.local?.workout?.historicalDraft == true
    val copiedKey = session.local?.workout?.draftSourceKey.orEmpty()
    val previous = remember(all, session.key, exercise.key, copiedKey) {
        val regular = previousExerciseSessions(all, session, exercise)
        val source = if(pastDraft) all.firstOrNull { it.key == copiedKey } else null
        val copied = source?.exercises?.singleOrNull { it.canonicalId == exercise.canonicalId && it.sets.isNotEmpty() }
        if(source!=null && copied!=null) listOf(source to copied) + regular.filter { it.first.key != source.key } else regular
    }
    val copiedPlans by model.plans.collectAsState()
    val copiedRoutine = if(pastDraft && copiedKey.startsWith("plan:")) copiedPlans.firstOrNull { it.plan.id == copiedKey.substringAfter(':') }?.ordered?.firstOrNull { it.exercise.id == exercise.canonicalId } else null
    val copiedTarget = copiedRoutine?.takeIf { exercise.sets.isEmpty() }?.sets?.minByOrNull { it.position }?.target
    val runs by model.planRuns.collectAsState()
    val run = session.local?.workout?.id?.let { id -> runs.firstOrNull { it.run.workoutId == id } }
    val runExercise = run?.exercises?.firstOrNull { it.entry.id == exercise.entryId }
    val previousEntry=previous.firstOrNull()?.second
    val previousRun=previous.firstOrNull()?.first?.local?.workout?.id?.let{id->runs.firstOrNull{it.run.workoutId==id}}?.exercises?.firstOrNull{it.exercise.id==exercise.canonicalId}
    val previousSlots:List<NotebookSet?> = previousRun?.takeIf{it.ordered.isNotEmpty() && previousEntry?.sets?.any{set->set.local?.targetId!=null}==true}?.ordered?.map{target->previousEntry?.sets?.firstOrNull{it.local?.targetId==target.id}} ?: previousEntry?.sets.orEmpty()
    fun suggestedWeight(target:WorkoutSetTarget?):String {
        if(target==null)return ""
        val prescribed=suggestedActualWeight(target.target)
        if(prescribed.isNotBlank())return prescribed
        return previousSlots.getOrNull(target.position)?.takeIf{it.local?.loadMode==target.loadMode && target.loadMode!=LoadMode.UNKNOWN}?.weight?.let(::weightText).orEmpty()
    }
    val firstPending = runExercise?.let { pendingTargets(it,exercise.sets.mapNotNull { set->set.local }).firstOrNull() }
    val seed = exercise.sets.lastOrNull() ?: previous.firstOrNull()?.second?.sets?.lastOrNull()
    var currentTargetId by rememberSaveable { mutableStateOf(firstPending?.id) }
    var mode by rememberSaveable { mutableStateOf(firstPending?.loadMode ?: copiedTarget?.loadMode ?: seed?.local?.loadMode?.takeIf{it!=LoadMode.UNKNOWN} ?: LoadMode.EXTERNAL) }
    val recordedContexts by model.setLoadContexts.collectAsState()
    val loadProfiles by model.loadProfiles.collectAsState()
    val loadProfile=loadProfiles.firstOrNull{it.exerciseId==exercise.canonicalId && it.selected}
    var actualRir by rememberSaveable{mutableStateOf("")};var actualRpe by rememberSaveable{mutableStateOf("")};var effortEdited by rememberSaveable{mutableStateOf(false)};var showEffort by rememberSaveable{mutableStateOf(false)}
    var actualWarmUp by rememberSaveable { mutableStateOf(firstPending?.isWarmUp ?: false) }
    var completionCooldown by remember { mutableStateOf(false) }
    LaunchedEffect(completionCooldown) { if(completionCooldown) { kotlinx.coroutines.delay(1000);completionCooldown=false } }
    val firstTarget = firstPending?.target
    var weight by rememberSaveable { mutableStateOf(if (runExercise != null) suggestedWeight(firstPending) else copiedTarget?.let(::suggestedActualWeight)?.takeIf { it.isNotBlank() } ?: seed?.weight?.let(::weightText) ?: "") }
    var reps by rememberSaveable { mutableStateOf(if (runExercise != null) firstTarget?.repsMin?.toString().orEmpty() else copiedTarget?.repsMin?.toString() ?: seed?.reps?.toString() ?: "") }
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
    fun storeDraft() { draft = arrayListOf(weight, reps, distance, seconds, unit, comment, cardio.toString(), mixed.toString(),mode,currentTargetId?.toString().orEmpty(),actualWarmUp.toString(),actualRir,actualRpe,effortEdited.toString()) }
    fun cancelEdit() {
        selected = null
        if (draft.size >= 8) {
            weight = draft[0]; reps = draft[1]; distance = draft[2]; seconds = draft[3]; unit = draft[4]; comment = draft[5]
            cardio = draft[6].toBoolean(); mixed = draft[7].toBoolean()
            if(draft.size>=11){mode=draft[8];currentTargetId=draft[9].toLongOrNull();actualWarmUp=draft[10].toBoolean()}
            if(draft.size>=14){actualRir=draft[11];actualRpe=draft[12];effortEdited=draft[13].toBoolean()} else {actualRir="";actualRpe="";effortEdited=false}
        }
        draft = arrayListOf()
    }
    fun choose(set: NotebookSet) {
        if (selected == null) storeDraft()
        val context=set.local?.id?.let{id->recordedContexts.firstOrNull{it.setId==id}}
        actualRir=context?.rir?.let(::weightText).orEmpty();actualRpe=context?.rpe?.let(::weightText).orEmpty();if(actualRir.isNotBlank() || actualRpe.isNotBlank())showEffort=true;effortEdited=false
        selected = set.key;actualWarmUp=set.local?.isWarmUp ?: false; mode = set.local?.loadMode ?: LoadMode.UNKNOWN; weight = set.weight?.let(::weightText) ?: ""; reps = set.reps?.toString() ?: ""
        distance = set.distance?.let(::weightText) ?: ""; seconds = set.seconds?.let(::weightText) ?: ""; unit = set.unit ?: "km"
        comment = set.comment; showComment = comment.isNotBlank(); cardio = set.cardio
        mixed = set.weight != null && (set.distance != null || set.seconds != null)
    }
    LaunchedEffect(selectedRecord?.local?.id,recordedContexts) {
        if(selectedRecord?.local!=null && !effortEdited){val context=recordedContexts.firstOrNull{it.setId==selectedRecord.local.id};actualRir=context?.rir?.let(::weightText).orEmpty();actualRpe=context?.rpe?.let(::weightText).orEmpty()}
    }
    // History may arrive after the local workout. Fill an untouched draft once,
    // without overwriting a user's typing or an explicitly selected record.
    LaunchedEffect(runExercise?.entry?.id) {
        if (!planSuggested && runExercise != null && selected == null && !userEdited) {
            currentTargetId=firstPending?.id;actualWarmUp=firstPending?.isWarmUp ?: false;mode=firstPending?.loadMode ?: LoadMode.UNKNOWN
            weight = suggestedWeight(firstPending); reps = firstTarget?.repsMin?.toString().orEmpty()
            planSuggested = true; suggested = true; cardio = false; mixed = false; distance = ""; seconds = ""
        }
    }
    LaunchedEffect(runExercise?.ordered?.firstOrNull{it.id==currentTargetId}?.skipped) {
        if(runExercise?.ordered?.firstOrNull{it.id==currentTargetId}?.skipped==true && selected==null) {
            val next=pendingTargets(runExercise,exercise.sets.mapNotNull{it.local}).firstOrNull()
            currentTargetId=next?.id;actualWarmUp=next?.isWarmUp ?: false;mode=next?.loadMode ?: LoadMode.EXTERNAL
            weight=suggestedWeight(next);reps=next?.repsMin?.toString().orEmpty();userEdited=false
        }
    }
    LaunchedEffect(currentTargetId,runExercise?.ordered?.firstOrNull{it.id==currentTargetId}?.target) {
        val target=runExercise?.ordered?.firstOrNull{it.id==currentTargetId}
        if(target!=null && selected==null && !userEdited) {
            weight=suggestedWeight(target);reps=target.repsMin?.toString() ?: previousSlots.getOrNull(target.position)?.reps?.toString().orEmpty()
            mode=target.loadMode;actualWarmUp=target.isWarmUp
        }
    }
    LaunchedEffect(previousSlots.map{it?.key},currentTargetId) {
        val target=runExercise?.ordered?.firstOrNull{it.id==currentTargetId}
        if(target!=null && selected==null && !userEdited && weight.isBlank())weight=suggestedWeight(target)
    }
    LaunchedEffect(copiedTarget) {
        if (pastDraft && copiedTarget != null && selected == null && !userEdited && exercise.sets.isEmpty()) {
            mode = copiedTarget.loadMode
            weight = suggestedActualWeight(copiedTarget).ifBlank { seed?.weight?.let(::weightText).orEmpty() }
            reps = copiedTarget.repsMin?.toString() ?: seed?.reps?.toString().orEmpty()
            suggested = true
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
            if(exercise.entryId!=null)validateEffort(optionalNonNegative(actualRir),optionalNonNegative(actualRpe))
            if (session.imported != null && reps.trim() == "0") { require(optionalNonNegative(weight) != null) { "Enter a weight." } }
            else InputRules.parseSet(if(mode==LoadMode.REPS_ONLY || (mode==LoadMode.UNKNOWN&&weight.isBlank()))"0" else weight, reps)
        }
        if (cardio || mixed) {
            require(optionalNonNegative(distance) != null || optionalNonNegative(seconds) != null) { "Enter distance or time." }
            optionalNonNegative(distance); optionalNonNegative(seconds)
        }
    }.exceptionOrNull()?.message
    val untouched = listOf(weight, reps, distance, seconds).all { it.isBlank() }
    fun saveActual() {
        if (busy || completionCooldown) return
        // A quick second tap is the same save gesture, even if Room has already
        // committed the first result. Identical later sets remain valid training.
        if (selectedRecord == null) completionCooldown = true

        val completedTarget=if(selectedRecord==null && !cardio)currentTargetId else null
        val done = {
            if(selectedRecord!=null)cancelEdit() else { selected=null;draft=arrayListOf<String>();actualRir="";actualRpe="";effortEdited=false }; focus.clearFocus(); keyboard?.hide()
            if(completedTarget!=null) {
                val next=runExercise?.ordered?.firstOrNull{t->t.id!=completedTarget&&!t.skipped&&exercise.sets.none{it.local?.targetId==t.id}}
                currentTargetId=next?.id;actualWarmUp=next?.isWarmUp ?: false;mode=next?.loadMode ?: LoadMode.EXTERNAL
                weight=suggestedWeight(next);reps=next?.repsMin?.toString().orEmpty();comment="";userEdited=false
                val global=run?.let{plannedSequence(it)}?.firstOrNull{t->t.id!=completedTarget&&!t.skipped&&session.local!!.entries.flatMap{it.sets}.none{it.targetId==t.id}}
                if(global!=null&&global.entryId!=exercise.entryId)session.exercises.firstOrNull{it.entryId==global.entryId}?.let{onSwitch(it.key)}
            }
            Unit
        }
        if (exercise.entryId != null) {
            if(completedTarget!=null && !mixed) model.completeTarget(completedTarget,weight,reps,comment,mode,done,actualRir,actualRpe)
            else if (cardio) model.logCardio(exercise.entryId, distance, unit, seconds, comment, selectedRecord?.local?.id, done)
            else model.logStrength(exercise.entryId, weight, reps, selectedRecord?.local?.id, comment, done,
                if (mixed) distance else null, unit, if (mixed) seconds else null, loadMode=mode,isWarmUp=actualWarmUp,actualRir=actualRir,actualRpe=actualRpe)
        } else model.logImported(session.imported!!.workout.id, exercise.sourceName ?: exercise.name, exercise.category, selectedRecord?.imported?.id,
            if (cardio) "" else weight, if (cardio) "" else reps, if (cardio || mixed) distance else "", unit,
            if (cardio || mixed) seconds else "", comment, done)

    }
    val actualEditor: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selectedRecord == null) (if(currentTargetId!=null) "Current target set "+((runExercise?.ordered?.indexOfFirst{it.id==currentTargetId} ?: -1)+1) else "New set") else "Editing set " + (exercise.sets.indexOf(selectedRecord) + 1), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { showComment = !showComment }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(if (comment.isNotBlank()) "Note •" else "Add note") }
                    }
                    if(!cardio && session.local!=null) {
                        LoadModePicker(mode,{mode=it;userEdited=true},!busy)
                        Row { Checkbox(actualWarmUp,{actualWarmUp=it},enabled=!busy&&(currentTargetId==null||selectedRecord!=null));Text("Warm-up result",Modifier.padding(top=12.dp)) }
                        if(exercise.entryId!=null) {
                            TextButton(onClick={model.reviewEntryWeights(exercise.entryId)},enabled=!busy){Text("Suggested weight · Use / Change")}
                            loadProfile?.let{p->Text("${p.gym} · ${p.equipment} · ${p.basis.replace('_',' ')}. Actual weight field uses kg.",style=MaterialTheme.typography.bodySmall)}
                            TextButton(onClick={showEffort=!showEffort},enabled=!busy){Text(if(showEffort)"Hide RIR / RPE" else "Record RIR / RPE")}
                            if(showEffort) {
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(actualRir,{actualRir=it;effortEdited=true},label={Text("Actual RIR (optional)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f).testTag("actual-rir"),enabled=!busy)
                                OutlinedTextField(actualRpe,{actualRpe=it;effortEdited=true},label={Text("Actual RPE (optional)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f).testTag("actual-rpe"),enabled=!busy)
                            }
                            Text("Report actual spare reps, or RIR-based RPE (10 = none left). Blank stays unknown; a target is never copied here.",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        if(currentTargetId!=null && runExercise?.ordered?.firstOrNull{it.id==currentTargetId}?.weightUnit==null) Text("Target load is unspecified. Previous load may fill a draft only when its load mode is known and compatible. It remains editable and does not alter the target.",style=MaterialTheme.typography.bodySmall)
                    }
                    if (!cardio) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumericControl("Weight (kg)", if(mode==LoadMode.REPS_ONLY)"0" else weight, { weight = it; userEdited = true }, 2.5, false, busy || mode==LoadMode.REPS_ONLY, Modifier.weight(1f))
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
                    Button(shape = MaterialTheme.shapes.small, onClick = ::saveActual, enabled = !busy && !completionCooldown && (completionReady || currentTargetId==null || selectedRecord!=null) && error == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
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
    Column(Modifier.fillMaxSize()) {
        NotebookBar(exercise.name, onBack, "Back to workout") {
            IconButton(onClick = { switcher = true }) { Icon(Icons.AutoMirrored.Filled.List, "Switch") }
            OverflowMenu { close ->
                if(session.local!=null&&!session.completed&&!pastDraft)MenuAction("Rest timer") { close();manualRest=true }
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
        } else LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("logger-list"), contentPadding = PaddingValues(16.dp)) {
            if (run != null && run.run.warmUp.isNotBlank()) item { CollapsedGuidance("Warm-up", run.run.warmUp) }
            if (run != null && run.run.legacyGuidance.isNotBlank()) item { CollapsedGuidance("Additional instructions", run.run.legacyGuidance) }
            if (!session.completed && session.local != null && !pastDraft) item { RestControls(model,session.local.workout.id,busy) }
            if (editable && runExercise!=null) item { actualEditor() }
            if (runExercise != null) item { Box(Modifier.padding(bottom = 12.dp)) {
                RunSuggestions(runExercise, model, busy, editable, exercise.sets, previousSlots, previousRun?.ordered?.isNotEmpty()==true && previousEntry?.sets?.any{it.local?.targetId!=null}==true, currentTargetId,
                    canComplete = selectedRecord==null && error==null && completionReady && !completionCooldown, onCompleteCurrent = ::saveActual,
                    onUse = { target ->
                        if (selected != null) cancelEdit()
                        currentTargetId=target.id;actualWarmUp=target.isWarmUp;mode=target.loadMode
                        val old=previousSlots.getOrNull(target.position)
                        val historyWeight=old?.takeIf { it.local?.loadMode==target.loadMode && target.loadMode!=LoadMode.UNKNOWN && it.weight!=null }?.weight?.let(::weightText)
                        weight = suggestedActualWeight(target.target).ifBlank { historyWeight.orEmpty() }
                        reps = target.repsMin?.toString() ?: old?.reps?.toString().orEmpty()
                        cardio=false;mixed=false;suggested=true;planSuggested=true;userEdited=false
                    }, onEdit = ::choose, onExtra = { if(selected!=null)cancelEdit();currentTargetId=null;actualWarmUp=false;mode=LoadMode.EXTERNAL;weight="";reps="";userEdited=true })
            } }
            if (exercise.name.length > 45) item {
                Text(exercise.name, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            if(run!=null) {
                val next=nextPlannedSet(run,session.local!!.entries.flatMap{it.sets})
                if(next!=null && next.entryId!=exercise.entryId) item { TextButton(onClick={session.exercises.firstOrNull{it.entryId==next.entryId}?.let{onSwitch(it.key)}}) { Text("Next exercise: "+(run.exercises.firstOrNull{it.entry.id==next.entryId}?.exercise?.name ?: "")) } }
            }
            if(runExercise==null) item {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val last = previous.firstOrNull()
                    if(pastDraft) Text("Copied values are drafts. Save each performed set, then Save completed.", style=MaterialTheme.typography.bodySmall)
                    copiedTarget?.let { Text("Routine suggestion · " + it.text, style=MaterialTheme.typography.bodyMedium) }
                    Text(if (last != null && copiedKey==last.first.key) "Copied reference · " + prettyDay(last.first.date) else if (last != null) "Previous · " + prettyDay(last.first.date) else if (data.imported.loading) "Loading previous session…" else "No previous session.",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (last != null) Text(compactResults(last.second.sets), style = MaterialTheme.typography.bodyMedium)
                }
                }
            }
            if (editable && runExercise==null) item { actualEditor() }
            val columns = setColumns(exercise.sets)
            if(pastDraft) item { Button(onClick=onSavePast,enabled=!busy && session.count>0,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Save completed")};val error by model.workoutTimeError.collectAsState();error?.let{Text(it,color=MaterialTheme.colorScheme.error)} }
            item { Text((if(pastDraft) "Draft sets · " else "Recorded sets · ") + exercise.sets.size, Modifier.padding(top = 20.dp, bottom = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val visibleSets=if(runExercise==null)exercise.sets else exercise.sets.filter{it.local?.targetId==null}
            if (visibleSets.isNotEmpty()) item { NotebookSetHeader(columns) }
            if (exercise.sets.isEmpty()) item { Text("No sets yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(visibleSets, key = { it.key }) { set ->
                NotebookSetRow(set, exercise.sets.indexOf(set) + 1, selected == set.key, if (editable && !busy) ({ choose(set) }) else null, columns = columns)
                if (!editable && set.comment.isNotBlank()) Text(set.comment, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if(manualRest && session.local!=null) AlertDialog(onDismissRequest={manualRest=false},title={Text("Rest timer")},text={OutlinedTextField(manualRestSeconds,{manualRestSeconds=it},label={Text("Rest seconds")})},confirmButton={TextButton(onClick={manualRestSeconds.toIntOrNull()?.let{model.startRest(session.local.workout.id,it)};manualRest=false},enabled=manualRestSeconds.toIntOrNull() in 1..7200){Text("Start rest")}},dismissButton={TextButton(onClick={manualRest=false}){Text("Cancel")}})
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
