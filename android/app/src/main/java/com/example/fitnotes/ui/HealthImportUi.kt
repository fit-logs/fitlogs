package com.example.fitnotes.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun HealthImportDialog(model: FitNotesViewModel, data: AppData, busy: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT < 28) {
        AlertDialog(onDismissRequest = onClose, title = { Text("Import activities") }, text = { Text("Health Connect requires Android 9 or later. Manual activity logging remains available.") }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
        return
    }
    var days by rememberSaveable { mutableIntStateOf(7) }
    val candidates by model.healthCandidates.collectAsState(); val known by model.knownHealth.collectAsState()
    val message by model.healthMessage.collectAsState()
    var source by rememberSaveable { mutableStateOf("All sources") }
    var skipped by rememberSaveable { mutableStateOf(listOf<String>()) }
    var attaching by remember { mutableStateOf<HealthCandidate?>(null) }
    var selectedLocal by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
            if (event == Lifecycle.Event.ON_STOP) model.cancelHealthRead()
        }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val status = remember(revision) { HealthConnectClient.getSdkStatus(context) }
    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        model.inspectHealth(days)
    }
    LaunchedEffect(candidates) {
        source = if (candidates.any { isGarminOrigin(it.session.originPackage) }) GARMIN_PACKAGE else "All sources"
        skipped = emptyList()
    }
    fun openSetup() {
        val intent = if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
        else Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        runCatching { context.startActivity(intent) }.onFailure {
            model.healthMessage.value = "Health Connect settings could not open. Install/update Health Connect or find it in Android Settings, then retry."
        }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                NotebookBar("Import activities", if (!busy) onClose else null)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxSize().testTag("health-preview-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(WATCH_NOTE, style = MaterialTheme.typography.bodyMedium) }
                    item { Text(HEALTH_ACCESS_CONTEXT, style = MaterialTheme.typography.bodySmall) }
                    if (status != HealthConnectClient.SDK_AVAILABLE) item {
                        Text(if (status == HealthConnectClient.SDK_UNAVAILABLE) "Health Connect is not supported or available for this device/profile." else "Install or update Health Connect, then return here.")
                        OutlinedButton(onClick = ::openSetup) { Text("Health Connect setup") }
                    } else {
                        item {
                            ChoiceField("Period", listOf("Last 7 days", "Last 30 days"), "Last $days days") {
                                days = if (it == "Last 7 days") 7 else 30
                                model.healthCandidates.value = emptyList()
                            }
                            TextButton(enabled = !busy, onClick = {
                                runCatching { permissions.launch(HealthConnectReader.permissions) }.onFailure {
                                    model.healthMessage.value = "The Health Connect permission screen could not open. Check setup and retry."
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Grant / review read permissions") }
                            TextButton(enabled = !busy, onClick = ::openSetup, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Health Connect settings") }
                            Button(enabled = !busy, onClick = { model.inspectHealth(days) }, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("Load preview") }
                        }
                    }
                    item { Text(message) }
                    if (candidates.isNotEmpty()) item {
                        val sources = candidates.map { it.session.originPackage }.distinct().sorted()
                        ChoiceField("Source package", listOf("All sources") + sources, source) { source = it }
                        Text("Source identity is the Health Connect origin package. Garmin Connect uses $GARMIN_PACKAGE.", style = MaterialTheme.typography.bodySmall)
                    }
                    items(candidates.filter { (source == "All sources" || it.session.originPackage == source) && it.session.key !in skipped }, key = { it.session.key }) { candidate ->
                        val s = candidate.session; val prior = known.firstOrNull { it.key == s.key }
                        val matches = matchingWorkouts(s, data.workouts.map { it.workout })
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(s.title.ifBlank { ActivityType.from(s.activityType).label }, style = MaterialTheme.typography.titleMedium)
                                Text(ActivityType.from(s.activityType).label + " · " + timestampText(s.start) + "\nTo " + timestampText(s.end))
                                Text("Source interval: " + durationText((s.end - s.start) / 1000.0))
                                s.distanceMeters?.let { Text("Distance: " + weightText(it / 1000) + " km") }
                                Text("Heart rate: ${candidate.samples.size} available samples · " + s.hrStatus, style = MaterialTheme.typography.bodySmall)
                                Text(s.distanceStatus, style = MaterialTheme.typography.bodySmall)
                                Text("${s.sourceName}\n${s.originPackage}", style = MaterialTheme.typography.bodySmall)
                                Text(when { prior != null -> "Already imported" + if (prior.workoutId == null && prior.importedWorkoutId == null) " · local session deleted" else ""
                                    matches.isNotEmpty() -> "Possible match · ${matches.size} local sessions"
                                    else -> "New" }, color = MaterialTheme.colorScheme.primary)
                                if (prior != null && (prior.modifiedAt != s.modifiedAt || prior.start != s.start || prior.end != s.end)) Text("Source changed since last import. Refresh updates imported fields; local data is preserved.")
                                if (prior == null) {
                                    TextButton(enabled = !busy, onClick = { model.importHealth(candidate) {} }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import as new session") }
                                    TextButton(enabled = !busy && (data.workouts.isNotEmpty() || data.imported.workouts.isNotEmpty()), onClick = { selectedLocal = null; attaching = candidate }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Attach to local session…") }
                                } else if ((prior.workoutId != null || prior.importedWorkoutId != null)) TextButton(enabled = !busy, onClick = { model.importHealth(candidate) {} }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh imported data") }
                                TextButton(enabled = !busy, onClick = { skipped = skipped + s.key }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip") }
                            }
                        }
                    }
                    if (skipped.isNotEmpty()) item { TextButton(onClick = { skipped = emptyList() }) { Text("Show skipped activities") } }
                    item { TextButton(enabled = !busy, onClick = onClose) { Text("Done") } }
                }
            }
        }
    }
    attaching?.let { candidate ->
        val matches = matchingWorkouts(candidate.session, data.workouts.map { it.workout }).map { it.id }.toSet()
        AlertDialog(onDismissRequest = { if (!busy) attaching = null }, title = { Text("Attach imported activity?") }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item { Text("Choose a local session and confirm. Its sets, title, notes, IDs and timing are preserved. Source timing stays separate. Date-only sessions are never suggested as timed matches.") }
                items(notebookSessions(data).sortedByDescending { it.local?.workout?.id in matches }, key = { it.key }) { session ->
                    TextButton(onClick = { selectedLocal = session.key }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text((if (selectedLocal == session.key) "✓ " else "") + session.date + " · " + session.title.ifBlank { session.activityTitle } +
                            " · " + session.timeLabel + if (session.local?.workout?.id in matches) " · Possible match" else "")
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = !busy && selectedLocal != null, onClick = { model.importHealth(candidate, selectedLocal?.takeIf { it.startsWith("local:") }?.substringAfter(":")?.toLong(),
                selectedLocal?.takeIf { it.startsWith("import:") }?.substringAfter(":")) { attaching = null } }) { Text("Confirm attach") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { attaching = null }) { Text("Cancel") } })
    }
}
