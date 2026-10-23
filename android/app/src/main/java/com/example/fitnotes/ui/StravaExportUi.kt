package com.example.fitnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import java.time.ZoneId

@Composable
fun StravaExportDialog(model: FitNotesViewModel, data: AppData, onClose: () -> Unit) {
    val headers by model.stravaExportHeaders.collectAsState()
    val preview by model.stravaExportPreview.collectAsState()
    val known by model.stravaExportKnown.collectAsState()
    val working by model.stravaExportWorking.collectAsState()
    val message by model.stravaExportMessage.collectAsState()
    var owner by rememberSaveable { mutableStateOf("") }
    var timezone by rememberSaveable { mutableStateOf("Offsets in file") }
    var otherZone by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }; var last by rememberSaveable { mutableStateOf("") }
    var timeUnit by rememberSaveable { mutableStateOf("Confirm units") }
    var distanceUnit by rememberSaveable { mutableStateOf("Confirm units") }
    var elevationUnit by rememberSaveable { mutableStateOf("Confirm units") }
    var elapsed by rememberSaveable(headers) { mutableStateOf(StravaOwnerExport.uniqueColumn(headers, "Elapsed Time")) }
    var distance by rememberSaveable(headers) { mutableStateOf(StravaOwnerExport.uniqueColumn(headers, "Distance")) }
    var moving by rememberSaveable(headers) { mutableStateOf(StravaOwnerExport.uniqueColumn(headers, "Moving Time")) }
    var elevation by rememberSaveable(headers) { mutableStateOf(StravaOwnerExport.uniqueColumn(headers, "Elevation Gain")) }
    var avg by rememberSaveable(headers) { mutableStateOf(StravaOwnerExport.uniqueColumn(headers, "Average Heart Rate")) }
    var max by rememberSaveable(headers) { mutableStateOf(StravaOwnerExport.uniqueColumn(headers, "Max Heart Rate")) }
    val options = StravaExportOptions(owner.trim(), when (timezone) { "Offsets in file" -> ""; "UTC" -> "UTC"; "Device timezone" -> ZoneId.systemDefault().id; else -> otherZone.trim() },
        elapsed, distance, moving, elevation, avg, max, timeUnit, distanceUnit, elevationUnit, first.trim(), last.trim())
    var reviewedOptions by remember { mutableStateOf<StravaExportOptions?>(null) }
    var choices by remember(preview) { mutableStateOf(preview?.candidates.orEmpty().map { candidate ->
        val prior = known.firstOrNull { it.key == candidate.session.key }
        val matches = preview?.matches?.get(candidate.session.key).orEmpty()
        StravaImportChoice(candidate, include = if (prior != null) prior.workoutId != null || prior.importedWorkoutId != null else matches.isEmpty())
    }) }
    var attaching by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var errors by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::readStravaFile) }
    fun close() { if (working) model.cancelStravaFile(); onClose() }
    fun choose(key: String, change: (StravaImportChoice) -> StravaImportChoice) { choices = choices.map { if (it.candidate.session.key == key) change(it) else it } }
    val selected = choices.count { it.include }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                NotebookBar("Strava activity files", ::close)
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(message, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxSize().testTag("strava-export-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Offline import from your own Strava account export.", style = MaterialTheme.typography.titleMedium)
                            Text("On Strava.com, use Settings → My Account → Download your account. Extract the archive, then choose activities.csv here. Live account connection is unavailable for this archive workflow.")
                            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !working) { Text("Choose activities.csv") }
                        }
                    }
                    if (headers.isNotEmpty()) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(owner, { owner = it }, label = { Text("Owner key (athlete ID or stable label)") }, enabled = !working, modifier = Modifier.fillMaxWidth().testTag("strava-owner"), singleLine = true)
                            Text("Reuse this key for every file from the same owner. The label is not an authenticated account connection.", style = MaterialTheme.typography.bodySmall)
                            ChoiceField("Timestamp timezone", listOf("Offsets in file", "UTC", "Device timezone", "Other timezone"), timezone) { timezone = it }
                            if (timezone == "Other timezone") OutlinedTextField(otherZone, { otherZone = it }, label = { Text("Timezone ID, e.g. Pacific/Auckland") }, singleLine = true)
                            Text("Timestamps with offsets keep them. Timestamps without offsets need a confirmed timezone; ambiguous daylight-saving times require an explicit offset.", style = MaterialTheme.typography.bodySmall)
                            CsvMetricColumn(headers, "Elapsed Time", elapsed) { elapsed = it }
                            ChoiceField("Time units", listOf("Confirm units", "seconds", "minutes", "h:m:s"), timeUnit) { timeUnit = it }
                            CsvMetricColumn(headers, "Distance", distance) { distance = it }
                            if (distance != null) ChoiceField("Distance units", listOf("Confirm units", "m", "km", "ft", "mi"), distanceUnit) { distanceUnit = it }
                            CsvMetricColumn(headers, "Moving Time", moving) { moving = it }
                            CsvMetricColumn(headers, "Elevation Gain", elevation) { elevation = it }
                            if (elevation != null) ChoiceField("Elevation units", listOf("Confirm units", "m", "ft"), elevationUnit) { elevationUnit = it }
                            CsvMetricColumn(headers, "Average Heart Rate", avg) { avg = it }
                            CsvMetricColumn(headers, "Max Heart Rate", max) { max = it }
                            if (avg != null || max != null) Text("Selected heart-rate columns are source-reported bpm summaries; no per-second samples are created.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(first, { first = it }, label = { Text("From date (YYYY-MM-DD, optional)") }, singleLine = true)
                            OutlinedTextField(last, { last = it }, label = { Text("Through date (YYYY-MM-DD, optional)") }, singleLine = true)
                            Text("Blank dates include all valid activities in the file. Check duplicate column numbers and their units against your export.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { reviewedOptions = options; model.previewStravaFile(options) }, enabled = !working) { Text("Load activity preview") }
                        }
                    }
                    if (preview != null) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Review activities", style = MaterialTheme.typography.titleLarge)
                            if (reviewedOptions != options) Text("Settings changed. Load the preview again before importing.")
                            Text("Possible interval/type/distance matches need an explicit attachment or Keep separate. Date or title alone never links activities.")
                            Button(onClick = { confirming = true }, enabled = !working && selected > 0 && reviewedOptions == options) { Text("Import $selected selected activities") }
                            if (preview!!.issues.isNotEmpty()) TextButton(onClick = { errors = !errors }) { Text(if (errors) "Hide row errors" else "Show ${preview!!.issues.size} row errors") }
                            if (errors && preview!!.issues.size > 100) Text("Showing the first 100 of ${preview!!.issues.size} row errors. Correct these and reload the file.")
                            if (errors) preview!!.issues.take(100).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    items(choices, key = { it.candidate.session.key }) { choice ->
                        val s = choice.candidate.session; val prior = known.firstOrNull { it.key == s.key }
                        val ignored = prior != null && prior.workoutId == null && prior.importedWorkoutId == null
                        val matches = preview?.matches?.get(s.key).orEmpty()
                        OutlinedCard(Modifier.fillMaxWidth().testTag("strava-row:${s.recordId}")) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(s.title.ifBlank { sourceSport(s) }, style = MaterialTheme.typography.titleMedium)
                                Text("${sourceSport(s)} · ${sourceTime(s.start, s.startOffsetSeconds)}")
                                Text("Elapsed ${durationText((s.end - s.start) / 1000.0)}" + s.distanceMeters?.let { " · ${weightText(it / 1000)} km" }.orEmpty())
                                s.movingSeconds?.let { Text("Source moving time · ${durationText(it)}") }
                                if (ignored) Text("Previously ignored/deleted. This activity stays excluded.")
                                else {
                                    if (prior != null || matches.isEmpty() || choice.keepSeparate || choice.attachTo != null || choice.attachImported != null)
                                        HandoffCheck("Include activity ${s.recordId}", choice.include) { include -> if (!working) choose(s.key) { it.copy(include = include) } }
                                    when {
                                        prior != null -> Text("Known source ID · refresh existing attachment")
                                        choice.attachTo != null || choice.attachImported != null -> Text("Attach to selected workout · local data retained")
                                        choice.keepSeparate -> Text("Keep as a separate activity (reviewed)")
                                        matches.isNotEmpty() -> Text("${matches.size} possible matches or prior deletion decisions need review")
                                        else -> Text("New activity")
                                    }
                                    if (prior == null) {
                                        matches.forEach { match ->
                                            if (match.workoutId != null || match.importedWorkoutId != null) TextButton(enabled = !working, onClick = {
                                                choose(s.key) { it.copy(include = true, attachTo = match.workoutId, attachImported = match.importedWorkoutId, keepSeparate = false) }
                                            }) { Text("Attach: ${match.label}") }
                                            else Text(match.label, style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(enabled = !working, onClick = { search = ""; attaching = s.key }) { Text("Choose another workout to attach") }
                                        TextButton(enabled = !working, onClick = { choose(s.key) { it.copy(include = true, attachTo = null, attachImported = null, keepSeparate = true) } }) { Text("Keep separate") }
                                        TextButton(enabled = !working, onClick = { choose(s.key) { it.copy(include = false) }; model.ignoreStravaFile(choice.candidate) }) { Text("Ignore activity ${s.recordId}") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("Import selected activities?") }, text = {
        Text("Process $selected activities using the reviewed owner, timezone and units. Attachments preserve local notes, sets and timing. Completed batches remain saved if you stop; reselecting the file safely refreshes the same source IDs.")
    }, confirmButton = { TextButton(onClick = { confirming = false; model.importStravaFile(choices) }, enabled = !working) { Text("Import", modifier = Modifier.testTag("confirm-strava-import")) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } })
    attaching?.let { key ->
        val sessions = notebookSessions(data).filter { search.isBlank() || (it.date + " " + it.title + " " + it.activityTitle).contains(search, true) }
        AlertDialog(onDismissRequest = { attaching = null }, title = { Text("Attach to a saved workout") }, text = {
            Column {
                OutlinedTextField(search, { search = it }, label = { Text("Search date or title") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(sessions.take(100), key = { it.key }) { session ->
                        TextButton(onClick = { choose(key) { it.copy(include = true, attachTo = session.local?.workout?.id, attachImported = session.imported?.workout?.id, keepSeparate = false) }; attaching = null }) {
                            Text("${session.date} · ${session.title.ifBlank { session.activityTitle }} · ${session.timeLabel}")
                        }
                    }
                    if (sessions.size > 100) item { Text("Search to narrow ${sessions.size} saved workouts.") }
                }
            }
        }, confirmButton = { TextButton(onClick = { attaching = null }) { Text("Cancel") } })
    }
}
@Composable
private fun CsvMetricColumn(headers: List<String>, name: String, value: Int?, change: (Int?) -> Unit) {
    val choices = StravaOwnerExport.columnChoices(headers, name)
    val labels = choices.associateWith { "Column ${it + 1}" }
    ChoiceField(name + " column", listOf("Not selected") + choices.map { labels.getValue(it) }, value?.let { labels[it] }.orEmpty().ifBlank { "Not selected" }) { label -> change(labels.entries.firstOrNull { it.value == label }?.key) }
}
