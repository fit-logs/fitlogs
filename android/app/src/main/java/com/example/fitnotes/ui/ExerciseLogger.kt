package com.example.fitnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Edit
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun ExerciseLogger(session:NotebookSession,exercise:NotebookExercise,all:List<NotebookSession>,data:AppData,model:FitNotesViewModel,busy:Boolean,editable:Boolean,
    onBack:()->Unit,onSwitch:(String)->Unit,onAdd:()->Unit,onEditWorkout:()->Unit,onHistory:(String)->Unit,onSavePast:()->Unit={}) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var switcher by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var selectedSet by remember { mutableStateOf<NotebookSet?>(null) }
    var extra by remember { mutableStateOf(false) }
    var extraWeight by rememberSaveable { mutableStateOf("") }
    var extraReps by rememberSaveable { mutableStateOf("") }
    var showNote by rememberSaveable { mutableStateOf(false) }
    var manualRest by remember { mutableStateOf(false) }
    val previous=remember(all,session,exercise){previousExerciseSessions(all,session,exercise)}
    val runs by model.planRuns.collectAsState()
    val run=runs.firstOrNull{it.run.workoutId==session.local?.workout?.id}
    val targets=run?.exercises?.firstOrNull{it.entry.id==exercise.entryId}
    val entryNote=targets?.entry?.planNote ?: session.local?.entries?.firstOrNull{it.entry.id==exercise.entryId}?.entry?.planNote.orEmpty()
    val pending=targets?.let{pendingTargets(it,exercise.sets.mapNotNull{set->set.local})}.orEmpty()
    var currentId by rememberSaveable { mutableStateOf(pending.firstOrNull()?.id) }
    var focusId by remember { mutableStateOf<Long?>(null) }
    val ready by model.completionReady.collectAsState()
    val listState=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val loadModes=(targets?.ordered?.map{it.loadMode}.orEmpty()+exercise.sets.mapNotNull{it.local?.loadMode}).distinct()
    val loadLabel=when(loadModes.singleOrNull()){LoadMode.BODYWEIGHT->"Added kg";LoadMode.ASSISTANCE->"Assist kg";LoadMode.REPS_ONLY->"Load";else->"kg"}
    Column(Modifier.fillMaxSize()) {
        NotebookBar(exercise.name,onBack,"Back to workout") {
            if(editable && exercise.entryId!=null)IconButton(onClick={showNote=!showNote}){Icon(Icons.Filled.Edit,"Exercise note")}
            IconButton(onClick={switcher=true}){Icon(Icons.AutoMirrored.Filled.List,"Switch exercise")}
            OverflowMenu { close ->
                if(!editable)MenuAction("Edit workout"){close();onEditWorkout()}
                if(session.local!=null&&!session.completed)MenuAction("Rest timer"){close();manualRest=true}
                if(editable && exercise.entryId!=null)MenuAction("Exercise settings"){close();showNote=true}
                if(editable && exercise.entryId!=null)MenuAction("Remove exercise"){close();remove=true}
            }
        }
        if(session.local?.workout?.historicalDraft == true || exercise.entryId != null) {
            // Text actions wrap independently without squeezing the exercise title.
            FlowRow(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(session.local?.workout?.historicalDraft == true) TextButton(onClick=onSavePast,enabled=!busy){Text("Save past workout")}
                if(exercise.entryId != null) TextButton(onClick={model.reviewEntryWeights(exercise.entryId)},enabled=!busy){Text("Suggested weights")}
            }
        }
        if(entryNote.isNotBlank() || showNote) {
            if(entryNote.isNotBlank())TextButton(onClick={showNote=!showNote},contentPadding=PaddingValues(horizontal=16.dp),modifier=Modifier.heightIn(min=48.dp)) { Text(if(entryNote.length<90)entryNote else "Exercise note",style=MaterialTheme.typography.bodySmall) }
            if(showNote) {
                if(editable && exercise.entryId!=null) { var draft by rememberSaveable(exercise.key,entryNote){mutableStateOf(entryNote)}
                    Column(Modifier.padding(horizontal=16.dp)) { OutlinedTextField(draft,{draft=it.take(2000)},label={Text("Exercise note")},modifier=Modifier.fillMaxWidth(),maxLines=4);TextButton(onClick={model.saveExerciseNote(exercise.entryId,draft){showNote=false}},enabled=!busy){Text("Save note")} }
                } else Text(entryNote,Modifier.padding(horizontal=16.dp,vertical=4.dp))
            }
        }
        PrimaryTabRow(selectedTabIndex=tab) { Tab(tab==0,{tab=0},text={Text("Log")});Tab(tab==1,{tab=1},text={Text("History")}) }
        previous.firstOrNull()?.let { Text("Last: "+compactResults(it.second.sets),Modifier.padding(horizontal=16.dp,vertical=10.dp),style=MaterialTheme.typography.bodyMedium) }
        if(tab==1)LazyColumn(Modifier.weight(1f).testTag("logger-history-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(previous.isNotEmpty()) item {
                // Use the same actual-result conversion as Progress. These rows
                // have already been matched by canonical identity/explicit alias.
                val records=remember(previous) {
                    observations(previous.map{(workout,matched)->workout.copy(exercises=listOf(matched))})
                        .map{observation->observation.copy(results=observation.results.map{it.copy(exerciseKey="logger-history")})}
                }
                if(records.any{it.completed&&it.activity=="strength"&&it.results.isNotEmpty()}) ExerciseHistoryGraph(records,onHistory)
            }
            else item { Text("No previous sessions.") }
            items(previous,key={it.first.key}) { (workout,e) ->
                Column { TextButton(onClick={onHistory(workout.key)},contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=48.dp)){Text(prettyDay(workout.date)+workout.timeLabel.takeIf{it.isNotBlank()}?.let{" · $it"}.orEmpty())};val columns=setColumns(e.sets);NotebookSetHeader(columns);e.sets.forEachIndexed{index,set->NotebookSetRow(set,index+1,columns=columns);HorizontalDivider()} }
            }
        } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tableWidth=maxWidth
            Box(Modifier.width(tableWidth)) {
                LazyColumn(Modifier.fillMaxSize().testTag("logger-list"),state=listState,contentPadding=PaddingValues(16.dp)) {
                    if(targets!=null || exercise.sets.isNotEmpty()) item { Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("Set",Modifier.width(48.dp),style=MaterialTheme.typography.labelMedium)
                        Text(loadLabel,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                        Text("Reps",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium);Spacer(Modifier.width(48.dp))
                    } }
                    targets?.let{planned->items(planned.ordered,key={"target:${it.id}"}) { target ->
                        val index=planned.ordered.indexOf(target)
                        val result=exercise.sets.firstOrNull{it.local?.targetId==target.id}
                        if(result!=null)LoggerResultRow(result,index+1,editable){selectedSet=result}
                        else PendingSetRow(target,index+1,currentId==target.id,focusId==target.id,busy||!ready,!editable,
                            onSelect={currentId=target.id},onSkip={model.skipTarget(target.id,!target.skipped)},onApply={value->model.applyRemainingWeight(exercise.entryId!!,value)},onComplete={weight,reps->
                                model.completeTarget(target.id,weight,reps,"",target.loadMode, done = {
                                    val next=pending.firstOrNull{it.id!=target.id};currentId=next?.id;focusId=next?.id
                                    if(next!=null)scope.launch{listState.animateScrollToItem(planned.ordered.indexOf(next)+1)}
                                    val global=run?.let{plannedSequence(it)}?.firstOrNull{t->t.id!=target.id && !t.skipped && session.local!!.entries.flatMap{it.sets}.none{it.targetId==t.id}}
                                    if(global!=null && global.entryId!=exercise.entryId && (next==null || planned.entry.supersetGroup.isNotBlank()))session.exercises.firstOrNull{it.entryId==global.entryId}?.let{onSwitch(it.key)}
                                })
                            });HorizontalDivider()
                    } }
                    val recorded=if(targets==null)exercise.sets else exercise.sets.filter{it.local?.targetId==null}
                    items(recorded,key={it.key}) { set -> LoggerResultRow(set,(targets?.ordered?.size ?: 0)+recorded.indexOf(set)+1,editable){selectedSet=set};HorizontalDivider() }
                    if(editable)item(key="set-entry") {
                        if(targets==null && exercise.entryId!=null && session.activity=="strength" && exercise.sets.none{it.cardio}) {
                            QuickSetEntry(exercise,session.key,model,busy,options={weight,reps->extraWeight=weight;extraReps=reps;extra=true})
                        } else TextButton(onClick={extra=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!busy){Text("Add set")}
                    }
                }
            }
        }

    }
    if(selectedSet!=null || extra)SetEditDialog(selectedSet,exercise,session,model,busy,targets!=null,targets?.ordered?.lastOrNull()?.loadMode ?: exercise.sets.lastOrNull()?.local?.loadMode ?: if(session.local!=null)LoadMode.EXTERNAL else LoadMode.UNKNOWN,extraWeight,extraReps,{selectedSet=null;extra=false})
    if(manualRest && session.local!=null) {
        var seconds by rememberSaveable{mutableStateOf("90")}
        AlertDialog(onDismissRequest={manualRest=false},title={Text("Rest timer")},text={OutlinedTextField(seconds,{seconds=it},label={Text("Seconds")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))},confirmButton={TextButton(onClick={model.startRest(session.local.workout.id,seconds.toInt());manualRest=false},enabled=seconds.toIntOrNull() in 1..7200){Text("Start")}},dismissButton={TextButton(onClick={manualRest=false}){Text("Cancel")}})
    }
    if(switcher)AlertDialog(onDismissRequest={switcher=false},title={Text("Exercises")},text={LazyColumn(Modifier.heightIn(max=400.dp)){items(session.exercises,key={it.key}){e->TextButton(onClick={switcher=false;onSwitch(e.key)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(e.name)}}}},confirmButton={if(editable)TextButton(onClick={switcher=false;onAdd()}){Text("Add exercise")}},dismissButton={TextButton(onClick={switcher=false}){Text("Close")}})
    if(remove && exercise.entryId!=null)NotebookConfirm("Remove exercise?","This also deletes its saved sets.","Remove",busy,{remove=false}){model.removeExercise(exercise.entryId){remove=false;onBack()}}
}

/** Draft values stay in the logger's existing per-exercise SaveableStateProvider. */
@Composable
private fun QuickSetEntry(exercise:NotebookExercise,sessionKey:String,model:FitNotesViewModel,busy:Boolean,options:(String,String)->Unit) {
    var weight by rememberSaveable(sessionKey,exercise.key){mutableStateOf("")}
    var reps by rememberSaveable(sessionKey,exercise.key){mutableStateOf("")}
    val mode=exercise.sets.lastOrNull()?.local?.loadMode ?: LoadMode.EXTERNAL
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val actualWeight=if(mode==LoadMode.REPS_ONLY)"" else weight
    val valid=runCatching { InputRules.parseSet(if(actualWeight.isBlank())"0" else actualWeight,reps) }.isSuccess
    fun complete() {
        if(!busy && valid) model.logStrength(exercise.entryId!!,actualWeight,reps,null,"",{
            focus.clearFocus();keyboard?.hide()
        },loadMode=mode)
    }
    Column(Modifier.fillMaxWidth().padding(top=12.dp).testTag("quick-set-entry"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(mode!=LoadMode.REPS_ONLY) OutlinedTextField(weight,{weight=it},singleLine=true,enabled=!busy,
                label={Text(when(mode){LoadMode.BODYWEIGHT->"Added kg";LoadMode.ASSISTANCE->"Assistance kg";else->"kg"})},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Next),
                modifier=Modifier.weight(1f).testTag("quick-weight"))
            OutlinedTextField(reps,{reps=it},singleLine=true,enabled=!busy,label={Text("Reps")},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Done),
                keyboardActions=KeyboardActions(onDone={complete()}),modifier=Modifier.weight(1f).testTag("quick-reps"))
        }
        Button(onClick={complete()},enabled=!busy&&valid,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("quick-complete")){Text("Complete set")}
        TextButton(onClick={options(weight,reps)},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Set options")}
    }
}

@Composable
private fun LoggerResultRow(set:NotebookSet,number:Int,editable:Boolean,onEdit:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("saved-set:${set.key}").then(if(editable)Modifier.clickable(onClick=onEdit) else Modifier),verticalAlignment=Alignment.CenterVertically) {
        Text(number.toString(),Modifier.width(48.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(set.cardio)set.distance?.let{weightText(it)+" "+set.unit}.orEmpty() else if(set.local?.loadMode==LoadMode.REPS_ONLY)"—" else if(set.local?.loadMode==LoadMode.BODYWEIGHT && (set.weight==null||set.weight==0.0))"Bodyweight" else set.weight?.let(::weightText).orEmpty(),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
        Text(if(set.cardio)set.seconds?.let{durationText(it)}.orEmpty() else set.reps?.toString().orEmpty(),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
        Text("✓",Modifier.width(48.dp),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PendingSetRow(target:WorkoutSetTarget,number:Int,selected:Boolean,focus:Boolean,busy:Boolean,readOnly:Boolean,onSelect:()->Unit,onSkip:()->Unit,onApply:(String)->Unit,onComplete:(String,String)->Unit) {
    var weight by rememberSaveable(target.id,target.weightValue,target.weightUnit){mutableStateOf(if(target.weightUnit=="bodyweight")"" else suggestedActualWeight(target.target))}
    var reps by rememberSaveable(target.id,target.repsMin){mutableStateOf(target.repsMin?.toString().orEmpty())}
    var menu by remember { mutableStateOf(false) }
    var weightEditor by remember { mutableStateOf(false) }
    val requester=remember{FocusRequester()}
    LaunchedEffect(focus) { if(focus && !target.skipped && !readOnly)requester.requestFocus() }
    val valid=runCatching{InputRules.parseSet(if(weight.isBlank())"0" else weight,reps)}.isSuccess
    Column(Modifier.fillMaxWidth().testTag("run-target:${target.id}").background(if(selected)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.45f) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.width(48.dp)) {
                TextButton(onClick={menu=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics { contentDescription="Set $number options" }){Text(number.toString())}
                DropdownMenu(menu,{menu=false}) { MenuAction(if(target.skipped)"Unskip" else "Skip"){menu=false;onSkip()};MenuAction("Edit weight"){menu=false;weightEditor=true} }
            }
            if(target.skipped)Text("Skipped",Modifier.weight(2f),color=MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                if(target.loadMode==LoadMode.REPS_ONLY)Text("—",Modifier.weight(1f)) else OutlinedTextField(weight,{weight=it},singleLine=true,enabled=!busy&&!readOnly,
                    placeholder={if(target.weightUnit=="bodyweight")Text("Bodyweight",style=MaterialTheme.typography.bodySmall)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                    modifier=Modifier.weight(1f).padding(end=4.dp).onFocusChanged{if(it.isFocused)onSelect()}.testTag("weight:${target.id}").semantics { contentDescription="Set $number weight in kilograms" })
                OutlinedTextField(reps,{reps=it},singleLine=true,enabled=!busy&&!readOnly,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                    modifier=Modifier.weight(1f).padding(end=4.dp).focusRequester(requester).onFocusChanged{if(it.isFocused)onSelect()}.testTag("reps:${target.id}").semantics { contentDescription="Set $number reps" })
            }
            Checkbox(false,{if(valid)onComplete(weight,reps)},enabled=!target.skipped&&valid&&!busy&&!readOnly,modifier=Modifier.size(48.dp).testTag("complete-target:${target.id}").semantics { contentDescription="Complete set $number" })
        }
        if(selected && target.repsMin!=target.repsMax && target.repsMin!=null)Text("Target: ${target.repsMin}–${target.repsMax} reps",Modifier.padding(start=48.dp,bottom=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(weightEditor) {
        var draft by remember{mutableStateOf(weight)}
        AlertDialog(onDismissRequest={weightEditor=false},title={Text("Weight")},text={Column{OutlinedTextField(draft,{draft=it},label={Text("kg")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal));TextButton(onClick={onApply(if(draft.isBlank())"" else "$draft kg");weight=draft;weightEditor=false},enabled=!busy){Text("Apply weight to remaining sets")}}},confirmButton={TextButton(onClick={weight=draft;weightEditor=false}){Text("Done")}},dismissButton={TextButton(onClick={weightEditor=false}){Text("Cancel")}})
    }
}

@Composable
private fun SetEditDialog(set:NotebookSet?,exercise:NotebookExercise,session:NotebookSession,model:FitNotesViewModel,busy:Boolean,hasPlan:Boolean,defaultMode:String,initialWeight:String,initialReps:String,onClose:()->Unit) {
    var weight by rememberSaveable(set?.key){mutableStateOf(if(set==null)initialWeight else set.weight?.let(::weightText).orEmpty())}
    var reps by rememberSaveable(set?.key){mutableStateOf(if(set==null)initialReps else set.reps?.toString().orEmpty())}
    var mode by rememberSaveable(set?.key){mutableStateOf(set?.local?.loadMode ?: defaultMode)}
    var note by rememberSaveable(set?.key){mutableStateOf(set?.comment.orEmpty())}
    var cardio by rememberSaveable(set?.key){mutableStateOf(set?.cardio==true)}
    var distance by rememberSaveable(set?.key){mutableStateOf(set?.distance?.let(::weightText).orEmpty())}
    var seconds by rememberSaveable(set?.key){mutableStateOf(set?.seconds?.let(::weightText).orEmpty())}
    var unit by rememberSaveable(set?.key){mutableStateOf(set?.unit ?: "km")}
    var settings by remember { mutableStateOf(false) }
    var showSetNote by rememberSaveable(set?.key){mutableStateOf(set?.comment?.isNotBlank()==true)}
    var delete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=onClose,title={Text(if(set==null)"Add set" else "Edit set")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(!cardio) { OutlinedTextField(weight,{weight=it},label={Text(when(mode){LoadMode.BODYWEIGHT->"Added kg";LoadMode.ASSISTANCE->"Assistance kg";else->"kg"})},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("edit-weight"));OutlinedTextField(reps,{reps=it},label={Text("Reps")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("edit-reps")) }
        else { OutlinedTextField(distance,{distance=it},label={Text("Distance")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal));UnitPicker(unit,{unit=it});OutlinedTextField(seconds,{seconds=it},label={Text("Seconds")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal)) }
        if(showSetNote) OutlinedTextField(note,{note=it.take(10000)},label={Text("Set note")},modifier=Modifier.fillMaxWidth(),maxLines=3)
        else TextButton(onClick={showSetNote=true}){Text("Add note")}
        TextButton(onClick={settings=!settings}){Text("Set options")}
        if(settings) { if(session.local!=null)LoadModePicker(mode,{mode=it},!busy);if(set==null)TextButton(onClick={cardio=!cardio}){Text(if(cardio)"Strength" else "Distance / time")};if(hasPlan&&exercise.entryId!=null&&!cardio)TextButton(onClick={model.applyRemainingWeight(exercise.entryId,if(weight.isBlank())"" else "$weight kg")},enabled=!busy){Text("Apply weight to remaining sets")};if(set!=null)TextButton(onClick={delete=true}){Text("Delete set")} }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={
        try {
            if(cardio){require(optionalNonNegative(distance)!=null||optionalNonNegative(seconds)!=null){"Enter distance or time."}}else InputRules.parseSet(if(weight.isBlank())"0" else weight,reps)
            if(exercise.entryId!=null) {
                if(cardio)model.logCardio(exercise.entryId,distance,unit,seconds,note,set?.local?.id,onClose)
                else model.logStrength(exercise.entryId,weight,reps,set?.local?.id,note,onClose,loadMode=mode,isWarmUp=set?.local?.isWarmUp ?: false)
            } else model.logImported(session.imported!!.workout.id,exercise.sourceName ?: exercise.name,exercise.category,set?.imported?.id,if(cardio)"" else weight,if(cardio)"" else reps,if(cardio)distance else "",unit,if(cardio)seconds else "",note,onClose)
        }catch(e:Exception){error=e.message}
    },enabled=!busy){Text(if(set==null)"Complete set" else "Save changes")}},dismissButton={TextButton(onClick=onClose,enabled=!busy){Text("Cancel")}})
    if(delete&&set!=null)NotebookConfirm("Delete set?","This removes the saved set.","Delete",busy,{delete=false}){if(set.local!=null)model.deleteSet(set.local.id){delete=false;onClose()}else{model.deleteImportedRecord(set.imported!!.id);delete=false;onClose()}}
}
