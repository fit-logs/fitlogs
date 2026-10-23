package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

val LocalSettingsAction = staticCompositionLocalOf<() -> Unit> { {} }
@Composable fun SettingsButton() { val open=LocalSettingsAction.current;IconButton(onClick=open){Icon(Icons.Filled.Settings,"Settings")} }

@Composable
fun TrainScreen(data:AppData,model:FitNotesViewModel,busy:Boolean,onResume:()->Unit,onStart:()->Unit,onFinish:()->Unit,onDiscard:()->Unit,onPlan:(String)->Unit,onNew:()->Unit,onAsk:()->Unit,onImport:()->Unit,onPast:()->Unit={},onDraft:(Long)->Unit={},onEditTime:(Long)->Unit={},onResumeFinished:(Long)->Unit={},onDiscardDraft:(Long)->Unit={}) {
    val plans by model.plans.collectAsState()
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
            item { HorizontalDivider(Modifier.padding(top=8.dp));Text("Saved plans",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=12.dp)) }
            item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick=onNew,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("New plan")}
                TextButton(onClick=onAsk,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Ask ChatGPT")}
                TextButton(onClick=onImport,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Import plan")}
            } }
            if(plans.isEmpty())item{Text("Save a plan for your next workout.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(plans,key={it.plan.id}) { plan ->
                Surface(onClick={onPlan(plan.plan.id)}) { Column(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(plan.plan.title,style=MaterialTheme.typography.titleMedium)
                    Text(planSubtitle(plan.exercises.size,plan.plan.durationMinutes),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } };HorizontalDivider()
            }
        }
    }
}
fun planSubtitle(exercises:Int,minutes:Int)= "$exercises exercises" + if(minutes>0) " · About $minutes min" else ""
