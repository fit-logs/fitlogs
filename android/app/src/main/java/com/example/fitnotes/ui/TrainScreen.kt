package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

val LocalSettingsAction = staticCompositionLocalOf<() -> Unit> { {} }
@Composable fun SettingsButton() { val open=LocalSettingsAction.current;IconButton(onClick=open){Icon(Icons.Filled.Settings,"Settings")} }

@Composable
fun TrainScreen(data:AppData,model:FitNotesViewModel,busy:Boolean,onResume:()->Unit,onStart:()->Unit,onFinish:()->Unit,onDiscard:()->Unit,onPlan:(String)->Unit,onNew:()->Unit,onAsk:()->Unit,onImport:()->Unit,onPast:()->Unit={},onDraft:(Long)->Unit={},onEditTime:(Long)->Unit={},onResumeFinished:(Long)->Unit={},onDiscardDraft:(Long)->Unit={},onProgramStart:(String)->Unit={},onImportProgram:()->Unit={}) {
    var section by rememberSaveable { mutableStateOf("") }
    val programs by model.programs.collectAsState()
    val programSessions by model.programSessions.collectAsState()
    val programError by model.programError.collectAsState()
    val primary = programs.firstOrNull { it.program.status == "active" }
    val drafts by model.pastDrafts.collectAsState()
    val notice = (data.workouts + drafts).filter { it.workout.autoFinishedAt != null && !it.workout.autoFinishAcknowledged }.maxByOrNull { it.workout.autoFinishedAt!! }
    Column(Modifier.fillMaxSize()) {
        NotebookBar("Train") { SettingsButton() }
        LazyColumn(Modifier.fillMaxSize().testTag("train-screen"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if (notice != null) item {
                OutlinedCard(Modifier.fillMaxWidth().testTag("auto-finish-notice")) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(if(notice.workout.historicalDraft) "Empty session kept as a draft" else "We auto-finished your workout. Change end time",style=MaterialTheme.typography.titleMedium)
                    Text(if(notice.workout.durationUnknown) "Duration is unknown. Saved sets and notes are unchanged." else "Timing is estimated. Saved sets and notes are unchanged.")
                    FlowRow {
                        TextButton(onClick={onEditTime(notice.workout.id)},enabled=!busy){Text("Edit time / duration")}
                        TextButton(onClick={onResumeFinished(notice.workout.id)},enabled=!busy && data.active==null && notice.workout.knownStart!=null){Text("Undo / Resume")}
                        TextButton(onClick={model.acknowledgeAutoFinish(notice.workout.id)},enabled=!busy){Text("Dismiss")}
                    }
                } }
            }
            item {
                ProgramNextCard(primary, data, programSessions, busy, { primary?.let { onProgramStart(it.program.id) } }, { section = "programs" }, { id -> primary?.let { model.completeProgramDay(it.program.id,id) } })
                primary?.next?.let{next->TextButton(onClick={model.reviewPlanWeights(next.day.planId)},enabled=!busy){Text("Suggested weights · Review / Change")}}
                programError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                data.active?.let { Text(it.workout.title.ifBlank{"Workout in progress"},style=MaterialTheme.typography.titleMedium);Text("${it.entries.size} exercises · ${it.entries.sumOf{e->e.sets.size}} sets",style=MaterialTheme.typography.bodyMedium) }
                Button(onClick=if(data.active!=null)onResume else onStart,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(data.active!=null)"Resume workout" else "Start empty workout")}
                data.active?.let { active ->
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick=onFinish,enabled=!busy && (active.workout.activityType!="strength" || active.entries.any{it.sets.isNotEmpty()}),modifier=Modifier.heightIn(min=48.dp)){Text("Finish workout")}
                        TextButton(onClick=onDiscard,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Discard workout")}
                        TextButton(onClick=onStart,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Start empty workout")}
                    }
                    TextButton(onClick={model.allowLongWorkout(active.workout.id)},enabled=!busy){Text("Keep running for 6 hours")}
                    RestControls(model,active.workout.id,busy)
                }
            } }
            item { OutlinedButton(onClick=onPast,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Log past workout")} }
            if(drafts.isNotEmpty())item{Text("Past workout drafts",style=MaterialTheme.typography.titleMedium)}
            items(drafts,key={"draft:${it.workout.id}"}){ draft ->
                OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text(localWorkoutDate(draft.workout)+" · "+draft.workout.title.ifBlank{"Past workout draft"},style=MaterialTheme.typography.titleMedium)
                    Text("${draft.entries.size} exercises · ${draft.entries.sumOf{it.sets.size}} draft sets")
                    FlowRow{TextButton(onClick={onDraft(draft.workout.id)},enabled=!busy){Text("Continue entry")};TextButton(onClick={onDiscardDraft(draft.workout.id)},enabled=!busy){Text("Discard draft")}}
                }}
            }
            item { HorizontalDivider(); FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={section="programs"},enabled=!busy){Text("My programs")}
                OutlinedButton(onClick={section="templates"},enabled=!busy){Text("My templates")}
            } }
            item { TextButton(onClick={section="explore"},enabled=!busy){Text("Explore programs / Import")} }

        }
    }
    if(section=="templates") TemplatesDialog(model,busy,{section=""},onPlan,onNew,onAsk,onImport)
    if(section=="programs") ProgramsDialog(model,data,busy,{section=""},onProgramStart,onPlan,importProgram={section="";onImportProgram()})
    if(section=="explore") AlertDialog(onDismissRequest={section=""},title={Text("Explore programs / Import")},text={Column{Text("Browse original programs, paste a complete program, or build ordered workout days from your templates.");TextButton(onClick={section="catalog"}){Text("Workout Library")}}},confirmButton={TextButton(onClick={section="";onImportProgram()}){Text("Import program")}},dismissButton={TextButton(onClick={section="programs"}){Text("My programs")}})
    if(section=="catalog") ProgramCatalogDialog(model,busy,{section="";model.catalogBack()},{section="";model.catalogBack();onImportProgram()})
}
fun planSubtitle(exercises:Int,minutes:Int)= "$exercises ${if(exercises==1) "exercise" else "exercises"}" + if(minutes>0) " · About $minutes min" else ""
