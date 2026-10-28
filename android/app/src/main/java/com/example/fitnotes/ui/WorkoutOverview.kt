package com.example.fitnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun WorkoutOverview(session: NotebookSession?, day: String, sessions: List<NotebookSession>, data: AppData, model: FitNotesViewModel,
    busy: Boolean, editable: Boolean, state: LazyListState, onDay: (String) -> Unit, onSession: (String?) -> Unit,
    onExercise: (String) -> Unit, onStart: () -> Unit, onAdd: () -> Unit, onEdit: () -> Unit, onShare: (String) -> Unit,
    onRecoverPlan: (Long) -> Unit, onImport: () -> Unit, onHealthImport: () -> Unit, onBackup: () -> Unit, onChatGPT: () -> Unit, onPastePlan: () -> Unit, onPlans: () -> Unit,
    onProgress: () -> Unit = {}, onLibrary: () -> Unit = {}, onTrainingSettings: () -> Unit = {}, onBack: (() -> Unit)? = null, onDeleted: () -> Unit, onFinished: () -> Unit, onRepeat: () -> Unit, onSavePast: () -> Unit = {}, onEditTime: () -> Unit = {}, onLogPast: () -> Unit = {}, onUseAsPlan: () -> Unit = {}) {
    var details by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var finish by remember { mutableStateOf(false) }
    var reorder by rememberSaveable(session?.key) { mutableStateOf(false) }
    val runs by model.planRuns.collectAsState()
    val run = session?.local?.workout?.id?.let { id -> runs.firstOrNull { it.run.workoutId == id } }
    val pending = run?.exercises?.flatMap { e -> pendingTargets(e, session?.local?.entries?.flatMap { it.sets }.orEmpty()) }.orEmpty()
    val currentEntry = run?.let { nextPlannedSet(it,session?.local?.entries?.flatMap { it.sets }.orEmpty()) }?.entryId
        ?: session?.exercises?.lastOrNull { it.sets.isNotEmpty() }?.entryId ?: session?.exercises?.firstOrNull()?.entryId
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session?.key,session?.completed) { while(session!=null && !session.completed) { now=System.currentTimeMillis();kotlinx.coroutines.delay(1000) } }
    Column(Modifier.fillMaxSize()) {
        NotebookBar(session?.title?.ifBlank { "Workout" } ?: "Train",onBack) {
            if(session?.local!=null && !session.completed && !session.local.workout.historicalDraft) TextButton(onClick={
                if(pending.isNotEmpty())finish=true else model.finish(session.local.workout.id){onFinished()}
            },enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Finish")}
            if(session!=null) OverflowMenu { close ->
                if(session.completed && !editable) MenuAction("Edit workout"){close();onEdit()}
                if(session.local != null) MenuAction("Edit time / duration"){close();onEditTime()}
                if(editable) MenuAction("Edit details"){close();details=true}
                if(editable && session.local!=null && session.exercises.size>1) MenuAction("Reorder exercises"){close();reorder=true}
                MenuAction("Share"){close();onShare(sessionContext(session,data))}
                if(session.completed && session.activity=="strength" && session.exercises.isNotEmpty()) MenuAction("Use as plan"){close();model.planFromSession(session);onPastePlan()}
                if(session.local?.let{eligibleEmptySuggestion(it,run!=null)}==true)MenuAction("Convert empty import to plan"){close();onRecoverPlan(session.local.workout.id)}
                MenuAction("Delete"){close();delete=true}
            }
        }
        if(session?.local?.workout?.historicalDraft == true) {
            TextButton(onClick=onSavePast,enabled=!busy,modifier=Modifier.padding(horizontal=12.dp)){Text("Save past workout")}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={onDay(moveDay(day,-1))}){Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft,"Previous day")}
            Text(prettyDay(day))
            TextButton(onClick={onDay(moveDay(day,1))},enabled=day<todayDate()){Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,"Next day")}
        }
        if(session==null) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("workout-overview"),contentPadding=PaddingValues(16.dp)) {
                item { Text("No workout selected"); Text("No workouts on this day.") }
                if(day<todayDate()) item { OutlinedButton(onClick=onLogPast,enabled=!busy,modifier=Modifier.testTag("log-workout-for-day")){Text("Log workout for this day")} }
            }
            return@Column
        }
        LazyColumn(state=state,modifier=Modifier.weight(1f).testTag("workout-overview"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(day<todayDate()) item { OutlinedButton(onClick=onLogPast,enabled=!busy,modifier=Modifier.testTag("log-workout-for-day")){Text("Log workout for this day")} }
            item { Text(if(session.completed) prettyDay(session.date,"EEE d MMM yyyy")+session.duration?.let{" · "+durationText(it)}.orEmpty()
                else session.startedMillis?.takeIf{it<=now}?.let{durationText((now-it)/1000.0)}.orEmpty(),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(session.local!=null && session.activity!="strength")item{ActivityPanel(session,editable,model,busy)}
            items(session.exercises,key={it.key}) { exercise ->
                if(!session.completed) {
                    val targets=run?.exercises?.firstOrNull{it.entry.id==exercise.entryId}
                    val progress=exerciseSetProgress(exercise,targets)
                    Surface(onClick={onExercise(exercise.key)},color=if(currentEntry==exercise.entryId)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.5f) else MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(exercise.name,style=MaterialTheme.typography.titleMedium,color=if(currentEntry==exercise.entryId)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(progress.label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            };Text(if(progress.allTargetsCompleted)"✓" else "›",Modifier.padding(start=8.dp),color=MaterialTheme.colorScheme.primary)
                        }
                    };HorizontalDivider()
                } else Column(Modifier.fillMaxWidth()) {
                    Text(exercise.name,Modifier.fillMaxWidth().heightIn(min=48.dp).clickable { onExercise(exercise.key) }.padding(vertical=10.dp),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface)
                    if(exercise.sets.isNotEmpty()) { val columns=setColumns(exercise.sets);NotebookSetHeader(columns);exercise.sets.forEachIndexed { index,set->NotebookSetRow(set,index+1,columns=columns,compact=true);HorizontalDivider() } }
                }
            }
            if(editable && session.local!=null && session.activity=="strength")item{TextButton(onClick=onAdd,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Filled.Add,null);Spacer(Modifier.width(8.dp));Text("Add exercise")}}
            val notes=listOf(session.notes,run?.run?.warmUp.orEmpty(),run?.run?.legacyGuidance.orEmpty()).filter{it.isNotBlank()}.distinct().joinToString("\n\n")
            if(notes.isNotBlank())item{CollapsedGuidance("Workout notes",notes)}
            if(session.completed)item{HeartRateChart(session.local?.health ?: session.imported?.health.orEmpty())}
        }
    }
    if(reorder && session?.local!=null) ReorderExercisesDialog(session,busy,model){reorder=false}
    if(details && session?.local!=null)ActivityDetailsDialog(session.local.workout,busy,{details=false}){title,notes,effort,distance,unit->model.saveActivityDetails(session.local.workout.id,title,notes,effort,distance,unit){details=false}}
    if(details && session?.imported!=null)SessionDetailsDialog(session,busy,{details=false}){_,notes->model.saveImportedNotes(session.imported.workout.id,notes);details=false}
    if(finish && session?.local!=null)NotebookConfirm("Finish workout?","${pending.size} planned sets are unfinished.","Finish",busy,{finish=false}){model.finish(session.local.workout.id){finish=false;onFinished()}}
    if(delete && session!=null)NotebookConfirm("Delete workout?","This deletes the workout and its saved sets.","Delete",busy,{delete=false}){if(session.local!=null)model.deleteWorkout(session.local.workout.id){delete=false;onDeleted()} else model.deleteImportedWorkout(session.imported!!.workout.id){delete=false;onDeleted()}}
}
@Composable
private fun SessionDetailsDialog(session: NotebookSession, busy: Boolean, onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(session.title) }; var notes by remember { mutableStateOf(session.notes) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Workout details") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (session.local != null) OutlinedTextField(title, { title = it.take(200) }, label = { Text("Title") }, singleLine = true)
            OutlinedTextField(notes, { notes = it.take(10000) }, label = { Text("Notes") }, minLines = 2, maxLines = 5)
        }
    }, confirmButton = { TextButton(onClick = { onSave(title, notes) }, enabled = !busy) { Text("Save") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
