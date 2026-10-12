package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

const val WATCH_NOTE = "Record on your watch separately. Garmin data can be attached after sync."
@Composable
fun ChoiceField(label: String, choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("$label: $selected ▾") }
        DropdownMenu(open, { open = false }) { choices.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(value); open = false }) } }
    }
}

@Composable
fun ActivityEntryDialog(busy: Boolean, model: FitNotesViewModel, onCancel: () -> Unit, onReady: (Long) -> Unit) {
    var typeKey by rememberSaveable { mutableStateOf("strength") }
    var past by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable { mutableStateOf("") }; var title by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }; var effort by rememberSaveable { mutableStateOf("Not recorded") }
    var day by rememberSaveable { mutableStateOf(todayDate()) }; var time by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }; var distance by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("km") }; var error by remember { mutableStateOf<String?>(null) }
    val type = ActivityType.from(typeKey)
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text("New session") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceField("Activity", ActivityType.entries.map { it.label }, type.label) { selected -> typeKey = ActivityType.entries.first { it.label == selected }.key }
            ChoiceField("Entry", listOf("Start session", "Log past activity"), if (past) "Log past activity" else "Start session") { past = it == "Log past activity" }
            if (type == ActivityType.OTHER) OutlinedTextField(label, { label = it.take(100) }, label = { Text("Custom activity label") }, modifier = Modifier.fillMaxWidth())
            if (past) {
                OutlinedTextField(day, { day = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(minutes, { minutes = it }, label = { Text("Duration (minutes)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(time, { time = it }, label = { Text("Optional start (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Blank start means unknown. Duration is the time you report for this activity.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(title, { title = it.take(200) }, label = { Text("Optional title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it.take(10000) }, label = { Text("Notes") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            ChoiceField("Effort", listOf("Not recorded", "Easy", "Okay", "Hard"), effort) { effort = it }
            if (past && type.key in listOf("running", "walking")) {
                OutlinedTextField(distance, { distance = it }, label = { Text("Optional distance") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceField("Unit", listOf("m", "km", "ft", "mi"), unit) { unit = it }
            }
            Text(WATCH_NOTE, style = MaterialTheme.typography.bodySmall)
            Text("The app timer records elapsed wall time. It does not start your watch, record GPS or receive live heart rate.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        try {
            require(type != ActivityType.OTHER || label.isNotBlank()) { "Enter a custom activity label." }
            val selectedEffort = effort.takeUnless { it == "Not recorded" }
            if (past) {
                require(validHistoryDate(day)) { "Enter a valid date as YYYY-MM-DD." }
                val seconds = optionalNonNegative(minutes)?.times(60) ?: error("Enter duration in minutes.")
                require(seconds > 0) { "Duration must be positive." }
                val start = if (time.isBlank()) null else {
                    require(Regex("[0-9]{2}:[0-9]{2}").matches(time) && validHistoryTime("$time:00")) { "Enter start as HH:mm, or leave it blank." }
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { isLenient = false }.parse("$day $time")!!.time
                }
                val dist = if (type.key in listOf("running", "walking")) optionalNonNegative(distance) else null
                model.logPastActivity(type.key, label, day, start, seconds, title, notes, selectedEffort, dist, if (dist == null) null else unit, onReady)
            } else model.startActivity(type.key, label, title, notes, selectedEffort, onReady)
        } catch (e: Exception) { error = e.message ?: "Check the activity fields." }
    }) { Text(if (past) "Save activity" else "Start session") } }, dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } })
}

@Composable
fun ActivityPanel(session: NotebookSession, editable: Boolean, model: FitNotesViewModel, busy: Boolean) {
    val local = session.local ?: return
    val workout = local.workout
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(workout.id, workout.isComplete) {
        while (!workout.isComplete) { now = System.currentTimeMillis(); delay(1000) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(session.activityTitle, style = MaterialTheme.typography.titleMedium)
        if (!workout.isComplete && workout.knownStart != null) {
            Text(durationText((now - workout.startedAt).coerceAtLeast(0) / 1000.0), style = MaterialTheme.typography.headlineMedium)
            Text("Started " + shortTime(workout.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(compactActivityDuration(session.duration), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(if (localDuration(workout) == null && local.health.isNotEmpty()) "Imported duration" else if (workout.sessionDate.isNotBlank()) "Reported duration" else "Elapsed time",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (workout.knownStart == null) "Local start time unknown" else "Logged time · " + compactTimeRange(workout.startedAt, workout.completedAt, includeDate = false),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        workout.effort?.let { Text("Effort · $it", style = MaterialTheme.typography.bodyMedium) }
        workout.distance?.let { Text(weightText(it) + " " + workout.distanceUnit, style = MaterialTheme.typography.titleMedium) }
        if (!workout.isComplete) Text(WATCH_NOTE, style = MaterialTheme.typography.bodySmall)
        if (editable) {
            var details by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { details = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit activity details") }
            if (details) ActivityDetailsDialog(workout, busy, { details = false }) { title, notes, effort, distance, unit ->
                model.saveActivityDetails(workout.id, title, notes, effort, distance, unit) { details = false }
            }
        }
    }
}
@Composable
fun ActivityDetailsDialog(workout: Workout, busy: Boolean, onCancel: () -> Unit, onSave: (String, String, String?, Double?, String?) -> Unit) {
    var title by rememberSaveable { mutableStateOf(workout.title) }; var notes by rememberSaveable { mutableStateOf(workout.notes) }
    var effort by rememberSaveable { mutableStateOf(workout.effort ?: "Not recorded") }
    var distance by rememberSaveable { mutableStateOf(workout.distance?.let(::weightText).orEmpty()) }
    var unit by rememberSaveable { mutableStateOf(workout.distanceUnit ?: "km") }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Activity details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it.take(200) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it.take(10000) }, label = { Text("Notes") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            ChoiceField("Effort", listOf("Not recorded", "Easy", "Okay", "Hard"), effort) { effort = it }
            if (workout.activityType in listOf("running", "walking")) {
                OutlinedTextField(distance, { distance = it }, label = { Text("Optional distance") }, modifier = Modifier.fillMaxWidth())
                ChoiceField("Unit", listOf("m", "km", "ft", "mi"), unit) { unit = it }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try { val dist = optionalNonNegative(distance); onSave(title, notes, effort.takeUnless { it == "Not recorded" }, dist, if (dist == null) null else unit) }
        catch (e: Exception) { error = e.message }
    }, enabled = !busy) { Text("Save") } }, dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } })
}

@Composable
fun MissingHeartRateInfo(busy: Boolean, onImport: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Heart rate", style = MaterialTheme.typography.titleMedium)
            Text("No heart-rate data imported for this session.", style = MaterialTheme.typography.bodyMedium)
            Text("Sync your watch, then reopen FitNotes or review imports.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onImport, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import activities") }
        }
    }
}

@Composable
fun ImportedActivityInfo(healthDetails: List<HealthDetail>) {
    healthDetails.forEach { health ->
        val s = health.session
        val samples = remember(health.samples) { health.samples.sortedBy { it.time } }
        var expanded by rememberSaveable(s.key) { mutableStateOf(false) }
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Heart rate", style = MaterialTheme.typography.titleMedium)
                Text(s.sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (samples.any { it.originPackage != s.originPackage }) Text("Heart-rate samples from another app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val heartIssue = when {
                    s.hrStatus.contains("permission", ignoreCase = true) -> if (samples.isEmpty()) "Heart-rate access is off." else "Heart-rate access is off; showing saved samples."
                    s.hrStatus.contains("failed", ignoreCase = true) -> if (samples.isEmpty()) "Heart-rate import needs a retry." else "Refresh failed; showing saved samples."
                    else -> null
                }
                heartIssue?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (s.sourceStatus.startsWith("Not returned")) Text("Source not returned at last sync; showing saved data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (samples.isEmpty()) {
                    Text("No heart-rate samples found for this session", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Imported sample summary", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val metrics = listOf("Average" to weightText(kotlin.math.round(samples.map { it.bpm }.average() * 10) / 10),
                        "Minimum" to samples.minOf { it.bpm }.toString(), "Maximum" to samples.maxOf { it.bpm }.toString())
                    val fontScale = LocalDensity.current.fontScale
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (fontScale > 1.35f || maxWidth < 300.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                metrics.forEachIndexed { index, (label, value) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        HeartRateMetric(value, index == 0, Alignment.End)
                                    }
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                metrics.forEachIndexed { index, (label, value) ->
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        HeartRateMetric(value, index == 0)
                                    }
                                }
                            }
                        }
                    }
                    Text("${samples.size} samples · " + compactTimeRange(samples.first().time, samples.last().time, includeDate = true),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Coverage may be sparse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.distanceMeters?.let { Text("Imported distance · " + weightText(it / 1000) + " km", style = MaterialTheme.typography.bodyMedium) }
                Text("Imported time · " + compactTimeRange(s.start, s.end, includeDate = true) + " · " + compactActivityDuration((s.end - s.start) / 1000.0),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Updated " + shortImportTime(s.importedAt), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (expanded) "Hide details" else "Import details")
                        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp))
                    }
                }
                if (expanded) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ImportDetail("Source app", "${s.sourceName}\n${s.originPackage}")
                    if (s.title.isNotBlank()) ImportDetail("Source title", s.title)
                    ImportDetail("Source start", timestampText(s.start))
                    ImportDetail("Source end", timestampText(s.end))
                    ImportDetail("Source duration", durationText((s.end - s.start) / 1000.0))
                    if (s.notes.isNotBlank()) ImportDetail("Source notes", s.notes)
                    if (samples.isNotEmpty()) {
                        ImportDetail("Sample coverage", "${samples.size} samples\nFirst: ${timestampText(samples.first().time)}\nLast: ${timestampText(samples.last().time)}")
                        ImportDetail("Heart-rate source", samples.map { it.originPackage }.distinct().joinToString("\n"))
                    }
                    ImportDetail("Heart-rate import", s.hrStatus)
                    ImportDetail("Distance import", s.distanceStatus)
                    if (s.distanceOrigins.isNotBlank()) ImportDetail("Distance source", s.distanceOrigins)
                    ImportDetail("Last import", timestampText(s.importedAt))
                    ImportDetail("Sync status", s.sourceStatus)
                    Text("Summaries use imported samples and may have gaps. They are not official Garmin workout statistics. Times are shown in your phone’s timezone.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HeartRateMetric(value: String, primary: Boolean, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text("bpm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImportDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun shortTime(time: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
private fun shortDate(time: Long): String {
    val year = SimpleDateFormat("yyyy", Locale.ROOT)
    val pattern = if (year.format(Date(time)) == year.format(Date())) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
}
private fun shortImportTime(time: Long): String = if (SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).let { it.format(Date(time)) == it.format(Date()) }) shortTime(time)
    else shortDate(time) + " · " + shortTime(time)
private fun compactTimeRange(start: Long, end: Long?, includeDate: Boolean): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    val startText = (if (includeDate) shortDate(start) + " · " else "") + shortTime(start)
    return if (end == null) startText else startText + "–" +
        (if (day.format(Date(start)) != day.format(Date(end))) shortDate(end) + " · " else "") + shortTime(end)
}
private fun compactActivityDuration(seconds: Double?): String {
    if (seconds == null) return "Duration unknown"
    val total = seconds.toLong(); val hours = total / 3600; val minutes = total % 3600 / 60; val remaining = total % 60
    return buildList {
        if (hours > 0) add("$hours h")
        if (minutes > 0) add("$minutes min")
        if (remaining > 0 || total == 0L) add("$remaining s")
    }.joinToString(" ")
}
