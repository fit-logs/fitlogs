package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import kotlinx.coroutines.delay

@Composable
fun BodyOverviewScreen(data: AppData, model: FitNotesViewModel, busy: Boolean, state: LazyListState, savedProfile: BodyProfile?, onProfile: () -> Unit, onMeasurements: () -> Unit) {
    // Refresh the device-local date while open, including a midnight/timezone change.
    var today by remember { mutableStateOf(bodyLocalDate()) }
    LaunchedEffect(Unit) { while (true) { today = bodyLocalDate(); delay(30_000) } }
    val weights = remember(data.imported.body) { weightReadings(data.imported.body) }
    val latest = latestWeight(weights)
    val profile = savedProfile ?: BodyProfile(weightUnit = latest?.record?.unit?.let(::canonicalWeightUnit) ?: "kg")
    val comparison = remember(weights, today) { fourWeekComparison(weights, today) }
    val value = bmi(latest?.kg, profile.heightCm)
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BodyMeasurement?>(null) }
    var editing by remember { mutableStateOf<BodyMeasurement?>(null) }
    var deleting by remember { mutableStateOf<BodyMeasurement?>(null) }
    var info by remember { mutableStateOf(false) }
    var comparisonInfo by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("body-overview"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Latest weight", style = MaterialTheme.typography.titleMedium)
            if (latest != null) {
                Text(bodyWeightText(latest.kg, profile.weightUnit), style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"), modifier = Modifier.padding(top = 6.dp).testTag("latest-weight"))
                Text(prettyDay(latest.record.date, "d MMM yyyy") + latest.record.time.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("latest-weight-date"))
            } else if (data.imported.loading) Text("Loading measurements…")
            else Text("Log your first weight to see its history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { adding = true }, enabled = !busy, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) { Text("Log weight") }
        }
        item {
            // Convert for presentation only. Selection resolves back to the original record and unit.
            val display = weights.map { it.record.copy(name = "Weight", value = weightInUnit(it.kg, profile.weightUnit), unit = profile.weightUnit) }
            MeasurementTrend(display, "Weight", profile.weightUnit, selectedRange = profile.chartRange, onRangeChanged = { model.bodyChartRange(it, profile.weightUnit) }, title = "Weight history") { point ->
                selected = weights.firstOrNull { it.record.id == point.id }?.record
            }
        }
        if (comparison != null) item {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text("Average weight", style = MaterialTheme.typography.titleMedium)
                    Text(bodyWeightText(comparison.recent.meanKg, profile.weightUnit), style = MaterialTheme.typography.titleLarge)
                    Text(comparison.text(profile.weightUnit), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { comparisonInfo = true }) { Icon(Icons.Outlined.Info, "Four-week comparison details") }
            }
        }
        item {
            HorizontalDivider()
            if (profile.heightCm == null) TextButton(onClick = onProfile, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add height to calculate BMI") }
            else if (value != null && latest != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("BMI ${bodyDecimal(value)}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("current-bmi"))
                        if (adultBmiEligible(profile.birthDate, today)) adultBmiCategory(value)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text("Weight recorded ${prettyDay(latest.record.date, "d MMM yyyy")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, "About BMI") }
                }
                if (profile.birthDate == null) TextButton(onClick = onProfile, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add birth date for age-appropriate interpretation") }
                else if (!adultBmiEligible(profile.birthDate, today)) Text("Adult BMI categories apply from age 20.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            TextButton(onClick = onMeasurements, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("All body measurements ›") }
        }
    }
    if (adding || editing != null) MeasurementDialog(editing, data.imported.body, "Bodyweight" to profile.weightUnit, busy, { adding = false; editing = null }, weightOnly = true) { id, name, number, unit, unitName, date, time, note ->
        model.saveMeasurement(id, name, number, unit, unitName, date, time, note) { adding = false; editing = null }
    }
    selected?.let { record -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Weight · ${prettyDay(record.date, "d MMM yyyy")}") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${weightText(record.value)} ${displayUnit(record.unit)}", style = MaterialTheme.typography.titleLarge) }
            item { Text(record.time.ifBlank { "Date only" }) }
            if (record.comment.isNotBlank()) item { Text(record.comment) }
            item { Text(data.imported.imports.firstOrNull { it.id == record.importId }?.source ?: "Saved measurement", style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = { selected = null; editing = record }) { Text("Edit") } }, dismissButton = {
        Row { TextButton(onClick = { selected = null; deleting = record }) { Text("Delete") }; TextButton(onClick = { selected = null }) { Text("Close") } }
    }) }
    deleting?.let { record -> NotebookConfirm("Delete measurement?", "This removes the saved value from ${prettyDay(record.date, "d MMM yyyy")}.", "Delete", busy, { deleting = null }) { model.deleteMeasurement(record.id) { deleting = null } } }
    if (comparisonInfo && comparison != null) AlertDialog(onDismissRequest = { comparisonInfo = false }, title = { Text("Four-week comparison") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Recent" to comparison.recent, "Previous" to comparison.previous).forEach { (label, window) -> item {
                Text("$label: ${prettyDay(window.from, "d MMM yyyy")} – ${prettyDay(window.to, "d MMM yyyy")}")
                Text(bodyWeightText(window.meanKg, profile.weightUnit) + " · ${window.measuredDays} measured days")
            } }
            item { Text("Each measured day has equal influence: first average that day’s readings, then average the daily values. Each window needs three measured days spanning at least seven days. Missing days are not filled in.", style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = { comparisonInfo = false }) { Text("Close") } })
    if (info) AlertDialog(onDismissRequest = { info = false }, title = { Text("About BMI") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("BMI compares weight with height. It does not distinguish muscle from fat and is a screening measure, not a diagnosis.") }
            item { Text("BMI = weight in kilograms ÷ height in metres squared. CDC adult categories apply from age 20. Categories use the unrounded value; the display uses one decimal place.") }
            item { TextButton(onClick = { uri.openUri("https://www.cdc.gov/bmi/adult-calculator/bmi-categories.html") }) { Text("CDC adult BMI categories ↗") } }
            item { TextButton(onClick = { uri.openUri("https://www.cdc.gov/bmi/about/index.html") }) { Text("CDC: About BMI ↗") } }
        }
    }, confirmButton = { TextButton(onClick = { info = false }) { Text("Close") } })
}
