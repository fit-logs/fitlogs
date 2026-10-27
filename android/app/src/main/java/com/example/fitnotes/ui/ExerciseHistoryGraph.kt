package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fitnotes.data.*

/** Shared session plotting/details keep logger history consistent with Progress. */
@Composable
fun StrengthSessionChart(points:List<StrengthPoint>,metric:StrengthMetric,tag:String,onWorkout:(String)->Unit) {
    if(points.isEmpty())return
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    NotebookLineChart(points.map{point->ChartValue(progressDateCoordinate(point.date),point.value,progressDateLabel(point.date,point.start),point.segment)},metric.unit,
        prettyDay(points.first().date,"d MMM"),prettyDay(points.last().date,"d MMM"),220.dp,tag,onPoint={selectedDate=points[it].date})
    selectedDate?.let { date ->
        AlertDialog(onDismissRequest={selectedDate=null},title={Text(prettyDay(date,"d MMM yyyy"))},text={LazyColumn {
            items(points.filter{it.date==date},key={it.sessionId}) { point ->
                Text(progressDateLabel(point.date,point.start),style=MaterialTheme.typography.labelLarge)
                Text(metric.label+": "+strengthPointSummary(point,metric),style=MaterialTheme.typography.titleMedium)
                Text(compactStrengthResults(point.sets),Modifier.padding(top=4.dp))
                TextButton(onClick={selectedDate=null;onWorkout(point.sessionId)},modifier=Modifier.heightIn(min=48.dp)){Text("View workout")}
                HorizontalDivider()
            }
        }},confirmButton={TextButton(onClick={selectedDate=null}){Text("Close")}})
    }
}

@Composable
fun ExerciseHistoryGraph(records:List<TrainingObservation>,onWorkout:(String)->Unit) {
    val key="logger-history"
    var range by rememberSaveable { mutableStateOf("3M") }
    var metricKey by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val metrics=remember(records){strengthMetrics(records,key)}
    val metric=metrics.firstOrNull{it.name==metricKey}?:defaultStrengthMetric(records,key)
    val points=remember(records,range,metric){strengthPoints(records,key,metric,progressFrom(range,todayDate()))}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("1M","3M","1Y","All").forEach{value->FilterChip(range==value,{range=value},label={Text(value)},modifier=Modifier.heightIn(min=48.dp))}
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick={menu=true},modifier=Modifier.heightIn(min=48.dp)){Text(metric.label+" ▾")}
                DropdownMenu(menu,{menu=false}){metrics.forEach{value->MenuAction(value.label){metricKey=value.name;menu=false}}}
            }
            TextButton(onClick={info=true},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)){Text("ⓘ")}
        }
        if(points.isNotEmpty()) StrengthSessionChart(points,metric,"logger-history-chart",onWorkout)
        else Column { Text("No records in this period");TextButton(onClick={range="All"}){Text("All time")} }
    }
    if(info)AlertDialog(onDismissRequest={info=false},title={Text(metric.label)},text={Text(metric.definition)},confirmButton={TextButton(onClick={info=false}){Text("Close")}})
}
