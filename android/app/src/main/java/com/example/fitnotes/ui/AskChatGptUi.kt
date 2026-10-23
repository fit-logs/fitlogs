package com.example.fitnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
internal fun HandoffScreen(title: String, busy: Boolean, onClose: () -> Unit, tag: String,
    attention: String? = null, footer: @Composable () -> Unit, content: LazyListScope.() -> Unit) {
    val scroll = rememberLazyListState()
    LaunchedEffect(attention) { if (attention != null) scroll.scrollToItem(0) }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                NotebookBar(title, if (!busy) onClose else null)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f).testTag(tag), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                HorizontalDivider()
                FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { footer() }
            }
        }
    }
}

@Composable
internal fun HandoffCheck(label: String, selected: Boolean, enabled: Boolean = true, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = onSelect), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun AskChatGptDialog(model: FitNotesViewModel, data: AppData, busy: Boolean, onClose: () -> Unit) {
    val initial by model.handoffPreferences.collectAsState()
    val prepared by model.preparedHandoff.collectAsState()
    val error by model.handoffError.collectAsState()
    val photos by model.photos.collectAsState()
    var initialized by rememberSaveable { mutableStateOf(false) }
    var range by rememberSaveable { mutableStateOf("30 days") }
    var first by rememberSaveable { mutableStateOf(moveDay(todayDate(), -29)) }
    var last by rememberSaveable { mutableStateOf(todayDate()) }
    var requestKey by rememberSaveable { mutableStateOf(HandoffRequestKind.NEXT_WORKOUT.name) }
    var target by rememberSaveable { mutableStateOf(tomorrowDate()) }
    var minutes by rememberSaveable { mutableStateOf("") }
    var preference by rememberSaveable { mutableStateOf("") }
    var goals by rememberSaveable { mutableStateOf("") }
    var equipment by rememberSaveable { mutableStateOf("") }
    var constraints by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    var showBody by rememberSaveable { mutableStateOf(false) }
    var showPhotos by rememberSaveable { mutableStateOf(false) }
    var bodyKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var photoIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var compactHistory by rememberSaveable { mutableStateOf(false) }
    var heartRate by rememberSaveable { mutableStateOf(false) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(model::exportHandoff) }
    LaunchedEffect(initial.loading) {
        if (!initial.loading && !initialized) {
            goals = initial.value.goals; equipment = initial.value.equipment; constraints = initial.value.constraints
            preference = initial.value.activityPreference; minutes = initial.value.availableMinutes?.toString().orEmpty(); initialized = true
        }
    }
    val kind = HandoffRequestKind.valueOf(requestKey)
    val measurements = remember(data.imported.body, first, last) { data.imported.body.filter { it.date in first..last }.distinctBy(::measurementSelectionKey).sortedBy { it.name } }
    val availablePhotos = remember(photos, first, last) { photos.filter { it.date in first..last } }
    fun close() { model.cancelHandoff(); onClose() }
    val pages = remember(prepared?.fullText) { prepared?.fullText?.lines()?.chunked(80)?.map { it.joinToString("\n") }.orEmpty() }
    HandoffScreen(if (prepared == null) "Ask ChatGPT" else "Review prompt", busy, ::close, "handoff-list", attention = formError ?: error, footer = {
        val value = prepared
        if (value == null) Button(onClick = {
            try {
                formError = null
                val available = if (minutes.isBlank()) null else WorkoutPlanParser.positiveInteger(minutes, WorkoutPlanParser.MAX_MINUTES, "Available minutes")
                model.prepareHandoff(HandoffRequest(first, last, kind, target, HandoffPreferences(goals = goals, equipment = equipment,
                    constraints = constraints, activityPreference = preference, availableMinutes = available, lastTargetDate = target), question,
                    if (showBody) bodyKeys.toSet() else emptySet(), if (showPhotos) photoIds.toSet() else emptySet(), heartRate, compactHistory))
                focus.clearFocus(); keyboard?.hide()
            } catch (failure: IllegalArgumentException) { formError = failure.message }
        }, enabled = initialized && !busy && !data.loading && !data.imported.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Preview prompt") }
        else {
            Button(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("FitNotes question", value.fullText))
                copied = true
            }, enabled = value.canCopy && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy prompt") }
            OutlinedButton(onClick = { model.shareHandoff { intent -> context.startActivity(Intent.createChooser(intent, "Share question")) } }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (value.canCopy) "Share" else "Share attached export") }
            TextButton(onClick = { export.launch("training-context-${todayDate()}.txt") }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Export complete text") }
            TextButton(onClick = { model.preparedHandoff.value = null; model.handoffError.value = null; copied = false }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit request") }
        }
        TextButton(onClick = ::close, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
    }) {
        (formError ?: error)?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        val value = prepared
        if (value == null) {
            if (!initialized) item { Text("Loading saved preferences…") }
            else {
                item { ChoiceField("History", listOf("7 days", "30 days", "Custom"), range) { selected ->
                    range = selected; photoIds = emptyList(); bodyKeys = emptyList()
                    if (selected != "Custom") { first = moveDay(todayDate(), if (selected == "7 days") -6 else -29); last = todayDate() }
                } }
                if (range == "Custom") item {
                    OutlinedTextField(first, { first = it; photoIds = emptyList(); bodyKeys = emptyList() }, label = { Text("From (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(last, { last = it; photoIds = emptyList(); bodyKeys = emptyList() }, label = { Text("Through (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item { Text("$first through $last · inclusive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { ChoiceField("Request", HandoffRequestKind.entries.map { it.label }, kind.label) { selected -> requestKey = HandoffRequestKind.entries.first { it.label == selected }.name } }
                if (kind == HandoffRequestKind.CUSTOM) item { OutlinedTextField(question, { question = it }, label = { Text("Your question") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(target, { target = it }, label = { Text("Target date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(minutes, { minutes = it }, label = { Text("Available minutes (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { ChoiceField("Activity preference", listOf("No preference") + ActivityType.entries.map { it.label }, if (preference.isBlank()) "No preference" else ActivityType.from(preference).label) { selected -> preference = ActivityType.entries.firstOrNull { it.label == selected }?.key.orEmpty() } }
                item { Text("History covers all activities. V2 imports strength plans; review/custom requests can ask for advice about other activities.", style = MaterialTheme.typography.bodySmall) }
                item { OutlinedTextField(goals, { goals = it }, label = { Text("Goals") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(equipment, { equipment = it }, label = { Text("Equipment") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(constraints, { constraints = it }, label = { Text("Exercise constraints") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()) }
                item { Text("Goals, equipment, constraints, activity preference and time are remembered on this device when you preview. Optional data selections start off.", style = MaterialTheme.typography.bodySmall) }
                item { Text("Optional data", style = MaterialTheme.typography.titleMedium); HandoffCheck("Select body measurements", showBody) { showBody = it; if (!it) bodyKeys = emptyList() } }
                if (showBody) {
                    if (measurements.isEmpty()) item { Text("No body measurements in this range.") }
                    items(measurements, key = { measurementSelectionKey(it) }) { measurement ->
                        val key = measurementSelectionKey(measurement)
                        HandoffCheck("${measurement.name} (${measurement.unitName.ifBlank { measurement.unit }})", key in bodyKeys) { selected -> bodyKeys = if (selected) bodyKeys + key else bodyKeys - key }
                    }
                }
                item { HandoffCheck("Select photos", showPhotos) { showPhotos = it; if (!it) photoIds = emptyList() } }
                if (showPhotos) {
                    item { Text("Select up to 8 photos. Share includes oriented JPEG copies; Copy includes text only.", style = MaterialTheme.typography.bodySmall) }
                    if (availablePhotos.isEmpty()) item { Text("No progress photos in this range. Add them in Body → Photos first.") }
                    items(availablePhotos, key = { it.id }) { photo ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PhotoImage(model.photoRepository, photo, Modifier.size(56.dp), thumbnail = true)
                            Column(Modifier.weight(1f)) {
                                HandoffCheck("${photo.date} · ${photo.angle}", photo.id in photoIds, enabled = photo.id in photoIds || photoIds.size < PreparedHandoff.MAX_SHARED_PHOTOS) { selected -> photoIds = if (selected) photoIds + photo.id else photoIds - photo.id }
                                if (photo.note.isNotBlank()) Text(photo.note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item { HandoffCheck("Compact strength summary (omit raw sets)",compactHistory,!busy){compactHistory=it} }
            item { HandoffCheck("Include raw heart-rate samples", heartRate) { heartRate = it }; Text("Includes available cached samples from imported exercise sessions. Coverage may be sparse; this is not a complete daily series.", style = MaterialTheme.typography.bodySmall) }
                if (data.imported.loading) item { Text("Loading the full history; preview is unavailable until complete.") }
            }
        } else {
            item {
                Text("${value.bytes / 1024 + 1} KiB · complete prompt", style = MaterialTheme.typography.labelLarge)
                Text("Review all text before copying or sharing. You choose the ChatGPT app in Android's share sheet and submit the question yourself.", style = MaterialTheme.typography.bodySmall)
                if (copied) Text("Prompt copied. Paste it into ChatGPT.", color = MaterialTheme.colorScheme.primary)
                if (value.photos.isNotEmpty()) Text("Copy is text only. Share attaches ${value.photos.size} selected photos; otherwise attach them manually in ChatGPT.", style = MaterialTheme.typography.bodySmall)
                if (!value.canCopy) Text("This prompt exceeds the 200,000-byte copy/share-text limit. Choose a shorter range, or share/export the full text as an attachment. No history is omitted.", color = MaterialTheme.colorScheme.primary)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(pages.indices.toList(), key = { it }) { index -> Text(pages[index], style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
