package com.example.fitnotes.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun LoadModePicker(value:String,onChange:(String)->Unit,enabled:Boolean) {
    var open by remember{mutableStateOf(false)}
    Box {
        TextButton(onClick={open=true},enabled=enabled){Text((if(value==LoadMode.UNKNOWN)"Load type" else LoadMode.label(value))+" ▾")}
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            LoadMode.values.forEach { mode->DropdownMenuItem(text={Text(if(mode==LoadMode.UNKNOWN)"Unspecified" else LoadMode.label(mode))},onClick={open=false;onChange(mode)}) }
        }
    }
}
@Composable
fun TrainingSettingsDialog(model:FitNotesViewModel,busy:Boolean,onClose:()->Unit) {
    val prefs by model.trainingPreferences.collectAsState()
    var permissionMessage by remember{mutableStateOf("")}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted)model.saveTrainingPreferences(prefs.copy(restAlerts=true)) else permissionMessage="Notifications remain off. The in-app rest countdown still works."
    }
    AlertDialog(onDismissRequest=onClose,title={Text("Training settings")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Rest starts after a newly logged target, when another working set remains. Supersets rest after the round; edits and skips never start rest.")
        Row { Checkbox(prefs.automaticRest,{model.saveTrainingPreferences(prefs.copy(automaticRest=it))},enabled=!busy);Text("Automatic rest",Modifier.padding(top=12.dp)) }
        Row { Checkbox(prefs.restAlerts,{checked->if(!checked)model.saveTrainingPreferences(prefs.copy(restAlerts=false)) else if(Build.VERSION.SDK_INT>=33)permissions.launch(Manifest.permission.POST_NOTIFICATIONS) else model.saveTrainingPreferences(prefs.copy(restAlerts=true))},enabled=!busy);Text("Rest notifications",Modifier.padding(top=12.dp)) }
        Text("Notifications can finish while the app is closed. Android may delay an alert while the device is idle; the countdown uses elapsed time. After a reboot or backup restore, a timer stays paused for review.",style=MaterialTheme.typography.bodySmall)
        if(permissionMessage.isNotBlank())Text(permissionMessage,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick=onClose){Text("Close")}})
}

@Composable
fun ExerciseLibraryScreen(model:FitNotesViewModel,data:AppData,busy:Boolean,onClose:()->Unit) {
    var selectedId by rememberSaveable { mutableStateOf(data.exercises.firstOrNull()?.id) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var merging by rememberSaveable { mutableStateOf(false) }
    var alias by rememberSaveable(selectedId){mutableStateOf("")}
    var sourceQuery by rememberSaveable { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<ExerciseSourceMapping?>(null) }
    var mapping by remember { mutableStateOf<ExerciseSourceMapping?>(null) }
    val selected=data.exercises.firstOrNull{it.id==selectedId}
    val preview by model.mergePreview.collectAsState()
    val error by model.trainingError.collectAsState()
    val configs by model.progression.collectAsState()
    val config=configs.firstOrNull{it.exerciseId==selectedId} ?: selectedId?.let{ExerciseProgression(it)}
    var increment by rememberSaveable(selectedId,config?.increment){mutableStateOf(config?.increment?:"2.5")}
    var unit by rememberSaveable(selectedId,config?.unit){mutableStateOf(config?.unit?:"kg")}
    val sources=remember(data.imported.workouts){data.imported.workouts.flatMap{session->session.sets.map{Triple(session.workout.importId,it.exercise,it.category)}}.distinct().sortedWith(compareBy({it.second},{it.third}))}
    HandoffScreen("Exercise library",busy,onClose,"exercise-library",footer={TextButton(onClick=onClose){Text("Close")}}) {
        error?.let { item{Text(it,color=MaterialTheme.colorScheme.error)} }
        item { TextButton(onClick={selecting=true}){Text((selected?.name?:"Select exercise")+" ▾")} }
        if(selected!=null) {
            item{Text("Aliases",style=MaterialTheme.typography.titleMedium)}
            items(data.exerciseAliases.filter{it.exerciseId==selected.id},key={"alias:${it.id}"}) { row->
                Row { Text(row.name,Modifier.weight(1f));TextButton(onClick={model.removeAlias(row.id)},enabled=!busy){Text("Remove alias")} }
            }
            item { OutlinedTextField(alias,{alias=it},label={Text("New alias")},modifier=Modifier.fillMaxWidth()) }
            item { FlowRow { TextButton(onClick={model.addAlias(selected.id,alias){alias=""}},enabled=!busy&&alias.isNotBlank()){Text("Add alias")};TextButton(onClick={merging=true},enabled=!busy){Text("Merge exercises")} } }
            item { Text("Aliases require an exact case/whitespace match. Fuzzy search ranks candidates; it never joins history automatically.",style=MaterialTheme.typography.bodySmall) }
            if(config!=null) {
                item{Text("Double progression",style=MaterialTheme.typography.titleMedium)}
                item { Row { Checkbox(config.enabled,{model.saveProgression(config.copy(enabled=it,increment=increment,unit=unit))},enabled=!busy);Text("Enable suggestions",Modifier.padding(top=12.dp)) } }
                item { OutlinedTextField(increment,{increment=it},label={Text("Suggested load increase")},modifier=Modifier.fillMaxWidth()) }
                item { FlowRow { listOf("kg","lb").forEach{choice->FilterChip(unit==choice,{unit=choice},label={Text(choice)})};TextButton(onClick={model.saveProgression(config.copy(increment=increment,unit=unit))},enabled=!busy){Text("Save progression setting")} } }
                item { Text("Suggestions support external and added-bodyweight loads. Assistance and reps-only work records normally. Every prescribed working set must be logged at its upper rep target and prescribed load. Warm-ups, skips, incomplete sessions and incompatible load modes cannot trigger an increase. Plans change only when you explicitly apply a suggestion.",style=MaterialTheme.typography.bodySmall) }
            }
        }
        item{HorizontalDivider();Text("Original history mappings",style=MaterialTheme.typography.titleMedium)}
        item { Text("Raw names, categories, source IDs and import fingerprints stay intact. Select a row to map that exact source/name/category.",style=MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(sourceQuery,{sourceQuery=it},label={Text("Search original names")},modifier=Modifier.fillMaxWidth()) }
        val identity=ExerciseIdentityIndex(data)
        items(sources.filter{it.second.contains(sourceQuery,true)||it.third.contains(sourceQuery,true)},key={"source:${it.first}:${it.second}:${it.third}"}) { (importId,name,category)->
            val id=identity.source(importId,name,category);val label=data.exercises.firstOrNull{it.id==id}?.name ?: "Unresolved"
            TextButton(onClick={selectedSource=ExerciseSourceMapping(importId,name,category,0)},enabled=!busy){Text("$name · ${category.ifBlank{"No category"}} → $label")}
        }
    }
    if(selecting||merging||selectedSource!=null) {
        androidx.compose.ui.window.Dialog(onDismissRequest={selecting=false;merging=false;selectedSource=null},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding()) { Column { if(merging)Text("Merge ${selected?.name} into…",Modifier.padding(16.dp),style=MaterialTheme.typography.titleLarge); ExercisePicker(data.exercises,if(merging)setOfNotNull(selectedId) else emptySet(),emptyList(),emptyMap(),busy,
                onCancel={selecting=false;merging=false;selectedSource=null},onSelect={ids->val chosen=ids.single();when{merging->model.previewExerciseMerge(chosen,selectedId!!);selectedSource!=null->mapping=selectedSource!!.copy(exerciseId=chosen);else->selectedId=chosen};selecting=false;merging=false;selectedSource=null},onCreate={},singleSelection=true,aliases=data.exerciseAliases,allowCreation=false) } }
        }
    }
    mapping?.let { value->NotebookConfirm("Save source mapping?","${value.sourceName} (${value.category}) will refer to ${data.exercises.firstOrNull{it.id==value.exerciseId}?.name}. Original source rows remain unchanged.","Save mapping",busy,{mapping=null}) { model.mapExerciseSource(value);mapping=null } }
    preview?.let { p->
        var confirmed by remember(p.fingerprint){mutableStateOf(false)}
        AlertDialog(onDismissRequest={model.mergePreview.value=null},title={Text("Review exercise merge")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Canonical name: ${p.survivor.name}. Merge ${p.removed.name}. ${p.workoutCount} workouts, ${p.planCount} plans, ${p.actualSets} actual sets and ${p.targetSets} targets. ${p.combinedWorkouts} workouts and ${p.combinedPlans} plans contain both names.")
            p.warnings.forEach{Text(it,Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodySmall)}
            p.blockers.forEach{Text(it,color=MaterialTheme.colorScheme.error)}
            Row{Checkbox(confirmed,{confirmed=it},modifier=Modifier.testTag("confirm-exercise-merge"));Text("These names describe the same movement",Modifier.padding(top=12.dp))}
        }},confirmButton={TextButton(onClick={model.confirmExerciseMerge()},enabled=!busy&&confirmed&&p.blockers.isEmpty()){Text("Merge exercises")}},dismissButton={TextButton(onClick={model.mergePreview.value=null}){Text("Cancel")}})
    }
}
