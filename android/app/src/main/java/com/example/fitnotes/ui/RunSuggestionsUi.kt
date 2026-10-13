package com.example.fitnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

fun suggestedActualWeight(target: PlanSetTarget): String = targetWeightKg(target)

@Composable
fun RunSuggestions(exercise: RunExerciseDetail, model: FitNotesViewModel, busy: Boolean, editable: Boolean,
    results: List<NotebookSet>, previous: List<NotebookSet?>, previousLinked: Boolean, currentId: Long?, canComplete: Boolean, onCompleteCurrent: () -> Unit, onUse: (WorkoutSetTarget) -> Unit, onEdit: (NotebookSet) -> Unit, onExtra: () -> Unit) {
    var showEditor by rememberSaveable(exercise.entry.id) { mutableStateOf(false) }
    var apply by rememberSaveable { mutableStateOf(false) }
    var appliedWeight by rememberSaveable { mutableStateOf("") }
    var undo by remember { mutableStateOf<Long?>(null) }
    val error by model.planImportError.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Workout suggestions · not completed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if(exercise.entry.supersetGroup.isNotBlank())Text("Superset ${exercise.entry.supersetGroup} · alternate exercises by set",style=MaterialTheme.typography.bodySmall)
        if (exercise.entry.planNote.isNotBlank()) Text(exercise.entry.planNote)
        if(LocalDensity.current.fontScale>=1.3f)Text("Swipe across the table to see all columns.",style=MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val large=LocalDensity.current.fontScale>=1.3f
        val tableWidth=if(large)maxWidth.coerceAtLeast(600.dp) else maxWidth
        val numberWidth=if(large)48.dp else 30.dp
        val doneWidth=if(large)64.dp else 48.dp
        Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Set",Modifier.width(numberWidth),style=MaterialTheme.typography.labelSmall)
            listOf("Previous","Target","Actual").forEach{Text(it,Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)}
            Text("Done",Modifier.width(doneWidth),style=MaterialTheme.typography.labelSmall)
        }
        exercise.ordered.forEachIndexed { index, set ->
            val actual=results.firstOrNull{it.local?.targetId==set.id}
            val before=previous.getOrNull(index)
            Surface(color=if(currentId==set.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().testTag("run-target:${set.id}")) {
                Column(Modifier.padding(horizontal=6.dp,vertical=4.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("${index+1}"+(if(set.isWarmUp)"\nW-up" else ""),Modifier.width(numberWidth),style=MaterialTheme.typography.bodySmall)
                        Text(before?.let{(it.weight?.let{kg->"${weightText(kg)} kg\n"}.orEmpty())+"${it.reps ?: "—"} reps"+(when(it.local?.loadMode){LoadMode.BODYWEIGHT->"\nadded load";LoadMode.ASSISTANCE->"\nassistance";LoadMode.REPS_ONLY->"\nreps only";null,LoadMode.UNKNOWN->"\nmode unknown";else->""})} ?: "—",Modifier.weight(1f).padding(end=6.dp),style=MaterialTheme.typography.bodySmall)
                        Text(set.target.text,Modifier.weight(1f).padding(end=6.dp),style=MaterialTheme.typography.bodySmall)
                        Text(if(set.skipped)"Skipped" else actual?.text ?: "Pending",Modifier.weight(1f).padding(end=6.dp).testTag("target-result:${set.id}").then(if(actual!=null && editable)Modifier.clickable(enabled=!busy){onEdit(actual)} else Modifier),style=MaterialTheme.typography.bodySmall)
                        Checkbox(checked=actual!=null,onCheckedChange=if(editable && !set.skipped)({checked->if(!checked)undo=set.id else if(currentId==set.id)onCompleteCurrent() else onUse(set)}) else null,enabled=!busy && (actual!=null || currentId!=set.id || canComplete),modifier=Modifier.width(doneWidth).testTag("complete-target:${set.id}"))
                    }
                    if(editable && (currentId==set.id || actual!=null || set.skipped))FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(actual==null && !set.skipped) {
                            TextButton(onClick={onUse(set)},enabled=!busy){Text(if(currentId==set.id)"Current set" else if(set.weightUnit=="lb")"Use in kg" else "Use")}
                            TextButton(onClick={model.skipTarget(set.id,true)},enabled=!busy){Text("Skip")}
                        }
                        if(set.skipped)TextButton(onClick={model.skipTarget(set.id,false)},enabled=!busy){Text("Unskip")}
                        if(actual!=null)TextButton(onClick={onEdit(actual)},enabled=!busy){Text("Edit result")}
                        if(set.restSeconds!=null && set.restSeconds>0)TextButton(onClick={model.startRest(exercise.entry.workoutId,set.restSeconds,set.id)},enabled=!busy){Text("Rest ${set.restSeconds}s")}
                    }
                }
            }
        }
        }
        }
        Text((if(previousLinked)"Previous values follow that session's linked target order." else "Previous values are shown by set order; historical target links are unknown.")+" Save set or the current set’s checkbox records training.",style=MaterialTheme.typography.bodySmall)
        if (exercise.ordered.any { it.weightUnit == "lb" }) Text("lb suggestions copied to the actual kg input use 0.45359237 kg/lb. Original target units remain intact.", style = MaterialTheme.typography.bodySmall)
        if (editable) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=onExtra,enabled=!busy){Text("Add extra set")}
            TextButton(onClick={model.addRunTarget(exercise.entry.id)},enabled=!busy){Text("Add target set")}
            TextButton(onClick={apply=!apply},enabled=!busy){Text("Apply weight to remaining sets")}
            TextButton(onClick={showEditor=!showEditor}){Text(if(showEditor)"Close suggestion editor" else "Edit workout suggestions")}
        }
        if(apply) {
            OutlinedTextField(appliedWeight,{appliedWeight=it},label={Text("Pending weight + unit")},supportingText={Text("Only unskipped targets with no recorded result will change.")},modifier=Modifier.fillMaxWidth())
            TextButton(onClick={model.applyRemainingWeight(exercise.entry.id,appliedWeight)},enabled=!busy){Text("Apply pending weight")}
        }
        if (showEditor) exercise.ordered.forEachIndexed { index, set ->
            var draft by remember(set.id, set.target) { mutableStateOf(set.target.editable()) }
            SetTargetEditor(draft, index + 1, busy, { draft = it })
            FlowRow {
                TextButton(onClick={model.saveRunSuggestion(set.id,draft,exercise.exercise.name){}},enabled=!busy){Text("Save suggestion ${index+1}")}
                TextButton(onClick={model.moveRunTarget(set.id,-1)},enabled=!busy&&index>0){Text("Move set up")}
                TextButton(onClick={model.moveRunTarget(set.id,1)},enabled=!busy&&index<exercise.ordered.lastIndex){Text("Move set down")}
            }
        }
        if (showEditor && error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
    }
    undo?.let{id->NotebookConfirm("Undo completion?","This removes the performed set and returns its target to pending. The original plan stays unchanged.","Undo",busy,{undo=null}){model.undoTarget(id);undo=null}}
}

@Composable
fun RestControls(model:FitNotesViewModel,workoutId:Long,busy:Boolean) {
    val timer by model.restTimer.collectAsState()
    val current=timer?.takeIf{it.workoutId==workoutId} ?: return
    var remaining by remember(current.token){mutableLongStateOf(current.remaining(model.restController.clock))}
    LaunchedEffect(current) {
        do {
            remaining=current.remaining(model.restController.clock)
            if(current.running && remaining==0L){model.restFinished(current.token);break}
            kotlinx.coroutines.delay(250)
        } while(current.running)
    }
    Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.fillMaxWidth().testTag("rest-controls")) {
        Column(Modifier.padding(10.dp)) {
            Text(if(current.alerted)"Rest finished" else "Rest ${if(current.running)"" else "paused · "}${(remaining+999)/1000/60}:${(((remaining+999)/1000)%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.titleSmall)
            FlowRow {
                if(!current.alerted) {
                    TextButton(onClick={model.restAction(current.token,if(current.running)"pause" else "resume")},enabled=!busy){Text(if(current.running)"Pause rest" else "Resume rest")}
                    TextButton(onClick={model.restAction(current.token,"extend")},enabled=!busy){Text("+30 seconds")}
                } else TextButton(onClick={model.restAction(current.token,"extend")},enabled=!busy){Text("+30 seconds")}
                TextButton(onClick={model.restAction(current.token,"skip")},enabled=!busy){Text("Skip rest")}
            }
        }
    }
}
