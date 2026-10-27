package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.fitnotes.data.*

@Composable
fun MeasurementTrend(records: List<BodyMeasurement>, name: String, unit: String, selectedRange: String? = null, onRangeChanged: (String) -> Unit = {}, title: String = "Trend", onRecord: (BodyMeasurement) -> Unit) {
    var localRange by rememberSaveable(name, unit) { mutableStateOf("All") }
    val range = selectedRange ?: localRange
    var selectedDay by rememberSaveable(name, unit, range) { mutableStateOf<String?>(null) }
    val from = progressFrom(range, todayDate())
    val points = remember(records, name, unit, from) { measurementPoints(records, name, unit, from) }
    val plotted = points.map { it.record }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1M", "3M", "1Y", "All").forEach { value ->
                FilterChip(range == value, { localRange = value; onRangeChanged(value) }, label = { Text(value) }, modifier = Modifier.heightIn(min = 48.dp).testTag("measurement-range:$value"))
            }
        }
        when {
            points.isEmpty() -> Text("No measurements in this period")
            !measurementPlotSupported(points) -> Text("These values are too large or precise for this chart. All measurements remain listed below.")
            else -> {
                val chart = points.map { point -> ChartValue(progressDateCoordinate(point.record.date), point.record.value, point.record.date, point.segment) }
                NotebookLineChart(chart, displayUnit(unit).ifBlank { "Value" }, prettyDay(plotted.first().date, "d MMM yy"), prettyDay(plotted.last().date, "d MMM yy"),
                    200.dp, "measurement-chart", onPoint = { index ->
                        val sameDay = plotted.filter { it.date == plotted[index].date }
                        if (sameDay.size == 1) onRecord(sameDay.single()) else selectedDay = plotted[index].date
                    }, description = "$name trend, ${plotted.size} measurements. ${prettyDay(plotted.first().date)} to ${prettyDay(plotted.last().date)}. Unit: ${displayUnit(unit).ifBlank { "unitless" }}.")
                TextButton(onClick = { selectedDay = "*" }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("View ${plotted.size} plotted " + if (plotted.size == 1) "measurement" else "measurements")
                }
            }
        }
    }
    selectedDay?.let { day ->
        val choices = plotted.filter { day == "*" || it.date == day }.asReversed()
        AlertDialog(onDismissRequest = { selectedDay = null }, title = { Text(if (day == "*") "Plotted measurements" else prettyDay(day, "d MMM yyyy")) }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(choices, key = { it.id }) { record ->
                    TextButton(onClick = { selectedDay = null; onRecord(record) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plot-record:${record.id}")) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(weightText(record.value) + if (unit.isBlank()) "" else " " + displayUnit(unit), style = MaterialTheme.typography.titleMedium)
                            Text(prettyDay(record.date, "d MMM yyyy") + " · " + record.time.ifBlank { "Date only" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }, confirmButton = { TextButton(onClick = { selectedDay = null }) { Text("Close") } })
    }
}
