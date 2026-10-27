package com.example.fitnotes.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import java.time.LocalDate

@Composable
fun ProgressModeSwitch(running: Boolean, onChange: (Boolean) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "Strength", true to "Running").forEach { (value, label) -> FilterChip(running == value, { onChange(value) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp)) }
    }
}
@Composable
fun ProgressScreen(data: AppData, model: FitNotesViewModel, busy: Boolean, onLibrary: () -> Unit, onWorkout: (String) -> Unit, onRun: (String) -> Unit, runs: List<RunRecord>) {
    val prefs = LocalContext.current.getSharedPreferences("running-progress", 0)
    var running by rememberSaveable { mutableStateOf(prefs.getBoolean("running", false)) }
    val change: (Boolean) -> Unit = { running = it; prefs.edit().putBoolean("running", it).apply() }
    if (running) RunningProgressScreen(data, onRun, change, runs)
    else StrengthProgressScreen(data, model, busy, onLibrary, onWorkout = onWorkout, modeSwitch = { ProgressModeSwitch(false, change) })
}
@Composable
fun RunRow(run: RunRecord, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(run.key) }.heightIn(min = 64.dp).padding(vertical = 12.dp).testTag("run-row:${run.key}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(run.title, style = MaterialTheme.typography.titleMedium)
        Text(prettyDay(run.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val summary = runSummary(run)
        if (summary.isNotBlank()) Text(summary)
        run.pace?.let { Text(run.preferredTime!!.first.paceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable
fun RunningProgressScreen(data: AppData, onRun: (String) -> Unit, onMode: (Boolean) -> Unit = {}, preparedRuns: List<RunRecord>? = null) {
    val prefs = LocalContext.current.getSharedPreferences("running-progress", 0)
    var range by rememberSaveable { mutableStateOf(prefs.getString("range", "3M")?.takeIf { it in listOf("1M", "3M", "1Y", "All") } ?: "3M") }
    val records = preparedRuns ?: remember(data) { runningRecords(data) }
    val stats = remember(records, range) { runningStats(records, progressFrom(range, todayDate())) }
    var info by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    LaunchedEffect(range) { prefs.edit().putString("range", range).apply() }
    Column(Modifier.fillMaxSize()) {
        NotebookBar("Progress") { SettingsButton() }
        LazyColumn(Modifier.fillMaxSize().testTag("running-progress"), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ProgressModeSwitch(true, onMode) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("1M", "3M", "1Y", "All").forEach { value -> FilterChip(range == value, { range = value }, label = { Text(value) }, modifier = Modifier.heightIn(min = 48.dp)) } } }
            if (stats.runs.isEmpty()) item { Text("No records in this period", Modifier.padding(top = 24.dp)); TextButton(onClick = { range = "All" }) { Text("All time") } }
            else {
                item { Text("Weekly recorded distance", style = MaterialTheme.typography.titleMedium); RunningWeekChart(stats.weeks) }
                item {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column { Text("Recorded distance", style = MaterialTheme.typography.bodySmall); Text(runDistanceText(stats.totalMetres), style = MaterialTheme.typography.titleLarge) }
                        Column { Text("Runs", style = MaterialTheme.typography.bodySmall); Text(stats.runs.size.toString(), style = MaterialTheme.typography.titleLarge) }
                        stats.longest?.let { longest -> Column { Text("Longest recorded run", style = MaterialTheme.typography.bodySmall); Text(runDistanceText(longest), style = MaterialTheme.typography.titleLarge) } }
                    }
                    TextButton(onClick = { info = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("About recorded distance") }
                }
                item { Text("Recent runs", style = MaterialTheme.typography.titleMedium) }
                items(stats.runs, key = { it.key }) { run -> RunRow(run, onRun); HorizontalDivider() }
            }
        }
    }
    if (info) AlertDialog(onDismissRequest = { info = false }, title = { Text("Recorded distance") }, text = { Text("Weeks run Monday to Sunday. This week is incomplete. Only runs in the selected period count. Weeks without records show no imported running distance.\n\n${stats.missingDistances} runs in this period have no recorded distance and are included only in the run count.") }, confirmButton = { TextButton(onClick = { info = false }) { Text("Close") } })
}
@Composable
fun RunningWeekChart(weeks: List<RunWeek>) {
    if (weeks.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary; val axes = MaterialTheme.colorScheme.outline; val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val maxKm = weeks.maxOf { it.metres / 1000 }.coerceAtLeast(1.0)
    val bounds = plotBounds(listOf(0.0, maxKm))
    val top = bounds.high.coerceAtLeast(1.0)
    var selected by remember(weeks) { mutableStateOf<RunWeek?>(null) }
    var barStart by remember { mutableFloatStateOf(0f) }; var barWidth by remember { mutableFloatStateOf(1f) }
    Text("km", style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(220.dp).testTag("running-week-chart").semantics { contentDescription = "Weekly recorded running distance in kilometres" }.pointerInput(weeks) {
        detectTapGestures { touch -> selected = weeks[((touch.x - barStart) / barWidth).toInt().coerceIn(0, weeks.lastIndex)] }
    }) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor.toArgb(); textSize = with(density) { 11.dp.toPx() * density.fontScale } }
        val ticks = (0..kotlin.math.round(top / bounds.step).toInt()).map { it * bounds.step }
        val labels = ticks.map { plotAxisText(it, bounds.step) }
        val left = labels.maxOf { paint.measureText(it) } + 12.dp.toPx(); val bottom = size.height - 36.dp.toPx() * density.fontScale; val right = size.width - 8.dp.toPx(); val chartTop = 12.dp.toPx()
        barStart = left; barWidth = (right - left) / weeks.size
        labels.forEachIndexed { index, label -> val y = bottom - (bottom-chartTop)*(ticks[index]/top).toFloat(); drawLine(axes.copy(alpha=.3f),Offset(left,y),Offset(right,y),1.dp.toPx()); drawContext.canvas.nativeCanvas.drawText(label, 0f, y, paint) }
        weeks.forEachIndexed { index, week ->
            val height = ((week.metres / 1000 / top) * (bottom - chartTop)).toFloat(); val x = left + index * barWidth
            if(height > 0) drawRect(accent.copy(alpha = if(week.current) .55f else 1f), Offset(x + barWidth*.15f, bottom-height), androidx.compose.ui.geometry.Size((barWidth*.7f).coerceAtLeast(1f), height))
        }
        drawContext.canvas.nativeCanvas.drawText(LocalDate.parse(weeks.first().monday).let { "${it.dayOfMonth} ${it.month.name.take(3).lowercase().replaceFirstChar(Char::titlecase)}" }, left, size.height-6.dp.toPx(), paint)
        val label = if (weeks.last().current) "This week*" else weeks.last().monday
        drawContext.canvas.nativeCanvas.drawText(label, (right-paint.measureText(label)).coerceAtLeast(left), size.height-6.dp.toPx(), paint)
    }
    if (weeks.last().current) Text("* So far this week", style = MaterialTheme.typography.bodySmall, color = textColor)
    selected?.let { week -> Text("Week of ${prettyDay(week.monday)}${if (week.current) " (so far)" else ""}: ${runDistanceText(week.metres)} · ${week.runs} runs", style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun RunDetailsDialog(run: RunRecord, data: AppData, model: FitNotesViewModel, busy: Boolean, onClose: () -> Unit) {
    var edit by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }; var review by remember { mutableStateOf(false) }; var sourceInfo by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                NotebookBar(run.title, onClose) { OverflowMenu { close ->
                    MenuAction("Edit run") { close(); edit = true }
                    MenuAction("Review possible matches") { close(); review = true }
                    MenuAction("Delete") { close(); delete = true }
                } }
                LazyColumn(Modifier.fillMaxSize().testTag("run-details"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(prettyDay(run.date), style = MaterialTheme.typography.bodyLarge) }
                    run.distanceMetres?.let { distance -> item { RunValue("Distance", runDistanceText(distance)) } }
                    run.preferredTime?.let { (basis, seconds) -> item { RunValue(basis.durationLabel, runDurationText(seconds)) } }
                    run.pace?.let { pace -> item { RunValue(run.preferredTime!!.first.paceLabel, paceText(pace)!!) } }
                    if (run.times.elapsed != null && run.preferredTime?.first != RunTimeBasis.ELAPSED) item { RunValue("Elapsed time", runDurationText(run.times.elapsed)) }
                    if (run.notes.isNotBlank()) item { Text("Notes", style = MaterialTheme.typography.titleMedium); Text(run.notes) }
                    if(run.sources.isNotEmpty()) item { HeartRateChart(run.sources) }
                    item { TextButton(onClick = { sourceInfo = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(run.sourceLabel) } }
                }
            }
        }
    }
    if (sourceInfo) AlertDialog(onDismissRequest = { sourceInfo = false }, title = { Text("Run details") }, text = { Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) {
        if (run.times.recorded != null) Text("FitNotes recorded duration: ${runDurationText(run.times.recorded)}. Its original time basis was not specified.")
        run.sources.forEach { detail -> val s = detail.session; val facts = RunSourceData.read(s.runData)
            Text(s.sourceName, style = MaterialTheme.typography.titleMedium)
            if(s.notes.isNotBlank())Text(s.notes)
            Text("${runLocalDate(s.start, s.startOffsetSeconds)} · Elapsed ${runDurationText((s.end-s.start)/1000.0)}")
            s.distanceMeters?.takeIf { facts.distanceVerified }?.let { Text("Source distance: ${runDistanceText(it)}") }
            facts.timerSeconds?.let { Text("Source timer: ${runDurationText(it)}") }; facts.movingSeconds?.let { Text("Source moving time: ${runDurationText(it)}") }
            facts.averageSpeedMps?.let { Text("Source average speed: $it m/s · ${facts.averageSpeedMeaning.orEmpty()}") }
            if (facts.distanceVerified.not()) Text("No unambiguous distance is available for this source session.")
        }
    } }, confirmButton = { TextButton(onClick = { sourceInfo = false }) { Text("Close") } })
    if (edit) RunEditDialog(run.copy(notes = when {
        run.key.startsWith("local:") -> data.workouts.firstOrNull { "local:${it.workout.id}"==run.key }?.workout?.notes.orEmpty()
        run.key.startsWith("set:") -> data.workouts.flatMap { it.entries }.flatMap { it.sets }.firstOrNull { "set:${it.id}"==run.key }?.comment.orEmpty()
        else -> data.imported.workouts.flatMap { it.sets }.firstOrNull { "fitnotes:${it.id}"==run.key }?.comment.orEmpty()
    }), busy, { edit = false }) { title, notes, km, seconds -> model.saveRun(run.key, title, notes, km, seconds) { edit = false } }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete this run?") }, text = { Text("Deletes it from FitNotes and recorded totals. Garmin and other source apps are unchanged. Repeat imports will keep it deleted.") }, confirmButton = { TextButton(enabled = !busy, onClick = { model.deleteRun(run.key, onClose) }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
    if (review) RunMatchDialog(run, runningRecords(data).filter { it.key != run.key && data.runLinks.none { decision -> decision.sourceKey == runPairDecisionKey(run.key,it.key) } }, busy, { review = false }, onSeparate = { target -> model.keepRunsSeparate(run.key,target){review=false} }) { target -> model.mergeRuns(run.key, target) { review = false; onClose() } }
}
@Composable private fun RunValue(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.bodySmall); Text(value, style = MaterialTheme.typography.headlineSmall) } }
@Composable
fun RunEditDialog(run: RunRecord, busy: Boolean, onClose: () -> Unit, onSave: (String,String,Double?,Double?) -> Unit) {
    var title by remember { mutableStateOf(run.title) }; var notes by remember { mutableStateOf(run.notes) }
    var km by remember { mutableStateOf(run.distanceMetres?.div(1000)?.toString().orEmpty()) }
    // Editing source elapsed/timer time must not silently turn it into a local, unspecified duration.
    var seconds by remember { mutableStateOf(run.times.recorded?.toString().orEmpty()) }; var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize()) { Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        NotebookBar("Edit run", onClose)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (run.key.startsWith("local:")) item { OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(km, { km = it }, label = { Text("Recorded distance (km)") }, keyboardOptions = KeyboardOptions(keyboardType=KeyboardType.Decimal), modifier=Modifier.fillMaxWidth()) }
            item { OutlinedTextField(seconds, { seconds = it }, label = { Text("Recorded duration (seconds)") }, supportingText = { Text("Source timing stays separate.") }, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal), modifier=Modifier.fillMaxWidth()) }
            item { OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier=Modifier.fillMaxWidth()) }
            error?.let { item { Text(it, color=MaterialTheme.colorScheme.error) } }
        }
        Button(enabled=!busy, onClick={ val d=km.trim().takeIf{it.isNotEmpty()}?.toDoubleOrNull(); val t=seconds.trim().takeIf{it.isNotEmpty()}?.toDoubleOrNull()
            if(title.isBlank() || km.isNotBlank()&&(d==null || !d.isFinite() || d<0) || seconds.isNotBlank()&&(t==null || !t.isFinite() || t<=0)) error="Check distance and duration. Leave missing values blank." else onSave(title,notes,d,t)
        }, modifier=Modifier.fillMaxWidth().padding(16.dp).heightIn(min=48.dp)) { Text("Save changes") }
    } } }
}
@Composable
fun RunMatchDialog(run: RunRecord, others: List<RunRecord>, busy: Boolean, onClose: () -> Unit, onSeparate: (String) -> Unit, onMerge: (String) -> Unit) {
    val matches = potentialRunMatches(run.copy(sources=emptyList()), others)
    var selected by remember { mutableStateOf<RunRecord?>(null) }
    if(selected != null) AlertDialog(onDismissRequest={selected=null},title={Text("Same run?")},text={Text("${run.title}\n${prettyDay(run.date)} · ${runSummary(run)}\n\n${selected!!.title}\n${prettyDay(selected!!.date)} · ${runSummary(selected!!)}\n\nBoth source records and notes are kept. The selected run supplies the recorded totals. A recovery backup is saved first.")},confirmButton={TextButton(enabled=!busy,onClick={onMerge(selected!!.key)}){Text("Same run")}},dismissButton={TextButton(enabled=!busy,onClick={onSeparate(selected!!.key)}){Text("Keep separate")}})
    else AlertDialog(onDismissRequest = onClose, title = { Text("Possible matches") }, text = {
        Column {
            if (matches.isEmpty()) Text("No potential matches found.")
            else matches.forEach { candidate ->
                TextButton(onClick = { selected = candidate }) {
                    Text("${prettyDay(candidate.date)} · ${candidate.title}\n${runSummary(candidate)}")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}
