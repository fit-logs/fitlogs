package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.fitnotes.AppData
import com.example.fitnotes.data.*

@Composable
fun NotebookHistory(sessions: List<NotebookSession>, data: AppData, state: LazyListState, onOpen: (String) -> Unit, onShare: () -> Unit, onImport: () -> Unit, onBackup: () -> Unit,
    onAsk: () -> Unit, onPastePlan: () -> Unit, onPlans: () -> Unit, onProgress: () -> Unit = {}, onLibrary: () -> Unit = {}, onTrainingSettings: () -> Unit = {}, onRun: (String) -> Unit = {}) {
    val preferences = LocalContext.current.getSharedPreferences("history-filter", 0)
    var activity by rememberSaveable { mutableStateOf(preferences.getString("activity", "all") ?: "all") }
    var filterOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rows = remember(sessions, data) { historyRows(sessions, runningRecords(data)) }
    val filtered = remember(rows, activity) { filterHistoryRows(rows, activity) }
    val filters = remember(rows) {
        listOf("all", "strength", "climbing", "running") +
            ActivityType.entries.map { it.key }.filter { key -> key !in listOf("strength", "climbing", "running") && rows.any { row -> row.activities.any { it.key == key } } }
    }
    val dateWidth = (48 * LocalDensity.current.fontScale).dp
    Column(Modifier.fillMaxSize()) {
        NotebookBar("History") {
            SettingsButton()
            OverflowMenu { close -> MenuAction("Share history"){close();onShare()} }
        }
        Box(Modifier.padding(horizontal = 16.dp)) {
            FilterChip(activity != "all", { filterOpen = true }, label = { Text(historyFilterLabel(activity)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("history-filter"))
            DropdownMenu(filterOpen, { filterOpen = false }) {
                filters.forEach { key -> DropdownMenuItem(
                    text = { Text(historyFilterLabel(key)) },
                    leadingIcon = { if (activity == key) Icon(Icons.Default.Check, contentDescription = "Selected") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("history-filter:$key"),
                    onClick = { activity = key; preferences.edit().putString("activity", key).apply(); filterOpen = false; scope.launch { state.scrollToItem(0) } }) }
            }
        }
        LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), modifier = Modifier.testTag("history-list")) {
            if (data.imported.loading) item { Text("Loading imported history…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (filtered.isEmpty() && !data.imported.loading) item { Text(if (activity == "all") "No completed workouts yet." else "No recorded activities of this type yet.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            filtered.groupBy { it.date.take(7) }.forEach { (month, rows) ->
                item(key = "month:$month") { Text(prettyDay(month + "-01", "MMMM yyyy"), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rows, key = { it.key }) { row ->
                    Surface(onClick = { row.runKey?.let(onRun) ?: onOpen(row.sessionKey) }, modifier = Modifier.testTag("history-session:${row.key}")) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 14.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(dateWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(prettyDay(row.date, "dd"), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
                                Text(prettyDay(row.date, "EEE").uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.activity.symbol + " " + row.title, style = MaterialTheme.typography.titleMedium)
                                if (row.subtitle.isNotBlank()) Text(row.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (row.summary.isNotBlank()) Text(row.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()

                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareRangeDialog(data: AppData, onCancel: () -> Unit, onPreview: (String) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var empty by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    fun prepare(first: String, last: String) {
        val text = rangeContext(data, first, last)
        if (text == null) empty = true else onPreview("Training context · $first to $last\n\n" + text)
    }
    if (!custom) AlertDialog(onDismissRequest = onCancel, title = { Text("Share training context") }, text = {
        Column {
            TextButton(onClick = { prepare(moveDay(todayDate(), -13), todayDate()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Last 14 days") }
            TextButton(onClick = { prepare(moveDay(todayDate(), -29), todayDate()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Last 30 days") }
            TextButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Custom date range") }
        }
    }, confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
    else {
        val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = pickerMillis(moveDay(todayDate(), -13)), initialSelectedEndDateMillis = pickerMillis(todayDate()), yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = onCancel, confirmButton = { TextButton(onClick = {
            val start = picker.selectedStartDateMillis; val end = picker.selectedEndDateMillis
            if (start != null && end != null && start <= end) prepare(pickerDay(start), pickerDay(end)) else invalid = true
        }) { Text("Preview") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }) {
            DateRangePicker(picker, modifier = Modifier.heightIn(max = 460.dp))
        }
    }
    if (empty || invalid) AlertDialog(onDismissRequest = { empty = false; invalid = false }, title = { Text(if (empty) "No activity in this range" else "Choose both dates") },
        text = { Text(if (empty) "There are no completed workouts or body measurements in the selected range." else "Choose a start and end date to preview your context.") },
        confirmButton = { TextButton(onClick = { empty = false; invalid = false }) { Text("OK") } })
}
