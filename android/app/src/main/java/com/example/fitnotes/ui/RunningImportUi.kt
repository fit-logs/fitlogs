package com.example.fitnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun RunningImportDialog(model: FitNotesViewModel, onClose: () -> Unit) {
    if (android.os.Build.VERSION.SDK_INT < 28) {
        AlertDialog(onDismissRequest=onClose,title={Text("Running history")},text={Text("Health Connect requires Android 9 or later. Existing FitNotes runs remain available.")},confirmButton={TextButton(onClick=onClose){Text("Close")}})
        return
    }
    val state by model.runImport.collectAsState()
    var period by rememberSaveable { mutableStateOf("Last 3 months") }
    var access by remember { mutableStateOf<HealthAccess?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current; val owner=LocalLifecycleOwner.current
    if(HealthConnectClient.getSdkStatus(context)!=HealthConnectClient.SDK_AVAILABLE) {
        AlertDialog(onDismissRequest=onClose,title={Text("Health Connect setup")},text={Text("Set up or update Health Connect from Health sync, then return to import runs. Existing FitNotes history is available now.")},confirmButton={TextButton(onClick=onClose){Text("Close")}})
        return
    }
    var choices by remember(state.preview) { mutableStateOf<Map<String,String>>(emptyMap()) }
    val permission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { revision++ }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)revision++}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer);model.cancelRunningImport()}
    }
    LaunchedEffect(revision) { access=runCatching{HealthConnectReader(context).access()}.getOrNull() }
    fun preview() { model.previewRunningHistory(when(period){"Last 3 months"->3;"Last year"->12;else->null}) }
    Dialog(onDismissRequest={model.cancelRunningImport();onClose()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                NotebookBar("Import running history",{model.cancelRunningImport();onClose()})
                if(state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f).testTag("running-import-preview"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Text("Import completed Garmin runs from Health Connect. All available means accessible records, not necessarily Garmin’s entire archive.") }
                    item { ChoiceField("Period",listOf("Last 3 months","Last year","All available"),period){if(!state.working){period=it;model.runImport.value=RunImportState()}} }
                    if(access?.exercise!=true || access?.distance!=true) item {
                        OutlinedButton(enabled=!state.working,onClick={runCatching { permission.launch(HealthConnectReader.runningPermissions) }.onFailure { model.runImport.value=state.copy(message="Open Health Connect from Health sync to review permissions.") }},modifier=Modifier.heightIn(min=48.dp)){Text("Allow run summaries")}
                    }
                    if(access?.historyAvailable==true && access?.history!=true) item {
                        Text("Older reads need historical access. Granting it does not make Garmin send older activities.",style=MaterialTheme.typography.bodySmall)
                        TextButton(enabled=!state.working,onClick={runCatching { permission.launch(setOf(HealthConnectReader.historyPermission)) }.onFailure { model.runImport.value=state.copy(message="Historical access could not open. You can still import accessible runs.") }},modifier=Modifier.heightIn(min=48.dp)){Text("Allow historical access")}
                    }
                    item { if(state.coverage.isNotBlank())Text(state.coverage,style=MaterialTheme.typography.bodySmall)
                        if(state.working) { Text(state.stage);TextButton(onClick=model::cancelRunningImport,modifier=Modifier.heightIn(min=48.dp)){Text("Cancel")}}
                        else OutlinedButton(onClick=::preview,enabled=access?.exercise==true,modifier=Modifier.heightIn(min=48.dp)){Text("Load preview")}
                        Text(state.message)
                    }
                    state.preview?.let { p ->
                        item {
                            Text("${p.candidates.size} runs found",style=MaterialTheme.typography.titleMedium)
                            if(p.earliest!=null)Text("${prettyDay(p.earliest!!)} – ${prettyDay(p.latest!!)}")
                            Text("${p.new} new · ${p.present} already imported · ${p.review} to review")
                            if(p.ignored>0)Text("${p.ignored} previously deleted",style=MaterialTheme.typography.bodySmall)
                        }
                        items(p.candidates.filter { p.matches[it.session.key].orEmpty().isNotEmpty() },key={it.session.key}) { candidate ->
                            val run=healthRun(HealthDetail(candidate.session,candidate.samples),"health:${candidate.session.key}","")
                            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                HorizontalDivider();Text("Possible match",style=MaterialTheme.typography.labelLarge)
                                Text("From Garmin · ${run.preferredTime?.first?.durationLabel.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                                Text("${run.title} · ${prettyDay(run.date)}\n${runSummary(run)}")
                                p.matches[candidate.session.key].orEmpty().forEach { existing ->
                                    Text("In FitNotes · ${existing.preferredTime?.first?.durationLabel.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                                    Text("${existing.title} · ${prettyDay(existing.date)}\n${runSummary(existing)}",style=MaterialTheme.typography.bodyMedium)
                                    FilterChip(choices[candidate.session.key]==existing.key,{choices=choices+(candidate.session.key to existing.key)},label={Text("Same run")},modifier=Modifier.heightIn(min=48.dp),enabled=!state.working)
                                }
                                FilterChip(choices[candidate.session.key]=="separate",{choices=choices+(candidate.session.key to "separate")},label={Text("Keep separate")},modifier=Modifier.heightIn(min=48.dp),enabled=!state.working)
                            }
                        }
                        if(p.review>0)item { Text("Linking keeps source records and local edits. The existing run supplies recorded totals. A recovery backup is saved before linking.",style=MaterialTheme.typography.bodySmall) }
                    }
                }
                state.preview?.let { p -> Button(onClick={model.importRunningHistory(choices)},enabled=!state.working && p.candidates.isNotEmpty() && p.matches.all{(key,matches)->matches.isEmpty()||key in choices},modifier=Modifier.fillMaxWidth().padding(16.dp).heightIn(min=48.dp)){Text("Import runs")} }
            }
        }
    }
}
