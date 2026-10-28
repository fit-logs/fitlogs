package com.example.fitnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun StrengthProgressScreen(data: AppData, model: FitNotesViewModel, busy: Boolean, onLibrary: () -> Unit, onClose: (() -> Unit)? = null,
    onWorkout: (String)->Unit = {}, modeSwitch: (@Composable () -> Unit)? = null) {
    val prefs=LocalContext.current.getSharedPreferences("strength-progress",0)
    val records=remember(data){observations(data).filter{it.completed&&it.activity=="strength"}}
    val exercises=remember(records){records.flatMap{it.results}.distinctBy{it.exerciseKey}.sortedBy{it.exerciseName.lowercase()}}
    val recentKeys=remember(records){records.sortedWith(compareByDescending<TrainingObservation>{it.date}.thenByDescending{it.started?:Long.MIN_VALUE})
        .flatMap{it.results.asReversed()}.map{it.exerciseKey}.distinct()}
    var selectedKey by rememberSaveable{mutableStateOf(prefs.getString("exercise",null))}
    var range by rememberSaveable{mutableStateOf(prefs.getString("range","3M")?.takeIf{it in listOf("1M","3M","1Y","All")}?:"3M")}
    var metricKey by rememberSaveable{mutableStateOf(prefs.getString("metric",null))}
    var choosing by remember{mutableStateOf(false)}
    var info by remember{mutableStateOf(false)}
    val redirectedKey=data.exerciseRedirects.firstOrNull{"exercise:${it.oldId}"==selectedKey}?.let{"exercise:${it.exerciseId}"}
    val selected=exercises.firstOrNull{it.exerciseKey==(redirectedKey?:selectedKey)}?:exercises.firstOrNull{it.exerciseKey==recentKeys.firstOrNull()}?:exercises.firstOrNull()
    val metrics=selected?.let{strengthMetrics(records,it.exerciseKey)}.orEmpty()
    val metric=metrics.firstOrNull{it.name==metricKey}?:selected?.let{defaultStrengthMetric(records,it.exerciseKey)}?:StrengthMetric.REPS
    LaunchedEffect(selected?.exerciseKey,range,metric) {
        selectedKey=selected?.exerciseKey;metricKey=metric.name
        prefs.edit().putString("exercise",selectedKey).putString("range",range).putString("metric",metric.name).apply()
    }
    val from=progressFrom(range,todayDate())
    val points=remember(records,selected,metric,from){selected?.let{strengthPoints(records,it.exerciseKey,metric,from)}.orEmpty()}
    val sessions=remember(records,selected,from){selected?.let{strengthSessions(records,it.exerciseKey,from)}.orEmpty()}
    val scroll=rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        NotebookBar("Progress",onClose){SettingsButton()}
        LazyColumn(Modifier.fillMaxSize().testTag("strength-progress"),state=scroll,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if (modeSwitch != null) item { modeSwitch() }
            if(exercises.isNotEmpty()) item { OutlinedButton(onClick={choosing=true},enabled=exercises.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text((selected?.exerciseName?:"Select exercise")+" ▾") } }
            if(exercises.isNotEmpty()) item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("1M","3M","1Y","All").forEach{value->FilterChip(range==value,{range=value},label={Text(value)},modifier=Modifier.heightIn(min=48.dp))}} }
            if(selected!=null) {
                item {
                    var open by remember{mutableStateOf(false)}
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            TextButton(onClick={open=true},modifier=Modifier.heightIn(min=48.dp)){Text(metric.label+" ▾")}
                            DropdownMenu(open,{open=false}){metrics.forEach{value->MenuAction(value.label){metricKey=value.name;open=false}}}
                        }
                        TextButton(onClick={info=true},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics { contentDescription="Metric information" }){Text("ⓘ")}
                    }
                }
                if(points.isEmpty()) item { Column(Modifier.padding(vertical=24.dp)) { Text("No records in this period");TextButton(onClick={range="All"}){Text("All time")} } }
                else {
                    item { StrengthSessionChart(points,metric,"volume-chart",onWorkout) }
                    item {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            val latestDay=points.filter{it.date==points.last().date}
                            val latest=if(latestDay.any{it.start==null}) latestDay else listOf(points.last())
                            listOf("Latest in period" to latest,"Best in period" to listOf(points.maxBy{it.value})).forEach{(label,values)->
                                Column(Modifier.weight(1f)) { Text(label,style=MaterialTheme.typography.bodySmall);Text(values.joinToString("\n"){strengthPointSummary(it,metric)},style=MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                }
                if(sessions.isNotEmpty()) {
                    item { Text("Recent sessions",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=8.dp)) }
                    items(sessions.asReversed(),key={it.id}) { session ->
                        var expanded by rememberSaveable(session.id){mutableStateOf(false)}
                        var overflow by remember(session.id){mutableStateOf(false)}
                        val results=compactStrengthResults(session.results.filter{it.exerciseKey==selected.exerciseKey})
                        Column(Modifier.fillMaxWidth().clickable{onWorkout(session.id)}.padding(vertical=8.dp)) {
                            Text(progressDateLabel(session.date,session.started),style=MaterialTheme.typography.labelLarge)
                            Text(results,style=MaterialTheme.typography.bodyLarge,maxLines=if(expanded)Int.MAX_VALUE else 2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,onTextLayout={overflow=it.hasVisualOverflow})
                            if(overflow||expanded) TextButton(onClick={expanded=!expanded},modifier=Modifier.heightIn(min=48.dp)){Text(if(expanded)"Show less" else "Show all sets")}
                        }
                        HorizontalDivider()
                    }
                }
            } else item { Text("Record a strength workout to see your progress.") }
        }
    }
    if(choosing) ProgressExercisePicker(exercises.map{e->ProgressExercise(e.exerciseKey,e.exerciseName,data.exerciseAliases.filter{"exercise:${it.exerciseId}"==e.exerciseKey}.map{it.name})},
        recentKeys,selected?.exerciseKey,{choosing=false},{selectedKey=it;choosing=false})
    if(info) AlertDialog(onDismissRequest={info=false},title={Text(metric.label)},text={Text(metric.definition)},confirmButton={TextButton(onClick={info=false}){Text("Close")}})
}
internal fun progressDateLabel(date:String,start:Long?)=prettyDay(date)+start?.let{" · "+java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(it))}.orEmpty()
