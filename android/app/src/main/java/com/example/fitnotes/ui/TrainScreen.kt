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

val LocalSettingsAction = staticCompositionLocalOf<() -> Unit> { {} }
@Composable fun SettingsButton() { val open=LocalSettingsAction.current;IconButton(onClick=open){Icon(Icons.Filled.Settings,"Settings")} }

@Composable
fun TrainScreen(data:AppData,model:FitNotesViewModel,busy:Boolean,onResume:()->Unit,onStart:()->Unit,onFinish:()->Unit,onDiscard:()->Unit,onPlan:(String)->Unit,onNew:()->Unit,onAsk:()->Unit,onImport:()->Unit) {
    val plans by model.plans.collectAsState()
    Column(Modifier.fillMaxSize()) {
        NotebookBar("Train") { SettingsButton() }
        LazyColumn(Modifier.fillMaxSize().testTag("train-screen"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                data.active?.let { Text(it.workout.title.ifBlank{"Workout in progress"},style=MaterialTheme.typography.titleMedium);Text("${it.entries.size} exercises · ${it.entries.sumOf{e->e.sets.size}} sets",style=MaterialTheme.typography.bodyMedium) }
                Button(onClick=if(data.active!=null)onResume else onStart,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(data.active!=null)"Resume workout" else "Start empty workout")}
                data.active?.let { active ->
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick=onFinish,enabled=!busy && (active.workout.activityType!="strength" || active.entries.any{it.sets.isNotEmpty()}),modifier=Modifier.heightIn(min=48.dp)){Text("Finish workout")}
                        TextButton(onClick=onDiscard,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Discard workout")}
                        TextButton(onClick=onStart,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Start empty workout")}
                    }
                    RestControls(model,active.workout.id,busy)
                }
            } }
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
